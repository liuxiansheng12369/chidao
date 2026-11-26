/*
 * 本文件基于 HyperLyric（作者 limczhh）的频谱思路移植，原项目：
 * https://github.com/limczhh/HyperLyric
 * 原版权归 limczhh 所有，原项目以 GPL-3.0 授权。
 * 移植与修改：liuxiansheng，2026；本文件随词岛整体以 GPL-3.0 发布。
 */

package com.liuxiansheng.lyricon.hyper.root.island.effects.particle

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.SweepGradient
import android.media.audiofx.Visualizer
import android.os.SystemClock
import android.view.View
import com.liuxiansheng.lyricon.hyper.common.RootConstants
import com.liuxiansheng.lyricon.hyper.root.HookEntry
import com.liuxiansheng.lyricon.hyper.root.utils.CoverColorHelper
import com.liuxiansheng.lyricon.hyper.root.utils.HookLogger
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 整机音频输出的实时能量（SystemUI 进程内）。
 *
 * Visualizer(0) 取全局混音，不抢占音频源；失败静默退化，绝不影响岛本身。
 * 节拍用低频段（第 1~10 个 bin）过峰检测，多个效果同时读取也不会互相扣减。
 */
internal object IslandAudioEnergy {
    private const val TAG = "IslandAudioEnergy"
    private const val BAR_COUNT = 32
    private const val BEAT_DECAY_SEC = 0.28f

    private var visualizer: Visualizer? = null
    private var holders = 0
    private var failed = false
    private val levels = FloatArray(BAR_COUNT)

    @Volatile
    private var active = false

    private var beatStartMs = 0L
    private var lastBeatMs = 0L
    private var bassPeak = 0.30f

    fun acquire(): Boolean {
        synchronized(this) {
            holders++
            if (visualizer != null) return true
            if (failed) return false
            return try {
                val v = Visualizer(0)
                v.captureSize = minOf(1024, Visualizer.getCaptureSizeRange()[1])
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
                        ) {
                            if (fft != null) onFft(fft)
                        }
                    },
                    Visualizer.getMaxCaptureRate() / 2,
                    true,
                    true
                )
                v.enabled = true
                visualizer = v
                HookLogger.i(TAG, "音频采集已启动")
                true
            } catch (t: Throwable) {
                failed = true
                HookLogger.i(TAG, "音频采集启动失败: ${t.javaClass.simpleName} ${t.message}")
                false
            }
        }
    }

    fun release() {
        synchronized(this) {
            if (holders > 0) holders--
            if (holders > 0) return
            try {
                visualizer?.enabled = false
            } catch (_: Throwable) {
            }
            try {
                visualizer?.release()
            } catch (_: Throwable) {
            }
            visualizer = null
            active = false
        }
    }

    fun isActive(): Boolean = active

    fun copyLevels(out: FloatArray) {
        synchronized(this) {
            for (i in out.indices) {
                out[i] = levels[if (i < BAR_COUNT) i else i % BAR_COUNT]
            }
        }
    }

    /** 当前节拍脉冲强度 0..1，按时间自然衰减（只读，可多消费者）。 */
    fun beatValue(nowMs: Long): Float {
        if (beatStartMs == 0L) return 0f
        val elapsed = (nowMs - beatStartMs) / 1000f
        return (1f - elapsed / BEAT_DECAY_SEC).coerceIn(0f, 1f)
    }

    private fun onWave(bytes: ByteArray) {
        val seg = (bytes.size / BAR_COUNT).coerceAtLeast(1)
        var loudest = 0f
        for (i in 0 until BAR_COUNT) {
            var sum = 0f
            val base = i * seg
            for (j in 0 until seg) {
                val idx = base + j
                if (idx < bytes.size) sum += abs(bytes[idx].toInt() - 128)
            }
            val value = min(1f, (sum / seg) / 128f * 3.4f)
            if (value > loudest) loudest = value
            val old = levels[i]
            levels[i] = if (value > old) value else old * 0.82f + value * 0.18f
        }
        active = loudest > 0.04f
    }

    private fun onFft(fft: ByteArray) {
        var low = 0
        val limit = minOf(10, fft.size)
        for (i in 1 until limit) {
            val value = abs(fft[i].toInt())
            if (value > low) low = value
        }
        val bass = min(1f, low / 190f)
        bassPeak = if (bass > bassPeak) {
            bass
        } else {
            (bassPeak * 0.97f + bass * 0.03f).coerceAtLeast(0.25f)
        }
        val now = SystemClock.uptimeMillis()
        if (bass > bassPeak * 0.62f && bass > 0.22f && now - lastBeatMs > 130L) {
            lastBeatMs = now
            beatStartMs = now
        }
    }
}

