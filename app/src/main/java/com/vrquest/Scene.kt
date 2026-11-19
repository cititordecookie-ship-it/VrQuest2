package com.vrquest

import android.graphics.PointF
import android.view.Choreographer
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.*
import kotlin.random.Random

interface VrHost {
    fun open(id: String)
    fun close(p: Panel)
    fun recenter()
    fun setEnv(id: Int)
    fun setPano(b: android.graphics.Bitmap?)
    fun toast(msg: String)
    fun handStatus(): String
    fun requestMedia(cb: (Boolean) -> Unit)
    fun startVoice()
    fun togglePassthrough()
    fun openUrl(u: String)
    fun toggleVr()
    val act: MainActivity
}

private class Ptr(val id: Int, val color: FloatArray) {
    var active = false; var origin = V3(0f, 0f, 0f); var dir = V3(0f, 0f, -1f)
    var press = false; var grab = false; var wasPress = false
    var mode = 0 // 0 idle, 1 ui, 2 move, 3 resize, 4 two-hand scale
    var target: Panel? = null; var other: Ptr? = null
    var grabDist = 2f; var off = V3(0f, 0f, 0f); var depth0 = 0.5f; var depth = 0.5f
    var startW = 1f; var startR = 1f; var sep0 = 1f
    var pokeUntil = 0L; var palmSince = 0L; var palmCool = 0L
    var px = 0f; var py = 0f
    var dwell = 0f; var dwellPanel: Panel? = null; var dwellX = 0f; var dwellY = 0f; var fireUntil = 0f
    var hasHand = false
}

class VrScene(val host: MainActivity) {
    val panels = CopyOnWriteArrayList<Panel>()
    @Volatile var cursors: List<Cursor> = emptyList()
    @Volatile var flash = 0f
    @Volatile var padPressed = false
    lateinit var head: HeadTracker
    var dock: Panel? = null
    var hud: Panel? = null
    var toastPanel: Panel? = null
    @Volatile var toastUntil = 0f
    fun nowTime() = time
    private var hudText: TextView? = null
    private val ptrs = arrayOf(Ptr(0, floatArrayOf(0.35f, 0.85f, 1f)), Ptr(1, floatArrayOf(0.7f, 0.55f, 1f)), Ptr(2, floatArrayOf(1f, 1f, 1f)))
    private var time = 0f
    private var nextBolt = 6f
    private var thunderAt = -1f
    private var hudAcc = 0f
    private val painterLast = HashMap<Panel, Long>()
    private var running = false
    private var lastLayoutFrame = 0L
    @Volatile var hostFps = 0f
    @Volatile var rendererFps = 0f

    // ---------- geometry of the camera image ----------
    fun imageDir(nx: Float, ny: Float): V3 {
        val tanH = tan(Math.toRadians(Prefs.camFov.toDouble() / 2)).toFloat() * Prefs.ptZoom
        val tanV = tanH * 9f / 16f
        return V3((nx - 0.5f) * 2 * tanH, (0.5f - ny) * 2 * tanV, -1f).norm()
    }

