package app.mg4dash.showroom

import app.mg4dash.R

internal enum class TyreStatus(val label: Int?) {
    NORMAL(null),
    WATCH(R.string.vh_tire_watch),
    LOW_PRESSURE(R.string.vh_tire_low),
    SENSOR_WARNING(R.string.vh_tire_check);

    companion object {
        private const val WATCH_KPA = 240
        private const val ALERT_KPA = 220

        /** The vehicle's state takes precedence until its warning codes are identified. */
        fun from(pressureKpa: Int?, sensorState: Int?): TyreStatus = when {
            sensorState != null && sensorState != 0 -> SENSOR_WARNING
            pressureKpa == null -> NORMAL
            pressureKpa < ALERT_KPA -> LOW_PRESSURE
            pressureKpa < WATCH_KPA -> WATCH
            else -> NORMAL
        }
    }
}
