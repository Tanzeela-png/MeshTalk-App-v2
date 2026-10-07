package com.example.meshtalk

import android.content.Intent
import android.net.wifi.p2p.WifiP2pDevice
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class HistoryActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        // Custom toolbar from activity_history.xml
        supportActionBar?.hide()

        val toolbar = findViewById<Toolbar>(R.id.toolbar)

        // Toolbar title
        toolbar.title = "Chat History"

        // Back button
        toolbar.setNavigationIcon(android.R.drawable.ic_menu_revert)

        toolbar.setNavigationOnClickListener {
            finish()
        }

        val dbHelper = DatabaseHelper(this)
        val rv = findViewById<RecyclerView>(R.id.rvHistory)
        val tvEmpty = findViewById<TextView>(R.id.tvEmpty)

        val myName = getSharedPreferences(
            "MeshTalkPrefs",
            MODE_PRIVATE
        ).getString("USER_NICKNAME", "")

        val savedList = mutableListOf<WifiP2pDevice>()

        // Placeholder address used for legacy (pre-fix) entries — same
        // constant your original code already used.
        val legacyAddressPlaceholder = "00:00:00:00:00:00"

        // Track which nicknames are already shown via the new `devices`
        // table, so a legacy `partners` row for the SAME person (their old
        // name, pre-rename) doesn't create a visible duplicate.
        val alreadyShownNames = mutableSetOf<String>()

        // ---- NEW: known devices (permanent unique_id), latest nickname ----
        // This is what fixes today's actual bug: a renamed device now shows
        // ONE row with its current name, instead of a new row per rename.
        dbHelper.getAllDevices().forEach { record ->
            if (record.nickname.isNotBlank() && record.nickname != myName) {
                savedList.add(
                    WifiP2pDevice().apply {
                        deviceName = record.nickname
                        // Real unique_id is carried here (deviceAddress is
                        // just a free-form string field) so the click handler
                        // below can open the correct permanent chat room.
                        deviceAddress = record.deviceId
                        status = -1
                    }
                )
                alreadyShownNames.add(record.nickname)
            }
        }

        // ---- LEGACY FALLBACK: pre-update chats, unique_id not known yet ----
        // Keeps old history visible until that peer reconnects (which will
        // send CMD_HELLO and move them into the devices table above).
        dbHelper.getAllPartners().forEach { name ->
            if (name != myName && name !in alreadyShownNames) {
                savedList.add(
                    WifiP2pDevice().apply {
                        deviceName = name
                        deviceAddress = legacyAddressPlaceholder
                        status = -1
                    }
                )
            }
        }

        if (savedList.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            rv.visibility = View.GONE
        } else {
            tvEmpty.visibility = View.GONE
            rv.visibility = View.VISIBLE
        }

        rv.layoutManager = LinearLayoutManager(this)

        rv.adapter = HistoryAdapter(savedList) { selectedDevice: WifiP2pDevice ->

            val intent = Intent(this, ChatActivity::class.java).apply {

                putExtra(
                    "PEER_NAME",
                    selectedDevice.deviceName
                )

                putExtra(
                    "PEER_IP",
                    "0.0.0.0"
                )

                // NEW: only pass a real unique id for rows that came from the
                // devices table — legacy rows keep the old nickname-only flow.
                if (selectedDevice.deviceAddress != legacyAddressPlaceholder) {
                    putExtra("PEER_UNIQUE_ID", selectedDevice.deviceAddress)
                }
            }

            startActivity(intent)
        }
    }
}