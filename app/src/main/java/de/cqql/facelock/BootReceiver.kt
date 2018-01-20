package de.cqql.facelock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context != null) {
            ScreenOnReceiver.register(context.applicationContext)
        }
    }
}