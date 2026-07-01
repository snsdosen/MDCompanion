package com.snsdosen.mdcompanion

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.snsdosen.mdcompanion.protocol.Protocol
import com.snsdosen.mdcompanion.ui.theme.MDCompanionTheme
import kotlinx.coroutines.launch
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.net.MalformedURLException
import java.net.URL
import java.util.Scanner
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.Checksum
import okhttp3.OkHttpClient
import okhttp3.Request

data class DeviceInfo(
    val connected: String = "Disconnected",
    val macAddress: String = "Unknown",
    val firmware: String = "Unknown",
    val model: String = "Unknown",
    val changelog: String = "",
    val title: String = "MediaDev Companion"
)

class DeviceViewModel : ViewModel() {

    private val _buttonEnabled = mutableStateOf(false)
    val buttonEnabled: State<Boolean> = _buttonEnabled

    private val _buttonText = mutableStateOf("Check for updates")
    val buttonText: State<String> = _buttonText

    fun setEnabled(value: Boolean) {
        _buttonEnabled.value = value
    }

    fun setText(text: String) {
        _buttonText.value = text
    }
}

class MainActivity : ComponentActivity() {

    private var deviceInfo by mutableStateOf(DeviceInfo())

    private val viewModel: DeviceViewModel by viewModels()

    //Connected device info
    private var mdModel = ""
    private var mdRevision: Byte = 0x0
    private var mdFirmware: Byte = 0x0
    private var mdProtocol: Byte = 0x0
    private var mdBuildSignature: Byte = 0x0
    private var mbBuildNumber = 0x0
    private var mdMacAddress: String = ""

    //Available update info
    private var updFirmware: Byte = 0x0
    private var updChunks = 0
    private var updProgress = 0
    private var updChecksum: Long = 0
    private var updPartition: Byte = 0

    private var dbgClickCount = 0
    private var dbgUnlocked = false

    //Update binary
    lateinit var updBinary: ByteArray

    //Fetched length of file, url connection size is not working on all devices for some reason
    private var lenghtOfFile = -1

    //Update state
    private var updState: Int = Protocol.MD_UPDATE_DISABLED
    private var updateDirName = ""      //Short device identifier
    private var updateDirFullName = ""  //Full device identifier name

    private var bluetoothSocket: BluetoothSocket? = null
    private var readThread: Thread? = null

    // Standard SPP (Serial Port Profile) UUID
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private var bluetoothAdapter: BluetoothAdapter? = null

    // Compose state to update the UI dynamically
    private var connectionStatus by mutableStateOf("Initializing Bluetooth...")

    private var progressDialog: AlertDialog? = null
    private var progressText: TextView? = null

