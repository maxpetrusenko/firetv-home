package app.firetvhome

import app.firetvhome.KeeperPolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class KeeperPolicyTest {
    private val now = 100_000L
    private val amazonCls = KeeperPolicy.AMAZON_HOME
    private fun decide(
        pkg: String?, cls: String? = null, age: Long = 0, lastMoveAt: Long = 0, role: Boolean = true,
        p: KeeperPolicy = KeeperPolicy(),
    ) = p.decide(pkg, cls, now - age, now, lastMoveAt, role)

    @Test fun roleInactive() = assertEquals(Action.NONE, decide("tv.dumbtv.home", age = 5000, role = false))
    @Test fun noEvent() = assertEquals(Action.NONE, decide(null))
    @Test fun dumbtvJustCameForward() = assertEquals(Action.NONE, decide("tv.dumbtv.home", age = 299))
    @Test fun dumbtvSettled() = assertEquals(Action.MOVE_TO_FRONT, decide("tv.dumbtv.home", age = 300))
    @Test fun amazonHomeFresh() = assertEquals(Action.NONE, decide(KeeperPolicy.AMAZON_PKG, amazonCls, age = 1999))
    @Test fun amazonHomeStale() = assertEquals(Action.MOVE_TO_FRONT, decide(KeeperPolicy.AMAZON_PKG, amazonCls, age = 2000))
    @Test fun amazonOtherActivityIgnored() = assertEquals(Action.NONE, decide(KeeperPolicy.AMAZON_PKG, "com.amazon.tv.launcher.ui.Other", age = 9000))
    @Test fun cooldownAfterOwnMove() = assertEquals(Action.NONE, decide("tv.dumbtv.home", age = 500, lastMoveAt = now - 999))
    @Test fun afterCooldown() = assertEquals(Action.MOVE_TO_FRONT, decide("tv.dumbtv.home", age = 1500, lastMoveAt = now - 1000))
    @Test fun ownPackage() = assertEquals(Action.NONE, decide("app.firetvhome", age = 9000))
    @Test fun otherApps() {
        assertEquals(Action.NONE, decide("org.smarttube.stable", age = 9000))
        assertEquals(Action.NONE, decide("com.android.settings", age = 9000))
    }

    @Test fun oneMovePerSettledState() {
        val p = KeeperPolicy()
        assertEquals(Action.MOVE_TO_FRONT, decide("tv.dumbtv.home", age = 400, p = p))
        assertEquals(Action.NONE, decide("tv.dumbtv.home", age = 400, p = p))
    }
}
