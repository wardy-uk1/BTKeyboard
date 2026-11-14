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
                if (d.kind == KeyKind.MODIFIER) e.modDown(d.modBit) else e.keyDown(code)
                feedback(d)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                release(ev.getPointerId(ev.actionIndex))
            }
            MotionEvent.ACTION_CANCEL -> {
                pointerKeys.keys.toList().forEach { release(it) }
            }
        }
        return true
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

    companion object { val ACCENT = 0xFF1E88E5.toInt() }
}
