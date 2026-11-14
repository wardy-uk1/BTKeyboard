package com.wardy.btkeyboard

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.URLSpan
import android.widget.ScrollView
import android.widget.TextView

/** About page: version, quick help, What's new (copied from the README at build time) and licence. */
object AboutDialog {

    private const val LICENCE_URL = "https://www.gnu.org/licenses/gpl-3.0.html"

    fun show(activity: Activity) {
        val dp = activity.resources.displayMetrics.density
        val version = try {
            activity.packageManager.getPackageInfo(activity.packageName, 0).versionName
        } catch (_: Exception) { "?" }

        val t = SpannableStringBuilder()
        fun title(s: String) = t.span(s + "\n", StyleSpan(Typeface.BOLD), RelativeSizeSpan(1.4f))
        fun heading(s: String) = t.append("\n").span(s.uppercase() + "\n", StyleSpan(Typeface.BOLD), ForegroundColorSpan(KeyboardView.ACCENT))
        fun line(s: String) = t.append(s).append("\n")

        title(activity.getString(R.string.app_name))
        line("Version $version")
        line("Turns your phone into a full-screen Bluetooth keyboard for Windows. The PC sees it as a normal keyboard, so there's nothing to install on it.")

        heading("Quick help")
        line("• Pairing: tap PC › Pair a new PC…, then on Windows go to Settings › Bluetooth & devices › Add device › Bluetooth and pick this phone. After that it reconnects by itself when you open the app.")
        line("• Layouts: tap Layout to switch between UK compact, UK full, US full and UK full + numpad. Set Windows to English (United Kingdom) for the UK layouts so keys type what they show.")
        line("• Fn (UK compact): hold or tap Fn for the blue legends: F1–F12 on the number row, Del on Backspace, Home/End/PgUp/PgDn on the arrows, Ins on I, PrtSc on P, Menu on M, ` on Esc.")
        line("• Shift, Ctrl, Alt, Win, AltGr, Fn: hold them like a real keyboard, or tap once to apply to the next key (blue outline). Double-tap to lock (solid blue), tap again to release.")
        line("• Caps Lock: a green dot on the key shows it's on.")
        line("• Gaming (LED): pick it under Layout. Change the lighting in ⚙ settings › Lighting. Slide a finger between keys without lifting.")
        line("• Controller: pick Classic, Twin Stick or Neon RGB under Layout. Windows sees a standard game controller; in Steam it works in any game, and Steam can make it act like an Xbox controller.")
        line("• Slow keys? Make sure Low-latency mode is on in ⚙ settings.")

        heading("What's new")
        val notes = try {
            activity.assets.open("whatsnew.md").bufferedReader().use { it.readText() }
        } catch (_: Exception) { "" }
        if (notes.isBlank()) line("See the GitHub page for release notes.")
        notes.lines().forEach { raw ->
            val l = raw.replace("**", "")
            when {
                l.startsWith("### ") -> t.append("\n").span(l.removePrefix("### ") + "\n", StyleSpan(Typeface.BOLD))
                l.startsWith("- ") -> line("• " + l.removePrefix("- "))
                l.isNotBlank() -> line(l)
            }
        }

        heading("Licence")
        line("Free software under the GNU General Public License v3.0 (GPL-3.0).")
        t.span("Read the licence", URLSpan(LICENCE_URL)).append("\n")

        val text = TextView(activity).apply {
            setText(t)
            textSize = 14f
            setTextColor(0xFFD0D6DE.toInt())
            setLinkTextColor(KeyboardView.ACCENT)
            movementMethod = LinkMovementMethod.getInstance()
            setLineSpacing(0f, 1.15f)
            val p = (20 * dp).toInt()
            setPadding(p, (12 * dp).toInt(), p, p)
        }
        AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setView(ScrollView(activity).apply { addView(text) })
            .setPositiveButton("Close", null)
            .show()
    }

    private fun SpannableStringBuilder.span(s: String, vararg spans: Any): SpannableStringBuilder {
        val start = length
        append(s)
        spans.forEach { setSpan(it, start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        return this
    }
}
