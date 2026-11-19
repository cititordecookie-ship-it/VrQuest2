package com.vrquest

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.*

class CamFeed {
    var tex = 0
    @Volatile var st: SurfaceTexture? = null
    @Volatile var rotation = 0
    val newFrame = AtomicBoolean(false)
    var hasFrame = false
    val stMat = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
}

class VrRenderer(private val scene: VrScene, private val head: HeadTracker, private val hands: HandTracker, private val onGlReady: () -> Unit) : GLSurfaceView.Renderer {
    val cam = CamFeed()
    @Volatile private var panoBmp: Bitmap? = null
    private var panoNew = false
    private var panoTex = 0

    private var progPanel = 0; private var progShape = 0; private var progEnv = 0; private var progDist = 0
    private var w = 1; private var h = 1
    private var fbo = 0; private var fboTex = 0; private var fw = 0; private var fh = 0
    private val proj = FloatArray(16); private val view = FloatArray(16); private val vp = FloatArray(16); private val mvp = FloatArray(16)
    private val tmp = FloatArray(16); private val tmp2 = FloatArray(16)
    private lateinit var quad: FloatBuffer
    private var lastNs = 0L; private var time = 0f
    private var frames = 0; private var fpsT = 0L
    @Volatile var fps = 0f
    private val shapeBuf = ByteBuffer.allocateDirect(5 * 4 * 6 * 1400).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val camIdentity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    fun setPano(b: Bitmap?) { panoBmp = b; panoNew = true }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        progPanel = Gl.program(Shaders.PANEL_VS, Shaders.PANEL_FS)
        progShape = Gl.program(Shaders.SHAPE_VS, Shaders.SHAPE_FS)
        progEnv = Gl.program(Shaders.FULL_VS, Shaders.ENV_FS)
        progDist = Gl.program(Shaders.FULL_VS, Shaders.DIST_FS)
        quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }
        // camera feed
        val t = IntArray(1); GLES20.glGenTextures(1, t, 0); cam.tex = t[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cam.tex)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val st = SurfaceTexture(cam.tex)
        st.setOnFrameAvailableListener { cam.newFrame.set(true) }
        cam.st = st; cam.hasFrame = false
        // pano tex
        GLES20.glGenTextures(1, t, 0); panoTex = t[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, panoTex)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        panoNew = panoBmp != null
        fbo = 0
        for (p in scene.panels) p.releaseGl()
        lastNs = 0L
        onGlReady()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) { w = width; h = height; fbo = 0 }

    private fun ensureFbo() {
        val sc = Prefs.renderScale
        val nw = max(64, (w * sc).toInt() and 1.inv()); val nh = max(64, (h * sc).toInt())
        if (fbo != 0 && nw == fw && nh == fh) return
        if (fbo != 0) { GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0); GLES20.glDeleteTextures(1, intArrayOf(fboTex), 0) }
        fw = nw; fh = nh
        val t = IntArray(1); GLES20.glGenTextures(1, t, 0); fboTex = t[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, fw, fh, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val f = IntArray(1); GLES20.glGenFramebuffers(1, f, 0); fbo = f[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTex, 0)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val dt = if (lastNs == 0L) 0.016f else min(0.1f, (now - lastNs) / 1e9f)
        lastNs = now; time += dt
        frames++; if (now - fpsT > 1_000_000_000L) { fps = frames * 1e9f / (now - fpsT); frames = 0; fpsT = now; scene.rendererFps = fps }

        // uploads
        if (panoNew) { panoNew = false; panoBmp?.let { GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, panoTex); GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, it, 0) } }
        if (cam.newFrame.getAndSet(false)) { try { cam.st?.updateTexImage(); cam.st?.getTransformMatrix(cam.stMat); cam.hasFrame = true } catch (_: Exception) {} }
        for (p in scene.panels) {
            if (p.tex == 0) p.initGl()
            if (p.frameAvail.getAndSet(false)) { try { p.st?.updateTexImage(); p.st?.getTransformMatrix(p.stMat); p.hasFrame = true } catch (_: Exception) {} }
        }
        try { scene.update(dt, head, hands.hands) } catch (e: Throwable) { Crash.log("update", e) }

        if (!Prefs.vrMode) {
            // flat single-view mode (test on the phone without a headset)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glClearColor(0f, 0f, 0f, 1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glDisable(GLES20.GL_DEPTH_TEST); GLES20.glDisable(GLES20.GL_CULL_FACE)
            try { drawEye(2, 0, 0, w, h) } catch (e: Throwable) { Crash.log("drawFlat", e) }
        } else {
        ensureFbo()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST); GLES20.glDisable(GLES20.GL_CULL_FACE)
        try { drawEye(0, 0, 0, fw / 2, fh); drawEye(1, fw / 2, 0, fw / 2, fh) } catch (e: Throwable) { Crash.log("drawEye", e) }

        // distortion to screen
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glClearColor(0f, 0f, 0f, 1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(progDist)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(progDist, "uScene"), 0)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(progDist, "uK"), Prefs.k1, Prefs.k2)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(progDist, "uZoom"), Prefs.zoom)
        val aspect = (w / 2f) / h
        GLES20.glUniform1f(GLES20.glGetUniformLocation(progDist, "uAspect"), aspect)
        val loc = GLES20.glGetAttribLocation(progDist, "aPos")
        GLES20.glEnableVertexAttribArray(loc); GLES20.glVertexAttribPointer(loc, 2, GLES20.GL_FLOAT, false, 0, quad)
        for (eye in 0..1) {
            GLES20.glViewport(eye * w / 2, 0, w / 2, h)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(progDist, "uEye"), eye.toFloat())
            GLES20.glUniform1f(GLES20.glGetUniformLocation(progDist, "uCenter"), Prefs.lensSep * (if (eye == 0) 1f else -1f))
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }
        GLES20.glDisableVertexAttribArray(loc)
        }

        if (!Prefs.fps60) { val el = (System.nanoTime() - now) / 1_000_000L; if (el < 30) try { Thread.sleep(30 - el) } catch (_: Exception) {} }
    }

    private fun mat3(m: FloatArray) = floatArrayOf(m[0], m[1], m[2], m[4], m[5], m[6], m[8], m[9], m[10])

    private fun drawEye(eye: Int, vx: Int, vy: Int, vw: Int, vh: Int) {
        GLES20.glViewport(vx, vy, vw, vh)
        val aspect = vw.toFloat() / vh
        Matrix.perspectiveM(proj, 0, Prefs.fov, aspect, 0.05f, 100f)
        val sx = if (eye == 0) -1f else if (eye == 1) 1f else 0f
        val e2s = head.eyeToScene
        // world->eye rotation = transpose
        val hinv = FloatArray(16); Matrix.transposeM(hinv, 0, e2s, 0)
        Matrix.setIdentityM(tmp, 0); Matrix.translateM(tmp, 0, -sx * Prefs.ipdMm / 2000f, 0f, 0f)
        Matrix.multiplyMM(view, 0, tmp, 0, hinv, 0)
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)
        val tanY = tan(Math.toRadians(Prefs.fov.toDouble() / 2)).toFloat()

        // background
        GLES20.glDisable(GLES20.GL_BLEND)
        val usePass = Prefs.passthrough && cam.hasFrame
        if (!usePass) {
            GLES20.glUseProgram(progEnv)
            GLES20.glUniformMatrix3fv(GLES20.glGetUniformLocation(progEnv, "uRot"), 1, false, mat3(e2s), 0)
            GLES20.glUniform2f(GLES20.glGetUniformLocation(progEnv, "uTan"), tanY * aspect, tanY)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(progEnv, "uTime"), time)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(progEnv, "uEnv"), if (Prefs.passthrough) 0 else Prefs.env)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(progEnv, "uFlash"), scene.flash)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, panoTex)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(progEnv, "uPano"), 0)
            val loc = GLES20.glGetAttribLocation(progEnv, "aPos")
            GLES20.glEnableVertexAttribArray(loc); GLES20.glVertexAttribPointer(loc, 2, GLES20.GL_FLOAT, false, 0, quad)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(loc)
        } else {
            GLES20.glClearColor(0f, 0f, 0f, 1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            drawPassthrough(aspect)
        }

        GLES20.glEnable(GLES20.GL_BLEND); GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        // panels, far to near
        val list = scene.panels.sortedByDescending { if (it.headLocked) 0f else it.pos.len() }
        for (p in list) drawPanel(p, sx)

        // cursors in world
        for (c in scene.cursors) drawCursor(c)
        // hand overlay (head-locked, center eye)
        drawHands(aspect)
    }

    private fun drawPassthrough(aspect: Float) {
        val D = 1.5f
        val tanH = tan(Math.toRadians(Prefs.camFov.toDouble() / 2)).toFloat() * Prefs.ptZoom
        val hw = tanH * D; val hh = hw * 9f / 16f
        Matrix.setIdentityM(tmp, 0); Matrix.translateM(tmp, 0, 0f, 0f, -D); Matrix.scaleM(tmp, 0, hw, hh, 1f)
        Matrix.multiplyMM(mvp, 0, proj, 0, tmp, 0)
        // uv rotation (camera rotation) then SurfaceTexture transform
        val rot = FloatArray(16); Matrix.setIdentityM(rot, 0)
        Matrix.translateM(rot, 0, 0.5f, 0.5f, 0f)
        Matrix.rotateM(rot, 0, if (Prefs.camMirror) 180f + cam.rotation else cam.rotation.toFloat(), 0f, 0f, 1f)
        Matrix.translateM(rot, 0, -0.5f, -0.5f, 0f)
        val tm = FloatArray(16); Matrix.multiplyMM(tm, 0, cam.stMat, 0, rot, 0)
        GLES20.glUseProgram(progPanel)
        setPanelUniforms(mvp, tm, 1f, 1f, 1f, 0f, 0f, 1f)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cam.tex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(progPanel, "uTex"), 0)
        val pos = floatArrayOf(-1f, 1f, 0f, 0f, 1f, -1f, -1f, 0f, 0f, 0f, 1f, 1f, 0f, 1f, 1f, 1f, -1f, 0f, 1f, 0f)
        val fb = ByteBuffer.allocateDirect(pos.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(pos); position(0) }
        drawMesh(progPanel, fb, 4)
    }

    private fun setPanelUniforms(m: FloatArray, tm: FloatArray, alpha: Float, sw: Float, sh: Float, radius: Float, hover: Float, bright: Float) {
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(progPanel, "uMVP"), 1, false, m, 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(progPanel, "uTexMat"), 1, false, tm, 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(progPanel, "uAlpha"), alpha)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(progPanel, "uSize"), sw, sh)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(progPanel, "uRadius"), radius)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(progPanel, "uHover"), hover)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(progPanel, "uBright"), bright)
    }

    private fun drawMesh(prog: Int, fb: FloatBuffer, count: Int) {
        val a = GLES20.glGetAttribLocation(prog, "aPos"); val b = GLES20.glGetAttribLocation(prog, "aUV")
        fb.position(0); GLES20.glEnableVertexAttribArray(a); GLES20.glVertexAttribPointer(a, 3, GLES20.GL_FLOAT, false, 20, fb)
        fb.position(3); GLES20.glEnableVertexAttribArray(b); GLES20.glVertexAttribPointer(b, 2, GLES20.GL_FLOAT, false, 20, fb)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, count)
        GLES20.glDisableVertexAttribArray(a); GLES20.glDisableVertexAttribArray(b)
        fb.position(0)
    }

    private fun drawPanel(p: Panel, sx: Float) {
        if (!p.hasFrame || p.alpha <= 0.01f || p.tex == 0) return
        p.ensureMesh()
        val model = p.model()
        if (p.headLocked) {
            Matrix.setIdentityM(tmp, 0); Matrix.translateM(tmp, 0, -sx * Prefs.ipdMm / 2000f, 0f, 0f)
            Matrix.multiplyMM(tmp2, 0, proj, 0, tmp, 0); Matrix.multiplyMM(mvp, 0, tmp2, 0, model, 0)
        } else Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)
        GLES20.glUseProgram(progPanel)
        setPanelUniforms(mvp, p.stMat, p.alpha, p.worldW, p.worldH, 0.045f.coerceAtMost(p.worldH / 3), p.hover, 1f)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, p.tex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(progPanel, "uTex"), 0)
        drawMesh(progPanel, p.mesh!!, p.meshCount)
    }

    // ---- shapes ----
    private fun shapeBegin(m: FloatArray, color: FloatArray, mode: Float) {
        GLES20.glUseProgram(progShape)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(progShape, "uMVP"), 1, false, m, 0)
        GLES20.glUniform4fv(GLES20.glGetUniformLocation(progShape, "uColor"), 1, color, 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(progShape, "uMode"), mode)
        shapeBuf.clear()
    }
    private fun v(x: Float, y: Float, z: Float, u: Float, w: Float) { shapeBuf.put(x); shapeBuf.put(y); shapeBuf.put(z); shapeBuf.put(u); shapeBuf.put(w) }
    private fun shapeEnd() {
        val n = shapeBuf.position() / 5
        if (n == 0) return
        shapeBuf.position(0)
        val a = GLES20.glGetAttribLocation(progShape, "aPos"); val b = GLES20.glGetAttribLocation(progShape, "aUV")
        GLES20.glEnableVertexAttribArray(a); GLES20.glVertexAttribPointer(a, 3, GLES20.GL_FLOAT, false, 20, shapeBuf)
        shapeBuf.position(3); GLES20.glEnableVertexAttribArray(b); GLES20.glVertexAttribPointer(b, 2, GLES20.GL_FLOAT, false, 20, shapeBuf)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, n)
        GLES20.glDisableVertexAttribArray(a); GLES20.glDisableVertexAttribArray(b)
        shapeBuf.clear()
    }
    private fun disc(cx: Float, cy: Float, cz: Float, r: Float) {
        v(cx - r, cy - r, cz, -1f, -1f); v(cx + r, cy - r, cz, 1f, -1f); v(cx - r, cy + r, cz, -1f, 1f)
        v(cx + r, cy - r, cz, 1f, -1f); v(cx + r, cy + r, cz, 1f, 1f); v(cx - r, cy + r, cz, -1f, 1f)
    }
    private fun ribbon(x0: Float, y0: Float, x1: Float, y1: Float, z: Float, wd: Float) {
        val dx = x1 - x0; val dy = y1 - y0; val l = max(hypot(dx, dy), 1e-5f)
        val nx = -dy / l * wd; val ny = dx / l * wd
        v(x0 + nx, y0 + ny, z, 1f, 0f); v(x0 - nx, y0 - ny, z, -1f, 0f); v(x1 + nx, y1 + ny, z, 1f, 0f)
        v(x0 - nx, y0 - ny, z, -1f, 0f); v(x1 - nx, y1 - ny, z, -1f, 0f); v(x1 + nx, y1 + ny, z, 1f, 0f)
    }
    private fun tri(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float, z: Float) { v(ax, ay, z, 0f, 0f); v(bx, by, z, 0f, 0f); v(cx, cy, z, 0f, 0f) }

    private fun drawCursor(c: Cursor) {
        val b = c.basis
        // billboard in the panel plane at hit point
        val r = c.radius
        val m = FloatArray(16); Matrix.setIdentityM(m, 0)
        m[0] = b[0].x; m[1] = b[0].y; m[2] = b[0].z; m[4] = b[1].x; m[5] = b[1].y; m[6] = b[1].z; m[8] = b[2].x; m[9] = b[2].y; m[10] = b[2].z
        m[12] = c.pos.x + b[2].x * 0.01f; m[13] = c.pos.y + b[2].y * 0.01f; m[14] = c.pos.z + b[2].z * 0.01f
        Matrix.multiplyMM(mvp, 0, vp, 0, m, 0)
        shapeBegin(mvp, floatArrayOf(c.color[0], c.color[1], c.color[2], 0.95f), 1f); disc(0f, 0f, 0f, r * (if (c.pressed) 0.7f else 1f)); shapeEnd()
        shapeBegin(mvp, floatArrayOf(1f, 1f, 1f, if (c.pressed) 1f else 0.7f), 0f); disc(0f, 0f, 0f, r * 0.22f); shapeEnd()
        if (c.progress > 0f) { shapeBegin(mvp, floatArrayOf(0.4f, 1f, 0.6f, 0.4f), 0f); disc(0f, 0f, 0f, r * 0.9f * c.progress); shapeEnd() }
    }

    private fun drawHands(aspect: Float) {
        val hs = hands.hands
        if (hs.isEmpty() || !Prefs.handsOn) return
        val D = 1.5f
        val hw = tan(Math.toRadians(Prefs.camFov.toDouble() / 2)).toFloat() * D * Prefs.ptZoom; val hh = hw * 9f / 16f
        Matrix.setIdentityM(tmp, 0); Matrix.translateM(tmp, 0, 0f, 0f, -D)
        Matrix.multiplyMM(mvp, 0, proj, 0, tmp, 0)
        val now = SystemClock.uptimeMillis()
        for (hd in hs) {
            if (now - hd.time > 350) continue
            val px = FloatArray(21) { (hd.x[it] - 0.5f) * 2 * hw }; val py = FloatArray(21) { (0.5f - hd.y[it]) * 2 * hh }
            val base = when { hd.pinch -> floatArrayOf(0.35f, 1f, 0.55f); hd.fist -> floatArrayOf(1f, 0.65f, 0.25f); else -> floatArrayOf(0.3f, 0.85f, 1f) }
            // palm fill (contour)
            shapeBegin(mvp, floatArrayOf(base[0], base[1], base[2], 0.16f), 0f)
            val pal = intArrayOf(0, 1, 5, 9, 13, 17)
            for (i in 1 until pal.size - 1) tri(px[pal[0]], py[pal[0]], px[pal[i]], py[pal[i]], px[pal[i + 1]], py[pal[i + 1]], 0f)
            shapeEnd()
            // outer glow (wide), then core line
            shapeBegin(mvp, floatArrayOf(base[0], base[1], base[2], 0.22f), 0f)
            for ((a, b) in Hand.BONES) ribbon(px[a], py[a], px[b], py[b], 0f, 0.055f * D * 0.5f)
            shapeEnd()
            shapeBegin(mvp, floatArrayOf(1f, 1f, 1f, 0.95f), 0f)
            for ((a, b) in Hand.BONES) ribbon(px[a], py[a], px[b], py[b], 0f, 0.006f * D)
            shapeEnd()
            // joints + tips
            shapeBegin(mvp, floatArrayOf(base[0], base[1], base[2], 1f), 0f)
            for (i in 0 until 21) disc(px[i], py[i], 0f, 0.008f * D)
            shapeEnd()
            shapeBegin(mvp, floatArrayOf(1f, 1f, 1f, 1f), 0f)
            for (t in Hand.TIPS) disc(px[t], py[t], 0f, 0.013f * D)
            shapeEnd()
            if (hd.pinch) { shapeBegin(mvp, floatArrayOf(0.4f, 1f, 0.6f, 0.9f), 1f); disc((px[4] + px[8]) / 2, (py[4] + py[8]) / 2, 0f, 0.045f * D); shapeEnd() }
        }
    }
}

class Cursor(val pos: V3, val basis: Array<V3>, val radius: Float, val color: FloatArray, val pressed: Boolean, val progress: Float = 0f)
