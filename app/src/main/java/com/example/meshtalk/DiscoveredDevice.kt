package com.example.meshtalk

/** One row from the discovered_devices table. */
data class DiscoveredDevice(
    val id: String,          // permanent unique id (from CMD_HELLO)
    val nickname: String,    // last known nickname
    val ipAddress: String,   // last known IP (changes across sessions - NOT used as a key)
    val macAddress: String,  // WiFi Direct MAC (device.deviceAddress) - stable pre-connect too
    val lastSeen: Long
)

/** Simple id+name pair used for the offline "partners" history list. */
data class PartnerInfo(
    val id: String,
    val name: String
)