/** 环绕粒子律动：沿岛一圈向外喷淡光粒子（默认关闭）。 */
internal object IslandParticleOverlay {
    private const val TAG = "IslandParticleOverlay"
    private const val MAX_PARTICLES = 700

    private val fields = WeakHashMap<View, Field>()
    private var logged = false

    fun isEnabled(): Boolean = runCatching {
        HookEntry.instance?.prefs
            ?.getBoolean(RootConstants.KEY_HOOK_ISLAND_PARTICLE_MOTION, false)
    }.getOrNull() ?: false

    fun draw(
        canvas: Canvas,
        backgroundView: View?,
        path: Path,
        measure: PathMeasure,
        density: Float
    ): Boolean {
        if (backgroundView == null) return false
        if (!isEnabled()) {
            val removed = synchronized(fields) { fields.remove(backgroundView) }
            if (removed != null) IslandAudioEnergy.release()
            return false
        }
        val field = synchronized(fields) {
            fields[backgroundView] ?: run {
                if (!IslandAudioEnergy.acquire()) return false
                Field(WeakReference(backgroundView), density).also { fields[backgroundView] = it }
            }
        }
        val nowMs = SystemClock.uptimeMillis()
        if (!logged) {
            logged = true
            HookLogger.i(TAG, "岛背景层 ${backgroundView.width}x${backgroundView.height} 轮廓长=${measure.length.toInt()}px")
        }
        return field.draw(canvas, path, measure, nowMs)
    }

