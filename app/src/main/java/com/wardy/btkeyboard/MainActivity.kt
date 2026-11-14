package com.wardy.btkeyboard

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

@SuppressLint("MissingPermission")
class MainActivity : Activity(), HidKeyboard.Listener {

    private lateinit var hid: HidKeyboard
    private lateinit var keyboard: KeyboardView
    private lateinit var status: TextView
    private lateinit var dot: View
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private val dp by lazy { resources.displayMetrics.density }

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_ON -> hid.start()
                BluetoothAdapter.STATE_OFF -> { hid.stop(); onStateChanged(HidKeyboard.State.BLUETOOTH_OFF, null) }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        hid = HidKeyboard(applicationContext, this).apply { lowLatency = prefs.getBoolean("low_latency", true) }
        keyboard = KeyboardView(this).apply {
            engine = KeyEngine { mods, keys -> hid.send(mods, keys) }.also { it.onStateChanged = { invalidate() } }
            layout = Layouts.byId(prefs.getString("layout", Layouts.UK_COMPACT))
            hapticsEnabled = prefs.getBoolean("haptics", true)
            soundEnabled = prefs.getBoolean("sound", false)
            rgbEffect = KeyboardView.RgbEffect.entries.getOrElse(prefs.getInt("rgb_effect", 0)) { KeyboardView.RgbEffect.WAVE }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF121418.toInt())
            addView(buildTopBar(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (34 * dp).toInt()))
            addView(keyboard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
        registerReceiver(btReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        ensurePermissionsThenStart()
    }

    private fun buildTopBar(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding((12 * dp).toInt(), 0, (6 * dp).toInt(), 0)
        dot = View(context).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.GRAY) }
        }
        addView(dot, LinearLayout.LayoutParams((9 * dp).toInt(), (9 * dp).toInt()).apply { marginEnd = (8 * dp).toInt() })
        status = TextView(context).apply {
            setTextColor(0xFFD0D6DE.toInt()); textSize = 13f; text = "Starting…"; isSingleLine = true
            setOnClickListener { showConnectDialog() }
        }
        addView(status, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { }); status.gravity = Gravity.CENTER_VERTICAL
        addView(barButton("PC") { showConnectDialog() })
        addView(barButton("Layout") { showLayoutDialog() })
        addView(barButton("⚙") { showSettingsDialog() })
    }

    private fun barButton(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE); textSize = 13f; gravity = Gravity.CENTER
        setPadding((14 * dp).toInt(), 0, (14 * dp).toInt(), 0)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT)
        background = GradientDrawable().apply { setColor(0xFF22262D.toInt()); cornerRadius = 6 * dp }
        (layoutParams as LinearLayout.LayoutParams).setMargins((4 * dp).toInt(), (4 * dp).toInt(), 0, (4 * dp).toInt())
        setOnClickListener { onClick() }
    }

    // ---------- permissions ----------
    private fun neededPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
        else emptyArray()

    private fun ensurePermissionsThenStart() {
        val missing = neededPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) hid.start() else requestPermissions(missing.toTypedArray(), REQ_PERMS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) hid.start()
        else {
            status.text = "Bluetooth permission needed. Tap to grant."
            status.setOnClickListener {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", packageName, null)))
            }
        }
    }

    // ---------- HID listener ----------
    override fun onStateChanged(state: HidKeyboard.State, device: BluetoothDevice?) {
        val name = device?.let { it.name ?: it.address }
        val (color, text) = when (state) {
            HidKeyboard.State.UNSUPPORTED -> 0xFFE53935.toInt() to "This phone doesn't support acting as a Bluetooth keyboard"
            HidKeyboard.State.BLUETOOTH_OFF -> 0xFFE53935.toInt() to "Bluetooth is off. Tap to turn on"
            HidKeyboard.State.STARTING -> Color.GRAY to "Starting…"
            HidKeyboard.State.READY -> 0xFFFFB300.toInt() to "Not connected. Tap PC to connect or pair"
            HidKeyboard.State.CONNECTING -> 0xFFFFB300.toInt() to "Connecting to $name…"
            HidKeyboard.State.CONNECTED -> 0xFF4CAF50.toInt() to "Connected to $name"
        }
        (dot.background as GradientDrawable).setColor(color)
        status.text = text
        status.setOnClickListener {
            if (state == HidKeyboard.State.BLUETOOTH_OFF) startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            else showConnectDialog()
        }
        if (state != HidKeyboard.State.CONNECTED) keyboard.releaseAll()
    }

    override fun onLedsChanged(leds: Int) { keyboard.capsLockOn = leds and 0x02 != 0 }

    override fun onMessage(text: String) { runOnUiThread { Toast.makeText(this, text, Toast.LENGTH_LONG).show() } }

    // ---------- dialogs ----------
    private fun dialog() = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)

    private fun showConnectDialog() {
        if (hid.state == HidKeyboard.State.UNSUPPORTED) {
            dialog().setTitle("Not supported")
                .setMessage("Your phone's Bluetooth stack doesn't offer the HID device profile, so it can't act as a keyboard. Some manufacturers remove it.")
                .setPositiveButton("OK", null).show()
            return
        }
        val devices = hid.bondedDevices()
        val connected = hid.connectedDevice
        val items = mutableListOf<String>()
        if (connected != null) items += "Disconnect from ${connected.name ?: connected.address}"
        items += "Pair a new PC…"
        items += devices.map { d ->
            val tag = if (d.bluetoothClass?.majorDeviceClass == android.bluetooth.BluetoothClass.Device.Major.COMPUTER) "💻 " else "   "
            tag + (d.name ?: d.address) + if (d == connected) "  ✓" else ""
        }
        dialog().setTitle("Connect to a PC")
            .setItems(items.toTypedArray()) { _, which ->
                var i = which
                if (connected != null) { if (i == 0) { hid.disconnect(); return@setItems }; i-- }
                if (i == 0) { showPairInstructions(); return@setItems }
                hid.connect(devices[i - 1])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showPairInstructions() {
        val name = (getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter?.name) ?: "this phone"
        startActivityForResult(
            Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120),
            REQ_DISCOVERABLE
        )
        dialog().setTitle("Pair with Windows")
            .setMessage(
                "1. Allow the phone to be visible (next prompt).\n" +
                "2. On the PC open Settings › Bluetooth & devices › Add device › Bluetooth.\n" +
                "3. Choose \"$name\" and confirm the code on both screens.\n\n" +
                "Windows will set it up as a keyboard. Next time it reconnects automatically when you open this app."
            )
            .setPositiveButton("OK", null).show()
    }

    private fun showLayoutDialog() {
        val all = Layouts.all
        val current = all.indexOfFirst { it.id == keyboard.layout.id }
        dialog().setTitle("Keyboard layout")
            .setSingleChoiceItems(all.map { it.title }.toTypedArray(), current) { d, which ->
                keyboard.layout = all[which]
                prefs.edit().putString("layout", all[which].id).apply()
                d.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSettingsDialog() {
        val labels = arrayOf("Vibrate on key press", "Key click sound", "Low-latency mode (uses a little more battery)")
        val checked = booleanArrayOf(keyboard.hapticsEnabled, keyboard.soundEnabled, hid.lowLatency)
        dialog().setTitle("Settings")
            .setMultiChoiceItems(labels, checked) { _, which, on ->
                when (which) {
                    0 -> { keyboard.hapticsEnabled = on; prefs.edit().putBoolean("haptics", on).apply() }
                    1 -> { keyboard.soundEnabled = on; prefs.edit().putBoolean("sound", on).apply() }
                    2 -> { hid.lowLatency = on; prefs.edit().putBoolean("low_latency", on).apply() }
                }
            }
            .setPositiveButton("Done", null)
            .setNeutralButton("About") { _, _ -> AboutDialog.show(this) }
            .setNegativeButton("Lighting") { _, _ -> showLightingDialog() }
            .show()
    }

    private fun showLightingDialog() {
        val effects = KeyboardView.RgbEffect.entries
        dialog().setTitle("Gaming keyboard lighting")
            .setSingleChoiceItems(effects.map { it.title }.toTypedArray(), keyboard.rgbEffect.ordinal) { d, which ->
                keyboard.rgbEffect = effects[which]
                prefs.edit().putInt("rgb_effect", which).apply()
                d.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- lifecycle / full screen ----------
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.apply {
                hide(WindowInsets.Type.systemBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        }
    }

    override fun onResume() {
        super.onResume()
        hid.setForeground(true)
    }

    override fun onPause() {
        super.onPause()
        keyboard.releaseAll()   // never leave a key stuck down on the PC
        hid.setForeground(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(btReceiver) } catch (_: Exception) {}
        hid.stop()
    }

    companion object {
        private const val REQ_PERMS = 1
        private const val REQ_DISCOVERABLE = 2
    }
}
