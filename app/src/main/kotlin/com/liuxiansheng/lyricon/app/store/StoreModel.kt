/*
 * 词岛 · 超级岛歌词 Xposed 模块（小米澎湃 OS / HyperOS）
 * 包名：io.github.liuxiansheng12369.chidao
 * Copyright 2026 liuxiansheng（刘先生）
 * 本文件为 词岛 自研实现。
 *
 * StoreModel：歌词源商店的数据模型。
 * 类型（kind）、可用性、安装状态、操作状态机全部显式建模，
 * UI 只按这些枚举渲染，不在界面里拼字符串判断。
 */

package com.liuxiansheng.lyricon.app.store

import com.liuxiansheng.lyricon.app.R

/** 商店条目类型，与上游目录 wire 值一致。 */
enum class StoreKind(val wire: String, val label: String) {
    DEDICATED_LYRICS("dedicated-lyrics", "专属播放器"),
    UNIVERSAL_LYRICS("universal-lyrics", "通用播放器"),
    ARTWORK("artwork", "动态封面"),
    ;

    companion object {
        fun fromWire(value: String): StoreKind? = entries.firstOrNull { it.wire == value }
    }
}

data class StoreArtifact(
    /** 同一份字节的多个镜像，顺序即优先顺序；每个主机都在目录的信任清单里。 */
    val urls: List<String>,
    val bytes: Long,
    val sha256: String,
    /** 客户端预置的签名证书集合的子集；目录不能引入新的签名者。 */
    val signerSha256: Set<String>,
)

/**
 * 条目来自哪份目录。界面按来源分区，标题直接用上游作者的名字，不写"自建"。
 */
enum class StoreSource(
    /** 分区标题；官方目录不单独成区，按播放器类型分组，所以为 null。 */
    val sectionTitle: String?,
    /** 作者署名。 */
    val author: String,
    /** 作者主页。 */
    val authorUrl: String,
    /** 这批源的简述。 */
    val credit: String,
) {
    UPSTREAM(
        sectionTitle = null,
        author = "Andrea-lyz",
        authorUrl = "https://github.com/Andrea-lyz",
        credit = "ColorOS 歌词源官方目录",
    ),
    TOMAKINO(
        sectionTitle = "词岛兼容源",
        author = "Tomakino",
        authorUrl = "https://github.com/tomakino",
        credit = "汽水、酷狗、酷我、QQ、网易云、Apple 等 21 个源",
    ),
    ANDREA(
        sectionTitle = "ColorOS 歌词源",
        author = "Andrea-lyz",
        authorUrl = "https://github.com/Andrea-lyz",
        credit = "酷我、QQ、网易云、Apple、Spotify、Salt、光锥等 14 个源",
    ),
    ;

    val isOwnBuild: Boolean get() = this != UPSTREAM
}

data class StoreEntry(
    val providerId: String,
    val kind: StoreKind,
    val applicationId: String,
    val versionName: String,
    val versionCode: Long,
    val minSdk: Int,
    val minStoreProtocol: Int,
    val sourceCommit: String,
    val releaseNotes: String?,
    val artifact: StoreArtifact,
    val displayName: String,
    /** 目录下发的图标地址（https，主机须在 trustedHosts 内）；空表示走客户端本地图。 */
    val iconUrl: String? = null,
    val source: StoreSource = StoreSource.UPSTREAM,
) {
    /** 需要更新版商店协议的条目照常列出，但本客户端不负责安装。 */
    fun supportedBy(storeProtocol: Int = STORE_PROTOCOL): Boolean = minStoreProtocol <= storeProtocol
}

data class StoreCatalog(
    val revision: Long,
    val issuedAtEpochSeconds: Long,
    val expiresAtEpochSeconds: Long,
    val channel: String,
    val trustedHosts: Set<String>,
    val entries: List<StoreEntry>,
)

enum class StoreFreshness { FRESH, EXPIRED }

sealed interface CatalogState {
    /** 一份通过验签的目录都没有：列表只能显示已安装。 */
    data object Unavailable : CatalogState

    data class Current(
        val catalog: StoreCatalog,
        val freshness: StoreFreshness,
    ) : CatalogState {
        /** 过期目录只展示已装状态，不提供安装/更新。 */
        val installable: Boolean get() = freshness == StoreFreshness.FRESH
    }
}

/** 当前设备能不能装这一条。 */
enum class StoreAvailability {
    AVAILABLE,
    NO_CATALOG,
    CATALOG_EXPIRED,
    NEEDS_NEWER_STORE,
    UNSUPPORTED_SDK,
}

enum class InstallState {
    NOT_INSTALLED,
    INSTALLED,
    UPDATE_AVAILABLE,
    /** 设备上的版本比目录新（本地自编译/内测包），不提供降级。 */
    LOCAL_NEWER,
    /** 已装包的签名与目录允许的签名不符，不提供覆盖安装。 */
    SIGNATURE_MISMATCH,
}

