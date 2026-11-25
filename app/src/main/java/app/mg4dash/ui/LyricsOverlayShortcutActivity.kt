package app.mg4dash.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import app.mg4dash.R
import app.mg4dash.system.Maintenance

/** Returns a launcher shortcut that sends the overlay toggle broadcast without opening the home. */
class LyricsOverlayShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Maintenance.on(this)) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        if (intent?.action == Intent.ACTION_CREATE_SHORTCUT) {
            val shortcut = Intent(this, LyricsOverlayReceiver::class.java).setAction(LyricsOverlayReceiver.ACTION_TOGGLE)
            val result = Intent().putExtra(Intent.EXTRA_SHORTCUT_NAME, getString(R.string.lyrics_overlay_shortcut_name))
                .putExtra(Intent.EXTRA_SHORTCUT_INTENT, shortcut)
            setResult(RESULT_OK, result)
        } else if (intent?.action == "com.mg4.control.action.SHORTCUT") {
            val action = intent.getStringExtra("action") ?: intent.getStringExtra("shortcut_action")
            if (action == LyricsOverlayReceiver.ACTION_SHOW) sendBroadcast(Intent(this, LyricsOverlayReceiver::class.java).setAction(LyricsOverlayReceiver.ACTION_SHOW))
            else if (action == LyricsOverlayReceiver.ACTION_HIDE) sendBroadcast(Intent(this, LyricsOverlayReceiver::class.java).setAction(LyricsOverlayReceiver.ACTION_HIDE))
            else sendBroadcast(Intent(this, LyricsOverlayReceiver::class.java).setAction(LyricsOverlayReceiver.ACTION_TOGGLE))
            setResult(RESULT_OK)
        } else if (intent?.action == LyricsOverlayReceiver.ACTION_TOGGLE ||
            intent?.action == LyricsOverlayReceiver.ACTION_SHOW ||
            intent?.action == LyricsOverlayReceiver.ACTION_HIDE) {
            sendBroadcast(Intent(this, LyricsOverlayReceiver::class.java).setAction(intent.action))
            setResult(RESULT_OK)
        }
        finish()
    }
}
