package com.jjjk.radioptt

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private var pttService: PttService? = null
    private var serviceBound = false
    private lateinit var statusText: TextView
    private lateinit var channelSpinner: Spinner
    private var channels: List<PttService.Channel> = emptyList()
    private lateinit var channelListener: AdapterView.OnItemSelectedListener

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
                pttService?.reconnect(url, key) { status -> runOnUiThread { statusText.text = status } }
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
            statusText.text = "Microphone permission required"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        channelSpinner = findViewById(R.id.channelSpinner)
        val pttButton = findViewById<Button>(R.id.pttButton)

        channelListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val selected = channels.getOrNull(position) ?: return
                val service = pttService ?: return
                if (selected.id == service.currentChannelId) return
                pttService?.switchChannel(selected.id) { status -> runOnUiThread { statusText.text = status } }
            }
        }
        // Listener is attached only after programmatic updates settle (see loadChannels)

        pttButton.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> pttService?.setTransmitting(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> pttService?.setTransmitting(false)
            }
            true
        }

        val (url, key) = loadPrefs()
        if (url.isEmpty() || key.isEmpty()) {
            // No config yet — open settings immediately.
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
        statusText.text = "Connecting…"
        pttService?.start(url, key) { status -> runOnUiThread { statusText.text = status } }
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
                val adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, list.map { it.name })
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
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
            p.getString(SettingsActivity.KEY_SERVER_URL, "") ?: "",
            p.getString(SettingsActivity.KEY_DEVICE_KEY, "") ?: ""
        )
    }

    override fun onDestroy() {
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
