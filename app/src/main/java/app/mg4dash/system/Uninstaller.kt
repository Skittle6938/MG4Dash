package app.mg4dash.system

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Uninstalling MG4Dash, MG4Radio or MG4Link from MG4Dash's settings (19/11, discussion #111). MG4Radio and MG4Link
 * are updates of the car's own radio and Android Auto: removing the update brings the car's own app back. MG4Dash
 * removes itself in an order that never leaves the car without a home: SAIC home back first (checked), then what it
 * added, then its rescue app (it would react to MG4Dash going), then itself; a step that fails stops it all.
 */
object Uninstaller {
    private const val TAG = "Uninstall"

    /** What the uninstall does on the car (replaced in the tests). */
    interface Ops {
        /** SAIC home enabled and preferred again; true when HOME resolves to it. */
        fun stockHomeBack(): Boolean
        fun installed(pkg: String): Boolean
        /** Removes [pkg] (an update: the car's own version comes back); true on success. */
        fun remove(pkg: String): Boolean
        /** MG4Dash's global settings removed. */
        fun clearSettings()
    }

    enum class Step { HOME, OVERLAY, WEB, SETTINGS, RESCUE, DASH }

    /**
     * What removing a package does: an update of a system app (MG4Radio on SWI69, MG4Link) is removed and the car's own
     * version comes back; a plain app (MG4Radio on SWI68 and the older firmwares, beside the car's own hmi.radio) is
     * uninstalled; a system app without an update has nothing to remove.
     */
    enum class Removal { REVERT_UPDATE, UNINSTALL, NOTHING }

    fun removalOf(flags: Int): Removal = when {
        flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0 -> Removal.REVERT_UPDATE
        flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0 -> Removal.NOTHING
        else -> Removal.UNINSTALL
    }

    /** How MG4Dash's own uninstall went: the steps done, and the one that failed (null: all done). */
    data class Result(val done: List<Step>, val failed: Step?)

    /** The global settings MG4Dash and its rescue app keep. */
    val SETTINGS_KEYS = listOf("mg4dash_alive", "mg4dash_home", "mg4dash_rescued", "mg4dash_rescue_ok", "mg4dash_rescue_busy",
        "mg4dash_rescue_disabled", "mg4dash_rescue_recompiled")

    /** MG4Dash's own uninstall, in its safe order. */
    fun uninstallDash(ops: Ops, self: String): Result {
        val done = ArrayList<Step>()
        fun step(s: Step, ok: Boolean): Boolean { if (ok) done += s else Log.w(TAG, "stopped at $s"); return ok }
        if (!step(Step.HOME, ops.stockHomeBack())) return Result(done, Step.HOME)
        if (ops.installed(FogOverlay.PKG) && !step(Step.OVERLAY, ops.remove(FogOverlay.PKG))) return Result(done, Step.OVERLAY)
        if (ops.installed(WebViewer.PKG) && !step(Step.WEB, ops.remove(WebViewer.PKG))) return Result(done, Step.WEB)
        ops.clearSettings(); done += Step.SETTINGS
        if (ops.installed(Rescue.PKG) && !step(Step.RESCUE, ops.remove(Rescue.PKG))) return Result(done, Step.RESCUE)
        if (!step(Step.DASH, ops.remove(self))) return Result(done, Step.DASH)
        return Result(done, null)
    }

    /** The car's own Ops: PackageInstaller (system uid), waited for. */
    class CarOps(private val context: Context) : Ops {
        override fun stockHomeBack(): Boolean = HomeSwitcher.stockHomeBack(context)

        override fun installed(pkg: String): Boolean = try { context.packageManager.getPackageInfo(pkg, 0); true } catch (_: Exception) { false }

        override fun remove(pkg: String): Boolean {
            val flags = try { context.packageManager.getApplicationInfo(pkg, 0).flags } catch (_: Exception) { return false }
            val how = removalOf(flags)
            Log.i(TAG, "$pkg: $how")
            if (how == Removal.NOTHING) return false
            val action = "app.mg4dash.UNINSTALLED.$pkg"
            val latch = CountDownLatch(1)
            var status = -999
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)
                    Log.i(TAG, "$pkg: $status ${i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
                    latch.countDown()
                }
            }
            context.registerReceiver(receiver, IntentFilter(action))
            return try {
                val pi = PendingIntent.getBroadcast(context, pkg.hashCode(), Intent(action).setPackage(context.packageName), PendingIntent.FLAG_UPDATE_CURRENT)
                context.packageManager.packageInstaller.uninstall(pkg, pi.intentSender)
                // Its own uninstall: the process goes before any answer
                latch.await(60, TimeUnit.SECONDS)
                status == PackageInstaller.STATUS_SUCCESS
            } catch (e: Exception) {
                Log.w(TAG, "$pkg: ${e.message}"); false
            } finally {
                try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
            }
        }

        override fun clearSettings() {
            for (k in SETTINGS_KEYS) try { android.provider.Settings.Global.putString(context.contentResolver, k, null) } catch (_: Exception) {}
        }
    }

    /** MG4Radio or MG4Link: its update removed, the car's own app back. Off the main thread. */
    fun removeApp(context: Context, pkg: String): Boolean = CarOps(context).remove(pkg)
}
