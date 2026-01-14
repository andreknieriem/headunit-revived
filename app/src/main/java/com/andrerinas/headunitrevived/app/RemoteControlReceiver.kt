package com.andrerinas.headunitrevived.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import com.andrerinas.headunitrevived.App

import com.andrerinas.headunitrevived.utils.AppLog

class RemoteControlReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_MEDIA_BUTTON == intent.action) {
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)?.let { event ->
                AppLog.i("ACTION_MEDIA_BUTTON: " + event.keyCode)
                val transport = App.provide(context).transport
                if (transport.isAlive) {
                    transport.send(event.keyCode, event.action == KeyEvent.ACTION_DOWN)
                }
            }
        }
    }
}
