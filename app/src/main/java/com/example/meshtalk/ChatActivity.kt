package com.example.meshtalk

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Bundle
import android.provider.MediaStore
import android.view.Menu
import android.view.MenuItem
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.imageview.ShapeableImageView
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread

class ChatActivity : AppCompatActivity() {

    private lateinit var toolbar: Toolbar
    private lateinit var ivToolbarProfile: ShapeableImageView
    private lateinit var tvToolbarTitle: TextView
    private lateinit var tvToolbarSubtitle: TextView

    private lateinit var etMessage: EditText
    private lateinit var btnSend: Button
    private lateinit var btnAttach: ImageButton
    private lateinit var rvChat: RecyclerView

    private lateinit var chatAdapter: ChatAdapter
    private val messageList = mutableListOf<ChatMessage>()

    private var networkEngine: ChatServerClient? = null
    private var partnerIP: String = "192.168.49.1"
    private var chatRoomId: String = "p2p_chat_room"
    private lateinit var dbHelper: DatabaseHelper
    private var currentPartnerName: String = "Mesh Device"
    private var myNickname: String = ""

    // NEW: permanent identity used for the WhatsApp-like chat room fix.
    // myUniqueId: this device's permanent id (from DeviceIdentity, same
    //             value MainActivity now broadcasts).
    // remoteUniqueId: the peer's permanent id, when we know it — either
    //             passed in from MainActivity (live scan/connect) or from
    //             HistoryActivity (already-known device), or learned mid-chat
    //             via the new CMD_HELLO handshake message below.
    private var myUniqueId: String = ""
    private var remoteUniqueId: String = ""

    private var p2pManager: WifiP2pManager? = null
    private var p2pChannel: WifiP2pManager.Channel? = null
    private var isReceiverRegistered = false
    private var isOnlineMode = true

