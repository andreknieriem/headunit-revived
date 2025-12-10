package com.andrerinas.headunitrevived.main

import android.app.PendingIntent
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import com.andrerinas.headunitrevived.App
import com.andrerinas.headunitrevived.R
import com.andrerinas.headunitrevived.aap.AapProjectionActivity
import com.andrerinas.headunitrevived.aap.protocol.proto.MediaPlayback
import com.andrerinas.headunitrevived.contract.MediaKeyIntent

/**
 * @author algavris
 * @date 17/07/2017
 */
class BackgroundNotification(private val context: Context) {

    companion object {
        private const val NOTIFICATION_MEDIA = 1
        const val mediaChannel = "media"
    }

    fun notify(metadata: MediaPlayback.MediaMetaData) {

        val playPauseKey = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        val nextKey = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_NEXT)
        val prevKey = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PREVIOUS)

        val playPause = PendingIntent.getBroadcast(context, 1, MediaKeyIntent(playPauseKey), PendingIntent.FLAG_UPDATE_CURRENT)
        val next = PendingIntent.getBroadcast(context, 1, MediaKeyIntent(nextKey), PendingIntent.FLAG_UPDATE_CURRENT)
        val prev = PendingIntent.getBroadcast(context, 1, MediaKeyIntent(prevKey), PendingIntent.FLAG_UPDATE_CURRENT)

        val notification = NotificationCompat.Builder(context, mediaChannel)
                .setContentTitle(metadata.song)
                .setAutoCancel(false)
                .setOngoing(true)
                .setContentText(metadata.artist)
                .setSubText(String.format("Remaining: %02d:%02d", metadata.duration / 60, metadata.duration % 60))
                .setSmallIcon(R.drawable.ic_stat_aa)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(PendingIntent.getActivity(context, 0, AapProjectionActivity.intent(context),
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_UPDATE_CURRENT))
                .addAction(R.drawable.ic_skip_previous_black_24dp, "Previous", prev)
                .addAction(R.drawable.ic_play_arrow_black_24dp, "Play/Pause", playPause)
                .addAction(R.drawable.ic_skip_next_black_24dp, "Next", next)


        if (!metadata.albumart.isEmpty) {
            val image = BitmapFactory.decodeByteArray(metadata.albumart.toByteArray(), 0, metadata.albumart.size())
            notification
                    .setStyle(NotificationCompat.BigPictureStyle().bigPicture(image))
                    .setLargeIcon(image)
        }
        App.provide(context).notificationManager.notify(NOTIFICATION_MEDIA, notification.build())
    }

}
