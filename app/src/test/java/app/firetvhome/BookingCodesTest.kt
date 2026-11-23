package app.firetvhome

import org.junit.Assert.assertEquals
import org.junit.Test

class BookingCodesTest {
    private fun s(t: String) = BookingCodes.strip(t)

    @Test fun trailingConf() {
        assertEquals("Flight AAA to BBB (team offsite)", s("Flight AAA to BBB (team offsite) conf ABC123"))
        assertEquals("Flight BBB to AAA", s("Flight BBB to AAA conf XYZ789"))
    }
    @Test fun variants() {
        assertEquals("Hotel Example", s("Hotel Example - Conf: ABC123"))
        assertEquals("Airline XY 123", s("Airline XY 123, confirmation ABC123"))
        assertEquals("XY 1311 AAA to BBB", s("XY 1311 AAA to BBB PNR XK4T9Q"))
        assertEquals("Dinner at the bistro", s("Dinner at the bistro (conf #A1B2C3D4)"))
        assertEquals("Hotel - Late checkin", s("Hotel - conf ABCDE1 - Late checkin"))
        assertEquals("Car rental, pickup 9am", s("Car rental, conf QWE123, pickup 9am"))
    }
    @Test fun noFalsePositives() {
        for (t in listOf("Conference call", "Confirm dentist", "Conf call with Sam", "Confirmation of lease", "conf room B",
            "Confidential review", "Team conf CALL", "PNR report review", "Conf lowercase abc123", "Conference ROOMS"))
            assertEquals(t, s(t))
    }
    @Test fun disabledAndEmpty() {
        assertEquals("X conf ABC123", BookingCodes.strip("X conf ABC123", enabled = false))
        assertEquals("conf ABC123", s("conf ABC123"))
    }
}
