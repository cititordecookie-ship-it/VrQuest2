package com.vrquest

import android.content.Context
import android.graphics.*
import android.os.BatteryManager
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

abstract class VrApp(val ctx: Context, val host: VrHost) {
    abstract val id: String
    abstract val title: String
    open val icon: String get() = "apps"
    open val pxW: Int get() = 1280
    open val pxH: Int get() = 720
    open val widthM: Float get() = 1.9f
    open val singleton: Boolean get() = true
    abstract fun build(): View
    open fun onClose() {}
}

fun tv(ctx: Context, text: String, size: Float = 30f, color: Int = Theme.text, bold: Boolean = false): TextView =
    TextView(ctx).apply { this.text = text; setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size); setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD }

fun button(ctx: Context, text: String, size: Float = 30f, bg: Int = Theme.card, fg: Int = Theme.text, onTap: () -> Unit): TextView =
    TextView(ctx).apply {
        this.text = text; setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size); setTextColor(fg); gravity = Gravity.CENTER
        background = rounded(bg, 24f); setPadding(28, 14, 28, 14); setOnClickListener { Sfx.click(); onTap() }
    }

/** Wraps an app view with a Quest-style title bar (drag area + curve/pin/TV/close buttons) and a resize grip. */
class WindowFrame(ctx: Context, app: VrApp, val panelRef: () -> Panel?, val scene: VrScene, content: View) : FrameLayout(ctx) {
    companion object { const val TITLE = 72 }
    init {
        background = rounded(Theme.bg, 0f)
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val bar = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(Theme.bar); setPadding(14, 0, 8, 0) }
        bar.addView(object : View(ctx) { override fun onDraw(c: Canvas) { Icons.draw(c, app.icon, width / 2f, height / 2f, 20f, Theme.accent) } }, LinearLayout.LayoutParams(52, TITLE))
        bar.addView(tv(ctx, app.title, 30f, Theme.text, true).apply { setPadding(10, 0, 0, 0) }, LinearLayout.LayoutParams(0, TITLE, 1f).apply { }.also { })
        (bar.getChildAt(1) as TextView).gravity = Gravity.CENTER_VERTICAL
        fun add(kind: String, f: (Panel) -> Unit) = bar.addView(IconBtn(ctx, kind, Theme.text) { panelRef()?.let(f) }, LinearLayout.LayoutParams(76, TITLE))
        add("curve") { scene.toggleCurve(it) }
        add("pin") { it.pinned = !it.pinned }
        add("tv") { scene.toggleTv(it) }
        add("close") { scene.requestClose(it) }
        col.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, TITLE))
        col.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(col, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(object : View(ctx) {
            override fun onDraw(c: Canvas) {
                val p = Paint(Paint.ANTI_ALIAS_FLAG); p.color = 0xAAFFFFFF.toInt(); p.strokeWidth = 5f; p.strokeCap = Paint.Cap.ROUND
                c.drawLine(width - 14f, height - 40f, width - 40f, height - 14f, p); c.drawLine(width - 14f, height - 24f, width - 24f, height - 14f, p)
            }
        }, LayoutParams(96, 96, Gravity.BOTTOM or Gravity.END))
    }
}

/** Quest-style floating dock. */
class DockView(ctx: Context, val host: VrHost) : View(ctx) {
    private class Item(val kind: String, val label: String, val action: () -> Unit)
    private val apps = listOf(
        Item("youtube", "YouTube") { host.open("youtube") },
        Item("browser", L.t("Browser", "Browser")) { host.open("browser") },
        Item("gallery", L.t("Gallery", "Galerie")) { host.open("gallery") },
        Item("play", L.t("Videos", "Video")) { host.open("video") },
        Item("games", L.t("Games", "Jocuri")) { host.open("games") },
        Item("env", L.t("Environments", "Fundaluri")) { host.open("env") },
        Item("settings", L.t("Settings", "Setări")) { host.open("settings") }
    )
    private val tools = listOf(
        Item("recenter", L.t("Recenter", "Recentrare")) { host.recenter() },
        Item("mic", L.t("Voice", "Voce")) { host.startVoice() },
        Item("cam", L.t("Passthrough", "Cameră")) { host.togglePassthrough() },
        Item("vr", L.t("VR / flat view", "VR / vedere simplă")) { host.toggleVr() },
        Item("apps", L.t("All apps", "Toate aplicațiile")) { host.open("apps") }
    )
    private val sm = apps + tools
    private var hover = -1
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val cell = 84f; private val x0 = 380f; private val barTop = 62f

