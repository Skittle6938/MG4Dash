package app.mg4dash.system

import android.content.Context

/**
 * Profiles, step 11: a phone paired with a profile switches to it at its first connection after a start. MG4Dash
 * listens to the phone's Bluetooth connection (calls, else music) and compares its address to the profiles' phones,
 * once per start; with two phones connected the one of the calls wins. A choice made by hand before (Settings, the
 * tile) is kept, and after that only a choice by hand changes the profile.
 */
object PhoneProfile {
    private const val TAG = "Profiles"
    @Volatile private var decided = false
    @Volatile private var started = false

    /** Whether to switch, and to which profile: null to stay. */
    internal fun choose(profiles: List<Profiles.Profile>, current: String, calls: String?, music: String?, manual: Boolean, decided: Boolean): String? {
        if (decided || manual) return null
        val phone = calls ?: music ?: return null
        val p = profiles.firstOrNull { it.phone.equals(phone, ignoreCase = true) } ?: return null
        return p.id.takeIf { it != current }
    }

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        val filter = android.content.IntentFilter().apply {
            addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction("android.bluetooth.headsetclient.profile.action.CONNECTION_STATE_CHANGED")
            addAction("android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED")
        }
        app.registerReceiver(object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context?, i: android.content.Intent?) {
                // The profiles settle a moment after the link: read them a little later
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ check(app, "Bluetooth") }, 3_000)
            }
        }, filter)
        check(app, "start")
    }

    /** A phone connected now: its profile, once per start. */
    fun check(context: Context, why: String) {
        if (decided) return
        val list = Profiles.list(context)
        if (list.none { it.phone != null }) return
        val calls = if (BtAudio.phoneConnected()) BtAudio.phoneAddress() else null
        val id = choose(list, Profiles.current(context).id, calls, null, Profiles.manualThisStart, decided)
        if (calls != null) decided = true
        if (id != null) {
            Log.i(TAG, "phone paired with a profile connected ($why)")
            Profiles.switchTo(context, id, "phone")
        }
    }
}
