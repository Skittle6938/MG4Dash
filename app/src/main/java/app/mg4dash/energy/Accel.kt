package app.mg4dash.energy

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import app.mg4dash.system.Log
import kotlin.math.sqrt

/**
 * Accéléromètre de l'autoradio pour la carte de consommation (en test) : le long de la voiture, il mesure
 * l'accélération plus la pente (g·sin θ), c'est-à-dire exactement la force d'élan et de côte. Travail par
 * seconde = masse × force mesurée × vitesse, à retirer de l'énergie de roulage.
 *
 * Le sens de montage est inconnu : appris en roulant. 1) Gravité : moyenne des premières secondes de
 * roulage (accélérations et pentes s'y compensent). 2) Axe avant : régression de la variation de vitesse de
 * la voiture sur les deux composantes horizontales. Retenu quand l'ajustement est bon ; gardé ensuite.
 */
object Accel {
    private const val TAG = "Accel"
    private const val GRAVITY_S = 300        // secondes de roulage pour la gravité
    private const val FIT_S = 600            // secondes de roulage pour l'axe avant
    private const val PREFS = "accel_cal"

    // Moyenne des mesures de la seconde en cours
    private var sx = 0.0; private var sy = 0.0; private var sz = 0.0; private var sn = 0
    private val lock = Any()

    // Calibration
    private var g: DoubleArray? = null       // direction de la gravité (unitaire)
    private var gSum = DoubleArray(3); private var gN = 0
    private var e1 = DoubleArray(3); private var e2 = DoubleArray(3)   // base du plan horizontal
    private var s11 = 0.0; private var s12 = 0.0; private var s22 = 0.0; private var s1y = 0.0; private var s2y = 0.0
    private var syy = 0.0; private var sY = 0.0; private var fitN = 0
    @Volatile private var forward: DoubleArray? = null   // axe avant, en m/s² mesurés par m/s² réels
    private var lastV: Float? = null
    private var started = false
    private var loggedAt = 0L

    val ready: Boolean get() = forward != null
    /** Avancement de l'apprentissage, en secondes de roulage (gravité puis axe avant). */
    val learnedS: Int get() = gN + fitN
    const val LEARN_S = GRAVITY_S + FIT_S

    fun start(context: Context) {
        if (started) return
        started = true
        load(context)
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: run { Log.i(TAG, "no accelerometer"); return }
        val ok = sm.registerListener(object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                synchronized(lock) { sx += e.values[0]; sy += e.values[1]; sz += e.values[2]; sn++ }
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }, sensor, 50_000)   // 20 mesures par seconde
        Log.i(TAG, "accelerometer ${sensor.name}: listening $ok, axis ${if (ready) "known" else "to learn"}")
    }

    /**
     * Une seconde de roulage : apprend tant que l'axe n'est pas connu, et rend le travail d'élan et de
     * côte de cette seconde (J, positif en accélérant ou en montant), ou null si l'axe n'est pas encore appris.
     */
    fun tick(context: Context, speedKmh: Float?, dtMs: Long): Float? {
        val a = synchronized(lock) {
            if (sn == 0) null else doubleArrayOf(sx / sn, sy / sn, sz / sn).also { sx = 0.0; sy = 0.0; sz = 0.0; sn = 0 }
        }
        val v = speedKmh?.div(3.6f)
        val prev = lastV
        lastV = v
        if (a == null || v == null || dtMs !in 500..2000) return null
        val f = forward
        if (f == null) learn(context, a, v, prev, dtMs)
        val fw = forward ?: return null
        val force = a[0] * fw[0] + a[1] * fw[1] + a[2] * fw[2]   // m/s² le long de la voiture
        return (MASS_KG * force * v * dtMs / 1000.0).toFloat()
    }

    private const val MASS_KG = 1800.0

    private fun learn(context: Context, a: DoubleArray, v: Float, prev: Float?, dtMs: Long) {
        if (v < 2f) return   // en roulant seulement (les pentes à l'arrêt fausseraient la gravité)
        val gv = g
        if (gv == null) {
            for (i in 0..2) gSum[i] += a[i]
            if (++gN >= GRAVITY_S) {
                val n = norm(gSum); val u = DoubleArray(3) { gSum[it] / n }
                g = u
                // Base horizontale : un axe quelconque non parallèle, puis produits vectoriels
                val ref = if (kotlin.math.abs(u[0]) < 0.9) doubleArrayOf(1.0, 0.0, 0.0) else doubleArrayOf(0.0, 1.0, 0.0)
                e1 = unit(cross(u, ref)); e2 = cross(u, e1)
                Log.i(TAG, String.format(java.util.Locale.US, "gravity learned: %.3f %.3f %.3f (|g| %.2f)", u[0], u[1], u[2], n / gN))
            }
            return
        }
        if (prev == null) return
        val y = (v - prev) / (dtMs / 1000.0)   // accélération réelle, m/s²
        if (kotlin.math.abs(y) > 5) return
        val h1 = dot(a, e1); val h2 = dot(a, e2)
        s11 += h1 * h1; s12 += h1 * h2; s22 += h2 * h2; s1y += h1 * y; s2y += h2 * y; syy += y * y; sY += y
        fitN++
        val now = android.os.SystemClock.elapsedRealtime()
        if (fitN < FIT_S || fitN % 60 != 0) return
        // Moindres carrés y = w1·h1 + w2·h2 ; l'axe avant est (w1·e1 + w2·e2) / |w|²
        val det = s11 * s22 - s12 * s12
        if (det <= 1e-9) return
        val w1 = (s22 * s1y - s12 * s2y) / det
        val w2 = (s11 * s2y - s12 * s1y) / det
        val ssRes = syy - 2 * (w1 * s1y + w2 * s2y) + (w1 * w1 * s11 + 2 * w1 * w2 * s12 + w2 * w2 * s22)
        val ssTot = syy - sY * sY / fitN
        val r2 = if (ssTot > 0) 1 - ssRes / ssTot else 0.0
        val gain = sqrt(w1 * w1 + w2 * w2)   // ≈ 1 si le capteur est juste
        if (now - loggedAt > 60_000 && app.mg4dash.system.Prefs(context).energyLog) {
            loggedAt = now
            Log.i(TAG, String.format(java.util.Locale.US, "axis fit: %d s, R² %.2f, gain %.2f", fitN, r2, gain))
        }
        if (r2 >= 0.5 && gain in 0.6..1.6) {
            // Projection sur l'axe : a·f doit rendre y, donc f = w (dans la base e1, e2)
            forward = DoubleArray(3) { w1 * e1[it] + w2 * e2[it] }
            save(context)
            Log.i(TAG, String.format(java.util.Locale.US, "forward axis learned after %d s: R² %.2f, gain %.2f", fitN, r2, gain))
        }
    }

    private fun save(context: Context) {
        val f = forward ?: return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("forward", f.joinToString(",")).apply()
    }

    private fun load(context: Context) {
        forward = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("forward", null)
            ?.split(',')?.mapNotNull { it.toDoubleOrNull() }?.takeIf { it.size == 3 }?.toDoubleArray()
    }

    /** Oublie l'axe appris (remontage de l'autoradio, essai). */
    fun reset(context: Context) {
        forward = null; g = null; gSum = DoubleArray(3); gN = 0
        s11 = 0.0; s12 = 0.0; s22 = 0.0; s1y = 0.0; s2y = 0.0; syy = 0.0; sY = 0.0; fitN = 0
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    private fun norm(a: DoubleArray) = sqrt(dot(a, a))
    private fun unit(a: DoubleArray) = norm(a).let { n -> DoubleArray(3) { a[it] / n } }
    private fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
}
