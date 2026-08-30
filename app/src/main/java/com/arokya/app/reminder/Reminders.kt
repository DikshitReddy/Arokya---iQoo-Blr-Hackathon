package com.arokya.app.reminder

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
import com.arokya.app.data.db.ArokyaDbHelper
import com.arokya.app.data.db.ReminderDao
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * User-set reminders — "remind me for gym tomorrow morning".
 *
 * The AI's set_reminder tool ends up here. Reminders are PERSISTED
 * ([ReminderDao]) and fired by [AlarmManager], because:
 *   - a notification is the whole point, so it must fire with the app closed;
 *   - AlarmManager alarms are lost on reboot, so the row in the DB is the
 *     source of truth and [rescheduleAll] re-arms them after a restart (see
 *     [BootReceiver]).
 *
 * Separate channel from the coaching nudge so a user can silence one without
 * the other.
 */
object Reminders {
    private const val TAG = "ArokyaReminder"
    private const val CHANNEL_ID = "arokya_reminders"

    const val EXTRA_REMINDER_ID = "reminder_id"
    const val EXTRA_REMINDER_TITLE = "reminder_title"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(
                CHANNEL_ID, "Reminders",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "Reminders you asked Arokya to set" }
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(ch)
        }
    }

    /**
     * Persists and schedules a reminder. Returns the row id.
     *
     * Rejects times in the past — the model occasionally computes "tomorrow"
     * wrong, and a reminder that fires instantly is worse than an error.
     */
    fun schedule(context: Context, title: String, triggerAtMillis: Long, nowMillis: Long): Long {
        require(triggerAtMillis > nowMillis) { "Reminder time is in the past." }
        val app = context.applicationContext
        val db = ArokyaDbHelper.getInstance(app).writableDatabase
        val id = ReminderDao.insert(db, title, triggerAtMillis, nowMillis)
        arm(app, id, triggerAtMillis)
        Log.i(TAG, "Scheduled #$id \"$title\" for ${format(triggerAtMillis)}")
        return id
    }

    /** Re-arms every future reminder — called on boot and app start. */
    fun rescheduleAll(context: Context) {
        val app = context.applicationContext
        val db = ArokyaDbHelper.getInstance(app).writableDatabase
        val now = System.currentTimeMillis()
        ReminderDao.upcoming(db, now).forEach { arm(app, it.id, it.triggerAtMillis) }
    }

    private fun arm(context: Context, id: Long, triggerAtMillis: Long) {
        val am = context.getSystemService(AlarmManager::class.java)
        val pending = firePendingIntent(context, id)
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        try {
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
        }
    }

    private fun firePendingIntent(context: Context, id: Long): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java)
            .setAction(ReminderReceiver.ACTION_FIRE)
            .putExtra(EXTRA_REMINDER_ID, id)
        return PendingIntent.getBroadcast(
            context, id.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Posts the reminder notification and marks it fired. */
    fun fire(context: Context, id: Long) {
        val app = context.applicationContext
        val db = ArokyaDbHelper.getInstance(app).writableDatabase
        val row = ReminderDao.all(db).firstOrNull { it.id == id } ?: return
        ensureChannel(app)

        val canPost = Build.VERSION.SDK_INT < 33 || ActivityCompat.checkSelfPermission(
            app, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED

        if (canPost) {
            val open = PendingIntent.getActivity(
                app, id.toInt(),
                Intent(app, MainActivity::class.java)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notif = NotificationCompat.Builder(app, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("⏰ ${row.title}")
                .setContentText("Reminder from Arokya")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(app).notify(20_000 + id.toInt(), notif)
        }
        ReminderDao.markFired(db, id)
        Log.i(TAG, "Fired #$id \"${row.title}\" (posted=$canPost)")
    }

    /** Cancels a reminder's alarm and removes it from the DB. */
    fun cancel(context: Context, id: Long) {
        val app = context.applicationContext
        app.getSystemService(AlarmManager::class.java).cancel(firePendingIntent(app, id))
        val db = ArokyaDbHelper.getInstance(app).writableDatabase
        ReminderDao.delete(db, id)
        Log.i(TAG, "Cancelled #$id")
    }

    fun format(millis: Long): String =
        SimpleDateFormat("EEE, d MMM 'at' h:mm a", Locale.getDefault()).format(Date(millis))
}
