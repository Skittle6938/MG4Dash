package app.mg4dash.ui

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import android.view.View

/**
 * Startup screen, instead of Android's "Phone is starting".
 *
 * As long as the session is not unlocked (file-based encryption), Android only shows
 * "directBootAware" home screens: this one (priority −1, above Android's waiting screen at
 * −1000 but below MG4Dash home at 0, which stays the normal home). It reads nothing from
 * encrypted storage: it animates, then opens MG4Dash home as soon as unlocked.
 */
class BootSplashActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var done = false

    private val unlocked = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) { openHome() }
    }

    /** Safety net: the state is also checked every second. */
    private val poll = object : Runnable {
        override fun run() { if (isUnlocked()) openHome() else handler.postDelayed(this, 1000) }
    }

    private fun isUnlocked() = try { (getSystemService(Context.USER_SERVICE) as UserManager).isUserUnlocked } catch (_: Throwable) { true }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Already unlocked (home requested later): directly MG4Dash home
        if (isUnlocked()) { openHome(); return }
        val v = SplashView(this).apply {
            version = try { packageManager.getPackageInfo(packageName, 0).versionName.substringBefore('-') } catch (_: Throwable) { "" }
        }
        setContentView(v)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        v.start()
        try { registerReceiver(unlocked, IntentFilter(Intent.ACTION_USER_UNLOCKED)) } catch (_: Throwable) {}
        handler.postDelayed(poll, 1000)
    }

    private fun openHome() {
        if (done) return
        done = true
        handler.removeCallbacks(poll)
        try {
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setComponent(ComponentName(this, "app.mg4dash.MainActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        } catch (_: Throwable) {}
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(poll)
        try { unregisterReceiver(unlocked) } catch (_: Throwable) {}
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {}
}