    private class Field(
        private val viewRef: WeakReference<View>,
        private val density: Float
    ) {
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

        private val particles = ArrayList<Particle>(MAX_PARTICLES)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val pos = FloatArray(2)
        private val tan = FloatArray(2)
        private val energy = FloatArray(32)
        private var lastMs = 0L
        private var emitAcc = 0f
        private var timeSec = 0f

        fun draw(canvas: Canvas, path: Path, measure: PathMeasure, nowMs: Long): Boolean {
            viewRef.get() ?: return false
            measure.setPath(path, false)
            val length = measure.length
            if (length <= 0.5f) return false

            val dt = if (lastMs == 0L) 0.016f else ((nowMs - lastMs) / 1000f).coerceIn(0.004f, 0.045f)
            lastMs = nowMs
            timeSec += dt

            IslandAudioEnergy.copyLevels(energy)
            var avg = 0f
            for (e in energy) avg += e
            avg /= energy.size

            val beat = IslandAudioEnergy.beatValue(nowMs)
            val rate = 18f + avg * 140f + beat * 420f
            emitAcc += rate * dt
            var guard = 0
            while (emitAcc >= 1f && particles.size < MAX_PARTICLES && guard++ < 140) {
                emitAcc -= 1f
                spawn(measure, length, avg, beat)
            }
            if (emitAcc > 16f) emitAcc = 16f

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

            for (p in particles) {
                val a = (p.life / p.maxLife).coerceIn(0f, 1f)
                val alpha = (a * a * 235f * (0.45f + avg * 0.7f + beat * 0.6f))
                    .toInt().coerceIn(0, 255)
                if (alpha <= 3) continue
                val r = p.size * (0.4f + 0.6f * a) * (1f + beat * 0.35f)
                val hue = (p.hue + timeSec * 16f) % 360f
                paint.color = Color.HSVToColor((alpha * 0.38f).toInt(), floatArrayOf(hue, 0.45f, 1f))
                canvas.drawCircle(p.x, p.y, r * 1.8f, paint)
                paint.color = Color.HSVToColor(alpha, floatArrayOf(hue, 0.55f, 1f))
                canvas.drawCircle(p.x, p.y, r * 0.85f, paint)
            }

            return particles.isNotEmpty() || IslandAudioEnergy.isActive() || beat > 0.02f
        }

        private fun spawn(measure: PathMeasure, length: Float, avg: Float, beat: Float) {
            val t = Math.random().toFloat()
            measure.getPosTan(length * t, pos, tan)
            val enough = abs(tan[0]) + abs(tan[1]) > 0.0001f
            // 顺时针轮廓的外法线
            val nx = if (enough) tan[1] else 0f
            val ny = if (enough) -tan[0] else -1f
            val angle = atan2(ny, nx) + ((Math.random() - 0.5) * 0.55).toFloat()
            // 背景层贴着岛（上下各只有二十几像素），粒子必须短促、贴边才看得见
            val speed = (6f + avg * 8f + beat * 24f) *
                (0.6f + Math.random().toFloat() * 0.8f) * density
            val offset = 1.2f * density
            val life = 0.28f + Math.random().toFloat() * 0.32f
            particles.add(
                Particle(
                    x = pos[0] + nx * offset,
                    y = pos[1] + ny * offset,
                    vx = cos(angle) * speed,
                    vy = sin(angle) * speed,
                    life = life,
                    maxLife = life,
                    hue = (t * 360f) % 360f,
                    size = (0.7f + Math.random().toFloat() * 0.9f + beat * 0.9f) * density
                )
            )
        }
    }
}

/**
 * 环绕光圈：跟粒子同一套轮廓与节拍，只是不喷粒子——只把这一圈描亮。
 * 平时细线微光，响度上来变粗变亮，鼓点整圈闪一下。
 */
internal object IslandRingGlowOverlay {
    private const val TAG = "IslandRingGlowOverlay"

    private val fields = WeakHashMap<View, Field>()
    private var logged = false

    /** 颜色缓存（跟封面时要跟着切歌变，但不用每帧都读盘）。 */
    @Volatile
    private var cachedColor = 0

    @Volatile
    private var cachedAtMs = 0L

    /** 彩虹档的 16 色环（首尾同色，收成闭环）。 */
    private val RING_RAINBOW_COLORS = intArrayOf(
        0xFFFF3B30.toInt(), // 红
        0xFFFF6B22.toInt(), // 朱红
        0xFFFF9500.toInt(), // 橙
        0xFFFFB300.toInt(), // 琥珀
        0xFFFFD400.toInt(), // 黄
        0xFFC6E82E.toInt(), // 黄绿
        0xFF34C759.toInt(), // 绿
        0xFF00C853.toInt(), // 青绿
        0xFF00C7BE.toInt(), // 青
        0xFF32ADE6.toInt(), // 天蓝
        0xFF007AFF.toInt(), // 蓝
        0xFF5856D6.toInt(), // 靛
        0xFF7D5FFF.toInt(), // 紫
        0xFFAF52DE.toInt(), // 紫红
        0xFFFF2D55.toInt(), // 玫红
        0xFFFF64B0.toInt(), // 粉
        0xFFFF3B30.toInt()  // 回到红，闭环
    )

    private fun modeNow(): Int = runCatching {
        prefs()?.getInt(RootConstants.KEY_HOOK_ISLAND_RING_GLOW_MODE, RootConstants.RING_GLOW_MODE_FIXED)
            ?: RootConstants.RING_GLOW_MODE_FIXED
    }.getOrNull() ?: RootConstants.RING_GLOW_MODE_FIXED

