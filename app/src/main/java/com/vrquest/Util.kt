package com.vrquest

import android.content.Context
import android.content.SharedPreferences
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import java.util.Locale
import kotlin.math.*
import kotlin.reflect.KProperty

val mainHandler = Handler(Looper.getMainLooper())
fun ui(r: () -> Unit) { if (Looper.myLooper() == Looper.getMainLooper()) r() else mainHandler.post(r) }

data class V3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = V3(x * s, y * s, z * s)
    fun dot(o: V3) = x * o.x + y * o.y + z * o.z
    fun cross(o: V3) = V3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun len() = sqrt(dot(this))
    fun norm(): V3 { val l = len(); return if (l < 1e-6f) this else this * (1f / l) }
}

/** English / Romanian helper */
object L {
    private val isRo = Locale.getDefault().language == "ro"
    fun t(en: String, ro: String) = if (isRo) ro else en
}

/** Persistent settings with in-memory cache (safe to read from the GL thread). */
object Prefs {
    lateinit var sp: SharedPreferences
    fun init(c: Context) { sp = c.getSharedPreferences("vrquest", Context.MODE_PRIVATE) }

    class F(val k: String, val d: Float) {
        @Volatile private var v: Float? = null
        operator fun getValue(t: Any?, p: KProperty<*>): Float { var x = v; if (x == null) { x = sp.getFloat(k, d); v = x }; return x }
        operator fun setValue(t: Any?, p: KProperty<*>, nv: Float) { v = nv; sp.edit().putFloat(k, nv).apply() }
    }
    class I(val k: String, val d: Int) {
        @Volatile private var v: Int? = null
        operator fun getValue(t: Any?, p: KProperty<*>): Int { var x = v; if (x == null) { x = sp.getInt(k, d); v = x }; return x }
        operator fun setValue(t: Any?, p: KProperty<*>, nv: Int) { v = nv; sp.edit().putInt(k, nv).apply() }
    }
    class B(val k: String, val d: Boolean) {
        @Volatile private var v: Boolean? = null
        operator fun getValue(t: Any?, p: KProperty<*>): Boolean { var x = v; if (x == null) { x = sp.getBoolean(k, d); v = x }; return x }
        operator fun setValue(t: Any?, p: KProperty<*>, nv: Boolean) { v = nv; sp.edit().putBoolean(k, nv).apply() }
    }

    var ipdMm by F("ipd", 63f)
    var lensSep by F("lensSep", 0f)          // -0.3..0.3 lens center shift
    var fov by F("fov", 95f)                 // vertical degrees per eye
    var renderScale by F("scale", 0.8f)
    var k1 by F("k1", 0.22f)
    var k2 by F("k2", 0.08f)
    var zoom by F("zoom", 1.0f)
    var handSens by F("hsens", 1f)
    var smoothing by F("smooth", 0.5f)
    var pokeSens by F("poke", 1f)
    var camFov by F("camfov", 66f)           // rear camera horizontal FOV
    var ptZoom by F("ptzoom", 1f)
    var volume by F("vol", 0.7f)
    var passthrough by B("pt", false)
    var env by I("env", 1)
    var dark by B("dark", true)
    var handsOn by B("hands", true)
    var debug by B("debug", false)
    var fps60 by B("fps60", true)
    var gaze by B("gaze", true)
    var firstRun by B("first", true)
    var vrMode by B("vr", true)
    var camMirror by B("cammirror", false)
}

object Theme {
    val bg get() = if (Prefs.dark) 0xFF14171F.toInt() else 0xFFF2F4F8.toInt()
    val card get() = if (Prefs.dark) 0xFF222733.toInt() else 0xFFFFFFFF.toInt()
    val bar get() = if (Prefs.dark) 0xFF0C0E13.toInt() else 0xFFDDE2EA.toInt()
    val text get() = if (Prefs.dark) 0xFFF1F3F7.toInt() else 0xFF14171F.toInt()
    val sub get() = if (Prefs.dark) 0xFF9AA3B5.toInt() else 0xFF5A6377.toInt()
    const val accent = 0xFF4FC3F7.toInt()
}

fun rounded(color: Int, r: Float, stroke: Int = 0, sw: Int = 0): GradientDrawable =
    GradientDrawable().apply { setColor(color); cornerRadius = r; if (sw > 0) setStroke(sw, stroke) }

