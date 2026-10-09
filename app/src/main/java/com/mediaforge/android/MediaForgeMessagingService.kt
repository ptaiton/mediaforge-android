package com.mediaforge.android

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class MediaForgeMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        PushRegistration.register(this, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val title = when (message.data["event"]) {
            "media_available" -> getString(R.string.download_completed)
            else -> message.notification?.title ?: message.data["title"] ?: "MediaForge"
        }
        val body = when (message.data["event"]) {
            "media_available" -> getString(R.string.media_available, message.data["media_title"].orEmpty())
            "test" -> getString(R.string.push_test_message)
            else -> message.notification?.body ?: message.data["body"] ?: getString(R.string.new_notification)
        }
        showNotification(title, body, MobileLinks.mediaId(message.data["media_id"]))
    }

    private fun showNotification(title: String, body: String, mediaId: Int?) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val channelId = "mediaforge-events"
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    channelId,
                    getString(R.string.notification_channel),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }

        val credentials = CredentialStore(this).read()
        val notificationTag = MobileLinks.notificationTag(credentials)
        val pendingIntent = PendingIntent.getActivity(
            this,
            mediaId ?: 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                data = android.net.Uri.parse("mediaforge://notification/$notificationTag/${mediaId ?: 0}")
                if (mediaId != null && credentials != null) {
                    putExtra(MobileLinks.MEDIA_ID, mediaId)
                    putExtra(MobileLinks.SERVER_IDENTITY, MobileLinks.identity(credentials))
                }
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.mediaforge_logo)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        NotificationManagerCompat.from(this).notify(notificationTag, mediaId ?: 0, notification)
    }
}
