package com.arokya.app.nudge

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.arokya.app.MainActivity
import com.arokya.app.R
import com.arokya.app.data.Store
import com.arokya.app.engine.RuleEngine

/**
 * THE HERO ELEMENT: Arokya speaks first.
 *
 * A recurring checkpoint posts a notification that opens straight into a
 * planned meal for the slot the user is heading into.
 *
 * ---------------------------------------------------------------------------
 * WHY ALARMMANAGER AND NOT WORKMANAGER
 * ---------------------------------------------------------------------------
 * WorkManager was the obvious choice and it was wrong twice over:
 *
 *  1. PeriodicWorkRequest clamps to a 15-minute floor, so short test cadences
 *     aren't expressible at all. Chaining one-shots instead means calling
 *     enqueueUniqueWork(REPLACE) from inside the running worker — which tells
 *     WorkManager to cancel the work that is currently running, i.e. itself.
 *     The chain dies after the first fire.
 *  2. Even a healthy chain is handed to JobScheduler, which batches. Sub-15
 *     minute spacing simply doesn't hold.
 *
 * AlarmManager gives real short-interval timing and survives the app being
 * closed. The model work still runs in a Worker ([NudgeCopyWorker]) because a
 * BroadcastReceiver only gets ~10 seconds and loading a multi-GB model takes
 * far longer than that.
 *
 * ---------------------------------------------------------------------------
 * WHY THE NOTIFICATION POSTS TWICE
 * ---------------------------------------------------------------------------
 * The alarm posts immediately using the ENGINE's own wording, then the worker
 * updates that same notification in place once the model has written better
 * copy. So the user always gets something instantly, and a slow or missing
 * model degrades the wording rather than producing silence — which is exactly
 * what "nothing happened" looked like before.
 */
object Nudge {
    private const val TAG = "ArokyaNudge"
    private const val CHANNEL_ID = "arokya_coach"
    const val NOTIFICATION_ID = 1001

    /**
     * Minutes between checkpoints.
     *
     * TESTING: 1 minute. Ship value is 480 (8 hours) — and at that point
     * consider anchoring to fixed 8am/2pm/8pm slots so a rolling interval
     * never lands at 3am.
     */
    const val INTERVAL_MINUTES = 1L

    /** Intent extras read by MainActivity. */
    const val EXTRA_OPEN_TAB = "arokya.open_tab"
    const val EXTRA_OPEN_PLAN = "arokya.open_plan"
    const val TAB_MEALS = "meals"

    // ---------------- permission ----------------

    /** Android 13+ silently drops every post without this. */
    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || ActivityCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

