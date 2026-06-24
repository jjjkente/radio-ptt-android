package com.jjjk.radioptt

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.TelephonyManager

object ImeiHelper {
    fun read(context: Context): String {
        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED) return ""
        return try {
            val tm = context.getSystemService(TelephonyManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                tm.getImei(0) ?: tm.imei ?: ""
            } else {
                @Suppress("DEPRECATION") tm.deviceId ?: ""
            }
        } catch (_: Exception) { "" }
    }
}
