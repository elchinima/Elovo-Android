package com.elovo.elovo

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class CallRejectReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        MyFirebaseMessagingService.stopRingtone()
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(1)

        val callerId = intent.getStringExtra("caller_id") ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = java.net.URL("https://elovo-app.onrender.com/api/calls/reject")
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                val body = """{"callerId":"$callerId"}"""
                connection.outputStream.write(body.toByteArray())
                connection.outputStream.flush()
                val responseCode = connection.responseCode
                android.util.Log.d("REJECT", "Response: $responseCode")
                connection.disconnect()
            } catch (e: Exception) {
                android.util.Log.e("REJECT", "Error: ${e.message}")
            }
        }
    }
}