package com.jjjk.radioptt

import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        title = "Device Info"

        val imei = ImeiHelper.read(this)
        val textImei = findViewById<TextView>(R.id.textImei)
        textImei.text = imei.ifEmpty { "Unavailable — grant Phone permission and reopen" }

        // Auto-save IMEI so it's always current
        if (imei.isNotEmpty()) {
            getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_DEVICE_KEY, imei).apply()
        }

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            setResult(RESULT_OK)
            finish()
        }
    }

    companion object {
        const val PREFS = "ptt_prefs"
        const val KEY_SERVER_URL = "server_url"
        const val KEY_DEVICE_KEY = "device_key"
        const val SERVER_URL = "https://poc.jjjk.com.au"
    }
}
