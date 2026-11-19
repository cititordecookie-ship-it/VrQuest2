package com.vrquest

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import kotlin.math.*
import kotlin.random.Random

/** Base for Canvas games. The panel painter calls draw() every frame, so onDraw advances the simulation. */
abstract class GameView(ctx: Context) : View(ctx) {
    protected val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var last = 0L
    protected var ptrs = HashMap<Int, PointF>()
    protected var hover: PointF? = null
    protected var lastPts = HashMap<Int, PointF>()

    abstract fun step(dt: Float)
    abstract fun render(c: Canvas)
    open fun pointerMoved(id: Int, from: PointF?, to: PointF) {}
    open fun pointerDown(id: Int, x: Float, y: Float) {}

    override fun onDraw(c: Canvas) {
        val now = System.nanoTime(); val dt = if (last == 0L) 0.016f else min(0.05f, (now - last) / 1e9f); last = now
        step(dt); render(c)
    }
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val a = e.actionMasked
        for (i in 0 until e.pointerCount) {
            val id = e.getPointerId(i); val to = PointF(e.getX(i), e.getY(i))
            if (a == MotionEvent.ACTION_DOWN || (a == MotionEvent.ACTION_POINTER_DOWN && i == e.actionIndex)) { ptrs[id] = to; pointerDown(id, to.x, to.y) }
            else if (a == MotionEvent.ACTION_MOVE) { pointerMoved(id, ptrs[id], to); ptrs[id] = to }
        }
        if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) ptrs.clear()
        if (a == MotionEvent.ACTION_POINTER_UP) ptrs.remove(e.getPointerId(e.actionIndex))
        return true
    }
    override fun onHoverEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_HOVER_EXIT) { hover = null; return true }
        val to = PointF(e.x, e.y); pointerMoved(-1, hover, to); hover = to
        return true
    }
    protected fun text(c: Canvas, t: String, x: Float, y: Float, size: Float, color: Int = Color.WHITE, align: Paint.Align = Paint.Align.LEFT) {
        p.style = Paint.Style.FILL; p.color = color; p.textSize = size; p.textAlign = align; p.typeface = Typeface.DEFAULT_BOLD; c.drawText(t, x, y, p); p.textAlign = Paint.Align.LEFT
    }
}

abstract class GameApp(ctx: Context, host: VrHost) : VrApp(ctx, host) {
    override val icon get() = "games"
    override val singleton get() = true
}

// ================= PIANO =================
class PianoApp(ctx: Context, host: VrHost) : GameApp(ctx, host) {
    override val id = "piano"; override val title = L.t("Piano", "Pian"); override val pxH = 520
    override fun build(): View = PianoView(ctx)

    private class PianoView(ctx: Context) : GameView(ctx) {
        private val whiteSemis = intArrayOf(0, 2, 4, 5, 7, 9, 11)
        private val blackAfter = booleanArrayOf(true, true, false, true, true, true, false) // black key after white i%7
        private val nWhite = 14
        private val lit = HashMap<Int, Float>() // key idx -> glow
        private val down = HashMap<Int, Int>() // pointer -> key
        private val song = intArrayOf(0, 0, 4, 4, 5, 5, 4, 3, 3, 2, 2, 1, 1, 0) // twinkle (white key indexes)
        private var hintOn = false; private var songPos = 0
        private fun freq(semi: Int) = 261.63f * 2.0.pow(semi / 12.0).toFloat()
        private fun whiteSemi(i: Int) = (i / 7) * 12 + whiteSemis[i % 7]

