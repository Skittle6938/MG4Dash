package app.mg4dash.settings

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.util.TypedValue
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.mg4dash.BuildConfig
import app.mg4dash.R
import app.mg4dash.car.CarBridge
import app.mg4dash.car.CarProps
import app.mg4dash.system.Firmware
import app.mg4dash.system.HomeSwitcher
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.net.URLEncoder

/**
 * Compatibility screen: what this firmware exposes to MG4Dash, as a short report the
 * driver can send from their phone (QR code → prefilled GitHub issue).
 *
 * The report contains no personal data: versions, services that answer and
 * a few car measurements (battery, range, climate). No position, VIN or account.
 */
object Compat {
    private const val ISSUES = "https://github.com/Skittle6938/MG4Dash/issues/new"

    fun report(a: Activity): String {
        val s = CarBridge.state.value
        val dm = a.resources.displayMetrics
        fun pkg(p: String) = if (Firmware.installed(a, p)) "yes" else "no"
        fun i(id: Int) = CarProps.readIntArea(id, 0x1000000)?.toString() ?: "—"
        fun f(id: Int) = CarProps.readFloatArea(id, 0x1000000)?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: "—"
        // App size = screen minus the SAIC bar (1920 − 142 = 1778): also note the whole screen
        fun realSize(c: android.content.Context): String = try {
            val m = android.util.DisplayMetrics()
            (c.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.getRealMetrics(m)
            "${m.widthPixels}x${m.heightPixels}"
        } catch (_: Exception) { "?" }
        return buildString {
            appendLine("MG4Dash ${BuildConfig.VERSION_NAME}")
            appendLine("Firmware: ${Firmware.version} (${Firmware.gen}, ${Firmware.family})")
            appendLine("Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), ${Build.MODEL}, ${dm.widthPixels}x${dm.heightPixels} @${dm.densityDpi} (screen ${realSize(a)})")
            appendLine("Packages: caradapter=${pkg("com.saicmotor.caradapter")} launcher=${pkg("com.saicmotor.launcher")} hmi.launcher=${pkg(Firmware.LEGACY_LAUNCHER)} adapterservice=${pkg("com.saicmotor.adapterservice")} radio=${pkg("com.saicmotor.radio")} androidauto=${pkg("com.allgo.app.androidauto")}")
            appendLine("Stock home: ${HomeSwitcher.STOCK.flattenToShortString()}")
            // Apps whose floating window can bring the SAIC bars back over home (#108)
            appendLine("Over other apps: ${app.mg4dash.ui.Fullscreen.overlayApps(a)}")
            appendLine("Services: " + CarBridge.probe().joinToString(", ") { "${it.first}=${it.second}" })
            appendLine("CarPropertyManager: ${if (CarProps.ready) "ok" else "absent"}; SOC=${f(0x2160f404)} range=${i(0x2140f41c)} V=${f(0x2160f406)} A=${f(0x2160f407)} driveMode=${i(0x2140a17c)} regen=${i(0x2140a191)} charge=${i(0x2140f409)}")
            appendLine("12 V battery: ${app.mg4dash.car.CarProps.battery12V() ?: "—"} (raw global=${app.mg4dash.car.CarProps.readFloatArea(app.mg4dash.car.CarProps.BAT_VOL, 0x1000000) ?: "—"} area0=${app.mg4dash.car.CarProps.readFloatArea(app.mg4dash.car.CarProps.BAT_VOL, 0) ?: "—"})")
            appendLine("Battery capacity: car=${app.mg4dash.energy.BatteryCapacity.fromCar() ?: "—"} measured=${app.mg4dash.energy.BatteryCapacity.estimate(a)?.let { "${"%.1f".format(java.util.Locale.US, it.first)} (${it.second} samples)" } ?: "—"}")
            appendLine("Shown: battery=${s.batteryPercent ?: "—"} range=${s.rangeKm ?: "—"} chargeStatus=${s.chargeStatus ?: "—"} chargeControl=${CarBridge.chargeControl}")
            appendLine("Climate: on=${s.hvacOn ?: "—"} temp=${s.driverTemp ?: "—"} fan=${s.fanSpeed ?: "—"} ac=${s.acOn ?: "—"} auto=${s.autoOn ?: "—"} recirc=${s.recirc ?: "—"} outside=${s.outsideTemp ?: "—"}")
            // The car's own set point limits (#107, old SDK firmwares): LO / HI values and the fan's max
            if (app.mg4dash.car.LegacyCar.limitsText != "—") appendLine("Climate limits: ${app.mg4dash.car.LegacyCar.limitsText}")
            // Equipment and commands to adapt by firmware (raw car values)
            fun hv(id: Int) = CarProps.readIntArea(id, 0x75)?.toString() ?: "—"
            val (seatCfg, wheelCfg) = CarBridge.heatingConfig()
            appendLine("Heating: config seat=${seatCfg ?: "—"} wheel=${wheelCfg ?: "—"}; props seatL=${hv(0x15402513)} seatR=${hv(0x15402514)} wheel=${hv(0x1540253a)}")
            appendLine("Drive side: ${CarProps.readBytes(CarProps.CAL_DRIVE_HDL_TYPE)?.joinToString("") { String.format("%02x", it) } ?: "—"} (${when (CarProps.rightHandDrive()) { true -> "right-hand"; false -> "left-hand"; null -> "?" }})")
            // Wipers and rain sensor (read only): equipment and current state
            fun ints(vararg p: Pair<String, Int>) = p.joinToString(" · ") { (n, id) -> "$n ${CarProps.getInt(id) ?: "—"}" }
            fun hex(id: Int) = CarProps.readBytes(id)?.joinToString("") { String.format("%02x", it) } ?: "—"
            appendLine("Wipers config: front ${hex(561005115)} · assy ${hex(561005187)}")
            appendLine("Wipers: " + ints("rain" to 289421397, "sw" to 289421399, "swAv" to 289421400, "poten" to 289421401,
                "potenAv" to 289421402, "swSts" to 557897511, "mode" to 557897519, "sens" to 557897518, "flick" to 557897517, "icmMode" to 557847978))
            appendLine("Controls: esp=${i(0x11403805)} tcs=${i(0x1140381f)} chrgCtrl=${i(0x2140f412)} lockCtrl=${i(0x2140f413)} doorCtrl=${i(0x2140f455)} plugAC=${i(0x2140f43e)} plugDC=${i(0x2140f43f)} onePedal=${i(0x2140a193)} ldw=${i(0x2140a104)} fcw=${i(0x2140a108)} slif=${i(0x2140a16c)}")
            appendLine("Old SDK: " + sdkClasses(a))
            appendLine("Media: " + listOf("com.saicmotor.service.media", "com.saicmotor.service.radio", "com.saicmotor.media", "com.saicmotor.radio",
                "com.saicmotor.hmi.media", "com.saicmotor.hmi.radio").joinToString(" ") { "${it.removePrefix("com.saicmotor.")}=${pkg(it)}" })
            append("Apps: " + saicApps(a))
        }
    }

    /** Old SDK classes found, and in which package (used to drive charging, ESC, aids). */
    private fun sdkClasses(a: Activity): String {
        val classes = listOf("VehicleSettingManager", "VehicleControlManager", "VehicleChargingManager", "VehicleConditionManager",
            "VehiclePropertyManager", "AirConditionManager")
        // Candidate packages only (loading each app takes time)
        val pkgs = saicPackages(a).filter { p -> listOf("launcher", "vehicle", "setting", ".ev", "charg", "service", "adapter").any { p.contains(it) } }
        return classes.joinToString(" ") { c ->
            val where = pkgs.firstOrNull { p ->
                try {
                    a.createPackageContext(p, android.content.Context.CONTEXT_INCLUDE_CODE or android.content.Context.CONTEXT_IGNORE_SECURITY)
                        .classLoader.loadClass("com.saicmotor.sdk.vehiclesettings.manager.$c"); true
                } catch (_: Throwable) { false }
            }
            "$c=${where?.removePrefix("com.saicmotor.") ?: "—"}"
        }
    }

    private fun saicPackages(a: Activity): List<String> = try {
        a.packageManager.getInstalledPackages(0).map { it.packageName }.filter { it.startsWith("com.saicmotor.") }.sorted()
    } catch (_: Exception) { emptyList() }

    /** Launchable SAIC apps (package name only): to open the Energy app, Settings… */
    private fun saicApps(a: Activity): String = try {
        val pm = a.packageManager
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName + "/" + it.activityInfo.name.substringAfterLast('.') }
            .filter { it.startsWith("com.saicmotor.") }
            .map { it.removePrefix("com.saicmotor.") }
            .distinct().sorted().joinToString(" ")
    } catch (_: Exception) { "—" }

