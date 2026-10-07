package com.example.meshtalk

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.location.LocationManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var btnScanCircle: View
    private lateinit var rvDevices: RecyclerView
    private lateinit var tvStatus: TextView
    private lateinit var liveAdapter: DeviceAdapter
    private val scannedDeviceList = mutableListOf<DiscoveredPeerItem>()

    var p2pManager: WifiP2pManager? = null
    var p2pChannel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private val intentFilter = IntentFilter()

    private lateinit var dbHelper: DatabaseHelper
    private var incomingDialog: AlertDialog? = null
    private var outgoingDialog: AlertDialog? = null
    private var isScanClicked = false
    private var myNickname: String = ""
    private var myUniqueId: String = ""

    private var lastPromptedAddress: String? = null

    // Connection tracking
    private var isRequestingConnection = false
    private var pendingOutgoingDeviceAddress: String? = null
    private var pendingOutgoingDeviceName: String? = null
    // NEW: permanent unique_id of whoever we're connecting to (outgoing or incoming)
    private var pendingOutgoingDeviceUniqueId: String? = null

    // Map to keep track of discovered service nicknames by MAC address
    private val serviceNicknames = mutableMapOf<String, String>()
    // NEW: same idea, but for the permanent unique_id broadcast in the TXT record
    private val serviceUniqueIds = mutableMapOf<String, String>()

    // Gallery DP Picker Setup
    private var selectedImageUri: Uri? = null
    private var dialogDpImageView: ImageView? = null

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {}

            selectedImageUri = it
            dialogDpImageView?.let { img ->
                img.setImageURI(it)
                img.imageTintList = null
            }
        }
    }

    val peerListListener = WifiP2pManager.PeerListListener { peerList ->
        scannedDeviceList.clear()

        peerList.deviceList.forEach { device ->
            val discoveredNick = serviceNicknames[device.deviceAddress]
            val rawName = if (!discoveredNick.isNullOrEmpty()) {
                discoveredNick
            } else if (!device.deviceName.isNullOrEmpty()) {
                device.deviceName
            } else {
                "Mesh User"
            }

            var displayName = rawName
            if (rawName.contains("(#")) {
                displayName = rawName.split("(#")[0].trim()
            }

            // NEW: pull the permanent unique_id we may have already learned
            // via the DNS-SD TXT record for this MAC address.
            val discoveredUniqueId = serviceUniqueIds[device.deviceAddress] ?: ""

            scannedDeviceList.add(
                DiscoveredPeerItem(
                    device = device,
                    nickname = displayName,
                    uniqueId = discoveredUniqueId
                )
            )
        }
        loadLiveDevices()

        if (!isRequestingConnection) {
            val incomingInviter = peerList.deviceList.firstOrNull { device ->
                device.status == WifiP2pDevice.INVITED
            }

            if (incomingInviter != null) {
                handleIncomingInvitation(incomingInviter)
            }
        }
    }

    val connectionInfoListener = WifiP2pManager.ConnectionInfoListener { info ->

        if (info.groupFormed) {

            incomingDialog?.dismiss()
            outgoingDialog?.dismiss()

            incomingDialog = null
            outgoingDialog = null

            val connectedDeviceName = pendingOutgoingDeviceName ?: myNickname

            val partnerIP =
                if (info.isGroupOwner) {
                    "192.168.49.1"
                } else {
                    info.groupOwnerAddress.hostAddress ?: "192.168.49.1"
                }

            val targetMacAddress = pendingOutgoingDeviceAddress ?: lastPromptedAddress ?: ""

            // NEW: resolve the permanent unique_id for whoever we just connected to.
            val connectedUniqueId = pendingOutgoingDeviceUniqueId
                ?: serviceUniqueIds[targetMacAddress]
                ?: ""

            isRequestingConnection = false
            pendingOutgoingDeviceAddress = null
            pendingOutgoingDeviceName = null
            pendingOutgoingDeviceUniqueId = null

            val intent = Intent(this, ChatActivity::class.java).apply {
                putExtra("PEER_IP", partnerIP)
                putExtra("PEER_NAME", connectedDeviceName)
                putExtra("PEER_DEVICE_ADDRESS", targetMacAddress)
                // NEW: only passed when we actually know it — ChatActivity
                // falls back to the old nickname-based room if this is empty.
                if (connectedUniqueId.isNotEmpty()) {
                    putExtra("PEER_UNIQUE_ID", connectedUniqueId)
                }
            }

            startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        toolbar.setNavigationIcon(R.drawable.baseline_menu_24)
        toolbar.setNavigationOnClickListener { view ->
            showPopupMenu(view)
        }

        btnScanCircle = findViewById(R.id.btnScanCircle)
        rvDevices = findViewById(R.id.rvDevices)
        tvStatus = findViewById(R.id.tvStatus)

        dbHelper = DatabaseHelper(this)

        val sharedPrefs = getSharedPreferences("MeshTalkPrefs", Context.MODE_PRIVATE)
        myNickname = sharedPrefs.getString("USER_NICKNAME", "") ?: ""

        // CHANGED: unique_id now comes from DeviceIdentity (single source of
        // truth shared with ChatActivity), instead of a separate 6-char id
        // that was generated here and never used anywhere else.
        myUniqueId = DeviceIdentity.getMyId(this)

        checkAndSetupProfile()

        p2pManager = getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        p2pChannel = p2pManager?.initialize(this, mainLooper, null)

        saveMyDeviceMacAddress()
        startLocalServiceBroadcast()
        setupServiceDiscoveryListeners()

        intentFilter.apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }

        rvDevices.layoutManager = LinearLayoutManager(this)

        isScanClicked = false
        loadLiveDevices()

        btnScanCircle.setOnClickListener {
            if (isHardwareReadyForScanning()) {
                isScanClicked = true
                tvStatus.text = "Scanning..."
                startWifiP2pDiscovery()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun saveMyDeviceMacAddress() {
        if (!hasLocationOrNearbyPermission()) return
        val channel = p2pChannel ?: return
        p2pManager?.requestDeviceInfo(channel) { device ->
            if (device != null && !device.deviceAddress.isNullOrEmpty()) {
                getSharedPreferences("MeshTalkPrefs", Context.MODE_PRIVATE)
                    .edit()
                    .putString("MY_MAC_ADDRESS", device.deviceAddress)
                    .apply()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocalServiceBroadcast() {
        if (myNickname.isEmpty()) return
        if (!hasLocationOrNearbyPermission()) return
        val channel = p2pChannel ?: return

        // CHANGED: broadcast now also carries our permanent unique_id ("uid")
        // alongside the nickname, so peers can learn it during scan itself.
        val record = mapOf("nickname" to myNickname, "uid" to myUniqueId)
        val serviceInfo = WifiP2pDnsSdServiceInfo.newInstance("MeshTalkService", "_meshtalk._tcp", record)

        p2pManager?.clearLocalServices(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                p2pManager?.addLocalService(channel, serviceInfo, null)
            }
            override fun onFailure(reason: Int) {}
        })
    }

    @SuppressLint("MissingPermission")
    private fun setupServiceDiscoveryListeners() {
        val channel = p2pChannel ?: return
        p2pManager?.setDnsSdResponseListeners(
            channel,
            { _, _, _ -> },
            { _, record, device ->
                val nick = record["nickname"]
                val uid = record["uid"] // NEW

                if (!uid.isNullOrEmpty()) {
                    serviceUniqueIds[device.deviceAddress] = uid // NEW
                }

                if (!nick.isNullOrEmpty()) {
                    serviceNicknames[device.deviceAddress] = nick
                    scannedDeviceList.find { it.device.deviceAddress == device.deviceAddress }?.let { item ->
                        val index = scannedDeviceList.indexOf(item)
                        if (index != -1) {
                            // CHANGED: also refresh uniqueId on the list item, not just nickname.
                            scannedDeviceList[index] = item.copy(
                                nickname = nick,
                                uniqueId = uid ?: item.uniqueId
                            )
                            loadLiveDevices()
                        }
                    }
                }
            }
        )
    }

    private fun showPopupMenu(view: View) {
        val popup = PopupMenu(this, view)

        popup.menuInflater.inflate(R.menu.popup_menu, popup.menu)

        popup.setOnMenuItemClickListener { item: MenuItem ->
            when (item.itemId) {
                R.id.menu_chat_history -> {
                    startActivity(Intent(this, HistoryActivity::class.java))
                    true
                }
                R.id.menu_edit_profile -> {
                    showProfileSetupDialog(isFirstTime = false)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun checkAndSetupProfile() {
        val sharedPrefs = getSharedPreferences("MeshTalkPrefs", MODE_PRIVATE)
        val isFirstTime = sharedPrefs.getBoolean("IS_FIRST_TIME", true)

        if (isFirstTime || myNickname.isEmpty()) {
            showProfileSetupDialog(isFirstTime = true)
        }
    }

    private fun showProfileSetupDialog(isFirstTime: Boolean) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_profile_setup, null)
        val cardDpClick = dialogView.findViewById<CardView>(R.id.cardDpClick)
        val imgDialogDp = dialogView.findViewById<ImageView>(R.id.imgDialogDp)
        val etNickname = dialogView.findViewById<EditText>(R.id.etDialogNickname)
        val btnSave = dialogView.findViewById<Button>(R.id.btnSaveProfile)

        dialogDpImageView = imgDialogDp

        etNickname.setText(myNickname)
        val savedImagePath = getSharedPreferences("MeshTalkPrefs", MODE_PRIVATE).getString("PROFILE_IMAGE_PATH", null)
        if (!savedImagePath.isNullOrEmpty() && File(savedImagePath).exists()) {
            try {
                val bitmap = BitmapFactory.decodeFile(savedImagePath)
                imgDialogDp.setImageBitmap(bitmap)
                imgDialogDp.imageTintList = null
            } catch (_: Exception) {}
        }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(!isFirstTime)
            .create()

        cardDpClick.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }

        btnSave.setOnClickListener {
            val nameInput = etNickname.text.toString().trim()
            myNickname = if (nameInput.isNotEmpty()) nameInput else android.os.Build.MODEL

            val editor = getSharedPreferences("MeshTalkPrefs", MODE_PRIVATE).edit()
            editor.putString("USER_NICKNAME", myNickname)

            selectedImageUri?.let { uri ->
                val savedPath = saveProfileImageToInternalStorage(uri)
                if (savedPath != null) {
                    editor.putString("PROFILE_IMAGE_PATH", savedPath)
                }
            }

            editor.putBoolean("IS_FIRST_TIME", false)
            editor.apply()

            startLocalServiceBroadcast()

            Toast.makeText(this, "Profile Saved! 👤", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun saveProfileImageToInternalStorage(uri: Uri): String? {
        return try {
            val inputStream = contentResolver.openInputStream(uri) ?: return null
            val destFile = File(filesDir, "profile_dp.jpg")
            val outputStream = java.io.FileOutputStream(destFile)
            inputStream.copyTo(outputStream)
            inputStream.close()
            outputStream.close()
            destFile.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun showOutgoingRequest(device: WifiP2pDevice) {
        outgoingDialog?.dismiss()

        val displayName = serviceNicknames[device.deviceAddress] ?: device.deviceName

        outgoingDialog = AlertDialog.Builder(this)
            .setTitle("⏳ Waiting for Response")
            .setMessage("Waiting for $displayName to accept your connection request...")
            .setCancelable(false)
            .setNegativeButton("Cancel") { _, _ ->
                isRequestingConnection = false
                pendingOutgoingDeviceAddress = null
                pendingOutgoingDeviceName = null
                pendingOutgoingDeviceUniqueId = null

                val channel = p2pChannel
                if (channel != null) {
                    p2pManager?.cancelConnect(channel, null)
                }
                outgoingDialog = null
            }
            .show()
    }

    fun isOutgoingRequestActive(): Boolean {
        return isRequestingConnection
    }

    fun handleIncomingInvitation(invitedDevice: WifiP2pDevice) {
        if (incomingDialog != null && incomingDialog!!.isShowing) return
        if (lastPromptedAddress == invitedDevice.deviceAddress) return
        lastPromptedAddress = invitedDevice.deviceAddress

        val incomingName = serviceNicknames[invitedDevice.deviceAddress] ?: invitedDevice.deviceName
        // NEW: capture unique_id for the incoming inviter too, same fallback
        // pattern already used for connectionInfoListener via lastPromptedAddress.
        val incomingUniqueId = serviceUniqueIds[invitedDevice.deviceAddress]

        runOnUiThread {
            incomingDialog = AlertDialog.Builder(this)
                .setTitle("📩 Mesh Request Incoming")
                .setMessage("$incomingName wants to connect. Accept?")
                .setCancelable(false)
                .setPositiveButton("Accept") { _, _ ->
                    pendingOutgoingDeviceUniqueId = incomingUniqueId // NEW
                    connectToDeviceDirectly(invitedDevice)
                }
                .setNegativeButton("Reject") { _, _ ->
                    Toast.makeText(this, "Rejected $incomingName", Toast.LENGTH_SHORT).show()
                    val channel = p2pChannel
                    if (channel != null) {
                        p2pManager?.cancelConnect(channel, null)
                    }
                    lastPromptedAddress = null
                }.show()
        }
    }

    private fun hasLocationOrNearbyPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
        } else {
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun isHardwareReadyForScanning(): Boolean {
        // 1. Wi-Fi Status Check
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (!wifiManager.isWifiEnabled) {
            Toast.makeText(this, "⚠️ Please turn ON your Wi-Fi", Toast.LENGTH_SHORT).show()
            return false
        }

        // 2. Location Service Check (For devices below Android 12)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                Toast.makeText(this, "⚠️ Please turn ON Location", Toast.LENGTH_SHORT).show()
                return false
            }
        }

        // 3. Permission Check: Agar allowed hai toh dialog NAHI dikhayega
        if (!hasLocationOrNearbyPermission()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (shouldShowRequestPermissionRationale(Manifest.permission.NEARBY_WIFI_DEVICES)) {
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES), 101)
                } else {
                    showScanningHelpPopup("Nearby Devices")
                }
            } else {
                if (shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) {
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 101)
                } else {
                    showScanningHelpPopup("Location")
                }
            }
            return false
        }

        return true
    }

    @SuppressLint("MissingPermission")
    private fun startWifiP2pDiscovery() {
        if (!hasLocationOrNearbyPermission()) return
        val channel = p2pChannel ?: return

        scannedDeviceList.clear()
        loadLiveDevices()

        val serviceRequest = WifiP2pDnsSdServiceRequest.newInstance()
        p2pManager?.addServiceRequest(channel, serviceRequest, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                p2pManager?.discoverServices(channel, null)
            }
            override fun onFailure(reason: Int) {}
        })

        p2pManager?.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Toast.makeText(this@MainActivity, "Searching...", Toast.LENGTH_SHORT).show()
                tvStatus.text = "Searching..."
            }

            override fun onFailure(reasonCode: Int) {
                tvStatus.text = "Scan Failed"
                val permName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) "Nearby Devices" else "Location"
                showScanningHelpPopup(permName)
            }
        })
    }

    private fun showScanningHelpPopup(permissionType: String) {
        AlertDialog.Builder(this)
            .setTitle("Permission Required")
            .setMessage("$permissionType permission is required for scanning nearby devices. Please allow it in App Settings.")
            .setPositiveButton("Open Settings") { _, _ ->
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", packageName, null)
                    }
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (isHardwareReadyForScanning()) {
                    isScanClicked = true
                    tvStatus.text = "Scanning..."
                    startWifiP2pDiscovery()
                }
            } else {
                val permName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) "Nearby Devices" else "Location"
                showScanningHelpPopup(permName)
            }
        }
    }

    private fun loadLiveDevices() {
        val activeDisplayList = if (isScanClicked) scannedDeviceList else mutableListOf()

        if (activeDisplayList.isNotEmpty()) {
            tvStatus.text = "${activeDisplayList.size} found"
        } else if (isScanClicked) {
            tvStatus.text = "No devices found"
        }

        liveAdapter = DeviceAdapter(activeDisplayList) { selectedItem ->
            showConnectionPromptDialog(
                selectedItem.device,
                selectedItem.nickname,
                selectedItem.uniqueId // NEW
            )
        }

        rvDevices.adapter = liveAdapter
    }

    // CHANGED: added uniqueId param (default "" keeps old callers, if any, compiling)
    private fun showConnectionPromptDialog(device: WifiP2pDevice, customNickname: String, uniqueId: String = "") {
        AlertDialog.Builder(this)
            .setTitle("Connect Mesh Node")
            .setMessage("Send request to $customNickname?")
            .setPositiveButton("Connect") { _, _ ->
                isRequestingConnection = true
                pendingOutgoingDeviceAddress = device.deviceAddress
                pendingOutgoingDeviceName = customNickname
                pendingOutgoingDeviceUniqueId = uniqueId.ifEmpty { null } // NEW

                showOutgoingRequest(device)
                connectToDeviceDirectly(device)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @SuppressLint("MissingPermission")
    private fun connectToDeviceDirectly(device: WifiP2pDevice) {
        if (!hasLocationOrNearbyPermission()) return
        val channel = p2pChannel ?: return

        val config = android.net.wifi.p2p.WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
        }

        p2pManager?.connect(
            channel,
            config,
            object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Toast.makeText(this@MainActivity, "Request sent...", Toast.LENGTH_SHORT).show()
                }

                override fun onFailure(reasonCode: Int) {
                    isRequestingConnection = false
                    pendingOutgoingDeviceAddress = null
                    pendingOutgoingDeviceName = null
                    pendingOutgoingDeviceUniqueId = null

                    outgoingDialog?.dismiss()
                    outgoingDialog = null

                    Toast.makeText(this@MainActivity, "Failed", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    override fun onResume() {
        super.onResume()

        receiver = WiFiDirectBroadcastReceiver(
            p2pManager,
            p2pChannel,
            this
        )

        registerReceiver(receiver, intentFilter)
        saveMyDeviceMacAddress()
        startLocalServiceBroadcast()
        loadLiveDevices()
    }

    override fun onPause() {
        super.onPause()

        receiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: Exception) {}
        }
        receiver = null
    }
}