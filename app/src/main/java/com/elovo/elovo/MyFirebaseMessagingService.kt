package com.elovo.elovo

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.elsim.elovo.R
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MyFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        var ringtonePlayer: MediaPlayer? = null

        fun stopRingtone() {
            ringtonePlayer?.stop()
            ringtonePlayer?.release()
            ringtonePlayer = null
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        val type = remoteMessage.data["type"]

        when (type) {
            "incoming_call" -> {
                val callerId = remoteMessage.data["callerId"] ?: return
                val callerName = remoteMessage.data["callerName"] ?: "Unknown"
                val callerAvatar = remoteMessage.data["callerAvatar"] ?: ""
                showIncomingCallNotification(callerId, callerName, callerAvatar)
                playRingtone()
            }
            "call_cancelled" -> {
                stopRingtone()
                val notificationManager =
                    getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.cancel(1)
            }
            else -> {
                val title = remoteMessage.notification?.title ?: "Elovo"
                val body = remoteMessage.notification?.body ?: ""
                showNotification(title, body)
            }
        }
    }

    override fun onNewToken(token: String) {
        sendTokenToServer(token)
    }

    private fun getLocalizedText(key: String): String {
        val lang = getSharedPreferences("elovo_prefs", Context.MODE_PRIVATE)
            .getString("language", "en") ?: "en"

        return when (lang) {
            "ru" -> when (key) {
                "incoming_call" -> "Входящий звонок"
                "accept" -> "Принять"
                "reject" -> "Отклонить"
                else -> key
            }
            "az" -> when (key) {
                "incoming_call" -> "Daxil olan zəng"
                "accept" -> "Qəbul et"
                "reject" -> "Rədd et"
                else -> key
            }
            else -> when (key) {
                "incoming_call" -> "Incoming call"
                "accept" -> "Accept"
                "reject" -> "Reject"
                else -> key
            }
        }
    }

    private fun playRingtone() {
        try {
            val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtonePlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(applicationContext, ringtoneUri)
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            android.util.Log.e("FCM", "Ringtone error: ${e.message}")
        }
    }

    private fun showIncomingCallNotification(
        callerId: String,
        callerName: String,
        callerAvatar: String
    ) {
        val channelId = "elovo_call_channel"
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val channel = NotificationChannel(
            channelId,
            "Elovo Calls",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Incoming call notifications"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 1000, 500, 1000)
        }
        notificationManager.createNotificationChannel(channel)

        val acceptIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("call_action", "accept")
            putExtra("caller_id", callerId)
            putExtra("caller_name", callerName)
            putExtra("caller_avatar", callerAvatar)
        }
        val acceptPendingIntent = PendingIntent.getActivity(
            this, 1, acceptIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val rejectIntent = Intent(this, CallRejectReceiver::class.java).apply {
            putExtra("caller_id", callerId)
        }
        val rejectPendingIntent = PendingIntent.getBroadcast(
            this, 2, rejectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getLocalizedText("incoming_call"))
            .setContentText(callerName)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setAutoCancel(true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .addAction(R.mipmap.ic_launcher, getLocalizedText("accept"), acceptPendingIntent)
            .addAction(R.mipmap.ic_launcher, getLocalizedText("reject"), rejectPendingIntent)
            .setFullScreenIntent(acceptPendingIntent, true)
            .build()

        notificationManager.notify(1, notification)
    }

    private fun showNotification(title: String, body: String) {
        val channelId = "elovo_channel"
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )

        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val channel = NotificationChannel(
            channelId,
            "Elovo Notifications",
            NotificationManager.IMPORTANCE_HIGH
        )
        notificationManager.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(0, notification)
    }

    private fun sendTokenToServer(token: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = java.net.URL("https://elovo-app.onrender.com/api/users/fcm-token")
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                val body =
                    """{"userId":"00000000-0000-0000-0000-000000000000","fcmToken":"$token"}"""
                connection.outputStream.write(body.toByteArray())
                connection.outputStream.flush()
                android.util.Log.d("FCM_SERVICE", "Token refreshed: ${connection.responseCode}")
                connection.disconnect()
            } catch (e: Exception) {
                android.util.Log.e("FCM_SERVICE", "Error: ${e.message}")
            }
        }
    }
}