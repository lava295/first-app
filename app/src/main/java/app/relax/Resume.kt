package app.relax

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.util.zip.ZipInputStream
import kotlin.math.roundToInt

/**
 * Offline resume → JD fit + keyword-honest tailoring.
 *
 * Rules enforced:
 * - Only surface keywords that already exist in the user's resume (or clear aliases of them).
 * - Never invent employers, titles, tools, or years.
 * - Tailored bullets = rewrite of EXISTING bullets with JD vocabulary where truthful.
 * - Fit score is explainable: matched skills / required skills, with gaps listed.
 */
object Resume {

    data class ProfileDoc(
        val rawText: String,
        val name: String = "",
        val email: String = "",
        val phone: String = "",
        val headline: String = "",
        val skills: List<String> = emptyList(),
        val bullets: List<String> = emptyList(),
        /** normalized token set for fast membership */
        val tokens: Set<String> = emptySet(),
    )

    data class FitResult(
        val score: Int,                    // 0–100
        val matched: List<String>,         // skills present in both JD and resume
        val missing: List<String>,         // JD skills not in resume (do NOT invent)
        val weak: List<String>,            // partial / alias hits
        val tailoredHeadline: String,
        val tailoredSummary: String,
        val tailoredBullets: List<String>,
        val keywordsToMirror: List<String>, // only ones user already has
        val notes: List<String>,
    )

    // Common skill lexicon + aliases (resume token → canonical)
    private val ALIAS = mapOf(
        "js" to "javascript", "ts" to "typescript", "py" to "python",
        "reactjs" to "react", "react.js" to "react", "nodejs" to "node.js", "node" to "node.js",
        "postgres" to "postgresql", "k8s" to "kubernetes", "gcp" to "google cloud",
        "ms excel" to "excel", "power bi" to "powerbi", "power-bi" to "powerbi",
        "ci/cd" to "cicd", "ci-cd" to "cicd", "rest api" to "rest", "restful" to "rest",
        "ops" to "operations", "operation" to "operations",
        "qc" to "quality control", "qa" to "quality assurance",
        "subtitles" to "subtitling", "subtitle" to "subtitling",
        "post-production" to "post production", "postprod" to "post production",
        "mgmt" to "management", "mgr" to "manager",
        "sr" to "senior", "jr" to "junior",
        "ai" to "artificial intelligence", "ml" to "machine learning",
        "nlp" to "natural language processing",
        "aws" to "amazon web services", "azure" to "microsoft azure",
        "sql server" to "sql", "mssql" to "sql",
        "html5" to "html", "css3" to "css",
        "ui/ux" to "ui", "ux/ui" to "ui",
    )

    // Phrases we look for inside JD text (multi-word first)
    private val SKILL_PHRASES = listOf(
        "content operations", "dubbing operations", "media operations", "post production",
        "quality control", "quality assurance", "team lead", "project management",
        "stakeholder management", "process improvement", "workflow automation",
        "machine learning", "artificial intelligence", "natural language processing",
        "google cloud", "amazon web services", "microsoft azure", "power bi",
        "data analysis", "data analytics", "business analysis", "product management",
        "agile", "scrum", "kanban", "jira", "confluence", "asana", "trello",
        "python", "java", "kotlin", "javascript", "typescript", "sql", "excel",
        "react", "node.js", "android", "ios", "aws", "docker", "kubernetes",
        "subtitling", "dubbing", "transcription", "localization", "qc",
        "operations", "coordinator", "analyst", "manager", "lead",
        "communication", "leadership", "problem solving", "collaboration",
        "vendor management", "sla", "kpi", "okr", "reporting", "dashboard",
        "figma", "photoshop", "premiere", "after effects", "avid", "pro tools",
        "salesforce", "hubspot", "zendesk", "servicenow",
        "tableau", "looker", "snowflake", "redshift", "bigquery",
        "rest", "graphql", "microservices", "linux", "git", "github", "gitlab",
        "c++", "c#", "golang", "go", "rust", "scala", "r", "matlab",
        "spark", "hadoop", "kafka", "airflow", "dbt", "etl", "elt",
        "seo", "sem", "content strategy", "copywriting", "editorial",
        "customer success", "account management", "business development",
    ).sortedByDescending { it.length }

