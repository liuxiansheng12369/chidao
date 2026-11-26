/*
 * Lyricon V3 · 超级岛歌词 Xposed 模块（小米澎湃 OS / HyperOS）
 * 包名：com.liuxiansheng.chidao
 * Copyright 2026 liuxiansheng（刘先生）
 * 本文件为 Lyricon V3 自研实现。
 */

package com.liuxiansheng.lyricon.app.live

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Random

/**
 * 听歌海报 · 无卡片版（规格见 docs\设计-听歌海报-无卡片版.md）。
 *
 * 文字全部直接压在背景上，没有纸条、卡片、底板；靠背景压暗 + 中部径向暗化保证可读。
 * 背景每天换一张随机图，同一天多次生成结果一致；拉不到、过亮、被弃用一律回落内置夜色。
 *
 * 只负责画图；统计口径、保存与分享入口都不在这里。
 */
object ListenPoster {
    private const val W = 1080
    private const val H = 1620
    private const val PAD = 80f              // 左右安全区，底边同值

    // 背景图开关（默认关闭：永远用内置夜色）
    private const val PREF = "listen_poster"
    private const val KEY_DAILY_BG = "daily_bg"

    private const val COLOR_BRAND = "#7BD8FF"
    private const val COLOR_GLOW = "#C6A6FF"
    private const val COLOR_MAIN = "#EEF2F7"
    private const val COLOR_SUB = "#9AA6B2"
    private const val COLOR_LINE = "#33FFFFFF"
    private const val COLOR_FALLBACK_1 = "#0B0D12"
    private const val COLOR_FALLBACK_2 = "#161C2A"
    private const val COLOR_FALLBACK_3 = "#0B0D12"

    // ——— 对外：背景开关 ———

