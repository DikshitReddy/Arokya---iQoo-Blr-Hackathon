package com.arokya.app.sensor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.compose.runtime.mutableStateOf
import com.arokya.app.data.Store
import java.util.Calendar

/**
 * REAL step data from the phone's pedometer.
 *
 * Uses [Sensor.TYPE_STEP_COUNTER], which the hardware accumulates continuously
 * since boot — it keeps counting with the app closed and costs almost no
 * battery, unlike TYPE_STEP_DETECTOR which needs a live listener per step.
 *
 * The counter is cumulative-since-boot, not per-day, so "today" is
 * `current - baselineTakenAtStartOfToday`. That baseline is persisted in
 * SharedPreferences rather than the app DB on purpose: it's throwaway device
 * state that's invalidated by any reboot, not user data worth migrating.
 *
 * HONEST LIMITATION: if Arokya wasn't open at midnight there is no way to know
 * what the counter read then, so the day's baseline is taken at first launch
 * and steps before that are not counted. [countingSince] exposes that so the
 * UI can say so rather than quietly under-reporting. Health Connect is the
 * upgrade path for true historical daily totals — it needs the Health Connect
 * app installed, which is why it isn't the default here.
 */
object StepSensor : SensorEventListener {
    private const val TAG = "ArokyaSteps"
    private const val PREFS = "arokya_steps"
    private const val KEY_BASELINE = "baseline_counter"
    private const val KEY_DAY = "baseline_day"

    /** Null until the sensor delivers its first reading. */
    val countingSince = mutableStateOf<Long?>(null)

    /** False when this phone has no pedometer at all. */
    val available = mutableStateOf(true)

    /** True once a real reading has landed — the UI must not claim live data before this. */
    val hasReading = mutableStateOf(false)

    private var sensorManager: SensorManager? = null
    private var appContext: Context? = null
    private var registered = false

    fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < 29 || ActivityCompat.checkSelfPermission(
            context, Manifest.permission.ACTIVITY_RECOGNITION
        ) == PackageManager.PERMISSION_GRANTED

    /** Safe to call repeatedly — re-registering after a permission grant is the point. */
    fun start(context: Context) {
        val app = context.applicationContext
        appContext = app
        if (!hasPermission(app)) {
            Log.i(TAG, "ACTIVITY_RECOGNITION not granted — not registering")
            return
        }
        if (registered) return

        val manager = app.getSystemService(SensorManager::class.java)
        sensorManager = manager
        val stepCounter = manager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        if (stepCounter == null) {
            available.value = false
            Log.w(TAG, "No TYPE_STEP_COUNTER on this device")
            return
        }
        available.value = true
        // SENSOR_DELAY_NORMAL is plenty: the counter is cumulative, so a slow
        // sample rate loses nothing — it just updates the display less often.
        manager.registerListener(this, stepCounter, SensorManager.SENSOR_DELAY_NORMAL)
        registered = true
        Log.i(TAG, "Registered for step counter updates")
    }

    fun stop() {
        if (!registered) return
        sensorManager?.unregisterListener(this)
        registered = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        val total = event.values.firstOrNull()?.toLong() ?: return
        val context = appContext ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        val today = dayKey()
        var baseline = prefs.getLong(KEY_BASELINE, -1L)
        val storedDay = prefs.getInt(KEY_DAY, -1)

        // Re-baseline on a new day, on first ever run, or after a reboot (the
        // counter restarts at 0, so a baseline above the current total is stale).
        if (storedDay != today || baseline < 0 || baseline > total) {
            baseline = total
            prefs.edit()
                .putLong(KEY_BASELINE, baseline)
                .putInt(KEY_DAY, today)
                .putLong("baseline_at", System.currentTimeMillis())
                .apply()
            Log.i(TAG, "New day/reboot — baseline set to $total")
        }

        countingSince.value = prefs.getLong("baseline_at", System.currentTimeMillis())
        val todaySteps = (total - baseline).coerceAtLeast(0L).toInt()
        hasReading.value = true
        // The whole app — rule engine, Home stats, the plan lever — reads this.
        Store.stepsToday.intValue = todaySteps
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun dayKey(): Int {
        val c = Calendar.getInstance()
        return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
    }
}