    private val pickFileLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri: android.net.Uri? ->
        if (uri != null) processSelectedFile(uri)
    }

    private val capturePhotoLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val imageBitmap = result.data?.extras?.get("data") as? Bitmap
            if (imageBitmap != null) processCapturedCameraPhoto(imageBitmap)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        ivToolbarProfile = findViewById(R.id.ivToolbarProfile)
        tvToolbarTitle = findViewById(R.id.tvToolbarTitle)
        tvToolbarSubtitle = findViewById(R.id.tvToolbarSubtitle)

        etMessage = findViewById(R.id.etMessage)
        btnSend = findViewById(R.id.btnSend)
        btnAttach = findViewById(R.id.btnAttach)
        rvChat = findViewById(R.id.rvChat)

        dbHelper = DatabaseHelper(this)
        myNickname = getSharedPreferences("MeshTalkPrefs", MODE_PRIVATE).getString("USER_NICKNAME", "") ?: ""
        myUniqueId = DeviceIdentity.getMyId(this) // NEW

        val passedName = intent.getStringExtra("PEER_NAME")
        if (!passedName.isNullOrBlank()) {
            currentPartnerName = passedName
        }

        // NEW: read the permanent id if MainActivity/HistoryActivity passed one.
        remoteUniqueId = intent.getStringExtra("PEER_UNIQUE_ID") ?: ""

        partnerIP = intent.getStringExtra("PEER_IP") ?: "192.168.49.1"
        isOnlineMode = (partnerIP != "0.0.0.0")

        p2pManager = getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        p2pChannel = p2pManager?.initialize(this, mainLooper, null)

        setupChatRoom()
        loadPartnerProfilePictureToToolbar()

        if (isOnlineMode) {
            initSocketEngine()
            startBackgroundAutoScan()
        } else {
            tvToolbarTitle.text = currentPartnerName
            tvToolbarSubtitle.text = "Offline"
            btnSend.isEnabled = true
        }

        btnSend.setOnClickListener {
            val text = etMessage.text.toString().trim()
            if (text.isNotEmpty()) {
                val messageId = System.currentTimeMillis().toString()
                val initialStatus = if (isOnlineMode) "SENT" else "PENDING"
                val newMessage = ChatMessage(id = messageId, senderId = "Me", messageText = text, timestamp = System.currentTimeMillis(), status = initialStatus)
                dbHelper.insertMessage(newMessage, chatRoomId)
                messageList.add(newMessage)
                chatAdapter.notifyItemInserted(messageList.size - 1)
                rvChat.scrollToPosition(messageList.size - 1)
                etMessage.text.clear()
                if (isOnlineMode) {
                    thread { networkEngine?.sendMessage(partnerIP, "MSG_DATA $messageId $text", 8888) }
                }
            }
        }

        btnAttach.setOnClickListener {
            if (isOnlineMode) showAttachmentOptionsDialog()
            else Toast.makeText(this, "Disabled in offline view.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadPartnerProfilePictureToToolbar() {
        val sharedPrefs = getSharedPreferences("MeshTalkPrefs", MODE_PRIVATE)
        val partnerImagePath = sharedPrefs.getString("PROFILE_IMAGE_$currentPartnerName", "")

        if (!partnerImagePath.isNullOrEmpty() && File(partnerImagePath).exists()) {
            val bitmap = BitmapFactory.decodeFile(partnerImagePath)
            ivToolbarProfile.setImageBitmap(bitmap)
        } else {
            ivToolbarProfile.setImageResource(android.R.drawable.stat_notify_chat)
        }
    }

    private fun showAttachmentOptionsDialog() {
        val options = arrayOf("Take Photo from Camera 📸", "Choose from Gallery 📂", "Cancel")
        AlertDialog.Builder(this).setTitle("Send Image").setItems(options) { dialog, which ->
            when (which) {
                0 -> {
                    val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                    if (intent.resolveActivity(packageManager) != null) capturePhotoLauncher.launch(intent)
                }
                1 -> pickFileLauncher.launch("image/*")
                2 -> dialog.dismiss()
            }
        }.show()
    }

    private fun processCapturedCameraPhoto(bitmap: Bitmap) {
        thread {
            try {
                val outputStream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
                val base64ImageString = android.util.Base64.encodeToString(outputStream.toByteArray(), android.util.Base64.NO_WRAP)
                val imagePayloadText = "IMG_DATA:$base64ImageString"
                val messageId = System.currentTimeMillis().toString()
                val newMessage = ChatMessage(messageId, "Me", imagePayloadText, System.currentTimeMillis(), if (isOnlineMode) "SENT" else "PENDING")

                dbHelper.insertMessage(newMessage, chatRoomId)

                runOnUiThread {
                    messageList.add(newMessage)
                    chatAdapter.notifyItemInserted(messageList.size - 1)
                    rvChat.scrollToPosition(messageList.size - 1)
                }
                if (isOnlineMode) {
                    networkEngine?.sendMessage(partnerIP, "MSG_DATA $messageId $imagePayloadText", 8888)
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun processSelectedFile(uri: android.net.Uri) {
        thread {
            try {
                val inputStream = contentResolver.openInputStream(uri)
                val originalBitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()

                if (originalBitmap != null) {
                    val outputStream = ByteArrayOutputStream()
                    originalBitmap.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
                    val base64ImageString = android.util.Base64.encodeToString(outputStream.toByteArray(), android.util.Base64.NO_WRAP)
                    val imagePayloadText = "IMG_DATA:$base64ImageString"
                    val messageId = System.currentTimeMillis().toString()
                    val newMessage = ChatMessage(messageId, "Me", imagePayloadText, System.currentTimeMillis(), if (isOnlineMode) "SENT" else "PENDING")

                    dbHelper.insertMessage(newMessage, chatRoomId)

                    runOnUiThread {
                        messageList.add(newMessage)
                        chatAdapter.notifyItemInserted(messageList.size - 1)
                        rvChat.scrollToPosition(messageList.size - 1)
                    }
                    if (isOnlineMode) {
                        networkEngine?.sendMessage(partnerIP, "MSG_DATA $messageId $imagePayloadText", 8888)
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun setupChatRoom() {
        // CHANGED: this is the core fix. If we already know the peer's
        // permanent unique_id (passed from MainActivity's live scan/connect,
        // or from HistoryActivity for an already-known device), the room is
        // built from BOTH permanent IDs and will never change again — no
        // matter how many times either side renames.
        //
        // If we don't know it yet (old-style offline History entry, or a
        // peer that hasn't sent CMD_HELLO yet), we fall back to the EXACT
        // same name-based room id as before, so none of your already-saved
        // chat history breaks or goes missing.
        chatRoomId = if (remoteUniqueId.isNotEmpty()) {
            ChatRoomUtil.generateChatRoomId(myUniqueId, remoteUniqueId)
        } else {
            "room_${currentPartnerName.replace(" ", "_")}"
        }

        tvToolbarTitle.text = currentPartnerName
        tvToolbarSubtitle.text = if (isOnlineMode) "Online" else "Offline"

        if (currentPartnerName != myNickname && currentPartnerName != "Mesh Device") {
            dbHelper.insertPartner(currentPartnerName)
        }

        // NEW: if we know the permanent id, register/refresh it in the
        // devices table right away (don't wait for CMD_HELLO round-trip) —
        // this is what makes it show up correctly in HistoryActivity.
        if (remoteUniqueId.isNotEmpty()) {
            dbHelper.upsertDevice(remoteUniqueId, currentPartnerName, partnerIP)
        }

        chatAdapter = ChatAdapter(messageList) { clickedMessage -> showDeleteDialog(clickedMessage) }
        rvChat.layoutManager = LinearLayoutManager(this)
        rvChat.adapter = chatAdapter
        loadChatHistory()
    }

    private fun initSocketEngine() {
        networkEngine = ChatServerClient { incomingText: String, senderIp: String ->
            if (senderIp.isNotEmpty() && senderIp != "127.0.0.1" && senderIp != "0.0.0.0") partnerIP = senderIp
            runOnUiThread {
                try {
                    val command = incomingText.substringBefore(" ")
                    val extraData = incomingText.substringAfter(" ")
                    when (command) {
                        "CMD_NAME" -> {
                            val receivedDeviceName = extraData.trim()
                            if (receivedDeviceName.isNotEmpty()) {
                                currentPartnerName = receivedDeviceName
                                tvToolbarTitle.text = currentPartnerName
                                tvToolbarSubtitle.text = "Online"
                                if (currentPartnerName != myNickname) dbHelper.insertPartner(currentPartnerName)

                                // CHANGED: only rebuild the fallback name-based
                                // room if we STILL don't know the permanent id.
                                // Once we know remoteUniqueId, chatRoomId must
                                // never be recomputed from the nickname again.
                                if (remoteUniqueId.isEmpty()) {
                                    chatRoomId = "room_${currentPartnerName.replace(" ", "_")}"
                                }

                                isOnlineMode = true
                                loadPartnerProfilePictureToToolbar()
                                loadChatHistory()
                                syncPendingAndUnsentMessages()
                            }
                        }
                        // NEW: identity handshake. Sent alongside CMD_NAME on
                        // every connect (see sendDeviceNameHandshake below).
                        // Format: "CMD_HELLO <uniqueId> <nickname>"
                        "CMD_HELLO" -> {
                            val helloParts = extraData.trim().split(" ", limit = 2)
                            if (helloParts.size == 2) {
                                val incomingId = helloParts[0].trim()
                                val incomingNickname = helloParts[1].trim()

                                if (incomingId.isNotEmpty()) {
                                    // Same id -> same row updated. This is what
                                    // stops a rename from creating a new device.
                                    dbHelper.upsertDevice(incomingId, incomingNickname, partnerIP)

                                    // If this chat started without knowing the
                                    // remote id (rare fallback path), adopt it
                                    // now and switch to the permanent room.
                                    if (remoteUniqueId.isEmpty()) {
                                        remoteUniqueId = incomingId
                                        chatRoomId = ChatRoomUtil.generateChatRoomId(myUniqueId, remoteUniqueId)
                                        loadChatHistory()
                                    }

                                    // Header always reflects the CURRENT nickname.
                                    if (incomingNickname.isNotEmpty() && incomingNickname != currentPartnerName) {
                                        currentPartnerName = incomingNickname
                                        tvToolbarTitle.text = currentPartnerName
                                        if (currentPartnerName != myNickname) dbHelper.insertPartner(currentPartnerName)
                                    }
                                }
                            }
                        }
                        "CMD_PROFILE_PIC" -> {
                            saveIncomingProfileImage(extraData)
                        }
                        "CMD_DEL" -> {
                            val target = messageList.find { it.id == extraData }
                            if (target != null) {
                                target.messageText = "🚫 This message was deleted"
                                chatAdapter.notifyDataSetChanged()
                                dbHelper.updateMessageText(extraData, "🚫 This message was deleted")
                            }
                        }
                        "CMD_READ" -> {
                            val target = messageList.find { it.id == extraData }
                            if (target != null) {
                                target.status = "READ"
                                chatAdapter.notifyDataSetChanged()
                                dbHelper.updateMessageStatus(extraData, "READ")
                            }
                        }
                        "MSG_DATA" -> {
                            val msgId = extraData.substringBefore(" ")
                            val actualText = extraData.substringAfter(" ")
                            val receivedMessage = ChatMessage(msgId, "Partner", actualText, System.currentTimeMillis(), "READ")

                            dbHelper.insertMessage(receivedMessage, chatRoomId)
                            messageList.add(receivedMessage)
                            chatAdapter.notifyItemInserted(messageList.size - 1)
                            rvChat.scrollToPosition(messageList.size - 1)
                            thread { networkEngine?.sendMessage(partnerIP, "CMD_READ $msgId", 8888) }
                        }
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }
        }
        networkEngine?.startServer(8888)
        sendDeviceNameHandshake()
    }

    private fun saveIncomingProfileImage(base64Image: String) {
        thread {
            try {
                val decodedBytes = android.util.Base64.decode(base64Image, android.util.Base64.DEFAULT)
                val bitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
                if (bitmap != null) {
                    val file = File(filesDir, "partner_profile_${currentPartnerName}.jpg")
                    val outputStream = FileOutputStream(file)
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
                    outputStream.flush()
                    outputStream.close()

                    getSharedPreferences("MeshTalkPrefs", MODE_PRIVATE)
                        .edit()
                        .putString("PROFILE_IMAGE_$currentPartnerName", file.absolutePath)
                        .apply()

                    runOnUiThread {
                        ivToolbarProfile.setImageBitmap(bitmap)
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun startBackgroundAutoScan() {
        val intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        registerReceiver(p2pBroadcastReceiver, intentFilter)
        isReceiverRegistered = true
        thread {
            while (isReceiverRegistered) {
                try {
                    if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                        p2pManager?.discoverPeers(p2pChannel, null)
                    }
                    Thread.sleep(6000)
                } catch (e: Exception) { e.printStackTrace() }
            }
        }
    }

    private val p2pBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    if (ActivityCompat.checkSelfPermission(this@ChatActivity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                        p2pManager?.requestPeers(p2pChannel) { peerList ->
                            val targetDevice = peerList.deviceList.find { it.deviceName == currentPartnerName }
                            if (targetDevice != null && targetDevice.status == WifiP2pDevice.AVAILABLE) {
                                val config = WifiP2pConfig().apply { deviceAddress = targetDevice.deviceAddress }
                                p2pManager?.connect(p2pChannel, config, null)
                            }
                        }
                    }
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val networkInfo = intent.getParcelableExtra<android.net.NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                    if (networkInfo != null && networkInfo.isConnected) {
                        if (ActivityCompat.checkSelfPermission(this@ChatActivity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                            p2pManager?.requestConnectionInfo(p2pChannel) { info ->
                                if (info.groupFormed) {
                                    partnerIP = if (info.isGroupOwner) "192.168.49.1" else info.groupOwnerAddress.hostAddress ?: "192.168.49.1"
                                    tvToolbarTitle.text = currentPartnerName
                                    tvToolbarSubtitle.text = "Connected"
                                    isOnlineMode = true
                                    sendDeviceNameHandshake()
                                }
                            }
                        }
                    } else {
                        tvToolbarTitle.text = currentPartnerName
                        tvToolbarSubtitle.text = "Offline"
                        isOnlineMode = false
                    }
                }
            }
        }
    }

    private fun sendDeviceNameHandshake() {
        thread {
            try {
                val sharedPrefs = getSharedPreferences("MeshTalkPrefs", Context.MODE_PRIVATE)
                val myCustomName = sharedPrefs.getString("USER_NICKNAME", android.os.Build.MODEL) ?: android.os.Build.MODEL
                val myImagePath = sharedPrefs.getString("PROFILE_IMAGE_PATH", "")

                for (i in 1..3) {
                    if (partnerIP != "0.0.0.0") {
                        networkEngine?.sendMessage(partnerIP, "CMD_NAME $myCustomName", 8888)
                        // NEW: identity handshake, sent alongside the existing
                        // CMD_NAME so older/unupdated peers still get a name.
                        networkEngine?.sendMessage(partnerIP, "CMD_HELLO $myUniqueId $myCustomName", 8888)

                        if (!myImagePath.isNullOrEmpty() && File(myImagePath).exists()) {
                            val bitmap = BitmapFactory.decodeFile(myImagePath)
                            val stream = ByteArrayOutputStream()
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 60, stream)
                            val base64Str = android.util.Base64.encodeToString(stream.toByteArray(), android.util.Base64.NO_WRAP)
                            networkEngine?.sendMessage(partnerIP, "CMD_PROFILE_PIC $base64Str", 8888)
                        }
                    }
                    Thread.sleep(500)
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun syncPendingAndUnsentMessages() {
        thread {
            try {
                Thread.sleep(1000)
                val pendingChats = dbHelper.getChatHistory(chatRoomId).filter {
                    it.senderId == "Me" && (it.status == "PENDING" || it.status == "SENT")
                }
                for (msg in pendingChats) {
                    networkEngine?.sendMessage(partnerIP, "MSG_DATA ${msg.id} ${msg.messageText}", 8888)
                    if (msg.status == "PENDING") {
                        dbHelper.updateMessageStatus(msg.id, "SENT")
                        runOnUiThread {
                            msg.status = "SENT"
                            chatAdapter.notifyDataSetChanged()
                        }
                    }
                    Thread.sleep(250)
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun loadChatHistory() {
        val savedChats = dbHelper.getChatHistory(chatRoomId)
        messageList.clear()
        if (savedChats.isNotEmpty()) messageList.addAll(savedChats)
        runOnUiThread {
            chatAdapter.notifyDataSetChanged()
            if (messageList.isNotEmpty()) rvChat.scrollToPosition(messageList.size - 1)
        }
    }

    private fun showDeleteDialog(message: ChatMessage) {
        val dialogBuilder = AlertDialog.Builder(this)
        dialogBuilder.setTitle("Delete Message/Media")
        if (message.senderId == "Me") {
            dialogBuilder.setMessage("How do you want to delete this?")
            dialogBuilder.setPositiveButton("Delete for Everyone") { _, _ ->
                message.messageText = "🚫 This message was deleted"
                chatAdapter.notifyDataSetChanged()
                dbHelper.updateMessageText(message.id, "🚫 This message was deleted")
                if (isOnlineMode) thread { networkEngine?.sendMessage(partnerIP, "CMD_DEL ${message.id}", 8888) }
            }
            dialogBuilder.setNeutralButton("Delete for Me") { _, _ ->
                messageList.remove(message)
                chatAdapter.notifyDataSetChanged()
                dbHelper.updateMessageText(message.id, "🚫 You deleted this message")
            }
        } else {
            dialogBuilder.setMessage("Do you want to delete this message from your screen?")
            dialogBuilder.setPositiveButton("Delete for Me") { _, _ ->
                messageList.remove(message)
                chatAdapter.notifyDataSetChanged()
                dbHelper.updateMessageText(message.id, "🚫 You deleted this message")
            }
        }
        dialogBuilder.setNegativeButton("Cancel", null)
        dialogBuilder.show()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.chat_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                onBackPressed()
                true
            }
            R.id.action_clear_chat -> {
                showClearChatDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showClearChatDialog() {
        AlertDialog.Builder(this)
            .setTitle("Clear Chat History")
            .setMessage("Are you sure you want to clear all messages in this chat?")
            .setPositiveButton("Clear All") { _, _ -> clearChatHistory() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun clearChatHistory() {
        dbHelper.clearChatHistory(chatRoomId)
        messageList.clear()
        chatAdapter.notifyDataSetChanged()
        Toast.makeText(this, "Chat cleared successfully", Toast.LENGTH_SHORT).show()
    }

    override fun onBackPressed() {
        disconnectFromP2pGroup()
        super.onBackPressed()
    }

    private fun disconnectFromP2pGroup() {
        if (isReceiverRegistered) {
            try { unregisterReceiver(p2pBroadcastReceiver) } catch (e: Exception) { e.printStackTrace() }
            isReceiverRegistered = false
        }
        p2pManager?.removeGroup(p2pChannel, null)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isReceiverRegistered) {
            try { unregisterReceiver(p2pBroadcastReceiver) } catch (e: Exception) { e.printStackTrace() }
            isReceiverRegistered = false
        }
        networkEngine?.closeAll()
    }
}