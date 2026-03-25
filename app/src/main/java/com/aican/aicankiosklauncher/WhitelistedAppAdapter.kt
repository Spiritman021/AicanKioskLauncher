package com.aican.aicankiosklauncher

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * RecyclerView adapter for the whitelisted apps grid on the kiosk home screen.
 * Each cell shows the app icon and label. Clicking launches the app.
 */
class WhitelistedAppAdapter(
    private var apps: List<InstalledAppInfo>,
    private val onAppClick: (packageName: String) -> Unit
) : RecyclerView.Adapter<WhitelistedAppAdapter.AppViewHolder>() {

    inner class AppViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val ivIcon: ImageView = itemView.findViewById(R.id.ivWhitelistedAppIcon)
        val tvLabel: TextView = itemView.findViewById(R.id.tvWhitelistedAppName)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_whitelisted_app, parent, false)
        return AppViewHolder(view)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        val app = apps[position]
        holder.ivIcon.setImageDrawable(app.icon)
        holder.tvLabel.text = app.appName

        holder.itemView.setOnClickListener {
            onAppClick(app.packageName)
        }
    }

    override fun getItemCount(): Int = apps.size

    fun updateData(newApps: List<InstalledAppInfo>) {
        apps = newApps
        notifyDataSetChanged()
    }
}