    private fun xOf(i: Int): Float = if (i < apps.size) x0 + i * cell else x0 + i * cell + 40f
    private fun indexAt(x: Float, y: Float): Int {
        if (y < barTop) return -1
        for (i in sm.indices) if (abs(x - xOf(i) - cell / 2) < cell / 2) return i
        return -1
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        p.style = Paint.Style.FILL; p.color = if (Prefs.dark) 0xE61A1D26.toInt() else 0xE6EEF0F5.toInt()
        c.drawRoundRect(14f, barTop, w - 14f, h - 10f, 56f, 56f, p)
        val cy = (barTop + h - 10f) / 2
        // profile
        p.color = 0xFF3DDC97.toInt(); c.drawCircle(62f, cy, 30f, p)
        p.color = Color.WHITE; c.drawCircle(62f, cy - 7f, 9f, p); c.drawArc(46f, cy + 2f, 78f, cy + 30f, 180f, 180f, true, p)
        // status pill
        p.color = if (Prefs.dark) 0xFF2A2E3A.toInt() else 0xFFD9DDE6.toInt(); c.drawRoundRect(112f, cy - 28f, 322f, cy + 28f, 28f, 28f, p)
        p.color = Theme.text; p.textSize = 30f; p.typeface = Typeface.DEFAULT_BOLD
        c.drawText(fmt.format(Date()), 128f, cy + 11f, p)
        // wifi icon + battery
        p.style = Paint.Style.STROKE; p.strokeWidth = 4f; p.strokeCap = Paint.Cap.ROUND
        for (r in 1..3) c.drawArc(228f - r * 8f, cy + 8f - r * 8f, 228f + r * 8f, cy + 8f + r * 8f, 225f, 90f, false, p)
        val bat = (context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        c.drawRoundRect(262f, cy - 12f, 304f, cy + 12f, 5f, 5f, p)
        p.style = Paint.Style.FILL; p.color = if (bat < 20) 0xFFFF5252.toInt() else 0xFF3DDC97.toInt()
        c.drawRect(266f, cy - 8f, 266f + 34f * bat.coerceIn(0, 100) / 100f, cy + 8f, p)
        // separator
        p.color = 0x44888888; c.drawRect(x0 + apps.size * cell + 14f, cy - 30f, x0 + apps.size * cell + 17f, cy + 30f, p)
        // icons
        for ((i, it) in sm.withIndex()) {
            val cx = xOf(i) + cell / 2
            if (i == hover) { p.color = 0x44FFFFFF; c.drawCircle(cx, cy, 40f, p) }
            val tint = if (it.kind == "cam" && Prefs.passthrough) Theme.accent else Theme.text
            Icons.draw(c, it.kind, cx, cy, 25f, tint)
        }
        // tooltip
        if (hover >= 0) {
            val t = sm[hover].label; p.textSize = 30f; p.typeface = Typeface.DEFAULT
            val tw = p.measureText(t) + 44f; val cx = xOf(hover) + cell / 2
            p.color = 0xEE20232D.toInt(); c.drawRoundRect(cx - tw / 2, 2f, cx + tw / 2, 52f, 25f, 25f, p)
            p.color = Color.WHITE; p.textAlign = Paint.Align.CENTER; c.drawText(t, cx, 38f, p); p.textAlign = Paint.Align.LEFT
        }
    }

    override fun onHoverEvent(e: MotionEvent): Boolean {
        val i = if (e.actionMasked == MotionEvent.ACTION_HOVER_EXIT) -1 else indexAt(e.x, e.y)
        if (i != hover) { hover = i; invalidate() }
        return true
    }
    private var downIdx = -1
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downIdx = indexAt(e.x, e.y); hover = downIdx }
            MotionEvent.ACTION_UP -> { val i = indexAt(e.x, e.y); if (i >= 0 && i == downIdx) { Sfx.click(); sm[i].action() }; downIdx = -1 }
        }
        return true
    }
}