/** PackageManager 报告的已装事实；读不到签名时为空集合（按不符处理，宁可少装）。 */
data class InstalledPackage(
    val versionName: String,
    val versionCode: Long,
    val signerSha256: Set<String>,
)

/** 卡片上唯一一条提示；警告优先于说明。 */
enum class StoreNote {
    SIGNATURE_MISMATCH,
    LOCAL_NEWER,
    NEEDS_NEWER_STORE,
    UNSUPPORTED_SDK,
    NONE,
}

data class StoreItem(
    val entry: StoreEntry,
    val installed: InstalledPackage?,
    val installState: InstallState,
    val availability: StoreAvailability,
    val note: StoreNote,
) {
    val providerId: String get() = entry.providerId
    val name: String get() = entry.displayName
    val actionLabel: String get() = when {
        !available -> "不可安装"
        installState == InstallState.UPDATE_AVAILABLE -> "更新"
        installState == InstallState.NOT_INSTALLED -> "安装"
        else -> "已安装"
    }
    val available: Boolean
        get() = availability == StoreAvailability.AVAILABLE &&
            installState != InstallState.SIGNATURE_MISMATCH &&
            installState != InstallState.LOCAL_NEWER
    val statusText: String get() = when (installState) {
        InstallState.NOT_INSTALLED -> "未安装"
        InstallState.INSTALLED -> "已安装 v${installed?.versionName.orEmpty()}"
        InstallState.UPDATE_AVAILABLE -> "已安装 v${installed?.versionName.orEmpty()}，可更新"
        InstallState.LOCAL_NEWER -> "本地版本更新 v${installed?.versionName.orEmpty()}"
        InstallState.SIGNATURE_MISMATCH -> "已装签名与目录不符"
    }
}

/** 一次商店操作；同一时刻只允许一个在跑。 */
sealed interface StoreOperation {
    val active: Boolean get() = false

    data class Downloading(val downloaded: Long, val total: Long) : StoreOperation {
        override val active get() = true
        val fraction: Float get() = if (total <= 0) 0f else (downloaded.toFloat() / total).coerceIn(0f, 1f)
    }

    data object Verifying : StoreOperation {
        override val active get() = true
    }

    /** 已校验完，等用户允许"安装未知应用"；不会循环弹。 */
    data object NeedsInstallPermission : StoreOperation

    data object Installing : StoreOperation {
        override val active get() = true
    }

    /** 系统安装器正在等用户确认，既不算成功也不算失败。 */
    data object WaitingForUser : StoreOperation {
        override val active get() = true
    }

    data class Succeeded(val versionName: String) : StoreOperation

    data class Failed(val failure: StoreFailure) : StoreOperation
}

/** 操作失败原因，每个都有固定说法。 */
enum class StoreFailure {
    NETWORK,
    CONTENT_MISMATCH,
    IDENTITY_MISMATCH,
    STORAGE,
    CANCELLED,
    CONFLICT,
    INCOMPATIBLE,
    BLOCKED,
    INVALID,
    INTERRUPTED,
    NOT_VERIFIED,
    OTHER,
    ;

    val text: String
        get() = when (this) {
            NETWORK -> "下载失败，换个网络再试"
            CONTENT_MISMATCH -> "下载内容与目录签名不符，已丢弃"
            IDENTITY_MISMATCH -> "安装包的包名、版本或签名与目录不符，已拒绝"
            STORAGE -> "存储写入失败（空间不足？）"
            CANCELLED -> "已取消"
            CONFLICT -> "与已安装的同名应用签名冲突"
            INCOMPATIBLE -> "系统版本不兼容"
            BLOCKED -> "被系统安全策略拦截"
            INVALID -> "安装包无效"
            INTERRUPTED -> "安装过程被中断，可重试"
            NOT_VERIFIED -> "安装器报成功，但系统里没查到对应版本"
            OTHER -> "未知原因失败"
        }
}

/** 商店本客户端实现的协议版本。 */
const val STORE_PROTOCOL: Int = 1

/**
 * providerId → 显示名。名字一律取各自模块 res/values/strings.xml 里的 app_name，
 * 两个作者各显示各的正式名，同一款播放器的两条源不会撞名。表里没有的回退成 providerId。
 */
