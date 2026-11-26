package com.liuxiansheng.lyricon.app.activity

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.audiofx.Visualizer
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 超级岛环绕律动 · 粒子预览。
 *
 * Visualizer(0) 取整机音频输出的实时波形，沿胶囊周长一圈均匀布点，
 * 每个点按该段能量向外喷淡光粒子：声音大喷得多、飞得快，安静时只剩零星微光。
 * 只在屏幕上画，不录音、不写盘、不上传。
 * 这是调试预览页，观感确认后再把同样的绘制挂到系统岛容器上。
 */
class SpectrumPreviewActivity : Activity() {

    private lateinit var ring: IslandRingView
    private lateinit var info: TextView
    private var tonePlaying = false
    private var toneThread: Thread? = null
    private var toneTrack: AudioTrack? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(4, 5, 8))
            setPadding(dp(16), dp(18), dp(16), dp(14))
        }
        val title = TextView(this).apply {
            text = "超级岛环绕律动 · 粒子预览"
            setTextColor(Color.WHITE)
            textSize = 17f
        }
        info = TextView(this).apply {
            setTextColor(Color.rgb(140, 160, 180))
            textSize = 12f
            setPadding(0, dp(6), 0, dp(12))
        }
        ring = IslandRingView(this)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        for (n in intArrayOf(16, 32, 64, 128, 192, 256)) {
            val b = Button(this)
            b.text = n.toString()
            b.isAllCaps = false
            b.textSize = 12f
            b.setPadding(0, 0, 0, 0)
            b.setOnClickListener {
                ring.setBarCount(n)
                updateInfo()
            }
            row.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

        // 没在放歌时也能看到真实律动：自己放一段固定测试音，让 Visualizer 采到真实波形
        val toneButton = Button(this).apply {
            text = "放测试音"
            isAllCaps = false
            setOnClickListener {
                if (tonePlaying) {
                    stopTone()
                    text = "放测试音"
                } else {
                    playTestTone()
                    text = "停止测试音"
                }
                tonePlaying = !tonePlaying
            }
        }

        root.addView(title)
        root.addView(info)
        root.addView(ring, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(row)
        root.addView(toneButton)
        setContentView(root)

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1001)
        }
        updateInfo()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        ring.restartCapture()
        updateInfo()
    }

    override fun onResume() {
        super.onResume()
        ring.restartCapture()
        updateInfo()
    }

    override fun onPause() {
        ring.stopCapture()
        stopTone()
        tonePlaying = false
        super.onPause()
    }

    private fun updateInfo() {
        val per = ring.perimeterDp()
        val each = (per.toFloat() / ring.barCount).roundToInt()
        val src = if (ring.isLive) "真实音频波形" else "模拟波形（点允许录音权限即真实）"
        info.text = "发射点 ${ring.barCount} · 一圈 ${per}dp · 每点间隔 ${each}dp · $src"
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    private fun playTestTone() {
        stopTone()
        val sr = 44100
        val minBuf = AudioTrack.getMinBufferSize(
            sr,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)
        val track = try {
            AudioTrack(
                AudioManager.STREAM_MUSIC,
                sr,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2,
                AudioTrack.MODE_STREAM
            )
        } catch (t: Throwable) {
            return
        }
        toneTrack = track
        try {
            track.play()
        } catch (t: Throwable) {
            return
        }
        toneThread = Thread {
            val buf = ShortArray(1024)
            var n = 0L
            while (!Thread.currentThread().isInterrupted) {
                for (i in buf.indices) {
                    val t = (n + i) / sr.toFloat()
                    val env = 0.30f + 0.70f * (0.5 + 0.5 * sin(2.0 * Math.PI * 1.6 * t))
                    val v = sin(2.0 * Math.PI * 196.0 * t) * 0.55 +
                        sin(2.0 * Math.PI * 392.0 * t) * 0.30 +
                        sin(2.0 * Math.PI * 784.0 * t) * 0.15
                    buf[i] = (v * env * 22000.0).toInt().coerceIn(-32768, 32767).toShort()
                }
                try {
                    track.write(buf, 0, buf.size)
                } catch (_: Throwable) {
                    break
                }
                n += buf.size
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    private fun stopTone() {
        toneThread?.interrupt()
        toneThread = null
        try {
            toneTrack?.stop()
        } catch (_: Throwable) {
        }
        try {
            toneTrack?.release()
        } catch (_: Throwable) {
        }
        toneTrack = null
    }
}

private class Particle(
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    var life: Float,
    val maxLife: Float,
    val hue: Float,
    val size: Float
)

/**
 * 沿胶囊周长一圈布点、向外喷淡光粒子的自绘视图。
 * 真实岛尺寸按 150dp × 36dp 取，屏幕上放大 2 倍显示以便看清细节。
 */
class IslandRingView(ctx: Context) : View(ctx) {

    companion object {
        private const val ISLAND_W_DP = 150f
        private const val ISLAND_H_DP = 36f
        private const val DISPLAY_SCALE = 2.0f
        private const val MAX_BARS = 512
        private const val MAX_PARTICLES = 1100
    }

    var barCount = 32
        private set

    var isLive = false
        private set

    private val d = resources.displayMetrics.density
    private val islandW = ISLAND_W_DP * d * DISPLAY_SCALE
    private val islandH = ISLAND_H_DP * d * DISPLAY_SCALE
    private val corner = islandH / 2f

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(10, 11, 15) }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.0f * d
        color = Color.argb(70, 170, 210, 250)
    }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.0f * d
    }
    private val camPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(2, 2, 4) }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private val energy = FloatArray(MAX_BARS)
    private val particles = ArrayList<Particle>(MAX_PARTICLES)
    private var visualizer: Visualizer? = null
    private var lastMs = 0L
    private var emitAcc = 0f
    private var simTime = 0f

    fun setBarCount(n: Int) {
        barCount = n.coerceIn(8, MAX_BARS)
        for (i in energy.indices) energy[i] = 0f
        invalidate()
    }

    /** 真实尺寸下一圈的周长（dp）。 */
    fun perimeterDp(): Int =
        (2.0 * (ISLAND_W_DP - ISLAND_H_DP) + Math.PI * ISLAND_H_DP).roundToInt()

    fun restartCapture() {
        stopCapture()
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            isLive = false
            invalidate()
            return
        }
        try {
            val v = Visualizer(0)
            v.captureSize = Visualizer.getCaptureSizeRange()[1]
            v.setDataCaptureListener(
                object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(
                        visualizer: Visualizer?,
                        waveform: ByteArray?,
                        samplingRate: Int
                    ) {
                        if (waveform != null) onWave(waveform)
                    }

                    override fun onFftDataCapture(
                        visualizer: Visualizer?,
                        fft: ByteArray?,
                        samplingRate: Int
                    ) = Unit
                },
                Visualizer.getMaxCaptureRate() / 2,
                true,
                false
            )
            v.enabled = true
            visualizer = v
            isLive = true
        } catch (t: Throwable) {
            visualizer = null
            isLive = false
        }
        lastMs = 0L
        invalidate()
    }

    fun stopCapture() {
        try {
            visualizer?.enabled = false
        } catch (_: Throwable) {
        }
        try {
            visualizer?.release()
        } catch (_: Throwable) {
        }
        visualizer = null
        isLive = false
    }

    private fun onWave(bytes: ByteArray) {
        val n = barCount
        val seg = (bytes.size / n).coerceAtLeast(1)
        for (i in 0 until n) {
            var sum = 0f
            val base = i * seg
            for (j in 0 until seg) {
                val idx = base + j
                if (idx < bytes.size) sum += abs(bytes[idx].toInt() - 128)
            }
            val raw = (sum / seg) / 128f
            val target = min(1f, raw * 4f)
            val old = energy[i]
            // 起得快、落得慢，避免粒子忽多忽少地闪
            energy[i] = if (target > old) target else old * 0.84f + target * 0.16f
        }
    }

    /** t∈[0,1) 沿周长顺时针（从上边右端开始），返回 x、y、法线 nx、ny。 */
    private fun pointAt(t: Float): FloatArray {
        val straight = islandW - 2 * corner
        val arc = Math.PI.toFloat() * corner
        val total = 2 * straight + 2 * arc
        var dist = (t % 1f) * total
        if (dist < straight) {
            return floatArrayOf(islandW - corner - dist, 0f, 0f, -1f)
        }
        dist -= straight
        if (dist < arc) {
            val a = -Math.PI.toFloat() / 2 - (dist / arc) * Math.PI.toFloat()
            return floatArrayOf(corner + corner * cos(a), corner + corner * sin(a), cos(a), sin(a))
        }
        dist -= arc
        if (dist < straight) {
            return floatArrayOf(corner + dist, islandH, 0f, 1f)
        }
        dist -= straight
        val a = Math.PI.toFloat() / 2 - (dist / arc) * Math.PI.toFloat()
        return floatArrayOf(
            islandW - corner + corner * cos(a),
            corner + corner * sin(a),
            cos(a),
            sin(a)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val nowMs = SystemClock.uptimeMillis()
        val dt = if (lastMs == 0L) 0.016f else ((nowMs - lastMs) / 1000f).coerceIn(0.004f, 0.045f)
        lastMs = nowMs
        simTime += dt

        val left = (width - islandW) / 2f
        val top = (height - islandH) / 2f
        rect.set(left, top, left + islandW, top + islandH)

        // 能量
        var avg = 0f
        for (i in 0 until barCount) {
            val e = if (isLive) energy[i] else simulated(simTime, i)
            if (!isLive) energy[i] = e
            avg += e
        }
        if (barCount > 0) avg /= barCount

        canvas.drawRoundRect(rect, corner, corner, bgPaint)

        // 一圈随音量明暗的淡光底环
        haloPaint.color = Color.argb((12 + avg * 70).toInt().coerceIn(0, 120), 120, 210, 240)
        canvas.drawRoundRect(rect, corner, corner, haloPaint)
        canvas.drawRoundRect(rect, corner, corner, strokePaint)
        canvas.drawCircle(width / 2f, height / 2f, 3.6f * d, camPaint)

        // 发射：每点每秒约 0.4~5 个，点数越多整圈越密
        emitAcc += barCount * (0.45f + avg * 5.0f) * dt
        var guard = 0
        while (emitAcc >= 1f && particles.size < MAX_PARTICLES && guard++ < 220) {
            emitAcc -= 1f
            emit(left, top)
        }
        if (emitAcc > 40f) emitAcc = 40f

        // 更新
        val it = particles.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.life -= dt
            if (p.life <= 0f) {
                it.remove()
                continue
            }
            p.x += p.vx * dt
            p.y += p.vy * dt
            p.vx *= 0.986f
            p.vy *= 0.986f
        }

        // 绘制粒子（两层同心圆模拟柔光，点多了也扛得住）
        for (p in particles) {
            val a = (p.life / p.maxLife).coerceIn(0f, 1f)
            val alpha = (a * a * 170f * (0.35f + avg * 0.9f)).toInt().coerceIn(0, 255)
            if (alpha <= 3) continue
            val r = p.size * (0.35f + 0.65f * a)
            val hue = (p.hue + simTime * 18f) % 360f
            dotPaint.color = Color.HSVToColor((alpha * 0.30f).toInt(), floatArrayOf(hue, 0.45f, 1f))
            canvas.drawCircle(p.x, p.y, r * 2.2f, dotPaint)
            dotPaint.color = Color.HSVToColor(alpha, floatArrayOf(hue, 0.60f, 1f))
            canvas.drawCircle(p.x, p.y, r * 0.75f, dotPaint)
        }

        postInvalidateOnAnimation()
    }

    private fun emit(left: Float, top: Float) {
        if (barCount <= 0) return
        val n = barCount
        var i = (Math.random() * n).toInt().coerceIn(0, n - 1)
        val j = (Math.random() * n).toInt().coerceIn(0, n - 1)
        // 取两次里能量更高的那个源，让粒子集中在响的那一段
        if (energy[j] > energy[i]) i = j
        val e = energy[i].coerceIn(0f, 1f)

        val pt = pointAt((i + 0.5f) / n)
        val baseAng = atan2(pt[3], pt[2])
        val ang = baseAng + ((Math.random() - 0.5) * 0.6).toFloat()
        val speed = (16f + e * 54f) * (0.7f + Math.random().toFloat() * 0.7f) * d
        val sx = left + pt[0] + pt[2] * (1.5f * d)
        val sy = top + pt[1] + pt[3] * (1.5f * d)
        val hue = ((i.toFloat() / n) * 360f) % 360f
        val life = 0.55f + Math.random().toFloat() * 0.75f

        particles.add(
            Particle(
                x = sx,
                y = sy,
                vx = cos(ang) * speed,
                vy = sin(ang) * speed,
                life = life,
                maxLife = life,
                hue = hue,
                size = (0.5f + Math.random().toFloat() * 1.0f) * d
            )
        )
    }

    private fun simulated(t: Float, i: Int): Float {
        val base = 0.30f + 0.40f * (0.5f + 0.5f * sin(t * 5.0f + i * 0.55f))
        val beat = 0.5f + 0.5f * sin(t * 2.2f)
        return min(1f, base * (0.6f + beat * 0.7f))
    }

    override fun onDetachedFromWindow() {
        stopCapture()
        super.onDetachedFromWindow()
    }
}
