package xyz.chenmilin.ankimcpbridge.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import xyz.chenmilin.ankimcpbridge.MainActivity
import xyz.chenmilin.ankimcpbridge.R

class NotificationFactory(private val context: Context) {

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.channel_description)
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    fun buildServiceNotification(port: Int): Notification {
        val openIntent = Intent(context, MainActivity::class.java).let {
            PendingIntent.getActivity(
                context, 0, it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        val stopIntent = Intent(context, McpForegroundService::class.java).apply {
            action = ACTION_STOP
        }.let {
            PendingIntent.getService(
                context, 0, it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.notification_title))
            .setContentText(context.getString(R.string.notification_text, port))
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_media_pause, context.getString(R.string.action_stop), stopIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "ankimcpbridge_service"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "xyz.chenmilin.ankimcpbridge.STOP_SERVICE"
    }
}
