package com.wardy.btkeyboard

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Makes the phone appear to Windows as a standard Bluetooth keyboard using Android's
 * HID Device profile (Android 9+). No software is needed on the PC.
 */
@SuppressLint("MissingPermission")
class HidKeyboard(private val context: Context, private val listener: Listener) {

    enum class State { UNSUPPORTED, BLUETOOTH_OFF, STARTING, READY, CONNECTING, CONNECTED }

    interface Listener {
        fun onStateChanged(state: State, device: BluetoothDevice?)
        fun onLedsChanged(leds: Int)
        fun onMessage(text: String)
    }

    private val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val prefs = context.getSharedPreferences("bt", Context.MODE_PRIVATE)
    /** One thread for every report so they always reach the PC in order. */
    private val executor = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var lastSendAt = 0L
    private var keepAlive: ScheduledFuture<*>? = null

    /**
     * Low-latency mode: while connected and the app is on screen, re-send the current key state
     * every few hundred ms. That stops the Bluetooth link dropping into power-saving sniff mode,
     * where each key press waits for the radio's next wake-up (often a second or more).
     */
    var lowLatency = true
        set(v) { field = v; updateKeepAlive() }
    private var foreground = false
    private val main = Handler(Looper.getMainLooper())

    private var hid: BluetoothHidDevice? = null
    @Volatile private var host: BluetoothDevice? = null
    private var registered = false
    @Volatile private var lastReport = ByteArray(8)

    var state = State.STARTING; private set
    val connectedDevice get() = host

    fun start() {
        val a = adapter ?: return setState(State.UNSUPPORTED)
        if (!a.isEnabled) return setState(State.BLUETOOTH_OFF)
        if (hid != null) { if (!registered) register(); return }
        setState(State.STARTING)
        if (!a.getProfileProxy(context, serviceListener, BluetoothProfile.HID_DEVICE)) {
            setState(State.UNSUPPORTED)
        }
    }

