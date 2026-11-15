package cn.spw.kugou.lite.sidecar

import cn.spw.kugou.lite.diag.Diag

/**
 * 发现页五个区块的接口：**请求 + 解析**。
 *
 * ## 边界
 *
 * 只做「发请求、把 JSON 变成模型」两件事 —— 不碰 UI、不碰播放、不碰会话状态
 * （FM 的游标与去重归 `PersonalFm`）。所以所有 `parseXxx` 都是**纯函数**：
 * 入参是已经解析好的 JSON 树，不发网络请求，可以离线单测。
 *
 * ## 五个接口的响应形态各不相同
 *
 * 这是本文件存在的唯一理由 —— 同一批「歌曲」在五个接口里的**位置和字段名都不一样**，
 * 用一套通用下钻会互相打架：
 *
 * | 区块 | 路径 | 歌曲/专辑在哪 | 坑 |
 * |---|---|---|---|
 * | 每日推荐 | `/everyday/recommend` | `data.song_list` | snake_case |
 * | 刷歌推荐 | `/home/discover` | `data.items[].song_info` | **多套一层** `song_info` |
 * | 新歌速递 | `/top/song` | `data` **直接是数组** | 不是 `data.songs` |
 * | 新碟上架 | `/top/album` | `data.chn/eur/jpn/kor` | 按地区分桶，没有统一列表 |
 * | 私人 FM | `/personal/fm` | `data.song_list` + `data.cur_mark` | 续流游标是上一首的 hash+songid，cur_mark 是反馈令牌 |
 *
 * ## 歌曲字段兼容不在这里重复实现
 *
 * 歌名有 `songname` / `audio_name` / `ori_audio_name` / `OriSongName` 等六七种写法，
 * 歌手有 `author_name` / `singername` / `Singers[]` 等。这套兼容逻辑已经在
 * [KugouApi] 里（那是所有列表接口共用的），这里**一律复用**
 * [KugouApi.parseSongForTest]，绝不复制第二份 —— 复制出去后任何一处修了
 * 另一处就漏，最终表现是「某个区块偶尔有歌没歌名」。
 *
 * ## 线程
 *
 * 带网络的方法都是**同步阻塞**的，必须在 [ApiExec] 的后台线程里调用，
 * 不能在 Compose 组合期直接调。
 */
object DiscoverApi {

    private const val TAG = "discover"

    /**
     * 新歌速递的榜单 id（上游 `rank_id`）。
     *
     * `/top/song` 的 `type` 参数就是榜单 id，`21608` 是「新歌速递」。
     * 参考实现里该值也是写死的默认值。
     */
    private const val TOP_SONG_RANK_ID = "21608"

    /** 发现页首屏每个歌曲区块只取 4 条（与 MD3Music 一致）。 */
    private const val HOME_DISCOVER_PAGE_SIZE = "4"

    // ------------------------------------------------------------ 纯解析

    /**
     * 解析「歌曲数组挂在 `data.<listKey>` 下」的响应。
     *
     * 每日推荐（`song_list`）、刷歌推荐、私人 FM 都是这个形状。
     *
     * ## 兜底链
     *
     * 1. `data.<listKey>`（正常形态）
     * 2. `<listKey>`（响应没套 `data` 信封的旧版本）
     * 3. `data` 本身是数组（`/top/song` 那种形态被误用到这里时也能出结果）
     *
     * 任一层类型不符就跳过，**绝不抛异常** —— 首页一个区块拿到脏数据
     * 不能让整页崩掉（宿主对组合期异常是直接杀进程）。
     *
     * @param listKey 歌曲数组的键名，默认 `song_list`
     */
    fun parseSongList(json: Any?, listKey: String = "song_list"): List<Song> {
        val body = JsonLite.dig(json, "data")
        val raw = JsonLite.asArray(JsonLite.dig(body, listKey))
            ?: JsonLite.asArray(JsonLite.dig(json, listKey))
            ?: JsonLite.asArray(body)
            ?: return emptyList()
        return parseSongs(raw)
    }

