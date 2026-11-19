package com.vrquest

import android.graphics.PointF
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*

enum class Zone { CONTENT, DRAG, RESIZE }

/** A floating 2D Android view rendered into a GL texture and placed in 3D space. */
class Panel(val view: View, val pxW: Int, val pxH: Int, val title: String = "", val appId: String = "") {
    @Volatile var pos = V3(0f, 0f, -2f)
    @Volatile var worldW = 1.9f
    @Volatile var curved = false
    @Volatile var curveR = 2.2f
    @Volatile var pinned = false
    @Volatile var headLocked = false
    @Volatile var alpha = 0f
    @Volatile var closing = false
    @Volatile var tv = false
    var savedPos = pos; var savedW = worldW; var savedCurved = false
    var hasTitleBar = true
    var hover = 0f
    val worldH get() = worldW * pxH / pxW

    // GL state (GL thread only, except `surface`/`ready`)
    var tex = 0
    var st: SurfaceTexture? = null
    @Volatile var surface: Surface? = null
    @Volatile var ready = false
    var hasFrame = false
    val frameAvail = AtomicBoolean(false)
    val stMat = FloatArray(16)
    var meshKey = ""
    var mesh: java.nio.FloatBuffer? = null
    var meshCount = 0

    val touch = Touch(this)

    fun initGl() {
        val t = IntArray(1); GLES20.glGenTextures(1, t, 0); tex = t[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val s = SurfaceTexture(tex)
        s.setDefaultBufferSize(pxW, pxH)
        s.setOnFrameAvailableListener { frameAvail.set(true) }
        st = s; surface = Surface(s); hasFrame = false; ready = true
    }

    fun releaseGl() {
        ready = false
        try { surface?.release() } catch (_: Exception) {}
        try { st?.release() } catch (_: Exception) {}
        surface = null; st = null; tex = 0; hasFrame = false
    }

    /** Basis: x right, y up, z toward the viewer at origin. */
    fun basis(at: V3 = pos): Array<V3> {
        if (headLocked) return arrayOf(V3(1f, 0f, 0f), V3(0f, 1f, 0f), V3(0f, 0f, 1f))
        val n = (at * -1f).norm()
        var x = V3(0f, 1f, 0f).cross(n)
        if (x.len() < 0.05f) x = V3(1f, 0f, 0f)
        x = x.norm()
        val y = n.cross(x)
        return arrayOf(x, y, n)
    }

    fun model(): FloatArray {
        val b = basis(); val m = FloatArray(16)
        m[0] = b[0].x; m[1] = b[0].y; m[2] = b[0].z
        m[4] = b[1].x; m[5] = b[1].y; m[6] = b[1].z
        m[8] = b[2].x; m[9] = b[2].y; m[10] = b[2].z
        m[12] = pos.x; m[13] = pos.y; m[14] = pos.z; m[15] = 1f
        return m
    }

    fun ensureMesh() {
        val key = "$worldW|$curved|$curveR"
        if (key == meshKey && mesh != null) return
        meshKey = key
        val segs = if (curved) 32 else 1
        val w = worldW; val h = worldH
        val data = FloatArray((segs + 1) * 2 * 5)
        var k = 0
        for (i in 0..segs) {
            val u = i / segs.toFloat()
            val px: Float; val pz: Float
            if (curved) { val a = (u - 0.5f) * w / curveR; px = curveR * sin(a); pz = curveR * (1 - cos(a)) } else { px = (u - 0.5f) * w; pz = 0f }
            data[k++] = px; data[k++] = h / 2; data[k++] = pz; data[k++] = u; data[k++] = 1f
            data[k++] = px; data[k++] = -h / 2; data[k++] = pz; data[k++] = u; data[k++] = 0f
        }
        val fb = java.nio.ByteBuffer.allocateDirect(data.size * 4).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
        fb.put(data); fb.position(0)
        mesh = fb; meshCount = (segs + 1) * 2
    }

    class Hit(val t: Float, val u: Float, val v: Float, val point: V3)

    /** Ray test. bounded=false keeps tracking outside the panel edges (for drags). */
    fun hit(o: V3, d: V3, bounded: Boolean = true): Hit? {
        val b = basis()
        val oo = o - pos
        val ol = V3(oo.dot(b[0]), oo.dot(b[1]), oo.dot(b[2]))
        val dl = V3(d.dot(b[0]), d.dot(b[1]), d.dot(b[2]))
        val w = worldW; val h = worldH
        val t: Float; val u: Float; val v: Float
        if (!curved) {
            if (dl.z > -1e-4f) return null
            t = -ol.z / dl.z
            if (t <= 0f) return null
            val px = ol.x + t * dl.x; val py = ol.y + t * dl.y
            u = px / w + 0.5f; v = 0.5f - py / h
        } else {
            val r = curveR
            val a = dl.x * dl.x + dl.z * dl.z
            if (a < 1e-6f) return null
            val bq = 2 * (ol.x * dl.x + (ol.z - r) * dl.z)
            val c = ol.x * ol.x + (ol.z - r) * (ol.z - r) - r * r
            val disc = bq * bq - 4 * a * c
            if (disc < 0) return null
            val sq = sqrt(disc)
            val t1 = (-bq + sq) / (2 * a); val t2 = (-bq - sq) / (2 * a)
            t = if (t1 > 0 && t2 > 0) min(t1, t2).let { if (c < 0) max(t1, t2) else it } else max(t1, t2)
            if (t <= 0f) return null
            val px = ol.x + t * dl.x; val pz = ol.z + t * dl.z; val py = ol.y + t * dl.y
            val ang = atan2(px, r - pz)
            u = ang * r / w + 0.5f; v = 0.5f - py / h
        }
        if (bounded && (u < 0f || u > 1f || v < 0f || v > 1f)) return null
        return Hit(t, u, v, o + d * t)
    }

    fun zoneAt(x: Float, y: Float): Zone {
        if (!hasTitleBar) return Zone.CONTENT
        if (x > pxW - 96 && y > pxH - 96) return Zone.RESIZE
        if (y < 72 && x < pxW - 5 * 76) return Zone.DRAG
        return Zone.CONTENT
    }
}

/** Turns pointer positions into real MotionEvents delivered to the panel's view (multi-touch + hover). */
class Touch(private val p: Panel) {
    @Volatile private var pending: Map<Int, PointF> = emptyMap()
    @Volatile private var hoverPt: PointF? = null
    private val posted = AtomicBoolean(false)
    private var cur: Map<Int, PointF> = emptyMap()
    private var downTime = 0L
    private var hovering = false

