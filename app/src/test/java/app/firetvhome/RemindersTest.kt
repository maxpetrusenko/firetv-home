package app.firetvhome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemindersTest {
    @Test fun parsesListsAndSortsByDue() {
        val l = Reminders.parseApple("""{"updated":"2026-01-01T00:00:00Z","lists":[
          {"name":"Home","items":[
            {"title":"No date","due":null},
            {"title":"Later","due":"2026-03-02T09:00:00Z"},
            {"title":"Sooner","due":"2026-03-01"},
            {"title":"  ","due":null}]},
          {"name":"","items":[{"title":"x","due":null}]},
          {"name":"Empty","items":[]}]}""")!!
        assertEquals(2, l.size)
        assertEquals("Home", l[0].label)
        assertEquals(listOf("Sooner", "Later", "No date"), l[0].items)
        assertEquals("Empty", l[1].label)
        assertEquals(emptyList<String>(), l[1].items)
    }

    @Test fun missingDueKeyAndBadDueAreFine() {
        val l = Reminders.parseApple("""{"lists":[{"name":"A","items":[{"title":"t1"},{"title":"t2","due":"garbage"}]}]}""")!!
        assertEquals(listOf("t1", "t2"), l.single().items)
    }

    @Test fun notTheSchema() {
        assertNull(Reminders.parseApple("{}"))
        assertNull(Reminders.parseApple("[]"))
        assertNull(Reminders.parseApple("nope"))
    }
}
