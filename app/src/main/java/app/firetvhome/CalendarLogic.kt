package app.firetvhome

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Pure calendar helpers: filtering, ordering, day grouping and labels. No Android types. */
object CalendarLogic {
    class DayGroup(val dayStart: Long, val label: String, val events: List<Ev>)

    /** Case-insensitive regex; an entry that is not a valid regex is matched as a plain substring. */
    fun compile(patterns: List<String>): List<Regex> = patterns.mapNotNull { p ->
        if (p.isBlank()) null
        else try { Regex(p, RegexOption.IGNORE_CASE) } catch (e: Exception) { Regex(Regex.escape(p), RegexOption.IGNORE_CASE) }
    }

    /** Hide events whose title matches any exclude, unless it also matches an include exception. Runs after all sources merge. */
    fun filter(events: List<Ev>, exclude: List<String>, includeExceptions: List<String>): List<Ev> {
        val ex = compile(exclude)
        if (ex.isEmpty()) return events
        val inc = compile(includeExceptions)
        return events.filter { e -> ex.none { it.containsMatchIn(e.title) } || inc.any { it.containsMatchIn(e.title) } }
    }

    fun startOfDay(t: Long, tz: TimeZone): Long = Calendar.getInstance(tz).apply {
        timeInMillis = t
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun addDays(dayStart: Long, n: Int, tz: TimeZone): Long =
        Calendar.getInstance(tz).apply { timeInMillis = dayStart; add(Calendar.DAY_OF_MONTH, n) }.timeInMillis

    /** "Today", "Tomorrow", else "Mon Nov 24". */
    fun dayLabel(dayStart: Long, now: Long, tz: TimeZone, locale: Locale = Locale.getDefault()): String {
        val today = startOfDay(now, tz)
        return when (dayStart) {
            today -> "Today"
            addDays(today, 1, tz) -> "Tomorrow"
            else -> SimpleDateFormat("EEE MMM d", locale).apply { timeZone = tz }.format(Date(dayStart))
        }
    }

    /** All-day events first, then timed ones by start time (ties by title). */
    fun order(events: List<Ev>): List<Ev> = events.sortedWith(compareBy({ !it.allDay }, { it.start }, { it.title }))

    /**
     * Events from today through [days] days ahead, grouped by day, empty days omitted. Ended timed events are dropped.
     * An event that began before today (multi-day) shows under today; one that spans days shows on its first visible day.
     */
    fun groupByDay(events: List<Ev>, now: Long, days: Int, tz: TimeZone, locale: Locale = Locale.getDefault()): List<DayGroup> {
        val today = startOfDay(now, tz)
        val limit = addDays(today, days, tz)
        val byDay = java.util.TreeMap<Long, MutableList<Ev>>()
        for (e in events) {
            if (!e.allDay && e.end <= now && e.start < now) continue
            if (e.allDay && e.end <= today && e.end > e.start) continue // all-day ended before today
            val d = startOfDay(maxOf(e.start, today), tz)
            if (d >= limit) continue
            byDay.getOrPut(d) { ArrayList() }.add(e)
        }
        return byDay.map { (d, l) -> DayGroup(d, dayLabel(d, now, tz, locale), order(l)) }
    }
}

/** Pure text-fit helper: the largest size that fits the line budget, stepping down to a floor. */
object TextFit {
    /** [linesAt] returns the line count the text needs at a given size. Returns [minSp] when nothing fits. */
    fun pick(maxSp: Float, minSp: Float, stepSp: Float, maxLines: Int, linesAt: (Float) -> Int): Float {
        var sp = maxSp
        while (sp > minSp) {
            if (linesAt(sp) <= maxLines) return sp
            sp -= stepSp
        }
        return minSp
    }
}
