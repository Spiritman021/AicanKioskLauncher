package com.aican.aicankiosklauncher

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat

object KioskWatchdogStarter {

    fun start(context: Context) {
        val appContext = context.applicationContext
        val serviceIntent = Intent(appContext, KioskWatchdogService::class.java)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(appContext, serviceIntent)
            } else {
                appContext.startService(serviceIntent)
            }
        } catch (_: Exception) {
            // Keep kiosk launch alive even if a device/ROM rejects the watchdog service.
        }
    }
}
