package app.firetvhome

import org.junit.Assert.assertEquals
import org.junit.Test

class MergeTest {
    private fun ev(t: String, s: Long, src: String) = Ev(t, s, s + 1, false, "", src)

    @Test fun mergesSortsAndKeepsLabels() {
        val a = listOf(ev("a2", 200, "Work"), ev("a1", 100, "Work"))
        val b = listOf(ev("b1", 150, "Home"), ev("b0", 100, "Home"))
        val m = Data.mergeEvents(listOf(a, b))
        assertEquals(listOf("a1", "b0", "b1", "a2"), m.map { it.title }) // equal starts keep source order
        assertEquals(listOf("Work", "Home", "Home", "Work"), m.map { it.source })
    }

    @Test fun emptyInputs() {
        assertEquals(0, Data.mergeEvents(emptyList()).size)
        assertEquals(1, Data.mergeEvents(listOf(emptyList(), listOf(ev("x", 1, "")))).size)
    }

    @Test fun copyWithSourceKeepsEvent() {
        val e = Ev("t", 5, 9, true, "u").copy("Lbl")
        assertEquals("Lbl", e.source); assertEquals("u", e.uid); assertEquals(true, e.allDay)
    }
}