    // ---------------- channel ----------------

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(
                CHANNEL_ID, "Arokya Coach",
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "Proactive coaching nudges" }
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(ch)
        }
    }

    // ---------------- scheduling ----------------

    private fun alarmIntent(context: Context): PendingIntent {
        val intent = Intent(context, NudgeAlarmReceiver::class.java)
            .setAction(NudgeAlarmReceiver.ACTION_CHECKPOINT)
        return PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * Arms the next checkpoint. Called on app start and re-armed by the
     * receiver after every fire — AlarmManager is one-shot by design, so the
     * chain is explicit rather than implicit.
     */
    fun schedule(context: Context, delayMinutes: Long = INTERVAL_MINUTES) {
        val app = context.applicationContext
        val am = app.getSystemService(AlarmManager::class.java)
        val triggerAt = System.currentTimeMillis() + delayMinutes * 60_000L

        // setExactAndAllowWhileIdle needs SCHEDULE_EXACT_ALARM on Android 12+,
        // which the user can revoke. Inexact still fires, just batched — so
        // degrade rather than throw.
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        try {
            if (exact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, alarmIntent(app))
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, alarmIntent(app))
            }
            Log.i(TAG, "Next checkpoint armed in ${delayMinutes}min (exact=$exact)")
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, alarmIntent(app))
            Log.w(TAG, "Exact alarm denied — fell back to inexact", e)
        }
    }

    /** Demo button: run a checkpoint right now, without disturbing the schedule. */
    fun fireNow(context: Context) {
        val app = context.applicationContext
        app.sendBroadcast(
            Intent(app, NudgeAlarmReceiver::class.java)
                .setAction(NudgeAlarmReceiver.ACTION_CHECKPOINT)
                .putExtra(NudgeAlarmReceiver.EXTRA_MANUAL, true)
        )
    }

    /** "Not now" stops the chain until the app is opened again. */
    fun cancel(context: Context) {
        val app = context.applicationContext
        app.getSystemService(AlarmManager::class.java).cancel(alarmIntent(app))
        Log.i(TAG, "Checkpoint schedule cancelled")
    }

    // ---------------- posting ----------------

    /**
     * Posts (or updates, same id) the nudge.
     *
     * Returns false only when the notification could NOT be delivered, so
     * callers never tell the user to check a shade that got nothing.
     */
    fun post(context: Context, title: String, body: String): Boolean {
        ensureChannel(context)
        if (!canPost(context)) {
            Log.w(TAG, "POST_NOTIFICATIONS not granted — nothing delivered.")
            return false
        }

        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFF1E8A82.toInt()) // teal accent on the small icon + app name
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(openPlanIntent(context))
            .setAutoCancel(true)
            .addAction(0, "Yes, suggest one", openPlanIntent(context))
            .addAction(0, "Not now", dismissIntent(context))
            .build()

        return try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notif)
            Log.i(TAG, "Posted: \"$title\"")
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "notify() refused", e)
            false
        }
    }

    fun dismiss(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    /**
     * The full-colour Arokya mark as a bitmap for [NotificationCompat.Builder.setLargeIcon]
     * — shown on the right of the expanded notification. Decoded from the same
     * launcher icon so the notification carries the real brand mark, while the
     * small status-bar icon stays a white silhouette (Android's rule).
     */
    fun brandLargeIcon(context: Context): android.graphics.Bitmap? =
        androidx.core.content.ContextCompat
            .getDrawable(context, R.drawable.arokya_icon)
            ?.let { d ->
                val size = (48 * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
                val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bmp)
                d.setBounds(0, 0, size, size)
                d.draw(canvas)
                bmp
            }

    /**
     * Proactively asks the user what they ate for [slotLabel]. Tapping opens
     * Arokya listening, so they can just say it and it's logged (the log_meal
     * tool does the rest). This is the "ask at meal time, take the input,
     * store it" loop the user wanted.
     */
    fun postMealLogPrompt(context: Context, slotLabel: String): Boolean {
        ensureChannel(context)
        if (!canPost(context)) return false
        val body = "Tell me what you had and I'll log it for you."
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFF1E8A82.toInt())
            .setContentTitle("Did you have ${slotLabel.lowercase()}?")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(openVoiceIntent(context))
            .setAutoCancel(true)
            .addAction(0, "Tell Arokya", openVoiceIntent(context))
            .build()
        return try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notif)
            true
        } catch (e: SecurityException) { false }
    }

    /** Opens the app straight into voice listening (reuses the hands-free path). */
    private fun openVoiceIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_START_LISTENING, true)
        }
        return PendingIntent.getActivity(
            context, 2, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Tapping the nudge (or "Yes") lands the user on the planned meal. */
    private fun openPlanIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_PLAN, true)
        }
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun dismissIntent(context: Context): PendingIntent {
        val intent = Intent(context, NudgeActionReceiver::class.java)
            .setAction(NudgeActionReceiver.ACTION_NOT_NOW)
        return PendingIntent.getBroadcast(
            context, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    // ---------------- legacy synchronous entry point ----------------

    /** Posts the engine's own message immediately, no model involved. */
    fun checkAndNudge(context: Context): String? {
        val rec = RuleEngine.proactiveCheck(Store.buildContext()) ?: return null
        Store.lastRecommendation.value = rec
        return if (post(context, "Arokya · ${rec.title}", rec.message)) rec.message else null
    }
}