        private fun keyAt(x: Float, y: Float): Int { // returns semitone-coded id: white = i, black = 100+i
            val kw = width / nWhite.toFloat(); val top = 90f
            if (y < top) { if (x in 20f..320f && y in 12f..80f) return 999; return -1 }
            val bh = (height - top) * 0.6f
            if (y < top + bh) for (i in 0 until nWhite - 1) if (blackAfter[i % 7]) { val cx = (i + 1) * kw; if (abs(x - cx) < kw * 0.3f) return 100 + i }
            return min(nWhite - 1, (x / kw).toInt())
        }
        override fun pointerDown(id: Int, x: Float, y: Float) { press(id, x, y) }
        private fun press(id: Int, x: Float, y: Float) {
            val k = keyAt(x, y); if (k < 0) return
            if (k == 999) { hintOn = !hintOn; songPos = 0; return }
            down[id] = k
            val semi = if (k >= 100) whiteSemi(k - 100) + 1 else whiteSemi(k)
            Sfx.note(freq(semi)); lit[k] = 1f
            if (hintOn && k < 100 && k == song[songPos % song.size]) songPos++
        }
        override fun pointerMoved(id: Int, from: PointF?, to: PointF) {
            if (id < 0) return
            val k = keyAt(to.x, to.y); if (k != down[id] && k >= 0 && k != 999) press(id, to.x, to.y)
        }
        override fun step(dt: Float) { for (k in lit.keys.toList()) { val v = lit[k]!! - dt * 2.2f; if (v <= 0f) lit.remove(k) else lit[k] = v }; if (ptrs.isEmpty()) down.clear() }
        override fun render(c: Canvas) {
            c.drawColor(0xFF10131A.toInt())
            p.style = Paint.Style.FILL; p.color = if (hintOn) Theme.accent else 0xFF2A2E3A.toInt(); c.drawRoundRect(20f, 12f, 320f, 80f, 30f, 30f, p)
            text(c, if (hintOn) L.t("Hints: ON", "Indicii: ON") else L.t("Song hints", "Indicii melodie"), 170f, 58f, 30f, if (hintOn) Color.BLACK else Color.WHITE, Paint.Align.CENTER)
            text(c, L.t("Use both hands: pinch or poke the keys", "Folosește ambele mâini: ciupește sau apasă tastele"), width - 20f, 58f, 28f, 0xFF9AA3B5.toInt(), Paint.Align.RIGHT)
            val kw = width / nWhite.toFloat(); val top = 90f; val bh = (height - top) * 0.6f
            val next = if (hintOn) song[songPos % song.size] else -1
            for (i in 0 until nWhite) {
                val g = lit[i] ?: 0f
                p.style = Paint.Style.FILL; p.color = Color.rgb(250 - (g * 120).toInt(), 250 - (g * 40).toInt(), 250); if (i == next) p.color = 0xFFA5F0B0.toInt()
                c.drawRoundRect(i * kw + 2, top, (i + 1) * kw - 2, height - 4f, 14f, 14f, p)
            }
            for (i in 0 until nWhite - 1) if (blackAfter[i % 7]) {
                val g = lit[100 + i] ?: 0f; val cx = (i + 1) * kw
                p.color = Color.rgb(20 + (g * 40).toInt(), 20 + (g * 150).toInt(), 24 + (g * 200).toInt())
                c.drawRoundRect(cx - kw * 0.3f, top, cx + kw * 0.3f, top + bh, 10f, 10f, p)
            }
        }
    }
}

// ================= BUBBLE POP =================
class BubbleApp(ctx: Context, host: VrHost) : GameApp(ctx, host) {
    override val id = "bubbles"; override val title = L.t("Bubble Pop", "Sparge bule"); override val pxH = 720
    override fun build(): View = BubbleView(ctx)