    /**
     * 解析刷歌推荐（`/home/discover`）。
     *
     * ## 结构（实测）
     *
     * ```json
     * { "status":1, "data": { "items": [
     *     { "item_type":"song", "song_info": { "songname":"…", "hash":"…" } }
     * ] } }
     * ```
     *
     * 歌曲**藏在 `items[].song_info` 里**，比其它接口多套一层。早期按
     * `data.song_list` 读会永远拿到空列表 —— 这正是「刷歌推荐空白」的原因。
     *
     * 同时兼容两种退化形态：`items[]` 元素本身就是歌曲（没有 `song_info`
     * 包装），以及 `data` 直接是数组。`item_type` 不是 song 的条目
     * （MV/专辑/歌单混排）只要没有 `song_info` 就会被 [KugouApi.parseSongForTest]
     * 按「缺 hash」丢弃，不需要在这里额外过滤。
     */
    fun parseHomeDiscover(json: Any?): List<Song> {
        val body = JsonLite.dig(json, "data")
        val items = JsonLite.asArray(JsonLite.dig(body, "items"))
            ?: JsonLite.asArray(JsonLite.dig(json, "items"))
            ?: JsonLite.asArray(body)
            ?: return emptyList()

        val songs = items.mapNotNull { item ->
            val obj = JsonLite.asObject(item) ?: return@mapNotNull null
            // 正常形态：歌曲在 song_info 里；退化形态：元素本身就是歌曲
            val songObj = JsonLite.asObject(obj["song_info"]) ?: obj
            KugouApi.parseSongForTest(songObj)
        }
        Diag.info(TAG, "刷歌推荐 → ${songs.size} 首")
        return songs
    }

    /**
     * 解析新歌速递（`/top/song`）。
     *
     * ## 结构（实测）
     *
     * ```json
     * { "status":1, "data": [ { "audio_name":"…", "hash":"…" }, … ] }
     * ```
     *
     * `data` **本身就是数组**，不是 `data.songs`。这是本接口与其它四个区块
     * 最大的形态差异，按 `data.songs` 读会永远空。
     *
     * 为防上游改版，`data.song_list` / `data.songs` / `data.info` 也作为
     * 兜底候选。
     */
    fun parseTopSong(json: Any?): List<Song> {
        val body = JsonLite.dig(json, "data")
        val raw = JsonLite.asArray(body)
            ?: listOf("song_list", "songs", "info")
                .firstNotNullOfOrNull { JsonLite.asArray(JsonLite.dig(body, it)) }
            ?: JsonLite.asArray(JsonLite.dig(json, "song_list"))
            ?: return emptyList()

        val songs = parseSongs(raw)
        Diag.info(TAG, "新歌速递 → ${songs.size} 首")
        return songs
    }

    /**
     * 解析新碟上架（`/top/album`）。
     *
     * ## 结构（实测 2026-11-07）
     *
     * ```json
     * { "status":1, "data": {
     *     "chn": [ { "albumid":1, "albumname":"…", "singername":"…", "imgurl":"…" } ],
     *     "eur": [ … ], "jpn": [ … ], "kor": [ … ]
     * } }
     * ```
     *
     * **按语种分桶**（华语/欧美/日本/韩国），每桶各 3 条，没有统一列表字段。
     * 所以必须按固定顺序 `chn → eur → jpn → kor` 拼接，顺序即界面上的展示顺序。
     *
     * 上游若改版成统一列表（`data.list` / `data.albums` / `data.info`），
     * 走兜底分支。
     */
    fun parseTopAlbum(json: Any?): List<AlbumBrief> {
        val body = JsonLite.dig(json, "data")

        val buckets = listOf("chn", "eur", "jpn", "kor")
            .mapNotNull { JsonLite.asArray(JsonLite.dig(body, it)) }
            .flatten()

        val raw = if (buckets.isNotEmpty()) {
            buckets
        } else {
            // 兜底：上游改版成统一列表，或 data 本身就是数组
            listOf("list", "albums", "info")
                .firstNotNullOfOrNull { JsonLite.asArray(JsonLite.dig(body, it)) }
                ?: JsonLite.asArray(body)
                ?: return emptyList()
        }

        val albums = raw.mapNotNull { item -> parseAlbumBrief(item) }
        Diag.info(TAG, "新碟上架 → ${albums.size} 张")
        return albums
    }

