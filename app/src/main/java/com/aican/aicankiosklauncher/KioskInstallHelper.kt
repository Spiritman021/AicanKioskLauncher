package com.aican.aicankiosklauncher

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context

object KioskInstallHelper {

    private const val PREFS_NAME = "kiosk_prefs"
    private const val KEY_WHITELISTED_APPS = "whitelisted_apps"

    private val installerPackages = setOf(
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.miui.packageinstaller",
        "com.google.android.permissioncontroller",
        "com.android.permissioncontroller",
        "com.android.documentsui",
        "com.android.providers.downloads",
        "com.android.providers.downloads.ui",
        "com.android.vending"
    )

    fun buildAllowedPackages(context: Context): Array<String> {
        val whitelisted = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_WHITELISTED_APPS, emptySet())
            ?.toSet()
            ?: emptySet()

        return buildSet {
            add(context.packageName)
            addAll(whitelisted)
            addAll(installerPackages)
        }.toTypedArray()
    }

    fun prepareInstallWindow(
        activity: Activity,
        dpm: DevicePolicyManager,
        adminComponent: ComponentName
    ) {
        if (!dpm.isDeviceOwnerApp(activity.packageName)) return

        try {
            dpm.setLockTaskPackages(adminComponent, buildAllowedPackages(activity))
        } catch (_: Exception) {
        }

        try {
            dpm.setPackagesSuspended(adminComponent, installerPackages.toTypedArray(), false)
        } catch (_: Exception) {
        }

        try {
            activity.stopLockTask()
        } catch (_: Exception) {
        }
    }

    fun restoreKioskWindow(
        activity: Activity,
        dpm: DevicePolicyManager,
        adminComponent: ComponentName
    ) {
        if (!dpm.isDeviceOwnerApp(activity.packageName)) return

        try {
            dpm.setLockTaskPackages(adminComponent, buildAllowedPackages(activity))
        } catch (_: Exception) {
        }

        try {
            activity.startLockTask()
        } catch (_: Exception) {
        }
    }
}
