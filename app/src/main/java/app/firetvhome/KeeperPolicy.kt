package app.firetvhome

/**
 * Pure decision logic for the Keeper (no Android dependencies).
 *
 * dumbtv runs its own keeper that moves itself over Amazon's home. To avoid two tasks being moved
 * at the same moment (which wedged the window manager), we never react to Amazon's home right
 * away. We react to dumbtv only once it has settled, and fall back to reacting to Amazon's home
 * if it stays in front long enough that dumbtv's keeper is evidently not running.
 */
class KeeperPolicy(private val ownPkg: String = OWN_PKG) {
    enum class Action { NONE, MOVE_TO_FRONT }

    private var handledTs = 0L

    /**
     * @param pkg class of the last MOVE_TO_FOREGROUND event seen (null if none)
     * @param ts timestamp of that event, ms
     * @param lastMoveAt when we last moved our own task, ms (0 if never)
     */
    fun decide(pkg: String?, cls: String?, ts: Long, now: Long, lastMoveAt: Long, roleActive: Boolean): Action {
        if (!roleActive || pkg == null || pkg == ownPkg) return Action.NONE
        if (ts == handledTs) return Action.NONE
        if (lastMoveAt != 0L && now - lastMoveAt < COOLDOWN_MS) return Action.NONE
        val age = now - ts
        val move = when {
            pkg == DUMBTV_PKG -> age >= DUMBTV_SETTLE_MS
            pkg == AMAZON_PKG && cls == AMAZON_HOME -> age >= AMAZON_FALLBACK_MS
            else -> false
        }
        if (!move) return Action.NONE
        handledTs = ts
        return Action.MOVE_TO_FRONT
    }

    companion object {
        const val OWN_PKG = "app.firetvhome"
        const val AMAZON_PKG = "com.amazon.tv.launcher"
        const val AMAZON_HOME = "com.amazon.tv.launcher.ui.HomeActivity_vNext"
        const val DUMBTV_PKG = "tv.dumbtv.home"
        const val DUMBTV_SETTLE_MS = 300L
        const val AMAZON_FALLBACK_MS = 2000L
        const val COOLDOWN_MS = 1000L
    }
}