    /**
     * 解析私人 FM（`/personal/fm`）。
     *
     * ## 结构（实测）
     *
     * ```json
     * { "status":1, "data": { "cur_mark":"…", "song_list":[ … ] } }
     * ```
     *
     * 两个产出：
     * 1. [FmBatch.curMark] —— **必须原样带回下一轮请求**，否则推荐流会从头开始
     *    （表现为来回推同几首歌）；
     * 2. [FmBatch.songs] —— 本批 5 首左右，每首的 [Song.curMark] 也写成同一个
     *    游标，方便反馈接口（`action=garbage` 等）直接取用。
     *
     * 解析失败时返回**空批次 + 空游标**，而不是 null：调用方（`PersonalFm`）
     * 只需判 `songs.isEmpty()`，少一层空判断。
     */
    fun parseFm(json: Any?): FmBatch {
        val body = JsonLite.dig(json, "data")
        val curMark = JsonLite.asString(JsonLite.dig(body, "cur_mark"))
            ?: JsonLite.asString(JsonLite.dig(json, "cur_mark"))
            ?: ""

        val raw = JsonLite.asArray(JsonLite.dig(body, "song_list"))
            ?: listOf("songs", "list", "info")
                .firstNotNullOfOrNull { JsonLite.asArray(JsonLite.dig(body, it)) }
            ?: emptyList()

        val songs = parseSongs(raw).map { it.copy(curMark = curMark) }
        Diag.info(TAG, "私人 FM → ${songs.size} 首，cur_mark=${curMark.take(8)}")
        return FmBatch(songs = songs, curMark = curMark)
    }

    // ------------------------------------------------------------ 网络请求

    /**
     * 每日推荐（`/everyday/recommend`，30 首）。
     *
     * 需要登录态：未登录时上游返回 `errcode 20028`，[KugouApi.lastNeedsLogin]
     * 会被置位，界面据此显示「去登录」。
     */
    fun dailyRecommend(): List<Song> {
        val json = KugouApi.getRaw("/everyday/recommend") ?: return emptyList()
        return parseSongList(json, "song_list")
    }

    /** 刷歌推荐（`/recommend/songs`）。 */
    fun refreshRecommend(): List<Song> {
        val json = KugouApi.getRaw("/recommend/songs") ?: return emptyList()
        return parseSongList(json, "song_list")
    }

    /**
     * 刷歌推荐流（`/home/discover`）。
     *
     * 首屏只要 4 条，`recall_type=song` 表示只召回歌曲（不混 MV/专辑）。
     */
    fun homeDiscover(): List<Song> {
        val json = KugouApi.getRaw(
            "/home/discover",
            mapOf(
                "pagesize" to HOME_DISCOVER_PAGE_SIZE,
                "recall_type" to "song",
            ),
        ) ?: return emptyList()
        return parseHomeDiscover(json)
    }

    /** 新歌速递（`/top/song`，按 `rank_id=21608` 取第一页）。 */
    fun newSongs(): List<Song> {
        val json = KugouApi.getRaw(
            "/top/song",
            mapOf(
                "page" to "1",
                "pagesize" to "30",
                "type" to TOP_SONG_RANK_ID,
            ),
        ) ?: return emptyList()
        return parseTopSong(json)
    }

    /** 新碟上架（`/top/album`，四个语种桶各 3 张）。 */
    fun newAlbums(): List<AlbumBrief> {
        val json = KugouApi.getRaw(
            "/top/album",
            mapOf("page" to "1", "pagesize" to "30"),
        ) ?: return emptyList()
        return parseTopAlbum(json)
    }

    /**
     * 私人 FM 取下一批。
     *
     * @param stationIndex 档位下标（见 [fmStations]），越界时按 0 处理
     * @param curMark 上一批返回的游标；为空表示**新会话**，不带该参数
     */
    fun personalFm(
        stationIndex: Int,
        cursorHash: String? = null,
        cursorSongId: String? = null,
    ): FmBatch {
        val station = fmStations.getOrElse(stationIndex) { fmStations[0] }
        val params = buildFmParams(
            mode = station.mode,
            songPoolId = station.songPoolId,
            cursorHash = cursorHash,
            cursorSongId = cursorSongId,
        )
        val json = KugouApi.getRaw("/personal/fm", params) ?: return FmBatch(emptyList(), "")
        return parseFm(json)
    }