    // List of required permissions for Android 12+
    private val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN
        )
    } else {
        arrayOf()
    }

    private fun onBluetoothDeviceDisconnected(device: BluetoothDevice) {

        try {
            bluetoothSocket?.close()
        } catch (_: Exception) {
        }

        bluetoothSocket = null

        readThread?.interrupt()

        runOnUiThread {
            deviceInfo = DeviceInfo(
                connected = "Disconnected",
                macAddress = "Unknown",
                firmware = "Unknown",
                model = "Unknown",
                changelog = ""
            )
        }
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {

        override fun onReceive(context: Context?, intent: Intent?) {

            //Log.d("BluetoothApp", "Action = ${intent?.action}")

            when (intent?.action) {

                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> {

                    val state = intent.getIntExtra(
                        BluetoothProfile.EXTRA_STATE,
                        BluetoothProfile.STATE_DISCONNECTED
                    )

                    val device: BluetoothDevice? =
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

                    when (state) {

                        BluetoothProfile.STATE_CONNECTED -> {
                            device?.let {
                                checkBluetoothPermissionsAndStart()
                            }
                        }

                        BluetoothProfile.STATE_DISCONNECTED -> {
                            device?.let {
                                //Disconnect only if the firmware update is not in progress
                                if(updState == Protocol.MD_UPDATE_ENABLED || updState == Protocol.MD_UPDATE_DISABLED){
                                    onBluetoothDeviceDisconnected(it)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Permission launcher to request multiple permissions at once
    private val requestMultiplePermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            connectionStatus = "Permissions granted. Looking for connected device..."
            findActiveConnectedDevice()
        } else {
            connectionStatus = "Bluetooth permissions denied by user."
        }
    }

    var dialogClickListener: DialogInterface.OnClickListener =
        object : DialogInterface.OnClickListener {
            override fun onClick(dialog: DialogInterface?, which: Int) {
                when (which) {
                    DialogInterface.BUTTON_POSITIVE -> startUpdate()
                    DialogInterface.BUTTON_NEGATIVE -> {}
                }
            }
        }

    //Handle update prompt
    private fun handleUpdatePrompt() {
        val builder = AlertDialog.Builder(this@MainActivity)

        builder.setTitle("Your device will now be updated")
        builder.setMessage("Do not turn off your phone\nor device during the process.\n\nUpdate?")
            .setPositiveButton("Yes", dialogClickListener)
            .setNegativeButton("No", dialogClickListener).show()
    }

    //Handle clicks to update button
    private fun handleUpdateButton() {
        when (updState) {
            Protocol.MD_UPDATE_ENABLED -> checkForUpdates()
            Protocol.MD_UPDATE_AVAILABLE ->                 //Ask user for update confirmation
                handleUpdatePrompt()
        }
    }

    private fun onTopBarClick(){
        //Unlock debug mode for unrestricted firmware update
        if(dbgClickCount < 10)dbgClickCount++;
        else if(!dbgUnlocked) {
            dbgUnlocked = true
            deviceInfo = deviceInfo.copy(
                title = "MediaDebug Companion"
            )
        }
    }

    @Composable
    fun DeviceTopBar(
        viewModel: DeviceViewModel,
        connected: String,
        macAddress: String,
        firmware: String,
        model: String,
        changelog: String,
        title: String
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(16.dp)
                .clickable {
                    onTopBarClick()
                }
        ) {
            val enabled by viewModel.buttonEnabled
            val text by viewModel.buttonText

            Text(
                text = "$title",
                fontSize = 22.sp
            )

            Text(connected)

            Spacer(modifier = Modifier.height(32.dp))

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("MAC: $macAddress")
                Text("Model: $model")
                Text("Firmware: $firmware")

                Spacer(modifier = Modifier.height(32.dp))

                Button(
                    onClick = {
                        handleUpdateButton()
                    },
                    enabled = enabled
                ) {
                    Text(text)
                }
            }
            Spacer(modifier = Modifier.height(32.dp))

            Text(changelog)
        }
    }

    //Start the update process
    private fun startUpdate() {
        setUpdateButton(Protocol.MD_UPDATE_UPDATING, false, "Update")

        //Log.d(TAG, "Update in progress");
        showProgress()

        Thread(object : Runnable {
            override fun run() {
                downloadImage()
            }
        }).start()
    }

    //Download new firmware image
    //Runs in it's own thread
    private fun downloadImage() {
        try {
            val versionString =
                "/releases/download/" + convertVersionToString(updFirmware) + "/update_version-" + convertVersionToString(
                    updFirmware
                )

            //Byte.toString(updPartition);
            val u = URL(Protocol.MD_REL_SERVER_URL + "/" + updateDirFullName + versionString + ".bin")

            //Open connection to a file
            val conection = u.openConnection()
            conection.connect()

            //Get the length of the file
            Log.d("BluetoothApp", lenghtOfFile.toString())

            //If the length is negative file doesn't exist
            if (lenghtOfFile < Protocol.MD_CHUNK_SIZE) {
                downloadImageFailed()
                return
            }

            var updateAllocSize = 0

            //Check if another chunk needs to be added
            if ((lenghtOfFile % Protocol.MD_CHUNK_SIZE) > 0) {
                updateAllocSize =
                    lenghtOfFile + (Protocol.MD_CHUNK_SIZE - (lenghtOfFile % Protocol.MD_CHUNK_SIZE))
            } else {
                updateAllocSize = lenghtOfFile
            }

            updBinary = ByteArray(updateAllocSize)
            updChunks = updateAllocSize / Protocol.MD_CHUNK_SIZE

            Log.d("BluetoothApp", "File size: " + Integer.toString(lenghtOfFile))
            Log.d("BluetoothApp", "Allocated size: " + Integer.toString(updateAllocSize))

            //Buffer for downloaded data
            val input: InputStream = BufferedInputStream(u.openStream())
            var count = 0
            var total = 0
            var readSize = 1024

            while ((input.read(updBinary, total, readSize).also { count = it }) != -1) {
                total += count

                //Check if this is the last remainder piece
                if ((total + readSize) > lenghtOfFile) readSize = lenghtOfFile - total

                //File was read, break from loop
                if (total == lenghtOfFile) break
            }

            val checksum: Checksum = CRC32()
            checksum.update(updBinary, 0, lenghtOfFile)
            updChecksum = checksum.getValue()

            //Log.d(TAG, Integer.toString(total));
            //Log.d(TAG, "Download complete, checksum:" + Long.toHexString(checksumValue) );

            //Close the connection
            input.close()

            //Start update with the received file
            updProgress = 0
            sendPacket()
        } catch (mue: MalformedURLException) {
            //Log.e(TAG, "malformed url error", mue);
            downloadImageFailed()
            return
        } catch (ioe: IOException) {
            //Log.e(TAG, "io error", ioe);
            downloadImageFailed()
            return
        } catch (se: SecurityException) {
            //Log.e(TAG, "security error", se);
            downloadImageFailed()
            return
        }
    }

    //Send a currently active packet to a device
    private fun sendPacket() {
        val msg = ByteArray(16 + Protocol.MD_CHUNK_SIZE)
        var flashOffset = 0
        val checksum: Checksum = CRC32()

        //Calculate real flash offset based on the current packet
        flashOffset = updProgress * Protocol.MD_CHUNK_SIZE

        //Set header information, write data command
        msg[0] = 'X'.code.toByte()
        msg[1] = 'M'.code.toByte()
        msg[2] = 'D'.code.toByte()
        msg[3] = 'W'.code.toByte()

        //Flash offset
        System.arraycopy(intToByteArray(flashOffset), 0, msg, 4, 4)

        //Packet size
        System.arraycopy(intToByteArray(Protocol.MD_CHUNK_SIZE), 0, msg, 8, 4)

        //Packet data
        System.arraycopy(updBinary, flashOffset, msg, 12, Protocol.MD_CHUNK_SIZE)

        //Get checksum for the data (ignore checksum itself)
        checksum.update(msg, 0, Protocol.MD_CHUNK_SIZE + 12)
        val checksumValue = checksum.getValue()

        //Slap checksum on the end
        System.arraycopy(
            intToByteArray(checksumValue.toInt()),
            0,
            msg,
            12 + Protocol.MD_CHUNK_SIZE,
            4
        )

        //Log.d(TAG, Long.toHexString(checksumValue));
        sendByteArray(msg)
    }

    //Convert integer to byte array
    fun intToByteArray(a: Int): ByteArray {
        val ret = ByteArray(4)
        ret[0] = (a and 0xFF).toByte()
        ret[1] = ((a shr 8) and 0xFF).toByte()
        ret[2] = ((a shr 16) and 0xFF).toByte()
        ret[3] = ((a shr 24) and 0xFF).toByte()
        return ret
    }

    //Failed to fetch new firmware
    private fun downloadImageFailed() {
        runOnUiThread(object : Runnable {
            override fun run() {
                Toast.makeText(
                    getApplicationContext(),
                    "Failed to download firmware",
                    Toast.LENGTH_SHORT
                ).show()

                //Kill progress dialog
                hideProgress()
            }
        })
    }

    private fun showProgress() {

        progressText = TextView(this).apply {
            text = "Downloading firmware..."
            textSize = 18f
            // Centriranje teksta unutar samog TextView-a
            gravity = android.view.Gravity.CENTER
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 50, 50, 50)
            // Centriranje svih elemenata unutar LinearLayouta
            gravity = android.view.Gravity.CENTER

            // Dodavanje ProgressBar-a s centriranim layout parametrima
            val progressParam = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.CENTER
                bottomMargin = 30 // Malo razmaka između progress bara i teksta
            }
            addView(ProgressBar(context), progressParam)

            // Dodavanje TextView-a s centriranim layout parametrima
            val textParam = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, // Raširi na cijelu širinu da gravity unutar TextView-a radi
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            addView(progressText, textParam)
        }

        progressDialog = AlertDialog.Builder(this)
            .setView(layout)
            .setCancelable(false)
            .show()
    }

    private fun updateProgress(percent: Float, text: String) {
        runOnUiThread(object : Runnable {
            override fun run() {

                if (percent < 0) progressText?.text = text
                else progressText?.text = "$text (${percent.toInt()}%)"
            }
        })
    }

    private fun hideProgress() {
        runOnUiThread(object : Runnable {
            override fun run() {
                progressDialog?.dismiss()
            }
        })
    }

    private fun checkForUpdates() {
        setUpdateButton(Protocol.MD_UPDATE_CHECKING, false, "Checking...")

        Thread(object : Runnable {
            override fun run() {

                //Set directory name based on a device identifier
                if (updateDirName == "ESP-A-1.0") updateDirFullName = "especiallyAlfa"

                //If this is a debug build fetch from local server
                if (mdBuildSignature == Protocol.MD_DEBUG_BUILD_SIGNATURE) verifyDebugBinary()
                else downloadJSON()
            }
        }).start()
    }

    //Failed to fetch update descriptor, handle it gracefully
    private fun downloadJSONFailed() {
        runOnUiThread(object : Runnable {
            override fun run() {
                Toast.makeText(getApplicationContext(), "Update check failed", Toast.LENGTH_SHORT)
                    .show()
                setUpdateButton(Protocol.MD_UPDATE_ENABLED, true, "Check for updates")
            }
        })
    }

    //As the name implies, get string from the stream
    fun convertStreamToString(`is`: InputStream): String {
        try {
            return Scanner(`is`).useDelimiter("\\A").next()
        } catch (e: NoSuchElementException) {
            return ""
        }
    }

    //Update is available
    private fun updateAvailable() {
        runOnUiThread(object : Runnable {
            override fun run() {
                showAlert("Version " + convertVersionToString(updFirmware) + " is available.")
                //Toast.makeText(getApplicationContext(), "Update " + convertVersionToString(updFirmware) + " available", Toast.LENGTH_SHORT).show();
                setUpdateButton(Protocol.MD_UPDATE_AVAILABLE, true, "Update")
            }
        })
    }

    @Composable
    fun MyAlertDialog(
        text: String,
        onDismiss: () -> Unit
    ) {
        AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(onClick = onDismiss) {
                    Text("OK")
                }
            },
            text = {
                Text(text)
            }
        )
    }

    //Show user dismissible dialog
    private fun showAlert(alertText: String?) {
        val dlgAlert = AlertDialog.Builder(this)

        runOnUiThread(object : Runnable {
            override fun run() {

                dlgAlert.setMessage(alertText)
                //dlgAlert.setTitle(updateDirName);
                dlgAlert.setPositiveButton("OK", null)
                dlgAlert.setCancelable(false)
                dlgAlert.create().show()
            }
        })
    }

    //No new updates
    private fun updateNotAvailable() {
        runOnUiThread(object : Runnable {
            override fun run() {
                showAlert("No new updates available.\nYour device is up to date.")
                //Toast.makeText(getApplicationContext(), "No updates available", Toast.LENGTH_SHORT).show();
                setUpdateButton(Protocol.MD_UPDATE_ENABLED, true, "Check for updates")
            }
        })
    }

    //Show changelog to the user
    private fun setChangelog(changelog: String) {
        runOnUiThread(object : Runnable {
            override fun run() {
                runOnUiThread {
                    deviceInfo = deviceInfo.copy(
                        changelog = changelog
                    )
                }
            }
        })
    }

    //Verify existence of a debug binary on debug server
    private fun verifyDebugBinary(){
        val fileName = Protocol.MD_DEBUG_SERVER + "/build/" + updateDirFullName + ".bin"

        val `is`: InputStream

        Log.d("BluetoothApp", fileName)

        //Enable update if file exists
        /*if(fileExists(fileName)){
            Log.d("BluetoothApp", "exists")
        }*/
    }

    //Download update JSON and extract relevant info from it
    //Runs in it's own thread
    private fun downloadJSON() {
        val `is`: InputStream

        try {
            val u =
                URL(Protocol.MD_SERVER_URL + "/" + updateDirFullName + "/refs/heads/main/changelog.json")
            `is` = u.openStream()

            Log.d("BluetoothApp", u.toString())

            val dis = DataInputStream(`is`)
        } catch (mue: MalformedURLException) {
            Log.d("BluetoothApp", "malformed url error", mue)
            downloadJSONFailed()
            return
        } catch (ioe: IOException) {
            Log.d("BluetoothApp", "io error", ioe)
            downloadJSONFailed()
            return
        } catch (se: SecurityException) {
            Log.d("BluetoothApp", "security error", se)
            downloadJSONFailed()
            return
        }

        try {
            val jsonStr: String = convertStreamToString(`is`)
            val jsonObj = JSONObject(jsonStr)

            //Contains entire changelog info
            var changelogString = ""

            //Fetch available version
            updFirmware = jsonObj.getInt("version").toByte()

            //Fetch file size
            lenghtOfFile = jsonObj.getInt("size")

            //Check if the fetched version is newer then the current one
            if (updFirmware > mdFirmware || dbgUnlocked) {
                updateAvailable()

                //Get changelog
                val releases = jsonObj.getJSONArray("releases")
                for (i in 0..<releases.length()) {
                    val relVer = releases.getInt(i)
                    val versionName = "version-" + convertVersionToString(relVer.toByte())
                    var versionChangelog =
                        "Version " + convertVersionToString(relVer.toByte()) + ":\n"

                    //If this is a current or older release skip it
                    if (relVer <= mdFirmware) continue

                    //Get changelog for each release
                    val changes = jsonObj.getJSONArray(versionName)

                    for (j in 0..<changes.length()) {
                        versionChangelog += " - " + changes.getString(j) + "\n"
                    }


                    changelogString = versionChangelog + "\n" + changelogString
                }

                setChangelog(changelogString)
            } else {
                updateNotAvailable()
            }
        } catch (e: JSONException) {
            Log.e("BluetoothApp", "JSON: " + e.message)
            downloadJSONFailed()
            return
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        unregisterReceiver(bluetoothReceiver)

        try {
            bluetoothSocket?.close()
        } catch (_: Exception) {
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val filter = IntentFilter().apply {
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }

        //Bluetooth svent receiver
        registerReceiver(bluetoothReceiver, filter)

        // Initialize Bluetooth Adapter
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        setContent {
            val viewModel: DeviceViewModel = viewModel()
            MDCompanionTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        DeviceTopBar(
                            connected = deviceInfo.connected,
                            macAddress = deviceInfo.macAddress,
                            firmware = deviceInfo.firmware,
                            model = deviceInfo.model,
                            viewModel = viewModel,
                            changelog = deviceInfo.changelog,
                            title = deviceInfo.title
                        )
                    }
                ) { innerPadding ->

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        contentAlignment = Alignment.Center
                    ) {
                        //StatusText(status = connectionStatus)
                    }
                }
            }

            // Check permissions when the UI is ready
            LaunchedEffect(Unit) {
                if (bluetoothAdapter == null) {
                    connectionStatus = "Bluetooth is not supported on this device"
                } else {
                    checkBluetoothPermissionsAndStart()
                }
            }
        }
    }

    private fun checkBluetoothPermissionsAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val missingPermissions = requiredPermissions.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }

            if (missingPermissions.isEmpty()) {
                findActiveConnectedDevice()
            } else {
                connectionStatus = "Requesting Bluetooth permissions..."
                requestMultiplePermissionsLauncher.launch(requiredPermissions)
            }
        } else {
            findActiveConnectedDevice()
        }
    }

    private fun findActiveConnectedDevice() {
        connectionStatus = "Checking actively connected devices..."

        // Use A2DP profile proxy to check for devices connected to system audio
        bluetoothAdapter?.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
                if (profile == BluetoothProfile.A2DP) {
                    try {
                        val connectedDevices = proxy?.connectedDevices
                        if (!connectedDevices.isNullOrEmpty()) {
                            // Get the first actively connected device
                            val activeDevice = connectedDevices[0]
                            val deviceName = activeDevice.name ?: "Unknown Device"

                            connectionStatus = "Found connected device: $deviceName. Connecting to SPP..."
                            //Log.d("BluetoothApp", "Actively connected device: $deviceName [${activeDevice.address}]")

                            runOnUiThread {
                                deviceInfo = deviceInfo.copy(
                                    connected = "Connecting..."
                                )
                            }

                            // Close proxy profile connection to avoid resource leaks
                            bluetoothAdapter?.closeProfileProxy(BluetoothProfile.A2DP, proxy)

                            // Start SPP connection in background thread
                            Thread {
                                attemptSppConnection(activeDevice)
                            }.start()
                        } else {
                            connectionStatus = "No active connected Bluetooth devices found in system."
                            bluetoothAdapter?.closeProfileProxy(BluetoothProfile.A2DP, proxy)
                        }
                    } catch (e: SecurityException) {
                        connectionStatus = "Security Exception while reading device info."
                        Log.e("BluetoothApp", "Permission error in proxy listener", e)
                    }
                }
            }

            override fun onServiceDisconnected(profile: Int) {
                // Handle service disconnection if necessary
            }
        }, BluetoothProfile.A2DP)
    }

    private fun convertVersionToString(version: Byte): String {
        var outString = ""
        outString =
            (version.toInt() shr 4).toByte().toString() + "." + (version.toInt() and 0xF).toByte()
                .toString()
        return outString
    }

    fun setUpdateButton(updateState: Int, buttonState: Boolean, buttonText: String) {
        viewModel.setEnabled(buttonState)
        viewModel.setText(buttonText)
        updState = updateState
    }

    //Receive response from a connected device
    private fun handleResponse(msg: String) {
        val message = msg.replace("\r\n", "")

        //Message has no header, and is thus invalid
        if (message.length < 4) return

        //Check if proper header is included
        if (message.substring(0, 3) != Protocol.MD_REPLY) {
            //Log.d(TAG,"Wrong command signature");
            return
        }

        when (message.substring(3, 4)) {
            Protocol.MD_CMD_IDENTIFIER ->                 //Identifier confirmed, request model
                if (message.substring(4) == Protocol.MD_IDENTIFIER) {

                    //Device is verified, we are connected
                    runOnUiThread {
                        deviceInfo = deviceInfo.copy(
                            connected = "Connected",
                            macAddress = mdMacAddress
                        )
                    }

                    sendMessage(Protocol.MD_GET_MODEL)
                }

            Protocol.MD_CMD_MODEL -> {
                mdModel = message.substring(4)
                sendMessage(Protocol.MD_GET_REVISION)
            }

            Protocol.MD_CMD_REVISION -> {
                mdRevision = message.get(4).code.toByte()

                runOnUiThread {
                    deviceInfo = deviceInfo.copy(
                        model = "${mdModel}-${convertVersionToString(mdRevision)}"
                    )
                }

                sendMessage(Protocol.MD_GET_FIRMWARE)
            }

            Protocol.MD_CMD_FIRMWARE -> {
                mdFirmware = message.get(4).code.toByte()

                //Check if this is the extended answer added in protocol 1.2
                if (message.length == 8) {
                    mdBuildSignature = message.get(5).code.toByte()
                    mbBuildNumber = message.get(6).code.toByte()
                        .toInt() or ((message.get(7).code.toByte()).toInt() shl 8)
                }

                runOnUiThread {
                    var fbBuild = convertVersionToString(mdFirmware)

                    if (mdBuildSignature == Protocol.MD_DEBUG_BUILD_SIGNATURE) fbBuild += " debug"
                    deviceInfo = deviceInfo.copy(
                        firmware = fbBuild
                    )
                }

                sendMessage(Protocol.MD_GET_PARTITION)

                //Check if this is the extended answer added in protocol 1.1
                if (message.length == 8) {
                    mdBuildSignature = message.get(5).code.toByte()
                    mbBuildNumber = message.get(6).code.toByte()
                        .toInt() or ((message.get(7).code.toByte()).toInt() shl 8)
                }
            }

            Protocol.MD_CMD_PARTITION -> {
                updPartition = message.get(4).code.toByte()
                sendMessage(Protocol.MD_GET_PROTOCOL)
            }

            Protocol.MD_CMD_PROTOCOL -> {
                mdProtocol = message.get(4).code.toByte()

                //Update update URL
                updateDirName = mdModel + "-" + convertVersionToString(mdRevision)

                //Enable check for update button
                runOnUiThread {
                    setUpdateButton(Protocol.MD_UPDATE_ENABLED, true, "Check for updates")
                }

                //Register event for player buttons
                //registerEvent(Constants.MD_EVENT_PLAYER_CMD)
            }

            Protocol.MD_CMD_WRITE ->                 //Check the response code
                when (message.substring(4, 5)) {
                    Protocol.MD_WRITE_ERROR -> {
                        //Undefined error, stop update
                        showAlert("There was an error during update.")
                        stopUpdate()
                    }

                    Protocol.MD_WRITE_OK -> {
                        if(updChunks != 0) updateProgress((updProgress.toFloat() / updChunks.toFloat()) * 100, "Updating firmware...   ")

                        //Go to next chunk if the update is in progress
                        if (updProgress < (updChunks - 1) && updState == Protocol.MD_UPDATE_UPDATING) {
                            updProgress++
                            sendPacket()
                        } else if (updProgress == (updChunks - 1) && updState == Protocol.MD_UPDATE_UPDATING) {
                            updateProgress(-1f, "Verifing image...")

                            //Request successful verification
                            sendMessage(Protocol.MD_VERIFY_DATA)
                        }
                    }

                    Protocol.MD_WRITE_WRONG_FORMAT -> {
                        //Binary is not meant for this device
                        showAlert("Error, firmware is not meant for this device.")
                        stopUpdate()
                    }

                    Protocol.MD_WRITE_WRONG_CHECKSUM -> sendPacket()
                }

            Protocol.MD_CMD_VERIFY ->                 //Check the response code
                when (message.substring(4, 5)) {
                    Protocol.MD_WRITE_OK -> {

                        showAlert("Update to version " + convertVersionToString(updFirmware) + " is complete.")

                        //Reboot device to new firmware, will disconnect itself
                        sendMessage(Protocol.MD_REBOOT)

                        //Natural way of update termination
                        stopUpdate()
                        setChangelog("")
                    }

                    Protocol.MD_WRITE_ERROR -> {
                        showAlert("Image verification failed, device was not updated.")
                        stopUpdate()
                    }
                }

            //Unused events
            Protocol.MD_CMD_REBOOT -> {}
            Protocol.MD_CMD_NOTIFICATION -> {}
            Protocol.MD_CMD_REGISTER_EVT -> {}
            Protocol.MD_CMD_EVENT -> {}
        }

        //Log.d(TAG,message.substring(0, 3) );
    }

    private fun stopUpdate() {
        hideProgress()
        updState = Protocol.MD_UPDATE_DISABLED
        //disconnectDevice()
    }

    private fun startListening() {
        readThread = Thread {
            try {
                val input = bluetoothSocket?.inputStream ?: return@Thread

                val buffer = ByteArray(1024)

                while (!Thread.currentThread().isInterrupted) {

                    val bytes = input.read(buffer)

                    if (bytes > 0) {
                        val message = String(buffer, 0, bytes)

                        handleResponse(message)
                    }
                }

            } catch (e: IOException) {
                //Log.e("BluetoothApp", "Read failed", e)
            }
        }

        readThread?.start()
    }

    //Send data through active SPP serial port
    private fun sendByteArray(message: ByteArray) {
        try {
            val output = bluetoothSocket?.outputStream ?: return
            output.write(message)
            output.flush()
        } catch (e: IOException) {
            Log.e("BluetoothApp", "Send failed", e)
        }
    }

    private fun sendMessage(message: String) {
        try {
            val output = bluetoothSocket?.outputStream ?: return

            val fullMessage = "$message\r\n"

            output.write(fullMessage.toByteArray())
            output.flush()

        } catch (e: IOException) {
            Log.e("BluetoothApp", "Send failed", e)
        }
    }

    private fun attemptSppConnection(device: BluetoothDevice) {
        var socket: BluetoothSocket? = null
        try {
            val deviceName = device.name ?: "Unknown Device"

            // Create RFCOMM socket with SPP UUID
            socket = device.createRfcommSocketToServiceRecord(SPP_UUID)

            bluetoothAdapter?.cancelDiscovery()

            socket.connect()

            bluetoothSocket = socket

            connectionStatus = "CONNECTED via SPP to: $deviceName"

            startListening();

            //Save MAC address
            mdMacAddress = device.address

            //Start the interrogation chain with identifier request
            sendMessage(Protocol.MD_GET_IDENTIFIER)

            //Log.d("BluetoothApp", "Successfully connected to $deviceName via SPP")

        } catch (e: IOException) {
            val deviceName = device.name ?: "Unknown Device"
            connectionStatus = "$deviceName found, but it does NOT support SPP or connection failed."
            Log.e("BluetoothApp", "SPP connection failed for $deviceName", e)

            try {
                socket?.close()
            } catch (closeException: IOException) {
                Log.e("BluetoothApp", "Error closing socket", closeException)
            }
        } catch (e: SecurityException) {
            connectionStatus = "Permission denied while connecting to ${device.name}"
            Log.e("BluetoothApp", "Security exception inside attemptSppConnection", e)
        }
    }
}

@Composable
fun StatusText(status: String, modifier: Modifier = Modifier) {
    Text(
        text = status,
        fontSize = 18.sp,
        modifier = modifier
    )
}