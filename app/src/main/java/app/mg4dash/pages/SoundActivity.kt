package app.mg4dash.pages

import app.mg4dash.system.Log
import android.view.Gravity
import app.mg4dash.BuildConfig
import app.mg4dash.R
import app.mg4dash.car.AudioService
import app.mg4dash.car.CarBridge
import app.mg4dash.car.VehicleState
import app.mg4dash.ui.EqualizerView
import app.mg4dash.ui.PageActivity
import app.mg4dash.ui.SoundFieldView
import java.util.concurrent.Executors

/**
 * Page Son : répartition avant / arrière / gauche / droite et égaliseur 5 bandes avec
 * préréglages et Loudness, envoyés au service son SAIC (mêmes réglages que l'appli Média).
 */
class SoundActivity : PageActivity() {
    override val pageTitle get() = getString(R.string.title_sound)
    override val page = app.mg4dash.ui.Dock.Page.VEHICLE

    private lateinit var field: SoundFieldView
    private lateinit var fieldValue: android.widget.TextView
    private lateinit var eq: EqualizerView
    private lateinit var pickPreset: (Int?) -> Unit
    private lateinit var loudness: android.widget.TextView

    /** Un seul fil pour les appels au service son : les réglages partent dans l'ordre. */
    private val io = Executors.newSingleThreadExecutor()
    private var audio: AudioService? = null
    /** Écart entre nos valeurs (−7…+7) et celles du service (0…14, 7 = centre / neutre). */
    private var fieldOffset = 7

    /** Pastilles → numéro de préréglage SAIC (2 Classique, 3 Pop, 4 Voix, 5 Jazz, 6 Rock, 7 Utilisateur). */
    private val presetIds = intArrayOf(USER, 2, 5, 3, 6, 4)
    /** Courbes affichées (−7…+7) ; remplacées par celles de la voiture quand elle répond. */
    private val curves = mutableMapOf(
        USER to intArrayOf(0, 0, 0, 0, 0),
        2 to intArrayOf(4, 2, 0, 2, 4), 5 to intArrayOf(3, 1, -1, 2, 3), 3 to intArrayOf(-1, 2, 4, 2, -1),
        6 to intArrayOf(5, 2, -1, 3, 5), 4 to intArrayOf(-2, 3, 5, 3, -1),
    )

    override fun buildBody() {
        // ── Répartition ──
        val fPanel = kit.panel(getString(R.string.snd_field))
        field = SoundFieldView(this).apply { onChange = { b, f -> showField(b, f); sendField(b, f) } }
        fPanel.addView(field, kit.lp(-1, 0, 1f, t = 4))
        val fRow = kit.row()
        fieldValue = kit.text("", 18f, R.color.text_sub)
        fRow.addView(fieldValue, kit.lp(0, -2, 1f))
        fRow.addView(kit.toggleTile(getString(R.string.snd_center)) { field.balance = 0; field.fader = 0; showField(0, 0); sendField(0, 0) }
            .apply { textSize = 16f }, kit.lp(kit.dp(130), kit.dp(48)))
        fPanel.addView(fRow, kit.lp(-1, -2, t = 8))
        body.addView(fPanel, kit.lp(0, -1, 34f))

        // ── Égaliseur ──
        val ePanel = kit.panel(getString(R.string.snd_eq))
        val names = listOf(R.string.snd_user, R.string.snd_classic, R.string.snd_jazz, R.string.snd_pop, R.string.snd_rock, R.string.snd_vocal).map { getString(it) }
        val (pRow, pick) = kit.segmented(names) { i -> choosePreset(presetIds[i]) }
        pickPreset = pick
        ePanel.addView(pRow, kit.lp(-1, -2, t = 10))
        eq = EqualizerView(this).apply {
            // Toucher un curseur : la courbe devient la courbe utilisateur
            onChange = { _, _ -> pickPreset(0); curves[USER] = values.copyOf(); sendUserEq(values.copyOf()) }
        }
        ePanel.addView(eq, kit.lp(-1, 0, 1f, t = 12))
        val bRow = kit.row().apply { gravity = Gravity.CENTER_VERTICAL }
        bRow.addView(kit.text(getString(R.string.snd_loudness_hint), 16f, R.color.text_sub), kit.lp(0, -2, 1f))
        loudness = kit.toggleTile(getString(R.string.snd_loudness)) {
            loudness.isSelected = !loudness.isSelected
            val on = loudness.isSelected
            io.execute { call("loudness") { it.setLoudness(on) } }
        }.apply { textSize = 16f }
        bRow.addView(loudness, kit.lp(kit.dp(160), kit.dp(48)))
        bRow.addView(kit.toggleTile(getString(R.string.snd_reset)) {
            val flat = IntArray(5); eq.set(flat); curves[USER] = flat; pickPreset(0); sendUserEq(flat)
        }.apply { textSize = 16f }, kit.lp(kit.dp(160), kit.dp(48), l = 10))
        ePanel.addView(bRow, kit.lp(-1, -2, t = 8))
        body.addView(ePanel, kit.lp(0, -1, 66f, l = 12))

        if (BuildConfig.MOCK) { field.balance = -2; field.fader = 3; showField(-2, 3); show(6, false); loudness.isSelected = true }
        else { showField(0, 0); show(USER, false); load() }
    }

