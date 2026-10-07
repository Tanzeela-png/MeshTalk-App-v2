package com.example.meshtalk

import android.net.wifi.p2p.WifiP2pDevice
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

// Data Class for Discovered Peer
data class DiscoveredPeerItem(
    val device: WifiP2pDevice,
    val nickname: String,
    val uniqueId: String
)

class DeviceAdapter(
    private val deviceList: List<DiscoveredPeerItem>,
    private val onItemClick: (DiscoveredPeerItem) -> Unit
) : RecyclerView.Adapter<DeviceAdapter.DeviceViewHolder>() {

    class DeviceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvDeviceTitle: TextView = itemView.findViewById(R.id.tvDeviceTitle)
        val tvDeviceSubtitle: TextView = itemView.findViewById(R.id.tvDeviceSubtitle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_device_row, parent, false)
        return DeviceViewHolder(view)
    }

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        val item = deviceList[position]

        // Displays ONLY the Nickname
        holder.tvDeviceTitle.text = item.nickname

        // Hide subtitle line completely
        holder.tvDeviceSubtitle.visibility = View.GONE

        holder.itemView.setOnClickListener {
            onItemClick(item)
        }
    }

    override fun getItemCount(): Int = deviceList.size
}