    fun set(m: Map<Int, PointF>, hover: PointF?) {
        pending = m; hoverPt = hover
        if (posted.compareAndSet(false, true)) mainHandler.post { posted.set(false); apply() }
    }

    private fun apply() {
        val next = pending; val hv = hoverPt
        val now = android.os.SystemClock.uptimeMillis()
        // new pointers
        val prevIds = cur.keys.sorted(); val nextIds = next.keys.sorted()
        if (prevIds.isEmpty() && nextIds.isNotEmpty()) downTime = now
        // sequence: removals, then additions, then move
        var state = LinkedHashMap(cur)
        for (id in prevIds) if (id !in next) { send(now, if (state.size == 1) MotionEvent.ACTION_UP else MotionEvent.ACTION_POINTER_UP, state, id); state.remove(id) }
        for (id in nextIds) if (id !in state) { state[id] = next[id]!!; send(now, if (state.size == 1) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_POINTER_DOWN, state, id) }
        if (state.isNotEmpty()) {
            var moved = false
            for ((id, pt) in next) { val o = state[id]; if (o == null || o.x != pt.x || o.y != pt.y) moved = true }
            state = LinkedHashMap(next)
            if (moved) send(now, MotionEvent.ACTION_MOVE, state, -1)
        }
        cur = next
        if (hv != null && next.isEmpty()) {
            val ev = MotionEvent.obtain(now, now, if (hovering) MotionEvent.ACTION_HOVER_MOVE else MotionEvent.ACTION_HOVER_ENTER, hv.x, hv.y, 0)
            ev.source = InputDevice.SOURCE_MOUSE
            p.view.dispatchGenericMotionEvent(ev); ev.recycle(); hovering = true
        } else if (hovering) {
            val ev = MotionEvent.obtain(now, now, MotionEvent.ACTION_HOVER_EXIT, 0f, 0f, 0)
            ev.source = InputDevice.SOURCE_MOUSE
            p.view.dispatchGenericMotionEvent(ev); ev.recycle(); hovering = false
        }
    }

    private fun send(now: Long, action: Int, state: Map<Int, PointF>, changed: Int) {
        val ids = state.keys.sorted()
        val props = Array(ids.size) { MotionEvent.PointerProperties().apply { id = ids[it]; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = Array(ids.size) { MotionEvent.PointerCoords().apply { x = state[ids[it]]!!.x; y = state[ids[it]]!!.y; pressure = 1f; size = 1f } }
        var act = action
        if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP) act = action or (ids.indexOf(changed) shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        val ev = MotionEvent.obtain(downTime, now, act, ids.size, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        p.view.dispatchTouchEvent(ev); ev.recycle()
    }

    fun scroll(x: Float, y: Float, dy: Float) {
        mainHandler.post {
            val now = android.os.SystemClock.uptimeMillis()
            val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE })
            val co = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; setAxisValue(MotionEvent.AXIS_VSCROLL, dy) })
            val ev = MotionEvent.obtain(now, now, MotionEvent.ACTION_SCROLL, 1, props, co, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
            p.view.dispatchGenericMotionEvent(ev); ev.recycle()
        }
    }
}
