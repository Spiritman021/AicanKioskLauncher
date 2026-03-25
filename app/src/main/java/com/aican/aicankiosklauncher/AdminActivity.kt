package com.aican.aicankiosklauncher

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.UserManager
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * AdminActivity — Secret settings panel for servicing the device.
 *
 * Accessible only via the 7-tap password on the kiosk screen.
 * Provides controls to:
 * - View device owner status
 * - Re-lock into kiosk mode
 * - Temporarily clear user restrictions
 * - Open Android Settings
 * - Manage whitelisted apps (toggle on/off)
 * - Return to kiosk launcher
 */
class AdminActivity : AppCompatActivity() {

    private lateinit var dpm: DevicePolicyManager
    private lateinit var adminComponent: ComponentName
    private lateinit var appListAdapter: AppListAdapter

    companion object {
        private const val PREFS_NAME = "kiosk_prefs"
        private const val KEY_WHITELISTED_APPS = "whitelisted_apps"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin)

        dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, MyDeviceAdminReceiver::class.java)

        val tvDeviceOwnerStatus = findViewById<TextView>(R.id.tvDeviceOwnerStatus)
        val btnRelockKiosk = findViewById<Button>(R.id.btnRelockKiosk)
        val btnOpenSettings = findViewById<Button>(R.id.btnOpenSettings)
        val btnClearRestrictions = findViewById<Button>(R.id.btnClearRestrictions)
        val btnReturnToKiosk = findViewById<Button>(R.id.btnReturnToKiosk)
        val etSearchApps = findViewById<EditText>(R.id.etSearchApps)
        val rvInstalledApps = findViewById<RecyclerView>(R.id.rvInstalledApps)

        // Show device owner status
        val isDeviceOwner = dpm.isDeviceOwnerApp(packageName)
        tvDeviceOwnerStatus.text = getString(
            R.string.device_owner_status,
            if (isDeviceOwner) "YES ✅" else "NO ❌"
        )

        // ── Load installed apps ──
        val whitelistedSet = loadWhitelistedApps()
        val installedApps = getInstalledLaunchableApps(whitelistedSet)

        appListAdapter = AppListAdapter(installedApps) { packageName, isEnabled ->
            toggleWhitelistedApp(packageName, isEnabled)
        }

        rvInstalledApps.layoutManager = LinearLayoutManager(this)
        rvInstalledApps.adapter = appListAdapter

        // Search filter
        etSearchApps.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                appListAdapter.filter(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // ── Existing button handlers ──

        // Re-lock into kiosk mode
        btnRelockKiosk.setOnClickListener {
            if (isDeviceOwner) {
                try {
                    val whitelisted = loadWhitelistedApps()
                    val allAllowed = mutableListOf(packageName)
                    allAllowed.addAll(whitelisted)
                    dpm.setLockTaskPackages(adminComponent, allAllowed.toTypedArray())
                    dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_FACTORY_RESET)
                    dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_ADD_USER)
                    dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
                    dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_SAFE_BOOT)
                    Toast.makeText(this, getString(R.string.kiosk_relocked), Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            returnToKiosk()
        }

        // Open Android Settings
        btnOpenSettings.setOnClickListener {
            try {
                val intent = Intent(Settings.ACTION_SETTINGS)
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Cannot open settings: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }

        // Temporarily clear user restrictions
        btnClearRestrictions.setOnClickListener {
            if (isDeviceOwner) {
                try {
                    dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_FACTORY_RESET)
                    dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_ADD_USER)
                    dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
                    dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_SAFE_BOOT)

                    // Unsuspend packages
                    try {
                        dpm.setPackagesSuspended(
                            adminComponent,
                            arrayOf(
                                "com.android.settings",
                                "com.google.android.gms"
                            ),
                            false
                        )
                    } catch (e: Exception) {
                        // Some packages may not be unsuspendable
                    }

                    Toast.makeText(this, getString(R.string.restrictions_cleared), Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        // Return to kiosk
        btnReturnToKiosk.setOnClickListener {
            returnToKiosk()
        }
    }

    /**
     * Query PackageManager for all installed apps that have a launcher intent,
     * excluding our own kiosk launcher package.
     */
    private fun getInstalledLaunchableApps(whitelistedSet: Set<String>): List<InstalledAppInfo> {
        val pm = packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        @Suppress("DEPRECATION")
        val resolveInfos = pm.queryIntentActivities(mainIntent, 0)

        return resolveInfos
            .filter { it.activityInfo.packageName != packageName }
            .map { resolveInfo ->
                val pkgName = resolveInfo.activityInfo.packageName
                InstalledAppInfo(
                    packageName = pkgName,
                    appName = resolveInfo.loadLabel(pm).toString(),
                    icon = resolveInfo.loadIcon(pm),
                    isWhitelisted = whitelistedSet.contains(pkgName)
                )
            }
            .sortedWith(compareByDescending<InstalledAppInfo> { it.isWhitelisted }.thenBy { it.appName.lowercase() })
    }

    /**
     * Load whitelisted app package names from SharedPreferences.
     */
    private fun loadWhitelistedApps(): Set<String> {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_WHITELISTED_APPS, emptySet())?.toSet() ?: emptySet()
    }

    /**
     * Add or remove a package from the whitelist and persist.
     */
    private fun toggleWhitelistedApp(pkg: String, isEnabled: Boolean) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentSet = prefs.getStringSet(KEY_WHITELISTED_APPS, emptySet())?.toMutableSet() ?: mutableSetOf()

        if (isEnabled) {
            currentSet.add(pkg)
        } else {
            currentSet.remove(pkg)
        }

        prefs.edit().putStringSet(KEY_WHITELISTED_APPS, currentSet).apply()

        if (dpm.isDeviceOwnerApp(packageName)) {
            try {
                val allAllowed = mutableListOf(packageName)
                allAllowed.addAll(currentSet)
                dpm.setLockTaskPackages(adminComponent, allAllowed.toTypedArray())
            } catch (e: Exception) {
                Toast.makeText(this, "Whitelist updated, but kiosk allow-list failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun returnToKiosk() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
        finish()
    }

    @Suppress("DEPRECATION")
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        returnToKiosk()
    }
}