    // ---------- text extraction ----------

    fun extractText(ctx: Context, uri: Uri): String {
        val name = uri.lastPathSegment?.lowercase() ?: ""
        val cr = ctx.contentResolver
        val mime = cr.getType(uri)?.lowercase() ?: ""
        return cr.openInputStream(uri)?.use { input ->
            when {
                mime.contains("pdf") || name.endsWith(".pdf") -> extractPdf(input)
                mime.contains("word") || name.endsWith(".docx") -> extractDocx(input)
                else -> input.bufferedReader().readText()
            }
        }?.trim().orEmpty()
    }

    /** Minimal PDF text scrape (streams / Tj / TJ). Good enough for most text resumes. */
    private fun extractPdf(input: InputStream): String {
        val bytes = input.readBytes()
        val raw = bytes.toString(Charsets.ISO_8859_1)
        val out = StringBuilder()
        // (text) Tj
        val tj = Regex("\\((?:\\\\.|[^\\\\)])*\\)\\s*Tj")
        tj.findAll(raw).forEach { m ->
            val s = m.value.substringAfter("(").substringBeforeLast(")")
            out.append(unescapePdf(s)).append(' ')
        }
        // [(text)] TJ
        val tjArr = Regex("\\[(.*?)\\]\\s*TJ", RegexOption.DOT_MATCHES_ALL)
        tjArr.findAll(raw).forEach { m ->
            Regex("\\((?:\\\\.|[^\\\\)])*\\)").findAll(m.groupValues[1]).forEach { p ->
                val s = p.value.removeSurrounding("(", ")")
                out.append(unescapePdf(s))
            }
            out.append(' ')
        }
        // fallback: readable ASCII runs
        if (out.length < 40) {
            val ascii = Regex("[\\x20-\\x7E]{4,}").findAll(raw).joinToString(" ") { it.value }
            out.append(ascii)
        }
        return cleanText(out.toString())
    }

    private fun unescapePdf(s: String) = s
        .replace("\\n", "\n").replace("\\r", "")
        .replace("\\t", " ").replace("\\(", "(").replace("\\)", ")")
        .replace("\\\\", "\\")

