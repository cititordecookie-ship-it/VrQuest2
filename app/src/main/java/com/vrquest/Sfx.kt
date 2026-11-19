package com.vrquest

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.*
import kotlin.random.Random

/** Synthesised audio: ambient loops (rain / storm / ocean / wind) and short effects. No asset files. */
object Sfx {
    private const val SR = 22050
    private val cache = HashMap<String, ShortArray>()

    private fun attrs() = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
    private fun fmt() = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(SR).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()

    fun play(buf: ShortArray, vol: Float = 1f) {
        try {
            val t = AudioTrack(attrs(), fmt(), buf.size * 2, AudioTrack.MODE_STATIC, 0)
            t.write(buf, 0, buf.size)
            t.setVolume((vol * Prefs.volume).coerceIn(0f, 1f))
            t.play()
            mainHandler.postDelayed({ try { t.release() } catch (_: Exception) {} }, buf.size * 1000L / SR + 300)
        } catch (_: Exception) {}
    }

    fun note(freq: Float) = play(cache.getOrPut("n$freq") {
        val n = (SR * 1.4f).toInt()
        ShortArray(n) { i ->
            val t = i / SR.toFloat(); val env = exp(-t * 3.2f) * min(1f, t * 400f)
            val s = sin(2 * PI * freq * t) + 0.5 * sin(4 * PI * freq * t) * exp(-t * 4f) + 0.25 * sin(6 * PI * freq * t) * exp(-t * 6f)
            (s * env * 9000).toInt().toShort()
        }
    }, 0.9f)

    fun pop() = play(cache.getOrPut("pop") { ShortArray(2200) { i -> val t = i / SR.toFloat(); (sin(2 * PI * (900 - 5000 * t) * t) * exp(-t * 40) * 14000).toInt().toShort() } })
    fun slice() = play(cache.getOrPut("slice") { ShortArray(4400) { i -> val t = i / SR.toFloat(); ((Random(i).nextFloat() - 0.5f) * exp(-t * 18) * 16000).toInt().toShort() } })
    fun boom() = play(cache.getOrPut("boom") { ShortArray(SR) { i -> val t = i / SR.toFloat(); (sin(2 * PI * 60 * t) * exp(-t * 5) * 20000).toInt().toShort() } })
    fun click() = play(cache.getOrPut("click") { ShortArray(700) { i -> val t = i / SR.toFloat(); (sin(2 * PI * 1800 * t) * exp(-t * 120) * 9000).toInt().toShort() } }, 0.5f)
    fun thunder() = play(cache.getOrPut("thunder") {
        var lp = 0f
        ShortArray(SR * 4) { i -> val t = i / SR.toFloat(); lp += ((Random(i).nextFloat() - 0.5f) - lp) * 0.02f
            (lp * 60000 * exp(-t * 1.1f) * min(1f, t * 8f)).toInt().coerceIn(-32000, 32000).toShort() }
    }, 1f)

    // ---- ambient loop ----
    @Volatile private var kind = 0 // 0 off 1 rain 2 storm 3 ocean 4 wind
    private var thread: Thread? = null
    @Volatile private var running = false

    fun ambient(k: Int) {
        kind = k
        if (k == 0) { stopAmbient(); return }
        if (thread?.isAlive == true) return
        running = true
        thread = Thread {
            val min = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val tr = try { AudioTrack(attrs(), fmt(), max(min, 8192), AudioTrack.MODE_STREAM, 0) } catch (e: Exception) { return@Thread }
            tr.play()
            val buf = ShortArray(2048); var lp1 = 0f; var lp2 = 0f; var ph = 0f; val rnd = Random(7)
            while (running) {
                val kk = kind
                tr.setVolume(Prefs.volume)
                for (i in buf.indices) {
                    val w = rnd.nextFloat() * 2 - 1
                    ph += 1f / SR
                    var s = 0f
                    when (kk) {
                        1, 2 -> { lp1 += (w - lp1) * 0.35f; lp2 += (w - lp2) * 0.01f
                            val drop = if (rnd.nextFloat() < 0.002f) (rnd.nextFloat() * 0.8f) else 0f
                            s = (lp1 - lp2) * (if (kk == 2) 0.55f else 0.35f) + drop * 0.3f }
                        3 -> { lp1 += (w - lp1) * 0.02f; s = lp1 * 6f * (0.5f + 0.5f * sin(ph * 2 * PI.toFloat() * 0.12f)) }
                        4 -> { lp1 += (w - lp1) * 0.05f; lp2 += (lp1 - lp2) * 0.05f; s = lp2 * 8f * (0.6f + 0.4f * sin(ph * 2 * PI.toFloat() * 0.07f)) }
                    }
                    buf[i] = (s.coerceIn(-1f, 1f) * 12000).toInt().toShort()
                }
                tr.write(buf, 0, buf.size)
            }
            try { tr.stop(); tr.release() } catch (_: Exception) {}
        }.also { it.isDaemon = true; it.start() }
    }

    fun stopAmbient() { running = false; thread = null }
}
