package com.dtn.mesh.service

import android.annotation.SuppressLint
import android.content.Context
import android.os.PowerManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Short, timed partial wake locks around relay work that must not be frozen half-way when the
 * screen is off (an MQTT connect attempt, a publish waiting for its PUBACK). Samsung / OEM power
 * managers suspend the CPU aggressively; without this a reconnect could stall until the next
 * screen-on.
 *
 * Deliberately NOT a lock held for the whole relay session: that would keep the CPU awake 24/7.
 * Staying connected through Doze needs the battery-optimisation exemption (see
 * [BatteryOptimization]), which the app asks the user for.
 *
 * One non-reference-counted lock: every [hold] re-arms it for at least the given time, so
 * overlapping work extends it and it always releases itself on timeout.
 */
@Singleton
class WakeLocks @Inject constructor(@ApplicationContext context: Context) {
    private val lock: PowerManager.WakeLock? = runCatching {
        context.getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dtnmesh:relay")
            ?.apply { setReferenceCounted(false) }
    }.getOrNull()

    @Volatile private var heldUntilMs = 0L

    /** Keep the CPU awake for at least [ms] more (capped at [MAX_HOLD_MS]). */
    @SuppressLint("WakelockTimeout") // always acquired with a timeout
    fun hold(ms: Long) {
        val l = lock ?: return
        val timeout = ms.coerceIn(1L, MAX_HOLD_MS)
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (l.isHeld && heldUntilMs >= now + timeout) return
            runCatching { l.acquire(timeout) }.onFailure { Log.w(TAG, "wake lock acquire failed", it) }
            heldUntilMs = now + timeout
        }
    }

    private companion object {
        const val TAG = "WakeLocks"
        const val MAX_HOLD_MS = 60_000L
    }
}
