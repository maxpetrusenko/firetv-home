package app.firetvhome

import android.util.Log
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** Birthdays: pure parsing and windowing, plus source dispatch. */
object Birthdays {
    private const val TAG = "firetv-home"
    const val FILE = "birthdays.json"

    /** [day] is the local start of the birthday day, in ms. */
    class Item(val day: Long, val name: String)

    /** Parse the pushed block {"generated","source","items":[{date,name,title}]}. Null when unusable; bad items are skipped. */
    fun parseBlock(json: String, tz: TimeZone = TimeZone.getDefault()): List<Item>? = try {
        val arr = JSONObject(json).getJSONArray("items")
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = tz; isLenient = false }
        val out = ArrayList<Item>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val d = try { fmt.parse(o.optString("date"))?.time } catch (e: Exception) { null } ?: continue
            val name = o.optString("name").ifEmpty { o.optString("title") }.trim()
            if (name.isNotEmpty()) out.add(Item(d, name))
        }
        out
    } catch (e: Exception) { null }

    /** Birthdays in [today, today + days), soonest first. */
    fun window(items: List<Item>, now: Long, days: Int, tz: TimeZone = TimeZone.getDefault()): List<Item> {
        val today = CalendarLogic.startOfDay(now, tz)
        val end = Calendar.getInstance(tz).apply { timeInMillis = today; add(Calendar.DAY_OF_MONTH, days) }.timeInMillis
        return items.filter { it.day in today until end }.sortedWith(compareBy({ it.day }, { it.name }))
    }

    /** "Sat Nov 29 — Name"; "Today — Name"; "Tomorrow — Name". */
    fun line(i: Item, now: Long, tz: TimeZone = TimeZone.getDefault(), locale: Locale = Locale.getDefault()): String =
        CalendarLogic.dayLabel(i.day, now, tz, locale) + " — " + i.name

    /** Events (ICS or HA calendar) to birthday items; the event title is the name. */
    fun fromEvents(events: List<Ev>, tz: TimeZone = TimeZone.getDefault()): List<Item> =
        events.map { Item(CalendarLogic.startOfDay(it.start, tz), it.title.trim()) }.filter { it.name.isNotEmpty() }

    /** Load from the configured source. Blocking; call off the main thread. Null on failure; empty when unsupported. */
    fun load(ctx: android.content.Context, c: Config, now: Long): List<Item>? {
        val b = c.birthdays ?: return null
        val to = Calendar.getInstance().apply { timeInMillis = CalendarLogic.startOfDay(now, TimeZone.getDefault()); add(Calendar.DAY_OF_MONTH, b.days) }.timeInMillis
        val from = CalendarLogic.startOfDay(now, TimeZone.getDefault())
        return when (b.type) {
            "block" -> {
                val f = java.io.File(Push.blocksDir(ctx), FILE)
                if (!f.canRead() || f.length() > 1_000_000L) emptyList() else parseBlock(f.readText())
            }
            "ics" -> if (b.url.isEmpty()) emptyList() else fromEvents(Ics.parse(Data.fetch(b.url), from, to))
            "ha" -> if (!c.haReady || b.entity.isEmpty()) emptyList() else fromEvents(Data.haEvents(c, b.entity, from, to))
            "google" -> { Log.w(TAG, "birthdays: type google is not supported yet; use ics, ha or block"); emptyList() }
            else -> { Log.w(TAG, "birthdays: unsupported type '${b.type}'"); emptyList() }
        }
    }
}
