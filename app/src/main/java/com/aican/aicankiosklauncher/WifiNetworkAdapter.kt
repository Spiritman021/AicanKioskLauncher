package com.aican.aicankiosklauncher

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView

data class WifiNetworkItem(
    val ssid: String,
    val capabilities: String,
    val level: Int,
    val isSaved: Boolean = false,
    val isConnected: Boolean = false
)

class WifiNetworkAdapter(
    private var items: List<WifiNetworkItem>,
    private val onConnectClick: (WifiNetworkItem) -> Unit
) : RecyclerView.Adapter<WifiNetworkAdapter.WifiViewHolder>() {

    inner class WifiViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvWifiName: TextView = itemView.findViewById(R.id.tvWifiName)
        val tvWifiMeta: TextView = itemView.findViewById(R.id.tvWifiMeta)
        val ivLock: ImageView = itemView.findViewById(R.id.ivWifiLock)
        val ivChevron: ImageView = itemView.findViewById(R.id.ivWifiChevron)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WifiViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_wifi_network, parent, false)
        return WifiViewHolder(view)
    }

    override fun onBindViewHolder(holder: WifiViewHolder, position: Int) {
        val item = items[position]
        holder.tvWifiName.text = item.ssid
        holder.tvWifiMeta.text = buildMetaText(item)
        holder.ivLock.isVisible = item.capabilities.equals("Secured", ignoreCase = true)
        holder.ivChevron.alpha = if (item.isConnected) 1f else 0.55f
        holder.itemView.setOnClickListener { onConnectClick(item) }
    }

    override fun getItemCount(): Int = items.size

    fun updateData(newItems: List<WifiNetworkItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    private fun buildMetaText(item: WifiNetworkItem): String {
        val status = when {
            item.isConnected -> "Connected"
            item.isSaved -> "Saved"
            else -> "Tap to connect"
        }
        return "$status • ${item.level} dBm • ${item.capabilities}"
    }
}
