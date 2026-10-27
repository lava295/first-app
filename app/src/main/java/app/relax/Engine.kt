package app.relax

import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

const val H = 3_600_000L

data class Posting(
    val id: String,
    val portal: String,
    val title: String,
    val company: String,
    val location: String,
    val url: String,
    val postedAt: Long,
    val approx: Boolean,          // true = portal gave no date, postedAt = when Relax first saw it
    val exp: String = "",
    val salary: String = "",
    val tier: Int = 3,
    val score: Int = 0,
    val firstSeen: Long = System.currentTimeMillis(),
    val applied: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("portal", portal).put("title", title).put("company", company)
        .put("location", location).put("url", url).put("postedAt", postedAt).put("approx", approx)
        .put("exp", exp).put("salary", salary).put("tier", tier).put("score", score)
        .put("firstSeen", firstSeen).put("applied", applied)

    companion object {
        fun fromJson(o: JSONObject) = Posting(
            o.getString("id"), o.getString("portal"), o.getString("title"), o.optString("company"),
            o.optString("location"), o.getString("url"), o.optLong("postedAt"), o.optBoolean("approx"),
            o.optString("exp"), o.optString("salary"), o.optInt("tier", 3), o.optInt("score"),
            o.optLong("firstSeen"), o.optBoolean("applied"),
        )
    }
}

data class Profile(
    val id: String,
    val name: String,
    val queries: List<String>,
    val ops: Boolean = false,                 // use the Ops priority engine
    val match: List<String> = emptyList(),    // title must contain one of these (sub-roles)
    val builtIn: Boolean = false,
)

class IngestResult(val jobs: List<Posting>, val status: Map<String, String>)

val OPS_QUERIES = listOf(
    "dubbing operations", "content operations", "content ops", "operations media",
    "subtitle editor", "subtitling", "QC quality control media", "team lead operations",
    "operations analyst", "operations executive media",
)

val BUILTIN = listOf(
    Profile("ops", "My Ops roles", OPS_QUERIES, true, emptyList(), true),
    Profile("dubbing", "Dubbing Operations", listOf("dubbing operations", "dubbing ops", "dubbing coordinator"), true, listOf("dubbing"), true),
    Profile("content", "Content Ops", listOf("content operations", "content ops", "content operation"), true, listOf("content op"), true),
    Profile("operations", "Operations (Media)", listOf("operations media", "operations executive media", "media operations"), true, listOf("operation", "ops"), true),
    Profile("subtitle", "Subtitle Editor", listOf("subtitle editor", "subtitling", "subtitle"), true, listOf("subtitle"), true),
    Profile("qc", "QC / Quality Control", listOf("quality control media", "QC media", "quality check video"), true, listOf("qc", "quality"), true),
    Profile("lead", "Team Lead", listOf("team lead operations", "operations lead", "team leader content"), true, listOf("team lead", "team leader", "ops lead", "operations lead"), true),
    Profile("analyst", "Operations Analyst", listOf("operations analyst", "ops analyst media"), true, listOf("operations analyst", "ops analyst"), true),
)

private val NON = Regex("[^a-z0-9\\s]")
private val WS = Regex("\\s+")
fun norm(s: String?): String = (s ?: "").lowercase().replace(NON, " ").replace(WS, " ").trim()

/** word-start match: "operation" matches "operations", "qc" does not match "xqc" */
fun has(blob: String, k: String): Boolean = " $blob".contains(" $k")
fun hasAny(blob: String, ks: List<String>): Boolean = ks.any { has(blob, it) }

private val ALIAS = mapOf(
    "bangalore" to "bengaluru", "gurgaon" to "gurugram", "bombay" to "mumbai",
    "madras" to "chennai", "calcutta" to "kolkata", "poona" to "pune",
)
fun cityNorm(s: String?): String = ALIAS.entries.fold(norm(s)) { a, e -> a.replace(e.key, e.value) }

