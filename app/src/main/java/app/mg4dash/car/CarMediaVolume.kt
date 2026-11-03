package app.mg4dash.car

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.SystemClock
import app.mg4dash.system.Firmware
import app.mg4dash.system.Log
import java.lang.reflect.Modifier

/**
 * Absolute car media volume using the same routes as MG4Control.
 * Keep one [Route] for each read/write pair because the routes use different scales.
 */
object CarMediaVolume {
    const val UI_MAX = 32
    private const val TAG = "CarVolume"
    private const val MEDIA_TYPE = 0
    private const val A9_GET_MAX = 0x5
    private const val A9_GET = 0x6
    private const val A9_SET = 0x7
    private const val SMART_SOUND_CLASS = "com.saicmotor.sdk.systemsettings.SmartSoundManager"
    private val smartSoundClasses = listOf(
        SMART_SOUND_CLASS,
        "com.saicmotor.sdk.systemsettings.manager.SmartSoundManager",
        "com.saicmotor.sdk.vehiclesettings.manager.SmartSoundManager",
        "com.saicmotor.sdk.vehiclesettings.SmartSoundManager",
        "com.saicmotor.sdk.sound.SmartSoundManager",
        "com.saicmotor.sdk.sound.manager.SmartSoundManager",
    )

    internal enum class Channel { SAIC_LEGACY, SAIC_A9, CAR_AUDIO, ANDROID }
    internal data class Route(val channel: Channel, val max: Int, val manager: Any? = null, val group: Int = 0)

    @Volatile private var appContext: Context? = null
    @Volatile private var smartSound: Any? = null
    @Volatile private var smartSoundListener: Any? = null
    @Volatile private var smartSoundInitAt = 0L

    /** Select an available route and its native scale, preferring SAIC APIs. */
    internal fun route(context: Context, waitForSaicMs: Long = 0): Route? {
        appContext = context.applicationContext
        val deadline = SystemClock.elapsedRealtime() + waitForSaicMs.coerceAtLeast(0)
        do {
            if (Firmware.family == Firmware.Family.LEGACY || Firmware.family == Firmware.Family.UNKNOWN) {
                initializeSmartSound(context.applicationContext)
                smartSound?.let { manager ->
                    val max = smartGet(manager, "getMaxVolume")
                    if (max > 0) return Route(Channel.SAIC_LEGACY, max, manager)
                }
            }
            // LegacySound uses a different binder contract; do not send A9 volume transaction IDs to it.
            if (Firmware.family == Firmware.Family.A9 || Firmware.family == Firmware.Family.UNKNOWN) {
                runCatching { CarBridge.audio() }.getOrNull()?.let { service ->
                    val max = runCatching { service.getMediaVolumeMax() }.getOrDefault(-1)
                    if (max > 0) return Route(Channel.SAIC_A9, max)
                }
            }
            if (SystemClock.elapsedRealtime() < deadline) runCatching { Thread.sleep(200) }
        } while (SystemClock.elapsedRealtime() < deadline)
        carAudioRoute()?.let { return it }
        val am = audioManager() ?: return null
        val max = runCatching { am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(-1)
        return if (max > 0) Route(Channel.ANDROID, max) else null
    }

    internal fun read(route: Route): Int = when (route.channel) {
        Channel.SAIC_LEGACY -> route.manager?.let { smartGet(it, "getVolume") } ?: -1
        Channel.SAIC_A9 -> runCatching { CarBridge.audio()?.getMediaVolume() ?: -1 }.getOrDefault(-1)
        Channel.CAR_AUDIO -> carAudioRead(route.manager, "getGroupVolume", route.group)
        Channel.ANDROID -> runCatching { audioManager()?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: -1 }.getOrDefault(-1)
    }

    /** Set an absolute volume on the selected route; Android fallbacks verify the value by reading it back. */
    internal fun write(route: Route, level: Int): Boolean {
        val target = level.coerceIn(0, route.max)
        val ok = when (route.channel) {
            Channel.SAIC_LEGACY -> route.manager?.let { smartSetVolume(it, target) } ?: false
            Channel.SAIC_A9 -> runCatching { CarBridge.audio()?.setMediaVolume(target) == true }.getOrDefault(false)
            Channel.CAR_AUDIO -> carAudioWrite(route.manager, route.group, target)
            Channel.ANDROID -> androidAudioWrite(target, route.max)
        }
        Log.i(TAG, "Volume média $target/${route.max}, voie=${route.channel}, écrit=$ok")
        return ok
    }

    /** Map the fixed 0..32 slider to the selected route's native scale. */
    internal fun nativeLevel(uiLevel: Int, route: Route): Int =
        ((uiLevel.coerceIn(0, UI_MAX).toFloat() / UI_MAX) * route.max).toInt().coerceIn(0, route.max)

    private fun audioManager(): AudioManager? = appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private fun carAudioRoute(): Route? {
        val manager = CarProps.carAudioManager() ?: return null
        val group = runCatching {
            (manager.javaClass.getMethod("getVolumeGroupIdForUsage", Int::class.javaPrimitiveType)
                .invoke(manager, AudioAttributes.USAGE_MEDIA) as Number).toInt()
        }.getOrDefault(0)
        val max = carAudioRead(manager, "getGroupMaxVolume", group)
        return if (max > 0) Route(Channel.CAR_AUDIO, max, manager, group) else null
    }

    private fun carAudioRead(manager: Any?, method: String, group: Int): Int {
        if (manager == null) return -1
        val i = Int::class.javaPrimitiveType
        return runCatching {
            (manager.javaClass.getMethod(method, i).invoke(manager, group) as Number).toInt()
        }.recoverCatching {
            (manager.javaClass.getMethod(method, i, i).invoke(manager, 0, group) as Number).toInt()
        }.getOrDefault(-1)
    }

    private fun carAudioWrite(manager: Any?, group: Int, target: Int): Boolean {
        if (manager == null) return false
        val i = Int::class.javaPrimitiveType
        val sent = runCatching {
            manager.javaClass.getMethod("setGroupVolume", i, i, i).invoke(manager, group, target, 0)
        }.recoverCatching {
            manager.javaClass.getMethod("setGroupVolume", i, i, i, i).invoke(manager, 0, group, target, 0)
        }.isSuccess
        return sent && carAudioRead(manager, "getGroupVolume", group) == target
    }

    private fun androidAudioWrite(target: Int, max: Int): Boolean {
        val am = audioManager() ?: return false
        val level = target.coerceIn(0, max)
        runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0) }
        var current = runCatching { am.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(-1)
        if (current == level) return true
        if (current < 0) return false
        val direction = if (level > current) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        runCatching { repeat(kotlin.math.abs(level - current)) { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0) } }
        current = runCatching { am.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(-1)
        return current == level
    }

