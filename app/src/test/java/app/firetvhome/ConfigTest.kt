package app.firetvhome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ConfigTest {
    private val ha = """"ha_url":"http://ha.example:8123/","ha_token":"t""""

    @Test fun emptyAndBadJson() {
        for (t in listOf("", "   ", "{not json")) {
            val c = Config.parse(t)
            assertFalse(c.hasCalendar); assertFalse(c.hasTodos)
            assertEquals(Config.DEFAULT_APPS, c.appOrder)
            assertEquals(10, c.alertMinutes)
            assertEquals(Config.BG_DEFAULT, c.background)
        }
    }

    @Test fun legacyHaCalendarAndTodos() {
        val c = Config.parse("""{$ha,"calendar_entity":"calendar.home","ics_url":"http://x/y.ics",
            "todo_entities":[{"entity":"todo.shopping_list","label":"To buy"},"todo.chores"]}""")
        assertEquals("http://ha.example:8123", c.haUrl)
        val cal = c.calendars.single() as CalSource.HaCalendar // HA wins over ics_url, as in the old behaviour
        assertEquals("calendar.home", cal.entity)
        val todo = c.reminders.single() as RemSource.HaTodo
        assertEquals(listOf("todo.shopping_list" to "To buy", "todo.chores" to "Chores"), todo.entities)
        assertTrue(c.hasCalendar && c.hasTodos)
    }

    @Test fun legacyIcsOnly() {
        val c = Config.parse("""{"ics_url":"http://x/y.ics"}""")
        assertEquals("http://x/y.ics", (c.calendars.single() as CalSource.IcsFeed).url)
        assertFalse(c.hasTodos)
    }

    @Test fun legacyHaEntityWithoutTokenFallsBackToIcs() {
        val c = Config.parse("""{"ha_url":"http://h","calendar_entity":"calendar.a","ics_url":"http://x/y.ics"}""")
        assertTrue(c.calendars.single() is CalSource.IcsFeed)
    }

    @Test fun providerLists() {
        val c = Config.parse("""{$ha,
          "calendars":[
            {"type":"ics","url":"http://a/a.ics","label":"Work"},
            {"type":"ha_calendar","entity":"calendar.b","label":"Home"},
            {"type":"ics","url":""},
            {"type":"nope","url":"x"}],
          "reminders":[
            {"type":"ha_todo","entities":[{"entity":"todo.x","label":"X"}]},
            {"type":"apple_reminders","url":"http://mac.example:8765/reminders.json"},
            {"type":"apple_reminders","file":"reminders.json"}],
          "background":"bali","calendar_alert_minutes":0}""", File("/base"))
        assertEquals(2, c.calendars.size)
        assertEquals("Work", c.calendars[0].label)
        assertEquals("calendar.b", (c.calendars[1] as CalSource.HaCalendar).entity)
        assertTrue(c.showCalLabels)
        assertEquals(3, c.reminders.size)
        assertEquals("http://mac.example:8765/reminders.json", (c.reminders[1] as RemSource.AppleReminders).url)
        assertEquals(File("/base", "reminders.json").path, (c.reminders[2] as RemSource.AppleReminders).file)
        assertEquals(Config.BG_BALI, c.background)
        assertEquals(0, c.alertMinutes)
    }

    @Test fun providerListReplacesLegacyKeys() {
        val c = Config.parse("""{"ics_url":"http://old/o.ics","calendars":[{"type":"ics","url":"http://new/n.ics"}]}""")
        assertEquals("http://new/n.ics", (c.calendars.single() as CalSource.IcsFeed).url)
    }

    @Test fun haProvidersNeedCredentials() {
        val c = Config.parse("""{"calendars":[{"type":"ha_calendar","entity":"calendar.a"}],
            "reminders":[{"type":"ha_todo","entities":[{"entity":"todo.a"}]}]}""")
        assertFalse(c.hasCalendar); assertFalse(c.hasTodos)
    }

    @Test fun themeAliasAndUnknownFallBack() {
        assertEquals(Config.BG_BALI, Config.parse("""{"theme":"BALI"}""").background)
        assertEquals(Config.BG_DEFAULT, Config.parse("""{"background":"mars"}""").background)
        assertEquals(Config.BG_BALI, Config.parse("""{"background":"bali","theme":"default"}""").background)
    }

    @Test fun singleSourceHasNoLabels() {
        assertFalse(Config.parse("""{"calendars":[{"type":"ics","url":"http://a","label":"A"}]}""").showCalLabels)
    }
}
