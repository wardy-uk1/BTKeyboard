package com.wardy.btkeyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.media.AudioManager
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

class KeyboardView(context: Context) : View(context) {

    var engine: KeyEngine? = null
    var hapticsEnabled = true
    var soundEnabled = false
    var capsLockOn = false
        set(v) { field = v; invalidate() }

    /** Lighting for gaming layouts. */
    enum class RgbEffect(val title: String) { WAVE("Rainbow wave"), RAINBOW("Static rainbow"), REACTIVE("Reactive (lights up when pressed)"), SOLID("Solid cyan") }
    var rgbEffect = RgbEffect.WAVE
        set(v) { field = v; invalidate() }
    private val pressTimes = HashMap<PlacedKey, Long>()
    private val hsv = FloatArray(3)

    var layout: KeyboardLayout = Layouts.byId(null)
        set(v) { engine?.releaseAll(); field = v; pointerKeys.clear(); computeKeys(); invalidate() }

    private class PlacedKey(val def: KeyDef, val rect: RectF)
    private val keys = ArrayList<PlacedKey>()
    /** pointer id -> (key, code actually sent) so release always matches the press even if Fn changed. */
    private val pointerKeys = HashMap<Int, Pair<PlacedKey, Int>>()

    private val dp = resources.displayMetrics.density
    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * dp }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF9AA4B2.toInt(); textAlign = Paint.Align.CENTER }
    private val fnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT; textAlign = Paint.Align.RIGHT }
    private val audio = context.getSystemService(AudioManager::class.java)

    init {
        setBackgroundColor(0xFF121418.toInt())
        isHapticFeedbackEnabled = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        computeKeys()
        // Stop the system back gesture firing when pressing keys at the screen edges (bottom 200dp is the max allowed).
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val ex = (200 * dp).toInt()
            val edge = (24 * dp).toInt()
            systemGestureExclusionRects = listOf(Rect(0, h - ex, edge, h), Rect(w - edge, h - ex, w, h))
        }
    }

    private fun computeKeys() {
        keys.clear()
        if (width == 0 || height == 0) return
        val rows = layout.rows
        val units = rows.maxOf { r -> r.sumOf { it.width.toDouble() } }.toFloat()
        val pad = 4 * dp
        val unitW = (width - 2 * pad) / units
        val rowH = (height - 2 * pad) / rows.size
        val gap = 3 * dp
        rows.forEachIndexed { ri, row ->
            var x = pad
            val y = pad + ri * rowH
            for (def in row) {
                val w = def.width * unitW
                if (def.kind != KeyKind.SPACER) {
                    keys += PlacedKey(def, RectF(x + gap / 2, y + gap / 2, x + w - gap / 2, y + rowH * def.rowSpan - gap / 2))
                }
                x += w
            }
        }
        val baseRowH = rowH
        textPaint.textSize = min(baseRowH * 0.34f, unitW * 0.42f)
        subPaint.textSize = textPaint.textSize * 0.62f
        fnPaint.textSize = textPaint.textSize * 0.5f
    }

    override fun onDraw(canvas: Canvas) {
        if (layout.gaming) drawRgb(canvas) else drawNormal(canvas)
    }

    // ---------- LED gaming look ----------
    private val rgbFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rgbStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rgbText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }

    private fun hueColor(h: Float, v: Float = 1f): Int {
        hsv[0] = ((h % 360f) + 360f) % 360f; hsv[1] = 0.85f; hsv[2] = v
        return Color.HSVToColor(hsv)
    }

    private fun drawRgb(canvas: Canvas) {
        canvas.drawColor(0xFF050608.toInt())
        val e = engine
        val now = android.os.SystemClock.uptimeMillis()
        val t = now / 1000f
        val held = pointerKeys.values.map { it.first }.toSet()
        val fn = e?.fnActive == true
        val shift = e?.shiftActive == true
        var animating = rgbEffect == RgbEffect.WAVE
        rgbStroke.strokeWidth = 1.6f * dp
        rgbText.textSize = textPaint.textSize
        for (pk in keys) {
            val d = pk.def; val r = pk.rect
            val radius = min(r.height(), r.width()) * 0.16f
            val pos = r.centerX() / width.coerceAtLeast(1) * 300f + r.centerY() / height.coerceAtLeast(1) * 40f
            // Glow strength 0..1 and colour per effect.
            var level = 1f
            val color = when (rgbEffect) {
                RgbEffect.WAVE -> hueColor(pos - t * 120f)
                RgbEffect.RAINBOW -> hueColor(pos)
                RgbEffect.SOLID -> 0xFF3BE8FF.toInt()
                RgbEffect.REACTIVE -> {
                    val since = pressTimes[pk]?.let { now - it } ?: Long.MAX_VALUE
                    level = when {
                        pk in held -> 1f
                        since < FADE_MS -> { animating = true; 1f - since / FADE_MS.toFloat() }
                        else -> 0f
                    }
                    hueColor(pos)
                }
            }
            val isDown = pk in held
            val modState = if (d.kind == KeyKind.MODIFIER && e != null) when {
                e.locked and d.modBit != 0 -> 2; e.latched and d.modBit != 0 -> 1; else -> 0 } else 0
            val glow = maxOf(level, if (modState > 0) 1f else 0f)

            rgbFill.color = when {
                isDown || modState == 2 -> blend(0xFF0A0B10.toInt(), color, 0.55f)
                else -> blend(0xFF0A0B10.toInt(), color, 0.08f * glow)
            }
            canvas.drawRoundRect(r, radius, radius, rgbFill)

            val edge = if (glow > 0.01f) blend(0xFF1C2230.toInt(), color, glow) else 0xFF1C2230.toInt()
            rgbStroke.color = edge
            if (glow > 0.01f) rgbStroke.setShadowLayer(7 * dp * glow, 0f, 0f, edge) else rgbStroke.clearShadowLayer()
            rgbStroke.strokeWidth = (if (modState == 1) 2.6f else 1.6f) * dp
            canvas.drawRoundRect(r, radius, radius, rgbStroke)

            val legend = when {
                isDown -> Color.WHITE
                glow > 0.01f -> blend(0xFF7D8BA0.toInt(), blend(color, Color.WHITE, 0.25f), glow)
                else -> 0xFF7D8BA0.toInt()
            }
            if (glow > 0.3f && !isDown) rgbText.setShadowLayer(5 * dp * glow, 0f, 0f, color) else rgbText.clearShadowLayer()
            val label = when {
                fn && d.fnLabel != null -> d.fnLabel
                shift && d.shiftLabel != null -> d.shiftLabel
                else -> d.label
            }
            rgbText.color = legend
            rgbText.textSize = if (label.length > 2) subPaint.textSize else textPaint.textSize
            canvas.drawText(label, r.centerX(), r.centerY() - (rgbText.descent() + rgbText.ascent()) / 2, rgbText)
            if (!fn && d.fnLabel != null) {
                fnPaint.color = blend(0xFF55627A.toInt(), color, glow * 0.8f)
                canvas.drawText(d.fnLabel, r.right - 4 * dp, r.bottom - 4 * dp, fnPaint)
            }
            if (d.isCaps && capsLockOn) {
                rgbFill.color = Color.WHITE
                canvas.drawCircle(r.left + 8 * dp, r.top + 8 * dp, 3.5f * dp, rgbFill)
            }
        }
        if (animating) postInvalidateDelayed(FRAME_MS)
    }

    private fun blend(a: Int, b: Int, f: Float): Int {
        val k = f.coerceIn(0f, 1f)
        fun ch(sh: Int) = (((a shr sh) and 0xFF) * (1 - k) + ((b shr sh) and 0xFF) * k).toInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    // ---------- normal look ----------
    private fun drawNormal(canvas: Canvas) {
        val e = engine
        val pressedKeys = pointerKeys.values.map { it.first }.toSet()
        val fn = e?.fnActive == true
        val shift = e?.shiftActive == true
        for (pk in keys) {
            val d = pk.def
            val r = pk.rect
            val radius = min(r.height(), r.width()) * 0.14f
            val isDown = pk in pressedKeys
            val modState = when {
                d.kind != KeyKind.MODIFIER || e == null -> 0
                e.locked and d.modBit != 0 -> 2
                e.latched and d.modBit != 0 -> 1
                else -> 0
            }
            keyPaint.color = when {
                isDown -> ACCENT
                modState == 2 -> 0xFF1565C0.toInt()
                d.kind == KeyKind.MODIFIER || d.label.length > 1 && d.code != Hid.SPACE -> 0xFF22262D.toInt()
                else -> 0xFF2E333B.toInt()
            }
            canvas.drawRoundRect(r, radius, radius, keyPaint)
            if (modState == 1) {
                strokePaint.color = ACCENT
                canvas.drawRoundRect(r, radius, radius, strokePaint)
            }

            val cx = r.centerX()
            val showFn = fn && d.fnLabel != null
            when {
                showFn -> drawCentered(canvas, d.fnLabel!!, cx, r, textPaint, ACCENT)
                d.shiftLabel != null -> {
                    // Two-legend keycap: shifted symbol on top, normal below; the active one is brighter.
                    val top = r.top + r.height() * 0.38f
                    val bottom = r.top + r.height() * 0.78f
                    textPaint.color = if (shift) Color.WHITE else 0xFF9AA4B2.toInt()
                    canvas.drawText(d.shiftLabel, cx, top, textPaint)
                    textPaint.color = if (shift) 0xFF9AA4B2.toInt() else Color.WHITE
                    canvas.drawText(d.label, cx, bottom, textPaint)
                    textPaint.color = Color.WHITE
                }
                else -> {
                    val small = d.label.length > 2
                    val p = if (small) subPaint else textPaint
                    val c = if (small) 0xFFD0D6DE.toInt() else Color.WHITE
                    drawCentered(canvas, d.label, cx, r, p, c)
                }
            }
            if (!fn && d.fnLabel != null) {
                canvas.drawText(d.fnLabel, r.right - 4 * dp, r.bottom - 4 * dp, fnPaint)
            }
            if (d.isCaps && capsLockOn) {
                keyPaint.color = 0xFF4CAF50.toInt()
                canvas.drawCircle(r.left + 8 * dp, r.top + 8 * dp, 3.5f * dp, keyPaint)
            }
        }
    }

    private fun drawCentered(c: Canvas, text: String, cx: Float, r: RectF, p: Paint, color: Int) {
        val old = p.color
        p.color = color
        val y = r.centerY() - (p.descent() + p.ascent()) / 2
        c.drawText(text, cx, y, p)
        p.color = old
    }

    private fun keyAt(x: Float, y: Float): PlacedKey? {
        keys.firstOrNull { it.rect.contains(x, y) }?.let { return it }
        // Touch landed in a gap: take the nearest key if it's close.
        val best = keys.minByOrNull { hypot(it.rect.centerX() - x, it.rect.centerY() - y) } ?: return null
        val r = best.rect
        val dx = maxOf(r.left - x, 0f, x - r.right)
        val dy = maxOf(r.top - y, 0f, y - r.bottom)
        return if (hypot(dx, dy) < 12 * dp) best else null
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val e = engine ?: return true
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = ev.actionIndex
                val pk = keyAt(ev.getX(i), ev.getY(i)) ?: return true
                val d = pk.def
                val code = if (d.kind == KeyKind.MODIFIER) 0 else if (e.fnActive && d.fnCode != null) d.fnCode else d.code
                pointerKeys[ev.getPointerId(i)] = pk to code
                pressTimes[pk] = android.os.SystemClock.uptimeMillis()
                if (d.kind == KeyKind.MODIFIER) e.modDown(d.modBit) else e.keyDown(code)
                feedback(d)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> if (layout.gaming) slide(ev, e)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                release(ev.getPointerId(ev.actionIndex))
            }
            MotionEvent.ACTION_CANCEL -> {
                pointerKeys.keys.toList().forEach { release(it) }
            }
        }
        return true
    }

    /** Gaming layouts: a finger rolling onto another key releases the old key and presses the new one. */
    private fun slide(ev: MotionEvent, e: KeyEngine) {
        for (i in 0 until ev.pointerCount) {
            val id = ev.getPointerId(i)
            val (cur, _) = pointerKeys[id] ?: continue
            if (cur.def.kind == KeyKind.MODIFIER || cur.rect.contains(ev.getX(i), ev.getY(i))) continue
            val next = keys.firstOrNull { it.rect.contains(ev.getX(i), ev.getY(i)) } ?: continue
            if (next.def.kind == KeyKind.MODIFIER) continue
            release(id)
            val code = if (e.fnActive && next.def.fnCode != null) next.def.fnCode else next.def.code
            pointerKeys[id] = next to code
            pressTimes[next] = android.os.SystemClock.uptimeMillis()
            e.keyDown(code)
            feedback(next.def)
            invalidate()
        }
    }

    private fun release(pointerId: Int) {
        val e = engine ?: return
        val (pk, code) = pointerKeys.remove(pointerId) ?: return
        if (pk.def.kind == KeyKind.MODIFIER) e.modUp(pk.def.modBit) else e.keyUp(code)
        invalidate()
    }

    fun releaseAll() {
        pointerKeys.clear()
        engine?.releaseAll()
        invalidate()
    }

    private fun feedback(d: KeyDef) {
        if (hapticsEnabled) {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING)
        }
        if (soundEnabled) {
            val fx = when (d.code) {
                Hid.SPACE -> AudioManager.FX_KEYPRESS_SPACEBAR
                Hid.BKSP -> AudioManager.FX_KEYPRESS_DELETE
                Hid.ENTER -> AudioManager.FX_KEYPRESS_RETURN
                else -> AudioManager.FX_KEYPRESS_STANDARD
            }
            audio.playSoundEffect(fx, -1f)
        }
    }

    companion object {
        val ACCENT = 0xFF1E88E5.toInt()
        private const val FADE_MS = 700L
        private const val FRAME_MS = 33L   // ~30 fps lighting animation
    }
}
