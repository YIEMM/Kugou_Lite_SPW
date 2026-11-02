package cn.spw.kugou.lite.sidecar

/**
 * 酷狗 API 返回的数据模型。
 *
 * ## 设计原则
 *
 * 1. **字段全部可空/带默认值** —— 第三方接口随时会变，缺字段不能崩。
 * 2. **不做重命名映射** —— 直接对应接口字段名（Kotlin 用驼峰），
 *    便于对照真实响应排错。
 * 3. **解析用容错的 [JsonLite]**，不引入 kotlinx-serialization：
 *    插件已有一个 `compileOnly` 的 Compose 依赖树，再加序列化库会放大
 *    shadow jar 与宿主类冲突的风险。
 */

/** 一首歌。搜索、歌单、榜单、每日推荐共用。 */
data class Song(
    /** 完整文件名，如「周杰伦 - 发如雪」。 */
    val fileName: String,
    /** 纯歌名，如「发如雪」。 */
    val title: String,
    /** 歌手名，多个用「、」连接。 */
    val singer: String,
    /**
     * 酷狗文件哈希。
     *
     * **可能为空** —— 榜单接口（`/rank/audio`）返回的歌曲**完全没有 hash 字段**，
     * 只给 `album_audio_id`。所以取流不能只依赖这个字段，见 [streamKey]。
     */
    val hash: String,
    /** 时长（秒）。 */
    val durationSec: Int,
    /** 封面模板 URL，含 `{size}` 占位符，需要替换成像素值。 */
    val imageUrl: String?,
    /** 文件大小（字节），用于 UI 上估算。 */
    val fileSize: Long,
    /** 版权/付费标记。`0` 通常可免费取流。 */
    val payType: Int,
    /** 专辑名。 */
    val albumName: String?,
    /**
     * 专辑音频 id。榜单接口用它替代 hash 取流。
     *
     * 实测 `GET /song/url?album_audio_id=967249675` 可以直接拿到 CDN 直链，
     * 不需要 hash。
     */
    val albumAudioId: String = "",
) {
    /** 时长格式化为 `m:ss`。 */
    val durationText: String
        get() {
            if (durationSec <= 0) return "--:--"
            val m = durationSec / 60
            val s = durationSec % 60
            return "%d:%02d".format(m, s)
        }

    /** 把封面模板 URL 的 `{size}` 换成实际像素。 */
    fun coverUrl(px: Int = 240): String? =
        imageUrl?.replace("{size}", px.toString())?.takeIf { it.startsWith("http") }

    /**
     * 是否可以取流。
     *
     * hash 或 album_audio_id 至少有一个就能试。两者都没有的条目
     * （极少数残缺数据）不应出现在可点击列表里。
     */
    val canStream: Boolean get() = hash.isNotBlank() || albumAudioId.isNotBlank()

    /**
     * 取流时优先用的查询参数。
     *
     * 用 hash 时走标准路径；只有 album_audio_id 时（榜单歌曲）走替代路径。
     */
    val streamQuery: String
        get() = when {
            hash.isNotBlank() -> "hash=$hash"
            albumAudioId.isNotBlank() -> "album_audio_id=$albumAudioId"
            else -> ""
        }
}

/** 搜索结果。 */
data class SearchResult(
    val songs: List<Song>,
    val total: Int,
)

/** 取流结果。 */
data class StreamInfo(
    /** 主地址。 */
    val url: String,
    /** 备用 CDN 地址。 */
    val backupUrls: List<String>,
    /** 实际码率（bps），如 128000。 */
    val bitRate: Int,
    /** 文件大小（字节）。 */
    val fileSize: Long,
    /** 扩展名，如 mp3 / flac。 */
    val extName: String,
    /** 时长（秒）。 */
    val durationSec: Int,
) {
    val allUrls: List<String> get() = listOf(url) + backupUrls

    /** 展示用音质描述。 */
    val qualityText: String
        get() = when {
            bitRate >= 900_000 || extName.equals("flac", true) -> "无损"
            bitRate >= 320_000 -> "320k"
            bitRate >= 128_000 -> "128k"
            else -> "${bitRate / 1000}k"
        }
}

/** 歌词搜索结果里的一条候选。 */
data class LyricCandidate(
    /** 歌词库内部 id，取歌词用。 */
    val id: String,
    /** 访问密钥，取歌词用。 */
    val accessKey: String,
    val song: String,
    val singer: String,
    val durationMs: Long,
)

/** 歌单。 */
data class Playlist(
    val id: String,
    val name: String,
    val coverUrl: String?,
    val songCount: Int,
    val playCount: Long,
    val description: String?,
)

/** 榜单。 */
data class Rank(
    val id: String,
    val name: String,
    val coverUrl: String?,
    val songCount: Int,
)
