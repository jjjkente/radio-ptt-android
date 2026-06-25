package com.jjjk.radioptt

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.os.Bundle
import android.os.IBinder
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.AdapterView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private var pttService: PttService? = null
    private var serviceBound = false
    private lateinit var statusText: TextView
    private lateinit var channelSpinner: Spinner
    private var channels: List<PttService.Channel> = emptyList()
    private lateinit var channelListener: AdapterView.OnItemSelectedListener
    private var replayUrls: List<String> = emptyList()
    private var replayIndex = 0
    private var mediaPlayer: MediaPlayer? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            pttService = (binder as PttService.PttBinder).getService()
            serviceBound = true
            startPtt()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            pttService = null
            serviceBound = false
        }
    }

    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val (url, key) = loadPrefs()
            if (serviceBound) {
                pttService?.reconnect(url, key) { status -> runOnUiThread { updateStatus(status) } }
            } else {
                requestPermissionsAndStart()
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.RECORD_AUDIO] == true) {
            bindAndStart()
        } else {
            updateStatus("Microphone permission required")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        channelSpinner = findViewById(R.id.channelSpinner)
        val pttButton    = findViewById<Button>(R.id.pttButton)
        val imeiLabel    = findViewById<TextView>(R.id.imeiLabel)
        val btnIdentify  = findViewById<Button>(R.id.btnIdentify)
        val btnReplay    = findViewById<Button>(R.id.btnReplay)

        // Read IMEI once, use for both display and auto-config
        val imei = ImeiHelper.read(this)
        if (imei.isNotEmpty()) {
            imeiLabel.text = "IMEI: $imei"
            getSharedPreferences(SettingsActivity.PREFS, MODE_PRIVATE).edit()
                .putString(SettingsActivity.KEY_DEVICE_KEY, imei).apply()
        }

        btnIdentify.setOnClickListener { pttService?.announceIdentity() }

        btnReplay.setOnClickListener { handleReplayPress() }

        // Circular button — red at rest, green while transmitting
        pttButton.background = pttButtonDrawable("#c0392b")

        channelListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val selected = channels.getOrNull(position) ?: return
                val service = pttService ?: return
                if (selected.id == service.currentChannelId) return
                pttService?.switchChannel(selected.id) { status -> runOnUiThread { updateStatus(status) } }
            }
        }

        pttButton.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    pttService?.setTransmitting(true)
                    pttButton.background = pttButtonDrawable("#22c55e")
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    pttService?.setTransmitting(false)
                    pttButton.background = pttButtonDrawable("#c0392b")
                }
            }
            true
        }

        val key = getSharedPreferences(SettingsActivity.PREFS, MODE_PRIVATE)
            .getString(SettingsActivity.KEY_DEVICE_KEY, "") ?: ""
        if (key.isEmpty()) {
            settingsLauncher.launch(Intent(this, SettingsActivity::class.java))
        } else {
            requestPermissionsAndStart()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_SETTINGS, 0, "Settings")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == MENU_SETTINGS) {
            settingsLauncher.launch(Intent(this, SettingsActivity::class.java))
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    // Hardware PTT side button on Inrico S200/T522A fires KEYCODE_LAST_CHANNEL (229, raw 0x0195).
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_LAST_CHANNEL) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> { pttService?.setTransmitting(true); return true }
                KeyEvent.ACTION_UP   -> { pttService?.setTransmitting(false); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun requestPermissionsAndStart() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.READ_PHONE_STATE)
        }
        if (needed.isEmpty()) {
            bindAndStart()
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun bindAndStart() {
        val intent = Intent(this, PttService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    private fun startPtt() {
        val (url, key) = loadPrefs()
        if (url.isEmpty() || key.isEmpty()) return
        updateStatus("Connecting…")
        pttService?.start(url, key) { status -> runOnUiThread { updateStatus(status) } }
        loadChannels()
        startChannelPolling()
    }

    private fun loadChannels() {
        val service = pttService ?: return
        lifecycleScope.launch {
            try {
                val list = service.fetchChannels()
                if (list == channels) return@launch  // no change, skip redraw
                channels = list
                val adapter = ArrayAdapter(this@MainActivity, R.layout.spinner_item, list.map { it.name })
                adapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
                // Detach listener before programmatic changes so adapter/selection
                // callbacks don't trigger a spurious channel switch.
                channelSpinner.onItemSelectedListener = null
                channelSpinner.adapter = adapter
                val curId = pttService?.currentChannelId ?: ""
                val idx = list.indexOfFirst { it.id == curId }
                if (idx >= 0) channelSpinner.setSelection(idx)
                // Reattach after the handler queue drains (setAdapter/setSelection post callbacks)
                channelSpinner.post { channelSpinner.onItemSelectedListener = channelListener }
            } catch (_: Exception) {}
        }
    }

    private fun startChannelPolling() {
        lifecycleScope.launch {
            while (true) {
                kotlinx.coroutines.delay(30_000)
                loadChannels()
            }
        }
    }

    private fun loadPrefs(): Pair<String, String> {
        val p = getSharedPreferences(SettingsActivity.PREFS, Context.MODE_PRIVATE)
        return Pair(
            SettingsActivity.SERVER_URL,
            p.getString(SettingsActivity.KEY_DEVICE_KEY, "") ?: ""
        )
    }

    private fun updateStatus(text: String) {
        statusText.text = text
        val color = when {
            text.contains(" · ") || text.contains("TRANSMITTING") || text.startsWith("◀") -> "#22c55e"
            text.contains("Connecting") || text.contains("Switching") || text.contains("Starting") -> "#f59e0b"
            text.contains("Failed") || text.contains("error") || text.contains("permission") ||
                text.contains("No network") -> "#ef4444"
            else -> "#94a3b8"
        }
        statusText.setTextColor(Color.parseColor(color))
    }

    private fun handleReplayPress() {
        val (url, key) = loadPrefs()
        val service = pttService ?: return
        val channelId = service.currentChannelId
        if (channelId.isEmpty()) {
            Toast.makeText(this, "Not connected to a channel", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            try {
                val recordings = withContext(Dispatchers.IO) {
                    val conn = URL("$url/api/recordings/channel/$channelId").openConnection() as HttpURLConnection
                    conn.setRequestProperty("x-device-key", key)
                    conn.connectTimeout = 5_000
                    conn.readTimeout = 5_000
                    val code = conn.responseCode
                    if (code == 404) return@withContext emptyList<String>()
                    val body = conn.inputStream.bufferedReader().readText()
                    val arr = JSONArray(body)
                    (0 until arr.length()).map { arr.getJSONObject(it).getString("url") }
                }
                if (recordings.isEmpty()) {
                    Toast.makeText(this@MainActivity, "No recordings yet", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                if (recordings != replayUrls) { replayUrls = recordings; replayIndex = 0 }
                else replayIndex = (replayIndex + 1) % replayUrls.size.coerceAtMost(5)
                val playUrl = replayUrls[replayIndex]
                mediaPlayer?.release()
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(playUrl)
                    prepareAsync()
                    setOnPreparedListener { start() }
                    setOnErrorListener { _, _, _ -> false }
                }
            } catch (_: Exception) {
                Toast.makeText(this@MainActivity, "Replay unavailable", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun pttButtonDrawable(hex: String): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(hex))
        }
    }

    override fun onDestroy() {
        mediaPlayer?.release()
        mediaPlayer = null
        if (serviceBound) {
            unbindService(connection)
            serviceBound = false
        }
        super.onDestroy()
    }

    companion object {
        private const val MENU_SETTINGS = 1
    }
}
