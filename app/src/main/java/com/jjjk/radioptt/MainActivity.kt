package com.jjjk.radioptt

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.livekit.android.LiveKit
import io.livekit.android.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private val serverUrl = "http://192.168.69.222:5057"
    // Use the device IMEI (printed on the radio) — register it in the admin UI first.
    private val deviceApiKey = "860433050133695"

    private lateinit var room: Room
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        val pttButton = findViewById<Button>(R.id.pttButton)

        room = LiveKit.create(applicationContext)

        pttButton.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> setTransmitting(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> setTransmitting(false)
            }
            true
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            connectToChannel()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
    }

    // Hardware PTT side button on Inrico S200/T522A fires KEYCODE_LAST_CHANNEL (229, raw 0x0195).
    // Intercept at Activity level so it works regardless of which view has focus.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_LAST_CHANNEL) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> { setTransmitting(true); return true }
                KeyEvent.ACTION_UP   -> { setTransmitting(false); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            connectToChannel()
        } else {
            statusText.text = "Microphone permission is required for PTT"
        }
    }

    private fun connectToChannel() {
        statusText.text = "Connecting..."
        lifecycleScope.launch {
            try {
                val (token, livekitUrl, channelName) = fetchToken()
                room.connect(url = livekitUrl, token = token)
                statusText.text = "Connected — channel: $channelName"
            } catch (err: Exception) {
                statusText.text = "Connection failed: ${err.message}"
            }
        }
    }

    private fun setTransmitting(on: Boolean) {
        lifecycleScope.launch {
            try {
                room.localParticipant.setMicrophoneEnabled(on)
            } catch (err: Exception) {
                statusText.text = "Mic error: ${err.message}"
            }
        }
    }

    private data class TokenResponse(val token: String, val livekitUrl: String, val channelName: String)

    private suspend fun fetchToken(): TokenResponse = withContext(Dispatchers.IO) {
        val url = URL("$serverUrl/api/devices/token")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("x-device-key", deviceApiKey)
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000

        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            .bufferedReader().readText()
        if (code !in 200..299) {
            val error = JSONObject(body).optString("error", "HTTP $code")
            throw RuntimeException(error)
        }

        val json = JSONObject(body)
        TokenResponse(
            token = json.getString("token"),
            livekitUrl = json.getString("livekitUrl"),
            channelName = json.getJSONObject("channel").getString("name"),
        )
    }

    override fun onDestroy() {
        room.disconnect()
        super.onDestroy()
    }
}
