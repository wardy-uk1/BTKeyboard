package com.wardy.btkeyboard

/** USB HID usage IDs (Keyboard/Keypad page 0x07). Windows maps these using its own layout setting. */
object Hid {
    fun letter(c: Char) = 0x04 + (c.lowercaseChar() - 'a')
    fun digit(d: Int) = if (d == 0) 0x27 else 0x1D + d       // 1..9 -> 0x1E..0x26, 0 -> 0x27
    fun f(n: Int) = 0x39 + n                                   // F1 = 0x3A ... F12 = 0x45

    const val ENTER = 0x28; const val ESC = 0x29; const val BKSP = 0x2A; const val TAB = 0x2B
    const val SPACE = 0x2C; const val MINUS = 0x2D; const val EQUAL = 0x2E
    const val LBRACKET = 0x2F; const val RBRACKET = 0x30; const val BACKSLASH = 0x31
    const val NONUS_HASH = 0x32; const val SEMICOLON = 0x33; const val QUOTE = 0x34
    const val GRAVE = 0x35; const val COMMA = 0x36; const val DOT = 0x37; const val SLASH = 0x38
    const val CAPS = 0x39; const val PRTSC = 0x46; const val SCRLK = 0x47; const val PAUSE = 0x48
    const val INS = 0x49; const val HOME = 0x4A; const val PGUP = 0x4B; const val DEL = 0x4C
    const val END = 0x4D; const val PGDN = 0x4E; const val RIGHT = 0x4F; const val LEFT = 0x50
    const val DOWN = 0x51; const val UP = 0x52; const val NUMLK = 0x53
    const val KP_SLASH = 0x54; const val KP_STAR = 0x55; const val KP_MINUS = 0x56
    const val KP_PLUS = 0x57; const val KP_ENTER = 0x58; const val KP_DOT = 0x63
    fun kp(d: Int) = if (d == 0) 0x62 else 0x58 + d          // KP1 = 0x59 ... KP9 = 0x61, KP0 = 0x62
    const val NONUS_BACKSLASH = 0x64; const val MENU = 0x65

    // Modifier bits in byte 0 of the report
    const val M_LCTRL = 0x01; const val M_LSHIFT = 0x02; const val M_LALT = 0x04; const val M_LWIN = 0x08
    const val M_RCTRL = 0x10; const val M_RSHIFT = 0x20; const val M_RALT = 0x40; const val M_RWIN = 0x80
    /** App-only "Fn" layer bit, never sent to the PC. */
    const val M_FN = 0x100
}

enum class KeyKind { NORMAL, MODIFIER, SPACER }

data class KeyDef(
    val label: String,
    val code: Int = 0,
    val width: Float = 1f,
    val shiftLabel: String? = null,
    val kind: KeyKind = KeyKind.NORMAL,
    val modBit: Int = 0,
    val fnCode: Int? = null,
    val fnLabel: String? = null,
    val rowSpan: Int = 1,
    val isCaps: Boolean = false,
)

class KeyboardLayout(val id: String, val title: String, val rows: List<List<KeyDef>>)

private fun k(label: String, code: Int, w: Float = 1f, shift: String? = null, fn: Int? = null, fnLabel: String? = null, span: Int = 1) =
    KeyDef(label, code, w, shift, KeyKind.NORMAL, 0, fn, fnLabel, span)
private fun mod(label: String, bit: Int, w: Float) = KeyDef(label, 0, w, kind = KeyKind.MODIFIER, modBit = bit)
private fun gap(w: Float) = KeyDef("", 0, w, kind = KeyKind.SPACER)
private fun letters(s: String) = s.map { k(it.uppercase(), Hid.letter(it)) }
private fun caps(w: Float) = KeyDef("Caps", Hid.CAPS, w, isCaps = true)

object Layouts {
    const val UK_COMPACT = "uk_compact"
    const val UK_FULL = "uk_full"
    const val US_FULL = "us_full"
    const val UK_FULL_NUMPAD = "uk_full_numpad"

    // ---------- shared pieces ----------
    private val ukShift = mapOf(1 to "!", 2 to "\"", 3 to "£", 4 to "$", 5 to "%", 6 to "^", 7 to "&", 8 to "*", 9 to "(", 0 to ")")
    private val usShift = mapOf(1 to "!", 2 to "@", 3 to "#", 4 to "$", 5 to "%", 6 to "^", 7 to "&", 8 to "*", 9 to "(", 0 to ")")

