package com.aican.aicankiosklauncher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.app.admin.DevicePolicyManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class UpdateAppActivity : AppCompatActivity() {

    companion object {
        private const val APP_META_URL = "https://cspl-labs.vercel.app/launcher-app-meta.json"
    }

    private lateinit var tvUpdateStatus: TextView
    private lateinit var tvUpdateAppName: TextView
    private lateinit var tvUpdateCompany: TextView
    private lateinit var tvInstalledVersion: TextView
    private lateinit var tvRemoteVersion: TextView
    private lateinit var tvReleaseDate: TextView
    private lateinit var tvApkSize: TextView
    private lateinit var btnRefreshUpdate: Button
    private lateinit var btnUpdateNow: Button
    private lateinit var dpm: DevicePolicyManager
    private lateinit var adminComponent: ComponentName

    private var remoteMetadata: UpdateMetadata? = null
    private var downloadedApkFile: File? = null
    private var waitingForInstallPermission = false
    private var installWindowOpened = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_update_app)

        dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, MyDeviceAdminReceiver::class.java)

        tvUpdateStatus = findViewById(R.id.tvUpdateStatus)
        tvUpdateAppName = findViewById(R.id.tvUpdateAppName)
        tvUpdateCompany = findViewById(R.id.tvUpdateCompany)
        tvInstalledVersion = findViewById(R.id.tvInstalledVersion)
        tvRemoteVersion = findViewById(R.id.tvRemoteVersion)
        tvReleaseDate = findViewById(R.id.tvReleaseDate)
        tvApkSize = findViewById(R.id.tvApkSize)
        btnRefreshUpdate = findViewById(R.id.btnRefreshUpdate)
        btnUpdateNow = findViewById(R.id.btnUpdateNow)
        val bottomActionBar = findViewById<View>(R.id.bottomActionBarUpdate)
        applyBottomSystemInset(bottomActionBar)

        tvInstalledVersion.text = getInstalledVersionName()
        fillPlaceholderMetadata()

        btnUpdateNow.setOnClickListener {
            val metadata = remoteMetadata
            if (metadata == null) {
                loadUpdateMetadata()
                return@setOnClickListener
            }
            if (metadata.updateUrl.isBlank()) {
                Toast.makeText(this, getString(R.string.update_missing_url), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            downloadAndInstallApk(metadata)
        }
        btnRefreshUpdate.setOnClickListener { loadUpdateMetadata() }

        loadUpdateMetadata()
    }

    override fun onResume() {
        super.onResume()
        if (waitingForInstallPermission && packageManager.canRequestPackageInstalls()) {
            waitingForInstallPermission = false
            downloadedApkFile?.let { installDownloadedApk(it) }
        }
        if (installWindowOpened && !waitingForInstallPermission) {
            KioskInstallHelper.restoreKioskWindow(this, dpm, adminComponent)
            installWindowOpened = false
        }
    }

    private fun fillPlaceholderMetadata() {
        val unknown = getString(R.string.update_value_unknown)
        tvUpdateAppName.text = unknown
        tvUpdateCompany.text = unknown
        tvRemoteVersion.text = unknown
        tvReleaseDate.text = unknown
        tvApkSize.text = unknown
    }

    private fun loadUpdateMetadata() {
        if (!isInternetAvailable()) {
            renderUpdateError(getString(R.string.update_status_offline))
            return
        }

        tvUpdateStatus.text = getString(R.string.update_status_loading)
        btnUpdateNow.isEnabled = false
        btnUpdateNow.text = getString(R.string.update_button_checking)

        Thread {
            try {
                val connection = (URL(APP_META_URL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 15000
                    requestMethod = "GET"
                }

                val responseCode = connection.responseCode
                if (responseCode !in 200..299) {
                    throw IOException("HTTP $responseCode")
                }

                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val metadata = parseUpdateMetadata(body)

                runOnUiThread {
                    remoteMetadata = metadata
                    renderMetadata(metadata)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    renderUpdateError(getFriendlyUpdateError(e))
                }
            }
        }.start()
    }

    private fun renderMetadata(metadata: UpdateMetadata) {
        tvUpdateAppName.text = metadata.appName
        tvUpdateCompany.text = metadata.company
        tvRemoteVersion.text = metadata.version
        tvReleaseDate.text = metadata.releaseDate
        tvApkSize.text = metadata.apkSize

        val comparison = compareVersions(metadata.version, getInstalledVersionName())
        if (comparison > 0) {
            tvUpdateStatus.text = getString(R.string.update_status_available)
            btnUpdateNow.isEnabled = true
            btnUpdateNow.text = getString(R.string.update_button_download)
        } else {
            tvUpdateStatus.text = getString(R.string.update_status_latest)
            btnUpdateNow.isEnabled = false
            btnUpdateNow.text = getString(R.string.update_button_latest)
        }
    }

    private fun renderUpdateError(message: String) {
        remoteMetadata = null
        tvUpdateStatus.text = message
        btnUpdateNow.isEnabled = true
        btnUpdateNow.text = getString(R.string.update_button_retry)
    }

    private fun parseUpdateMetadata(json: String): UpdateMetadata {
        val root = JSONObject(json)
        val app = root.getJSONObject("app")
        val downloads = root.getJSONObject("downloads")

        val version = app.optString("version")
        val updateUrl = downloads.optString("updateUrl")

        if (version.isBlank() || updateUrl.isBlank()) {
            throw IOException(getString(R.string.update_metadata_failed))
        }

        return UpdateMetadata(
            appName = app.optString("name", getString(R.string.update_value_unknown)),
            company = app.optString("company", getString(R.string.update_value_unknown)),
            version = version,
            releaseDate = app.optString("releaseDate", getString(R.string.update_value_unknown)),
            updateUrl = updateUrl,
            apkSize = downloads.optString("apkSize", getString(R.string.update_value_unknown))
        )
    }

    private fun downloadAndInstallApk(metadata: UpdateMetadata) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_update_download_progress, null)
        val progressBar = dialogView.findViewById<ProgressBar>(R.id.progressDownload)
        val tvProgress = dialogView.findViewById<TextView>(R.id.tvDownloadProgress)

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_download_title))
            .setView(dialogView)
            .setCancelable(false)
            .create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()

        btnUpdateNow.isEnabled = false

        Thread {
            try {
                val downloadsDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    ?: throw IOException("Download storage unavailable")
                val targetFile = File(downloadsDir, "launcher-update.apk")

                val connection = (URL(metadata.updateUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20000
                    readTimeout = 20000
                    requestMethod = "GET"
                    instanceFollowRedirects = true
                }

                val responseCode = connection.responseCode
                if (responseCode !in 200..299) {
                    throw IOException("HTTP $responseCode")
                }

                val contentType = connection.contentType.orEmpty()
                if (contentType.contains("text/html", ignoreCase = true)) {
                    throw IOException(getString(R.string.update_download_failed))
                }

                val contentLength = connection.contentLength
                runOnUiThread {
                    progressBar.isIndeterminate = contentLength <= 0
                }

                connection.inputStream.use { input ->
                    FileOutputStream(targetFile).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var totalRead = 0L
                        while (true) {
                            val bytesRead = input.read(buffer)
                            if (bytesRead == -1) break
                            output.write(buffer, 0, bytesRead)
                            totalRead += bytesRead

                            if (contentLength > 0) {
                                val percent = ((totalRead * 100) / contentLength).toInt().coerceIn(0, 100)
                                runOnUiThread {
                                    progressBar.progress = percent
                                    tvProgress.text = getString(R.string.update_download_progress, percent)
                                }
                            }
                        }
                        output.flush()
                    }
                }

                downloadedApkFile = targetFile
                runOnUiThread {
                    dialog.dismiss()
                    btnUpdateNow.isEnabled = true
                    if (packageManager.canRequestPackageInstalls()) {
                        installDownloadedApk(targetFile)
                    } else {
                        waitingForInstallPermission = true
                        Toast.makeText(this, getString(R.string.update_install_permission_needed), Toast.LENGTH_LONG).show()
                        openUnknownSourcesSettings()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    dialog.dismiss()
                    btnUpdateNow.isEnabled = true
                    Toast.makeText(this, getFriendlyUpdateError(e), Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun openUnknownSourcesSettings() {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:$packageName")
        }
        startActivity(intent)
    }

    private fun installDownloadedApk(apkFile: File) {
        try {
            KioskInstallHelper.prepareInstallWindow(this, dpm, adminComponent)
            installWindowOpened = true

            val apkUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apkFile)
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(installIntent)
        } catch (e: Exception) {
            if (installWindowOpened) {
                KioskInstallHelper.restoreKioskWindow(this, dpm, adminComponent)
                installWindowOpened = false
            }
            Toast.makeText(this, getString(R.string.update_install_failed), Toast.LENGTH_LONG).show()
        }
    }

    private fun getInstalledVersionName(): String {
        @Suppress("DEPRECATION")
        val info = packageManager.getPackageInfo(packageName, 0)
        return info.versionName ?: getString(R.string.update_value_unknown)
    }

    private fun compareVersions(left: String, right: String): Int {
        val leftParts = left.split(".")
        val rightParts = right.split(".")
        val maxParts = maxOf(leftParts.size, rightParts.size)

        for (index in 0 until maxParts) {
            val leftValue = leftParts.getOrNull(index)?.toIntOrNull() ?: 0
            val rightValue = rightParts.getOrNull(index)?.toIntOrNull() ?: 0
            if (leftValue != rightValue) {
                return leftValue.compareTo(rightValue)
            }
        }
        return 0
    }

    private fun isInternetAvailable(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun getFriendlyUpdateError(error: Exception): String {
        val message = error.message.orEmpty()
        return when {
            message.contains("Unable to resolve host", ignoreCase = true) -> getString(R.string.update_status_offline)
            message.contains("timeout", ignoreCase = true) -> getString(R.string.update_download_failed)
            message.contains("failed to load", ignoreCase = true) -> getString(R.string.update_metadata_failed)
            else -> getString(R.string.update_download_failed)
        }
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

    data class UpdateMetadata(
        val appName: String,
        val company: String,
        val version: String,
        val releaseDate: String,
        val updateUrl: String,
        val apkSize: String
    )
}
