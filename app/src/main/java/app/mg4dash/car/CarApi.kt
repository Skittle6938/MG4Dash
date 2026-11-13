package app.mg4dash.car

import android.os.IBinder
import android.os.Parcel

/**
 * Binder calls to the "carapi" services of CarAdapterService (A9 / SWI69 family).
 *
 * Written for MG4Dash: only the interface (descriptor) and the transaction number of each
 * method used are reused, found on the car by analysing the stock launcher.
 * All calls are synchronous (two-way) and follow the standard AIDL format:
 * interface token, arguments, then any exception and the return value.
 */
open class RemoteService(private val binder: IBinder, private val descriptor: String) {

    val alive: Boolean get() = binder.isBinderAlive

    private inline fun <T> call(code: Int, write: (Parcel) -> Unit = {}, read: (Parcel) -> T): T {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(descriptor)
            write(data)
            binder.transact(code, data, reply, 0)
            reply.readException()
            return read(reply)
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    protected fun int(code: Int): Int = call(code) { it.readInt() }
    protected fun bool(code: Int): Boolean = call(code) { it.readInt() != 0 }
    protected fun float(code: Int): Float = call(code) { it.readFloat() }
    protected fun void(code: Int) = call(code) { }
    protected fun voidInt(code: Int, value: Int) = call(code, { it.writeInt(value) }) { }
    protected fun voidFloat(code: Int, value: Float) = call(code, { it.writeFloat(value) }) { }
    protected fun binderInt(code: Int, value: Int): IBinder? = call(code, { it.writeInt(value) }) { it.readStrongBinder() }
    protected fun <T> raw(code: Int, write: (Parcel) -> Unit, read: (Parcel) -> T): T = call(code, write, read)
}

/** CarAdapterService: hands out one binder per domain (queryClient). */
class AdapterService(b: IBinder) : RemoteService(b, "com.saicmotor.carapi.ICarAdapterService") {
    fun queryClient(code: Int): IBinder? = binderInt(1, code)
}

/** Climate (queryClient 7). The "switch" calls move one step, with no argument. */
class HvacService(b: IBinder) : RemoteService(b, "com.saicmotor.carapi.hvac.ICarHvacService") {
    val hvacPowerStatus get() = bool(6)
    val acStatus get() = bool(8)
    val autoStatus get() = bool(10)
    val ecoStatus get() = bool(14)
    val airCirculationStatus get() = int(16)
    val fanDirection get() = int(18)
    val fanSpeed get() = int(20)
    val driverTemperature get() = float(22)
    val outSideTemperature get() = float(25)
    val frontDefrostStatus get() = bool(28)
    val rearDefrostStatus get() = bool(30)

    fun switchHvacPowerStatus() = void(5)
    fun switchACStatus() = void(7)
    fun switchAutoStatus() = void(9)
    fun switchEcoStatus() = void(13)
    fun switchAirCirculationStatus() = void(15)
    fun switchFrontDefrostStatus() = void(27)
    fun switchRearDefrostStatus() = void(29)
    fun setFanDirection(v: Int) = voidInt(17, v)
    fun setFanSpeed(v: Int) = voidInt(19, v)
    fun setDriverTemperature(v: Float) = voidFloat(21, v)

    // Stock climate settings (#65), area 117 on the VHAL side
    /** Air flow in AUTO mode: 0 = high, 1 = medium, 2 = low. */
    val autoAirVolume get() = int(40)
    fun setAutoAirVolume(v: Int) = voidInt(39, v)
    /** Rear defrost on with the front: 0 = yes, 1 = no. */
    val defrostLinkage get() = int(38)
    fun setDefrostLinkage(v: Int) = voidInt(37, v)
    /** Climate following the ECO drive mode: 1 = yes, 2 = no. */
    val ecoLinkage get() = int(69)
    fun setEcoLinkage(v: Int) = voidInt(68, v)
}

/** Energy / charging (queryClient 4). */
class EvService(b: IBinder) : RemoteService(b, "com.saicmotor.carapi.ev.ICarEvService") {
    val chargePercentChange get() = int(3)
    val chargeStatus get() = int(4)
    val chargingRemainTime get() = int(6)
    val disChargingRemainTime get() = int(7)
    val currentRange get() = int(12)
    val chargeCtrlStatus get() = int(20)
    val disChargeCtrl get() = int(24)

