package app.mg4dash.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import app.mg4dash.system.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Téléphone en projection (Android Auto / CarPlay), en lecture seule (#41).
 *
 * Ouvrir Android Auto ou CarPlay sans téléphone connecté laisse un écran noir. Comme les lanceurs
 * d'origine, on demande au service AllGo `com.allgo.rui/.RemoteUIService` l'appareil en session
 * (`IRemoteUIService.getActiveRemoteDevice`, transaction 15) : rien = aucune session, sinon son type
 * (1 CarPlay, 2 Android Auto). Voie documentée par MG4Control (SliDeeN, branche beta, 2026-10-23).
 */
object Projection {
    private const val TAG = "Projection"
    private const val DESCRIPTOR = "com.allgo.rui.IRemoteUIService"
    private const val TX_ACTIVE_DEVICE = 15
    private val SERVICE = ComponentName("com.allgo.rui", "com.allgo.rui.RemoteUIService")

    sealed class Session {
        /** AllGo a répondu : [target] = écran de la projection en cours, null = aucun téléphone. */
        data class Read(val target: ComponentName?) : Session()
        /** Service absent ou muet : on ne sait pas, on ouvre comme avant. */
        object Unknown : Session()
    }

    @Volatile private var binder: IBinder? = null
    @Volatile private var latch: CountDownLatch? = null
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) { binder = service; latch?.countDown() }
        override fun onServiceDisconnected(name: ComponentName?) { binder = null }
    }

    /** Hors du fil principal : la première liaison attend la réponse du service (2 s au plus). */
    fun session(context: Context): Session {
        val b = bind(context) ?: return Session.Unknown
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            b.transact(TX_ACTIVE_DEVICE, data, reply, 0)
            reply.readException()
            if (reply.readInt() == 0) return Session.Read(null)
            reply.readInt()          // statut de session
            reply.readString()       // nom du téléphone (non gardé)
            reply.readString()       // UUID
            reply.readString()       // adresse BT
            when (val type = reply.readInt()) {
                1 -> Session.Read(Apps.CARPLAY)
                2 -> Session.Read(Apps.ANDROID_AUTO)
                else -> { Log.i(TAG, "unknown projection type: $type"); Session.Unknown }
            }
        } catch (e: Exception) {
            Log.w(TAG, "active device unreadable: ${e.message}"); Session.Unknown
        } finally { reply.recycle(); data.recycle() }
    }

    /**
     * Touche média vers la projection, comme le launcher d'origine (CPAAModel.mediaControlCPAA) :
     * `IRemoteUIService.sendMediaPlayControlKey` (transaction 18). En passant sur la source
     * CarPlay / Android Auto, le launcher envoie 1 : AllGo rend alors l'audio principal au téléphone,
     * que la radio avait pris (#36). Bloquant : hors du fil principal.
     */
    fun mediaKey(context: Context, code: Int): Boolean {
        val b = bind(context) ?: return false
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(code)
            b.transact(TX_MEDIA_KEY, data, reply, 0)
            reply.readException()
            Log.i(TAG, "projection media key $code → ${reply.readInt()}")
            true
        } catch (e: Exception) { Log.w(TAG, "projection media key $code: ${e.message}"); false }
        finally { reply.recycle(); data.recycle() }
    }

    /**
     * Arrête la session en cours (`stopRemoteSession`, transaction 3), avec l'appareil renvoyé tel quel par
     * `getActiveRemoteDevice`. Couper seulement l'appli Android Auto laissait la session ouverte chez AllGo :
     * les réglages d'origine se croyaient toujours en Android Auto et recoupaient la musique Bluetooth.
     * Bloquant : hors du fil principal.
     */
    fun stopSession(context: Context): Boolean = onActiveDevice(context, "stopped", TX_STOP_SESSION)

    /** Appelle [tx] avec l'appareil renvoyé tel quel par `getActiveRemoteDevice`. */
    private fun onActiveDevice(context: Context, label: String, tx: Int): Boolean {
        val b = bind(context) ?: return false
        val q = Parcel.obtain(); val dev = Parcel.obtain(); val data = Parcel.obtain(); val reply = Parcel.obtain()
        return try {
            q.writeInterfaceToken(DESCRIPTOR)
            b.transact(TX_ACTIVE_DEVICE, q, dev, 0)
            dev.readException()
            if (dev.readInt() == 0) { Log.i(TAG, "projection session not $label: no device"); return false }
            val start = dev.dataPosition()
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(1)
            data.appendFrom(dev, start, dev.dataSize() - start)
            b.transact(tx, data, reply, 0)
            reply.readException()
            Log.i(TAG, "projection session $label: ${reply.readInt()}")
            true
        } catch (e: Exception) { Log.w(TAG, "projection session $label: ${e.message}"); false }
        finally { reply.recycle(); data.recycle(); dev.recycle(); q.recycle() }
    }
    private const val TX_STOP_SESSION = 3

    /** Code du launcher d'origine pour « passer sur la source projection ». */
    const val KEY_SELECT_SOURCE = 1
    private const val TX_MEDIA_KEY = 18

    @Synchronized
    private fun bind(context: Context): IBinder? {
        binder?.takeIf { it.isBinderAlive }?.let { return it }
        val l = CountDownLatch(1); latch = l
        val ok = try {
            context.applicationContext.bindService(Intent().setComponent(SERVICE), connection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) { Log.w(TAG, "AllGo binding refused: ${e.message}"); false }
        if (!ok) return null
        l.await(2000, TimeUnit.MILLISECONDS)
        return binder
    }
}
