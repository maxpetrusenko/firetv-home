package app.firetvhome

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference

/** Pure routing and parsing for the push-refresh contract. No Android types, so JVM tests cover it. */
object Push {
    const val ACTION = "app.firetvhome.REFRESH"
    const val TAG = "FTH_PUSH"
    const val CALENDAR = "calendar"
    const val REMINDERS = "reminders"
    const val TRIPS = "trips"
    const val BIRTHDAYS = "birthdays"
    val ALL = linkedSetOf(CALENDAR, REMINDERS, TRIPS, BIRTHDAYS)
    const val FRESH_MS = 2 * 3_600_000L
    private const val SKEW_MS = 5 * 60_000L

    /** `block` extra to the set of blocks to reload. Missing means all; unknown names are ignored (empty set). */
    fun route(block: String?): Set<String> {
        val b = block?.trim()?.lowercase()
        return when {
            b == null || b.isEmpty() || b == "all" -> ALL
            b in ALL -> setOf(b)
            else -> emptySet()
        }
    }

    /** calendar.json is usable for 2 h after generated_at_ms; a stamp slightly in the future (clock skew) still counts. */
    fun isFresh(generatedAtMs: Long, now: Long): Boolean = generatedAtMs > 0 && now - generatedAtMs in -SKEW_MS until FRESH_MS

    class CalendarFile(val generatedAtMs: Long, val events: List<Ev>)

    /** Null when the document is not a usable v1 calendar file (the caller falls back to ICS). A bad event is skipped. */
    fun parseCalendar(json: String): CalendarFile? = try {
        val o = JSONObject(json)
        if (o.optInt("v", 0) != 1) null
        else {
            val gen = o.getLong("generated_at_ms")
            val arr = o.getJSONArray("events")
            val out = ArrayList<Ev>(arr.length())
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                val allDay = e.optBoolean("all_day", false)
                val s = Data.isoToMs(e.optString("start")) ?: continue
                val en = Data.isoToMs(e.optString("end")) ?: s
                val title = e.optString("title", "").ifEmpty { "(no title)" }
                out.add(Ev(title, s, en, allDay, e.optString("id", "")))
            }
            CalendarFile(gen, out)
        }
    } catch (e: Exception) { null }

    fun blocksDir(ctx: Context): File = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "blocks")

    /** Fresh events from blocks/calendar.json, or null (missing, stale, malformed). Runs on a worker thread. */
    fun freshCalendar(ctx: Context, now: Long): List<Ev>? {
        val f = File(blocksDir(ctx), "calendar.json")
        if (!f.canRead() || f.length() > 1_000_000L) return null
        val c = parseCalendar(f.readText()) ?: return null
        return if (isFresh(c.generatedAtMs, now)) c.events else null
    }

    // ---- dirty flags and in-process notification ----
    private const val PREFS = "push"
    private const val DIRTY = "dirty"

    fun markDirty(ctx: Context, blocks: Set<String>) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        synchronized(this) {
            val cur = HashSet(p.getStringSet(DIRTY, emptySet()) ?: emptySet())
            if (cur.addAll(blocks)) p.edit().putStringSet(DIRTY, cur).apply()
        }
    }

    fun clearDirty(ctx: Context, blocks: Set<String>) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        synchronized(this) {
            val cur = HashSet(p.getStringSet(DIRTY, emptySet()) ?: emptySet())
            if (cur.removeAll(blocks)) p.edit().putStringSet(DIRTY, cur).apply()
        }
    }

    interface Listener { fun onPush(blocks: Set<String>, ts: Long) }

    @Volatile private var listener: WeakReference<Listener>? = null
    /** Set while MainActivity is resumed, null otherwise. Weak, so it can never leak the activity. */
    fun setListener(l: Listener?) { listener = l?.let { WeakReference(it) } }
    fun notifyListener(blocks: Set<String>, ts: Long): Boolean {
        val l = listener?.get() ?: return false
        l.onPush(blocks, ts)
        return true
    }
}

/**
 * Push from any host with adb: `am broadcast -a app.firetvhome.REFRESH --es block calendar --el ts <ms>`.
 * Cheap by design: one prefs write, one log line, no network. Runs on the main thread, so the in-process
 * hand-off to a resumed MainActivity is a plain call; the activity moves the work to its io thread.
 */
class RefreshReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Push.ACTION) return
        val name = intent.getStringExtra("block") ?: "all"
        val ts = intent.getLongExtra("ts", 0L)
        val blocks = Push.route(name)
        Log.i(Push.TAG, "recv block=$name ts=$ts now=${System.currentTimeMillis()}")
        if (blocks.isEmpty()) return
        Push.markDirty(ctx, blocks)
        Push.notifyListener(blocks, ts)
    }
}
