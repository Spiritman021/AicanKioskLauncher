package com.aican.aicankiosklauncher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Boot Receiver — ensures the kiosk launcher auto-starts after device reboot.
 *
 * Listens for BOOT_COMPLETED broadcast and launches MainActivity
 * with FLAG_ACTIVITY_NEW_TASK so the kiosk re-engages without user interaction.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_BOOT_COMPLETED == intent.action) {
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(launchIntent)
        }
    }
}
