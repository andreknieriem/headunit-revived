package com.andrerinas.headunitrevived

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import android.content.IntentFilter
import android.view.KeyEvent
import com.andrerinas.headunitrevived.aap.AapProjectionActivity
import com.andrerinas.headunitrevived.aap.protocol.messages.LocationUpdateEvent
import com.andrerinas.headunitrevived.contract.KeyIntent
import com.andrerinas.headunitrevived.contract.LocationUpdateIntent
import com.andrerinas.headunitrevived.contract.MediaKeyIntent
import com.andrerinas.headunitrevived.contract.ProjectionActivityRequest

class AapBroadcastReceiver : BroadcastReceiver() {

    companion object {
        val filter: IntentFilter by lazy {
            val filter = IntentFilter()
            filter.addAction(LocationUpdateIntent.action)
            filter.addAction(MediaKeyIntent.action)
            filter.addAction(ProjectionActivityRequest.action)
            filter
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val component = App.provide(context)
        if (intent.action == LocationUpdateIntent.action) {
            val location = LocationUpdateIntent.extractLocation(intent)
            if (component.settings.useGpsForNavigation && component.transport.isAlive) {
                App.provide(context).transport.send(LocationUpdateEvent(location))
            }

            if (location.latitude != 0.0 && location.longitude != 0.0) {
                component.settings.lastKnownLocation = location
            }
        } else if (intent.action == MediaKeyIntent.action) {
            val event: KeyEvent? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(KeyIntent.extraEvent, KeyEvent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(KeyIntent.extraEvent)
            }
            if (component.transport.isAlive) {
                event?.let {
                    component.transport.send(it.keyCode, it.action == KeyEvent.ACTION_DOWN)
                }
            }
        } else if (intent.action == ProjectionActivityRequest.action){
            if (component.transport.isAlive) {
                val aapIntent = Intent(context, AapProjectionActivity::class.java)
                aapIntent.putExtra(AapProjectionActivity.EXTRA_FOCUS, true)
                aapIntent.flags = FLAG_ACTIVITY_NEW_TASK
                context.startActivity(aapIntent)
            }
        }
    }
}

