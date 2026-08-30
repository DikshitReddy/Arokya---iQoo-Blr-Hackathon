package com.arokya.app.nudge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.arokya.app.data.Store
import com.arokya.app.engine.RuleEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * One checkpoint firing.
 *
 * Deliberately does the LEAST possible work: a BroadcastReceiver gets roughly
 * ten seconds before Android kills it, which is nowhere near enough to load a
 * multi-gigabyte model. So this posts the engine's wording immediately and
 * hands the slow model call to [NudgeCopyWorker], which updates the same
 * notification in place when it's done.
 *
 * Re-arming happens FIRST, so a crash anywhere below still leaves the chain
 * alive for the next interval.
 */
class NudgeAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ArokyaNudge"
        const val ACTION_CHECKPOINT = "com.arokya.app.NUDGE_CHECKPOINT"
        const val EXTRA_MANUAL = "manual"

        /**
         * TESTING ONLY — set false before shipping.
         *
         * Normally the engine stays quiet unless a real rule fires, which is
         * what stops the nudge feeling like spam. But the rules are gated on
         * time of day (R1 evening, R2 early afternoon), so outside those
         * windows a checkpoint correctly produces nothing — indistinguishable
         * from a broken feature when you're testing at 2am.
         */
        const val ALWAYS_NUDGE = true
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CHECKPOINT) return
        val app = context.applicationContext
        val manual = intent.getBooleanExtra(EXTRA_MANUAL, false)

        // Re-arm before anything that can fail. A manual fire from the demo
        // button must not shift the real schedule.
        if (!manual) Nudge.schedule(app)

        if (!Nudge.canPost(app)) {
            Log.w(TAG, "Checkpoint fired but POST_NOTIFICATIONS isn't granted — open the app once.")
            return
        }

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            // Throwable, not Exception: an ExceptionInInitializerError or
            // NoClassDefFoundError from touching Compose state off the main
            // thread is an Error, and would otherwise vanish without a trace.
            try {
                Log.i(TAG, "Checkpoint work started (storeLoaded=${Store.loaded.value})")
                // A cold process has an empty Store, which would make every
                // number below wrong. This is a fast local SQLite read.
                if (!Store.loaded.value) Store.load(app)
                Log.i(TAG, "Store ready: ${Store.meals.size} meals, ${Store.pantry.size} pantry")

                // At a main meal the user hasn't logged yet, ask what they ate
                // rather than suggesting a plan — "did you have lunch? tell me".
                // Tapping opens Arokya listening, and log_meal stores it.
                val slot = com.arokya.app.engine.MealSlot.now()
                if (slot.isMainMeal && !slot.isLoggedIn(Store.meals.toList())) {
                    val posted = Nudge.postMealLogPrompt(app, slot.label)
                    Log.i(TAG, "Meal-log prompt for ${slot.label} posted=$posted")
                    return@launch
                }

                val healthContext = Store.buildContext()
                val recommendation =
                    if (ALWAYS_NUDGE) RuleEngine.recommend(healthContext)
                    else RuleEngine.proactiveCheck(healthContext)

                if (recommendation == null) {
                    Log.i(TAG, "Nothing worth saying right now — staying quiet.")
                    return@launch
                }
                Store.lastRecommendation.value = recommendation

                // Instant, guaranteed. The model only ever improves on this.
                val posted = Nudge.post(
                    app,
                    "Arokya · ${recommendation.title}",
                    recommendation.message,
                )
                Log.i(TAG, "Checkpoint posted=$posted (manual=$manual) — asking the model for better copy")

                WorkManager.getInstance(app).enqueue(
                    OneTimeWorkRequestBuilder<NudgeCopyWorker>().build()
                )
            } catch (e: Throwable) {
                Log.e(TAG, "Checkpoint failed", e)
            } finally {
                pending.finish()
            }
        }
    }
}