    private class BubbleView(ctx: Context) : GameView(ctx) {
        class B(var x: Float, var y: Float, val r: Float, val vy: Float, val col: Int, var pop: Float = 0f, val ph: Float = Random.nextFloat() * 6f)
        private val bubbles = ArrayList<B>()
        private var score = 0; private var left = 60f; private var spawn = 0f; private var combo = 0; private var comboT = 0f; private var over = false
        private var t = 0f
        override fun pointerDown(id: Int, x: Float, y: Float) { if (over) { reset() } else hit(x, y, x, y) }
        private fun reset() { bubbles.clear(); score = 0; left = 60f; over = false; combo = 0 }
        override fun pointerMoved(id: Int, from: PointF?, to: PointF) { if (!over) hit(from?.x ?: to.x, from?.y ?: to.y, to.x, to.y) else if (id >= 0) {} }
        private fun hit(x0: Float, y0: Float, x1: Float, y1: Float) {
            for (b in bubbles) if (b.pop == 0f && segDist(b.x, b.y, x0, y0, x1, y1) < b.r + 14f) {
                b.pop = 0.001f; combo++; comboT = 1.2f; score += 10 * min(combo, 5); Sfx.pop()
            }
        }
        override fun step(dt: Float) {
            t += dt
            if (over) return
            left -= dt; if (left <= 0f) { over = true; return }
            comboT -= dt; if (comboT <= 0f) combo = 0
            spawn -= dt
            if (spawn <= 0f) { spawn = 0.35f + Random.nextFloat() * 0.35f; val r = 40f + Random.nextFloat() * 50f
                bubbles.add(B(r + Random.nextFloat() * (width - 2 * r), height + r, r, -(90f + Random.nextFloat() * 120f), Color.HSVToColor(200, floatArrayOf(Random.nextFloat() * 360f, 0.55f, 1f)))) }
            val it = bubbles.iterator()
            while (it.hasNext()) { val b = it.next(); if (b.pop > 0f) { b.pop += dt * 4f; if (b.pop > 1f) it.remove() } else { b.y += b.vy * dt; b.x += sin(t * 2 + b.ph) * 30f * dt; if (b.y < -b.r) it.remove() } }
        }
        override fun render(c: Canvas) {
            c.drawColor(0xFF0B1B2E.toInt())
            for (b in bubbles) {
                val s = if (b.pop > 0f) 1f + b.pop * 0.6f else 1f; val a = if (b.pop > 0f) (1f - b.pop) else 1f
                p.style = Paint.Style.FILL; p.color = (b.col and 0x00FFFFFF) or ((120 * a).toInt() shl 24); c.drawCircle(b.x, b.y, b.r * s, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = 5f; p.color = Color.argb((230 * a).toInt(), 255, 255, 255); c.drawCircle(b.x, b.y, b.r * s, p)
                p.style = Paint.Style.FILL; p.color = Color.argb((200 * a).toInt(), 255, 255, 255); c.drawCircle(b.x - b.r * 0.35f, b.y - b.r * 0.35f, b.r * 0.15f, p)
            }
            text(c, "${L.t("Score", "Scor")}: $score", 30f, 60f, 44f)
            text(c, "${left.toInt()} s", width - 30f, 60f, 44f, Color.WHITE, Paint.Align.RIGHT)
            if (combo > 1) text(c, "x${min(combo, 5)}", width / 2f, 70f, 54f, 0xFFFFD54F.toInt(), Paint.Align.CENTER)
            if (over) { text(c, L.t("Time! Score $score", "Timp! Scor $score"), width / 2f, height / 2f, 70f, Color.WHITE, Paint.Align.CENTER); text(c, L.t("Pinch to play again", "Ciupește pentru a juca din nou"), width / 2f, height / 2f + 70f, 36f, 0xFF9AA3B5.toInt(), Paint.Align.CENTER) }
        }
    }
}

fun segDist(px: Float, py: Float, x0: Float, y0: Float, x1: Float, y1: Float): Float {
    val dx = x1 - x0; val dy = y1 - y0; val l2 = dx * dx + dy * dy
    val t = if (l2 < 1e-6f) 0f else (((px - x0) * dx + (py - y0) * dy) / l2).coerceIn(0f, 1f)
    return hypot(px - (x0 + t * dx), py - (y0 + t * dy))
}

// ================= FRUIT SLICE =================
class FruitApp(ctx: Context, host: VrHost) : GameApp(ctx, host) {
    override val id = "fruit"; override val title = L.t("Fruit Slice", "Taie fructe"); override val pxH = 720
    override fun build(): View = FruitView(ctx)