    fun dailyBgEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_DAILY_BG, false)

    fun setDailyBgEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DAILY_BG, enabled).apply()
    }

    // ——— 对外：出图 ———

    /** 画一张海报。背景图要联网，所以放 IO 线程；调用方自己起协程。 */
    suspend fun build(context: Context, stats: ListenStats): Bitmap = withContext(Dispatchers.IO) {
        val photo = if (dailyBgEnabled(context)) loadDailyBackground(context) else null
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        if (photo != null) {
            drawPhotoBackdrop(canvas, photo, System.currentTimeMillis())
            photo.recycle()
        } else {
            drawFallbackBackdrop(canvas)
        }
        drawTextLayer(canvas, stats)
        bmp
    }

    // ——— 背景：拉图、缓存、判据、处理 ———

    /**
     * 取当天背景图：本地有缓存直接用；没有就下；下不到或过亮都返回 null（回落内置夜色）。
     * 只下载图片，不上传任何本地数据。
     */
    private fun loadDailyBackground(context: Context): Bitmap? {
        val dayKey = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val dir = File(context.filesDir, "poster_bg").apply { mkdirs() }
        // 只留当天的，别攒垃圾
        dir.listFiles()?.forEach { if (it.name != "$dayKey.jpg") it.delete() }
        val file = File(dir, "$dayKey.jpg")

        if (!file.exists() || file.length() < 20_000L) {
            if (!download(dayKey, file)) {
                file.delete()
                return null
            }
        }
        val cover = decodeCover(file) ?: return null
        // 过亮或细节过密的图直接弃用：没有纸面遮挡，亮图会把白字吃掉
        if (!isDarkEnough(cover)) {
            cover.recycle()
            return null
        }
        return cover
    }

    private fun download(dayKey: String, dst: File): Boolean = runCatching {
        val conn = (URL("https://picsum.photos/seed/$dayKey/$W/$H").openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000
            readTimeout = 12000
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "LyriconV3")
        }
        val ok = conn.inputStream.use { input ->
            val tmp = File(dst.parentFile, dst.name + ".tmp")
            tmp.outputStream().use { out -> input.copyTo(out) }
            if (tmp.length() < 20_000L) {
                tmp.delete()
                false
            } else {
                if (dst.exists()) dst.delete()
                tmp.renameTo(dst)
            }
        }
        conn.disconnect()
        ok
    }.getOrDefault(false)

    /** 解码并居中裁到海报比例（cover），避免拉伸变形。 */
    private fun decodeCover(file: File): Bitmap? = runCatching {
        val src = BitmapFactory.decodeFile(file.absolutePath) ?: return null
        val target = W.toFloat() / H.toFloat()
        val ratio = src.width.toFloat() / src.height.toFloat()
        val cw = if (ratio > target) (src.height * target).toInt() else src.width
        val ch = if (ratio > target) src.height else (src.width / target).toInt()
        val left = ((src.width - cw) / 2).coerceAtLeast(0)
        val top = ((src.height - ch) / 2).coerceAtLeast(0)
        val cut = Bitmap.createBitmap(src, left, top, cw.coerceAtMost(src.width), ch.coerceAtMost(src.height))
        val scaled = Bitmap.createScaledBitmap(cut, W, H, true)
        if (cut !== src && cut !== scaled) cut.recycle()
        if (src !== scaled && src !== cut) src.recycle()
        scaled
    }.getOrNull()

    /** 平均亮度偏低才算能压白字；过亮直接弃用换兜底。 */
    private fun isDarkEnough(bitmap: Bitmap): Boolean = runCatching {
        val small = Bitmap.createScaledBitmap(bitmap, 24, 24, true)
        var sum = 0.0
        for (y in 0 until 24) {
            for (x in 0 until 24) {
                val c = small.getPixel(x, y)
                sum += 0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)
            }
        }
        small.recycle()
        (sum / 576.0) < 135.0
    }.getOrDefault(false)

    /** 背景处理层：重度模糊（散景）+ 压暗 + 降饱和 + 颗粒 + 暗角。 */
    private fun drawPhotoBackdrop(canvas: Canvas, photo: Bitmap, seed: Long) {
        // 先缩到 1/14 再放大回来 —— 大半径模糊，比逐像素卷积快得多
        val tiny = Bitmap.createScaledBitmap(photo, (W / 14).coerceAtLeast(1), (H / 14).coerceAtLeast(1), true)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val matrix = ColorMatrix().apply { setSaturation(0.32f) }
        matrix.postConcat(
            ColorMatrix(
                floatArrayOf(
                    0.70f, 0f, 0f, 0f, 0f,
                    0f, 0.70f, 0f, 0f, 0f,
                    0f, 0f, 0.76f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f,
                )
            )
        )
        paint.colorFilter = ColorMatrixColorFilter(matrix)
        canvas.drawBitmap(tiny, null, RectF(0f, 0f, W.toFloat(), H.toFloat()), paint)
        tiny.recycle()

        // 暗角：四周压死，中间留一点
        val vignette = Paint().apply {
            shader = RadialGradient(
                W / 2f, H / 2f, W * 0.95f,
                intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.parseColor("#B3000000")),
                floatArrayOf(0f, 0.42f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, W.toFloat(), H.toFloat(), vignette)

        // 颗粒：固定种子，同一天多次生成颗粒位置一致
        val rnd = Random(seed)
        val dot = Paint().apply { color = Color.parseColor("#18FFFFFF") }
        repeat(1400) {
            canvas.drawCircle((rnd.nextFloat() * W), (rnd.nextFloat() * H), rnd.nextFloat() * 1.4f + 0.6f, dot)
        }
    }

    /** 内置深蓝夜色：任何情况下都能出图。 */
    private fun drawFallbackBackdrop(canvas: Canvas) {
        val bg = Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, H.toFloat(),
                intArrayOf(
                    Color.parseColor(COLOR_FALLBACK_1),
                    Color.parseColor(COLOR_FALLBACK_2),
                    Color.parseColor(COLOR_FALLBACK_3),
                ),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, W.toFloat(), H.toFloat(), bg)
        val glow = Paint().apply {
            shader = RadialGradient(
                W * 0.85f, H * 0.18f, 520f,
                intArrayOf(Color.parseColor("#4A7BD8FF"), Color.TRANSPARENT),
                floatArrayOf(0f, 1f), Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, W.toFloat(), H.toFloat(), glow)
        val glow2 = Paint().apply {
            shader = RadialGradient(
                W * 0.10f, H * 0.92f, 560f,
                intArrayOf(Color.parseColor("#3AC6A6FF"), Color.TRANSPARENT),
                floatArrayOf(0f, 1f), Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, W.toFloat(), H.toFloat(), glow2)
    }

    /** 可读区：画面中部一块大面积柔和径向暗化，中心更暗、向四周渐隐。 */
    private fun drawReadableZone(canvas: Canvas) {
        val paint = Paint().apply {
            shader = RadialGradient(
                W / 2f, H * 0.50f, W * 0.86f,
                intArrayOf(
                    Color.parseColor("#B305070C"),
                    Color.parseColor("#6605070C"),
                    Color.TRANSPARENT,
                ),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, W.toFloat(), H.toFloat(), paint)
    }

    // ——— 文字层（从上到下，全部左对齐） ———

    private fun drawTextLayer(canvas: Canvas, stats: ListenStats) {
        drawReadableZone(canvas)

        val main = Color.parseColor(COLOR_MAIN)
        val sub = Color.parseColor(COLOR_SUB)
        val brand = Color.parseColor(COLOR_BRAND)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val right = W - PAD

        // 1 品牌行
        paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        paint.textSize = 34f
        paint.color = brand
        canvas.drawText("词岛 · 听歌记录", PAD, 120f, paint)

        // 2 副标题
        paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        paint.textSize = 28f
        paint.color = sub
        canvas.drawText(stats.label + " 的音乐小结", PAD, 168f, paint)

        // 3 分隔线
        drawLine(canvas, 200f)

        // 4 三组统计：小号标题 + 超大数字（规格：文字层一律白/浅灰）
        statBlock(canvas, paint, 258f, "累计收听", stats.totalLabel.replace(" ", ""))
        statBlock(canvas, paint, 428f, "有效播放", "${stats.playCount} 条")
        statBlock(canvas, paint, 598f, "听过曲目", "${stats.uniqueSongs} 首")

        // 5 分隔线
        drawLine(canvas, 738f)

        // 6 最爱歌手：名字左对齐 + N 次右对齐，取前三
        paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        paint.textSize = 32f
        paint.color = main
        canvas.drawText("最爱歌手", PAD, 792f, paint)
        var y = 850f
        if (stats.topArtists.isEmpty()) {
            paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            paint.textSize = 28f
            paint.color = sub
            canvas.drawText("还没有足够数据", PAD, y, paint)
            y += 58f
        } else {
            stats.topArtists.take(3).forEach { (name, count) ->
                paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                paint.textSize = 32f
                paint.color = main
                canvas.drawText(clip(name, 16), PAD, y, paint)
                paint.color = sub
                val tail = "$count 次"
                canvas.drawText(tail, right - paint.measureText(tail), y, paint)
                y += 58f
            }
        }

        // 7 分隔线
        drawLine(canvas, 1014f)

        // 8 单曲循环：一行 歌名 + N 次
        paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        paint.textSize = 32f
        paint.color = main
        canvas.drawText("单曲循环", PAD, 1066f, paint)
        paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        paint.color = main
        val song = stats.topSongs.firstOrNull()
        if (song == null) {
            paint.textSize = 28f
            paint.color = sub
            canvas.drawText("还没有足够数据", PAD, 1122f, paint)
        } else {
            paint.textSize = 32f
            paint.color = main
            canvas.drawText("「${clip(song.first, 18)}」 ${song.second} 次", PAD, 1122f, paint)
        }

        // 9 分隔线
        drawLine(canvas, 1170f)

        // 10 都在哪听：前三，中文应用名，· 分隔
        paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        paint.textSize = 32f
        paint.color = main
        canvas.drawText("都在哪听", PAD, 1222f, paint)
        paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        paint.textSize = 28f
        paint.color = sub
        val sources = if (stats.topSources.isEmpty()) "—"
        else stats.topSources.take(3).joinToString("  ·  ") { it.first }
        canvas.drawText(sources, PAD, 1274f, paint)

        // 11 底部：左日期，右 词岛 V3
        paint.textSize = 26f
        paint.color = sub
        val day = SimpleDateFormat("M月d日 EEEE", Locale.CHINA).format(Date())
        canvas.drawText(day, PAD, H - PAD, paint)
        val sign = "词岛 V3"
        canvas.drawText(sign, right - paint.measureText(sign), H - PAD, paint)
    }

    /** 一组统计：上面小号标题，下面超大数字（超过 6 个字符降到 72）。 */
    private fun statBlock(canvas: Canvas, paint: Paint, titleBaseline: Float, title: String, number: String) {
        paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        paint.textSize = 26f
        paint.color = Color.parseColor(COLOR_SUB)
        canvas.drawText(title, PAD, titleBaseline, paint)

        paint.typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
        paint.color = Color.parseColor(COLOR_MAIN)
        var size = if (number.length > 6) 72f else 96f
        paint.textSize = size
        // 再兜一层：万一还是出安全区就继续缩
        while (paint.measureText(number) > W - PAD * 2 && size > 40f) {
            size -= 4f
            paint.textSize = size
        }
        canvas.drawText(number, PAD, titleBaseline + 96f, paint)
    }

    /** 1px 细白分隔线，左右各留安全区。 */
    private fun drawLine(canvas: Canvas, y: Float) {
        val paint = Paint().apply {
            color = Color.parseColor(COLOR_LINE)
            strokeWidth = 1f
        }
        canvas.drawRect(PAD, y, W - PAD, y + 1f, paint)
    }

    private fun clip(text: String, max: Int): String =
        if (text.length <= max) text else text.substring(0, max) + "…"

    // ——— 落到相册与分享（保持原样，不动入口与路径） ———

    /** 保存到相册，返回 content uri（失败返回 null）。 */
    fun saveToGallery(context: Context, bitmap: Bitmap, stats: ListenStats): Uri? {
        val name = "词岛听歌报告_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
            .format(Date()) + ".png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/词岛"
            )
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        }.onFailure { return null }
        return uri
    }

    fun share(context: Context, uri: Uri) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享听歌报告"))
    }
}
