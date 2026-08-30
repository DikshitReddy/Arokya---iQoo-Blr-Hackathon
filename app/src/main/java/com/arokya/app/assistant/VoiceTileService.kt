package com.arokya.app.assistant

import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.arokya.app.MainActivity

/**
 * "Talk to Arokya" in the Quick Settings shade — pull down, tap, start talking.
 *
 * The closest thing to a voice assistant that a third-party app can offer
 * without the always-on-microphone battery/privacy cost of a real hotword:
 * one gesture, no app navigation, straight into listening.
 */
@RequiresApi(Build.VERSION_CODES.N)
class VoiceTileService : TileService() {
    override fun onClick() {
        super.onClick()
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(MainActivity.EXTRA_START_LISTENING, true)
        }
        // Dismisses the shade and unlocks if needed before launching.
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                android.app.PendingIntent.getActivity(
                    this, 0, intent,
                    android.app.PendingIntent.FLAG_IMMUTABLE or
                            android.app.PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