    /** Lecture des réglages actuels de la voiture. */
    private fun load() = io.execute {
        val a = CarBridge.audio().also { audio = it }
        if (a == null) { Log.w(TAG, "sound service unavailable"); return@execute }
        try {
            val b = a.balance; val f = a.fader
            // Valeurs relevées : centre 7 (0…14) comme l'égaliseur, ou 0 (−7…+7) sur d'autres versions
            fieldOffset = if (b < 0 || f < 0 || (b == 0 && f == 0)) 0 else 7
            val preset = a.equalizerPreset
            val loud = a.loudness
            for (id in presetIds) (if (id == USER) a.userEq else a.eqBand(id))?.let { raw -> curves[id] = IntArray(5) { raw[it] - EQ_CENTER } }
            Log.i(TAG, "read: balance $b, fader $f (offset $fieldOffset), preset $preset, loudness $loud, curve ${curves[preset]?.joinToString()}")
            runOnUiThread {
                field.balance = b - fieldOffset; field.fader = fieldOffset - f   // fader du service : grand = arrière
                showField(field.balance, field.fader)
                show(if (preset in presetIds) preset else USER, false)
                loudness.isSelected = loud
            }
        } catch (e: Exception) { Log.w(TAG, "read: ${e.message}") }
    }

    private fun choosePreset(id: Int) {
        show(id, false)
        io.execute { call("préréglage $id") { it.setEqualizerPreset(id) } }
    }

    /** Affiche un préréglage (pastille + courbe). */
    private fun show(id: Int, send: Boolean) {
        pickPreset(presetIds.indexOf(id).coerceAtLeast(0))
        eq.set(curves[id] ?: IntArray(5))
        if (send) sendUserEq(eq.values.copyOf())
    }

    @Volatile private var pendingField: Pair<Int, Int>? = null
    private fun sendField(b: Int, f: Int) {
        val first = pendingField == null
        pendingField = b to f
        if (first) io.execute {
            val (pb, pf) = pendingField ?: return@execute
            pendingField = null
            call("balance $pb fader $pf") { it.setSoundField(pb + fieldOffset, fieldOffset - pf) }
        }
    }

    @Volatile private var pendingEq: IntArray? = null
    private fun sendUserEq(v: IntArray) {
        val first = pendingEq == null
        pendingEq = v
        if (first) io.execute {
            val e = pendingEq ?: return@execute
            pendingEq = null
            call("égaliseur ${e.joinToString()}") { a -> a.setUserEq(IntArray(5) { e[it] + EQ_CENTER }).also { if (a.equalizerPreset != USER) a.setEqualizerPreset(USER) } }
        }
    }