    fun isEnabled(): Boolean = runCatching {
        HookEntry.instance?.prefs
            ?.getBoolean(RootConstants.KEY_HOOK_ISLAND_RING_GLOW, false)
    }.getOrNull() ?: false

    private fun prefs() = runCatching { HookEntry.instance?.prefs }.getOrNull()

    /** 当前该用的光圈基准色：跟随封面 / 16 色固定 / 自定义，三档。 */
    private fun resolveBaseColor(nowMs: Long): Int {
        if (cachedColor != 0 && nowMs - cachedAtMs < 250L) return cachedColor
        val p = prefs()
        val mode = p?.getInt(RootConstants.KEY_HOOK_ISLAND_RING_GLOW_MODE, RootConstants.RING_GLOW_MODE_FIXED)
            ?: RootConstants.RING_GLOW_MODE_FIXED
        val fixed = p?.getInt(RootConstants.KEY_HOOK_ISLAND_RING_GLOW_COLOR, RootConstants.DEFAULT_RING_GLOW_COLOR)
            ?: RootConstants.DEFAULT_RING_GLOW_COLOR
        val custom = p?.getInt(
            RootConstants.KEY_HOOK_ISLAND_RING_GLOW_CUSTOM_COLOR,
            RootConstants.DEFAULT_RING_GLOW_COLOR
        ) ?: RootConstants.DEFAULT_RING_GLOW_COLOR
        val color = when (mode) {
            RootConstants.RING_GLOW_MODE_FIXED -> fixed
            RootConstants.RING_GLOW_MODE_CUSTOM -> custom
            else -> runCatching {
                CoverColorHelper.currentSession()
                    ?.let { CoverColorHelper.getCachedColors(useGradient = false, session = it) }
                    ?.first?.firstOrNull()
            }.getOrNull() ?: fixed
        }
        cachedColor = color
        cachedAtMs = nowMs
        return color
    }

    /** 往白色方向提亮/压暗，用来区分外柔光与内亮线。 */
    private fun tint(base: Int, whiteMix: Float, dim: Float): Triple<Int, Int, Int> {
        val r = (Color.red(base) * (1f - whiteMix) + 255f * whiteMix).toInt().coerceIn(0, 255)
        val g = (Color.green(base) * (1f - whiteMix) + 255f * whiteMix).toInt().coerceIn(0, 255)
        val b = (Color.blue(base) * (1f - whiteMix) + 255f * whiteMix).toInt().coerceIn(0, 255)
        return Triple(
            (r * dim).toInt().coerceIn(0, 255),
            (g * dim).toInt().coerceIn(0, 255),
            (b * dim).toInt().coerceIn(0, 255)
        )
    }

    fun draw(
        canvas: Canvas,
        backgroundView: View?,
        path: Path,
        measure: PathMeasure,
        density: Float
    ): Boolean {
        if (backgroundView == null) return false
        if (!isEnabled()) {
            val removed = synchronized(fields) { fields.remove(backgroundView) }
            if (removed != null) IslandAudioEnergy.release()
            return false
        }
        val field = synchronized(fields) {
            fields[backgroundView] ?: run {
                if (!IslandAudioEnergy.acquire()) return false
                Field(WeakReference(backgroundView), density).also { fields[backgroundView] = it }
            }
        }
        val nowMs = SystemClock.uptimeMillis()
        if (!logged) {
            logged = true
            HookLogger.i(TAG, "环绕光圈已挂载 轮廓长=${measure.length.toInt()}px")
        }
        return field.draw(canvas, path, measure, nowMs)
    }

