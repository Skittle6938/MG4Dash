package app.mg4dash.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import app.mg4dash.media.LyricsRepo
import app.mg4dash.media.MediaHub

/** MG4Control entry points for manually showing or hiding the lyrics pill. */
class LyricsOverlayReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val app = context.applicationContext
        Handler(Looper.getMainLooper()).post {
            LyricsRepo.start(app)
            MediaHub.start(app)
            when (action) {
                ACTION_TOGGLE -> if (LyricsOverlay.active) LyricsOverlay.stop() else LyricsOverlay.show(app)
                ACTION_SHOW -> LyricsOverlay.show(app)
                ACTION_HIDE -> LyricsOverlay.stop()
            }
        }
    }

    companion object {
        const val ACTION_TOGGLE = "app.mg4dash.action.TOGGLE_LYRICS_OVERLAY"
        const val ACTION_SHOW = "app.mg4dash.action.SHOW_LYRICS_OVERLAY"
        const val ACTION_HIDE = "app.mg4dash.action.HIDE_LYRICS_OVERLAY"
    }
}