    /** Appel au service son, sur le fil `io` ; journalisé pour les retours de test. */
    private fun call(what: String, block: (AudioService) -> Any?) {
        val a = audio ?: CarBridge.audio().also { audio = it } ?: run { Log.w(TAG, "$what: sound service unavailable"); return }
        try { Log.i(TAG, "$what → ${block(a)}") } catch (e: Exception) { Log.w(TAG, "$what: ${e.message}") }
    }

    private fun showField(b: Int, f: Int) {
        fun side(v: Int, neg: Int, pos: Int) = if (v == 0) null else "${getString(if (v < 0) neg else pos)} ${kotlin.math.abs(v)}"
        val parts = listOfNotNull(side(b, R.string.snd_left, R.string.snd_right), side(f, R.string.snd_rear, R.string.snd_front))
        fieldValue.text = if (parts.isEmpty()) getString(R.string.snd_centered) else parts.joinToString(" · ")
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }

    // ── Spectre en direct (Visualizer sur la sortie audio générale, session 0) ──
    private var visualizer: android.media.audiofx.Visualizer? = null
    private val bars = FloatArray(BARS)

    override fun onResume() {
        super.onResume()
        if (BuildConfig.MOCK) { eq.spectrum = FloatArray(BARS) { k -> (0.75f - 0.5f * k / BARS + 0.2f * kotlin.math.sin(k * 0.9f)).coerceIn(0.05f, 1f) }; return }
        try {
            val v = android.media.audiofx.Visualizer(0)
            v.captureSize = android.media.audiofx.Visualizer.getCaptureSizeRange()[1]
            val rate = android.media.audiofx.Visualizer.getMaxCaptureRate()
            v.setDataCaptureListener(object : android.media.audiofx.Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(vis: android.media.audiofx.Visualizer?, w: ByteArray?, sr: Int) {}
                override fun onFftDataCapture(vis: android.media.audiofx.Visualizer?, fft: ByteArray?, samplingRate: Int) {
                    if (fft != null) onFft(fft, samplingRate / 1000f)
                }
            }, rate, false, true)
            v.enabled = true
            visualizer = v
            Log.i(TAG, "spectrum: Visualizer on (${v.captureSize} points, ${rate / 1000} Hz)")
        } catch (e: Throwable) { Log.w(TAG, "spectrum unavailable: ${e.message}") }
    }

    override fun onPause() {
        super.onPause()
        try { visualizer?.enabled = false; visualizer?.release() } catch (_: Throwable) {}
        visualizer = null
    }

    /** FFT → BARS barres réparties en échelle log de 60 Hz à 16 kHz, montée rapide et retombée douce. */
    private fun onFft(fft: ByteArray, sampleHz: Float) {
        val n = fft.size / 2; if (n < 2 || sampleHz <= 0f) return
        val binHz = sampleHz / 2f / n
        for (k in 0 until BARS) {
            val f0 = 60f * Math.pow(16000.0 / 60.0, k / BARS.toDouble()).toFloat()
            val f1 = 60f * Math.pow(16000.0 / 60.0, (k + 1) / BARS.toDouble()).toFloat()
            val i0 = (f0 / binHz).toInt().coerceIn(1, n - 1); val i1 = (f1 / binHz).toInt().coerceIn(i0, n - 1)
            var m = 0f
            for (i in i0..i1) { val re = fft[2 * i].toFloat(); val im = fft[2 * i + 1].toFloat(); m = maxOf(m, kotlin.math.hypot(re, im)) }
            // dB ramené à 0…1 (≈ 50 dB de dynamique)
            val db = if (m > 0f) 20f * kotlin.math.log10(m) else 0f
            val target = ((db - 5f) / 40f).coerceIn(0f, 1f)
            bars[k] = if (target > bars[k]) target else bars[k] * 0.85f + target * 0.15f
        }
        val copy = bars.copyOf()
        runOnUiThread { if (!isFinishing) eq.spectrum = copy }
    }

    override fun render(s: VehicleState) {}

    companion object {
        private const val TAG = "Sound"
        private const val USER = 7
        private const val EQ_CENTER = 7
        private const val BARS = 40
    }
}