    fun issueUrl(report: String): String {
        val title = "Compatibility: ${Firmware.version.substringBefore('-').ifBlank { Firmware.gen.name }}"
        val body = "**What works / what doesn't:**\n\n\n**Report (from Settings › Compatibility):**\n```\n$report\n```"
        // Characters allowed as such in a query value (RFC 3986) left readable: a shorter URL, so a QR code that still
        // reads once the report grew (#108's apps over other apps). "&", ";", "#", "+" and "%" stay encoded
        fun enc(t: String) = URLEncoder.encode(t, "UTF-8").replace("+", "%20")
            .replace("%3A", ":").replace("%2F", "/").replace("%2C", ",").replace("%3D", "=").replace("%28", "(").replace("%29", ")")
        return "$ISSUES?labels=compatibility&title=${enc(title)}&body=${enc(body)}"
    }

    private fun qr(text: String, size: Int): Bitmap? = try {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size,
            mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L, EncodeHintType.MARGIN to 2))
        Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565).apply {
            for (x in 0 until size) for (y in 0 until size) setPixel(x, y, if (m[x, y]) Color.BLACK else Color.WHITE)
        }
    } catch (_: Exception) { null }

    /** Report + QR code; "Open" shows the GitHub page (MG4Dash Web on the car, otherwise the browser). */
    fun show(a: Activity) {
        val text = report(a)
        val url = issueUrl(text).let { u ->
            if (u.length <= 2800) u else issueUrl(text.lines().filterNot { it.startsWith("Apps:") }.joinToString("\n"))
        }
        val d = a.resources.displayMetrics.density
        val box = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setPadding((24 * d).toInt(), (8 * d).toInt(), (24 * d).toInt(), 0) }
        box.addView(ScrollView(a).apply {
            addView(TextView(a).apply {
                this.text = text; setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); setTextIsSelectable(true)
                typeface = android.graphics.Typeface.MONOSPACE
            })
        }, LinearLayout.LayoutParams(0, (420 * d).toInt(), 1f))
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding((20 * d).toInt(), 0, 0, 0) }
        qr(url, (380 * d).toInt())?.let { bmp -> col.addView(ImageView(a).apply { setImageBitmap(bmp) }, LinearLayout.LayoutParams((380 * d).toInt(), (380 * d).toInt())) }
        col.addView(TextView(a).apply {
            this.text = a.getString(R.string.st_compat_scan); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        }, LinearLayout.LayoutParams((380 * d).toInt(), -2))
        box.addView(col, LinearLayout.LayoutParams(-2, -2))
        AlertDialog.Builder(a)
            .setTitle(R.string.st_compat_report).setView(box)
            .setPositiveButton(R.string.st_close, null)
            // In MG4Dash Web on the car: its browser refuses pages on some head units (#95)
            .setNeutralButton(R.string.st_compat_open) { _, _ -> app.mg4dash.system.WebViewer.open(a, url) }
            .show().also { app.mg4dash.ui.Dialogs.fix(it) }
    }
}