private val DISPLAY_NAMES = mapOf(
    // ColorOS-Live-Lyrics-Providers（Andrea-lyz）14 条
    "player-qishui" to "汽水音乐歌词 Provider",
    "player-kugou" to "酷狗音乐歌词 Provider",
    "kuwo-music" to "酷我音乐歌词 Provider",
    "player-qq" to "QQ 音乐歌词 Provider",
    "player-netease" to "网易云 / 荣耀云音乐歌词 Provider",
    "player-apple" to "Apple Music Lyric Provider",
    "player-spotify" to "Spotify Lyric Provider",
    "player-salt" to "Salt Player Lyric Provider",
    "player-cone" to "ConePlayer Lyric Provider",
    "player-lx" to "LX Music Lyric Provider",
    "player-poweramp" to "Poweramp Lyric Provider",
    "player-readify" to "Readify",
    "universal-provider" to "通用播放器Provider",
    "artwork-provider-am" to "Dynamic Artwork Provider",
    // LyricProvider（Tomakino）21 条
    "qishui" to "汽水音乐歌词支持",
    "kugou" to "酷狗音乐歌词支持",
    "kuwo" to "酷我音乐歌词支持",
    "qq-music" to "QQ音乐歌词提供",
    "qq-music-hd" to "QQ音乐HD歌词提供",
    "qq-music-free" to "QQ音乐简洁版歌词支持",
    "163-music" to "网易云音乐歌词支持",
    "apple-music" to "Apple Music Provider",
    "spotify" to "Spotify歌词支持",
    "poweramp" to "PowerAmp Lyric Provider",
    "lx" to "洛雪音乐歌词支持",
    "symfonium" to "Symfonium Server",
    "gramophone" to "Gramophone歌词支持",
    "retro" to "Retro Music歌词支持",
    "splayer" to "SPlayer歌词支持",
    "music-free" to "MusicFree歌词支持",
    "feiniu" to "飞牛音乐歌词提供",
    "bodian" to "波点音乐歌词支持",
    "car-provider" to "车载歌词支持",
    "cloud-provider" to "云歌词支持",
    "meizu-provider" to "魅族歌词支持",
    "miui-music" to "小米音乐歌词支持",
)

fun storeDisplayName(providerId: String): String = DISPLAY_NAMES[providerId] ?: providerId

/**
 * providerId → 图标（目标播放器官方 launcher 图标的副本，与上游商店同一套素材）。
 * Readify 自己没有 launcher 图标，表里也没有它，卡片上是首字母块。
 */
val STORE_ICONS: Map<String, Int> = mapOf(
    "player-qishui" to R.drawable.store_icon_qishui,
    "player-kugou" to R.drawable.store_icon_kugou,
    "kuwo-music" to R.drawable.store_icon_kuwo,
    "player-qq" to R.drawable.store_icon_qq,
    "player-netease" to R.drawable.store_icon_netease,
    "player-apple" to R.drawable.store_icon_apple,
    "player-spotify" to R.drawable.store_icon_spotify,
    "player-salt" to R.drawable.store_icon_salt,
    "player-cone" to R.drawable.store_icon_cone,
    "player-lx" to R.drawable.store_icon_lx,
    "player-poweramp" to R.drawable.store_icon_poweramp,
    "universal-provider" to R.drawable.store_icon_universal,
    "artwork-provider-am" to R.drawable.store_icon_artwork,
    // 自建目录的 providerId 沿用对应播放器的官方图标；没有专属图标的用通用源图标
    "qishui" to R.drawable.store_icon_qishui,
    "kugou" to R.drawable.store_icon_kugou,
    "kuwo" to R.drawable.store_icon_kuwo,
    "qq-music" to R.drawable.store_icon_qq,
    "qq-music-hd" to R.drawable.store_icon_qq,
    "qq-music-free" to R.drawable.store_icon_qq,
    "163-music" to R.drawable.store_icon_netease,
    "apple-music" to R.drawable.store_icon_apple,
    "spotify" to R.drawable.store_icon_spotify,
    "poweramp" to R.drawable.store_icon_poweramp,
    "lx" to R.drawable.store_icon_lx,
    "player-readify" to R.drawable.store_icon_readify,
    // 词幕自建源：能拿到目标播放器官方图标的用官方图，剩下的是自绘（同风格扁平+符号）
    "bodian" to R.drawable.store_icon_bodian,
    "feiniu" to R.drawable.store_icon_feiniu,
    "music-free" to R.drawable.store_icon_musicfree,
    "gramophone" to R.drawable.store_icon_gramophone,
    "retro" to R.drawable.store_icon_retro,
    "symfonium" to R.drawable.store_icon_symfonium,
    "splayer" to R.drawable.store_icon_splayer,
    "car-provider" to R.drawable.store_icon_car,
    "cloud-provider" to R.drawable.store_icon_cloud,
    "meizu-provider" to R.drawable.store_icon_meizu,
    "miui-music" to R.drawable.store_icon_xiaomi,
)

/** 体积文案。 */
fun storeSizeText(bytes: Long): String = when {
    bytes <= 0 -> "体积未知"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format("%.1f MB", bytes / 1048576.0)
}
