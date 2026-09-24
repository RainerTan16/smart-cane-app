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
 * Smart Cane Companion — proof-of-concept.
 *
 * Flow: pair this phone to "SmartCane" in Android's Bluetooth
 * settings first, then open this app and hit Connect. Every alert
 * byte the ESP32 forwards gets mapped to a preloaded sound and
 * played instantly — audio comes out through whatever Bluetooth
 * headset (AirPods) is currently the phone's active audio output.
 * No AirPods-specific code needed; that routing is automatic on
 * Android.
 *
 * This is a starting point, not a finished app. Known gaps, worth
 * fixing before the actual defense demo:
 *  - No auto-reconnect if the connection drops mid-walk
 *  - No foreground service, so Android may kill the Bluetooth
 *    listener if the app is backgrounded for a while
 *  - No handling for unknown/unmapped track codes beyond ignoring them
 */
class MainActivity : AppCompatActivity() {

    // Standard Serial Port Profile UUID — matches ESP32's BluetoothSerial
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

    // SoundPool preloads every clip at launch so playback on an alert
    // is near-instant (a few ms) instead of MediaPlayer's ~100-500ms
    // per-play decode/prepare cost. That gap matters for a device
    // whose whole purpose is an immediate warning.
    private lateinit var soundPool: SoundPool
    private val soundIds = mutableMapOf<Int, Int>() // track code -> loaded sound id

    /**
     * Your real track range is bigger than a fixed map is worth
     * maintaining by hand: 1-21 (obstacle zone/distance combos),
     * 29-30 (stairs), 40-42 (SOS/system) — plus a "hole detected"
     * code and incline codes that aren't nailed down yet.
     *
     * Instead of a hardcoded list, this scans res/raw at startup
     * for any file matching "alert_XXXX" and preloads whatever it
     * finds. Drop in res/raw/alert_0015.mp3 for track 15 and it
     * just works — no code change needed here when new tracks (like
     * the hole/incline ones) get added later.
     */
    private val maxTrackCodeToScan = 99 // covers your current 1-42 range with headroom

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        deviceSpinner = findViewById(R.id.deviceSpinner)
        connectButton = findViewById(R.id.connectButton)

        setupSoundPool()
        requestBtPermissionsIfNeeded()
        loadPairedDevices()

        connectButton.setOnClickListener {
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

    private fun setupSoundPool() {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        soundPool = SoundPool.Builder()
            .setMaxStreams(2) // a couple of overlapping alerts is fine; more gets confusing to hear anyway
            .setAudioAttributes(attrs)
            .build()

        // Preload every clip that exists, while the app is idle, so
        // there's no decode/prepare delay at the moment an alert fires.
        for (trackCode in 1..maxTrackCodeToScan) {
            val resName = "alert_%04d".format(trackCode)
            val resId = resources.getIdentifier(resName, "raw", packageName)
            if (resId != 0) {
                soundIds[trackCode] = soundPool.load(this, resId, 1)
            }
        }
    }

    private fun requestBtPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val needed = listOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            ).filter {
                ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (needed.isNotEmpty()) {
                ActivityCompat.requestPermissions(this, needed.toTypedArray(), 1)
            }
        }
    }

    private fun loadPairedDevices() {
        val bonded = bluetoothAdapter?.bondedDevices ?: emptySet()
        pairedDevices.clear()
        pairedDevices.addAll(bonded)
        val names = bonded.map { it.name ?: it.address }
        deviceSpinner.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
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
            } catch (e: IOException) {
                mainHandler.post { statusText.text = "Connection failed: ${e.message}" }
            }
        }
    }

    private fun listenForAlerts(input: InputStream) {
        val reader = input.bufferedReader()
        while (true) {
            try {
                val line = reader.readLine() ?: break
                // Ignore anything non-numeric (e.g. stray text) rather than crash
                val trackCode = line.trim().toIntOrNull() ?: continue
                playAlert(trackCode)
            } catch (e: IOException) {
                mainHandler.post { statusText.text = "Disconnected: ${e.message}" }
                break
            }
        }
    }

    private fun playAlert(trackCode: Int) {
        val soundId = soundIds[trackCode] ?: return // unknown code — ignore
        mainHandler.post {
            soundPool.play(soundId, 1f, 1f, 1, 0, 1f)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ioExecutor.shutdownNow()
        socket?.let {
            try {
                it.close()
            } catch (_: IOException) {
            }
        }
        soundPool.release()
    }
}
