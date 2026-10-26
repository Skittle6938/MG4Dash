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
        // Démarrage de la voiture avant le déverrouillage (écran BootSplash) : le stockage des réglages
        // n'est pas encore lisible ; tout le reste démarre au déverrouillage, dans le même processus.
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
        Log.i("App", "start ${BuildConfig.VERSION_NAME} (${BuildConfig.FLAVOR})")
        // Première installation : style Complet, plein écran et barre verticale à gauche par défaut.
        // Les installations existantes gardent leurs réglages (le fichier de préférences n'est pas vide).
        if (!BuildConfig.MOCK) {
            val sp = app.mg4dash.system.Prefs(this).raw
            if (!sp.contains("defaults_v1")) {
                val fresh = sp.all.isEmpty()
                sp.edit().apply {
                    if (fresh) putInt("home_style", 0).putBoolean("fullscreen", true).putBoolean("vertical_dock", true)
                        .putBoolean("tutorial_pending", true)   // tuto des gestes au premier accueil
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
        // Capacité de la batterie : valeur de la voiture dès qu'elle répond (CarPropertyManager asynchrone)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ app.mg4dash.energy.BatteryCapacity.apply(this) }, 15_000)
        ConsumptionEstimator.start(this)
        app.mg4dash.energy.EnergyLogger.start(this)
        MediaHub.start(this)
        app.mg4dash.map.LocationRepo.start(this)
        app.mg4dash.map.NavRepo.start(this)
        if (!BuildConfig.MOCK) app.mg4dash.system.StarKey.start(this)
        WeatherRepo.start(this)
        app.mg4dash.ui.Themes.publish(this)
        startThemeWatch()
    }

    private fun startThemeWatch() {
        // Thème auto pour Radio Dash : l'accueil ne republie le thème que lorsqu'il est affiché ; quand
        // la radio est au premier plan, les feux qui s'allument n'étaient donc jamais transmis.
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        var lastSig = app.mg4dash.ui.Themes.signature(this)
        h.postDelayed(object : Runnable {
            override fun run() {
                val sig = app.mg4dash.ui.Themes.signature(this@LauncherApp)
                if (sig != lastSig) { lastSig = sig; app.mg4dash.ui.Themes.publish(this@LauncherApp) }
                h.postDelayed(this, 5_000)
            }
        }, 5_000)
    }

    /** Langue de la voiture changée : la météo (libellés, lieu, heures) est recalculée dans la nouvelle langue. */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        WeatherRepo.refreshNow(this)
    }

    /**
     * Garde-fou : 3 plantages en moins de 3 minutes → le launcher SAIC redevient l'accueil,
     * pour que l'écran de la voiture ne reste jamais inutilisable.
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
                // Ne jamais masquer le plantage d'origine.
            }
            previous?.uncaughtException(thread, error)
        }
    }

    companion object {
        private const val CRASH_LIMIT = 3
        private const val CRASH_WINDOW_MS = 3 * 60 * 1000L
    }
}

/** Accès aux textes traduits depuis du code sans Context (états véhicule, météo…). */
object Tr {
    lateinit var app: Application
    fun s(id: Int, vararg args: Any): String = app.getString(id, *args)
}
