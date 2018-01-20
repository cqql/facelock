package de.cqql.facelock

import android.os.Bundle
import android.support.v7.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // When the app is first started, the receiver has not been registered at boot time
        ScreenOnReceiver.register(this)
    }

    override fun onDestroy() {
        ScreenOnReceiver.unregister(this)

        super.onDestroy()
    }
}
