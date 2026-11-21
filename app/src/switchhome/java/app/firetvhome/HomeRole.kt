package app.firetvhome

import android.content.Context

/** Persisted "firetv-home is the active Home" flag; the Keeper only runs while it is set. */
object HomeRole {
    private const val PREFS = "home_role"
    private const val KEY = "active"

    fun isActive(c: Context): Boolean =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    private fun set(c: Context, v: Boolean) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, v).apply()
    }

    fun take(c: Context) {
        set(c, true)
        Keeper.start(c)
    }

    fun release(c: Context) {
        set(c, false)
        Keeper.stop(c)
    }
}