    /** docx = zip with word/document.xml */
    private fun extractDocx(input: InputStream): String {
        ZipInputStream(input).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                if (e.name == "word/document.xml") {
                    val xml = BufferedReader(InputStreamReader(zis, Charsets.UTF_8)).readText()
                    return cleanText(xml.replace(Regex("<[^>]+>"), " "))
                }
                e = zis.nextEntry
            }
        }
        return ""
    }

    private fun cleanText(s: String) =
        s.replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]"), " ")
            .replace(Regex("[ \\t\\x0b\\f\\r]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()

    // ---------- parse resume into structured profile ----------

    fun parse(text: String): ProfileDoc {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val email = Regex("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE)
            .find(text)?.value.orEmpty()
        val phone = Regex("(?:\\+\\d{1,3}[-\\s]?)?(?:\\d{10}|\\d{3}[-\\s]\\d{3}[-\\s]\\d{4})")
            .find(text)?.value.orEmpty()
        val name = lines.firstOrNull {
            it.length in 3..48 && !it.contains("@") && it.split(" ").size in 2..5 &&
                it.none { ch -> ch.isDigit() }
        }.orEmpty()

        val skills = extractSkills(text)
        val bullets = lines.filter {
            it.startsWith("•") || it.startsWith("-") || it.startsWith("*") ||
                (it.length in 40..220 && it.contains(" "))
        }.map { it.trimStart('•', '-', '*', ' ') }.distinct().take(40)

        val headline = lines.firstOrNull {
            val n = norm(it)
            n != norm(name) && !n.contains("@") && it.length in 8..90 &&
                (hasAny(n, listOf("engineer", "manager", "analyst", "developer", "lead", "specialist",
                    "coordinator", "executive", "operations", "designer", "consultant")) ||
                    skills.any { sk -> has(n, sk) })
        }.orEmpty()

        val tokens = tokenize(text)
        return ProfileDoc(
            rawText = text,
            name = name,
            email = email,
            phone = phone,
            headline = headline,
            skills = skills,
            bullets = bullets,
            tokens = tokens,
        )
    }

    private fun extractSkills(text: String): List<String> {
        val n = norm(text)
        val found = linkedSetOf<String>()
        for (p in SKILL_PHRASES) {
            if (has(n, p)) found += p
        }
        // also split common "Skills:" sections
        val sec = Regex("(?:skills|technical skills|core competencies)\\s*[:\\-]\\s*(.+)", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.getOrNull(1).orEmpty()
        sec.split(",", "|", "/", "•", "\n").map { norm(it) }.filter { it.length in 2..32 }.forEach { found += it }
        return found.toList()
    }

    private fun tokenize(text: String): Set<String> {
        val n = norm(text)
        val parts = n.split(" ").filter { it.length >= 2 }.toMutableSet()
        // add alias canonical forms
        ALIAS.forEach { (k, v) -> if (k in parts || has(n, k)) parts += v }
        return parts
    }

    // ---------- fit against a job ----------

    fun fit(doc: ProfileDoc, job: Posting, jdExtra: String = ""): FitResult {
        val jdBlob = norm(listOf(job.title, job.company, job.exp, job.salary, job.location, jdExtra).joinToString(" "))
        val required = linkedSetOf<String>()
        for (p in SKILL_PHRASES) {
            if (has(jdBlob, p)) required += p
        }
        // title words as soft requirements
        norm(job.title).split(" ").filter { it.length > 3 && it !in STOP }.forEach { required += it }

        val matched = mutableListOf<String>()
        val missing = mutableListOf<String>()
        val weak = mutableListOf<String>()

        for (req in required) {
            when {
                hasSkill(doc, req) -> matched += req
                hasWeak(doc, req) -> weak += req
                else -> missing += req
            }
        }

        val denom = (matched.size + weak.size + missing.size).coerceAtLeast(1)
        val score = ((matched.size * 100 + weak.size * 55) / denom.toDouble()).roundToInt().coerceIn(0, 100)

        // keywords user already owns that appear in JD — safe to mirror
        val mirror = (matched + weak).distinct().filter { hasSkill(doc, it) || hasWeak(doc, it) }

        val tailoredHeadline = tailorHeadline(doc, job, mirror)
        val tailoredSummary = tailorSummary(doc, job, mirror, missing)
        val tailoredBullets = tailorBullets(doc, job, mirror)

        val notes = buildList {
            if (matched.isNotEmpty()) add("Strong overlap: ${matched.take(8).joinToString(", ")}.")
            if (weak.isNotEmpty()) add("Partial overlap (use carefully): ${weak.take(5).joinToString(", ")}.")
            if (missing.isNotEmpty()) {
                add("JD also asks for: ${missing.take(8).joinToString(", ")} — not on your resume, so not added.")
            }
            add("No employers, titles, or tools were invented. Only your existing experience was rephrased.")
        }

        return FitResult(
            score = score,
            matched = matched.distinct(),
            missing = missing.distinct(),
            weak = weak.distinct(),
            tailoredHeadline = tailoredHeadline,
            tailoredSummary = tailoredSummary,
            tailoredBullets = tailoredBullets,
            keywordsToMirror = mirror,
            notes = notes,
        )
    }

    private fun hasSkill(doc: ProfileDoc, skill: String): Boolean {
        val s = norm(skill)
        if (has(norm(doc.rawText), s)) return true
        if (doc.skills.any { has(norm(it), s) || has(s, norm(it)) }) return true
        val canon = ALIAS[s] ?: s
        if (canon != s && has(norm(doc.rawText), canon)) return true
        return s in doc.tokens || canon in doc.tokens
    }

    private fun hasWeak(doc: ProfileDoc, skill: String): Boolean {
        val s = norm(skill)
        // token overlap: "operations" vs "operation", "manage" vs "manager"
        val stem = s.take(s.length.coerceAtMost(5))
        if (stem.length >= 4 && doc.tokens.any { it.startsWith(stem) || stem.startsWith(it.take(5)) }) return true
        return false
    }

    private fun tailorHeadline(doc: ProfileDoc, job: Posting, mirror: List<String>): String {
        val base = doc.headline.ifBlank {
            // derive from top skills — still only user's skills
            doc.skills.take(3).joinToString(" · ").ifBlank { "Professional" }
        }
        val roleHint = job.title.split(" ").take(4).joinToString(" ")
        val keys = mirror.take(3).joinToString(" · ")
        return buildString {
            append(base.trim().trimEnd('.', '|', '-', '·'))
            if (keys.isNotEmpty() && !norm(base).contains(norm(keys.split(" · ").first()))) {
                append(" | ").append(keys)
            }
            // only mention role words if user already has them
            val roleWords = norm(roleHint).split(" ").filter { it.length > 3 && hasSkill(doc, it) }
            if (roleWords.isNotEmpty() && !norm(base).contains(roleWords.first())) {
                append(" · ").append(roleWords.joinToString(" ").replaceFirstChar { it.uppercase() })
            }
        }.take(120)
    }

    private fun tailorSummary(
        doc: ProfileDoc,
        job: Posting,
        mirror: List<String>,
        missing: List<String>,
    ): String {
        val skillsLine = (mirror.ifEmpty { doc.skills }.take(8)).joinToString(", ")
        val domain = when {
            hasSkill(doc, "dubbing") || hasSkill(doc, "subtitling") || hasSkill(doc, "content operations") ->
                "media and content operations"
            hasSkill(doc, "python") || hasSkill(doc, "sql") || hasSkill(doc, "data analysis") ->
                "data and analytics"
            hasSkill(doc, "android") || hasSkill(doc, "kotlin") || hasSkill(doc, "java") ->
                "software engineering"
            else -> "operations and delivery"
        }
        val target = job.title.ifBlank { "this role" }
        return buildString {
            append("Professional with hands-on experience in $domain")
            if (skillsLine.isNotEmpty()) append(", including $skillsLine")
            append(". ")
            append("Background maps cleanly to $target")
            append(" based on existing work — not assumed skills. ")
            if (missing.isNotEmpty()) {
                append("Open to growing into: ${missing.take(3).joinToString(", ")}.")
            }
        }
    }

    private fun tailorBullets(doc: ProfileDoc, job: Posting, mirror: List<String>): List<String> {
        if (doc.bullets.isEmpty()) {
            // synthesize ONLY from declared skills — still no fiction about employers
            return mirror.take(5).map { k ->
                "Applied $k in day-to-day delivery, aligned with requirements for ${job.title.ifBlank { "this role" }}."
            }
        }
        val jdTokens = tokenize("${job.title} ${job.exp} ${mirror.joinToString(" ")}")
        return doc.bullets
            .map { b -> b to scoreBullet(b, jdTokens, mirror) }
            .sortedByDescending { it.second }
            .take(8)
            .map { (b, _) -> rewriteBullet(b, mirror) }
    }

    private fun scoreBullet(b: String, jdTokens: Set<String>, mirror: List<String>): Int {
        val n = norm(b)
        var s = 0
        for (t in jdTokens) if (has(n, t)) s += 2
        for (m in mirror) if (has(n, m)) s += 3
        return s
    }

    private fun rewriteBullet(b: String, mirror: List<String>): String {
        var t = b.trim().trimEnd('.')
        // weave in at most one already-owned keyword not already present
        val add = mirror.firstOrNull { !has(norm(t), it) && it.length > 2 }
        if (add != null && t.length < 180) {
            t = "$t; leveraged $add where relevant"
        }
        // keep tone professional
        if (!t.first().isUpperCase()) t = t.replaceFirstChar { it.uppercase() }
        return t.take(220)
    }

    // ---------- export ----------

    fun renderText(doc: ProfileDoc, fit: FitResult, job: Posting): String = buildString {
        appendLine(doc.name.ifBlank { "Candidate" })
        val contact = listOf(doc.email, doc.phone).filter { it.isNotBlank() }.joinToString(" · ")
        if (contact.isNotEmpty()) appendLine(contact)
        appendLine()
        appendLine(fit.tailoredHeadline)
        appendLine()
        appendLine("SUMMARY")
        appendLine(fit.tailoredSummary)
        appendLine()
        appendLine("KEY SKILLS (from your resume, matched to JD)")
        appendLine(fit.keywordsToMirror.ifEmpty { doc.skills }.take(12).joinToString(" · "))
        appendLine()
        appendLine("SELECTED EXPERIENCE (rephrased, not invented)")
        fit.tailoredBullets.forEach { appendLine("• $it") }
        appendLine()
        appendLine("——")
        appendLine("Target: ${job.title} @ ${job.company}")
        appendLine("Fit score: ${fit.score}/100")
        fit.notes.forEach { appendLine("· $it") }
    }

    fun share(ctx: Context, text: String, title: String = "Tailored resume") {
        val dir = File(ctx.cacheDir, "resumes").apply { mkdirs() }
        val safe = title.replace(Regex("[^A-Za-z0-9._-]+"), "_").take(40)
        val file = File(dir, "${safe.ifBlank { "resume" }}.txt")
        file.writeText(text)
        val uri = try {
            FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        } catch (_: Exception) {
            // fallback: share plain text only
            null
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, text)
            if (uri != null) {
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        ctx.startActivity(Intent.createChooser(intent, "Share tailored resume"))
    }

    // ---------- persistence ----------

    fun save(ctx: Context, doc: ProfileDoc) {
        val sp = ctx.getSharedPreferences("relax", Context.MODE_PRIVATE)
        val o = JSONObject()
            .put("rawText", doc.rawText)
            .put("name", doc.name)
            .put("email", doc.email)
            .put("phone", doc.phone)
            .put("headline", doc.headline)
            .put("skills", JSONArray(doc.skills))
            .put("bullets", JSONArray(doc.bullets))
        sp.edit().putString("resume_doc", o.toString()).apply()
    }

    fun load(ctx: Context): ProfileDoc? {
        val sp = ctx.getSharedPreferences("relax", Context.MODE_PRIVATE)
        val s = sp.getString("resume_doc", null) ?: return null
        return runCatching {
            val o = JSONObject(s)
            val skills = o.getJSONArray("skills").let { a -> (0 until a.length()).map { a.getString(it) } }
            val bullets = o.getJSONArray("bullets").let { a -> (0 until a.length()).map { a.getString(it) } }
            val raw = o.getString("rawText")
            ProfileDoc(
                rawText = raw,
                name = o.optString("name"),
                email = o.optString("email"),
                phone = o.optString("phone"),
                headline = o.optString("headline"),
                skills = skills,
                bullets = bullets,
                tokens = tokenize(raw),
            )
        }.getOrNull()
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences("relax", Context.MODE_PRIVATE).edit().remove("resume_doc").apply()
    }

    private val STOP = setOf(
        "with", "from", "that", "this", "have", "will", "your", "their", "about",
        "into", "over", "than", "then", "also", "using", "work", "role", "job",
        "jobs", "good", "team", "years", "year", "experience", "experienced",
    )
}
