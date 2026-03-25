package com.aican.aicankiosklauncher

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * RecyclerView adapter for the installed apps list in AdminActivity.
 * Shows each app with its icon, name, package name, and a toggle switch.
 */
class AppListAdapter(
    private var apps: List<InstalledAppInfo>,
    private val onToggle: (packageName: String, isEnabled: Boolean) -> Unit
) : RecyclerView.Adapter<AppListAdapter.AppViewHolder>() {

    private var filteredApps: List<InstalledAppInfo> = apps

    inner class AppViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val ivAppIcon: ImageView = itemView.findViewById(R.id.ivAppIcon)
        val tvAppName: TextView = itemView.findViewById(R.id.tvAppName)
        val tvPackageName: TextView = itemView.findViewById(R.id.tvPackageName)
        val switchWhitelist: MaterialSwitch = itemView.findViewById(R.id.switchWhitelist)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app, parent, false)
        return AppViewHolder(view)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        val app = filteredApps[position]

        holder.ivAppIcon.setImageDrawable(app.icon)
        holder.tvAppName.text = app.appName
        holder.tvPackageName.text = app.packageName

        // Remove listener before setting checked to avoid triggering callback
        holder.switchWhitelist.setOnCheckedChangeListener(null)
        holder.switchWhitelist.isChecked = app.isWhitelisted

        holder.switchWhitelist.setOnCheckedChangeListener { _, isChecked ->
            app.isWhitelisted = isChecked
            onToggle(app.packageName, isChecked)
        }
    }

    override fun getItemCount(): Int = filteredApps.size

    /**
     * Filter the app list by query string (matches app name or package name).
     */
    fun filter(query: String) {
        filteredApps = if (query.isBlank()) {
            apps
        } else {
            apps.filter {
                it.appName.contains(query, ignoreCase = true) ||
                        it.packageName.contains(query, ignoreCase = true)
            }
        }
        notifyDataSetChanged()
    }

    /**
     * Update the full data set (e.g. after reloading from PackageManager).
     */
    fun updateData(newApps: List<InstalledAppInfo>) {
        apps = newApps
        filteredApps = newApps
        notifyDataSetChanged()
    }
}