    private fun smartGet(manager: Any, method: String): Int = runCatching {
        (manager.javaClass.getMethod(method, Int::class.javaPrimitiveType).invoke(manager, MEDIA_TYPE) as Number).toInt()
    }.getOrDefault(-1)

    private fun smartSetVolume(manager: Any, level: Int): Boolean = runCatching {
        manager.javaClass.getMethod(
            "setVolume", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
        ).invoke(manager, MEDIA_TYPE, level, 0)
        true
    }.getOrDefault(false)

    /** Initialize the legacy SAIC SDK as MG4Control does; connection may complete in a callback. */
    private fun initializeSmartSound(context: Context) {
        if (smartSound != null) return
        val now = SystemClock.elapsedRealtime()
        if (now - smartSoundInitAt < 1_000L) return
        smartSoundInitAt = now
        val launcher = try {
            context.createPackageContext(
                Firmware.LEGACY_LAUNCHER,
                Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY
            )
        } catch (e: Exception) {
            Log.w(TAG, "SmartSound launcher context unavailable: ${e.message}")
            return
        }
        for (name in smartSoundClasses) {
            if (smartSound != null) return
            val cls = runCatching { launcher.classLoader.loadClass(name) }.getOrNull() ?: continue
            initializeSmartSoundClass(cls, context)
        }
    }

    private fun initializeSmartSoundClass(cls: Class<*>, context: Context) {
        try {
            val singleton = runCatching { cls.getDeclaredField("sInstance") }.getOrNull()
                ?: cls.declaredFields.firstOrNull { Modifier.isStatic(it.modifiers) && cls.isAssignableFrom(it.type) }
                ?: throw NoSuchFieldException("SmartSoundManager singleton")
            singleton.isAccessible = true
            singleton.get(null)?.let { smartSound = it; return }

            val init = cls.methods.firstOrNull { method ->
                method.name == "init" && method.parameterCount == 2 && Context::class.java.isAssignableFrom(method.parameterTypes[0])
            } ?: return
            val listenerType = init.parameterTypes[1]
            val listener = if (listenerType.isInterface) {
                java.lang.reflect.Proxy.newProxyInstance(listenerType.classLoader, arrayOf(listenerType)) { _, method, _ ->
                    if (method.name == "onServiceConnected") runCatching { smartSound = singleton.get(null) }
                    null
                }
            } else null
            smartSoundListener = listener
            init.invoke(null, context.applicationContext, listener)
            smartSound = singleton.get(null)
        } catch (e: Exception) {
            Log.w(TAG, "SmartSoundManager init failed: ${e.message}")
        }
    }
}
