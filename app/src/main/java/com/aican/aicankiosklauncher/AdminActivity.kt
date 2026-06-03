package com.aican.aicankiosklauncher

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.UserManager
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.materialswitch.MaterialSwitch

class AdminActivity : AppCompatActivity() {

    private lateinit var dpm: DevicePolicyManager
    private lateinit var adminComponent: ComponentName
    private lateinit var appListAdapter: AppListAdapter

    companion object {
        private const val ADMIN_PASSWORD = "aican2024"
        private const val PREFS_NAME = "kiosk_prefs"
        private const val KEY_WHITELISTED_APPS = "whitelisted_apps"
        private const val RESTRICTION_DISALLOW_REMOVE_DEVICE_ADMIN = "no_remove_device_admin"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, MyDeviceAdminReceiver::class.java)

        val btnTabActions = findViewById<Button>(R.id.btnTabActions)
        val btnTabWhitelist = findViewById<Button>(R.id.btnTabWhitelist)
        val layoutActionsTab = findViewById<NestedScrollView>(R.id.layoutActionsTab)
        val layoutWhitelistTab = findViewById<NestedScrollView>(R.id.layoutWhitelistTab)
        val switchDarkMode = findViewById<MaterialSwitch>(R.id.switchDarkMode)
        val tvDeviceOwnerStatus = findViewById<TextView>(R.id.tvDeviceOwnerStatus)
        val btnSetDefaultLauncher = findViewById<Button>(R.id.btnSetDefaultLauncher)
        val btnClearDefaultLauncher = findViewById<Button>(R.id.btnClearDefaultLauncher)
        val btnRelockKiosk = findViewById<Button>(R.id.btnRelockKiosk)
        val btnOpenSettings = findViewById<Button>(R.id.btnOpenSettings)
        val btnUpdateApp = findViewById<Button>(R.id.btnUpdateApp)
        val btnDisableKioskCompletely = findViewById<Button>(R.id.btnDisableKioskCompletely)
        val btnReturnToKiosk = findViewById<Button>(R.id.btnReturnToKiosk)
        val bottomActionBar = findViewById<View>(R.id.bottomActionBarAdmin)
        val etSearchApps = findViewById<EditText>(R.id.etSearchApps)
        val rvInstalledApps = findViewById<RecyclerView>(R.id.rvInstalledApps)
        applyBottomSystemInset(bottomActionBar)

        tvDeviceOwnerStatus.text = getString(
            R.string.device_owner_status,
            if (dpm.isDeviceOwnerApp(packageName)) "YES ✅" else "NO ❌"
        )
        updateLauncherStatus()

        switchDarkMode.isChecked = UiModePrefs.isDarkModeEnabled(this)
        switchDarkMode.setOnCheckedChangeListener { _, isChecked ->
            UiModePrefs.setDarkModeEnabled(this, isChecked)
            recreate()
        }

        val whitelistedSet = loadWhitelistedApps()
        val installedApps = getInstalledLaunchableApps(whitelistedSet)

        appListAdapter = AppListAdapter(installedApps) { packageName, isEnabled ->
            toggleWhitelistedApp(packageName, isEnabled)
        }

        rvInstalledApps.layoutManager = LinearLayoutManager(this)
        rvInstalledApps.adapter = appListAdapter