    private fun digits(shift: Map<Int, String>, withFn: Boolean) = (listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 0)).mapIndexed { i, d ->
        if (withFn) k("$d", Hid.digit(d), shift = shift[d], fn = Hid.f(i + 1), fnLabel = "F${i + 1}")
        else k("$d", Hid.digit(d), shift = shift[d])
    }

    private fun fnRow(): List<KeyDef> = buildList {
        add(k("Esc", Hid.ESC)); add(gap(1f))
        for (i in 1..12) {
            add(k("F$i", Hid.f(i)))
            if (i == 4 || i == 8) add(gap(0.5f))
        }
    }

    // ---------- UK compact (default): 5 rows, 15 units, Fn layer ----------
    private val ukCompact = KeyboardLayout(
        UK_COMPACT, "UK compact",
        listOf(
            listOf(k("Esc", Hid.ESC, fn = Hid.GRAVE, fnLabel = "`")) +
                digits(ukShift, withFn = true) +
                listOf(
                    k("-", Hid.MINUS, shift = "_", fn = Hid.f(11), fnLabel = "F11"),
                    k("=", Hid.EQUAL, shift = "+", fn = Hid.f(12), fnLabel = "F12"),
                    k("⌫", Hid.BKSP, 2f, fn = Hid.DEL, fnLabel = "Del"),
                ),
            listOf(k("Tab", Hid.TAB, 1.5f)) +
                "qwertyu".let(::letters) +
                listOf(
                    k("I", Hid.letter('i'), fn = Hid.INS, fnLabel = "Ins"),
                    k("O", Hid.letter('o')),
                    k("P", Hid.letter('p'), fn = Hid.PRTSC, fnLabel = "PrtSc"),
                    k("[", Hid.LBRACKET, shift = "{"), k("]", Hid.RBRACKET, shift = "}"),
                    gap(0.25f),
                    k("Enter", Hid.ENTER, 1.25f, span = 2),
                ),
            listOf(caps(1.75f)) + letters("asdfghjkl") +
                listOf(
                    k(";", Hid.SEMICOLON, shift = ":"), k("'", Hid.QUOTE, shift = "@"),
                    k("#", Hid.NONUS_HASH, shift = "~"),
                    gap(1.25f),   // under the tall Enter
                ),
            listOf(mod("Shift", Hid.M_LSHIFT, 2f), k("\\", Hid.NONUS_BACKSLASH, shift = "|")) +
                letters("zxcvbn") +
                listOf(
                    k("M", Hid.letter('m'), fn = Hid.MENU, fnLabel = "Menu"),
                    k(",", Hid.COMMA, shift = "<"), k(".", Hid.DOT, shift = ">"), k("/", Hid.SLASH, shift = "?"),
                    k("↑", Hid.UP, fn = Hid.PGUP, fnLabel = "PgUp"),
                    mod("⇧", Hid.M_RSHIFT, 1f),
                ),
            listOf(
                mod("Ctrl", Hid.M_LCTRL, 1.25f), mod("Win", Hid.M_LWIN, 1.25f), mod("Alt", Hid.M_LALT, 1.25f),
                mod("Fn", Hid.M_FN, 1.25f),
                k("", Hid.SPACE, 6f),
                mod("AltGr", Hid.M_RALT, 1f),
                k("←", Hid.LEFT, fn = Hid.HOME, fnLabel = "Home"),
                k("↓", Hid.DOWN, fn = Hid.PGDN, fnLabel = "PgDn"),
                k("→", Hid.RIGHT, fn = Hid.END, fnLabel = "End"),
            ),
        )
    )

    // ---------- Full (tenkeyless) UK / US, optional numpad ----------
    private fun navTop() = listOf(gap(0.25f), k("PrtSc", Hid.PRTSC), k("ScrLk", Hid.SCRLK), k("Pause", Hid.PAUSE))
    private fun nav1() = listOf(gap(0.25f), k("Ins", Hid.INS), k("Home", Hid.HOME), k("PgUp", Hid.PGUP))
    private fun nav2() = listOf(gap(0.25f), k("Del", Hid.DEL), k("End", Hid.END), k("PgDn", Hid.PGDN))
    private fun nav3() = listOf(gap(3.25f))
    private fun nav4() = listOf(gap(1.25f), k("↑", Hid.UP), gap(1f))
    private fun nav5() = listOf(gap(0.25f), k("←", Hid.LEFT), k("↓", Hid.DOWN), k("→", Hid.RIGHT))

    private fun bottomRow() = listOf(
        mod("Ctrl", Hid.M_LCTRL, 1.25f), mod("Win", Hid.M_LWIN, 1.25f), mod("Alt", Hid.M_LALT, 1.25f),
        k("", Hid.SPACE, 6.25f),
        mod("AltGr", Hid.M_RALT, 1.25f), mod("Win", Hid.M_RWIN, 1.25f), k("Menu", Hid.MENU, 1.25f),
        mod("Ctrl", Hid.M_RCTRL, 1.25f),
    )

    private fun ukMain(): List<List<KeyDef>> = listOf(
        listOf(k("`", Hid.GRAVE, shift = "¬")) + digits(ukShift, false) +
            listOf(k("-", Hid.MINUS, shift = "_"), k("=", Hid.EQUAL, shift = "+"), k("⌫", Hid.BKSP, 2f)),
        listOf(k("Tab", Hid.TAB, 1.5f)) + letters("qwertyuiop") +
            listOf(k("[", Hid.LBRACKET, shift = "{"), k("]", Hid.RBRACKET, shift = "}"), gap(0.25f), k("Enter", Hid.ENTER, 1.25f, span = 2)),
        listOf(caps(1.75f)) + letters("asdfghjkl") +
            listOf(k(";", Hid.SEMICOLON, shift = ":"), k("'", Hid.QUOTE, shift = "@"), k("#", Hid.NONUS_HASH, shift = "~"), gap(1.25f)),
        listOf(mod("Shift", Hid.M_LSHIFT, 1.25f), k("\\", Hid.NONUS_BACKSLASH, shift = "|")) + letters("zxcvbnm") +
            listOf(k(",", Hid.COMMA, shift = "<"), k(".", Hid.DOT, shift = ">"), k("/", Hid.SLASH, shift = "?"), mod("Shift", Hid.M_RSHIFT, 2.75f)),
        bottomRow(),
    )

    private fun usMain(): List<List<KeyDef>> = listOf(
        listOf(k("`", Hid.GRAVE, shift = "~")) + digits(usShift, false) +
            listOf(k("-", Hid.MINUS, shift = "_"), k("=", Hid.EQUAL, shift = "+"), k("⌫", Hid.BKSP, 2f)),
        listOf(k("Tab", Hid.TAB, 1.5f)) + letters("qwertyuiop") +
            listOf(k("[", Hid.LBRACKET, shift = "{"), k("]", Hid.RBRACKET, shift = "}"), k("\\", Hid.BACKSLASH, 1.5f, shift = "|")),
        listOf(caps(1.75f)) + letters("asdfghjkl") +
            listOf(k(";", Hid.SEMICOLON, shift = ":"), k("'", Hid.QUOTE, shift = "\""), k("Enter", Hid.ENTER, 2.25f)),
        listOf(mod("Shift", Hid.M_LSHIFT, 2.25f)) + letters("zxcvbnm") +
            listOf(k(",", Hid.COMMA, shift = "<"), k(".", Hid.DOT, shift = ">"), k("/", Hid.SLASH, shift = "?"), mod("Shift", Hid.M_RSHIFT, 2.75f)),
        bottomRow(),
    )

    private fun numpad(): List<List<KeyDef>> = listOf(
        listOf(gap(4.25f)),
        listOf(gap(0.25f), k("Num", Hid.NUMLK), k("/", Hid.KP_SLASH), k("*", Hid.KP_STAR), k("-", Hid.KP_MINUS)),
        listOf(gap(0.25f), k("7", Hid.kp(7)), k("8", Hid.kp(8)), k("9", Hid.kp(9)), k("+", Hid.KP_PLUS, span = 2)),
        listOf(gap(0.25f), k("4", Hid.kp(4)), k("5", Hid.kp(5)), k("6", Hid.kp(6)), gap(1f)),
        listOf(gap(0.25f), k("1", Hid.kp(1)), k("2", Hid.kp(2)), k("3", Hid.kp(3)), k("Ent", Hid.KP_ENTER, span = 2)),
        listOf(gap(0.25f), k("0", Hid.kp(0), 2f), k(".", Hid.KP_DOT), gap(1f)),
    )

    private fun full(id: String, title: String, main: List<List<KeyDef>>, withNumpad: Boolean): KeyboardLayout {
        val nav = listOf(navTop(), nav1(), nav2(), nav3(), nav4(), nav5())
        val left = listOf(fnRow()) + main
        val pad = if (withNumpad) numpad() else List(6) { emptyList() }
        return KeyboardLayout(id, title, left.indices.map { left[it] + nav[it] + pad[it] })
    }

    val all: List<KeyboardLayout> = listOf(
        ukCompact,
        full(UK_FULL, "UK full", ukMain(), false),
        full(US_FULL, "US full", usMain(), false),
        full(UK_FULL_NUMPAD, "UK full + numpad", ukMain(), true),
    )

    fun byId(id: String?): KeyboardLayout = all.firstOrNull { it.id == id } ?: ukCompact
}
