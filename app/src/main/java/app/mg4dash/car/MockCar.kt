package app.mg4dash.car

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Données simulées pour l'émulateur : permet de valider l'UI sans la voiture. */
object MockCar {
    fun start(scope: CoroutineScope, state: MutableStateFlow<VehicleState>) {
        state.value = VehicleState(
            connected = true, hvacOn = true, acOn = true, autoOn = false, recirc = 1,
            fanSpeed = 4, fanDirection = 1, driverTemp = 21.5f, outsideTemp = 14.0f,
            defrostFront = false, defrostRear = false, batteryPercent = 78, socPrecise = 78.4f, seatHeatL = 2, seatHeatR = 0, wheelHeat = 0, rangeKm = 284,
            chargeStatus = 7, chargeCtrl = 2, dischargeCtrl = 2, driveMode = 3, regenLevel = 2, onePedal = 0,
            odometerKm = 18342, speedKmh = 0f, ecoOn = false, autoAirVolume = 1, defrostLinked = true, ecoLinked = false, steeringMode = 2, brakePedalMode = 0,
            enduranceMode = 0, lasMode = 3, fcwState = 2, fcwBrakeMode = 2, overSpeedSound = 1, speedLimitSound = 0,
        )
        scope.launch {
            var n = 0
            while (true) {
                delay(10_000)
                n++
                val s = state.value
                state.value = s.copy(
                    odometerKm = (s.odometerKm ?: 0) + 1,
                    batteryPercent = ((s.batteryPercent ?: 80) - if (n % 6 == 0) 1 else 0).coerceAtLeast(5),
                )
            }
        }
    }

    fun command(label: String, state: MutableStateFlow<VehicleState>) {
        val s = state.value
        state.value = when (label) {
            "power" -> s.copy(hvacOn = !(s.hvacOn ?: false))
            "ac" -> s.copy(acOn = !(s.acOn ?: false))
            "auto" -> s.copy(autoOn = !(s.autoOn ?: false))
            "recirc" -> s.copy(recirc = ((s.recirc ?: 0) + 1) % 3)
            "defrostFront" -> s.copy(defrostFront = !(s.defrostFront ?: false))
            "defrostRear" -> s.copy(defrostRear = !(s.defrostRear ?: false))
            "eco" -> s.copy(ecoOn = !(s.ecoOn ?: false))
            "startCharge" -> s.copy(chargeStatus = 1, chargeCtrl = 1, chargeRemainMin = 95)
            "stopCharge" -> s.copy(chargeStatus = 8, chargeCtrl = 2)
            "startV2L" -> s.copy(chargeStatus = 13, dischargeCtrl = 1)
            "stopV2L" -> s.copy(chargeStatus = 14, dischargeCtrl = 2)
            else -> s
        }
    }
}