    /**
     * 构造 `/personal/fm` 的请求参数。
     *
     * ## 续流游标是 hash + songid，不是 cur_mark（2026-11-15 真机定位）
     *
     * 早期用 `cur_mark` 当分页游标，但上游每次都回同一个 `99999998`：
     *
     *     21:21:22 私人 FM → 5 首，cur_mark=99999998  → 追加 5 首
     *     21:21:42 私人 FM → 5 首，cur_mark=99999998  → 本批全听过，停止续拉
     *
     * 于是「无限续播」只能成功一次。对照 MD3Music 参考实现
     * （`personal_fm_core.dart` 的 `_fetch` → `getPersonalFm(hash, songId, action)`）：
     * 续流靠**上一首的 hash + songid** 告诉上游「从这首之后接着推」。
     * `cur_mark` 是**每首歌的反馈令牌**（play/garbage 上报用），不是分页游标。
     */
    internal fun buildFmParams(
        mode: String,
        songPoolId: Int,
        cursorHash: String?,
        cursorSongId: String?,
    ): Map<String, String> = buildMap {
        put("mode", mode)
        put("song_pool_id", songPoolId.toString())
        put("action", "play")
        // 空值不能混进 query：上游按「有 hash 就续流」处理，空串会续一个不存在的流。
        cursorHash?.takeIf { it.isNotBlank() }?.let { put("hash", it) }
        cursorSongId?.takeIf { it.isNotBlank() }?.let { put("songid", it) }
    }

    // ------------------------------------------------------------ 内部

    /**
     * FM 档位表（与 MD3Music `kFmStations` 一致，**下标不可变动**）。
     *
     * 配置里按下标持久化，所以顺序就是协议的一部分。
     * `internal` 是给 `PersonalFm` 用的：它在这张表之上加中文名，
     * 而不是再抄一份 mode/pool 数值。
     */
    internal val fmStations: List<FmStation> = listOf(
        FmStation(mode = "normal", songPoolId = 0), // 0 红心
        FmStation(mode = "normal", songPoolId = 2), // 1 探索
        FmStation(mode = "small", songPoolId = 1),  // 2 小众
        FmStation(mode = "radio", songPoolId = 0),  // 3 电台
    )

    /** 档位在网络层的两个参数。 */
    internal data class FmStation(val mode: String, val songPoolId: Int)

    /** 批量解析歌曲数组；单条失败只跳过那一条。 */
    private fun parseSongs(raw: List<Any?>): List<Song> =
        raw.mapNotNull { KugouApi.parseSongForTest(it) }

    /**
     * 解析一条专辑。
     *
     * 字段兼容链（`/top/album` 实测用第一组命名，其余为兜底）：
     * - id：`albumid` / `album_id` / `id`
     * - 名称：`albumname` / `album_name` / `name`
     * - 歌手：`singername` / `author_name` / `singer_name`
     * - 封面：`imgurl` / `cover_url` / `img` / `sizable_cover`
     *
     * 封面**保留 `{size}` 占位**，由调用方按展示尺寸替换。
     */
    private fun parseAlbumBrief(item: Any?): AlbumBrief? {
        val obj = JsonLite.asObject(item) ?: return null
        val id = firstString(obj, "albumid", "album_id", "AlbumID", "id") ?: return null
        val name = firstString(obj, "albumname", "album_name", "AlbumName", "name")
            ?: return null
        return AlbumBrief(
            id = id,
            name = name,
            artist = firstString(obj, "singername", "author_name", "singer_name") ?: "未知歌手",
            coverUrl = firstString(obj, "imgurl", "cover_url", "img", "sizable_cover"),
        )
    }

    /** 按顺序取第一个非空字符串字段。 */
    private fun firstString(obj: Map<String, Any?>, vararg keys: String): String? =
        keys.firstNotNullOfOrNull { JsonLite.asString(obj[it])?.takeIf { s -> s.isNotBlank() } }
}

/**
 * 一批私人 FM 结果。
 *
 * @param songs 本批曲目（通常 5 首）
 * @param curMark 续流游标，下一轮请求原样带上；空串表示服务端没给
 */
data class FmBatch(val songs: List<Song>, val curMark: String)
