package com.aican.aicankiosklauncher

import android.graphics.drawable.Drawable

/**
 * Data class representing an installed app on the device.
 */
data class InstalledAppInfo(
    val packageName: String,
    val appName: String,
    val icon: Drawable,
    var isWhitelisted: Boolean = false
)
