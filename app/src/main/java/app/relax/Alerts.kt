package app.relax

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.*
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

class Store(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("relax", Context.MODE_PRIVATE)

    var defaultId: String
        get() = sp.getString("defaultId", "ops") ?: "ops"
        set(v) { sp.edit().putString("defaultId", v).apply() }

    /** off | hourly | morning | evening | daily */
    var alertMode: String
        get() = sp.getString("alertMode", "off") ?: "off"
        set(v) { sp.edit().putString("alertMode", v).apply() }

    var alertMinutes: Int
        get() = sp.getInt("alertMin", 12 * 60)
        set(v) { sp.edit().putInt("alertMin", v).apply() }

    var alertStrong: Boolean
        get() = sp.getBoolean("alertStrong", true)
        set(v) { sp.edit().putBoolean("alertStrong", v).apply() }

    /** hours window used by background alerts (matches UI filter) */
    var alertHours: Long
        get() = sp.getLong("alertHours", 24L)
        set(v) { sp.edit().putLong("alertHours", v).apply() }

    var lastOpen: Long
        get() = sp.getLong("lastOpen", 0L)
        set(v) { sp.edit().putLong("lastOpen", v).apply() }

    fun customProfiles(): List<Profile> = runCatching {
        val a = JSONArray(sp.getString("profiles", "[]") ?: "[]")
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val q = o.getJSONArray("queries")
            val ops = o.optBoolean("ops", false)
            val match = o.optJSONArray("match")
            val matchList = if (match != null) (0 until match.length()).map { match.getString(it) } else emptyList()
            Profile(o.getString("id"), o.getString("name"), (0 until q.length()).map { q.getString(it) }, ops, matchList)
        }
    }.getOrDefault(emptyList())

    fun putProfiles(l: List<Profile>) {
        val a = JSONArray()
        l.forEach {
            a.put(
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("queries", JSONArray(it.queries))
                    .put("ops", it.ops)
                    .put("match", JSONArray(it.match)),
            )
        }
        sp.edit().putString("profiles", a.toString()).apply()
    }

    fun allProfiles(): List<Profile> = BUILTIN + customProfiles()

    fun deleteProfile(id: String) {
        putProfiles(customProfiles().filter { it.id != id })
        sp.edit().remove("c_$id").remove("t_$id").apply()
        if (defaultId == id) defaultId = "ops"
    }

    private fun parse(s: String?): List<Posting> = runCatching {
        val a = JSONArray(s ?: "[]")
        (0 until a.length()).map { Posting.fromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun dump(l: List<Posting>) = JSONArray().also { a -> l.forEach { a.put(it.toJson()) } }.toString()

    fun cache(id: String) = parse(sp.getString("c_$id", null))
    fun stamp(id: String) = sp.getLong("t_$id", 0L)
    fun putCache(id: String, l: List<Posting>) {
        sp.edit().putString("c_$id", dump(l)).putLong("t_$id", System.currentTimeMillis()).apply()
    }

    fun saved() = parse(sp.getString("saved", null))
    fun putSaved(l: List<Posting>) { sp.edit().putString("saved", dump(l)).apply() }

    fun applied() = parse(sp.getString("applied", null))
    fun putApplied(l: List<Posting>) { sp.edit().putString("applied", dump(l)).apply() }

    fun appliedKeys(): Set<String> = applied().map { Engine.key(it) }.toSet()

    fun seen(): Set<String> = runCatching {
        val a = JSONArray(sp.getString("seen", "[]") ?: "[]")
        (0 until a.length()).map { a.getString(it) }.toSet()
    }.getOrDefault(emptySet())

    fun addSeen(keys: List<String>) {
        val all = LinkedHashSet(seen())
        all.addAll(keys)
        val trimmed = if (all.size > 3000) all.toList().takeLast(3000) else all.toList()
        sp.edit().putString("seen", JSONArray(trimmed).toString()).apply()
    }
}

object Alerts {
    private const val CH = "jobs"

    fun channel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            val existing = nm.getNotificationChannel(CH)
            if (existing == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CH, "Job alerts", NotificationManager.IMPORTANCE_DEFAULT).apply {
                        description = "New matching jobs from your default preference"
                        enableVibration(true)
                    },
                )
            }
        }
    }

    private fun minutesFor(st: Store) = when (st.alertMode) {
        "morning" -> 8 * 60
        "evening" -> 18 * 60
        else -> st.alertMinutes
    }

    private fun delayTo(minutes: Int): Long {
        val now = LocalDateTime.now()
        var t = now.toLocalDate().atTime(minutes / 60, minutes % 60)
        if (!t.isAfter(now.plusMinutes(1))) t = t.plusDays(1)
        return Duration.between(now, t).toMillis()
    }

    /** Idempotent: call on app start, when the mode changes, and after every timed run. */
    fun schedule(ctx: Context) {
        channel(ctx)
        val st = Store(ctx)
        val wm = WorkManager.getInstance(ctx)
        val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        when (st.alertMode) {
            "off" -> {
                wm.cancelUniqueWork("alerts_h")
                wm.cancelUniqueWork("alerts_t")
            }
            "hourly" -> {
                wm.cancelUniqueWork("alerts_t")
                // min period on many devices is 15 min; 1h is fine
                wm.enqueueUniquePeriodicWork(
                    "alerts_h",
                    ExistingPeriodicWorkPolicy.UPDATE,
                    PeriodicWorkRequestBuilder<AlertWorker>(1, TimeUnit.HOURS)
                        .setConstraints(net)
                        .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.MINUTES)
                        .build(),
                )
            }
            else -> {
                wm.cancelUniqueWork("alerts_h")
                wm.enqueueUniqueWork(
                    "alerts_t",
                    ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<AlertWorker>()
                        .setInitialDelay(delayTo(minutesFor(st)), TimeUnit.MILLISECONDS)
                        .setConstraints(net)
                        .build(),
                )
            }
        }
    }

    /**
     * Background check:
     * - uses DEFAULT preference only
     * - respects alertHours window + alertStrong (P1–P2)
     * - never notifies for already-applied or already-seen jobs
     */
    suspend fun check(ctx: Context): Int {
        channel(ctx)
        val st = Store(ctx)
        val p = st.allProfiles().firstOrNull { it.id == st.defaultId } ?: BUILTIN[0]
        val r = Engine.ingest(p, pages = 1, windowSec = st.alertHours * 3_600L)
        if (r.jobs.isEmpty()) return 0

        val seen = st.seen()
        val applied = st.appliedKeys()
        val since = System.currentTimeMillis() - st.alertHours * 3_600_000L

        val fresh = r.jobs.filter { j ->
            val k = Engine.key(j)
            k !in seen &&
                k !in applied &&
                j.postedAt >= since &&
                j.score >= 300 &&
                (!st.alertStrong || j.tier <= 2)
        }

        st.putCache(p.id, Engine.merge(st.cache(p.id), r.jobs).filter { Engine.key(it) !in applied })
        st.addSeen(r.jobs.map { Engine.key(it) })

        if (fresh.isEmpty()) return 0

        val top = fresh.sortedWith(Engine.ORDER).take(5)
        val title = when (top.size) {
            1 -> "1 new match — ${top[0].title}"
            else -> "${top.size} new matches for ${p.name}"
        }
        val body = top.joinToString("\n") { "• ${it.title} @ ${it.company}" }
        notify(ctx, title, body, top.size)
        return fresh.size
    }

    fun notify(ctx: Context, title: String, body: String, count: Int = 1) {
        channel(ctx)
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val open = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, CH)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setNumber(count)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(9001, n)
        } catch (_: SecurityException) {
            /* permission revoked mid-flight */
        }
    }

    fun test(ctx: Context) {
        notify(ctx, "Relax — test alert", "Notifications work. You'll only get real alerts for new matches on your default preference.")
    }
}

class AlertWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        return try {
            Alerts.check(applicationContext)
            // re-arm one-shot daily / morning / evening
            val st = Store(applicationContext)
            if (st.alertMode != "off" && st.alertMode != "hourly") {
                Alerts.schedule(applicationContext)
            }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
