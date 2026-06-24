package com.jjjk.radioptt

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin
import android.os.Binder
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import io.livekit.android.LiveKit
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
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
    var currentChannelId: String = ""
    private var idleStatus = ""   // "DeviceName · ChannelName" — restored after TX/RX clears
    private var transmitting = false

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

    data class Channel(val id: String, val name: String)

    suspend fun fetchChannels(): List<Channel> = withContext(Dispatchers.IO) {
        val conn = URL("$serverUrl/api/channels/available").openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("x-device-key", deviceKey)
        conn.connectTimeout = 8_000
        conn.readTimeout = 8_000
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream).bufferedReader().readText()
        if (code !in 200..299) throw RuntimeException("Failed to fetch channels: HTTP $code")
        val arr = org.json.JSONArray(body)
        (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            Channel(obj.getString("id"), obj.getString("name"))
        }
    }

    fun switchChannel(channelId: String, onStatus: (String) -> Unit) {
        scope.launch {
            try {
                notify("Switching channel…")
                withContext(Dispatchers.IO) {
                    val conn = URL("$serverUrl/api/devices/self/channel").openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.setRequestProperty("x-device-key", deviceKey)
                    conn.doOutput = true
                    conn.connectTimeout = 8_000
                    conn.readTimeout = 8_000
                    conn.outputStream.write("""{"channelId":"$channelId"}""".toByteArray())
                    conn.responseCode
                    conn.disconnect()
                }
                room.disconnect()
                connectToChannel()
            } catch (e: Exception) {
                notify("Switch failed: ${e.message}")
            }
        }
    }

    fun setTransmitting(on: Boolean) {
        transmitting = on
        scope.launch {
            try {
                room.localParticipant.setMicrophoneEnabled(on)
                if (on) {
                    notify("▶ TRANSMITTING")
                } else {
                    playRogerBeep()
                    notify(idleStatus)
                }
            } catch (e: Exception) {
                notify("Mic error: ${e.message}")
            }
        }
    }

    // CB-style descending bloop: sweeps from 1050 Hz down to 600 Hz over 220 ms.
    private fun playRogerBeep() {
        scope.launch(Dispatchers.IO) {
            try {
                val rate = 44100
                val pcm  = synthSweep(1050.0, 600.0, 0.220, rate, 0.30)
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(rate)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(pcm.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                track.write(pcm, 0, pcm.size)
                track.play()
                delay(300)
                track.stop()
                track.release()
            } catch (_: Exception) {}
        }
    }

    // Linear frequency chirp with phase-accurate accumulation and 15 ms fade-in/out.
    private fun synthSweep(f0: Double, f1: Double, durSec: Double, rate: Int, amp: Double): ShortArray {
        val n    = (rate * durSec).toInt()
        val fade = (rate * 0.015).toInt()
        return ShortArray(n) { i ->
            val t     = i.toDouble() / rate
            val phase = 2.0 * PI * (f0 * t + (f1 - f0) * t * t / (2.0 * durSec))
            val env   = when {
                i < fade     -> i.toDouble() / fade
                i > n - fade -> (n - i).toDouble() / fade
                else         -> 1.0
            }
            (sin(phase) * 32767 * amp * env).toInt().toShort()
        }
    }

    private fun connectToChannel() {
        scope.launch {
            try {
                notify("Connecting…")
                val result = fetchToken()
                currentChannelId = result.channelId
                idleStatus = "${result.deviceName} · ${result.channelName}"
                room.connect(url = result.livekitUrl, token = result.token)
                // LiveKit's AudioSwitch resets speakerphone ~350ms after connect.
                // Wait for it to settle then force speaker + max voice volume.
                delay(600)
                val am = getSystemService(AudioManager::class.java)
                am.mode = AudioManager.MODE_IN_COMMUNICATION
                am.isSpeakerphoneOn = true
                am.setStreamVolume(
                    AudioManager.STREAM_VOICE_CALL,
                    am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL),
                    0
                )
                notify(idleStatus)
                startRoomEventListener()
            } catch (e: Exception) {
                notify("Failed: ${e.message}")
            }
        }
    }

    private fun startRoomEventListener() {
        scope.launch {
            room.events.collect { event ->
                when (event) {
                    is RoomEvent.TrackUnmuted -> {
                        val p = event.participant
                        if (p.sid == room.localParticipant.sid) return@collect
                        if (transmitting) return@collect
                        val name = (p.name?.takeIf { it.isNotBlank() } ?: p.identity ?: "?").toString()
                        notify("◀ $name")
                    }
                    is RoomEvent.TrackMuted -> {
                        val p = event.participant
                        if (p.sid == room.localParticipant.sid) return@collect
                        if (transmitting) return@collect
                        notify(idleStatus)
                    }
                    else -> {}
                }
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

    data class TokenResult(val token: String, val livekitUrl: String, val deviceName: String, val channelName: String, val channelId: String)

    private suspend fun fetchToken(): TokenResult = withContext(Dispatchers.IO) {
        val conn = URL("$serverUrl/api/devices/token").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("x-device-key", deviceKey)
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream).bufferedReader().readText()
        if (code !in 200..299) throw RuntimeException(JSONObject(body).optString("error", "HTTP $code"))
        val json = JSONObject(body)
        val ch = json.getJSONObject("channel")
        TokenResult(
            token = json.getString("token"),
            livekitUrl = json.getString("livekitUrl"),
            deviceName = json.optString("deviceName", deviceKey),
            channelName = ch.getString("name"),
            channelId = ch.getString("id"),
        )
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
        val am = getSystemService(AudioManager::class.java)
        am.isSpeakerphoneOn = false
        am.mode = AudioManager.MODE_NORMAL
        super.onDestroy()
    }

    companion object {
        const val NOTIF_ID = 1001
        const val CHANNEL_ID = "ptt_service"
    }
}
