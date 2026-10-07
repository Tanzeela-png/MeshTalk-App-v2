package com.example.meshtalk

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

// Simple holder for a row from the new `devices` table.
data class DeviceRecord(
    val deviceId: String,
    val nickname: String,
    val ipAddress: String,
    val lastSeen: Long
)

class DatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "meshtalk_final_db.db"
        // VERSION BUMPED 2 -> 3 so onUpgrade runs and creates the new table.
        private const val DATABASE_VERSION = 3

        private const val TABLE_MESSAGES = "messages"
        private const val KEY_ID = "id"
        private const val KEY_ROOM_ID = "roomId"
        private const val KEY_SENDER_ID = "senderId"
        private const val KEY_MESSAGE_TEXT = "messageText"
        private const val KEY_TIMESTAMP = "timestamp"
        private const val KEY_STATUS = "status"

        private const val TABLE_PARTNERS = "partners"
        private const val KEY_PARTNER_NAME = "partnerName"

        // ---------------- NEW: devices table ----------------
        private const val TABLE_DEVICES = "devices"
        private const val KEY_DEVICE_ID = "deviceId"
        private const val KEY_DEVICE_NICKNAME = "nickname"
        private const val KEY_DEVICE_IP = "ipAddress"
        private const val KEY_DEVICE_LAST_SEEN = "lastSeen"
    }

    override fun onCreate(db: SQLiteDatabase?) {
        val createMessageTable = ("CREATE TABLE " + TABLE_MESSAGES + "("
                + KEY_ID + " TEXT PRIMARY KEY,"
                + KEY_ROOM_ID + " TEXT,"
                + KEY_SENDER_ID + " TEXT,"
                + KEY_MESSAGE_TEXT + " TEXT,"
                + KEY_TIMESTAMP + " INTEGER,"
                + KEY_STATUS + " TEXT" + ")")
        db?.execSQL(createMessageTable)

        val createPartnersTable = ("CREATE TABLE " + TABLE_PARTNERS + "("
                + KEY_PARTNER_NAME + " TEXT PRIMARY KEY" + ")")
        db?.execSQL(createPartnersTable)

        // ---------------- NEW: devices table ----------------
        // deviceId (permanent UUID from DeviceIdentity.getMyId) is the PRIMARY KEY.
        // This is what lets a nickname change UPDATE the same row instead of
        // creating a new device / new chat.
        val createDevicesTable = ("CREATE TABLE " + TABLE_DEVICES + "("
                + KEY_DEVICE_ID + " TEXT PRIMARY KEY,"
                + KEY_DEVICE_NICKNAME + " TEXT,"
                + KEY_DEVICE_IP + " TEXT,"
                + KEY_DEVICE_LAST_SEEN + " INTEGER" + ")")
        db?.execSQL(createDevicesTable)
    }

    override fun onUpgrade(db: SQLiteDatabase?, oldVersion: Int, newVersion: Int) {
        db?.execSQL("DROP TABLE IF EXISTS $TABLE_MESSAGES")
        db?.execSQL("DROP TABLE IF EXISTS $TABLE_PARTNERS")
        db?.execSQL("DROP TABLE IF EXISTS $TABLE_DEVICES")
        onCreate(db)
    }

    fun insertMessage(message: ChatMessage, roomId: String) {
        val db = this.writableDatabase
        val values = ContentValues().apply {
            put(KEY_ID, message.id)
            put(KEY_ROOM_ID, roomId)
            put(KEY_SENDER_ID, message.senderId)
            put(KEY_MESSAGE_TEXT, message.messageText)
            put(KEY_TIMESTAMP, message.timestamp)
            put(KEY_STATUS, message.status)
        }
        db.insertWithOnConflict(TABLE_MESSAGES, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        db.close()
    }

    fun getChatHistory(roomId: String): List<ChatMessage> {
        val messageList = mutableListOf<ChatMessage>()
        val db = this.readableDatabase
        val cursor = db.rawQuery("SELECT * FROM $TABLE_MESSAGES WHERE $KEY_ROOM_ID = ? ORDER BY $KEY_TIMESTAMP ASC", arrayOf(roomId))
        if (cursor.moveToFirst()) {
            do {
                val message = ChatMessage(
                    id = cursor.getString(cursor.getColumnIndexOrThrow(KEY_ID)),
                    senderId = cursor.getString(cursor.getColumnIndexOrThrow(KEY_SENDER_ID)),
                    messageText = cursor.getString(cursor.getColumnIndexOrThrow(KEY_MESSAGE_TEXT)),
                    timestamp = cursor.getLong(cursor.getColumnIndexOrThrow(KEY_TIMESTAMP)),
                    status = cursor.getString(cursor.getColumnIndexOrThrow(KEY_STATUS))
                )
                messageList.add(message)
            } while (cursor.moveToNext())
        }
        cursor.close()
        db.close()
        return messageList
    }

    fun insertPartner(partnerName: String) {
        val cleanName = partnerName.trim()
        if (cleanName.isEmpty() || cleanName == "Mesh Device" || cleanName == "MeshTalk") {
            return
        }

        val db = this.writableDatabase
        val values = ContentValues().apply {
            put(KEY_PARTNER_NAME, cleanName)
        }
        db.insertWithOnConflict(TABLE_PARTNERS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        db.close()
    }

    fun getAllPartners(): List<String> {
        val partnerList = mutableListOf<String>()
        val db = this.readableDatabase
        val cursor = db.rawQuery("SELECT $KEY_PARTNER_NAME FROM $TABLE_PARTNERS", null)
        if (cursor.moveToFirst()) {
            do {
                val name = cursor.getString(cursor.getColumnIndexOrThrow(KEY_PARTNER_NAME))
                if (name.isNotBlank() && name != "Mesh Device" && name != "MeshTalk") {
                    partnerList.add(name)
                }
            } while (cursor.moveToNext())
        }
        cursor.close()
        db.close()
        return partnerList
    }

    fun updateMessageText(msgId: String, newText: String) {
        val db = this.writableDatabase
        val values = ContentValues().apply { put(KEY_MESSAGE_TEXT, newText) }
        db.update(TABLE_MESSAGES, values, "$KEY_ID = ?", arrayOf(msgId))
        db.close()
    }

    fun updateMessageStatus(msgId: String, newStatus: String) {
        val db = this.writableDatabase
        val values = ContentValues().apply { put(KEY_STATUS, newStatus) }
        db.update(TABLE_MESSAGES, values, "$KEY_ID = ?", arrayOf(msgId))
        db.close()
    }

    fun clearChatHistory(roomId: String) {
        val db = this.writableDatabase
        db.delete(TABLE_MESSAGES, "$KEY_ROOM_ID = ?", arrayOf(roomId))
        db.close()
    }

    // ==================== NEW: devices table methods ====================

    /**
     * Call this whenever CMD_HELLO is received from a peer.
     * Same deviceId -> same row gets UPDATED (nickname/ip/lastSeen refresh).
     * This is the core fix: a nickname change never creates a new device.
     */
    fun upsertDevice(deviceId: String, nickname: String, ipAddress: String) {
        val cleanId = deviceId.trim()
        if (cleanId.isEmpty()) return

        val db = this.writableDatabase
        val values = ContentValues().apply {
            put(KEY_DEVICE_ID, cleanId)
            put(KEY_DEVICE_NICKNAME, nickname.trim())
            put(KEY_DEVICE_IP, ipAddress.trim())
            put(KEY_DEVICE_LAST_SEEN, System.currentTimeMillis())
        }
        db.insertWithOnConflict(TABLE_DEVICES, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        db.close()
    }

    /** Always returns the CURRENT nickname — use this for chat header / list, never a cached value. */
    fun getNicknameForDevice(deviceId: String): String? {
        val db = this.readableDatabase
        val cursor = db.rawQuery(
            "SELECT $KEY_DEVICE_NICKNAME FROM $TABLE_DEVICES WHERE $KEY_DEVICE_ID = ?",
            arrayOf(deviceId)
        )
        var nickname: String? = null
        if (cursor.moveToFirst()) {
            nickname = cursor.getString(cursor.getColumnIndexOrThrow(KEY_DEVICE_NICKNAME))
        }
        cursor.close()
        db.close()
        return nickname
    }

    fun getDevice(deviceId: String): DeviceRecord? {
        val db = this.readableDatabase
        val cursor = db.rawQuery("SELECT * FROM $TABLE_DEVICES WHERE $KEY_DEVICE_ID = ?", arrayOf(deviceId))
        var record: DeviceRecord? = null
        if (cursor.moveToFirst()) {
            record = DeviceRecord(
                deviceId = cursor.getString(cursor.getColumnIndexOrThrow(KEY_DEVICE_ID)),
                nickname = cursor.getString(cursor.getColumnIndexOrThrow(KEY_DEVICE_NICKNAME)),
                ipAddress = cursor.getString(cursor.getColumnIndexOrThrow(KEY_DEVICE_IP)),
                lastSeen = cursor.getLong(cursor.getColumnIndexOrThrow(KEY_DEVICE_LAST_SEEN))
            )
        }
        cursor.close()
        db.close()
        return record
    }

    fun getAllDevices(): List<DeviceRecord> {
        val list = mutableListOf<DeviceRecord>()
        val db = this.readableDatabase
        val cursor = db.rawQuery("SELECT * FROM $TABLE_DEVICES ORDER BY $KEY_DEVICE_LAST_SEEN DESC", null)
        if (cursor.moveToFirst()) {
            do {
                list.add(
                    DeviceRecord(
                        deviceId = cursor.getString(cursor.getColumnIndexOrThrow(KEY_DEVICE_ID)),
                        nickname = cursor.getString(cursor.getColumnIndexOrThrow(KEY_DEVICE_NICKNAME)),
                        ipAddress = cursor.getString(cursor.getColumnIndexOrThrow(KEY_DEVICE_IP)),
                        lastSeen = cursor.getLong(cursor.getColumnIndexOrThrow(KEY_DEVICE_LAST_SEEN))
                    )
                )
            } while (cursor.moveToNext())
        }
        cursor.close()
        db.close()
        return list
    }
}