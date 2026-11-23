package app.firetvhome

import org.junit.Assert.assertEquals
import org.junit.Test

class TrimPlanTest {
    // Rows: header(0) item item header(0) item item  -> bottoms every 30 px
    private val w = listOf(0, 1, 1, 0, 1, 1)
    private val b = listOf(30, 60, 90, 120, 150, 180)

    @Test fun fitsKeepsEverything() {
        val r = TrimPlan.plan(b, w, 180, 26)
        assertEquals(6, r.keep); assertEquals(0, r.hidden)
    }

    @Test fun overflowShowsPlusNInsteadOfDropping() {
        val r = TrimPlan.plan(b, w, 150, 26) 
        assertEquals(3, r.keep) // k=4 would end on the header at row 4: it goes too
        assertEquals(2, r.hidden) // the two items of the second group
    }

    @Test fun neverLeavesTrailingHeader() {
        val r = TrimPlan.plan(b, w, 125, 26) // k=3 (bottom 90), last kept is item
        assertEquals(3, r.keep)
        assertEquals(2, r.hidden)
    }

    @Test fun existingMoreRowCountsItsItems() {
        val r = TrimPlan.plan(listOf(30, 60, 90, 120), listOf(0, 1, 1, 5), 100, 26)
        assertEquals(2, r.keep); assertEquals(6, r.hidden) // item + the old "+5 more"
    }

    @Test fun alwaysKeepsFirstRow() {
        val r = TrimPlan.plan(listOf(300, 400), listOf(1, 1), 50, 26)
        assertEquals(1, r.keep); assertEquals(1, r.hidden)
    }
}
