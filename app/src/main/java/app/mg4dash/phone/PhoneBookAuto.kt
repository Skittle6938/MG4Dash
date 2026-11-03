package app.mg4dash.phone

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile
import android.content.Context
import app.mg4dash.BuildConfig
import app.mg4dash.pages.PhoneActivity
import app.mg4dash.system.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Phone book fetched in the background: as soon as a phone is connected over Bluetooth
 * and the car's phone book is empty, the sync (PBAP) starts, without waiting for
 * the Phone page to open. New attempt every 5 min at most while it stays empty.
 */
object PhoneBookAuto {
    private const val CHECK_MS = 30_000L
    private const val RETRY_MS = 5 * 60_000L
    private const val PBAP_CLIENT = 17
    private const val HEADSET_CLIENT = 16
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start(context: Context) {
        if (BuildConfig.MOCK) return
        val app = context.applicationContext
        scope.launch {
            delay(20_000)   // let Bluetooth reconnect at startup
            var lastTry = 0L
            while (true) {
                try {
                    val a = BluetoothAdapter.getDefaultAdapter()
                    val phone = a != null && (a.getProfileConnectionState(PBAP_CLIENT) == BluetoothProfile.STATE_CONNECTED ||
                        a.getProfileConnectionState(HEADSET_CLIENT) == BluetoothProfile.STATE_CONNECTED)
                    val now = System.currentTimeMillis()
                    if (phone && now - lastTry > RETRY_MS && PhoneActivity.contacts(app).isEmpty()) {
                        lastTry = now
                        Log.i("Phone", "empty phone book, phone connected: background sync")
                        PhoneActivity.sync(app, manual = false)
                    }
                    if (!phone) lastTry = 0L   // next phone connected: try at once
                } catch (e: Exception) {
                    Log.w("Phone", "background phone book: ${e.message}")
                }
                delay(CHECK_MS)
            }
        }
    }
}