        etSearchApps.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                appListAdapter.filter(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        btnTabActions.setOnClickListener {
            layoutActionsTab.visibility = View.VISIBLE
            layoutWhitelistTab.visibility = View.GONE
            btnTabActions.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.kiosk_accent))
            btnTabActions.setTextColor(getColor(R.color.white))
            btnTabWhitelist.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.admin_button_bg))
            btnTabWhitelist.setTextColor(getColor(R.color.admin_button_text))
        }

        btnTabWhitelist.setOnClickListener {
            layoutActionsTab.visibility = View.GONE
            layoutWhitelistTab.visibility = View.VISIBLE
            btnTabActions.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.admin_button_bg))
            btnTabActions.setTextColor(getColor(R.color.admin_button_text))
            btnTabWhitelist.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.kiosk_accent))
            btnTabWhitelist.setTextColor(getColor(R.color.white))
        }
        btnTabActions.performClick()

        btnSetDefaultLauncher.setOnClickListener { setAsDefaultLauncher() }
        btnClearDefaultLauncher.setOnClickListener { clearDefaultLauncher() }

        btnRelockKiosk.setOnClickListener {
            if (dpm.isDeviceOwnerApp(packageName)) {
                try {
                    dpm.setLockTaskPackages(adminComponent, KioskInstallHelper.buildAllowedPackages(this))
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

        btnOpenSettings.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (e: Exception) {
                Toast.makeText(this, "Cannot open settings: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }

        btnUpdateApp.setOnClickListener { openUpdateScreenWithPassword() }
        btnDisableKioskCompletely.setOnClickListener { disableKioskCompletely() }
        btnReturnToKiosk.setOnClickListener { returnToKiosk() }
    }

    private fun getInstalledLaunchableApps(whitelistedSet: Set<String>): List<InstalledAppInfo> {
        val pm = packageManager
        @Suppress("DEPRECATION")
        return pm.getInstalledApplications(0)
            .asSequence()
            .filter { it.packageName != packageName }
            .mapNotNull { appInfo ->
                val pkgName = appInfo.packageName
                val launchIntent = pm.getLaunchIntentForPackage(pkgName) ?: return@mapNotNull null
                launchIntent.resolveActivityInfo(pm, 0) ?: return@mapNotNull null

                InstalledAppInfo(
                    packageName = pkgName,
                    appName = pm.getApplicationLabel(appInfo).toString(),
                    icon = pm.getApplicationIcon(appInfo),
                    isWhitelisted = whitelistedSet.contains(pkgName)
                )
            }
            .distinctBy { it.packageName }
            .toList()
            .sortedWith(compareByDescending<InstalledAppInfo> { it.isWhitelisted }.thenBy { it.appName.lowercase() })
    }

    private fun loadWhitelistedApps(): Set<String> {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_WHITELISTED_APPS, emptySet())?.toSet() ?: emptySet()
    }

    private fun toggleWhitelistedApp(pkg: String, isEnabled: Boolean) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentSet = prefs.getStringSet(KEY_WHITELISTED_APPS, emptySet())?.toMutableSet() ?: mutableSetOf()

        if (isEnabled) currentSet.add(pkg) else currentSet.remove(pkg)
        prefs.edit().putStringSet(KEY_WHITELISTED_APPS, currentSet).apply()

        if (dpm.isDeviceOwnerApp(packageName)) {
            try {
                dpm.setLockTaskPackages(adminComponent, KioskInstallHelper.buildAllowedPackages(this))
            } catch (e: Exception) {
                Toast.makeText(this, "Whitelist updated, but kiosk allow-list failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun setAsDefaultLauncher() {
        if (!dpm.isDeviceOwnerApp(packageName)) {
            Toast.makeText(this, "Not Device Owner", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val intentFilter = IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            val activity = ComponentName(packageName, SplashActivity::class.java.name)

            dpm.clearPackagePersistentPreferredActivities(adminComponent, packageName)
            dpm.addPersistentPreferredActivity(adminComponent, intentFilter, activity)

            Toast.makeText(this, getString(R.string.default_launcher_set), Toast.LENGTH_SHORT).show()
            updateLauncherStatus()
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun clearDefaultLauncher() {
        if (!dpm.isDeviceOwnerApp(packageName)) {
            Toast.makeText(this, "Not Device Owner", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            dpm.clearPackagePersistentPreferredActivities(adminComponent, packageName)
            Toast.makeText(this, getString(R.string.default_launcher_cleared), Toast.LENGTH_SHORT).show()
            updateLauncherStatus()
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun updateLauncherStatus() {
        val tvLauncherStatus = findViewById<TextView>(R.id.tvLauncherStatus)
        val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) }

        @Suppress("DEPRECATION")
        val resolveInfo = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        val currentLauncher = resolveInfo?.activityInfo?.packageName

        if (currentLauncher == packageName) {
            tvLauncherStatus.text = getString(R.string.default_launcher_active)
            tvLauncherStatus.setTextColor(getColor(R.color.kiosk_status_active))
        } else {
            tvLauncherStatus.text = getString(R.string.default_launcher_inactive, currentLauncher ?: "Unknown")
            tvLauncherStatus.setTextColor(getColor(R.color.admin_danger))
        }
    }

    private fun openUpdateScreenWithPassword() {
        val passwordInput = EditText(this).apply {
            hint = getString(R.string.update_password_prompt_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding(48, 32, 48, 32)
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_password_title))
            .setMessage(getString(R.string.update_password_message))
            .setView(passwordInput)
            .setPositiveButton(getString(R.string.btn_continue)) { _, _ ->
                if (passwordInput.text.toString() != ADMIN_PASSWORD) {
                    Toast.makeText(this, getString(R.string.admin_password_incorrect), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                startActivity(Intent(this, UpdateAppActivity::class.java))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun disableKioskCompletely() {
        if (!dpm.isDeviceOwnerApp(packageName)) {
            Toast.makeText(this, "This app is not the device owner", Toast.LENGTH_SHORT).show()
            return
        }

        val passwordInput = EditText(this).apply {
            hint = getString(R.string.admin_password_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding(48, 32, 48, 32)
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.confirm_admin_password_title))
            .setView(passwordInput)
            .setPositiveButton(getString(R.string.btn_continue)) { _, _ ->
                if (passwordInput.text.toString() != ADMIN_PASSWORD) {
                    Toast.makeText(this, getString(R.string.admin_password_incorrect), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.disable_kiosk_completely_title))
                    .setMessage(getString(R.string.disable_kiosk_completely_message))
                    .setPositiveButton(getString(R.string.disable_kiosk_completely_confirm)) { _, _ ->
                        try {
                            try {
                                stopLockTask()
                            } catch (_: Exception) {
                            }

                            try {
                                dpm.setPackagesSuspended(
                                    adminComponent,
                                    arrayOf(
                                        "com.android.settings",
                                        "com.miui.home",
                                        "com.miui.securitycenter",
                                        "com.google.android.gms",
                                        "com.android.vending",
                                        "com.miui.gallery",
                                        "com.mi.android.globalFileexplorer"
                                    ),
                                    false
                                )
                            } catch (_: Exception) {
                            }

                            listOf(
                                UserManager.DISALLOW_FACTORY_RESET,
                                UserManager.DISALLOW_ADD_USER,
                                UserManager.DISALLOW_SAFE_BOOT,
                                UserManager.DISALLOW_CONFIG_WIFI,
                                UserManager.DISALLOW_CONFIG_BLUETOOTH,
                                UserManager.DISALLOW_USB_FILE_TRANSFER,
                                UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
                                RESTRICTION_DISALLOW_REMOVE_DEVICE_ADMIN
                            ).forEach { restriction ->
                                try {
                                    dpm.clearUserRestriction(adminComponent, restriction)
                                } catch (_: Exception) {
                                }
                            }

                            try {
                                dpm.setLockTaskPackages(adminComponent, arrayOf())
                            } catch (_: Exception) {
                            }

                            dpm.clearDeviceOwnerApp(packageName)
                            Toast.makeText(this, getString(R.string.kiosk_disabled_completely), Toast.LENGTH_LONG).show()
                            finish()
                        } catch (e: Exception) {
                            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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

    private fun applyBottomSystemInset(view: View) {
        val basePaddingBottom = view.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bottomInset = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, basePaddingBottom + bottomInset)
            insets
        }
        ViewCompat.requestApplyInsets(view)
    }
}
