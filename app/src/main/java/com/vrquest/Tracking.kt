package com.vrquest

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.view.Surface
import android.view.WindowManager
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.io.File
import java.net.URL
import java.nio.ByteBuffer
import kotlin.math.*

/** 3DoF head tracking from the game rotation vector sensor. */
class HeadTracker(private val ctx: Context) : SensorEventListener {
    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) ?: sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val r = FloatArray(9)
    private val rm = FloatArray(9)
    private var yawOffset = 0f
    private var autoCentered = false
    private var startMs = 0L

    /** eye -> scene rotation, column-major 4x4 */
    @Volatile var eyeToScene = FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) }
    @Volatile private var raw = FloatArray(9) { if (it % 4 == 0) 1f else 0f }

    fun start() { startMs = SystemClock.uptimeMillis(); autoCentered = false; sensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) } }
    fun stop() { sm.unregisterListener(this) }
    override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}

    @Suppress("DEPRECATION")
    override fun onSensorChanged(e: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(r, e.values)
        val rot = (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
        if (rot == Surface.ROTATION_270) SensorManager.remapCoordinateSystem(r, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, rm)
        else SensorManager.remapCoordinateSystem(r, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, rm)
        // ENU -> scene (x=E, y=U, z=-N): rows of S*R
        val h = FloatArray(9)
        for (c in 0 until 3) { h[0 + c] = rm[0 + c]; h[3 + c] = rm[6 + c]; h[6 + c] = -rm[3 + c] }
        raw = h
        if (!autoCentered && SystemClock.uptimeMillis() - startMs > 800) { recenter(); autoCentered = true }
        publish()
    }

    private fun publish() {
        val h = raw; val c = cos(yawOffset); val s = sin(yawOffset)
        // Ry(yawOffset) * H
        val o = FloatArray(9)
        for (col in 0 until 3) {
            o[0 + col] = c * h[0 + col] + s * h[6 + col]
            o[3 + col] = h[3 + col]
            o[6 + col] = -s * h[0 + col] + c * h[6 + col]
        }
        val m = FloatArray(16)
        for (row in 0 until 3) for (col in 0 until 3) m[col * 4 + row] = o[row * 3 + col]
        m[15] = 1f
        eyeToScene = m
    }

    fun recenter() {
        val h = raw
        val fx = -h[2]; val fz = -h[8]
        yawOffset = atan2(fx, -fz)
        publish()
    }

    fun toScene(v: V3): V3 { val m = eyeToScene; return V3(m[0] * v.x + m[4] * v.y + m[8] * v.z, m[1] * v.x + m[5] * v.y + m[9] * v.z, m[2] * v.x + m[6] * v.y + m[10] * v.z) }
    fun forward() = toScene(V3(0f, 0f, -1f))
    fun available() = sensor != null
}

/** Processed hand. Image coordinates normalised 0..1 (origin top-left, upright). */
class HandState(
    val slot: Int, val x: FloatArray, val y: FloatArray, val right: Boolean,
    val pinch: Boolean, val pinchAmt: Float, val fist: Boolean, val palm: Boolean, val point: Boolean,
    val depth: Float, val depthVel: Float, val swipe: Int, val time: Long
)

object Hand {
    val BONES = arrayOf(
        0 to 1, 1 to 2, 2 to 3, 3 to 4, 0 to 5, 5 to 6, 6 to 7, 7 to 8, 5 to 9, 9 to 10, 10 to 11, 11 to 12,
        9 to 13, 13 to 14, 14 to 15, 15 to 16, 13 to 17, 17 to 18, 18 to 19, 19 to 20, 0 to 17
    )
    val TIPS = intArrayOf(4, 8, 12, 16, 20)
    const val ASPECT = 16f / 9f
}

/** MediaPipe hand landmarker (21 landmarks x 2 hands) + gesture classifier. */
class HandTracker(private val ctx: Context) {
    @Volatile var hands: List<HandState> = emptyList()
    @Volatile var status: String = "init"
    @Volatile var fps = 0f
    private var lm: HandLandmarker? = null
    private var lastTs = 0L
    private var frames = 0; private var fpsT = SystemClock.uptimeMillis()
    private var lastImgRot = 0

    private class Mem {
        var x = FloatArray(21); var y = FloatArray(21); var has = false
        var pinch = false; var depth = 0f; var t = 0L; var depthVel = 0f; var cx = 0f; var swipeUntil = 0L
    }
    private val mem = arrayOf(Mem(), Mem())

    fun start() {
        if (lm != null || status == "loading") return
        status = "loading"
        Thread {
            try {
                val f = File(ctx.filesDir, "hand_landmarker.task")
                val bytes: ByteArray = try { ctx.assets.open("hand_landmarker.task").use { it.readBytes() } } catch (e: Exception) {
                    if (!f.exists() || f.length() < 1000) {
                        status = "downloading model"
                        val tmp = File(f.path + ".tmp")
                        URL("https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task")
                            .openStream().use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                        tmp.renameTo(f)
                    }
                    f.readBytes()
                }
                val buf = ByteBuffer.allocateDirect(bytes.size); buf.put(bytes); buf.rewind()
                lm = build(buf, true) ?: build(buf, false)
                status = if (lm != null) "ready" else "model error"
            } catch (e: Exception) { status = "model error: ${e.message}" }
        }.start()
    }