    private class FruitView(ctx: Context) : GameView(ctx) {
        class F(var x: Float, var y: Float, var vx: Float, var vy: Float, val e: String, val bomb: Boolean, var half: Int = 0, var rot: Float = 0f, var vr: Float = 0f)
        private val fruits = ArrayList<F>(); private val trail = ArrayList<Triple<Float, Float, Float>>()
        private var score = 0; private var lives = 3; private var spawn = 0f; private var t = 0f
        private val emojis = arrayOf("🍉", "🍊", "🍎", "🍌", "🍍", "🍓")
        override fun pointerDown(id: Int, x: Float, y: Float) { if (lives <= 0) { lives = 3; score = 0; fruits.clear() } }
        override fun pointerMoved(id: Int, from: PointF?, to: PointF) {
            if (lives <= 0 || from == null) return
            val sp = hypot(to.x - from.x, to.y - from.y); if (sp < 8f) return
            trail.add(Triple(to.x, to.y, 0.25f))
            val add = ArrayList<F>()
            for (f in fruits) if (f.half == 0 && segDist(f.x, f.y, from.x, from.y, to.x, to.y) < 62f) {
                if (f.bomb) { Sfx.boom(); lives--; f.half = -1 } else { Sfx.slice(); score += 10; f.half = 1
                    add.add(F(f.x, f.y, f.vx + 160f, f.vy - 60f, f.e, false, 2, 0f, 3f)); f.vx -= 160f; f.vr = -3f }
            }
            fruits.addAll(add)
        }
        override fun step(dt: Float) {
            t += dt; if (lives <= 0) return
            spawn -= dt
            if (spawn <= 0f) { spawn = 0.7f + Random.nextFloat() * 0.8f
                val bomb = Random.nextFloat() < 0.16f
                fruits.add(F(100f + Random.nextFloat() * (width - 200f), height + 60f, (Random.nextFloat() - 0.5f) * 240f, -(900f + Random.nextFloat() * 350f), if (bomb) "💣" else emojis[Random.nextInt(emojis.size)], bomb, 0, 0f, (Random.nextFloat() - 0.5f) * 4f)) }
            val it = fruits.iterator()
            while (it.hasNext()) { val f = it.next(); f.vy += 1100f * dt; f.x += f.vx * dt; f.y += f.vy * dt; f.rot += f.vr * dt
                if (f.y > height + 120f && f.vy > 0) { if (f.half == 0 && !f.bomb) { lives--; if (lives <= 0) {} }; it.remove() } }
            val tt = trail.iterator(); while (tt.hasNext()) { val a = tt.next(); val i = trail.indexOf(a); trail[i] = Triple(a.first, a.second, a.third - dt); if (trail[i].third <= 0f) tt.remove() }
        }
        override fun render(c: Canvas) {
            c.drawColor(0xFF1A2230.toInt())
            p.textSize = 110f; p.textAlign = Paint.Align.CENTER; p.style = Paint.Style.FILL; p.color = Color.WHITE
            for (f in fruits) {
                if (f.half == -1) continue
                c.save(); c.translate(f.x, f.y); c.rotate(Math.toDegrees(f.rot.toDouble()).toFloat())
                if (f.half == 1) c.clipRect(-80f, -100f, 0f, 100f) else if (f.half == 2) c.clipRect(0f, -100f, 80f, 100f)
                c.drawText(f.e, 0f, 36f, p); c.restore()
            }
            p.textAlign = Paint.Align.LEFT
            p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND
            for (i in 1 until trail.size) { val a = trail[i - 1]; val b = trail[i]; p.strokeWidth = 18f * b.third * 3f; p.color = Color.argb((255 * b.third * 4).toInt().coerceAtMost(255), 120, 220, 255); c.drawLine(a.first, a.second, b.first, b.second, p) }
            text(c, "${L.t("Score", "Scor")}: $score", 30f, 60f, 44f)
            text(c, "❤".repeat(max(0, lives)), width - 30f, 60f, 44f, 0xFFFF5252.toInt(), Paint.Align.RIGHT)
            if (lives <= 0) { text(c, L.t("Game over: $score", "Gata: $score"), width / 2f, height / 2f, 70f, Color.WHITE, Paint.Align.CENTER); text(c, L.t("Pinch to play again", "Ciupește pentru a juca din nou"), width / 2f, height / 2f + 70f, 36f, 0xFF9AA3B5.toInt(), Paint.Align.CENTER) }
        }
    }
}

// ================= APE TAG (gorilla chase) =================
class ApeApp(ctx: Context, host: VrHost) : GameApp(ctx, host) {
    override val id = "ape"; override val title = L.t("Ape Tag", "Gorila te prinde"); override val pxH = 720
    override fun build(): View = ApeView(ctx)

