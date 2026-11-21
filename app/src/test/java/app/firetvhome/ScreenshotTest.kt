package app.firetvhome

import android.view.View
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import org.junit.Rule
import org.junit.Test
import java.util.Calendar

/**
 * Renders the real home screen views (HomeScreen, Ui) with invented demo data, off device, for docs/screenshot.png.
 * Plain `./gradlew test` only renders; `./gradlew recordPaparazziDebug` writes the PNG under app/src/test/snapshots.
 */
class ScreenshotTest {
    @get:Rule val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.NEXUS_5.copy(
            screenWidth = 1920, screenHeight = 1080, xdpi = 213, ydpi = 213,
            density = Density.TV, orientation = ScreenOrientation.LANDSCAPE, softButtons = false,
        ),
        theme = "AppTheme",
    )

    private fun at(dayOffset: Int, hour: Int, minute: Int): Long = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_MONTH, dayOffset); set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test fun homeWithDemoData() {
        val ctx = paparazzi.context
        val now = at(0, 8, 0)
        val screen = HomeScreen(ctx, Config.BG_DEFAULT)
        val events = listOf(
            Ev("Dentist", at(0, 15, 30), at(0, 16, 15), false, "1", "Home"),
            Ev("Team call", at(1, 10, 0), at(1, 10, 30), false, "2", "Work"),
            Ev("Soccer practice", at(1, 17, 30), at(1, 19, 0), false, "3", "Home"),
            Ev("Pick up groceries", at(2, 18, 0), at(2, 18, 45), false, "4", "Home"),
            Ev("Project review", at(3, 14, 0), at(3, 15, 0), false, "5", "Work"),
            Ev("Library day", at(4, 0, 0), at(5, 0, 0), true, "6", "Home"),
        )
        // layoutlib freezes its own clock, so pin the clock views to the demo time with literal patterns.
        fun literal(pattern: String) = "'" + java.text.SimpleDateFormat(pattern, java.util.Locale.US).format(java.util.Date(now)) + "'"
        screen.clock.format12Hour = literal("h:mm"); screen.clock.format24Hour = literal("HH:mm")
        screen.ampm.format12Hour = literal("a"); screen.ampm.format24Hour = ""
        screen.date.format12Hour = literal("EEEE, MMMM d"); screen.date.format24Hour = literal("EEEE, MMMM d")
        screen.renderCalendar(true, events, false, true, now)
        screen.renderReminders(
            true,
            listOf(
                TodoList("Household", listOf("Buy milk", "Return library books", "Water the plants")),
                TodoList("Shopping", listOf("Light bulbs", "Printer paper")),
            ),
            false,
        )
        screen.renderNotifs(listOf(
            NotifService.Item("Messages", "Package delivered", "Your parcel was left at the front door."),
            NotifService.Item("Mail", "Weekly summary", "3 new messages"),
        ))
        val tiles = listOf(
            "Video" to R.drawable.ic_launcher, "Music" to R.drawable.ic_launcher, "Browser" to R.drawable.ic_launcher,
            "Photos" to R.drawable.ic_launcher, "All apps" to R.drawable.ic_apps, "Settings" to R.drawable.ic_settings,
        )
        for ((label, icon) in tiles) screen.appsRow.addView(Ui.tile(ctx, label, 98, {}, iconRes = icon))
        paparazzi.snapshot(screen.view as View)
    }
}
