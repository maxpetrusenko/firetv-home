package app.firetvhome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class ConfigBirthdaysTest {
    private val tz = TimeZone.getTimeZone("America/New_York")
    private fun day(s: String) = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = tz }.parse(s)!!.time

    @Test fun configDefaults() {
        val c = Config.parse("{}")
        assertTrue(c.exclude.isEmpty()); assertTrue(c.includeExceptions.isEmpty())
        assertEquals(8, c.calDays)
        assertNull(c.birthdays)
    }

    @Test fun configFilterAndBirthdays() {
        val c = Config.parse("""{"calendar":{"exclude":["a","  ","b"],"include_exceptions":["c"],"days":10},
            "birthdays":{"type":"BLOCK"}}""")
        assertEquals(listOf("a", "b"), c.exclude)
        assertEquals(listOf("c"), c.includeExceptions)
        assertEquals(10, c.calDays)
        assertEquals("block", c.birthdays!!.type)
        assertEquals(30, c.birthdays!!.days)
    }

    @Test fun topLevelFilterKeysAndBirthdayDays() {
        val c = Config.parse("""{"exclude":["x"],"include_exceptions":["y"],"calendar_days":99,
            "birthdays":{"type":"ics","url":"https://example.com/b.ics","days":14}}""")
        assertEquals(listOf("x"), c.exclude)
        assertEquals(31, c.calDays) // clamped
        assertEquals("https://example.com/b.ics", c.birthdays!!.url)
        assertEquals(14, c.birthdays!!.days)
    }

    @Test fun legacyAlertMinutesIgnored() {
        val c = Config.parse("""{"calendar_alert_minutes":10,"ics_url":"http://x/y.ics"}""")
        assertEquals("http://x/y.ics", (c.calendars.single() as CalSource.IcsFeed).url)
    }

    @Test fun birthdayTypes() {
        for (t in listOf("block", "ics", "ha", "google")) assertEquals(t, Config.parse("""{"birthdays":{"type":"$t"}}""").birthdays!!.type)
        assertNull(Config.parse("""{"birthdays":{}}""").birthdays)
    }

    @Test fun birthdayBlockParse() {
        val items = Birthdays.parseBlock("""{"generated":"2026-11-21T00:00:00Z","source":"s","items":[
            {"date":"2026-11-29","name":"Ann","title":"Ann's Birthday"},
            {"date":"bad","name":"Bad","title":"x"},
            {"date":"2026-11-21","name":"","title":"Bob"},
            {"date":"2026-11-22","name":"","title":""}]}""", tz)!!
        assertEquals(listOf("Ann", "Bob"), items.map { it.name })
        assertNull(Birthdays.parseBlock("not json", tz))
        assertNull(Birthdays.parseBlock("""{"items":5}""", tz))
    }

    @Test fun windowAndLines() {
        val now = day("2026-11-21") + 15 * 3_600_000L
        val items = listOf("2026-11-20" to "Past", "2026-11-21" to "Now", "2026-11-22" to "Tom", "2026-11-29" to "Ann",
            "2026-12-20" to "Edge", "2026-12-21" to "Out").map { Birthdays.Item(day(it.first), it.second) }
        val w = Birthdays.window(items, now, 30, tz)
        assertEquals(listOf("Now", "Tom", "Ann", "Edge"), w.map { it.name })
        assertEquals(listOf("Today — Now", "Tomorrow — Tom", "Sun Nov 29 — Ann"), w.take(3).map { Birthdays.line(it, now, tz, Locale.US) })
        assertTrue(Birthdays.window(items, now, 1, tz).size == 1)
    }

    @Test fun pushRoutesBirthdays() {
        assertEquals(setOf("birthdays"), Push.route("birthdays"))
        assertNotNull(Push.ALL.find { it == "birthdays" })
    }
}
