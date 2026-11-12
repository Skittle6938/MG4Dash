package app.mg4dash.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import app.mg4dash.system.Log

/**
 * Bluetooth and USB of old SDK firmwares (SWI68…, #80): no com.saicmotor.media app, the sound is run by the
 * `com.saicmotor.service.media` service and shown by `com.saicmotor.hmi.music`. USB has no media browser,
 * only this service. Interfaces and transaction codes found in MediaService of the stock firmware (SWI68 R46).
 */
object SaicMedia {
    private const val TAG = "SaicMedia"
    private const val PKG = "com.saicmotor.service.media"
    private const val SERVICE = "$PKG.MediaService"
    /** Stock music app (screen opened by touching the card). */
    const val HMI_PKG = "com.saicmotor.hmi.music"
    private const val OTHER_FIRMWARES_PKG = "com.saicmotor.media"

    private const val BT_PAUSE = 1
    private const val BT_PLAY = 2
    private const val USB_PAUSE = 13
    private const val USB_PLAY = 14
    private const val USB_PREVIOUS = 25
    private const val USB_NEXT = 26
    private const val USB_IS_PLAYING = 30
    private const val USB_CURRENT_MUSIC = 33
    /** IPlayStatusBinderInterface.getCurrentMediaSource (transaction found in the SWI165 SystemUI's SAIC SDK). */
    private const val STATUS_CURRENT_SOURCE = 9

    data class Track(val title: String, val artist: String, val album: String)
    data class UsbState(val track: Track?, val playing: Boolean)

    /** Only where the media app of the other firmwares is missing and this service exists. */
    fun applies(context: Context): Boolean {
        fun installed(pkg: String) = try { context.packageManager.getPackageInfo(pkg, 0); true } catch (_: Exception) { false }
        return !installed(OTHER_FIRMWARES_PKG) && installed(PKG)
    }

    /** One binder per action: the service returns the interface asked for by the bind intent. */
    private class Link(val action: String, val descriptor: String) {
        @Volatile var binder: IBinder? = null
        private var bound = false
        private val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) { binder = service; Log.i(TAG, "$action connected") }
            override fun onServiceDisconnected(name: ComponentName?) { binder = null; Log.w(TAG, "$action disconnected") }
        }

        fun connect(context: Context) {
            if (bound && binder != null) return
            bound = try {
                context.applicationContext.bindService(Intent(action).setClassName(PKG, SERVICE), conn, Context.BIND_AUTO_CREATE)
            } catch (e: Exception) { Log.w(TAG, "binding $action: ${e.message}"); false }
        }

        fun <T> call(code: Int, read: (Parcel) -> T): T? {
            val b = binder ?: return null
            val data = Parcel.obtain(); val reply = Parcel.obtain()
            return try {
                data.writeInterfaceToken(descriptor)
                b.transact(code, data, reply, 0)
                reply.readException()
                read(reply)
            } catch (e: Exception) {
                Log.w(TAG, "transaction $code: ${e.message}"); null
            } finally { data.recycle(); reply.recycle() }
        }
    }

    private val bt = Link("$PKG.BT_MUSIC_ACTION", "com.saicmotor.sdk.media.IBtMusicBinderInterface")
    private val usb = Link("$PKG.MUSIC_PLAYER_ACTION", "com.saicmotor.sdk.media.IMusicPlayerBinderInterface")
    /** Which source the media service has (IPlayStatusBinderInterface): what the SAIC top bar shows (#106). */
    private val status = Link("$PKG.PLAY_STATUS_ACTION", "com.saicmotor.sdk.media.IPlayStatusBinderInterface")

    @Volatile var last: UsbState? = null
        private set

    val ready: Boolean get() = usb.binder?.isBinderAlive == true

    fun connect(context: Context) { bt.connect(context); usb.connect(context); status.connect(context) }

    /** The media service's current source type (6 USB, 50 CarPlay…), as its top bar has it; null if it does not answer. */
    fun currentSource(): Int? = status.call(STATUS_CURRENT_SOURCE) { it.readInt() }

    /** Current USB track and state; null if the service does not answer. */
    fun refresh(): UsbState? {
        val playing = usb.call(USB_IS_PLAYING) { it.readInt() != 0 } ?: return null
        val track = usb.call(USB_CURRENT_MUSIC) { readTrack(it) }
        return UsbState(track, playing).also { last = it }
    }

    /** AudioInfoBean, after its presence flag: id, duration, name, cover, path, artist, user, avatar, album… */
    internal fun readTrack(r: Parcel): Track? {
        if (r.readInt() == 0) return null
        r.readLong(); r.readLong()
        val name = r.readString().orEmpty().trim()
        r.readString(); r.readString()
        val artist = r.readString().orEmpty().trim()
        r.readString(); r.readString()
        return Track(name, artist, r.readString().orEmpty().trim())
    }

    /** Stock music app on the right tab, with the stock launcher's intent: its "module" extra is "bt" or "usb". */
    fun openMusicApp(context: Context, usb: Boolean): Boolean = try {
        context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .setClassName(HMI_PKG, "$HMI_PKG.ui.activity.MusicActivity")
            .putExtra("module", if (usb) "usb" else "bt"))
        true
    } catch (e: Exception) { Log.w(TAG, "opening the music app: ${e.message}"); false }

    fun btPlay(): Boolean = bt.call(BT_PLAY) { it.readInt() != 0 } ?: false
    fun btPause(): Boolean = bt.call(BT_PAUSE) { it.readInt() != 0 } ?: false
    /** False if the service has nothing to play yet (list not loaded by the stock music app). */
    fun usbPlay(): Boolean = usb.call(USB_PLAY) { it.readInt() != 0 } ?: false
    fun usbPause(): Boolean = usb.call(USB_PAUSE) { true } ?: false
    fun usbNext(): Boolean = usb.call(USB_NEXT) { true } ?: false
    fun usbPrevious(): Boolean = usb.call(USB_PREVIOUS) { true } ?: false
}
