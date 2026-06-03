package com.aican.aicankiosklauncher

import android.app.Application

class KioskApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        UiModePrefs.applyFromPreferences(this)
    }
}
