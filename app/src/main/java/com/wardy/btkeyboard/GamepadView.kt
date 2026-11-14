package com.wardy.btkeyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/** On-screen game controller with three skins. Sends [GamepadState] whenever anything changes. */
class GamepadView(context: Context) : View(context) {

    enum class Skin(val id: String, val title: String) {
        CLASSIC("pad_classic", "Controller – Classic"),
        TWIN("pad_twin", "Controller – Twin Stick"),
        NEON("pad_neon", "Controller – Neon RGB");
        companion object { fun byId(id: String?) = entries.firstOrNull { it.id == id } }
    }

    private enum class Kind { BUTTON, TRIGGER, DPAD, STICK }

    /** Positions are in the 844 × 356 design space of the mockups and scaled to the screen. */
    private class Elem(
        val kind: Kind, val label: String, val cx: Float, val cy: Float, val w: Float, val h: Float,
        val bit: Int = 0, val trigger: Int = 0, val left: Boolean = true,
        val textColor: Int = 0, val glow: Int = 0, val circle: Boolean = false, val home: Boolean = false,
    ) {
        val rect = RectF(); var cxS = 0f; var cyS = 0f; var rS = 0f   // screen geometry
    }

    var onState: ((GamepadState) -> Unit)? = null
    var hapticsEnabled = true
    var skin = Skin.CLASSIC
        set(v) { field = v; releaseAll(); elems = build(v); layoutElems(); invalidate() }

    private var elems = build(skin)
    private val dp = resources.displayMetrics.density

