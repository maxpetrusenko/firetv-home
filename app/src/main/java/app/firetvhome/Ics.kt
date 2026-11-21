package app.firetvhome

import java.util.Calendar
import java.util.TimeZone

/**
 * Small self-contained ICS reader. Parses VEVENTs and expands RRULE (DAILY/WEEKLY/MONTHLY/YEARLY with
 * INTERVAL, COUNT, UNTIL, BYDAY incl. 2TU / -1FR, BYMONTHDAY, BYMONTH), EXDATE and RECURRENCE-ID
 * overrides, returning only instances that overlap the window [ws, we).
 */
object Ics {
    private class P(val params: Map<String, String>, val value: String)
    private class Raw {
        val props = HashMap<String, P>()
        val exdates = ArrayList<P>()
    }
    private class T(val ms: Long, val allDay: Boolean, val tz: TimeZone)
    private class Info(
        val raw: Raw, val uid: String, val title: String, val start: Long, val allDay: Boolean,
        val tz: TimeZone, val dur: Long, val days: Int, val recId: Long?, val cancelled: Boolean,
    )

    private const val DAY = 86_400_000L
    private const val MAX_ITER = 20_000
    private val DOW = mapOf("SU" to 1, "MO" to 2, "TU" to 3, "WE" to 4, "TH" to 5, "FR" to 6, "SA" to 7)
    private val DUR = Regex("([+-])?P(?:(\\d+)W)?(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?")
    private val BYDAY = Regex("([+-]?\\d+)?(SU|MO|TU|WE|TH|FR|SA)")

    fun parse(text: String, ws: Long, we: Long): List<Ev> {
        val lines = text.replace("\r\n", "\n").replace("\r", "\n").replace(Regex("\n[ \t]"), "").split('\n')
        val raws = ArrayList<Raw>()
        var cur: Raw? = null
        var nested = 0
        for (line in lines) {
            if (line == "BEGIN:VEVENT") { cur = Raw(); nested = 0; continue }
            if (cur == null) continue
            if (line.startsWith("BEGIN:")) { nested++; continue }
            if (line == "END:VEVENT") { raws.add(cur); cur = null; continue }
            if (line.startsWith("END:")) { nested--; continue }
            if (nested > 0) continue // VALARM etc.
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val head = line.substring(0, idx).split(';')
            val params = HashMap<String, String>()
            for (i in 1 until head.size) {
                val kv = head[i].split('=', limit = 2)
                if (kv.size == 2) params[kv[0].uppercase()] = kv[1].trim('"')
            }
            val p = P(params, line.substring(idx + 1))
            val name = head[0].uppercase()
            if (name == "EXDATE") cur.exdates.add(p) else cur.props[name] = p
        }
        val infos = raws.mapNotNull { try { info(it) } catch (e: Exception) { null } }
        val overridden = HashMap<String, HashSet<Long>>()
        for (i in infos) i.recId?.let { overridden.getOrPut(i.uid) { HashSet() }.add(it) }
        val out = ArrayList<Ev>()
        for (i in infos) {
            if (i.cancelled) continue
            try {
                val rr = i.raw.props["RRULE"]?.value
                if (i.recId != null || rr == null) emit(i, i.start, ws, we, out)
                else {
                    val skip = HashSet<Long>(overridden[i.uid] ?: emptySet<Long>())
                    for (x in i.raw.exdates) for (v in x.value.split(',')) if (v.isNotBlank()) skip.add(time(P(x.params, v)).ms)
                    expand(i, rr, skip, ws, we, out)
                }
            } catch (e: Exception) { }
        }
        return out
    }

    private fun info(r: Raw): Info? {
        val ds = r.props["DTSTART"] ?: return null
        val s = time(ds)
        val dur = r.props["DTEND"]?.let { time(it).ms - s.ms }
            ?: r.props["DURATION"]?.let { duration(it.value) }
            ?: if (s.allDay) DAY else 0L
        return Info(
            r, r.props["UID"]?.value ?: "", unescape(r.props["SUMMARY"]?.value ?: "(no title)"),
            s.ms, s.allDay, s.tz, dur, maxOf(1, Math.round(dur / DAY.toDouble()).toInt()),
            r.props["RECURRENCE-ID"]?.let { time(it).ms },
            r.props["STATUS"]?.value?.trim().equals("CANCELLED", true),
        )
    }