/** Vector icons drawn with Canvas. s = half size. */
object Icons {
    fun draw(c: Canvas, kind: String, cx: Float, cy: Float, s: Float, color: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = color; p.strokeWidth = s * 0.16f; p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
        fun stroke() { p.style = Paint.Style.STROKE }
        fun fill() { p.style = Paint.Style.FILL }
        when (kind) {
            "youtube" -> {
                p.color = 0xFFFF2D2D.toInt(); fill()
                c.drawRoundRect(cx - s * 1.1f, cy - s * 0.78f, cx + s * 1.1f, cy + s * 0.78f, s * 0.45f, s * 0.45f, p)
                p.color = Color.WHITE
                val pa = Path(); pa.moveTo(cx - s * 0.3f, cy - s * 0.42f); pa.lineTo(cx + s * 0.5f, cy); pa.lineTo(cx - s * 0.3f, cy + s * 0.42f); pa.close()
                c.drawPath(pa, p)
            }
            "browser" -> {
                stroke(); c.drawCircle(cx, cy, s * 0.9f, p)
                c.drawOval(cx - s * 0.42f, cy - s * 0.9f, cx + s * 0.42f, cy + s * 0.9f, p)
                c.drawLine(cx - s * 0.9f, cy, cx + s * 0.9f, cy, p)
            }
            "gallery" -> {
                stroke(); c.drawRoundRect(cx - s, cy - s * 0.8f, cx + s, cy + s * 0.8f, s * 0.2f, s * 0.2f, p)
                val pa = Path(); pa.moveTo(cx - s * 0.8f, cy + s * 0.6f); pa.lineTo(cx - s * 0.2f, cy - s * 0.1f); pa.lineTo(cx + s * 0.2f, cy + s * 0.3f); pa.lineTo(cx + s * 0.4f, cy + s * 0.1f); pa.lineTo(cx + s * 0.8f, cy + s * 0.6f)
                c.drawPath(pa, p); fill(); c.drawCircle(cx + s * 0.45f, cy - s * 0.3f, s * 0.15f, p)
            }
            "games" -> {
                stroke(); c.drawRoundRect(cx - s * 1.1f, cy - s * 0.6f, cx + s * 1.1f, cy + s * 0.7f, s * 0.5f, s * 0.5f, p)
                c.drawLine(cx - s * 0.65f, cy - s * 0.15f, cx - s * 0.25f, cy - s * 0.15f, p)
                c.drawLine(cx - s * 0.45f, cy - s * 0.35f, cx - s * 0.45f, cy + s * 0.05f, p)
                fill(); c.drawCircle(cx + s * 0.35f, cy - s * 0.05f, s * 0.12f, p); c.drawCircle(cx + s * 0.65f, cy - s * 0.2f, s * 0.12f, p)
            }
            "env" -> {
                stroke(); val pa = Path(); pa.moveTo(cx - s, cy + s * 0.7f); pa.lineTo(cx - s * 0.3f, cy - s * 0.3f); pa.lineTo(cx + s * 0.1f, cy + s * 0.25f); pa.lineTo(cx + s * 0.45f, cy - s * 0.1f); pa.lineTo(cx + s, cy + s * 0.7f); pa.close()
                c.drawPath(pa, p); fill(); c.drawCircle(cx + s * 0.5f, cy - s * 0.6f, s * 0.17f, p)
            }
            "settings" -> {
                stroke(); c.drawCircle(cx, cy, s * 0.5f, p)
                for (i in 0 until 8) { val a = i * PI.toFloat() / 4; c.drawLine(cx + cos(a) * s * 0.7f, cy + sin(a) * s * 0.7f, cx + cos(a) * s * 1f, cy + sin(a) * s * 1f, p.apply { strokeWidth = s * 0.28f }) }
            }
            "apps" -> { fill(); for (i in -1..1) for (j in -1..1) c.drawCircle(cx + i * s * 0.62f, cy + j * s * 0.62f, s * 0.17f, p) }
            "mic" -> {
                stroke(); c.drawRoundRect(cx - s * 0.3f, cy - s * 0.9f, cx + s * 0.3f, cy + s * 0.2f, s * 0.3f, s * 0.3f, p)
                c.drawArc(cx - s * 0.6f, cy - s * 0.5f, cx + s * 0.6f, cy + s * 0.5f, 10f, 160f, false, p)
                c.drawLine(cx, cy + s * 0.5f, cx, cy + s * 0.9f, p)
            }
            "recenter" -> { stroke(); c.drawCircle(cx, cy, s * 0.7f, p); fill(); c.drawCircle(cx, cy, s * 0.15f, p)
                stroke(); c.drawLine(cx, cy - s, cx, cy - s * 0.5f, p); c.drawLine(cx, cy + s, cx, cy + s * 0.5f, p); c.drawLine(cx - s, cy, cx - s * 0.5f, cy, p); c.drawLine(cx + s, cy, cx + s * 0.5f, cy, p) }
            "cam" -> { stroke(); c.drawRoundRect(cx - s, cy - s * 0.6f, cx + s, cy + s * 0.7f, s * 0.2f, s * 0.2f, p); c.drawCircle(cx, cy + s * 0.05f, s * 0.35f, p) }
            "vr" -> { stroke(); c.drawRoundRect(cx - s * 1.1f, cy - s * 0.55f, cx + s * 1.1f, cy + s * 0.55f, s * 0.35f, s * 0.35f, p); c.drawCircle(cx - s * 0.5f, cy, s * 0.25f, p); c.drawCircle(cx + s * 0.5f, cy, s * 0.25f, p) }
            "bell" -> { stroke(); val pa = Path(); pa.moveTo(cx - s * 0.7f, cy + s * 0.5f); pa.quadTo(cx - s * 0.5f, cy + s * 0.2f, cx - s * 0.5f, cy - s * 0.2f); pa.quadTo(cx - s * 0.5f, cy - s * 0.8f, cx, cy - s * 0.8f); pa.quadTo(cx + s * 0.5f, cy - s * 0.8f, cx + s * 0.5f, cy - s * 0.2f); pa.quadTo(cx + s * 0.5f, cy + s * 0.2f, cx + s * 0.7f, cy + s * 0.5f); pa.close(); c.drawPath(pa, p) }
            "close" -> { stroke(); c.drawLine(cx - s * 0.6f, cy - s * 0.6f, cx + s * 0.6f, cy + s * 0.6f, p); c.drawLine(cx + s * 0.6f, cy - s * 0.6f, cx - s * 0.6f, cy + s * 0.6f, p) }
            "pin" -> { stroke(); c.drawCircle(cx, cy - s * 0.3f, s * 0.45f, p); c.drawLine(cx, cy + s * 0.15f, cx, cy + s * 0.9f, p) }
            "curve" -> { stroke(); c.drawArc(cx - s * 1.2f, cy - s * 0.2f, cx + s * 1.2f, cy + s * 2f, 215f, 110f, false, p) }
            "tv" -> { stroke(); c.drawRoundRect(cx - s, cy - s * 0.7f, cx + s, cy + s * 0.4f, s * 0.15f, s * 0.15f, p); c.drawLine(cx - s * 0.4f, cy + s * 0.8f, cx + s * 0.4f, cy + s * 0.8f, p) }
            "back" -> { stroke(); c.drawLine(cx + s * 0.3f, cy - s * 0.7f, cx - s * 0.4f, cy, p); c.drawLine(cx - s * 0.4f, cy, cx + s * 0.3f, cy + s * 0.7f, p) }
            "fwd" -> { stroke(); c.drawLine(cx - s * 0.3f, cy - s * 0.7f, cx + s * 0.4f, cy, p); c.drawLine(cx + s * 0.4f, cy, cx - s * 0.3f, cy + s * 0.7f, p) }
            "reload" -> { stroke(); c.drawArc(cx - s * 0.7f, cy - s * 0.7f, cx + s * 0.7f, cy + s * 0.7f, 40f, 280f, false, p); c.drawLine(cx + s * 0.55f, cy - s * 0.75f, cx + s * 0.55f, cy - s * 0.25f, p); c.drawLine(cx + s * 0.55f, cy - s * 0.25f, cx + s * 0.1f, cy - s * 0.3f, p) }
            "home" -> { stroke(); val pa = Path(); pa.moveTo(cx - s * 0.8f, cy); pa.lineTo(cx, cy - s * 0.8f); pa.lineTo(cx + s * 0.8f, cy); pa.moveTo(cx - s * 0.55f, cy - s * 0.15f); pa.lineTo(cx - s * 0.55f, cy + s * 0.75f); pa.lineTo(cx + s * 0.55f, cy + s * 0.75f); pa.lineTo(cx + s * 0.55f, cy - s * 0.15f); c.drawPath(pa, p) }
            "search" -> { stroke(); c.drawCircle(cx - s * 0.15f, cy - s * 0.15f, s * 0.55f, p); c.drawLine(cx + s * 0.25f, cy + s * 0.25f, cx + s * 0.8f, cy + s * 0.8f, p) }
            "play" -> { fill(); val pa = Path(); pa.moveTo(cx - s * 0.5f, cy - s * 0.7f); pa.lineTo(cx + s * 0.7f, cy); pa.lineTo(cx - s * 0.5f, cy + s * 0.7f); pa.close(); c.drawPath(pa, p) }
        }
    }
}

