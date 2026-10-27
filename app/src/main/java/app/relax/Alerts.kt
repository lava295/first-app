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

    var lastOpen: Long
        get() = sp.getLong("lastOpen", 0L)
        set(v) { sp.edit().putLong("lastOpen", v).apply() }

    fun customProfiles(): List<Profile> = runCatching {
        val a = JSONArray(sp.getString("profiles", "[]") ?: "[]")
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val q = o.getJSONArray("queries")
            Profile(o.getString("id"), o.getString("name"), (0 until q.length()).map { q.getString(it) })
        }
    }.getOrDefault(emptyList())

    fun putProfiles(l: List<Profile>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("queries", JSONArray(it.queries))) }
        sp.edit().putString("profiles", a.toString()).apply()
    }

    fun allProfiles(): List<Profile> = BUILTIN + customProfiles()

    fun deleteProfile(id: String) {
        putProfiles(customProfiles().filter { it.id != id })
        sp.edit().remove("c_$id").remove("t_$id").apply()
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

    fun seen(): Set<String> = runCatching {
        val a = JSONArray(sp.getString("seen", "[]") ?: "[]")
        (0 until a.length()).map { a.getString(it) }.toSet()
    }.getOrDefault(emptySet())

    fun addSeen(keys: List<String>) {
        val all = LinkedHashSet<String>(seen())
        all.addAll(keys)
        val trimmed = if (all.size > 3000) all.toList().takeLast(3000) else all.toList()
        sp.edit().putString("seen", JSONArray(trimmed).toString()).apply()
    }
}

object Alerts {
    private const val CH = "jobs"

    fun channel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            ctx.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CH, "Job alerts", NotificationManager.IMPORTANCE_DEFAULT))
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
        val st = Store(ctx)
        val wm = WorkManager.getInstance(ctx)
        val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        when (st.alertMode) {
            "off" -> {
                wm.cancelUniqueWork("alerts_h"); wm.cancelUniqueWork("alerts_t")
            }
            "hourly" -> {
                wm.cancelUniqueWork("alerts_t")
                wm.enqueueUniquePeriodicWork(
                    "alerts_h", ExistingPeriodicWorkPolicy.UPDATE,
                    PeriodicWorkRequestBuilder<AlertWorker>(1, TimeUnit.HOURS).setConstraints(net).build(),
                )
            }
            else -> {
                wm.cancelUniqueWork("alerts_h")
                wm.enqueueUniqueWork(
                    "alerts_t", ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<AlertWorker>()
                        .setInitialDelay(delayTo(minutesFor(st)), TimeUnit.MILLISECONDS)
                        .setConstraints(net).build(),
                )
            }
        }
    }

    /** Searches the default profile, stores results, notifies about unseen ones. Returns number of new jobs. */
    suspend fun check(ctx: Context): Int {
        val st = Store(ctx)
        val p = st.allProfiles().firstOrNull { it.id == st.defaultId } ?: BUILTIN[0]
        val r = Engine.ingest(p, pages = 1, windowSec = 86_400L)
        if (r.jobs.isEmpty()) return 0   // offline / blocked: change nothing
        val seen = st.seen()
        val fresh = r.jobs.filter {
            Engine.key(it) !in seen && it.score >= 300 && (!st.alertStrong || it.tier <= 2)
        }
        st.putCache(p.id, Engine.merge(st.cache(p.id), r.jobs))
        st.addSeen(r.jobs.map { Engine.key(it) })
        if (fresh.isNotEmpty()) notify(ctx, fresh)
        return fresh.size
    }

    fun notify(ctx: Context, fresh: List<Posting>) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        channel(ctx)
        val top = fresh.sortedWith(Engine.ORDER).first()
        val open = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, CH)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("${fresh.size} new job${if (fresh.size > 1) "s are" else " is"} waiting for you")
            .setContentText("${top.title} · ${top.company}" + if (fresh.size > 1) "  +${fresh.size - 1} more" else "")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(fresh.sortedWith(Engine.ORDER).take(6).joinToString("\n") { "• ${it.title} — ${it.company}" }),
            )
            .setContentIntent(open).setAutoCancel(true).build()
        NotificationManagerCompat.from(ctx).notify(1, n)
    }

    fun test(ctx: Context) {
        val now = System.currentTimeMillis()
        notify(ctx, listOf(Posting("test", "linkedin", "Dubbing Operations Lead", "Sample Studio", "Bengaluru", "https://example.com", now, false)))
    }
}

class AlertWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        try {
            Alerts.check(applicationContext)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // network/parse problems must never break the schedule
        }
        val mode = Store(applicationContext).alertMode
        if (mode != "off" && mode != "hourly") Alerts.schedule(applicationContext)   // chain next run
        return Result.success()
    }
}
