package com.dtn.mesh.service

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * The battery-optimisation exemption. Without it Doze (and Samsung's "sleeping apps") cut the
 * relay's network in the background, so the phone drops off the relay and stops carrying
 * messages until it is opened again.
 */
object BatteryOptimization {
    fun isExempt(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

    /**
     * Ask the system to exempt this app (one system dialog). Falls back to the system's list of
     * battery-optimised apps if the direct request isn't available on this device.
     */
    @SuppressLint("BatteryLife") // a store-and-forward relay must keep running in the background
    fun requestExemption(context: Context) {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(direct)
        } catch (_: ActivityNotFoundException) {
            runCatching { context.startActivity(list) }
        } catch (_: SecurityException) {
            runCatching { context.startActivity(list) }
        }
    }
}
