package com.arokya.app.nudge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Handles the notification's "Not now" button without opening the app.
 *
 * Dismissing also stops the schedule: someone who says "not now" to a nudge
 * doesn't want another one in five minutes. [Nudge.schedule] restarts it the
 * next time they open the app, which is the natural signal that they're
 * interested again.
 */
class NudgeActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_NOT_NOW = "com.arokya.app.NUDGE_NOT_NOW"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_NOT_NOW) return
        Nudge.dismiss(context)
        Nudge.cancel(context)
    }
}
