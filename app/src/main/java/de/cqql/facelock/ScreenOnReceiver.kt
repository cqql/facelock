package de.cqql.facelock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log

class ScreenOnReceiver : BroadcastReceiver() {
    override fun onReceive(p0: Context?, p1: Intent?) {
        Log.i(this.javaClass.canonicalName, "Screen ON!")
    }

    companion object {
        val TAG = "ScreenOnReceiver"
        private var receiver: ScreenOnReceiver? = null

        fun register(context: Context) {
            if (receiver == null) {
                val filter = IntentFilter()
                filter.addAction(Intent.ACTION_SCREEN_ON)
                receiver = ScreenOnReceiver()
                context.registerReceiver(receiver, filter)

                Log.d(TAG, "Registered")
            }
        }

        fun unregister(context: Context) {
            if (receiver != null) {
                context.unregisterReceiver(receiver)
                receiver = null
            }
        }
    }
}