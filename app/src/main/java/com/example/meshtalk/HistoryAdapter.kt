package com.example.meshtalk

import android.content.Context
import android.graphics.BitmapFactory
import android.net.wifi.p2p.WifiP2pDevice
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.io.File

class HistoryAdapter(
    private val deviceList: List<WifiP2pDevice>,
    private val onDeviceClick: (WifiP2pDevice) -> Unit
) : RecyclerView.Adapter<HistoryAdapter.HistoryViewHolder>() {

    class HistoryViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivProfile: ImageView = view.findViewById(R.id.ivProfile)
        val tvDeviceTitle: TextView = view.findViewById(R.id.tvDeviceTitle)
        val tvDeviceSubtitle: TextView = view.findViewById(R.id.tvDeviceSubtitle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_device_row, parent, false)
        return HistoryViewHolder(view)
    }

    override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) {
        val device = deviceList[position]

        holder.tvDeviceTitle.text = device.deviceName
        holder.tvDeviceSubtitle.text = "Tap to view chat history"

        val context = holder.itemView.context
        val prefs = context.getSharedPreferences("MeshTalkPrefs", Context.MODE_PRIVATE)

        // Partner/Device key se specific picture path uthayein
        val partnerImagePath = prefs.getString("PROFILE_IMAGE_${device.deviceName}", "")

        if (!partnerImagePath.isNullOrEmpty() && File(partnerImagePath).exists()) {
            val bitmap = BitmapFactory.decodeFile(partnerImagePath)
            holder.ivProfile.setImageBitmap(bitmap)
        } else {
            // Default Fallback Icon
            holder.ivProfile.setImageResource(android.R.drawable.stat_notify_chat)
        }

        holder.itemView.setOnClickListener {
            onDeviceClick(device)
        }
    }

    override fun getItemCount(): Int = deviceList.size
}