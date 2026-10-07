package com.example.meshtalk

/**
 * Creates one stable chat room for exactly two devices.
 *
 * The room is based ONLY on permanent device IDs.
 *
 * Example:
 *
 * Device A = AAA
 * Device B = BBB
 *
 * Both devices will calculate:
 * AAA_BBB
 *
 * This means:
 * - IP change does not create a new chat
 * - nickname change does not create a new chat
 * - WiFi Direct MAC change does not create a new chat
 * - connection direction does not matter
 */
object ChatRoomUtil {

    fun generateChatRoomId(
        myId: String,
        remoteId: String
    ): String {

        val first = myId.trim()
        val second = remoteId.trim()

        require(first.isNotEmpty()) {
            "myId cannot be empty"
        }

        require(second.isNotEmpty()) {
            "remoteId cannot be empty"
        }

        return if (first < second) {
            "${first}_$second"
        } else {
            "${second}_$first"
        }
    }

    /**
     * Temporary room used only until CMD_HELLO gives us
     * the permanent device ID of the other device.
     */
    fun generatePendingRoomId(
        partnerName: String
    ): String {

        val cleanName = partnerName
            .trim()
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')

        return "pending_$cleanName"
    }
}