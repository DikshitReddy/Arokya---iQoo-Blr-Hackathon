package com.arokya.app.nudge

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.arokya.app.data.Store
import com.arokya.app.engine.DayBudget
import com.arokya.app.engine.MealSlot
import com.arokya.app.engine.NutritionTargets
import com.arokya.app.ml.Ml
import com.arokya.app.ml.generateNudgeCopy

/**
 * Upgrades an already-posted nudge to model-written copy.
 *
 * Runs as a Worker purely for the time budget: loading a .litertlm model can
 * take tens of seconds, far past the ~10s a BroadcastReceiver gets. It never
 * schedules anything, so there's no self-chaining and nothing to get wrong —
 * timing belongs entirely to AlarmManager (see [Nudge]).
 *
 * Failure here is genuinely fine: the user already has the engine's wording on
 * their lock screen. That fallback is the ENGINE speaking, not canned text
 * pretending to be a model reply, so the app's no-mock rule still holds.
 */
class NudgeCopyWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    companion object { private const val TAG = "ArokyaNudge" }

    override suspend fun doWork(): Result {
        return try {
            if (!Store.loaded.value) {
                Ml.useLiteRtLm(applicationContext)
                Store.load(applicationContext)
            }

            val context = Store.buildContext()
            val slot = MealSlot.now()
            val remaining = DayBudget.remaining(
                NutritionTargets.forProfile(Store.profile),
                Store.meals.toList(),
            )

            val copy = generateNudgeCopy(
                context = context,
                slot = slot,
                remaining = remaining,
                // Rotates tone across consecutive fires so repeated nudges
                // don't read identically.
                rotation = (System.currentTimeMillis() /
                        (Nudge.INTERVAL_MINUTES * 60_000L)).toInt(),
            )

            // Same notification id -> updates in place rather than stacking.
            Nudge.post(applicationContext, copy.title, copy.body)
            Log.i(TAG, "Model copy applied: \"${copy.title}\"")
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "Model couldn't write copy — engine wording stands.", e)
            Result.success()
        }
    }
}
