package app.mg4dash

import android.app.Application
import app.mg4dash.car.CarBridge
import app.mg4dash.energy.ConsumptionEstimator
import app.mg4dash.media.MediaHub
import app.mg4dash.system.HomeSwitcher
import app.mg4dash.system.Log
import app.mg4dash.weather.WeatherRepo

class LauncherApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Tr.app = this
        // Car started before unlock (BootSplash screen): the settings storage
        // is not readable yet; everything else starts at unlock, in the same process.
        val um = getSystemService(USER_SERVICE) as android.os.UserManager
        if (!um.isUserUnlocked) {
            registerReceiver(object : android.content.BroadcastReceiver() {
                override fun onReceive(c: android.content.Context?, i: android.content.Intent?) {
                    try { unregisterReceiver(this) } catch (_: Throwable) {}
                    startAll()
                }
            }, android.content.IntentFilter(android.content.Intent.ACTION_USER_UNLOCKED))
            return
        }
        startAll()
    }

    @Volatile private var started = false

    private fun startAll() {
        if (started) return
        started = true
        installCrashGuard()
        // Shortcuts menu, and MG's fog light button hidden by the SystemUI overlay (installed and switched with the
        // setting): only once unlocked, as they read the settings (unreadable from the boot screen: MG4Dash crashed in
        // a loop at start and Android showed its launcher picker)
        app.mg4dash.system.FogOverlay.start(this)
        app.mg4dash.ui.ShortcutsPanel.init(this)
        app.mg4dash.ui.ShortcutsPanel.GlobalStrip.update(this)
        // ADB at start (setting, off by default)
        if (!BuildConfig.MOCK && app.mg4dash.system.Prefs(this).adbAtStart) app.mg4dash.system.AdbSwitch.enable(this, "setting at start")
        Log.i("App", "start ${BuildConfig.VERSION_NAME} (${BuildConfig.FLAVOR})")
        // Apps whose floating window can bring the SAIC bars back over home (#108)
        Thread { Log.i("Bars", "over other apps: ${app.mg4dash.ui.Fullscreen.overlayApps(this)}") }.start()
        // Separate safety net (MG4Dash Rescue): sign of life, and its installation if needed
        app.mg4dash.system.Rescue.start(this)
        // Consumption map removed (1/11): file and accelerometer settings deleted
        java.io.File(filesDir, "consumption_map.json").takeIf { it.exists() }?.let { it.delete(); Log.i("App", "old consumption map deleted") }
        java.io.File(applicationInfo.dataDir, "shared_prefs/accel_cal.xml").takeIf { it.exists() }?.delete()
        // First install: Full layout, full screen and vertical bar on the left by default.
        // Existing installs keep their settings (the preferences file is not empty).
        if (!BuildConfig.MOCK) {
            val sp = app.mg4dash.system.Prefs(this).raw
            if (!sp.contains("defaults_v1")) {
                val fresh = sp.all.isEmpty()
                sp.edit().apply {
                    if (fresh) putInt("home_style", 0).putBoolean("fullscreen", true).putBoolean("vertical_dock", true)
                        .putBoolean("tutorial_pending", true)   // gesture tutorial on the first home screen
                    putBoolean("defaults_v1", true)
                }.apply()
                if (fresh) Log.i("App", "first install: Full layout, full screen, vertical bar")
            }
        }
        app.mg4dash.car.VehicleState.tempOffset = app.mg4dash.system.Prefs(this).tempOffset
        app.mg4dash.system.HomeSwitcher.init(this)
        CarBridge.start(this)
        app.mg4dash.phone.PhoneBookAuto.start(this)
        app.mg4dash.car.CarProps.start(this)
        app.mg4dash.energy.EnergyMonitor.start(this)
        app.mg4dash.climate.AcCycle.resume(this)
        app.mg4dash.energy.ConsumptionCurve.start(this)
        // Battery capacity: the car's value as soon as it answers (asynchronous CarPropertyManager)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ app.mg4dash.energy.BatteryCapacity.apply(this) }, 15_000)
        ConsumptionEstimator.start(this)
        app.mg4dash.energy.EnergyLogger.start(this)
        MediaHub.start(this)
        app.mg4dash.map.LocationRepo.start(this)
        app.mg4dash.map.NavRepo.start(this)
        if (!BuildConfig.MOCK) {
            app.mg4dash.system.StarKey.start(this)
            app.mg4dash.system.LauncherLink.start(this)
            app.mg4dash.energy.V2lAuto.start(this)
        }
        // MG4Dash's own traffic on the Wi-Fi first, before the weather and the map ask for anything
        app.mg4dash.system.WifiFirst.start(this)
        WeatherRepo.start(this)
        app.mg4dash.system.DataUsage.start(this)
        app.mg4dash.ui.Themes.publish(this)
        app.mg4dash.system.DataUsage.publishWifiOnly(this)
        startThemeWatch()
    }

    private fun startThemeWatch() {
        // Auto theme for Radio Dash: the home screen only republishes the theme while displayed; when
        // the radio was in front, lights coming on were therefore never passed on.
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        // The bar's width and side too (Radio Dash #2): the side may only be known once the car answers
        var lastSig = app.mg4dash.ui.Themes.signature(this) to app.mg4dash.ui.Themes.published(this)
        h.postDelayed(object : Runnable {
            override fun run() {
                val sig = app.mg4dash.ui.Themes.signature(this@LauncherApp) to app.mg4dash.ui.Themes.published(this@LauncherApp)
                if (sig != lastSig) { lastSig = sig; app.mg4dash.ui.Themes.publish(this@LauncherApp) }
                h.postDelayed(this, 5_000)
            }
        }, 5_000)
    }

    /** Car language changed: the weather (labels, place, hours) is recomputed in the new language. */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        WeatherRepo.refreshNow(this)
    }

    /**
     * Safeguard: 3 crashes in under 3 minutes → the SAIC launcher becomes the home screen again,
     * so the car's screen never stays unusable.
     */
    private fun installCrashGuard() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val sp = getSharedPreferences("crash_guard", MODE_PRIVATE)
                val now = System.currentTimeMillis()
                val recent = (sp.getString("times", "") ?: "")
                    .split(',').mapNotNull { it.toLongOrNull() }
                    .filter { now - it < CRASH_WINDOW_MS } + now
                sp.edit().putString("times", recent.joinToString(",")).commit()
                Log.e("Crash", "crash #${recent.size} in the window", error)
                if (recent.size >= CRASH_LIMIT) {
                    sp.edit().putString("times", "").putBoolean("fell_back", true).commit()
                    HomeSwitcher.switchToStock(this)
                }
            } catch (_: Throwable) {
                // Never hide the original crash.
            }
            previous?.uncaughtException(thread, error)
        }
    }

    companion object {
        private const val CRASH_LIMIT = 3
        private const val CRASH_WINDOW_MS = 3 * 60 * 1000L
    }
}

/** Access to translated texts from code without a Context (vehicle states, weather…). */
object Tr {
    lateinit var app: Application
    fun s(id: Int, vararg args: Any): String = app.getString(id, *args)
}
