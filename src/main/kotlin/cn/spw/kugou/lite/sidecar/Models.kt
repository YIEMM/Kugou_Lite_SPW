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
    /**
     * `media_privilege`：0 = 普通可播；非 0 = 有额外限制（无版权/会员专享）。
     *
     * 实测「この世界を愛したいんだ」为 5、取流返回 `31833`（无版权）；
     * 可播歌曲为 0。判定见 [playabilityOf]。
     */
    val mediaPrivilege: Int = 0,
    /**
     * `trans_param.display`：0 = 平台不展示播放，1 = 正常，-1 = 字段缺失。
     */
    val displayFlag: Int = -1,
    /**
     * 已探测到的可播放性。
     *
     * 接口字段猜不出来（见 [playabilityOf] 的反证），只能靠实际取流探测后回填。
     * [Playability.Unknown] = 还没探测，界面不标任何标签。
     */
    val probed: Playability = Playability.Unknown,
    /** 专辑名。 */
    val albumName: String?,
    /**
     * 专辑音频 id。榜单接口用它替代 hash 取流。
     *
     * 实测 `GET /song/url?album_audio_id=967249675` 可以直接拿到 CDN 直链，
     * 不需要 hash。
     */
    val albumAudioId: String = "",
    /**
     * 歌手 ID 列表（酷狗内部的 author_id）。
     *
     * 搜索接口的 `Singers[].id` 里就有。点击歌手名可以用它进歌手页。
     * 榜单/歌单接口不一定提供，所以可能为空。
     */
    val artistIds: List<String> = emptyList(),
    /**
     * 逐位歌手的 (名字, id) 对，用于让**每个歌手名单独可点**。
     *
     * ## 为什么不能只用 [singer] + [artistIds]
     *
     * [singer] 是把多位歌手用「、」拼起来的**一个字符串**，
     * 而 [artistIds] 是并列的 id 列表。界面上如果只把它们当成
     * 一整个标签，点击就只能跳到第一位歌手 —— 其余歌手没法单独点。
     *
     * 所以这里保留结构化信息：每一位歌手对应一项，`id` 可能为空
     * （歌单接口不提供 id 时），此时该名字照常显示但不可点。
     *
     * 为空表示接口没给出任何歌手信息，界面上退化为
     * 显示 [singer] 纯文本。
     */
    val artists: List<ArtistRef> = emptyList(),
    /**
     * 专辑 id。用于点击专辑名进入专辑页。
     *
     * 各接口基本都提供（`album_id`），但歌单接口可能没有，此时为空。
     */
    val albumId: String = "",
    /**
     * 酷狗的 mixsongid（混合作品 id）。
     *
     * 收藏到歌单时 `/playlist/tracks/add` 需要它：
     * `data` 参数格式是 `歌名|hash|album_id|mixsongid`。
     * 缺失时传 0，服务端多数情况仍能识别（靠 hash）。
     */
    val mixSongId: String = "",
    /**
     * 歌在歌单里的**文件 id**（`fileid`）。
     *
     * ## 为什么删除必须靠它
     *
     * `/playlist/tracks/del` 的参数是 `fileids`，值是 fileid（数字），
     * 不是 hash。传 hash 时上游会当成「按 hash 查找」处理，
     * 但实测常常返回 status=0（没删掉）。
     *
     * fileid 只有**读取歌单曲目**时才返回（`/playlist/track/all`
     * 的每条记录里带 `fileid`），搜索结果里没有。
     * 所以为 0 表示「这首歌不是从歌单里读出来的」。
     */
    val fileId: Long = 0L,
    /**
     * 私人 FM 的推荐游标（`cur_mark`）。
     *
     * `/personal/fm` 在 `data.cur_mark` 里回传一个不透明令牌，下一轮请求
     * **原样带上**才能续上同一条推荐流；不带会从头开始推、出现重复曲目。
     *
     * 只有 FM 接口返回的歌曲会填它，其它接口一律为空串。
     * 放在构造参数末尾且带默认值，避免破坏已有的 [Song] 构造调用。
     */
    val curMark: String = "",
) {
    /**
     * 时长格式化为 `m:ss`。
     *
     * 超过 24 小时（86400 秒）视为脏数据，直接显示 `--:--` ——
     * 真机上曾出现 `4500:27` 这种值，根因是某接口的时长单位是毫秒
     * （已在 [KugouApi.parseDurationSec] 统一归一化）。这里再加一道防线，
     * 保证 UI 永远不会渲染出荒谬的时长。
     */
    val durationText: String
        get() {
            if (durationSec <= 0 || durationSec > 86_400) return "--:--"
            val m = durationSec / 60
            val s = durationSec % 60
            return "%d:%02d".format(m, s)
        }

    /** 把封面模板 URL 的 `{size}` 换成实际像素。 */
    fun coverUrl(px: Int = 240): String? =
        imageUrl?.replace("{size}", px.toString())?.takeIf { it.startsWith("http") }

    /**
     * 可播放性（用户需求：无版权歌曲标注、播放全部时剔除）。
     *
     * 只认探测结果 —— 不再用 `media_privilege` 猜（实测该字段与可播放性无关，
     * 见 [playabilityOf] 的反证表格）。
     */
    val playability: Playability get() = probed

    /** 是否**确认**无版权（未知不算）。 */
    val isNoCopyright: Boolean get() = probed == Playability.NoCopyright

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
/**
 * 歌单。
 *
 * ## 自建 vs 收藏
 *
 * `/user/playlist` 的每条记录带 `type`：
 * - `type = 0`：**自建**歌单（含「我喜欢」「默认收藏」）
 * - `type = 1`：**收藏**别人的歌单
 *
 * 这两类在界面上要分开呈现 —— 自建是「我的」，收藏是「我收的」。
 *
 * ## 「我喜欢」的识别
 *
 * 酷狗把「我喜欢」做成一条固定 `listid=2` 的自建歌单。
 * 所以 [isFavorites] 按 [listId] 判断，不按名字（名字可能被用户改）。
 */
data class Playlist(
    val id: String,
    val name: String,
    val coverUrl: String?,
    val songCount: Int,
    val playCount: Long,
    val description: String?,
    /** 数字形式的歌单 id（`listid`），用于写操作。 */
    val listId: String = "",
    /** `type=0` 自建，`type=1` 收藏。 */
    val type: Int = 0,
    /** 是否是「我喜欢」。 */
    val isFavorites: Boolean = false,
    /**
     * 原作者歌单的 gid（`list_create_gid`）。
     *
     * 收藏别人的歌单时，`global_collection_id` 是**自己收藏副本**的 gid，
     * 曲目可能因下架而比原歌单少（实测「好听的沉浸式日文歌」副本 16 首、
     * 原歌单 18 首）。取曲目时收藏歌单应优先用本字段。
     */
    val listCreateGid: String = "",
) {
    /** 是否自建。 */
    val isOwn: Boolean get() = type == 0

    /** 取曲目时实际该用的 gid（见 [pickPlaylistGid]）。 */
    val fetchGid: String get() = pickPlaylistGid(type, id, listCreateGid)

    /**
     * 取指定尺寸的封面地址。
     *
     * 酷狗 CDN 的封面地址多含 `{size}` 占位（如
     * `http://c1.kgimg.com/stdmusic/{size}/xxx.jpg`），
     * 把它替换成像素数即可。不含占位的地址原样返回。
     */
    fun coverAt(px: Int = 300): String? {
        val url = coverUrl ?: return null
        return if (url.contains("{size}")) url.replace("{size}", px.toString()) else url
    }
}

/**
 * 是否为「收藏的别人的歌单」。
 *
 * `/user/playlist` 的 `type`：0=自建 / 1=收藏。
 * 只有收藏歌单才存在「副本 gid」与「原作者 gid」不一致的问题。
 */
fun isCollectedPlaylist(type: Int): Boolean = type == 1

/**
 * 选一个用来拉曲目的 gid（用户反馈 #1 的根因）。
 *
 * ## 两个 gid 的来历
 *
 * 收藏别人的歌单时，酷哥会给你一份**收藏副本**：
 *
 * | 字段 | 含义 | 曲目数 |
 * |---|---|---|
 * | `global_collection_id` | 自己收藏副本的 gid | 可能少（下架曲目不进副本） |
 * | `list_create_gid` | 原作者歌单的 gid | 完整 |
 *
 * 实测「好听的沉浸式日文歌」：副本 16 首、原歌单 18 首。
 * MoeKoe / 官方酷狗 Lite 能拿全，就是因为对收藏歌单用 `list_create_gid`。
 *
 * 规则：
 * - 收藏歌单（type=1）且有 `listCreateGid` → 用原作者 gid
 * - 其余（自建歌单、缺字段）→ 用 `globalCollectionId`
 */
fun pickPlaylistGid(type: Int, globalCollectionId: String, listCreateGid: String): String =
    if (isCollectedPlaylist(type) && listCreateGid.isNotBlank()) listCreateGid
    else globalCollectionId

/** 榜单。 */
data class Rank(
    val id: String,
    val name: String,
    val coverUrl: String?,
    val songCount: Int,
)

/**
 * 歌手。
 *
 * `id` 来自搜索结果里的 `Singers[].id`（酷狗内部的 author_id）。
 */
data class Artist(
    val id: String,
    val name: String,
    /** 头像（`union_cover` 或其他头像字段）。 */
    val avatarUrl: String? = null,
)

/**
 * 专辑。
 *
 * 字段来自 `/album/detail`（实测 `data` 是**数组**，取第一项）：
 * `album_name` / `author_name` / `sizable_cover`（含 `{size}` 占位）
 * / `publish_date` / `type` / `authors[]`。
 */
data class Album(
    val id: String,
    val name: String,
    val artist: String,
    /** 封面模板，含 `{size}` 占位。 */
    val coverTemplate: String? = null,
    val publishDate: String? = null,
    /** 专辑类型，如「单曲专辑」「录音室专辑」。 */
    val type: String? = null,
    val songCount: Int = 0,
) {
    /** 取指定尺寸的封面地址。 */
    fun coverUrl(px: Int = 500): String? =
        coverTemplate?.replace("{size}", px.toString())
}

/**
 * 歌曲里的「一位歌手」引用。
 *
 * `id` 可能为空：歌单接口只给拼接好的 `"A、B、C"` 字符串，
 * 拆不出 id。此时名字照常显示，但不可点击进歌手页。
 */
data class ArtistRef(
    val name: String,
    val id: String = "",
) {
    /** 有 id 才能进歌手页。 */
    val clickable: Boolean get() = id.isNotBlank()
}

/**
 * 发现页「新碟上架」区块里的专辑。
 *
 * ## 为什么和 [Album] 分开
 *
 * `/top/album` 每项只有 `albumid` / `albumname` / `singername` / `imgurl`
 * 四个字段，没有 [Album] 那套 `publish_date` / `type` / `song_count`。
 * 发现页只用来展示封面横排（点进去再调 `/album/detail` 取详情），
 * 所以单独用一个最小模型，避免到处构造半空的 [Album]。
 *
 * [coverUrl] **保留 `{size}` 占位**（`http://x/{size}/a.jpg`）：
 * 首屏小封面与详情页大封面像素不同，交给调用方按需替换。
 */
data class AlbumBrief(
    val id: String,
    val name: String,
    val artist: String,
    /** 封面模板，通常含 `{size}` 占位；接口没给时为 null。 */
    val coverUrl: String?,
) {
    /** 取指定尺寸的封面地址（替换 `{size}` 占位）。 */
    fun coverAt(px: Int = 240): String? =
        coverUrl?.replace("{size}", px.toString())?.takeIf { it.startsWith("http") }
}

// ---------------------------------------------------------------- 可播放性

/**
 * 歌曲可播放性。
 *
 * 用户需求：无版权歌曲在列表里标注「无版权」，点「播放全部」时自动剔除，
 * 单独点击时提示无法播放。
 */
enum class Playability {
    /** 可以播放。 */
    Ok,

    /** 无版权 / 已下架，点了也不能播。 */
    NoCopyright,

    /** 尚未探测（默认值，界面不标任何东西）。 */
    Unknown,
}

/**
 * 由接口字段判断可播放性 —— **不可靠，仅作粗筛**。
 *
 * ## 为什么不靠 media_privilege（实测反证）
 *
 * 2026-11 实测「yi_捏喜欢的音乐」歌单前 10 首：
 *
 * | 歌曲 | media_privilege | trans_param.display | 实际取流 |
 * |---|---|---|---|
 * | ハレハレヤ | 8 | 0 | **31833 无版权** |
 * | 群青 | 10 | 0 | **成功** ← 反证 |
 * | 春に落ちて | 8 | 0 | 成功 |
 * | 我们的歌 | 10 | 0 | 成功（收藏页解锁） |
 *
 * `media_privilege` 取 5/8/10 都既有能播也有不能播的，与可播放性**无相关性**。
 * 所以真正可靠的判定只有**实际取流一次**（见 [KugouApi::probePlayability]）。
 *
 * 这个函数只保留给「明确无版权」的单向判断，其余一律 [Playability.Unknown]。
 */
fun playabilityOf(mediaPrivilege: Int, displayFlag: Int): Playability = Playability.Unknown

/** 31833 = 无版权/已下架（取流接口实测返回 `illegal key`）。 */
fun isNoCopyrightErrorCode(code: Int): Boolean = code == 31833