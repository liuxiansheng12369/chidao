/*
 * 词岛 · 超级岛歌词 Xposed 模块（小米澎湃 OS / HyperOS）
 * 包名：com.liuxiansheng.chidao
 * Copyright 2026 liuxiansheng（刘先生）
 * 本文件为 词岛 自研实现。
 */

package com.liuxiansheng.lyricon.hyper.root.manual
import com.liuxiansheng.lyricon.hyper.root.utils.DiagnosticsLog

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.liuxiansheng.lyricon.axis.api.ManualAxisApi
import com.liuxiansheng.lyricon.axis.core.ManualAxis
import com.liuxiansheng.lyricon.axis.core.ManualAxisResolver
import com.liuxiansheng.lyricon.axis.core.AxisStore
import com.liuxiansheng.lyricon.hyper.lyric.model.LyricWord
import com.liuxiansheng.lyricon.hyper.lyric.model.RichLyricLine
import com.liuxiansheng.lyricon.hyper.lyric.model.Song
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * 人工轴（打轴器）在 SystemUI 侧的读取桥。
 *
 * 为什么不能直接读文件：本进程是 SystemUI，`filesDir` 是 SystemUI 自己的目录，
 * 人工轴存在词岛 App 的私有目录里，跨 uid 读不到。所以走 App 的 ContentProvider：
 *   SystemUI → call(authority, "axis_lookup", songKey) → App 代读并回传 JSON
 *
 * 命中就整首换掉：把 Song.lyrics 换成人工轴的行，之后 updateSong 重建时间轴，
 * 岛和状态栏都按人工轴推进。
 *
 * === 线程纪律（别改回去，这条踩过坑）===
 * `contentResolver.call` 是一次跨进程同步等待，没有超时。App 进程不在 / 正在冷启动时
 * 它会一直等，等满 5 秒就被 MIUI 判成 APP_SCOUT_HANG —— 表现就是整个超级岛和状态栏卡死。
 * 而 [applyIfHit] 的调用点是 MediaController 回调（SystemUI 主线程），所以：
 *
 *   1. 主线程只允许读内存里的 [cache]，**永远不在这里 call**。
 *   2. 缓存没有这首歌的结果 → 先按原歌词正常渲染（合成的 song 直接返回），
 *      同时把查询丢到 [worker]，查到有轴再 post 回主线程让上层重建。
 *   3. 只有 [applyIfHitNow] 同步查，且只给"打轴器刚保存完"这一条路径用 ——
 *      那时 App 进程必定活着，call 是毫秒级。
 *
 * 缓存里必须能区分"没查过"和"查过但没有"：[cache] 的 null 值代表后者，
 * 所以要配 `containsKey` 判断。这一点和当年出问题的那版缓存不同 ——
 * 那版只缓存命中、miss 不缓存，且保存后不失效；现在保存会清空整张表。
 */
object ManualAxisBridge {

    private const val TAG = "ManualAxis"
    private const val AUTHORITY = "io.github.liuxiansheng12369.chidao.live-state"
    private const val METHOD_LOOKUP = "axis_lookup"

    /** SystemUI 自己的轴副本目录（App 保存时推送过来）。读它不需要 App 活着。 */
    private const val MIRROR_DIR = "lyricon_axis"

    /**
     * 最多记这么多首，超了按"最久没用到"淘汰一首（2.0.103 改）。
     *
     * 旧做法是超限直接 `cache.clear()` 整表清空：听二十几首之后每首都要重新读盘/跨进程查，
     * 表现就是"唱到一半歌词才出来"。上限放宽到 500，淘汰只踢最旧的一条，其余结论留着。
     */
    private const val CACHE_MAX = 500

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var runtime: Context? = null

    /** 本进程的轴库（2.0.103）。attach 时打开，之后换歌只查这张表。 */
    @Volatile
    private var mirrorDb: AxisMirrorDb? = null

