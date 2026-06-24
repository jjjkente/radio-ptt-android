package com.jjjk.radioptt

import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        title = "PTT Settings"

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val editUrl = findViewById<EditText>(R.id.editServerUrl)
        val editKey = findViewById<EditText>(R.id.editDeviceKey)

        editUrl.setText(prefs.getString(KEY_SERVER_URL, ""))
        editKey.setText(prefs.getString(KEY_DEVICE_KEY, ""))

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            var url = editUrl.text.toString().trim().trimEnd('/')
            if (url.isNotEmpty() && !url.startsWith("http://") && !url.startsWith("https://")) {
                url = "http://$url"
            }
            prefs.edit()
                .putString(KEY_SERVER_URL, url)
                .putString(KEY_DEVICE_KEY, editKey.text.toString().trim())
                .apply()
            setResult(RESULT_OK)
            finish()
        }
    }

    companion object {
        const val PREFS = "ptt_prefs"
        const val KEY_SERVER_URL = "server_url"
        const val KEY_DEVICE_KEY = "device_key"
    }
}