private val T1_STRONG = listOf(
    "dubbing operations", "dubbing ops", "content operations", "content ops", "content operation",
    "media operations", "post production operations", "post-production operations", "post production ops",
    "ott operations", "broadcast operations", "production operations",
)
private val T1_GENERIC = listOf(
    "operations executive", "operations manager", "operations specialist", "ops manager", "ops executive",
)
private val T2_STRONG = listOf("subtitle editor", "subtitling", "subtitle", "dubbing lead")
private val T2_GENERIC = listOf(
    "qc", "quality check", "quality control", "quality assurance", "team lead", "team leader",
    "operations lead", "ops lead", "content lead",
)
private val T3 = listOf(
    "operations analyst", "ops analyst", "operations coordinator", "operations associate",
    "operations intern", "content coordinator", "workflow operations", "process operations",
)
private val MEDIA = listOf(
    "media", "entertainment", "dubbing", "subtitling", "post production", "post-production", "ott",
    "streaming", "animation", "vfx", "broadcast", "film", "tv", "television", "content studio", "production house",
)
private val STOP = setOf("a", "an", "the", "and", "of", "for", "in", "at", "to", "job", "jobs")

object Engine {
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        private val cookieJar = object : okhttp3.CookieJar {
        private val store = mutableMapOf<String, List<okhttp3.Cookie>>()
        override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) {
            store[url.host] = cookies
        }
        override fun loadForRequest(url: okhttp3.HttpUrl): List<okhttp3.Cookie> =
            store[url.host] ?: emptyList()
    }
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .cookieJar(cookieJar)
        .build()

    val ORDER: Comparator<Posting> = compareBy<Posting>({ it.tier }, { -it.score }, { -it.postedAt })

    // ---------- scoring ----------
    private fun scoreOps(j: Posting): Posting {
        val title = norm(j.title)
        val full = "$title ${norm(j.company)}"
        val media = if (hasAny(full, MEDIA)) 50 else 0
        if (hasAny(full, T1_STRONG) || (media > 0 && hasAny(full, T1_GENERIC))) {
            var s = 900 + media
            if (has(title, "dubbing") && has(title, "operation")) s += 40
            if (has(title, "content") && has(title, "operation")) s += 30
            return j.copy(tier = 1, score = s)
        }
        if (hasAny(full, T1_GENERIC)) return j.copy(tier = 2, score = 600)
        if (hasAny(full, T2_STRONG) || (media > 0 && hasAny(full, T2_GENERIC))) {
            var s = 700 + media
            if (has(title, "subtitle")) s += 25
            if (has(title, "qc") || has(title, "quality")) s += 20
            if (has(title, "team lead") || has(title, "team leader")) s += 15
            return j.copy(tier = 2, score = s)
        }
        if (hasAny(full, T3)) return j.copy(tier = 3, score = 500 + media)
        if (hasAny(title, listOf("operation", "ops")) && media > 0) return j.copy(tier = 1, score = 650 + media)
        if (hasAny(full, T2_GENERIC)) return j.copy(tier = 3, score = 400)
        return j.copy(tier = 3, score = 100 + media)
    }

    private fun scoreCustom(j: Posting, p: Profile): Posting {
        val title = norm(j.title)
        var tier = 3
        var sc = 100
        for (q in p.queries) {
            val qn = norm(q)
            val words = qn.split(" ").filter { it.isNotEmpty() && it !in STOP }
            if (words.isEmpty()) continue
            val hit = words.count { has(title, it) }
            if (has(title, qn) || hit == words.size) {
                tier = 1; sc = maxOf(sc, 900 + words.size)
            } else if (hit > 0 && tier == 3) {
                tier = 2; sc = maxOf(sc, 600 + hit * 10)
            }
        }
        return j.copy(tier = tier, score = sc)
    }

    fun score(j: Posting, p: Profile) = if (p.ops) scoreOps(j) else scoreCustom(j, p)

    // ---------- dedupe / merge / filter ----------
    fun key(j: Posting): String {
        val c = norm(j.company)
        if (c.isEmpty() || c == "unknown" || c == "see listing") return j.id
        return norm(j.title) + "|" + c + "|" + cityNorm(j.location).substringBefore(" ")
    }

    private fun dedupe(l: List<Posting>): List<Posting> {
        val m = LinkedHashMap<String, Posting>()
        for (j in l) {
            val k = key(j)
            val o = m[k]
            if (o == null || (o.approx && !j.approx)) m[k] = j
        }
        return m.values.toList()
    }

    fun merge(cache: List<Posting>, fresh: List<Posting>): List<Posting> {
        val now = System.currentTimeMillis()
        val byId = LinkedHashMap<String, Posting>()
        cache.forEach { byId[it.id] = it }
        for (j in fresh) {
            val o = byId[j.id]
            byId[j.id] = if (o == null) j else j.copy(
                firstSeen = o.firstSeen,
                postedAt = if (j.approx) o.postedAt else j.postedAt,
                approx = if (j.approx) o.approx else false,
                applied = o.applied,
            )
        }
        return dedupe(byId.values.toList())
            .filter { now - it.postedAt < 30 * 24 * H }
            .sortedWith(ORDER).take(600)
    }

    fun filter(
        list: List<Posting>,
        hours: Long,
        portal: String,
        loc: String,
        hideWeak: Boolean,
        newest: Boolean,
    ): List<Posting> {
        val since = System.currentTimeMillis() - hours * H
        val l = cityNorm(loc)
        val out = list.filter {
            it.postedAt >= since &&
                (portal == "all" || it.portal == portal) &&
                (l.isEmpty() || l in cityNorm(it.location)) &&
                (!hideWeak || it.score >= 300)
        }
        return if (newest) out.sortedByDescending { it.postedAt } else out
    }

    // ---------- date parsing ----------
    private val AGO = Regex("(\\d+)\\s*\\+?\\s*(minute|min|hour|hr|day|week|month)")

    fun parseAgo(t0: String?): Long? {
        val t = (t0 ?: "").lowercase().trim()
        if (t.isEmpty()) return null
        val now = System.currentTimeMillis()
        if ("just now" in t || "moment" in t || "today" in t) return now
        if ("few hours" in t) return now - 3 * H
        if ("yesterday" in t) return now - 24 * H
        val m = AGO.find(t) ?: return null
        val n = m.groupValues[1].toLongOrNull() ?: return null
        val unit = when (m.groupValues[2]) {
            "minute", "min" -> H / 60
            "hour", "hr" -> H
            "day" -> 24 * H
            "week" -> 168 * H
            else -> 720 * H
        }
        return now - n * unit
    }

    private fun parseDate(s: String): Long? = runCatching {
        LocalDate.parse(s.take(10)).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    }.getOrNull()

    // ---------- http ----------
    private suspend fun get(url: String, extra: Map<String, String> = emptyMap()): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            val rb = Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("Accept-Language", "en-IN,en;q=0.9")
                .header(
                    "Accept",
                    extra["Accept"]
                        ?: "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8",
                )
                .header("Referer", "https://www.naukri.com/")
                .header("Origin", "https://www.naukri.com")
                .header("sec-fetch-dest", "empty")
                .header("sec-fetch-mode", "cors")
                .header("sec-fetch-site", "same-origin")
            extra.forEach { (k, v) -> rb.header(k, v) }
            http.newCall(rb.build()).execute().use { it.code to (it.body?.string() ?: "") }
        }

    private fun sha(s: String) =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(20)

    private fun slug(q: String) = q.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

    private fun mk(
        portal: String,
        title: String,
        company: String,
        loc: String,
        url: String,
        ts: Long?,
        exp: String = "",
        sal: String = "",
    ): Posting {
        val u = url.substringBefore("?")
        return Posting(
            sha("$portal|$u"), portal, title.trim(), company.trim().ifEmpty { "Unknown" }, loc.trim(), u,
            ts ?: System.currentTimeMillis(), ts == null, exp.trim(), sal.trim(),
        )
    }

    // ---------- scrapers (one query, all pages) ----------
    private suspend fun linkedin(q: String, pages: Int, windowSec: Long): Pair<List<Posting>, String?> {
        val out = mutableListOf<Posting>()
        for (pg in 0 until pages) {
            val url = "https://www.linkedin.com/jobs-guest/jobs/api/seeMoreJobPostings/search".toHttpUrl().newBuilder()
                .addQueryParameter("keywords", q)
                .addQueryParameter("location", "India")
                .addQueryParameter("f_TPR", "r$windowSec")
                .addQueryParameter("sortBy", "DD")
                .addQueryParameter("start", (pg * 25).toString())
                .build().toString()
            var (code, body) = get(url)
            if (code == 429) {
                delay(3000)
                val r = get(url); code = r.first; body = r.second
            }
            if (code == 400 && pg > 0) break
            if (code != 200) return out to (if (code == 429) "rate-limited" else "HTTP $code")
            val cards = Jsoup.parse(body).select("li")
            if (cards.isEmpty()) break
            for (c in cards) {
                val title = c.selectFirst(".base-search-card__title")?.text().orEmpty()
                val href = c.selectFirst("a.base-card__full-link, a[href*='/jobs/view/']")?.attr("href").orEmpty()
                if (title.isEmpty() || href.isEmpty()) continue
                val t = c.selectFirst("time")
                val ts = parseAgo(t?.text()) ?: t?.attr("datetime")?.let { parseDate(it) }
                out += mk(
                    "linkedin", title,
                    c.selectFirst(".base-search-card__subtitle")?.text().orEmpty(),
                    c.selectFirst(".job-search-card__location")?.text().orEmpty(), href, ts,
                )
            }
            delay(700)
        }
        return out to null
    }

    /**
     * Naukri strategy (permanent fix for 406):
     * 1) Mobile Android client path (src=jobsearchAndroid) — no web recaptcha on most IPs
     * 2) Desktop web path with full browser headers — fallback
     * 3) HTML page scrape — last resort
     * Also: session cookie warm-up, UA rotation, exponential backoff on 406/429.
     */
    private suspend fun naukri(q: String, pages: Int): Pair<List<Posting>, String?> {
        val out = mutableListOf<Posting>()
        val slug = slug(q)
        var err: String? = null

        // warm session once (sets _t_ds / J cookies Naukri checks)
        runCatching {
            get(
                "https://www.naukri.com/",
                mapOf("Accept" to "text/html,application/xhtml+xml"),
            )
        }
        delay(300 + (0..200).random().toLong())

        val paths = listOf(
            // Path A: Android app API — bypasses web recaptcha on the majority of networks
            "jobsearchAndroid" to mapOf(
                "appid" to "109",
                "systemid" to "109",
                "clientid" to "d3skt0p",
                "Accept" to "application/json",
                "Content-Type" to "application/json",
                "User-Agent-Override" to "okhttp/4.12.0",
            ),
            // Path B: desktop web client
            "jobsearchDesk" to mapOf(
                "appid" to "109",
                "systemid" to "109",
                "clientid" to "d3skt0p",
                "Accept" to "application/json",
                "Content-Type" to "application/json",
            ),
        )

        var pathIdx = 0
        var consecutiveBlocks = 0

        for (pg in 1..pages) {
            var pageGot = false
            while (pathIdx < paths.size && !pageGot) {
                val (srcName, hdrs) = paths[pathIdx]
                val api = "https://www.naukri.com/jobapi/v3/search".toHttpUrl().newBuilder()
                    .addQueryParameter("noOfResults", "20")
                    .addQueryParameter("urlType", "search_by_keyword")
                    .addQueryParameter("searchType", "adv")
                    .addQueryParameter("keyword", q)
                    .addQueryParameter("location", "india")
                    .addQueryParameter("pageNo", pg.toString())
                    .addQueryParameter("seoKey", "$slug-jobs-in-india")
                    .addQueryParameter("src", srcName)
                    .addQueryParameter("k", q)
                    .addQueryParameter("l", "india")
                    .build().toString()

                // optional UA override for android path
                val extra = hdrs.toMutableMap()
                val uaOverride = extra.remove("User-Agent-Override")
                val (c, b) = if (uaOverride != null) {
                    getWithUa(api, extra, uaOverride)
                } else {
                    get(api, extra)
                }

                when (c) {
                    200 -> {
                        val arr = runCatching { JSONObject(b).optJSONArray("jobDetails") }.getOrNull()
                        if (arr != null && arr.length() > 0) {
                            for (i in 0 until arr.length()) {
                                val o = arr.optJSONObject(i) ?: continue
                                val title = o.optString("title")
                                val jd = o.optString("jdURL")
                                if (title.isBlank() || jd.isBlank()) continue
                                val url = if (jd.startsWith("http")) jd else "https://www.naukri.com$jd"
                                var exp = ""; var sal = ""; var loc = ""
                                val ph = o.optJSONArray("placeholders")
                                if (ph != null) for (k in 0 until ph.length()) {
                                    val p = ph.optJSONObject(k) ?: continue
                                    when (p.optString("type")) {
                                        "experience" -> exp = p.optString("label")
                                        "salary" -> sal = p.optString("label")
                                        "location" -> loc = p.optString("label")
                                    }
                                }
                                val created = o.optLong("createdDate", 0L)
                                val ts: Long? =
                                    if (created > 0) created else parseAgo(o.optString("footerPlaceholderLabel"))
                                out += mk("naukri", title, o.optString("companyName"), loc, url, ts, exp, sal)
                            }
                            pageGot = true
                            consecutiveBlocks = 0
                            err = null
                        } else {
                            // empty page on this path — try next path once, then stop paging
                            pathIdx++
                        }
                    }
                    406, 403 -> {
                        consecutiveBlocks++
                        err = if (c == 406) "recaptcha (406) path=$srcName" else "HTTP $c path=$srcName"
                        // exponential backoff then switch path
                        delay((1500L * consecutiveBlocks).coerceAtMost(6000L) + (0..500).random())
                        pathIdx++
                    }
                    429 -> {
                        err = "rate-limited"
                        delay(3000 + (0..1000).random().toLong())
                        // retry same path once by not incrementing pathIdx
                        consecutiveBlocks++
                        if (consecutiveBlocks >= 3) pathIdx++
                    }
                    else -> {
                        err = "HTTP $c"
                        pathIdx++
                    }
                }
            }

            if (!pageGot) {
                // Path C: HTML fallback (rarely has cards, but cheap try)
                val pageUrl = if (pg == 1) "https://www.naukri.com/$slug-jobs-in-india"
                else "https://www.naukri.com/$slug-jobs-in-india-$pg"
                val (hc, hb) = get(pageUrl)
                if (hc == 200) {
                    val cards = Jsoup.parse(hb).select(
                        "div.srp-jobtuple-wrapper, article.jobTuple, div[data-job-id], a[href*='/job-listings']",
                    )
                    for (card in cards) {
                        val a = card.selectFirst("a.title, a[class*='title'], a[href*='/job-listings']")
                        val title = a?.text().orEmpty()
                        val href = a?.attr("href").orEmpty()
                        if (title.length < 5 || href.isEmpty()) continue
                        val full = if (href.startsWith("http")) href else "https://www.naukri.com$href"
                        out += mk(
                            "naukri", title,
                            card.selectFirst("a.comp-name, a.subTitle, .comp-name")?.text().orEmpty(),
                            card.selectFirst(".locWdth, .loc-wrap")?.text().orEmpty(), full,
                            parseAgo(card.selectFirst(".job-post-day, .postedDate")?.text()),
                            card.selectFirst(".expwdth, .exp-wrap")?.text().orEmpty(),
                            card.selectFirst(".sal-wrap, .sal")?.text().orEmpty(),
                        )
                        pageGot = true
                    }
                }
                if (!pageGot) break
            }

            delay(800 + (0..600).random().toLong())
        }

        if (out.isEmpty() && err == null) err = "0 results"
        return out to err
    }

    /** Same as get() but forces a specific User-Agent (Android okhttp path). */
    private suspend fun getWithUa(
        url: String,
        extra: Map<String, String>,
        ua: String,
    ): Pair<Int, String> = withContext(Dispatchers.IO) {
        val rb = Request.Builder()
            .url(url)
            .header("User-Agent", ua)
            .header("Accept-Language", "en-IN,en;q=0.9")
            .header("Accept", extra["Accept"] ?: "application/json")
            .header("Referer", "https://www.naukri.com/")
            .header("Origin", "https://www.naukri.com")
        extra.forEach { (k, v) -> rb.header(k, v) }
        http.newCall(rb.build()).execute().use { it.code to (it.body?.string() ?: "") }
    }

    private suspend fun internshala(q: String, pages: Int): Pair<List<Posting>, String?> {
        val out = mutableListOf<Posting>()
        val s = slug(q)
        var err: String? = null
        for (pg in 1..pages) {
            val suffix = if (pg > 1) "/page-$pg" else ""
            var (code, body) = get("https://internshala.com/jobs/keywords-$s$suffix")
            if (code == 404) {
                val r = get("https://internshala.com/jobs/$s-jobs$suffix"); code = r.first; body = r.second
            }
            if (code != 200) {
                err = if (code == 404) null else "HTTP $code"
                break
            }
            val cards = Jsoup.parse(body).select("div.individual_internship, div[id^='individual_internship']")
            if (cards.isEmpty()) break
            for (c in cards) {
                val a = c.selectFirst("a.job-title-href, .job-internship-name a, h3 a")
                val title = a?.text().orEmpty()
                val href = a?.attr("href").orEmpty().ifEmpty { c.attr("data-href") }
                if (title.isEmpty() || href.isEmpty()) continue
                val full = if (href.startsWith("http")) href else "https://internshala.com$href"
                out += mk(
                    "internshala", title,
                    c.selectFirst(".company-name")?.text().orEmpty(),
                    c.selectFirst(".locations a, .location_link")?.text().orEmpty(), full,
                    parseAgo(c.selectFirst(".status-success, .status-inactive, .status-info")?.text()),
                    "", c.selectFirst(".stipend, .salary")?.text().orEmpty(),
                )
            }
            delay(800)
        }
        return out to err
    }

    private fun friendly(e: Exception) = when (e) {
        is java.net.UnknownHostException -> "offline"
        is java.net.SocketTimeoutException -> "timeout"
        else -> e.javaClass.simpleName
    }

    private suspend fun runPortal(
        qs: List<String>,
        fn: suspend (String) -> Pair<List<Posting>, String?>,
    ): Pair<List<Posting>, String> {
        val acc = mutableListOf<Posting>()
        var err: String? = null
        var fails = 0
        for (q in qs) {
            val r: Pair<List<Posting>, String?> = try {
                fn(q)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList<Posting>() to friendly(e)
            }
            acc += r.first
            if (r.second != null) {
                err = r.second
                if (r.first.isEmpty() && ++fails >= 2) break   // portal is blocking/offline: stop wasting time
            }
        }
        val n = acc.distinctBy { it.id }.size
        val text = if (n == 0) (err ?: "0 results") else "$n found" + (if (err != null) " (partial: $err)" else "")
        return acc to text
    }

    // ---------- main entry ----------
    suspend fun ingest(p: Profile, pages: Int = 2, windowSec: Long = 2_592_000L): IngestResult = coroutineScope {
        val qs = p.queries.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(10)
        val li = async { runPortal(qs) { linkedin(it, pages, windowSec) } }
        val nk = async { runPortal(qs) { naukri(it, pages) } }
        val ish = async { runPortal(qs) { internshala(it, pages) } }
        val rs = listOf(li.await(), nk.await(), ish.await())
        val jobs = dedupe(rs.flatMap { it.first })
            .map { score(it, p) }
            .filter { j -> p.match.isEmpty() || p.match.any { has(norm(j.title), it) } }
            .sortedWith(ORDER)
        IngestResult(
            jobs,
            mapOf(
                "LinkedIn" to rs[0].second,
                "Naukri" to rs[1].second,
                "Internshala" to rs[2].second,
            ),
        )
    }
}