class IconBtn(ctx: Context, val kind: String, var tint: Int, val onTap: () -> Unit) : View(ctx) {
    private var hot = false
    init { setOnClickListener { onTap() } }
    override fun onDraw(c: Canvas) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        if (hot) { p.color = 0x33FFFFFF; c.drawCircle(width / 2f, height / 2f, min(width, height) / 2f, p) }
        Icons.draw(c, kind, width / 2f, height / 2f, min(width, height) * 0.28f, tint)
    }
    override fun onHoverEvent(e: MotionEvent): Boolean {
        val h = e.actionMasked != MotionEvent.ACTION_HOVER_EXIT
        if (h != hot) { hot = h; invalidate() }
        return true
    }
}

/** Writes uncaught / render errors to a file; shown on next start. */
object Crash {
    lateinit var file: java.io.File
    @Volatile var last: String = ""
    fun init(ctx: Context) {
        file = java.io.File(ctx.filesDir, "crash.txt")
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e -> log("FATAL ${t.name}", e); old?.uncaughtException(t, e) }
    }
    fun log(where: String, e: Throwable) {
        val txt = "$where: ${android.util.Log.getStackTraceString(e)}"
        if (txt == last) return
        last = txt
        try { file.appendText(txt.take(4000) + "\n----\n") } catch (_: Exception) {}
    }
    fun take(): String? = try { if (file.exists()) file.readText().also { file.delete() } else null } catch (_: Exception) { null }
}