    private fun cal(tz: TimeZone, y: Int, m: Int, d: Int, h: Int, mi: Int, s: Int): Long =
        Calendar.getInstance(tz).apply { clear(); set(y, m, d, h, mi, s) }.timeInMillis

    private fun zone(id: String): TimeZone {
        val tz = TimeZone.getTimeZone(id)
        return if (tz.id == "GMT" && id != "GMT" && id != "UTC") TimeZone.getDefault() else tz
    }

    private fun time(p: P): T {
        val v = p.value.trim()
        val def = TimeZone.getDefault()
        val y = v.substring(0, 4).toInt()
        val mo = v.substring(4, 6).toInt() - 1
        val d = v.substring(6, 8).toInt()
        if (p.params["VALUE"] == "DATE" || v.length == 8) return T(cal(def, y, mo, d, 0, 0, 0), true, def)
        val tz = if (v.endsWith("Z")) TimeZone.getTimeZone("UTC") else p.params["TZID"]?.let { zone(it) } ?: def
        val sec = if (v.length >= 15) v.substring(13, 15).toInt() else 0
        return T(cal(tz, y, mo, d, v.substring(9, 11).toInt(), v.substring(11, 13).toInt(), sec), false, tz)
    }

    private fun duration(s: String): Long {
        val g = DUR.matchEntire(s.trim())?.groupValues ?: return 0
        fun n(i: Int) = g[i].toLongOrNull() ?: 0L
        val ms = (n(2) * 7 + n(3)) * DAY + n(4) * 3_600_000L + n(5) * 60_000L + n(6) * 1000L
        return if (g[1] == "-") -ms else ms
    }