    // ---------- window management (main thread) ----------
    fun addPanel(p: Panel, spawn: Boolean = true) {
        host.hostLayout.addView(p.view, FrameLayout.LayoutParams(p.pxW, p.pxH))
        p.view.measure(android.view.View.MeasureSpec.makeMeasureSpec(p.pxW, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(p.pxH, android.view.View.MeasureSpec.EXACTLY))
        p.view.layout(0, 0, p.pxW, p.pxH)
        if (spawn) p.pos = freeSlot(p.worldW)
        panels.add(p)
    }

    private fun yawOf(v: V3) = atan2(v.x, -v.z)

    fun freeSlot(w: Float, dist: Float = 2.3f, excl: Panel? = null): V3 {
        val f = head.forward(); val yaw = yawOf(f)
        val offs = floatArrayOf(0f, 0.55f, -0.55f, 1.1f, -1.1f, 1.65f, -1.65f)
        for (o in offs) {
            val ok = panels.none { !it.headLocked && it !== dock && it !== excl && !it.closing && abs(angDiff(yawOf(it.pos), yaw + o)) < 0.5f }
            if (ok) return V3(sin(yaw + o) * dist, 0.1f, -cos(yaw + o) * dist)
        }
        return V3(sin(yaw) * dist, 0.1f, -cos(yaw) * dist)
    }
    private fun angDiff(a: Float, b: Float): Float { var d = a - b; while (d > PI) d -= (2 * PI).toFloat(); while (d < -PI) d += (2 * PI).toFloat(); return d }

    fun summonDock() {
        val d = dock ?: return
        val yaw = yawOf(head.forward())
        d.pos = V3(sin(yaw) * 1.25f, -0.5f, -cos(yaw) * 1.25f)
        Sfx.click()
    }

    fun recenterWindows() {
        val yaw = 0f
        val offs = floatArrayOf(0f, 0.55f, -0.55f, 1.1f, -1.1f, 1.65f, -1.65f)
        var i = 0
        for (p in panels) {
            if (p === dock || p.headLocked || p.pinned || p.tv) continue
            val d = p.pos.len()
            val o = offs[min(i, offs.size - 1)]; i++
            p.pos = V3(sin(yaw + o) * d, 0.1f, -cos(yaw + o) * d)
        }
        dock?.pos = V3(0f, -0.5f, -1.25f)
    }

    fun toggleTv(p: Panel) {
        if (!p.tv) {
            p.savedPos = p.pos; p.savedW = p.worldW; p.savedCurved = p.curved
            val yaw = yawOf(head.forward())
            p.pos = V3(sin(yaw) * 4.2f, 0.4f, -cos(yaw) * 4.2f)
            p.worldW = 5.6f; p.curved = true; p.curveR = 4.2f; p.tv = true
            if (Prefs.env != 6 && !Prefs.passthrough) host.setEnv(6)
        } else { p.pos = p.savedPos; p.worldW = p.savedW; p.curved = p.savedCurved; p.tv = false }
    }

    fun toggleCurve(p: Panel) { p.curved = !p.curved; p.curveR = p.pos.len().coerceIn(1.5f, 6f) }

    fun requestClose(p: Panel) { if (p === dock) return; p.closing = true }

    // ---------- painter: draws each panel's View into its Surface ----------
    private val frameCb = object : Choreographer.FrameCallback {
        override fun doFrame(ns: Long) {
            if (!running) return
            val period = if (Prefs.fps60) 16_000_000L else 33_000_000L
            val ms = ns / 1_000_000L
            if (!this@VrScene.isHeadReady()) { Choreographer.getInstance().postFrameCallback(this); return }
            val fwd = this@VrScene.head.forward()
            for (p in panels) {
                val s = p.surface ?: continue
                if (!p.ready) continue
                if (!p.headLocked && !p.closing) { // skip panels far outside the view
                    val dir = p.pos.norm(); if (dir.dot(fwd) < 0.2f && p !== dock) { continue }
                }
                val last = painterLast[p] ?: 0L
                val every = if (p === dock) 66L else 33L
                if (ms - last < every) continue
                painterLast[p] = ms
                try {
                    if (!p.view.isLaidOut || p.view.width != p.pxW) { p.view.measure(android.view.View.MeasureSpec.makeMeasureSpec(p.pxW, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(p.pxH, android.view.View.MeasureSpec.EXACTLY)); p.view.layout(0, 0, p.pxW, p.pxH) }
                    val c = s.lockHardwareCanvas()
                    try { c.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR); p.view.draw(c) } finally { s.unlockCanvasAndPost(c) }
                } catch (_: Exception) {}
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }
    fun isHeadReady() = ::head.isInitialized
    fun startPainter() { if (running) return; running = true; Choreographer.getInstance().postFrameCallback(frameCb) }
    fun stopPainter() { running = false }

    // ---------- HUD ----------
    fun createHud() {
        val tv = TextView(host).apply { setTextColor(Color.WHITE); textSize = 26f; typeface = Typeface.MONOSPACE; setBackgroundColor(0x99000000.toInt()); setPadding(16, 8, 16, 8); gravity = Gravity.CENTER_VERTICAL }
        hudText = tv
        val p = Panel(tv, 560, 170, "hud", "hud"); p.hasTitleBar = false; p.headLocked = true; p.worldW = 0.42f; p.pos = V3(0f, 0.3f, -1.1f); p.alpha = 0f
        hud = p; addPanel(p, false)
    }

    // ---------- main update (GL thread) ----------
    fun update(dt: Float, head: HeadTracker, hands: List<HandState>) {
        this.head = head
        time += dt
        val now = android.os.SystemClock.uptimeMillis()
        // fades + removal
        for (p in panels) {
            if (p.closing) p.alpha -= dt * 5f
            else if (p === hud) p.alpha = if (Prefs.debug) 1f else 0f
            else if (p === toastPanel) p.alpha = if (time < toastUntil) 1f else 0f
            else p.alpha = min(1f, p.alpha + dt * 4f)
            if (p.closing && p.alpha <= 0f) {
                panels.remove(p); p.releaseGl()
                ui { host.hostLayout.removeView(p.view); (p.view.tag as? VrApp)?.onClose() }
            }
        }
        updateLightning(dt)
        if (Prefs.debug) { hudAcc += dt; if (hudAcc > 0.5f) { hudAcc = 0f; val t = "FPS ${rendererFps.toInt()}  hands ${hands.size}  cam ${host.handStatus()}"; ui { hudText?.text = t } } }

        // ---- pointers ----
        for (p in ptrs) p.active = false
        val fresh = if (Prefs.handsOn) hands.filter { now - it.time < 350 }.sortedBy { it.slot } else emptyList()
        for ((i, h) in fresh.withIndex()) {
            if (i > 1) break
            val ptr = ptrs[i]; ptr.active = true; ptr.hasHand = true
            val (nx, ny) = when { h.pinch -> Pair((h.x[4] + h.x[8]) / 2, (h.y[4] + h.y[8]) / 2); h.fist -> Pair(h.x[9], h.y[9]); else -> Pair(h.x[8], h.y[8]) }
            val gx = 0.5f + (nx - 0.5f) * Prefs.handSens; val gy = 0.5f + (ny - 0.5f) * Prefs.handSens
            val raw = head.toScene(imageDir(gx, gy))
            val a = 0.65f
            ptr.dir = (ptr.dir * (1 - a) + raw * a).norm()
            if (h.point && h.depthVel > 0.45f / Prefs.pokeSens && now > ptr.pokeUntil + 450) ptr.pokeUntil = now + 170
            ptr.press = h.pinch || now < ptr.pokeUntil
            ptr.grab = h.fist
            ptr.depth = h.depth
            // open palm summon
            if (h.palm) { if (ptr.palmSince == 0L) ptr.palmSince = now; if (now - ptr.palmSince > 800 && now > ptr.palmCool) { ptr.palmCool = now + 2500; ui { summonDock() } } } else ptr.palmSince = 0L
            if (h.swipe != 0) cycleEnv(h.swipe)
        }
        if (fresh.isEmpty()) {
            val g = ptrs[2]
            if (Prefs.gaze || padPressed) {
                g.active = true; g.hasHand = false; g.dir = head.forward(); g.grab = false
                g.press = padPressed || time < g.fireUntil
            } else g.press = false
        }

        val hoverPts = HashMap<Panel, PointF>()
        val touches = HashMap<Panel, HashMap<Int, PointF>>()
        val newCursors = ArrayList<Cursor>()
        val prevHover = HashSet<Panel>()

        for (ptr in ptrs) {
            if (!ptr.active) { if (ptr.mode != 0) endPtr(ptr); ptr.wasPress = false; continue }
            // nearest panel hit
            var best: Panel? = null; var bh: Panel.Hit? = null
            for (p in panels) {
                if (p.headLocked || p.closing || p.alpha < 0.5f) continue
                val hh = p.hit(ptr.origin, ptr.dir) ?: continue
                if (bh == null || hh.t < bh.t) { best = p; bh = hh }
            }
            val wants = ptr.press || ptr.grab
            var progress = 0f
            if (wants && !ptr.wasPress) {
                if (best != null && bh != null) {
                    val p = best; ptr.target = p
                    val x = bh.u * p.pxW; val y = bh.v * p.pxH
                    val zone = p.zoneAt(x, y)
                    val other = ptrs.firstOrNull { it !== ptr && it.active && it.mode == 2 && it.target === p }
                    if (other != null && !ptr.grab) { ptr.mode = 4; ptr.other = other; ptr.startW = p.worldW; ptr.sep0 = max(0.05f, angle(other.dir, ptr.dir)) }
                    else if (zone == Zone.RESIZE) { ptr.mode = 3; ptr.startW = p.worldW; ptr.startR = max(0.1f, hypot((bh.u - 0.5f) * p.worldW, (bh.v - 0.5f) * p.worldH)) }
                    else if (zone == Zone.DRAG || ptr.grab) {
                        ptr.mode = 2; ptr.grabDist = bh.t; ptr.depth0 = ptr.depth
                        val b = p.basis(); val o = p.pos - bh.point
                        ptr.off = V3(o.dot(b[0]), o.dot(b[1]), o.dot(b[2]))
                        if (p.tv) { p.tv = false }
                    } else { ptr.mode = 1; Sfx.click() }
                }
            }
            if (wants && ptr.mode != 0) {
                val p = ptr.target
                if (p != null) when (ptr.mode) {
                    1 -> {
                        val hh = p.hit(ptr.origin, ptr.dir, false)
                        if (hh != null) { ptr.px = (hh.u * p.pxW).coerceIn(0f, p.pxW - 1f); ptr.py = (hh.v * p.pxH).coerceIn(0f, p.pxH - 1f) }
                        touches.getOrPut(p) { HashMap() }[ptr.id] = PointF(ptr.px, ptr.py)
                    }
                    2 -> {
                        var gd = ptr.grabDist
                        if (ptr.hasHand) gd *= (ptr.depth / ptr.depth0).coerceIn(0.4f, 2.5f).pow(1.5f)
                        gd = gd.coerceIn(0.8f, 9f)
                        val nh = ptr.origin + ptr.dir * gd
                        val b = p.basis(nh)
                        val np = nh + b[0] * ptr.off.x + b[1] * ptr.off.y + b[2] * ptr.off.z
                        p.pos = p.pos * 0.45f + np * 0.55f
                        if (p.curved) p.curveR = p.pos.len().coerceIn(1.5f, 8f)
                    }
                    3 -> {
                        val hh = p.hit(ptr.origin, ptr.dir, false)
                        if (hh != null) { val r = hypot((hh.u - 0.5f) * p.worldW, (hh.v - 0.5f) * p.worldH); p.worldW = (ptr.startW * r / ptr.startR).coerceIn(0.6f, 9f) }
                    }
                    4 -> { val o = ptr.other; if (o != null) p.worldW = (ptr.startW * angle(o.dir, ptr.dir) / ptr.sep0).coerceIn(0.6f, 9f) }
                }
            }
            if (!wants && ptr.wasPress) endPtr(ptr)
            ptr.wasPress = wants

            // hover + gaze dwell
            if (!wants && ptr.mode == 0 && best != null && bh != null) {
                hoverPts[best] = PointF(bh.u * best.pxW, bh.v * best.pxH)
                if (!ptr.hasHand && !padPressed) {
                    val px = bh.u * best.pxW; val py = bh.v * best.pxH
                    if (ptr.dwellPanel === best && hypot(px - ptr.dwellX, py - ptr.dwellY) < 50f && time >= ptr.fireUntil) ptr.dwell += dt
                    else if (time >= ptr.fireUntil) { ptr.dwellPanel = best; ptr.dwellX = px; ptr.dwellY = py; ptr.dwell = 0f }
                    if (ptr.dwell > 1.4f) { ptr.fireUntil = time + 0.18f; ptr.dwell = -0.8f }
                    progress = (ptr.dwell / 1.4f).coerceIn(0f, 1f)
                } else ptr.dwell = 0f
            }
            // cursor
            val cp: Panel? = if (ptr.mode in 1..3) ptr.target else best
            var pos = ptr.origin + ptr.dir * 2.2f; var t = 2.2f
            var basis = arrayOf(V3(1f, 0f, 0f), V3(0f, 1f, 0f), V3(0f, 0f, 1f))
            if (cp != null) {
                val hh = cp.hit(ptr.origin, ptr.dir, ptr.mode == 0)
                if (hh != null) { pos = hh.point; t = hh.t; basis = cp.basis() }
            }
            newCursors.add(Cursor(pos, basis, 0.02f * max(t, 0.8f), ptr.color, wants, progress))
            if (ptr.mode == 0 && best != null) prevHover.add(best)
            if (ptr.mode in 1..3) ptr.target?.let { prevHover.add(it) }
        }
        cursors = newCursors
        for (p in panels) {
            val tgt = if (p in prevHover) 1f else 0f
            p.hover += (tgt - p.hover) * min(1f, dt * 10f)
            val map = touches[p]; val hv = hoverPts[p]
            val act = (map != null && map.isNotEmpty()) || hv != null
            if (act || touchActive.contains(p)) p.touch.set(map ?: emptyMap(), hv)
            if (act) touchActive.add(p) else touchActive.remove(p)
        }
    }
    private val touchActive = HashSet<Panel>()

    private fun endPtr(ptr: Ptr) { ptr.mode = 0; ptr.target = null; ptr.other = null }
    private fun angle(a: V3, b: V3) = acos(a.dot(b).coerceIn(-1f, 1f))

    fun cycleEnv(dir: Int) {
        var e = Prefs.env + dir; if (e > 6) e = 1; if (e < 1) e = 6
        ui { host.setEnv(e) }
    }

    private fun updateLightning(dt: Float) {
        flash = max(0f, flash - dt * 2.6f)
        val e = Prefs.env
        if (Prefs.passthrough || (e != 1 && e != 2)) return
        nextBolt -= dt
        if (nextBolt <= 0f) {
            flash = if (e == 2) 1f else 0.5f
            thunderAt = time + 0.4f + Random.nextFloat() * 1.4f
            nextBolt = (if (e == 2) 5f else 18f) + Random.nextFloat() * (if (e == 2) 9f else 25f)
        }
        if (thunderAt > 0f && time >= thunderAt) { thunderAt = -1f; Sfx.thunder() }
    }

    fun scrollAt(dy: Float) {
        for (p in panels) if (p.hover > 0.5f && !p.headLocked) p.touch.scroll(p.pxW / 2f, p.pxH / 2f, dy)
    }
}