    /** 默认在线歌词优先：原歌词为空时才用轴；manual_first 时命中就整首换。 */
    @Volatile
    var manualFirst: Boolean = false

    /** key → 轴；value 为 null 表示"查过了、这首没敲过轴"。按访问顺序排，末尾是最近用过的。 */
    private val cache = object : LinkedHashMap<String, ManualAxis?>(64, 0.75f, true) {}

    /** 每条缓存写入的时刻，只用来让"查过没有"这种结论过期，命中结果不过期。 */
    private val cacheAt = HashMap<String, Long>()

    /**
     * "这首没轴"的结论保留多久（2.0.103：60 秒 → 6 小时，对齐 HyperLyrics Enhanced 的 MISS_TTL）。
     *
     * 不能永久留：切歌那一刻 App 进程可能被冻结，查询失败也会被记成"没轴"，
     * 之后用户敲完轴再切回来，缓存还咬着旧的 null，就一直没歌词。
     * 但 60 秒又太短 —— 同一首没轴的歌反复切回来就反复跨进程查，查的那几百毫秒就是歌词空窗。
     * 6 小时足够覆盖一整场听歌，又能在下次开机前自然失效。
     */
    private const val MISS_TTL_MS = 6L * 60L * 60L * 1000L

    /** 正在后台查的 key，防止同一首反复排同一个活。 */
    private val querying = HashSet<String>()

    private val cacheLock = Any()

    private val workerThread by lazy {
        HandlerThread("ManualAxisLookup").apply { start() }
    }
    private val worker by lazy { Handler(workerThread.looper) }
    private val main by lazy { Handler(Looper.getMainLooper()) }

    fun attach(context: Context) {
        runtime = context.applicationContext ?: context
        val dir = File(context.filesDir, MIRROR_DIR)
        // 2.0.103：打开本进程轴库。目录里已有的 json 先补进库；库里还带着上次运行留下的
        // "这首没轴"结论，按墙上时间换算出"距今多久"，有效的直接预填进内存，免得开机后又查一轮。
        runCatching {
            val db = mirrorDb ?: AxisMirrorDb(context, dir).also { mirrorDb = it }
            db.reindex()
            db.trim(CACHE_MAX)
            val wallNow = System.currentTimeMillis()
            val realtimeNow = SystemClock.elapsedRealtime()
            db.missesSince(wallNow - MISS_TTL_MS).forEach { (key, checkedWall) ->
                synchronized(cacheLock) {
                    if (!cache.containsKey(key)) {
                        cache[key] = null
                        cacheAt[key] = realtimeNow - (wallNow - checkedWall)
                    }
                }
            }
        }.onFailure { e ->
            Log.w(TAG, "轴库初始化失败: ${e.javaClass.simpleName}: ${e.message}")
        }
        // SystemUI 重启后副本目录还在，但这次不会有人推送 → 直接指过去，别等广播
        runCatching {
            if (dir.isDirectory) ManualAxisResolver.setRootOverride(dir)
        }
        // 副本为空（刚装新版 / 清过数据）→ 请 App 推一次全量。
        // 状态栏刚起来本进程的接收器还没注册，晚几秒再喊，否则广播到了没人接。
        if (!dir.isDirectory || dir.listFiles().isNullOrEmpty()) {
            Handler(Looper.getMainLooper()).postDelayed({
                runCatching {
                    context.sendBroadcast(
                        android.content.Intent(ManualAxisApi.ACTION_AXIS_SYNC_REQUEST)
                            .setPackage("com.liuxiansheng.chidao")
                    )
                    DiagnosticsLog.i(TAG, "副本为空，已请 App 重推轴库")
                }.onFailure { e ->
                    Log.w(TAG, "请求重推失败: ${e.javaClass.simpleName}: ${e.message}")
                }
            }, 3000)
        }
    }

