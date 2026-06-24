package com.jjjk.radioptt

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import io.livekit.android.LiveKit
import io.livekit.android.room.Room
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class PttService : Service() {

    inner class PttBinder : Binder() {
        fun getService() = this@PttService
    }

    private val binder = PttBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var room: Room
    private lateinit var fusedLocation: FusedLocationProviderClient
    private var serverUrl = ""
    private var deviceKey = ""
    private var statusCallback: ((String) -> Unit)? = null
    private var started = false

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { loc ->
                scope.launch(Dispatchers.IO) {
                    postLocation(loc.latitude, loc.longitude, loc.accuracy)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        room = LiveKit.create(applicationContext)
        fusedLocation = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Starting…"))
    }

    fun start(serverUrl: String, deviceKey: String, onStatus: (String) -> Unit) {
        this.serverUrl = serverUrl
        this.deviceKey = deviceKey
        this.statusCallback = onStatus
        if (!started) {
            started = true
            connectToChannel()
            startLocationReporting()
        }
    }

    fun reconnect(serverUrl: String, deviceKey: String, onStatus: (String) -> Unit) {
        started = false
        this.serverUrl = serverUrl
        this.deviceKey = deviceKey
        room.disconnect()
        start(serverUrl, deviceKey, onStatus)
    }

    fun setTransmitting(on: Boolean) {
        scope.launch {
            try { room.localParticipant.setMicrophoneEnabled(on) } catch (_: Exception) {}
        }
    }

    private fun connectToChannel() {
        scope.launch {
            try {
                notify("Connecting…")
                val (token, livekitUrl, channelName) = fetchToken()
                room.connect(url = livekitUrl, token = token)
                notify("Connected · $channelName")
            } catch (e: Exception) {
                notify("Failed: ${e.message}")
            }
        }
    }

    private fun notify(text: String) {
        updateNotification(text)
        statusCallback?.invoke(text)
    }

    private fun startLocationReporting() {
        val req = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 30_000L)
            .setMinUpdateIntervalMillis(15_000L)
            .build()
        try {
            fusedLocation.requestLocationUpdates(req, locationCallback, Looper.getMainLooper())
        } catch (_: SecurityException) {}
    }

    private fun postLocation(lat: Double, lon: Double, accuracy: Float) {
        try {
            val conn = URL("$serverUrl/api/devices/location").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-device-key", deviceKey)
            conn.doOutput = true
            conn.connectTimeout = 5_000
            conn.readTimeout = 5_000
            conn.outputStream.write("""{"lat":$lat,"lon":$lon,"accuracy":$accuracy}""".toByteArray())
            conn.responseCode
            conn.disconnect()
        } catch (_: Exception) {}
    }

    private suspend fun fetchToken(): Triple<String, String, String> = withContext(Dispatchers.IO) {
        val conn = URL("$serverUrl/api/devices/token").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("x-device-key", deviceKey)
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream).bufferedReader().readText()
        if (code !in 200..299) throw RuntimeException(JSONObject(body).optString("error", "HTTP $code"))
        val json = JSONObject(body)
        Triple(json.getString("token"), json.getString("livekitUrl"), json.getJSONObject("channel").getString("name"))
    }

    private fun createNotificationChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "PTT Radio", NotificationManager.IMPORTANCE_LOW)
        ch.description = "Keeps the PTT connection alive in the background"
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("JJJK Radio PTT")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        fusedLocation.removeLocationUpdates(locationCallback)
        scope.cancel()
        room.disconnect()
        super.onDestroy()
    }

    companion object {
        const val NOTIF_ID = 1001
        const val CHANNEL_ID = "ptt_service"
    }
}