    private class Field(
        private val viewRef: WeakReference<View>,
        private val density: Float
    ) {
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val energy = FloatArray(32)
        private var lastMs = 0L
        private var timeSec = 0f
        private var smooth = 0f

        // 彩虹档：绕岛一圈 16 色渐变，色带随时间缓慢转动
        private var rainbowShader: SweepGradient? = null
        private var shaderCx = Float.NaN
        private var shaderCy = Float.NaN
        private val shaderMatrix = Matrix()
        private val bounds = android.graphics.RectF()

        fun draw(canvas: Canvas, path: Path, measure: PathMeasure, nowMs: Long): Boolean {
            viewRef.get() ?: return false
            measure.setPath(path, false)
            if (measure.length <= 0.5f) return false

            val dt = if (lastMs == 0L) 0.016f else ((nowMs - lastMs) / 1000f).coerceIn(0.004f, 0.045f)
            lastMs = nowMs
            timeSec += dt

            IslandAudioEnergy.copyLevels(energy)
            var avg = 0f
            for (e in energy) avg += e
            avg /= energy.size

            // 响度平滑：起快落慢，光圈跟着歌的强弱呼吸
            smooth = if (avg > smooth) {
                smooth * 0.45f + avg * 0.55f
            } else {
                smooth * 0.86f + avg * 0.14f
            }
            val beat = IslandAudioEnergy.beatValue(nowMs)
            val mode = modeNow()
            if (mode == RootConstants.RING_GLOW_MODE_RAINBOW) {
                // 彩虹档：整圈按 16 色环着色，色带随时间绕岛转动
                path.computeBounds(bounds, true)
                val cx = bounds.centerX()
                val cy = bounds.centerY()
                val shader = rainbowShader ?: run {
                    SweepGradient(cx, cy, RING_RAINBOW_COLORS, null).also { rainbowShader = it }
                }
                if (shaderCx != cx || shaderCy != cy) {
                    shaderCx = cx
                    shaderCy = cy
                }
                shaderMatrix.setRotate(timeSec * 30f, cx, cy)
                shader.setLocalMatrix(shaderMatrix)

                val glowAlpha = (6f + smooth * 26f + beat * 46f).toInt().coerceIn(0, 90)
                glowPaint.shader = shader
                glowPaint.alpha = glowAlpha
                glowPaint.strokeWidth = (2.6f + smooth * 2.2f + beat * 3.2f) * density
                canvas.drawPath(path, glowPaint)

                val coreAlpha = (35f + smooth * 70f + beat * 110f).toInt().coerceIn(0, 220)
                corePaint.shader = shader
                corePaint.alpha = coreAlpha
                corePaint.strokeWidth = (1.0f + smooth * 0.9f + beat * 2.0f) * density
                canvas.drawPath(path, corePaint)
                return IslandAudioEnergy.isActive() || smooth > 0.02f || beat > 0.02f
            }
            // 非彩虹档：清掉 shader，回到单色
            glowPaint.shader = null
            corePaint.shader = null
            val base = resolveBaseColor(nowMs)
            // 外柔光直接用基准色，内亮线往白提一档 —— 与原来写死的冷蓝白观感一致
            val glowRgb = Triple(Color.red(base), Color.green(base), Color.blue(base))
            val coreRgb = tint(base, whiteMix = 0.48f, dim = 1.0f)

            // 外层柔光
            glowPaint.color = Color.argb(
                (6f + smooth * 26f + beat * 46f).toInt().coerceIn(0, 90),
                glowRgb.first,
                glowRgb.second,
                glowRgb.third
            )
            glowPaint.strokeWidth = (2.6f + smooth * 2.2f + beat * 3.2f) * density
            canvas.drawPath(path, glowPaint)

            // 内层亮线：鼓点打到最亮
            corePaint.color = Color.argb(
                (35f + smooth * 70f + beat * 110f).toInt().coerceIn(0, 220),
                coreRgb.first,
                coreRgb.second,
                coreRgb.third
            )
            corePaint.strokeWidth = (1.0f + smooth * 0.9f + beat * 2.0f) * density
            canvas.drawPath(path, corePaint)

            // 有音乐就一直跟帧刷新；完全安静且衰减干净时停手省电
            return IslandAudioEnergy.isActive() || smooth > 0.02f || beat > 0.02f
        }
    }
}