    fun stop() {
        try {
            hid?.let { h ->
                host?.let { sendRaw(it, ByteArray(8)) }
                h.unregisterApp()
                adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, h)
            }
        } catch (_: Exception) {}
        hid = null; registered = false; host = null
        keepAlive?.cancel(false); keepAlive = null
    }

    fun bondedDevices(): List<BluetoothDevice> =
        adapter?.bondedDevices?.sortedWith(
            compareByDescending<BluetoothDevice> { it.bluetoothClass?.majorDeviceClass == android.bluetooth.BluetoothClass.Device.Major.COMPUTER }
                .thenBy { it.name ?: it.address }
        ) ?: emptyList()

    fun connect(device: BluetoothDevice) {
        val h = hid ?: return listener.onMessage("Bluetooth keyboard service isn't ready yet")
        host?.takeIf { it != device }?.let { h.disconnect(it) }
        setState(State.CONNECTING, device)
        if (!h.connect(device)) {
            listener.onMessage("Couldn't start connecting to ${device.name ?: device.address}")
            setState(State.READY)
        }
    }

    fun disconnect() {
        val d = host ?: return
        sendRaw(d, ByteArray(8))
        hid?.disconnect(d)
        prefs.edit().remove(KEY_LAST).apply()
    }

    /** Send a boot-keyboard report: [modifiers, reserved, k1..k6]. */
    fun send(modifiers: Int, keys: IntArray) {
        val r = ByteArray(8)
        r[0] = modifiers.toByte()
        keys.take(6).forEachIndexed { i, k -> r[2 + i] = k.toByte() }
        lastReport = r
        val d = host ?: return
        executor.execute { sendRaw(d, r) }
    }

    /** Call from the activity's onResume/onPause so the keep-alive only runs while the keyboard is in use. */
    fun setForeground(fg: Boolean) { foreground = fg; updateKeepAlive() }

    private fun updateKeepAlive() {
        val want = lowLatency && foreground && host != null
        if (want && keepAlive == null) {
            keepAlive = executor.scheduleWithFixedDelay({
                val d = host ?: return@scheduleWithFixedDelay
                if (SystemClock.uptimeMillis() - lastSendAt >= KEEPALIVE_MS) sendRaw(d, lastReport)
            }, KEEPALIVE_MS, KEEPALIVE_MS, TimeUnit.MILLISECONDS)
        } else if (!want) {
            keepAlive?.cancel(false); keepAlive = null
        }
    }

    private fun sendRaw(device: BluetoothDevice, report: ByteArray) {
        try { hid?.sendReport(device, REPORT_ID, report); lastSendAt = SystemClock.uptimeMillis() } catch (_: Exception) {}
    }

    private fun register() {
        val h = hid ?: return
        val sdp = BluetoothHidDeviceAppSdpSettings(
            "Phone Keyboard", "Full-screen phone keyboard", "BT Keyboard",
            BluetoothHidDevice.SUBCLASS1_KEYBOARD, DESCRIPTOR
        )
        // Standard best-effort channel with room for bursts. (v1.1 asked for a "guaranteed" channel
        // whose 9-byte burst limit was smaller than one key report, which some chips throttle.)
        val outQos = BluetoothHidDeviceAppQosSettings(
            BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT, 800, 32, 2000,
            BluetoothHidDeviceAppQosSettings.MAX, BluetoothHidDeviceAppQosSettings.MAX
        )
        val inQos = BluetoothHidDeviceAppQosSettings(
            BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT, 0, 0, 0,
            BluetoothHidDeviceAppQosSettings.MAX, BluetoothHidDeviceAppQosSettings.MAX
        )
        if (!h.registerApp(sdp, inQos, outQos, executor, callback)) {
            listener.onMessage("Couldn't register as a keyboard. Another app may be using the Bluetooth keyboard feature.")
        }
    }

    private val serviceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            hid = proxy as BluetoothHidDevice
            register()
        }
        override fun onServiceDisconnected(profile: Int) {
            hid = null; registered = false; host = null
            setState(State.STARTING)
        }
    }

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            this@HidKeyboard.registered = registered
            if (!registered) { host = null; setState(State.STARTING); return }
            setState(State.READY)
            // Reconnect to the last PC automatically.
            val target = pluggedDevice ?: prefs.getString(KEY_LAST, null)?.let { addr ->
                adapter?.bondedDevices?.firstOrNull { it.address == addr }
            }
            target?.let { main.post { connect(it) } }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    host = device
                    prefs.edit().putString(KEY_LAST, device.address).apply()
                    setState(State.CONNECTED, device)
                    main.post { updateKeepAlive() }
                }
                BluetoothProfile.STATE_CONNECTING -> setState(State.CONNECTING, device)
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (host == null || host == device) { host = null; setState(State.READY); main.post { updateKeepAlive() } }
                }
            }
        }

        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            hid?.replyReport(device, type, id, lastReport)
        }

        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
            hid?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
            if (type == BluetoothHidDevice.REPORT_TYPE_OUTPUT && data.isNotEmpty()) leds(data.last().toInt())
        }

        override fun onInterruptData(device: BluetoothDevice, reportId: Byte, data: ByteArray) {
            if (data.isNotEmpty()) leds(data.last().toInt())
        }

        override fun onVirtualCableUnplug(device: BluetoothDevice) {
            if (host == device) host = null
            setState(State.READY)
        }
    }

    private fun leds(v: Int) = main.post { listener.onLedsChanged(v and 0xFF) }

    private fun setState(s: State, device: BluetoothDevice? = host) {
        state = s
        main.post { listener.onStateChanged(s, device) }
    }

    companion object {
        private const val KEY_LAST = "last_host"
        private const val REPORT_ID = 1
        private const val KEEPALIVE_MS = 250L

        /** Standard boot keyboard with report ID 1: 8 modifier bits, 1 reserved byte, 5 LED bits out, 6 key slots. */
        val DESCRIPTOR = byteArrayOf(
            0x05, 0x01,             // Usage Page (Generic Desktop)
            0x09, 0x06,             // Usage (Keyboard)
            0xA1.toByte(), 0x01,    // Collection (Application)
            0x85.toByte(), 0x01,    //   Report ID (1)
            0x05, 0x07,             //   Usage Page (Key Codes)
            0x19, 0xE0.toByte(),    //   Usage Min (224)
            0x29, 0xE7.toByte(),    //   Usage Max (231)
            0x15, 0x00,             //   Logical Min (0)
            0x25, 0x01,             //   Logical Max (1)
            0x75, 0x01,             //   Report Size (1)
            0x95.toByte(), 0x08,    //   Report Count (8)
            0x81.toByte(), 0x02,    //   Input (Data, Var, Abs) - modifiers
            0x95.toByte(), 0x01,    //   Report Count (1)
            0x75, 0x08,             //   Report Size (8)
            0x81.toByte(), 0x01,    //   Input (Const) - reserved
            0x95.toByte(), 0x05,    //   Report Count (5)
            0x75, 0x01,             //   Report Size (1)
            0x05, 0x08,             //   Usage Page (LEDs)
            0x19, 0x01,             //   Usage Min (Num Lock)
            0x29, 0x05,             //   Usage Max (Kana)
            0x91.toByte(), 0x02,    //   Output (Data, Var, Abs) - LEDs
            0x95.toByte(), 0x01,    //   Report Count (1)
            0x75, 0x03,             //   Report Size (3)
            0x91.toByte(), 0x01,    //   Output (Const) - padding
            0x95.toByte(), 0x06,    //   Report Count (6)
            0x75, 0x08,             //   Report Size (8)
            0x15, 0x00,             //   Logical Min (0)
            0x25, 0x65,             //   Logical Max (101)
            0x05, 0x07,             //   Usage Page (Key Codes)
            0x19, 0x00,             //   Usage Min (0)
            0x29, 0x65,             //   Usage Max (101)
            0x81.toByte(), 0x00,    //   Input (Data, Array) - keys
            0xC0.toByte()           // End Collection
        )
    }
}
