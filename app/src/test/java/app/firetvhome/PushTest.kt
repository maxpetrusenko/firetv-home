package app.firetvhome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

class PushTest {
    private fun doc(events: String, gen: String = "1795300000000", v: Int = 1) =
        """{"v":$v,"generated_at_ms":$gen,"source":"gcal-sync","events":[$events]}"""

    private fun local(s: String) = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(s)!!.time

    @Test fun allDay() {
        val c = Push.parseCalendar(doc("""{"id":"a","title":"Trip","start":"2026-11-22","end":"2026-11-23","all_day":true,"location":null}"""))!!
        val e = c.events.single()
        assertTrue(e.allDay)
        assertEquals(local("2026-11-22"), e.start)
        assertEquals(local("2026-11-23"), e.end)
        assertEquals("a", e.uid)
        assertEquals("Trip", e.title)
        assertEquals(1795300000000L, c.generatedAtMs)
    }

    @Test fun timedWithOffsets() {
        val c = Push.parseCalendar(doc(
            """{"id":"1","title":"A","start":"2026-11-22T09:00:00-05:00","end":"2026-11-22T10:00:00-05:00","all_day":false,"location":"x"},
               {"id":"2","title":"B","start":"2026-11-22T14:00:00Z","end":"2026-11-22T15:00:00.000Z","all_day":false,"location":null},
               {"id":"3","title":"C","start":"2026-11-22T14:00:00+05:30","end":"2026-11-22T15:00:00+05:30","all_day":false}"""))!!
        assertEquals(3, c.events.size)
        // 09:00-05:00 == 14:00Z
        assertEquals(c.events[0].start, c.events[1].start)
        assertEquals(3_600_000L, c.events[0].end - c.events[0].start)
        assertEquals(3_600_000L, c.events[1].end - c.events[1].start)
        // 14:00+05:30 == 08:30Z, 5.5 h before 14:00Z
        assertEquals(c.events[1].start - 19_800_000L, c.events[2].start)
        assertFalse(c.events[0].allDay)
    }

    @Test fun badEventIsSkippedOthersKept() {
        val c = Push.parseCalendar(doc("""{"id":"1","title":"ok","start":"2026-11-22","end":"2026-11-22","all_day":true},{"id":"2","title":"bad","start":"nope","end":"x"},{"title":"nostart"}"""))!!
        assertEquals(listOf("ok"), c.events.map { it.title })
    }

    @Test fun missingEndFallsBackToStart() {
        val e = Push.parseCalendar(doc("""{"id":"1","title":"t","start":"2026-11-22T09:00:00Z","all_day":false}"""))!!.events.single()
        assertEquals(e.start, e.end)
    }

    @Test fun malformedFallsBack() {
        assertNull(Push.parseCalendar(""))
        assertNull(Push.parseCalendar("not json"))
        assertNull(Push.parseCalendar("""{"v":1,"generated_at_ms":1}"""))            // no events
        assertNull(Push.parseCalendar("""{"v":1,"events":[]}"""))                    // no stamp
        assertNull(Push.parseCalendar(doc("", v = 2)))                               // unknown version
        assertNull(Push.parseCalendar("""{"v":1,"generated_at_ms":"x","events":[]}"""))
        assertNull(Push.parseCalendar("""{"v":1,"generated_at_ms":1,"events":"no"}"""))
        assertNotNull(Push.parseCalendar(doc("")))                                    // empty list is valid
    }

    @Test fun staleness() {
        val now = 10_000_000_000L
        assertTrue(Push.isFresh(now, now))
        assertTrue(Push.isFresh(now - Push.FRESH_MS + 1, now))
        assertFalse(Push.isFresh(now - Push.FRESH_MS, now))
        assertFalse(Push.isFresh(now - 3 * 3_600_000L, now))
        assertTrue(Push.isFresh(now + 60_000L, now))          // small clock skew
        assertFalse(Push.isFresh(now + 3_600_000L, now))      // far future is not trusted
        assertFalse(Push.isFresh(0, now))
    }

    @Test fun routing() {
        assertEquals(setOf("calendar"), Push.route("calendar"))
        assertEquals(setOf("reminders"), Push.route("reminders"))
        assertEquals(setOf("trips"), Push.route(" Trips "))
        assertEquals(Push.ALL, Push.route("all"))
        assertEquals(Push.ALL, Push.route(null))
        assertEquals(Push.ALL, Push.route(""))
        assertEquals(emptySet<String>(), Push.route("bogus"))
        assertEquals(setOf("calendar", "reminders", "trips"), Push.ALL)
    }

    @Test fun defaultManifestHasNoHomeCategory() {
        val main = java.io.File("src/main/AndroidManifest.xml").readText()
        assertFalse("default manifest must not declare HOME", main.contains("category.HOME"))
        assertTrue(main.contains("category.LAUNCHER"))
        assertTrue(main.contains("category.LEANBACK_LAUNCHER"))
        assertTrue(main.contains("android.permission.DUMP"))
        val overlay = java.io.File("src/switchhome/AndroidManifest.xml").readText()
        assertTrue("HOME belongs only in the switchhome overlay", overlay.contains("category.HOME"))
    }
}
