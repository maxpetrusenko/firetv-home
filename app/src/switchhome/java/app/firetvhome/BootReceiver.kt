package app.firetvhome

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (HomeRole.isActive(c)) Keeper.start(c)
    }
}
