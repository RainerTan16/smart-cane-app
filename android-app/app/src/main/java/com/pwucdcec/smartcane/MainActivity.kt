package com.pwucdcec.smartcane

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Smart Cane Companion.
 *
 * Flow: pair this phone to "SmartCane" in Android's Bluetooth
 * settings first, then open this app and tap Connect. Every track
 * number the ESP32 forwards gets played instantly from a preloaded
 * sound. Audio comes out through whatever Bluetooth headset (AirPods)
 * is the phone's active audio output. No AirPods-specific code is
 * needed; Android routes it automatically.
 *
 * Known gaps: no auto-reconnect, no foreground service.
 */
class MainActivity : AppCompatActivity() {

    // Standard Serial Port Profile UUID, matches the ESP32's BluetoothSerial
    private val sppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        BluetoothAdapter.getDefaultAdapter()
    }

    private var socket: BluetoothSocket? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor()

    private lateinit var statusText: TextView
    private lateinit var deviceSpinner: Spinner
    private lateinit var connectButton: Button

    private val pairedDevices = mutableListOf<BluetoothDevice>()

    // SoundPool preloads every clip at launch so playback is near-instant.
    private lateinit var soundPool: SoundPool
    private val soundIds = mutableMapOf<Int, Int>() // track code -> loaded sound id

    // The app scans res/raw for files named alert_0001 ... alert_0099 and
    // preloads whatever exists. Add a file, it just works. No code change.
    private val maxTrackCodeToScan = 99

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        deviceSpinner = findViewById(R.id.deviceSpinner)
        connectButton = findViewById(R.id.connectButton)

        setupSoundPool()

        if (!hasBtPermission()) requestBtPermission()

        connectButton.setOnClickListener {
            if (!hasBtPermission()) {
                requestBtPermission()
                return@setOnClickListener
            }
            if (bluetoothAdapter?.isEnabled != true) {
                statusText.text = "Turn on Bluetooth first"
                return@setOnClickListener
            }
            val device = pairedDevices.getOrNull(deviceSpinner.selectedItemPosition)
            if (device == null) {
                Toast.makeText(
                    this,
                    "Pair 'SmartCane' in Android Bluetooth settings first",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                connectTo(device)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh the list whenever the screen comes back, so a device that
        // was just paired in Bluetooth settings shows up without restarting.
        if (hasBtPermission()) loadPairedDevices()
    }

    private fun setupSoundPool() {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        soundPool = SoundPool.Builder()
            .setMaxStreams(2)
            .setAudioAttributes(attrs)
            .build()

        for (trackCode in 1..maxTrackCodeToScan) {
            val resName = "alert_%04d".format(trackCode)
            val resId = resources.getIdentifier(resName, "raw", packageName)
            if (resId != 0) {
                soundIds[trackCode] = soundPool.load(this, resId, 1)
            }
        }
    }

    // On Android 12+ the app must be granted BLUETOOTH_CONNECT before it may
    // even read the list of paired devices. Without this check the app
    // crashes on first launch.
    private fun hasBtPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ActivityCompat.checkSelfPermission(
            this, Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestBtPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) {
            if (hasBtPermission()) {
                loadPairedDevices()
            } else {
                statusText.text =
                    "Bluetooth permission denied. Allow it in Settings > Apps > Smart Cane Companion > Permissions"
            }
        }
    }

    private fun loadPairedDevices() {
        try {
            val bonded = bluetoothAdapter?.bondedDevices ?: emptySet()
            pairedDevices.clear()
            pairedDevices.addAll(bonded)
            val names = pairedDevices.map { it.name ?: it.address }
            deviceSpinner.adapter =
                ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
            // Pre-select the cane if it is already paired
            val cane = pairedDevices.indexOfFirst { it.name == "SmartCane" }
            if (cane >= 0) deviceSpinner.setSelection(cane)
        } catch (e: SecurityException) {
            statusText.text = "Bluetooth permission needed"
        }
    }

    private fun connectTo(device: BluetoothDevice) {
        statusText.text = "Connecting..."
        ioExecutor.execute {
            try {
                val sock = device.createRfcommSocketToServiceRecord(sppUuid)
                bluetoothAdapter?.cancelDiscovery()
                sock.connect()
                socket = sock
                mainHandler.post { statusText.text = "Connected to ${device.name}" }
                listenForAlerts(sock.inputStream)
            } catch (e: Exception) {
                mainHandler.post { statusText.text = "Connection failed: ${e.message}" }
            }
        }
    }

    private fun listenForAlerts(input: InputStream) {
        val reader = input.bufferedReader()
        try {
            while (true) {
                val line = reader.readLine() ?: break
                // Ignore anything that is not a number (e.g. the "SOS_ALERT" text)
                val trackCode = line.trim().toIntOrNull() ?: continue
                playAlert(trackCode)
            }
        } catch (e: IOException) {
            // fall through to the message below
        }
        mainHandler.post { statusText.text = "Disconnected. Tap Connect to reconnect" }
    }

    private fun playAlert(trackCode: Int) {
        val soundId = soundIds[trackCode] ?: return // no audio file for this code
        mainHandler.post {
            soundPool.play(soundId, 1f, 1f, 1, 0, 1f)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ioExecutor.shutdownNow()
        try {
            socket?.close()
        } catch (e: IOException) {
        }
        soundPool.release()
    }
}
