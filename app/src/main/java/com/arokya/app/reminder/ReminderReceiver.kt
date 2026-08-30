package com.arokya.app.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** An alarm came due — post the reminder. Kept trivial; work lives in [Reminders]. */
class ReminderReceiver : BroadcastReceiver() {
    companion object { const val ACTION_FIRE = "com.arokya.app.REMINDER_FIRE" }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val id = intent.getLongExtra(Reminders.EXTRA_REMINDER_ID, -1L)
        if (id >= 0) Reminders.fire(context, id)
    }
}