    /** 1 = start, 2 = stop (like the stock launcher's button). */
    fun chargeCtrl(v: Int) = voidInt(5, v)
    fun disChargeCtrl(v: Int) = voidInt(8, v)
    /** Charge gun lock: 1 = lock, 2 = unlock (stock launcher's button). */
    fun elecClckCtrl(v: Int) = voidInt(16, v)
}

/** Vehicle settings (queryClient 8). Only regeneration and one-pedal driving are changed. */
class VehicleSettingService(b: IBinder) : RemoteService(b, "com.saicmotor.carapi.vs.ICarVehicleSettingService") {
    val fcwState get() = int(20)
    val fcwAutoBrakeMode get() = int(22)
    val lasMode get() = int(31)
    val drivingMode get() = int(63)
    val steeringMode get() = int(155)
    val brakePedalMode get() = int(157)
    val regenerativeLevel get() = int(160)
    val signalPedalState get() = int(163)
    val enduranceMode get() = int(166)
    val overSpeedSoundMode get() = int(180)
    val speedLimitSoundMode get() = int(182)

    /** 0 = low, 1 = medium, 2 = high, 3 = adaptive. */
    fun setRegenerativeLevel(v: Int) = voidInt(159, v)
    /** One-pedal driving: 1 = yes, 0 = no. */
    fun setSignalPedalState(v: Int) = voidInt(162, v)
}

/** Vehicle configuration (queryClient 2): fitted equipment (codes found in the SAIC climate app). */
class ConfigService(b: IBinder) : RemoteService(b, "com.saicmotor.carapi.config.ICarConfigService") {
    val seatHeatingConfig get() = int(23)
    val steeringWheelHeatConfig get() = int(52)
    /** Car model code (getCarModelConfigData): 69 MG4, 132 EU/UK MY24, 198 Israel MY24, 200 Turkey MY24… */
    val carModelConfig get() = int(8)
}

/**
 * Sound (queryClient 10), same calls as the sound settings of the stock Media app.
 * Equaliser: 5 bands (80 Hz, 500 Hz, 1 kHz, 5 kHz, 16 kHz) from 0 to 14 (7 = neutral);
 * presets 2 Classic, 3 Pop, 4 Vocal, 5 Jazz, 6 Rock, 7 User.
 */
open class AudioService(b: IBinder, descriptor: String = "com.saicmotor.carapi.audio.ICarAudioService") : RemoteService(b, descriptor) {
    /** Balance always centred on 7; otherwise the Sound page deduces the centre from the values read. */
    open val centredOnSeven get() = false
    open val loudness get() = bool(16)
    open val equalizerPreset get() = int(23)
    open val balance get() = int(31)
    open val fader get() = int(32)

    open fun setLoudness(on: Boolean) = raw(15, { it.writeInt(if (on) 1 else 0) }) { it.readInt() != 0 }
    open fun setEqualizerPreset(v: Int) = raw(22, { it.writeInt(v) }) { it.readInt() != 0 }
    open fun setSoundField(balance: Int, fader: Int) = raw(30, { it.writeInt(balance); it.writeInt(fader) }) { it.readInt() != 0 }
    /** Vehicle media volume on the native 0..getMediaVolumeMax() scale. */
    fun getMediaVolumeMax(): Int = raw(5, { it.writeInt(0) }) { it.readInt() }
    fun getMediaVolume(): Int = raw(6, { it.writeInt(0) }) { it.readInt() }
    fun setMediaVolume(level: Int): Boolean = raw(7, { it.writeInt(0); it.writeInt(level); it.writeInt(0) }) { true }
    /** Curve of a preset (7 = user), 5 values; null if the service does not answer. */
    open fun eqBand(preset: Int): IntArray? = raw(21, { it.writeInt(preset) }) { readEq(it) }
    open val userEq: IntArray? get() = raw(20, {}) { readEq(it) }
    open fun setUserEq(v: IntArray) = raw(19, { p -> p.writeInt(1); v.forEach { p.writeInt(it) } }) { it.readInt() != 0 }

    private fun readEq(p: Parcel): IntArray? = if (p.readInt() == 0) null else IntArray(5) { p.readInt() }
}

/** T-box, mobile network (queryClient 16). */
class TboxService(b: IBinder) : RemoteService(b, "com.saicmotor.carapi.tbox.ITboxService") {
    val connected get() = bool(4)
    /** Signal strength, 0 to 31. */
    val csq get() = int(8)
    /** 0 no network, 1 circuit switched, 2 to 4: 2G to 4G. */
    val wanStatus get() = int(9)
}

/** General information (queryClient 1). */
class GeneralService(b: IBinder) : RemoteService(b, "com.saicmotor.carapi.general.ICarGeneralService") {
    /** Central screen backlight: false = off (the stock launcher's "screen off" command). */
    fun setDisplayState(on: Boolean) = voidInt(4, if (on) 1 else 0)
    val latestSpeed get() = float(17)
    val totalMileage get() = int(23)
}