    /**
     * 打轴器保存后主动失效：整张表清掉。
     *
     * 必须真清 —— 否则用户敲完新轴，同一首再播时命中旧的 null 结果，
     * 新轴永远显示不出来，只有切到别的歌才恢复。
     */
    fun invalidateAxisCache() {
        synchronized(cacheLock) {
            cache.clear()
            cacheAt.clear()
        }
        // 新轴可能推翻了某首歌"没轴"的结论：miss 表清掉，轴表跟着目录对齐一次
        mirrorDb?.clearMisses()
        mirrorDb?.reindex()
    }

    /**
     * 收到 App 推来的轴文件，落到本进程自己的目录。
     *
     * 载荷是 `[{"name":"axis-xxx.json","body":"..."}]`，一次广播跑一批，索引也在里面。
     * 落盘后清空查询缓存，正在播的那首立刻能命中新轴。
     */
    fun acceptPush(context: Context, payload: String) {
        val dir = File(context.filesDir, MIRROR_DIR)
        runCatching {
            if (!dir.exists() && !dir.mkdirs()) {
                Log.w(TAG, "轴副本目录创建失败: ${dir.absolutePath}")
                return
            }
            val arr = Json.parseToJsonElement(payload).jsonArray
            var written = 0
            var indexBody: String? = null
            arr.forEach { element ->
                val obj = element.jsonObject
                val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val body = obj["body"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                // 只收平铺的 json 文件名，挡住用 ../ 往别处写的可能
                if (!name.endsWith(".json") || name.contains('/') || name.contains('\\')) {
                    Log.w(TAG, "忽略非法文件名: $name")
                    return@forEach
                }
                if (name == "index.json") indexBody = body
                File(dir, name).writeText(body)
                written++
            }
            // 索引到了就照着它清一遍：App 里删掉的轴，这边残留的文件也删掉，
            // 否则副本只会越攒越大（索引里没有的歌不会命中，但文件一直占着）
            indexBody?.let { body ->
                val entries = runCatching { Json.parseToJsonElement(body).jsonArray }.getOrNull()
                if (entries != null) {
                    val keep = entries.mapNotNull {
                        it.jsonObject["fileName"]?.jsonPrimitive?.contentOrNull
                    }.toSet()
                    var removed = 0
                    dir.listFiles()?.forEach { f ->
                        if (f.isFile &&
                            f.name.startsWith("axis-") &&
                            f.name.endsWith(".json") &&
                            f.name !in keep &&
                            f.delete()
                        ) {
                            removed++
                        }
                    }
                    if (removed > 0) DiagnosticsLog.i(TAG, "副本清理 $removed 个已删除的轴")
                }
            }
            // 指向副本目录：之后查轴先读盘，不再跨进程叫醒 App
            ManualAxisResolver.setRootOverride(dir)
            // 2.0.103：新到的轴同步进本进程轴库，下一首直接走索引查询
            mirrorDb?.reindex()
            DiagnosticsLog.i(TAG, "收到轴推送 $written 个文件 → ${dir.absolutePath}")
        }.onFailure { e ->
            Log.w(TAG, "处理轴推送失败: ${e.javaClass.simpleName}: ${e.message}")
        }
        invalidateAxisCache()
    }

    /**
     * App 轴曲库里删掉某几首：把本进程的副本、索引、轴库行一起清掉。
     *
     * 只发「重新读盘」不够 —— 这边手里攥着副本文件和 sqlite 行，
     * App 删了、岛还照样拿它顶上，用户会以为删除没生效。
     */
    fun drop(context: Context, keys: List<String>) {
        if (keys.isEmpty()) return
        val dir = File(context.filesDir, MIRROR_DIR)
        runCatching {
            val dropSet = keys.filter { it.isNotBlank() }.toHashSet()
            if (dropSet.isEmpty()) return
            val index = runCatching { AxisStore(dir).loadIndex() }.getOrDefault(emptyList())
            // 1. 副本文件：先按索引找到文件名再删，不猜哈希
            index.filter { it.songKey in dropSet }.forEach { entry ->
                runCatching { File(dir, entry.fileName).delete() }
            }
            // 2. 副本索引：同步去掉这几条，别留指向已删文件的条目
            val remain = index.filterNot { it.songKey in dropSet }
            if (remain.size != index.size) {
                runCatching { AxisStore(dir).saveIndex(remain) }
            }
            // 3. 本进程轴库 + 「这首没轴」结论
            keys.forEach { key -> mirrorDb?.drop(key) }
            // 4. 内存里已知的结论
            synchronized(cacheLock) {
                keys.forEach { key ->
                    cache.remove(key)
                    cacheAt.remove(key)
                }
            }
            DiagnosticsLog.i(TAG, "已删除副本轴 ${keys.size} 首")
        }.onFailure { e ->
            Log.w(TAG, "删除副本轴失败: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
    /**
     * 换歌时调用：命中人工轴则返回替换过 lyrics 的 Song，否则原样返回。
     *
     * **本方法保证不阻塞调用线程。** 结果分两路来：
     *   - 缓存里已经有结论 → 当场返回；
     *   - 没有 → 返回原 Song（先按在线歌词正常出画面），后台查完通过 [onLateHit] 回调。
     *
     * @param onLateHit 后台查到轴时在主线程回调，参数是替换过 lyrics 的 Song。
     */
    fun applyIfHit(song: Song?, onLateHit: ((Song) -> Unit)? = null): Song? {
        val s = song ?: return null
        val title = s.name.orEmpty()
        if (title.isBlank()) return s
        val key = keyOf(s)

        val known: Boolean
        val axis: ManualAxis?
        var stale = false
        synchronized(cacheLock) {
            known = cache.containsKey(key)
            axis = cache[key]
            // "查过、没轴"的结论过期了就当没查过，重新来一次
            if (known && axis == null) {
                val at = cacheAt[key] ?: 0L
                stale = SystemClock.elapsedRealtime() - at > MISS_TTL_MS
            }
        }

        if (!known || stale) {
            // 主线程不等：先按原歌词走，后台查到再回来换
            DiagnosticsLog.i(TAG, if (stale) "miss(过期重查) key=$key" else "miss(pending) key=$key")
            enqueue(key, s, onLateHit)
            return s
        }
        if (axis == null) {
            DiagnosticsLog.i(TAG, "miss key=$key")
            return s
        }
        return applyAxis(s, key, axis)
    }

    /**
     * 同步查一次并当场返回。
     *
     * **只给"打轴器刚保存完"用**（AXIS_SAVED 那条路径）：那一刻 App 进程必定活着，
     * 一次 call 是毫秒级。普通换歌路径不许调这个，否则又回到主线程等 5 秒。
     */
    fun applyIfHitNow(song: Song?): Song? {
        val s = song ?: return null
        val title = s.name.orEmpty()
        if (title.isBlank()) return s
        val key = keyOf(s)
        val r = lookup(key)
        // 查询失败不写缓存：否则 SystemUI 刚起（runtime 还没挂上）那一下会把这首歌判成"没轴"
        if (r.ok) {
            synchronized(cacheLock) {
                evictIfNeeded()
                cache[key] = r.axis
                cacheAt[key] = SystemClock.elapsedRealtime()
            }
            if (r.axis == null) {
                mirrorDb?.markMiss(key, System.currentTimeMillis())
            } else {
                mirrorDb?.clearMiss(key)
            }
        }
        val axis = r.axis
        if (axis == null) {
            DiagnosticsLog.i(TAG, "miss key=$key" + if (r.ok) "" else " (查询失败，不写缓存)")
            return s
        }
        return applyAxis(s, key, axis)
    }

    private fun keyOf(s: Song): String = ManualAxisApi.songKeyOf(
        s.name.orEmpty(),
        s.artist.orEmpty(),
        s.duration.takeIf { it > 0L } ?: 0L,
    )

    /** 超出上限时只淘汰最久没访问的一条；不要把整张表清空。调用方需持有 cacheLock。 */
    private fun evictIfNeeded() {
        while (cache.size > CACHE_MAX) {
            val oldest = cache.keys.firstOrNull() ?: break
            cache.remove(oldest)
            cacheAt.remove(oldest)
        }
    }

    private fun enqueue(key: String, song: Song, onLateHit: ((Song) -> Unit)?) {
        synchronized(cacheLock) {
            if (!querying.add(key)) return
        }
        worker.post {
            val r = try {
                lookup(key)
            } catch (e: Throwable) {
                Lookup(null, false)
            }
            synchronized(cacheLock) {
                querying.remove(key)
                // 只有"问到了"才落缓存；失败的结果不算数，下次切歌还能重新问
                if (r.ok) {
                    evictIfNeeded()
                    cache[key] = r.axis
                    cacheAt[key] = SystemClock.elapsedRealtime()
                }
            }
            // 结论同时落库：命中清掉旧 miss，没轴记一条带墙上时间的 miss（跨重启仍有效）
            if (r.ok) {
                if (r.axis == null) {
                    mirrorDb?.markMiss(key, System.currentTimeMillis())
                } else {
                    mirrorDb?.clearMiss(key)
                }
            }
            val axis = r.axis
            if (axis == null) {
                DiagnosticsLog.i(TAG, "miss key=$key" + if (r.ok) "" else " (查询失败，不写缓存)")
                return@post
            }
            val replaced = applyAxis(song, key, axis)
            if (onLateHit != null) {
                main.post { onLateHit(replaced) }
            }
        }
    }

    /** 把人工轴塞进 Song 的 lyrics。纯数据操作，任意线程可调。 */
    private fun applyAxis(s: Song, key: String, axis: ManualAxis): Song {
        // 「暂无歌词，请您欣赏」不是歌词，不能算"已有推送词"，否则本地轴永远顶不上去
        val hasOwnLyric = s.lyrics?.any {
            !com.liuxiansheng.lyricon.axis.core.LyricPlaceholder.isPlaceholder(it.text)
        } == true
        val isManual = axis.isManual
        // 两条规矩：
        // 一、**手打的轴一律优先** —— 一句句敲出来的，比任何来源都准。
        // 二、**在线词自动铺的轴永远不顶掉推送歌词** ——
        //    Pro provider / 播放器 MediaSession 给的词是按这首歌现场来的，
        //    比按歌名在网上搜的准。只有整首真没词时，才用这份轴救场。
        if (!isManual && hasOwnLyric) {
            DiagnosticsLog.i(TAG, "hit key=$key 但已有推送歌词（origin=online），保留推送词")
            return s
        }
        // 2.0.103：轴明显不全时不整段替换 —— 这是"打了轴反而丢歌词"的主要来源。
        // 判据：轴上的词不到在线行数的 80%（在线轴 90%），就当这份轴没打全，保留在线歌词。
        // 在线本来就没词时不设限（轴再少也比空着强）。
        val onlineCount = s.lyrics?.count {
            !com.liuxiansheng.lyricon.axis.core.LyricPlaceholder.isPlaceholder(it.text)
        } ?: 0
        val axisCount = axis.lines.size
        if (axisCount == 0) {
            DiagnosticsLog.i(TAG, "hit key=$key 但轴是空的，保留原歌词")
            return s
        }
        val minPercent = if (isManual) 80 else 90
        if (onlineCount > 0 && axisCount * 100 < onlineCount * minPercent) {
            DiagnosticsLog.i(
                TAG,
                "hit key=$key 但轴不全 axisLines=$axisCount onlineLines=$onlineCount" +
                    "（不足 $minPercent%，origin=${axis.origin}），保留在线歌词"
            )
            return s
        }
        // 整轴调值：只平移时间，不改记录。负=提前（歌词早出现），正=延后
        val off = axis.offsetMs.toLong()
        fun shift(ms: Long): Long = (ms + off).coerceAtLeast(0L)

        val lines = axis.lines.map { line ->
            val lb = shift(line.beginMs)
            val le = shift(line.endMs).coerceAtLeast(lb + 1L)
            val words = if (line.words.isEmpty()) {
                null
            } else {
                line.words.mapIndexed { wi, w ->
                    val wb = shift(w.beginMs)
                    val next = line.words.getOrNull(wi + 1)?.let { shift(it.beginMs) } ?: le
                    val wEnd = next.coerceAtLeast(wb + 1L)
                    LyricWord(begin = wb, end = wEnd, text = w.text)
                }
            }
            RichLyricLine(
                begin = lb,
                end = le,
                duration = (le - lb).coerceAtLeast(1L),
                text = line.text,
                // 有字级时间就一起给渲染链，逐字进度才走得动；没有就退回整行高亮
                words = words,
            )
        }
        DiagnosticsLog.i(
            TAG,
            "hit key=$key lines=${lines.size} lead=${axis.leadMs} offset=${axis.offsetMs} " +
                "precision=${axis.precision} words=${axis.lines.sumOf { it.words.size }}"
        )
        return s.copy(lyrics = lines)
    }

    /**
     * 一次查询的结果。
     *
     * [ok] 用来区分两种情况：「问到了，这首确实没轴」和「压根没问成」（SystemUI 刚起 runtime
     * 还没挂上、跨进程调用失败、回包解析不了）。后者**绝对不能写进缓存** ——
     * 写进去就等于把这首歌判死，切回来也不会再查，只有重启状态栏才恢复。
     */
    private class Lookup(val axis: ManualAxis?, val ok: Boolean)

    private fun lookup(key: String): Lookup {
        val ctx = runtime ?: run {
            DiagnosticsLog.i(TAG, "miss key=$key reason=no_context")
            return Lookup(null, false)
        }
        // 1) 先查本进程的轴库（sqlite）：按 key 一次索引查询，不读 json、不碰 App 进程
        mirrorDb?.get(key)?.let { axis ->
            DiagnosticsLog.i(TAG, "hit(轴库) key=$key lines=${axis.lines.size}")
            return Lookup(axis, true)
        }
        // 2) 轴库里没有 → 再读副本文件（首启还没 reindex 完时兜底，也兼容旧数据）
        val local = runCatching {
            val dir = File(ctx.filesDir, MIRROR_DIR)
            if (!dir.isDirectory) return@runCatching null
            ManualAxisResolver.setRootOverride(dir)
            ManualAxisResolver.find(ctx, key)
        }.getOrNull()
        if (local != null) {
            DiagnosticsLog.i(TAG, "hit(副本) key=$key")
            return Lookup(local, true)
        }
        // 3) 副本里也没有 → 退回 App 代读（可能会叫醒它的进程，只在这一种情况下发生）
        val bundle: Bundle? = try {
            ctx.contentResolver.call(
                Uri.parse("content://$AUTHORITY"),
                METHOD_LOOKUP,
                key,
                null
            )
        } catch (e: Throwable) {
            // 这里是"查不到"和"查询本身失败"的分水岭：跨进程被系统拦住时要能从日志看出来
            Log.w(TAG, "miss key=$key 跨进程查询失败: ${e.javaClass.simpleName}: ${e.message}")
            return Lookup(null, false)
        }
        if (bundle == null) {
            Log.w(TAG, "miss key=$key 跨进程调用没有回包")
            return Lookup(null, false)
        }
        if (bundle.getBoolean("hit", false).not()) {
            // 明确答复：这首没有人工轴
            return Lookup(null, true)
        }
        val axis = bundle.getString("json")?.let { raw ->
            runCatching { json.decodeFromString<ManualAxis>(raw) }.getOrNull()
        }
        if (axis == null) {
            Log.w(TAG, "miss key=$key 命中回包解析失败")
            return Lookup(null, false)
        }
        return Lookup(axis, true)
    }
}
