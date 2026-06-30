package com.elovo.elovo

import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.media.AudioManager
import android.util.Log
import android.webkit.JavascriptInterface
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.*
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import android.Manifest
import android.net.Uri
import android.content.Intent

class AndroidBridge(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @JavascriptInterface
    fun onUserLoggedIn(userId: String) {
        FirebaseMessaging.getInstance().token.addOnSuccessListener { fcmToken ->
            sendTokenToServer(userId, fcmToken)
        }
    }

    @JavascriptInterface
    fun onCallStarted() {
        CallForegroundService.start(context)
        (context as? MainActivity)?.runOnUiThread {
            android.widget.Toast.makeText(context, "onCallStarted called", android.widget.Toast.LENGTH_SHORT).show()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val audioAttributes = android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val focusRequest = android.media.AudioFocusRequest.Builder(
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
                )
                    .setAudioAttributes(audioAttributes)
                    .build()
                audioManager.requestAudioFocus(focusRequest)
            }
            audioManager.mode = AudioManager.MODE_IN_CALL
            audioManager.stopBluetoothSco()
            audioManager.isBluetoothScoOn = false
            audioManager.isSpeakerphoneOn = false

            if (isBluetoothHeadsetConnected()) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    audioManager.startBluetoothSco()
                    audioManager.isBluetoothScoOn = true
                    Log.d("AUDIO", "Default: Bluetooth")
                }, 500)
            } else {
                Log.d("AUDIO", "Default: Earpiece")
            }
        }
    }

    @JavascriptInterface
    fun cycleSpeaker(): String {
        return when {
            isBluetoothHeadsetConnected() -> {
                when {
                    audioManager.isBluetoothScoOn -> {
                        audioManager.isBluetoothScoOn = false
                        audioManager.stopBluetoothSco()
                        audioManager.isSpeakerphoneOn = true
                        "speaker"
                    }
                    audioManager.isSpeakerphoneOn -> {
                        audioManager.isSpeakerphoneOn = false
                        "earpiece"
                    }
                    else -> {
                        audioManager.isSpeakerphoneOn = false
                        audioManager.startBluetoothSco()
                        audioManager.isBluetoothScoOn = true
                        "bluetooth"
                    }
                }
            }
            else -> {
                if (audioManager.isSpeakerphoneOn) {
                    audioManager.isSpeakerphoneOn = false
                    "earpiece"
                } else {
                    audioManager.isSpeakerphoneOn = true
                    "speaker"
                }
            }
        }
    }

    @JavascriptInterface
    fun getAvailableAudioDevices(): String {
        val devices = mutableListOf<org.json.JSONObject>()

        if (isBluetoothHeadsetConnected()) {
            devices.add(org.json.JSONObject(mapOf("id" to "bluetooth", "label" to "Bluetooth")))
        }
        devices.add(org.json.JSONObject(mapOf("id" to "speaker", "label" to "Speaker")))
        devices.add(org.json.JSONObject(mapOf("id" to "earpiece", "label" to "Earpiece")))

        return org.json.JSONArray(devices).toString()
    }

    @JavascriptInterface
    fun setAudioDevice(deviceId: String) {
        (context as? MainActivity)?.runOnUiThread {
            audioManager.mode = AudioManager.MODE_IN_CALL
            when (deviceId) {
                "bluetooth" -> {
                    audioManager.isSpeakerphoneOn = false
                    audioManager.stopBluetoothSco()
                    audioManager.isBluetoothScoOn = false
                    audioManager.startBluetoothSco()
                    audioManager.isBluetoothScoOn = true
                }
                "speaker" -> {
                    audioManager.isBluetoothScoOn = false
                    audioManager.stopBluetoothSco()
                    audioManager.isSpeakerphoneOn = true
                }
                "earpiece" -> {
                    audioManager.isBluetoothScoOn = false
                    audioManager.stopBluetoothSco()
                    audioManager.isSpeakerphoneOn = false
                }
            }
        }
    }

    @JavascriptInterface
    fun onCallEnded() {
        CallForegroundService.stop(context)
        (context as? MainActivity)?.runOnUiThread {
            audioManager.stopBluetoothSco()
            audioManager.isBluetoothScoOn = false
            audioManager.isSpeakerphoneOn = false
            audioManager.mode = AudioManager.MODE_NORMAL
            Log.d("AUDIO", "Call ended, audio reset")
        }
    }

    @JavascriptInterface
    fun getCurrentAudioDevice(): String {
        return when {
            audioManager.isBluetoothScoOn -> "bluetooth"
            audioManager.isSpeakerphoneOn -> "speaker"
            else -> "earpiece"
        }
    }

    private fun isBluetoothHeadsetConnected(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) != PackageManager.PERMISSION_GRANTED
                ) return false
            }
            val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter() ?: return false
            bluetoothAdapter.getProfileConnectionState(BluetoothProfile.HEADSET) ==
                    BluetoothAdapter.STATE_CONNECTED
        } catch (e: Exception) {
            false
        }
    }

    private fun sendTokenToServer(userId: String, fcmToken: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL("https://elovo-app.onrender.com/api/users/fcm-token")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                val body = """{"userId":"$userId","fcmToken":"$fcmToken"}"""
                val os: OutputStream = connection.outputStream
                os.write(body.toByteArray())
                os.flush()
                Log.d("FCM_BRIDGE", "Token sent: ${connection.responseCode}")
                connection.disconnect()
            } catch (e: Exception) {
                Log.e("FCM_BRIDGE", "Error: ${e.message}")
            }
        }
    }

    @JavascriptInterface
    fun enableProximitySensor() {
        (context as? MainActivity)?.startProximitySensor()
    }

    @JavascriptInterface
    fun disableProximitySensor() {
        (context as? MainActivity)?.stopProximitySensor()
    }

    @JavascriptInterface
    fun stopCallNotification() {
        MyFirebaseMessagingService.stopRingtone()
        (context as? MainActivity)?.runOnUiThread {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            notificationManager.cancel(1)
        }
    }

    @JavascriptInterface
    fun downloadFile(url: String, filename: String) {
        try {
            val request = android.app.DownloadManager.Request(Uri.parse(url))
            request.setTitle(filename)
            request.setDescription("Downloading...")
            request.setNotificationVisibility(
                android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            )
            request.setDestinationInExternalPublicDir(
                android.os.Environment.DIRECTORY_DOWNLOADS,
                filename
            )
            val cookieManager = android.webkit.CookieManager.getInstance()
            val cookies = cookieManager.getCookie("https://elovo-app.onrender.com")
            if (cookies != null) {
                request.addRequestHeader("Cookie", cookies)
            }

            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE)
                    as android.app.DownloadManager
            downloadManager.enqueue(request)
        } catch (e: Exception) {
            Log.e("DOWNLOAD", "Error: ${e.message}")
        }
    }

    @JavascriptInterface
    fun setLanguage(language: String) {
        context.getSharedPreferences("elovo_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("language", language)
            .apply()
    }

    @JavascriptInterface
    fun triggerBoundaryPulse() {
        (context as? MainActivity)?.runOnUiThread {
            (context as? MainActivity)?.animateBoundaryPulse()
        }
    }

    @JavascriptInterface
    fun clearCookies() {
        context.getSharedPreferences("elovo_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("logged_out", true)
            .apply()
    }

    private var uploadWakeLock: android.os.PowerManager.WakeLock? = null

    @JavascriptInterface
    fun startUploadWakeLock() {
        (context as? MainActivity)?.runOnUiThread {
            try {
                if (uploadWakeLock == null) {
                    val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                    uploadWakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "elovo:upload_wakelock")
                }
                if (uploadWakeLock?.isHeld == false) {
                    uploadWakeLock?.acquire(10 * 60 * 1000L)
                    Log.d("WAKELOCK", "Upload WakeLock acquired")
                }
            } catch (e: Exception) {
                Log.e("WAKELOCK", "Error acquiring WakeLock: ${e.message}")
            }
        }
    }

    @JavascriptInterface
    fun stopUploadWakeLock() {
        (context as? MainActivity)?.runOnUiThread {
            try {
                if (uploadWakeLock?.isHeld == true) {
                    uploadWakeLock?.release()
                    Log.d("WAKELOCK", "Upload WakeLock released")
                }
            } catch (e: Exception) {
                Log.e("WAKELOCK", "Error releasing WakeLock: ${e.message}")
            }
        }
    }

    @JavascriptInterface
    fun startAudioPlayback(title: String, durationMs: Long) {
        try {
            val intent = Intent(context, UploadForegroundService::class.java).apply {
                action = "START_AUDIO"
                putExtra("title", title)
                putExtra("durationMs", durationMs)
            }
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        } catch (e: Exception) {
            Log.e("UPLOAD_BRIDGE", "Error starting audio playback service: ${e.message}")
        }
    }

    @JavascriptInterface
    fun stopAudioPlayback() {
        try {
            val intent = Intent(context, UploadForegroundService::class.java).apply {
                action = "STOP_AUDIO"
            }
            context.startService(intent)
        } catch (e: Exception) {
            Log.e("UPLOAD_BRIDGE", "Error stopping audio playback service: ${e.message}")
        }
    }

    @JavascriptInterface
    fun updateAudioPosition(positionMs: Long, durationMs: Long) {
        try {
            val intent = Intent(context, UploadForegroundService::class.java).apply {
                action = "UPDATE_AUDIO_POSITION"
                putExtra("positionMs", positionMs)
                putExtra("durationMs", durationMs)
            }
            context.startService(intent)
        } catch (e: Exception) {
            Log.e("UPLOAD_BRIDGE", "Error updating audio position: ${e.message}")
        }
    }

    @JavascriptInterface
    fun updateAudioState(isPlaying: Boolean) {
        try {
            val intent = Intent(context, UploadForegroundService::class.java).apply {
                action = "UPDATE_AUDIO_STATE"
                putExtra("isPlaying", isPlaying)
            }
            context.startService(intent)
        } catch (e: Exception) {
            Log.e("UPLOAD_BRIDGE", "Error updating audio state: ${e.message}")
        }
    }

    @JavascriptInterface
    fun startUploadNotification(fileName: String) {
        try {
            val intent = Intent(context, UploadForegroundService::class.java).apply {
                action = "START"
                putExtra("fileName", fileName)
            }
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        } catch (e: Exception) {
            Log.e("UPLOAD_BRIDGE", "Error starting upload service: ${e.message}")
        }
    }

    @JavascriptInterface
    fun updateUploadNotification(progress: Int) {
        try {
            val intent = Intent(context, UploadForegroundService::class.java).apply {
                action = "UPDATE"
                putExtra("progress", progress)
            }
            context.startService(intent)
        } catch (e: Exception) {
            Log.e("UPLOAD_BRIDGE", "Error updating upload service: ${e.message}")
        }
    }

    @JavascriptInterface
    fun stopUploadNotification(success: Boolean) {
        try {
            val intent = Intent(context, UploadForegroundService::class.java).apply {
                action = "STOP"
                putExtra("success", success)
            }
            context.startService(intent)
        } catch (e: Exception) {
            Log.e("UPLOAD_BRIDGE", "Error stopping upload service: ${e.message}")
        }
    }
}