    private fun unescape(s: String): String {
        if (s.indexOf('\\') < 0) return s
        val b = StringBuilder()
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '\\' && i + 1 < s.length) {
                val n = s[++i]
                b.append(if (n == 'n' || n == 'N') ' ' else n)
            } else b.append(ch)
            i++
        }
        return b.toString()
    }

    private fun end(i: Info, s: Long): Long =
        if (i.allDay) Calendar.getInstance(i.tz).apply { timeInMillis = s; add(Calendar.DAY_OF_MONTH, i.days) }.timeInMillis
        else s + i.dur

    private fun emit(i: Info, s: Long, ws: Long, we: Long, out: MutableList<Ev>) {
        val e = end(i, s)
        val hit = if (e > s) s < we && e > ws else s >= ws && s < we
        if (hit) out.add(Ev(i.title, s, e, i.allDay, i.uid))
    }

    private fun ints(v: String?) = v?.split(',')?.mapNotNull { it.trim().toIntOrNull() } ?: emptyList()

    private fun expand(i: Info, rr: String, skip: Set<Long>, ws: Long, we: Long, out: MutableList<Ev>) {
        val r = rr.split(';').associate { val kv = it.split('=', limit = 2); kv[0].uppercase() to kv.getOrElse(1) { "" } }
        val freq = r["FREQ"] ?: return
        val interval = maxOf(1, r["INTERVAL"]?.toIntOrNull() ?: 1)
        val count = r["COUNT"]?.toIntOrNull() ?: Int.MAX_VALUE
        val until = r["UNTIL"]?.let { u ->
            if (u.length == 8) cal(i.tz, u.substring(0, 4).toInt(), u.substring(4, 6).toInt() - 1, u.substring(6, 8).toInt(), 23, 59, 59)
            else time(P(mapOf("TZID" to i.tz.id), u)).ms
        } ?: Long.MAX_VALUE
        val byDay = (r["BYDAY"]?.split(',') ?: emptyList()).mapNotNull { BYDAY.matchEntire(it.trim().uppercase()) }
            .map { (it.groupValues[1].toIntOrNull() ?: 0) to DOW.getValue(it.groupValues[2]) }
        val byMonthDay = ints(r["BYMONTHDAY"])
        val byMonth = ints(r["BYMONTH"])

        val base = Calendar.getInstance(i.tz).apply { timeInMillis = i.start }
        val y0 = base.get(Calendar.YEAR); val m0 = base.get(Calendar.MONTH); val d0 = base.get(Calendar.DAY_OF_MONTH)
        val hh = base.get(Calendar.HOUR_OF_DAY); val mi = base.get(Calendar.MINUTE); val ss = base.get(Calendar.SECOND)
        val dow0 = base.get(Calendar.DAY_OF_WEEK)
        fun at(y: Int, m: Int, d: Int) = cal(i.tz, y, m, d, hh, mi, ss)
        fun dowOf(ms: Long) = Calendar.getInstance(i.tz).apply { timeInMillis = ms }.get(Calendar.DAY_OF_WEEK)

        fun monthCands(y: Int, m: Int): List<Long> {
            val first = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(y, m, 1) }
            val dim = first.getActualMaximum(Calendar.DAY_OF_MONTH)
            val fd = first.get(Calendar.DAY_OF_WEEK)
            fun dow(d: Int) = (fd - 1 + d - 1) % 7 + 1
            val days = sortedSetOf<Int>()
            if (byMonthDay.isNotEmpty()) {
                for (md in byMonthDay) { val d = if (md > 0) md else dim + md + 1; if (d in 1..dim && (byDay.isEmpty() || byDay.any { it.second == dow(d) })) days.add(d) }
            } else if (byDay.isNotEmpty()) {
                for ((ord, wd) in byDay) {
                    val all = (1..dim).filter { dow(it) == wd }
                    if (ord == 0) days.addAll(all)
                    else (if (ord > 0) all.getOrNull(ord - 1) else all.getOrNull(all.size + ord))?.let { days.add(it) }
                }
            } else if (d0 <= dim) days.add(d0)
            return days.map { at(y, m, it) }
        }

        // Without COUNT, jump near the window instead of walking from DTSTART.
        var k = 0
        if (count == Int.MAX_VALUE) {
            val diff = (ws - i.start - maxOf(i.dur, i.days * DAY) - 2 * DAY) / DAY
            val unit = when (freq) { "DAILY" -> 1L; "WEEKLY" -> 7L; "MONTHLY" -> 32L; else -> 366L }
            k = maxOf(0L, diff / unit / interval - 1).toInt()
        }
        var n = 0
        var iter = 0
        while (iter++ < MAX_ITER) {
            val cands: List<Long> = when (freq) {
                "DAILY" -> {
                    val ms = at(y0, m0, d0 + k * interval)
                    val c = Calendar.getInstance(i.tz).apply { timeInMillis = ms }
                    val dim = c.getActualMaximum(Calendar.DAY_OF_MONTH)
                    val dom = c.get(Calendar.DAY_OF_MONTH)
                    val ok = (byMonth.isEmpty() || c.get(Calendar.MONTH) + 1 in byMonth) &&
                        (byDay.isEmpty() || byDay.any { it.second == c.get(Calendar.DAY_OF_WEEK) }) &&
                        (byMonthDay.isEmpty() || dom in byMonthDay || (dom - dim - 1) in byMonthDay)
                    if (ok) listOf(ms) else emptyList()
                }
                "WEEKLY" -> {
                    val off = (dow0 - Calendar.MONDAY + 7) % 7
                    val targets = (if (byDay.isEmpty()) listOf(dow0) else byDay.map { it.second }).map { (it - Calendar.MONDAY + 7) % 7 }
                    targets.sorted().map { at(y0, m0, d0 - off + it + k * interval * 7) }
                        .filter { byMonth.isEmpty() || Calendar.getInstance(i.tz).apply { timeInMillis = it }.get(Calendar.MONTH) + 1 in byMonth }
                }
                "MONTHLY" -> {
                    val idx = m0 + k * interval
                    val ms = monthCands(y0 + Math.floorDiv(idx, 12), Math.floorMod(idx, 12))
                    if (byMonth.isEmpty()) ms else ms.filter { Calendar.getInstance(i.tz).apply { timeInMillis = it }.get(Calendar.MONTH) + 1 in byMonth }
                }
                "YEARLY" -> {
                    val y = y0 + k * interval
                    (if (byMonth.isEmpty()) listOf(m0) else byMonth.map { it - 1 }).flatMap { monthCands(y, it) }
                }
                else -> return
            }
            for (s in cands.sorted()) {
                if (s < i.start) continue
                if (s > until || s >= we) return
                if (++n > count) return
                if (s !in skip) emit(i, s, ws, we, out)
            }
            k++
        }
    }
}
