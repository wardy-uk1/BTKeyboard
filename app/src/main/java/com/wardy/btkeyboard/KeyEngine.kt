package com.wardy.btkeyboard

import android.os.SystemClock

/**
 * Tracks which keys are down and builds 8-byte boot-keyboard reports.
 *
 * Modifiers (Shift/Ctrl/Alt/Win/AltGr/Fn) behave like a physical keyboard when held with
 * another finger. Tapped on their own they become sticky:
 *   one tap = applies to the next key, two quick taps = locked, another tap = off.
 * Key repeat is handled by Windows itself while a key is held.
 */
class KeyEngine(private val sink: (modifiers: Int, keys: IntArray) -> Unit) {

    var onStateChanged: (() -> Unit)? = null

    private val pressed = LinkedHashSet<Int>()
    private var held = 0            // modifiers physically held down
    private var chorded = 0         // held modifiers that were used with another key
    var latched = 0; private set    // one-shot modifiers
    var locked = 0; private set     // locked modifiers
    private val latchTime = HashMap<Int, Long>()

    val activeMods get() = held or latched or locked
    val fnActive get() = activeMods and Hid.M_FN != 0
    val shiftActive get() = activeMods and (Hid.M_LSHIFT or Hid.M_RSHIFT) != 0

    fun keyDown(code: Int) {
        if (code == 0) return
        pressed.add(code)
        chorded = chorded or held
        send()
    }

    fun keyUp(code: Int) {
        if (!pressed.remove(code)) return
        if (pressed.isEmpty() && latched != 0) {
            // Release the key while the one-shot modifier is still applied, then drop the modifier.
            send()
            latched = 0
        }
        send()
    }

    fun modDown(bit: Int) {
        held = held or bit
        chorded = chorded and bit.inv()
        send()
    }

    fun modUp(bit: Int) {
        held = held and bit.inv()
        val wasChord = chorded and bit != 0
        chorded = chorded and bit.inv()
        if (!wasChord) {
            val now = SystemClock.uptimeMillis()
            when {
                locked and bit != 0 -> locked = locked and bit.inv()
                latched and bit != 0 -> {
                    latched = latched and bit.inv()
                    if (now - (latchTime[bit] ?: 0L) < DOUBLE_TAP_MS) locked = locked or bit
                }
                else -> { latched = latched or bit; latchTime[bit] = now }
            }
        }
        send()
    }

    fun releaseAll() {
        pressed.clear(); held = 0; chorded = 0; latched = 0; locked = 0
        send()
    }

    private fun send() {
        val mods = activeMods and 0xFF   // Fn never leaves the phone
        sink(mods, pressed.take(6).toIntArray())
        onStateChanged?.invoke()
    }

    companion object { const val DOUBLE_TAP_MS = 400L }
}
