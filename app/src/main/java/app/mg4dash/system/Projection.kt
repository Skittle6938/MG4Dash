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
 * Phone in projection (Android Auto / CarPlay), read only (#41).
 *
 * Opening Android Auto or CarPlay with no phone connected leaves a black screen. Like the stock
 * launchers, ask the AllGo service `com.allgo.rui/.RemoteUIService` for the device in session
 * (`IRemoteUIService.getActiveRemoteDevice`, transaction 15): nothing = no session, otherwise its type
 * (1 CarPlay, 2 Android Auto). Route documented by MG4Control (SliDeeN, beta branch, 2026-10-23).
 */
object Projection {
    private const val TAG = "Projection"
    private const val DESCRIPTOR = "com.allgo.rui.IRemoteUIService"
    private const val TX_ACTIVE_DEVICE = 15
    private val SERVICE = ComponentName("com.allgo.rui", "com.allgo.rui.RemoteUIService")

    sealed class Session {
        /** AllGo answered: [target] = screen of the current projection, null = no phone. */
        data class Read(val target: ComponentName?) : Session()
        /** Service missing or silent: unknown, open as before. */
        object Unknown : Session()
    }

    @Volatile private var binder: IBinder? = null
    @Volatile private var latch: CountDownLatch? = null
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) { binder = service; latch?.countDown() }
        override fun onServiceDisconnected(name: ComponentName?) { binder = null }
    }

    /** Off the main thread: the first binding waits for the service's answer (2 s at most). */
    fun session(context: Context): Session {
        val b = bind(context) ?: return Session.Unknown
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            b.transact(TX_ACTIVE_DEVICE, data, reply, 0)
            reply.readException()
            if (reply.readInt() == 0) return Session.Read(null)
            reply.readInt()          // session status
            reply.readString()       // phone name (not kept)
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
     * Media key to the projection, like the stock launcher (CPAAModel.mediaControlCPAA):
     * `IRemoteUIService.sendMediaPlayControlKey` (transaction 18). When moving to the CarPlay /
     * Android Auto source, the launcher sends 1: AllGo then gives main audio back to the phone,
     * which the radio had taken (#36). Blocking: off the main thread.
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
     * Stops the current session (`stopRemoteSession`, transaction 3), with the device returned as is by
     * `getActiveRemoteDevice`. Cutting only the Android Auto app left the session open at AllGo:
     * the stock settings still believed they were in Android Auto and cut Bluetooth music again.
     * Blocking: off the main thread.
     */
    fun stopSession(context: Context): Boolean = onActiveDevice(context, "stopped", TX_STOP_SESSION)

    /** Calls [tx] with the device returned as is by `getActiveRemoteDevice`. */
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

    /** Stock launcher's code for "move to the projection source". */
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
