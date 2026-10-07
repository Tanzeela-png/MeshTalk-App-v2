package com.example.meshtalk

data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val senderId: String,          // "Me" ya "Partner"
    var messageText: String,       // Message content
    val timestamp: Long,           // Time status
    var status: String = "SENT"    // "SENT" ya "READ"
)