    private class ApeView(ctx: Context) : GameView(ctx) {
        private var px = 640f; private var py = 400f
        class G(var x: Float, var y: Float, val sp: Float)
        private val gorillas = ArrayList<G>(); private var bx = 300f; private var by = 300f
        private var score = 0; private var alive = true; private var t = 0f; private var best = 0; private var tagFlash = 0f
        private fun reset() { px = width / 2f; py = height / 2f; gorillas.clear(); gorillas.add(G(80f, 80f, 150f)); score = 0; alive = true; t = 0f; moveBanana() }
        private fun moveBanana() { bx = 80f + Random.nextFloat() * (width - 160f); by = 120f + Random.nextFloat() * (height - 200f) }
        override fun pointerDown(id: Int, x: Float, y: Float) { if (!alive || gorillas.isEmpty()) reset() }
        override fun step(dt: Float) {
            if (gorillas.isEmpty() && width > 0) reset()
            if (!alive) return
            t += dt
            val tgt = ptrs.values.firstOrNull() ?: hover
            if (tgt != null) { px += (tgt.x - px) * min(1f, dt * 9f); py += (tgt.y - py) * min(1f, dt * 9f) }
            for (g in gorillas) { val dx = px - g.x; val dy = py - g.y; val d = max(1f, hypot(dx, dy)); val s = g.sp + t * 2.5f; g.x += dx / d * s * dt; g.y += dy / d * s * dt
                if (d < 70f) { alive = false; best = max(best, score); tagFlash = 1f; Sfx.boom() } }
            if (hypot(px - bx, py - by) < 70f) { score++; Sfx.pop(); moveBanana(); if (score % 4 == 0) gorillas.add(G(if (Random.nextBoolean()) 40f else width - 40f, 40f + Random.nextFloat() * (height - 80f), 140f)) }
            tagFlash = max(0f, tagFlash - dt)
        }
        override fun render(c: Canvas) {
            c.drawColor(0xFF1F3B2A.toInt())
            p.style = Paint.Style.FILL; p.color = 0x22FFFFFF; for (i in 0 until 12) c.drawCircle((i * 197) % width.coerceAtLeast(1).toFloat(), (i * 131) % height.coerceAtLeast(1).toFloat(), 60f, p)
            p.textAlign = Paint.Align.CENTER; p.color = Color.WHITE
            p.textSize = 90f; c.drawText("🍌", bx, by + 30f, p)
            p.textSize = 110f; for (g in gorillas) c.drawText("🦍", g.x, g.y + 38f, p)
            p.textSize = 100f; c.drawText("🐵", px, py + 34f, p)
            p.textAlign = Paint.Align.LEFT
            if (tagFlash > 0f) { p.color = Color.argb((120 * tagFlash).toInt(), 255, 0, 0); c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p) }
            text(c, "🍌 $score", 30f, 60f, 44f); text(c, "${L.t("Best", "Record")}: $best", width - 30f, 60f, 40f, 0xFFFFD54F.toInt(), Paint.Align.RIGHT)
            if (!alive) { text(c, L.t("TAGGED!", "TE-A PRINS!"), width / 2f, height / 2f, 90f, 0xFFFF5252.toInt(), Paint.Align.CENTER); text(c, L.t("Pinch to try again", "Ciupește pentru a încerca din nou"), width / 2f, height / 2f + 70f, 36f, Color.WHITE, Paint.Align.CENTER) }
        }
    }
}