    // Touch state
    private val pointerElem = HashMap<Int, Elem>()
    private val stickOffset = HashMap<Elem, Pair<Float, Float>>()   // normalised -1..1
    private var hat = GamepadState.HAT_NONE
    private var last = GamepadState.NEUTRAL

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }

    // ---------- skins (from the mockups) ----------
    private fun build(s: Skin): List<Elem> {
        val yel = 0xFFF5C518.toInt(); val blu = 0xFF4FA3F7.toInt(); val red = 0xFFFF6B6B.toInt(); val grn = 0xFF5FD068.toInt()
        return when (s) {
            Skin.CLASSIC, Skin.NEON -> {
                val n = s == Skin.NEON
                listOf(
                    Elem(Kind.TRIGGER, "LT", 80f, 30f, 132f, 40f, trigger = 1, glow = 0xFFFF3BD4.toInt()),
                    Elem(Kind.BUTTON, "LB", 220f, 30f, 132f, 40f, bit = GamepadState.LB, glow = 0xFF9B5CFF.toInt()),
                    Elem(Kind.BUTTON, "RB", 624f, 30f, 132f, 40f, bit = GamepadState.RB, glow = 0xFF3B8BFF.toInt()),
                    Elem(Kind.TRIGGER, "RT", 764f, 30f, 132f, 40f, trigger = 2, glow = 0xFF3BE8FF.toInt()),
                    Elem(Kind.STICK, "", 130f, 150f, 160f, 160f, left = true),
                    Elem(Kind.DPAD, "", 280f, 268f, 132f, 132f, glow = 0xFF39FF88.toInt()),
                    Elem(Kind.BUTTON, "View", 370f, 142f, 44f, 30f, bit = GamepadState.VIEW, glow = 0xFF8A97AD.toInt()),
                    Elem(Kind.BUTTON, "", 422f, 142f, 52f, 52f, bit = GamepadState.HOME, circle = true, home = true,
                        textColor = if (n) 0xFFFFE53B.toInt() else 0xFF4CAF50.toInt(), glow = 0xFFFFE53B.toInt()),
                    Elem(Kind.BUTTON, "Menu", 474f, 142f, 44f, 30f, bit = GamepadState.MENU, glow = 0xFF8A97AD.toInt()),
                    Elem(Kind.BUTTON, "Y", 738f, 94f, 58f, 58f, bit = GamepadState.Y, circle = true, textColor = if (n) 0xFFFFE53B.toInt() else yel, glow = 0xFFFFE53B.toInt()),
                    Elem(Kind.BUTTON, "X", 680f, 152f, 58f, 58f, bit = GamepadState.X, circle = true, textColor = if (n) 0xFF9CC4FF.toInt() else blu, glow = 0xFF3B8BFF.toInt()),
                    Elem(Kind.BUTTON, "B", 796f, 152f, 58f, 58f, bit = GamepadState.B, circle = true, textColor = if (n) 0xFFFF9AAD.toInt() else red, glow = 0xFFFF4D6D.toInt()),
                    Elem(Kind.BUTTON, "A", 738f, 210f, 58f, 58f, bit = GamepadState.A, circle = true, textColor = if (n) 0xFF39FF88.toInt() else grn, glow = 0xFF39FF88.toInt()),
                    Elem(Kind.STICK, "", 568f, 270f, 136f, 136f, left = false),
                )
            }
            Skin.TWIN -> listOf(
                Elem(Kind.TRIGGER, "LT", 40f, 87f, 56f, 150f, trigger = 1),
                Elem(Kind.BUTTON, "LB", 136f, 34f, 120f, 44f, bit = GamepadState.LB),
                Elem(Kind.BUTTON, "RB", 708f, 34f, 120f, 44f, bit = GamepadState.RB),
                Elem(Kind.TRIGGER, "RT", 804f, 87f, 56f, 150f, trigger = 2),
                Elem(Kind.DPAD, "", 157f, 123f, 114f, 114f),
                Elem(Kind.BUTTON, "Y", 684f, 86f, 46f, 46f, bit = GamepadState.Y, circle = true, textColor = yel),
                Elem(Kind.BUTTON, "X", 640f, 130f, 46f, 46f, bit = GamepadState.X, circle = true, textColor = blu),
                Elem(Kind.BUTTON, "B", 728f, 130f, 46f, 46f, bit = GamepadState.B, circle = true, textColor = 0xFFFF7A7A.toInt()),
                Elem(Kind.BUTTON, "A", 684f, 174f, 46f, 46f, bit = GamepadState.A, circle = true, textColor = grn),
                Elem(Kind.BUTTON, "", 422f, 111f, 50f, 50f, bit = GamepadState.HOME, circle = true, home = true, textColor = 0xFF7FB2FF.toInt()),
                Elem(Kind.BUTTON, "View", 390f, 163f, 56f, 30f, bit = GamepadState.VIEW),
                Elem(Kind.BUTTON, "Menu", 456f, 163f, 60f, 30f, bit = GamepadState.MENU),
                Elem(Kind.STICK, "", 290f, 264f, 168f, 168f, left = true),
                Elem(Kind.STICK, "", 554f, 264f, 168f, 168f, left = false),
            )
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        layoutElems()
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val ex = (200 * dp).toInt(); val edge = (24 * dp).toInt()
            systemGestureExclusionRects = listOf(android.graphics.Rect(0, h - ex, edge, h), android.graphics.Rect(w - edge, h - ex, w, h))
        }
    }

    private fun layoutElems() {
        if (width == 0 || height == 0) return
        val sx = width / 844f; val sy = height / 356f; val s = min(sx, sy)
        for (e in elems) {
            e.cxS = e.cx * sx; e.cyS = e.cy * sy
            val hw = e.w * s / 2; val hh = e.h * s / 2
            e.rect.set(e.cxS - hw, e.cyS - hh, e.cxS + hw, e.cyS + hh)
            e.rS = min(hw, hh)
        }
        text.textSize = 20 * s
    }

    // ---------- drawing ----------
    private val bg get() = when (skin) { Skin.CLASSIC -> 0xFF16181D; Skin.TWIN -> 0xFF1B2230; Skin.NEON -> 0xFF07080C }.toInt()
    private val baseFill get() = when (skin) { Skin.CLASSIC -> 0xFF2B2F37; Skin.TWIN -> 0xFF2A3550; Skin.NEON -> 0xFF0E0F15 }.toInt()
    private val baseText get() = when (skin) { Skin.CLASSIC -> 0xFFE6E9EE; Skin.TWIN -> 0xFFE8EDF5; Skin.NEON -> 0xFFEAF6FF }.toInt()

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(bg)
        val pressed = pointerElem.values.toSet()
        for (e in elems) when (e.kind) {
            Kind.BUTTON, Kind.TRIGGER -> drawButton(canvas, e, e in pressed)
            Kind.DPAD -> drawDpad(canvas, e)
            Kind.STICK -> drawStick(canvas, e)
        }
    }

    private fun neonStroke(color: Int, on: Boolean) {
        stroke.color = color
        stroke.strokeWidth = 2 * dp
        stroke.setShadowLayer((if (on) 14 else 8) * dp, 0f, 0f, color)
    }

    private fun drawButton(c: Canvas, e: Elem, down: Boolean) {
        val r = e.rect
        val radius = if (e.circle) r.width() / 2 else when (skin) { Skin.TWIN -> min(r.width(), r.height()) / 2; else -> 10 * dp }
        val twinFilled = skin == Skin.TWIN && e.circle && !e.home
        fill.color = when {
            down && skin == Skin.NEON -> blend(0xFF0E0F15.toInt(), e.glow, 0.5f)
            down -> blend(if (twinFilled) e.textColor else baseFill, Color.WHITE, 0.3f)
            twinFilled -> e.textColor
            else -> baseFill
        }
        c.drawRoundRect(r, radius, radius, fill)
        if (skin == Skin.NEON) { neonStroke(e.glow, down); c.drawRoundRect(r, radius, radius, stroke); stroke.clearShadowLayer() }
        if (e.home) { drawHouse(c, e); return }
        text.color = when {
            twinFilled -> 0xFF1B2230.toInt()
            e.textColor != 0 -> e.textColor
            skin == Skin.NEON -> blend(e.glow, Color.WHITE, 0.4f)
            else -> baseText
        }
        val size = text.textSize
        if (e.label.length > 2) text.textSize = size * 0.62f
        c.drawText(e.label, r.centerX(), r.centerY() - (text.descent() + text.ascent()) / 2, text)
        text.textSize = size
    }

    private fun drawHouse(c: Canvas, e: Elem) {
        val s = e.rS * 0.5f; val x = e.cxS; val y = e.cyS
        val p = Path().apply {
            moveTo(x - s, y - s * 0.05f); lineTo(x, y - s * 0.85f); lineTo(x + s, y - s * 0.05f)
            lineTo(x + s * 0.75f, y - s * 0.05f); lineTo(x + s * 0.75f, y + s * 0.8f); lineTo(x - s * 0.75f, y + s * 0.8f)
            lineTo(x - s * 0.75f, y - s * 0.05f); close()
        }
        stroke.clearShadowLayer(); stroke.color = e.textColor; stroke.strokeWidth = 2 * dp
        c.drawPath(p, stroke)
        if (skin != Skin.NEON) { stroke.strokeWidth = 2 * dp; c.drawCircle(x, y, e.rS - dp, stroke) }
    }

    private fun drawDpad(c: Canvas, e: Elem) {
        val r = e.rect; val third = r.width() / 3
        val arms = listOf(
            Triple(RectF(r.left + third, r.top, r.right - third, r.top + third * 1.1f), "▲", intArrayOf(7, 0, 1)),
            Triple(RectF(r.left + third, r.bottom - third * 1.1f, r.right - third, r.bottom), "▼", intArrayOf(3, 4, 5)),
            Triple(RectF(r.left, r.top + third, r.left + third * 1.1f, r.bottom - third), "◀", intArrayOf(5, 6, 7)),
            Triple(RectF(r.right - third * 1.1f, r.top + third, r.right, r.bottom - third), "▶", intArrayOf(1, 2, 3)),
        )
        val neon = skin == Skin.NEON
        val col = if (neon) e.glow else 0xFFC4CAD3.toInt()
        for ((rect, arrow, dirs) in arms) {
            val on = hat in dirs
            fill.color = if (on) (if (neon) blend(baseFill, e.glow, 0.5f) else blend(baseFill, Color.WHITE, 0.3f)) else baseFill
            val rad = 8 * dp
            c.drawRoundRect(rect, rad, rad, fill)
            if (neon) { neonStroke(e.glow, on); c.drawRoundRect(rect, rad, rad, stroke); stroke.clearShadowLayer() }
            text.color = col
            val size = text.textSize; text.textSize = size * 0.7f
            c.drawText(arrow, rect.centerX(), rect.centerY() - (text.descent() + text.ascent()) / 2, text)
            text.textSize = size
        }
    }

    private fun drawStick(c: Canvas, e: Elem) {
        val (ox, oy) = stickOffset[e] ?: (0f to 0f)
        val r = e.rS
        val knobR = r * if (skin == Skin.TWIN) 0.39f else 0.44f
        val travel = r - knobR
        when (skin) {
            Skin.CLASSIC -> {
                fill.color = 0xFF1D2026.toInt(); c.drawCircle(e.cxS, e.cyS, r, fill)
                stroke.clearShadowLayer(); stroke.color = 0xFF3A3F49.toInt(); stroke.strokeWidth = 2 * dp
                c.drawCircle(e.cxS, e.cyS, r, stroke)
            }
            Skin.TWIN -> {
                fill.color = 0xFF222B40.toInt(); c.drawCircle(e.cxS, e.cyS, r, fill)
                stroke.clearShadowLayer(); stroke.color = 0xFF3A4766.toInt(); stroke.strokeWidth = 2 * dp
                stroke.pathEffect = DashPathEffect(floatArrayOf(6 * dp, 5 * dp), 0f)
                c.drawCircle(e.cxS, e.cyS, r * 0.79f, stroke)
                stroke.pathEffect = null
            }
            Skin.NEON -> {
                fill.color = 0xFF0A0B10.toInt(); c.drawCircle(e.cxS, e.cyS, r, fill)
                stroke.shader = SweepGradient(e.cxS, e.cyS, intArrayOf(
                    0xFFFF3BD4.toInt(), 0xFF9B5CFF.toInt(), 0xFF3B8BFF.toInt(), 0xFF3BE8FF.toInt(),
                    0xFF39FF88.toInt(), 0xFFFFE53B.toInt(), 0xFFFF7A3B.toInt(), 0xFFFF3BD4.toInt()), null)
                stroke.strokeWidth = 4 * dp
                stroke.setShadowLayer(10 * dp, 0f, 0f, 0xFF9B5CFF.toInt())
                c.drawCircle(e.cxS, e.cyS, r - 2 * dp, stroke)
                stroke.shader = null; stroke.clearShadowLayer()
            }
        }
        val kx = e.cxS + ox * travel; val ky = e.cyS + oy * travel
        fill.color = when (skin) { Skin.CLASSIC -> 0xFF434955; Skin.TWIN -> 0xFF3B4A6E; Skin.NEON -> 0xFF1A1D27 }.toInt()
        c.drawCircle(kx, ky, knobR, fill)
        if (skin == Skin.NEON) {
            neonStroke(if (e.left) 0xFF3BE8FF.toInt() else 0xFFFF3BD4.toInt(), stickOffset.containsKey(e))
            c.drawCircle(kx, ky, knobR, stroke); stroke.clearShadowLayer()
        }
    }

    private fun blend(a: Int, b: Int, f: Float): Int {
        fun ch(sh: Int) = (((a shr sh) and 0xFF) * (1 - f) + ((b shr sh) and 0xFF) * f).toInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    // ---------- touch ----------
    private fun hit(x: Float, y: Float): Elem? {
        // Sticks and the d-pad get generous targets; buttons a little slack.
        elems.firstOrNull { (it.kind == Kind.STICK || it.kind == Kind.DPAD) && hypot(x - it.cxS, y - it.cyS) < it.rS * 1.15f }?.let { return it }
        val slack = 8 * dp
        return elems.filter { it.kind == Kind.BUTTON || it.kind == Kind.TRIGGER }
            .firstOrNull { RectF(it.rect).apply { inset(-slack, -slack) }.contains(x, y) }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = ev.actionIndex
                val e = hit(ev.getX(i), ev.getY(i)) ?: return true
                pointerElem[ev.getPointerId(i)] = e
                track(e, ev.getX(i), ev.getY(i))
                if (hapticsEnabled) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING)
                update()
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until ev.pointerCount) {
                    val id = ev.getPointerId(i)
                    val e = pointerElem[id] ?: continue
                    if (e.kind == Kind.BUTTON || e.kind == Kind.TRIGGER) {
                        // Roll from one face button to another without lifting (A → B).
                        val n = hit(ev.getX(i), ev.getY(i))
                        if (n != null && n != e && (n.kind == Kind.BUTTON || n.kind == Kind.TRIGGER)) {
                            pointerElem[id] = n
                            if (hapticsEnabled) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING)
                        }
                    } else track(e, ev.getX(i), ev.getY(i))
                }
                update()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val e = pointerElem.remove(ev.getPointerId(ev.actionIndex))
                if (e?.kind == Kind.STICK) stickOffset.remove(e)
                if (e?.kind == Kind.DPAD) hat = GamepadState.HAT_NONE
                update()
            }
            MotionEvent.ACTION_CANCEL -> releaseAll()
        }
        return true
    }

    private fun track(e: Elem, x: Float, y: Float) {
        when (e.kind) {
            Kind.STICK -> {
                val knobR = e.rS * if (skin == Skin.TWIN) 0.39f else 0.44f
                val travel = (e.rS - knobR).coerceAtLeast(1f)
                var dx = (x - e.cxS) / travel; var dy = (y - e.cyS) / travel
                val m = hypot(dx, dy)
                if (m > 1f) { dx /= m; dy /= m }
                stickOffset[e] = dx to dy
            }
            Kind.DPAD -> {
                val dx = x - e.cxS; val dy = y - e.cyS
                hat = if (hypot(dx, dy) < e.rS * 0.2f) GamepadState.HAT_NONE else {
                    // 0 = up, clockwise in 45° steps
                    val deg = (Math.toDegrees(atan2(dx.toDouble(), -dy.toDouble())) + 360) % 360
                    ((deg + 22.5) / 45).toInt() % 8
                }
            }
            else -> {}
        }
    }

    private fun update() {
        var buttons = 0; var lt = 0; var rt = 0
        for (e in pointerElem.values) {
            if (e.kind == Kind.BUTTON) buttons = buttons or e.bit
            if (e.kind == Kind.TRIGGER) { if (e.trigger == 1) lt = 255 else rt = 255 }
        }
        var lx = 0; var ly = 0; var rx = 0; var ry = 0
        for ((e, o) in stickOffset) {
            val ax = (o.first * 127).roundToInt(); val ay = (o.second * 127).roundToInt()
            if (e.left) { lx = ax; ly = ay } else { rx = ax; ry = ay }
        }
        val s = GamepadState(buttons, hat, lx, ly, rx, ry, lt, rt)
        if (s != last) { last = s; onState?.invoke(s) }
        invalidate()
    }

    fun releaseAll() {
        pointerElem.clear(); stickOffset.clear(); hat = GamepadState.HAT_NONE
        update()
    }
}