    private fun build(buf: ByteBuffer, gpu: Boolean): HandLandmarker? = try {
        buf.rewind()
        val b = BaseOptions.builder().setModelAssetBuffer(buf)
        if (gpu) b.setDelegate(Delegate.GPU)
        val o = HandLandmarker.HandLandmarkerOptions.builder().setBaseOptions(b.build())
            .setRunningMode(RunningMode.LIVE_STREAM).setNumHands(2)
            .setMinHandDetectionConfidence(0.5f).setMinHandPresenceConfidence(0.5f).setMinTrackingConfidence(0.5f)
            .setResultListener { res, _ -> onResult(res) }
            .setErrorListener { }
            .build()
        HandLandmarker.createFromOptions(ctx, o)
    } catch (e: Throwable) { null }

    fun stop() { try { lm?.close() } catch (_: Exception) {}; lm = null; if (status == "ready") status = "init"; hands = emptyList() }

    fun analyze(img: ImageProxy) {
        val l = lm
        if (l == null || !Prefs.handsOn) { img.close(); return }
        try {
            val bmp = img.toBitmap(); lastImgRot = img.imageInfo.rotationDegrees
            val opts = ImageProcessingOptions.builder().setRotationDegrees(lastImgRot).build()
            var ts = SystemClock.uptimeMillis(); if (ts <= lastTs) ts = lastTs + 1; lastTs = ts
            l.detectAsync(BitmapImageBuilder(bmp).build(), opts, ts)
        } catch (_: Throwable) {} finally { img.close() }
    }

    private fun d(x: FloatArray, y: FloatArray, a: Int, b: Int) = hypot((x[a] - x[b]) * Hand.ASPECT, y[a] - y[b])

    private fun onResult(res: HandLandmarkerResult) {
        val now = SystemClock.uptimeMillis()
        frames++; if (now - fpsT > 1000) { fps = frames * 1000f / (now - fpsT); frames = 0; fpsT = now }
        val lms = res.landmarks(); val hd = res.handednesses()
        val used = BooleanArray(2); val out = ArrayList<HandState>()
        val a0 = 1f - 0.85f * Prefs.smoothing
        val tanV = tan(Math.toRadians(Prefs.camFov.toDouble() / 2)).toFloat() * 9f / 16f
        for (i in lms.indices) {
            val rx = FloatArray(21) { lms[i][it].x() }; val ry = FloatArray(21) { lms[i][it].y() }
            if (Prefs.camMirror) for (k in 0 until 21) rx[k] = 1f - rx[k]
            // slot: nearest previous wrist
            var slot = -1; var best = 1e9f
            for (s in 0 until 2) if (!used[s]) {
                val m = mem[s]; val dist = if (m.has && now - m.t < 600) hypot(m.x[0] - rx[0], m.y[0] - ry[0]) else 0.5f
                if (dist < best) { best = dist; slot = s }
            }
            if (slot < 0) continue
            used[slot] = true
            val m = mem[slot]
            val x = FloatArray(21); val y = FloatArray(21)
            for (k in 0 until 21) {
                if (!m.has || now - m.t > 600) { x[k] = rx[k]; y[k] = ry[k] }
                else {
                    val sp = hypot(rx[k] - m.x[k], ry[k] - m.y[k])
                    val a = min(1f, a0 + sp * 12f)
                    x[k] = m.x[k] + (rx[k] - m.x[k]) * a; y[k] = m.y[k] + (ry[k] - m.y[k]) * a
                }
            }
            val size = max(d(x, y, 0, 9), 1e-4f)
            fun ext(tip: Int, pip: Int) = d(x, y, tip, 0) > d(x, y, pip, 0) * 1.08f
            val index = ext(8, 6); val mid = ext(12, 10); val ring = ext(16, 14); val pinky = ext(20, 18)
            val thumb = d(x, y, 4, 17) > d(x, y, 2, 17) * 1.05f
            val ratio = d(x, y, 4, 8) / size
            val pinch = if (m.pinch) ratio < 0.45f else ratio < 0.30f
            val curled3 = !mid && !ring && !pinky
            val fist = curled3 && !index && d(x, y, 8, 0) < d(x, y, 6, 0) && ratio > 0.30f
            val palm = index && mid && ring && pinky && thumb
            val point = index && curled3 && !pinch
            // depth in metres from apparent size of wrist->middle MCP (~9.5 cm)
            val f = 0.5f / tanV
            val depth = (f * 0.095f / size).coerceIn(0.1f, 2f)
            var vel = 0f
            if (m.has && now - m.t in 1..400) { val dt = (now - m.t) / 1000f; vel = m.depthVel * 0.6f + ((depth - m.depth) / dt) * 0.4f }
            val dep = if (m.has && now - m.t < 600) m.depth * 0.7f + depth * 0.3f else depth
            var swipe = 0
            val cx = x[9]
            if (palm && m.has && now - m.t in 1..300 && now > m.swipeUntil) {
                val vx = (cx - m.cx) / ((now - m.t) / 1000f)
                if (abs(vx) > 1.6f) { swipe = if (vx > 0) 1 else -1; m.swipeUntil = now + 900 }
            }
            m.x = x; m.y = y; m.has = true; m.pinch = pinch; m.depth = dep; m.depthVel = vel; m.t = now; m.cx = cx
            val label = try { hd[i][0].categoryName() } catch (e: Exception) { "Left" }
            out.add(HandState(slot, x, y, label == "Left", pinch, ratio, fist, palm, point, dep, vel, swipe, now))
        }
        for (s in 0 until 2) if (!used[s] && now - mem[s].t > 600) mem[s].has = false
        hands = out
    }
}
