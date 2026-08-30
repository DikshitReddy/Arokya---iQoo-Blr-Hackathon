package com.arokya.app.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * AlarmManager loses every alarm on reboot, so re-arm all future reminders
 * from the DB once the device finishes booting.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Reminders.rescheduleAll(context)
        }
    }
}
