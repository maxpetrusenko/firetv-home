package app.firetvhome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class CalendarLogicTest {
    private val tz = TimeZone.getTimeZone("America/New_York")
    private fun t(s: String, zone: TimeZone = tz): Long =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { timeZone = zone }.parse(s)!!.time
    private fun timed(title: String, start: String) = Ev(title, t(start), t(start) + 3_600_000L, false)
    private fun allDay(title: String, day: String) = Ev(title, t("$day 00:00"), t("$day 00:00") + 86_400_000L, true)

    private val exclude = listOf("""drop-?\s*in\s*care""")
    private val include = listOf("""parents'?\s*night\s*out""", """parent's\s*night\s*out""", """\bPNO\b""")

    private fun shown(vararg titles: String) =
        CalendarLogic.filter(titles.map { timed(it, "2026-11-24 10:00") }, exclude, include).map { it.title }

    @Test fun dropInCareSpellingsHidden() {
        for (s in listOf("Drop-in care", "Drop in Care", "dropin care", "DROP-IN CARE", "Drop-In Care: Saturday")) {
            assertTrue(s, shown(s).isEmpty())
        }
    }

    @Test fun unrelatedKept() = assertEquals(listOf("Dentist", "Dropbox review"), shown("Dentist", "Dropbox review"))

    @Test fun pnoExceptionWins() {
        val keep = listOf("Drop-in care Parents Night Out", "Drop in care - parent's night out", "Drop-in care PNO", "Dropin care parents' night out")
        assertEquals(keep, shown(*keep.toTypedArray()))
        assertTrue(shown("Drop-in care PNOX").isEmpty())
    }

    @Test fun plainSubstringAndInvalidRegex() {
        val ev = listOf(timed("Soccer (U8", "2026-11-24 10:00"), timed("Chess", "2026-11-24 11:00"))
        assertEquals(listOf("Chess"), CalendarLogic.filter(ev, listOf("soccer (u8"), emptyList()).map { it.title })
        assertEquals(ev.size, CalendarLogic.filter(ev, emptyList(), include).size)
    }

    @Test fun allDayFirstThenByStart() {
        val o = CalendarLogic.order(listOf(timed("B", "2026-11-24 09:00"), timed("A", "2026-11-24 08:00"), allDay("Z", "2026-11-24"), allDay("Y", "2026-11-24")))
        assertEquals(listOf("Y", "Z", "A", "B"), o.map { it.title })
    }

    @Test fun groupingLabelsAndOrder() {
        val now = t("2026-11-21 20:00")
        val g = CalendarLogic.groupByDay(
            listOf(timed("late", "2026-11-21 22:00"), allDay("d0", "2026-11-21"), timed("tm", "2026-11-22 09:00"),
                timed("mon", "2026-11-24 09:00"), timed("past", "2026-11-21 08:00"), timed("far", "2026-12-05 09:00")),
            now, 8, tz, Locale.US)
        assertEquals(listOf("Today", "Tomorrow", "Tue Nov 24"), g.map { it.label })
        assertEquals(listOf("d0", "late"), g[0].events.map { it.title }) // all-day first; ended "past" dropped
        assertEquals(1, g[2].events.size)
    }

    @Test fun windowLengthRespected() {
        val now = t("2026-11-21 08:00")
        val ev = listOf(timed("in", "2026-11-28 09:00"), timed("out", "2026-11-29 09:00"))
        assertEquals(listOf("in"), CalendarLogic.groupByDay(ev, now, 8, tz, Locale.US).flatMap { it.events }.map { it.title })
    }

    @Test fun acrossMidnightTodayVsTomorrow() {
        val ev = listOf(timed("a", "2026-11-22 00:30"))
        assertEquals("Tomorrow", CalendarLogic.groupByDay(ev, t("2026-11-21 23:59"), 8, tz, Locale.US).single().label)
        assertEquals("Today", CalendarLogic.groupByDay(ev, t("2026-11-22 00:05"), 8, tz, Locale.US).single().label)
    }

    @Test fun timezoneChangesTheDay() {
        // 2026-11-22 03:00 UTC is Nov 21 22:00 in New York, Nov 22 in UTC.
        val utc = TimeZone.getTimeZone("UTC")
        val instant = t("2026-11-22 03:00", utc)
        val ev = listOf(Ev("x", instant, instant + 3_600_000L, false))
        val now = t("2026-11-21 18:00", utc)
        assertEquals("Today", CalendarLogic.groupByDay(ev, now, 8, tz, Locale.US).single().label)
        assertEquals("Tomorrow", CalendarLogic.groupByDay(ev, now, 8, utc, Locale.US).single().label)
    }

    @Test fun multiDayAllDayShowsFromToday() {
        val e = Ev("trip", t("2026-11-19 00:00"), t("2026-11-23 00:00"), true)
        val g = CalendarLogic.groupByDay(listOf(e), t("2026-11-21 08:00"), 8, tz, Locale.US)
        assertEquals("Today", g.single().label)
        assertTrue(CalendarLogic.groupByDay(listOf(e), t("2026-11-24 08:00"), 8, tz, Locale.US).isEmpty())
    }

    @Test fun textFitStepsDownToFloor() {
        // Lines needed scale inversely with size: 40 chars-per-line budget at 18sp.
        fun lines(len: Int) = { sp: Float -> Math.ceil((len * sp / (18f * 40)).toDouble()).toInt() }
        assertEquals(18f, TextFit.pick(18f, 13f, 1f, 2, lines(70)), 0f)       // 2 lines at 18
        assertEquals(16f, TextFit.pick(18f, 13f, 1f, 2, lines(90)), 0f)       // 90*16/720 = 2.0 -> 2 lines
        assertEquals(13f, TextFit.pick(18f, 13f, 1f, 2, lines(400)), 0f)      // never fits: floor
        assertEquals(18f, TextFit.pick(18f, 13f, 1f, 2, lines(5)), 0f)
    }

    @Test fun nextDayFlightsSurviveGroupingAndBookingCodeStrip() {
        val now = t("2026-11-22 23:30")
        val evs = listOf(
            timed("Flight AAA to BBB (team offsite) conf ABC123", "2026-11-23 08:08"),
            timed("Flight BBB to AAA conf XYZ789", "2026-11-23 21:24"),
        )
        val g = CalendarLogic.groupByDay(CalendarLogic.filter(evs, exclude, include), now, 8, tz, Locale.US)
        assertEquals(listOf("Tomorrow"), g.map { it.label })
        assertEquals(
            listOf("Flight AAA to BBB (team offsite)", "Flight BBB to AAA"),
            g[0].events.map { BookingCodes.strip(it.title) },
        )
    }
}
