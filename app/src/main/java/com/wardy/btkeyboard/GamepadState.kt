package com.wardy.btkeyboard

/** Controller state, packed into the 9-byte report ID 2 described in [HidKeyboard.DESCRIPTOR]. */
data class GamepadState(
    val buttons: Int = 0,      // bit n = button n+1
    val hat: Int = HAT_NONE,   // 0 = up, clockwise to 7 = up-left; 8 = centred
    val lx: Int = 0, val ly: Int = 0, val rx: Int = 0, val ry: Int = 0,   // -127..127
    val lt: Int = 0, val rt: Int = 0,                                     // 0..255
) {
    fun toReport(): ByteArray = byteArrayOf(
        (buttons and 0xFF).toByte(), ((buttons shr 8) and 0xFF).toByte(),
        (hat and 0x0F).toByte(),
        lx.coerceIn(-127, 127).toByte(), ly.coerceIn(-127, 127).toByte(),
        rx.coerceIn(-127, 127).toByte(), ry.coerceIn(-127, 127).toByte(),
        lt.coerceIn(0, 255).toByte(), rt.coerceIn(0, 255).toByte(),
    )

    companion object {
        const val HAT_NONE = 8
        val NEUTRAL = GamepadState()
        // Button numbers follow the common Xbox-style order most PC games and Steam expect.
        const val A = 1 shl 0; const val B = 1 shl 1; const val X = 1 shl 2; const val Y = 1 shl 3
        const val LB = 1 shl 4; const val RB = 1 shl 5; const val VIEW = 1 shl 6; const val MENU = 1 shl 7
        const val LS = 1 shl 8; const val RS = 1 shl 9; const val HOME = 1 shl 10
    }
}
