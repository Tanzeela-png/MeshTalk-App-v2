package com.example.meshtalk

import android.content.Context
import java.util.UUID

/**
 * Handles this device's permanent Unique ID and nickname.
 *
 * Device ID:
 * - Generated once
 * - Stored locally
 * - Does not depend on IP
 * - Does not depend on WiFi Direct MAC
 * - Does not depend on nickname
 *
 * Nickname:
 * - User can change it anytime
 * - Changing nickname does NOT change Device ID
 */
object DeviceIdentity {

    private const val PREFS_NAME = "MeshTalkPrefs"

    private const val KEY_DEVICE_ID = "MY_DEVICE_ID"
    private const val KEY_NICKNAME = "USER_NICKNAME"

    /**
     * Returns permanent device ID.
     *
     * A UUID is generated only on first installation/run.
     */
    fun getMyId(context: Context): String {

        val prefs = context.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )

        var id = prefs.getString(
            KEY_DEVICE_ID,
            null
        )

        if (id.isNullOrBlank()) {

            id = UUID.randomUUID().toString()

            prefs.edit()
                .putString(KEY_DEVICE_ID, id)
                .apply()
        }

        return id
    }

    /**
     * Returns user's nickname.
     *
     * If no nickname has been saved,
     * phone model is used as fallback.
     */
    fun getMyNickname(context: Context): String {

        val prefs = context.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )

        return prefs
            .getString(KEY_NICKNAME, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: android.os.Build.MODEL
    }

    /**
     * Saves user's nickname.
     *
     * Nickname change never changes Device ID.
     */
    fun setMyNickname(
        context: Context,
        nickname: String
    ) {

        val cleanNickname = nickname.trim()

        if (cleanNickname.isEmpty()) {
            return
        }

        context.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )
            .edit()
            .putString(
                KEY_NICKNAME,
                cleanNickname
            )
            .apply()
    }
}