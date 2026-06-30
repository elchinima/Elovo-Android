package com.elovo.elovo

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.elsim.elovo.R
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.media.MediaMetadata

class UploadForegroundService : Service() {

    private val NOTIFICATION_ID = 101
    private val CHANNEL_ID = "elovo_upload_channel"
    private var currentFileName = ""
    private var mediaSession: MediaSession? = null
    private var audioDurationMs: Long = 0L
    private var audioPositionMs: Long = 0L
    private var isAudioPlaying: Boolean = false
    private val autoCloseHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val autoCloseRunnable = Runnable {
        stopAudioSession()
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY

        when (action) {
            "START" -> {
                currentFileName = intent.getStringExtra("fileName") ?: "File"
                createNotificationChannel()
                val notification = getNotification(currentFileName, 0)
                startForeground(NOTIFICATION_ID, notification)
            }
            "UPDATE" -> {
                val progress = intent.getIntExtra("progress", 0)
                val notification = getNotification(currentFileName, progress)
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.notify(NOTIFICATION_ID, notification)
            }
            "STOP" -> {
                val success = intent.getBooleanExtra("success", false)
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (success) {
                    val doneNotification = getCompleteNotification(currentFileName)
                    notificationManager.notify(NOTIFICATION_ID, doneNotification)
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        stopSelf()
                    }, 3000)
                } else {
                    stopSelf()
                }
            }
            "START_AUDIO" -> {
                val title = intent.getStringExtra("title") ?: "Voice message"
                audioDurationMs = intent.getLongExtra("durationMs", 0L)
                audioPositionMs = 0L
                isAudioPlaying = true
                createNotificationChannel()
                startAudioSession(title)
                cancelAutoClose()
            }
            "STOP_AUDIO" -> {
                cancelAutoClose()
                stopAudioSession()
                stopSelf()
            }
            "UPDATE_AUDIO_POSITION" -> {
                audioPositionMs = intent.getLongExtra("positionMs", 0L)
                audioDurationMs = intent.getLongExtra("durationMs", audioDurationMs)
                updatePlaybackState()
            }
            "UPDATE_AUDIO_STATE" -> {
                val playing = intent.getBooleanExtra("isPlaying", false)
                isAudioPlaying = playing
                updatePlaybackState()
                updateAudioNotification()
                if (playing) {
                    cancelAutoClose()
                } else {
                    scheduleAutoClose()
                }
            }
        }

        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        cancelAutoClose()
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelAutoClose()
        stopAudioSession()
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID)
    }

    private fun scheduleAutoClose() {
        autoCloseHandler.removeCallbacks(autoCloseRunnable)
        autoCloseHandler.postDelayed(autoCloseRunnable, 60_000L)
    }

    private fun cancelAutoClose() {
        autoCloseHandler.removeCallbacks(autoCloseRunnable)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Elovo Uploads",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun getLocalizedText(key: String): String {
        val lang = getSharedPreferences("elovo_prefs", Context.MODE_PRIVATE)
            .getString("language", "en") ?: "en"

        return when (lang) {
            "ru" -> when (key) {
                "uploading" -> "Загрузка файла..."
                "upload_complete" -> "Загрузка завершена"
                "voice_message" -> "Голосовое сообщение"
                else -> key
            }
            "az" -> when (key) {
                "uploading" -> "Fayl yüklənir..."
                "upload_complete" -> "Yükləmə tamamlandı"
                "voice_message" -> "Səsli mesaj"
                else -> key
            }
            else -> when (key) {
                "uploading" -> "Uploading file..."
                "upload_complete" -> "Upload complete"
                "voice_message" -> "Voice message"
                else -> key
            }
        }
    }

    private fun getNotification(fileName: String, progress: Int): Notification {
        val title = getLocalizedText("uploading")
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(fileName)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setProgress(100, progress, false)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun getCompleteNotification(fileName: String): Notification {
        val title = getLocalizedText("upload_complete")
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(fileName)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(false)
            .setProgress(0, 0, false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun getContentIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun updatePlaybackState() {
        try {
            val stateInt = if (isAudioPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
            val state = PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or
                    PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_PLAY_PAUSE
                )
                .setState(stateInt, audioPositionMs, if (isAudioPlaying) 1.0f else 0f)
                .build()
            mediaSession?.setPlaybackState(state)
        } catch (e: Exception) {
            Log.e("AUDIO_SESSION", "Error updating playback state: ${e.message}")
        }
    }

    private fun updateAudioNotification() {
        try {
            val displayTitle = getLocalizedText("voice_message")

            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }

            builder.setContentTitle(displayTitle)
                .setContentText("Elovo")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(isAudioPlaying)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .setContentIntent(getContentIntent())

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                builder.setStyle(Notification.MediaStyle().setMediaSession(mediaSession?.sessionToken))
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, builder.build())
        } catch (e: Exception) {
            Log.e("AUDIO_SESSION", "Error updating notification: ${e.message}")
        }
    }

    private fun startAudioSession(title: String) {
        try {
            if (mediaSession == null) {
                mediaSession = MediaSession(this, "ElovoAudioSession").apply {
                    setCallback(object : MediaSession.Callback() {
                        override fun onPause() {
                            MainActivity.instance?.evaluateJs("window.toggleVoiceFromNative()")
                        }
                        override fun onPlay() {
                            MainActivity.instance?.evaluateJs("window.toggleVoiceFromNative()")
                        }
                    })
                }
            }

            val stateBuilder = PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or
                    PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_PLAY_PAUSE
                )
                .setState(PlaybackState.STATE_PLAYING, audioPositionMs, 1.0f)
            mediaSession?.setPlaybackState(stateBuilder.build())

            val displayTitle = if (title == "Voice message") getLocalizedText("voice_message") else title
            val metadataBuilder = MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, displayTitle)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "Elovo")
            if (audioDurationMs > 0) {
                metadataBuilder.putLong(MediaMetadata.METADATA_KEY_DURATION, audioDurationMs)
            }
            mediaSession?.setMetadata(metadataBuilder.build())

            mediaSession?.isActive = true

            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }

            builder.setContentTitle(displayTitle)
                .setContentText("Elovo")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .setContentIntent(getContentIntent())

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                builder.setStyle(Notification.MediaStyle().setMediaSession(mediaSession?.sessionToken))
            }

            startForeground(NOTIFICATION_ID, builder.build())
        } catch (e: Exception) {
            Log.e("AUDIO_SESSION", "Error: ${e.message}")
        }
    }

    private fun stopAudioSession() {
        try {
            mediaSession?.isActive = false
            mediaSession?.release()
            mediaSession = null
        } catch (e: Exception) {
            Log.e("AUDIO_SESSION", "Error stopping: ${e.message}")
        }
    }
}
