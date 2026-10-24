package app.mg4dash.system

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.Toast

/** Raccourcis vers les applis SAIC (composants relevés sur la voiture le 2026-10-13). */
object Apps {
    private const val TAG = "Apps"

    val VEHICLE = ComponentName("com.saicmotor.vehiclesetting", "com.saicmotor.vehiclesetting.ui.VehicleSettingActivity")
    val HVAC = ComponentName("com.saicmotor.hvac", "com.saicmotor.hvac.ui.activity.MainActivity")
    val ENERGY = ComponentName("com.saicmotor.saicev", "com.saicmotor.saicev.activity.MainActivity")
    val ANDROID_AUTO = ComponentName("com.allgo.app.androidauto", "com.allgo.app.androidauto.ProjectionActivity")
    /** CarPlay de la pile allgo (relevé sur SWI68 par adammcdonagh ; repli sur le lanceur du paquet). */
    val CARPLAY = ComponentName("com.allgo.carplay.service", "com.allgo.carplay.service.CarPlayActivity")
    val SETTINGS = ComponentName("com.saicmotor.settings", "com.saicmotor.settings.ui.MainActivity")
    val RADIO = ComponentName("com.saicmotor.radio", "com.saicmotor.radio.view.RadioMainActivity")
    val MEDIA = ComponentName("com.saicmotor.media", "com.saicmotor.media.ui.activity.MainActivity")
    val MG4_CONTROL = ComponentName("com.mg4.control", "com.mg4.control.MainActivity")
    val BT_PHONE = ComponentName("com.saicmotor.btphone", "com.saicmotor.btphone.view.activity.BTPhoneMainActivity")

    fun open(context: Context, component: ComponentName, label: String): Boolean {
        val intent = Intent(Intent.ACTION_MAIN)
            .setComponent(component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return try {
            context.startActivity(intent)
            Log.i(TAG, "opening $label (${component.flattenToShortString()})")
            true
        } catch (e: ActivityNotFoundException) {
            fallback(context, component.packageName, label)
        } catch (e: SecurityException) {
            fallback(context, component.packageName, label)
        }
    }

    fun openPackage(context: Context, pkg: String, label: String = pkg): Boolean = fallback(context, pkg, label)

    /** Autres noms connus selon le firmware : mots-clés cherchés dans les paquets lanceurs (issue #13). */
    private val ALIASES = mapOf(
        "com.saicmotor.vehiclesetting" to listOf("vehiclesetting", "vehicle.setting", "carsetting", "vehicle"),
        "com.mg4.control" to listOf("mg4control", "mg4.control", "mg4"),
        "com.saicmotor.saicev" to listOf("saicev", "hmi.ev", "hmi.chargesettings", "chargesettings", "energy", "charging"),
        // Anciens firmwares (SWI68/165/133) : clim d'origine dans com.saicmotor.hmi.hvac (issue #30)
        "com.saicmotor.hvac" to listOf("hmi.hvac", "hvac", "climate", "aircondition"),
        "com.saicmotor.settings" to listOf("hmi.settings", "systemsetting", "settings"),
    )

    private fun searchLauncher(context: Context, pkg: String): Intent? {
        val keys = ALIASES[pkg] ?: return null
        val pm = context.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val hit = keys.firstNotNullOfOrNull { k ->
            apps.firstOrNull { ri ->
                val p = ri.activityInfo.packageName.lowercase()
                val l = ri.loadLabel(pm).toString().lowercase().replace(" ", "")
                p != context.packageName && (p.contains(k) || l.contains(k.replace(".", "")))
            }
        } ?: return null
        return Intent(Intent.ACTION_MAIN).setClassName(hit.activityInfo.packageName, hit.activityInfo.name)
    }

    private fun fallback(context: Context, pkg: String, label: String): Boolean {
        val launch = context.packageManager.getLaunchIntentForPackage(pkg) ?: searchLauncher(context, pkg)
        return if (launch != null) {
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Log.i(TAG, "opening $label via launch intent")
            true
        } else {
            Log.w(TAG, "$label not found ($pkg)")
            app.mg4dash.ui.Toasts.show(context, context.getString(app.mg4dash.R.string.app_unavailable, label))
            false
        }
    }
}
