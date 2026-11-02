package app.mg4dash.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Parcel

/**
 * Launcher d'origine du SWI173 (A9_Launcher_V1.0.1.71, #78) : son service reçoit les boutons du volant, mais
 * les abandonne tant qu'un écran d'accueil ne s'est pas enregistré auprès de lui (ModeManager.processKeyEvent :
 * « mCurrentController | mLauncherCallback == null »). Le launcher du SWI69 n'a pas cette condition.
 * Quand MG4Dash est l'accueil, il s'enregistre à sa place : le service pilote lui-même lecture, précédent et
 * suivant, et garde l'état de la caméra 360. Seule l'ouverture et la fermeture de la caméra, confiées à
 * l'accueil (LauncherPresenter.onLaunchComponent), restent à faire ici ; le reste de l'interface est ignoré.
 */
object LauncherLink {
    private const val TAG = "LauncherLink"
    private const val PKG = "com.saicmotor.launcher"
    private const val ACTION = "com.saicmotor.ILauncherService"
    private const val SERVICE_DESCRIPTOR = "com.saicmotor.launcher.ILauncherService"
    private const val CALLBACK_DESCRIPTOR = "com.saicmotor.launcher.ILauncherCallback"
    private const val TX_REGISTER_CALLBACK = IBinder.FIRST_CALL_TRANSACTION
    private const val TX_ON_LAUNCH_COMPONENT = IBinder.FIRST_CALL_TRANSACTION + 2
    /** L'accueil d'origine, ouvert un instant au démarrage, efface l'enregistrement en se refermant. */
    private const val REGISTER_EVERY_MS = 30_000L

    private lateinit var appContext: Context
    private lateinit var handler: Handler
    @Volatile private var service: IBinder? = null
    private var logged = false

    private val callback = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) { reply?.writeString(CALLBACK_DESCRIPTOR); return true }
            if (code !in IBinder.FIRST_CALL_TRANSACTION..IBinder.LAST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(CALLBACK_DESCRIPTOR)
            if (code == TX_ON_LAUNCH_COMPONENT) launchComponent(data.readString(), data.readInt())
            // Rien à afficher pour le launcher : toutes les réponses sont vides, ou « non » pour celles qui en attendent une
            reply?.writeNoException()
            reply?.writeInt(0)
            return true
        }
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, b: IBinder?) { service = b; handler.post { register() } }
        override fun onServiceDisconnected(name: ComponentName?) { service = null; logged = false }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (service == null) bind() else register()
            handler.postDelayed(this, REGISTER_EVERY_MS)
        }
    }

    fun start(context: Context) {
        if (Firmware.gen != Firmware.Gen.SWI173) return
        appContext = context.applicationContext
        handler = Handler(HandlerThread("launcher-link").apply { start() }.looper)
        handler.post(tick)
    }

    private fun bind() {
        if (!isHome()) return
        try {
            val ok = appContext.bindService(Intent(ACTION).setPackage(PKG), conn, Context.BIND_AUTO_CREATE)
            Log.i(TAG, "binding stock launcher service: $ok")
        } catch (e: Exception) { Log.w(TAG, "binding stock launcher service: ${e.message}") }
    }

    /** MG4Dash ne prend jamais la place de l'accueil d'origine quand celui-ci est l'accueil (mode Entretien). */
    private fun isHome() = HomeSwitcher.currentHome(appContext) == HomeSwitcher.ours(appContext)

    private fun register() {
        val b = service ?: return
        if (!isHome()) return
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(SERVICE_DESCRIPTOR)
            data.writeStrongBinder(callback)
            b.transact(TX_REGISTER_CALLBACK, data, reply, 0)
            reply.readException()
            if (!logged) Log.i(TAG, "registered with the stock launcher service: it now handles the steering wheel buttons")
            logged = true
        } catch (e: Exception) {
            Log.w(TAG, "registering with the stock launcher service: ${e.message}")
        } finally { data.recycle(); reply.recycle() }
    }

    private fun launchComponent(pkg: String?, type: Int) {
        val intent = cameraIntent(pkg, type)
        Log.i(TAG, "stock launcher asks to launch $pkg, type $type${if (intent == null) ": ignored" else ""}")
        if (intent == null) return
        try { appContext.startService(intent) } catch (e: Exception) { Log.w(TAG, "camera: ${e.message}") }
    }

    /** Mêmes intentions que LauncherPresenter.onLaunchComponent : 0 ferme la caméra, sinon l'ouvre. */
    fun cameraIntent(pkg: String?, type: Int): Intent? = when (pkg) {
        "com.saicmotor.evs" -> Intent().setComponent(ComponentName(pkg, "com.saicmotor.evs.service.EvsService"))
            .putExtra("state", if (type == 0) 0 else 1)
        "com.saicmotor.avm" -> Intent(avmAction(type)).setComponent(ComponentName(pkg, "com.saicmotor.avm.service.SaicMotorAvmService"))
        "com.saicmotor.hmi.aroundview" -> Intent(avmAction(type)).setComponent(ComponentName(pkg, "com.saicmotor.hmi.aroundview.AvmService"))
        else -> null
    }

    private fun avmAction(type: Int) = if (type == 0) "com.saicmotor.avm.STOP" else "com.saicmotor.avm.START_ENTER"
}
