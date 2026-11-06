package cn.spw.kugou.lite.sidecar

import cn.spw.kugou.lite.diag.Diag
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/**
 * 酷狗边车的 HTTP 客户端。
 *
 * ## 接口实测结论（2026-11，KuGouMusicApi v1.6.3）
 *
 * | 能力 | 路径 | 关键点 |
 * |---|---|---|
 * | 搜索 | `/search?keywords=&page=&pagesize=` | 返回 `data.lists[]` |
 * | 取流 | `/song/url?hash=` | **必须先登录**，否则 `errcode 20028` |
 * | 搜歌词 | `/search/lyric?hash=` | 拿 `candidates[].id/accesskey` |
 * | 取歌词 | `/lyric?id=&accesskey=&fmt=lrc&decode=true` | 两步，不能直接用 hash |
 * | 用户歌单 | `/user/playlist` | 需登录 |
 * | 榜单列表 | `/rank/list` | 无需登录 |
 *
 * ## 取流为什么要带 token
 *
 * 不登录时 `/song/url` 一律返回 `errcode 20028`「本次请求需要验证」——
 * 这是服务端的行为指纹反爬。带上登录 cookie 后该错误消失，
 * 免费曲目可直接拿到 CDN 直链，付费曲目返回 `fail_process: ["pkg","buy"]`
 * 且无 url（属正常版权限制，需酷狗会员）。
 *
 * ## 线程模型
 *
 * 所有方法都是**同步阻塞**的，必须放在 [cn.spw.kugou.lite.sidecar.ApiExec] 的
 * 后台线程里调用，绝不能在 Compose 组合期调用（会卡住渲染甚至触发宿主 ANR）。
 */
object KugouApi {

    private const val TAG = "api"
    private const val CONNECT_TIMEOUT_MS = 6_000
    private const val READ_TIMEOUT_MS = 12_000

    /**
     * 收藏页 page_id —— 解锁「已收藏但无版权」曲目用。
     *
     * 酷狗约定：歌曲被收藏后，即便无版权也可通过收藏页的 page_id 解锁播放。
     * 参考 md3Music `kugou_api_client.dart` 与 EchoMusic `resolver.ts`。
     */
    private const val COLLECT_PAGE_ID = "356753938"

    /** 最近一次调用是否因未登录被拒（20028）。UI 据此提示去登录。 */
    @Volatile
    var lastNeedsLogin: Boolean = false
        private set

    @Volatile
    var lastError: String? = null
        private set

    // ---------------------------------------------------------------- 搜索

    fun search(keyword: String, page: Int = 1, pageSize: Int = 30): SearchResult? {
        if (keyword.isBlank()) return SearchResult(emptyList(), 0)
        val json = get(
            "/search",
            mapOf(
                "keywords" to keyword,
                "page" to page.toString(),
                "pagesize" to pageSize.toString(),
            ),
        ) ?: return null

        val lists = JsonLite.dig(json, "data", "lists")
        val songs = JsonLite.asArray(lists).orEmpty().mapNotNull { parseSong(it) }
        val total = JsonLite.asInt(JsonLite.dig(json, "data", "total"))
        Diag.info(TAG, "搜索「$keyword」→ ${songs.size} 条")
        return SearchResult(songs, if (total > 0) total else songs.size)
    }

    // ---------------------------------------------------------------- 取流

    /**
     * 取播放地址（按设置里的音质偏好，自动降级）。
     *
     * ## 两轮 + 音质降级链
     *
     * 对每个音质档位（从高到低）：
     * 1. 常规取流
     * 2. 失败则用 `ppage_id=356753938`（收藏页）解锁已收藏的无版权曲目
     *
     * 任一档位拿到地址就返回。全部失败才返回 null。
     *
     * 非会员请求 flac/320 时服务端常返回 128k 或直接失败，
     * 降级链保证「至少能出声」，而不是直接报错。
     *
     * @param song 要播放的歌曲
     * @return 成功返回 [StreamInfo]；付费/无权限/未登录返回 null
     */
    fun streamUrl(song: Song): StreamInfo? {
        val query = song.streamQuery
        if (query.isEmpty()) {
            lastError = "该歌曲缺少取流标识"
            return null
        }

        val chain = Settings.resolveQualityChain()
        for (quality in chain) {
            // 第一轮：常规取流
            streamUrlByQuery(query, quality, pageId = null)?.let {
                Diag.info(TAG, "取流成功「${song.title}」请求=${quality} 实际=${it.qualityText}")
                return it
            }

            // 第二轮：收藏页解锁（针对受限曲目）
            streamUrlByQuery(query, quality, pageId = COLLECT_PAGE_ID)?.let {
                Diag.info(TAG, "收藏页解锁成功「${song.title}」请求=${quality} 实际=${it.qualityText}")
                return it
            }

            if (lastNeedsLogin) return null // 登录问题，降级也没用
        }

        return null
    }

    /**
     * 按指定音质取流（保留给需要精确控制的调用方）。
     *
     * @param quality 形如 `128` / `320` / `flac`
     */
    fun streamUrl(song: Song, quality: String): StreamInfo? {
        val query = song.streamQuery
        if (query.isEmpty()) {
            lastError = "该歌曲缺少取流标识"
            return null
        }
        streamUrlByQuery(query, quality, pageId = null)?.let { return it }
        return streamUrlByQuery(query, quality, pageId = COLLECT_PAGE_ID)
    }

    /** 按 hash 取流（保留给只有 hash 的调用方）。 */
    fun streamUrl(hash: String, quality: String = "128"): StreamInfo? {
        streamUrlByQuery("hash=$hash", quality, pageId = null)?.let { return it }
        return streamUrlByQuery("hash=$hash", quality, pageId = COLLECT_PAGE_ID)
    }

    private fun streamUrlByQuery(streamQuery: String, quality: String, pageId: String?): StreamInfo? {
        val params = parseQueryString(streamQuery).toMutableMap()
        params["quality"] = quality
        pageId?.let { params["ppage_id"] = it }

        val json = get("/song/url", params) ?: return null

        val url = JsonLite.asString(JsonLite.dig(json, "url"))
            ?: JsonLite.asArray(JsonLite.dig(json, "url")).orEmpty()
                .firstNotNullOfOrNull { JsonLite.asString(it) }
        if (url.isNullOrBlank()) {
            val fail = JsonLite.asArray(JsonLite.dig(json, "fail_process")).orEmpty()
                .mapNotNull { JsonLite.asString(it) }
            val status = JsonLite.asInt(JsonLite.dig(json, "status"))
            lastError = when {
                fail.contains("buy") || fail.contains("pkg") -> "该曲目需要酷狗会员"
                // status=3 + privilege=5 是版权/地区限制，不是会员问题
                status == 3 -> "该曲目受版权限制（可尝试收藏后再播放）"
                else -> "无可用地址"
            }
            Diag.info(TAG, "取流无地址 $streamQuery status=$status fail=$fail")
            return null
        }

        val backups = JsonLite.asArray(JsonLite.dig(json, "backupUrl")).orEmpty()
            .mapNotNull { JsonLite.asString(it) }
            .filter { it.startsWith("http") }

        lastError = null
        return StreamInfo(
            url = url,
            backupUrls = backups,
            bitRate = JsonLite.asInt(JsonLite.dig(json, "bitRate")),
            fileSize = JsonLite.asLong(JsonLite.dig(json, "fileSize")),
            extName = JsonLite.asString(JsonLite.dig(json, "extName")) ?: "mp3",
            durationSec = JsonLite.asInt(JsonLite.dig(json, "timeLength")),
        )
    }

    /** 把 `a=1&b=2` 形式的查询串拆成 Map，便于和其他参数合并。 */
    private fun parseQueryString(query: String): Map<String, String> =
        query.split('&').mapNotNull { part ->
            val i = part.indexOf('=')
            if (i <= 0) null else part.substring(0, i) to part.substring(i + 1)
        }.toMap()

    // ---------------------------------------------------------------- 歌词

    /** 第一步：按 hash 搜歌词候选。 */
    fun searchLyric(hash: String): List<LyricCandidate> {
        val json = get("/search/lyric", mapOf("hash" to hash)) ?: return emptyList()
        val candidates = JsonLite.dig(json, "candidates")
        return JsonLite.asArray(candidates).orEmpty().mapNotNull { item ->
            val obj = JsonLite.asObject(item) ?: return@mapNotNull null
            val id = JsonLite.asString(obj["id"]) ?: return@mapNotNull null
            val key = JsonLite.asString(obj["accesskey"]) ?: return@mapNotNull null
            LyricCandidate(
                id = id,
                accessKey = key,
                song = JsonLite.asString(obj["song"]) ?: "",
                singer = JsonLite.asString(obj["singer"]) ?: "",
                durationMs = JsonLite.asLong(obj["duration"]),
            )
        }
    }

    /** 第二步：按候选 id + accesskey 取解码后的歌词文本。 */
    fun lyric(candidate: LyricCandidate, fmt: String = "krc"): String? {
        val json = get(
            "/lyric",
            mapOf(
                "id" to candidate.id,
                "accesskey" to candidate.accessKey,
                "fmt" to fmt,
                "decode" to "true",
            ),
        ) ?: return null
        return JsonLite.asString(JsonLite.dig(json, "decodeContent"))
            ?: JsonLite.asString(JsonLite.dig(json, "content"))
    }

    /**
     * 一步取歌词：内部串起两步。
     *
     * 优先取 **KRC（逐字）**，失败再退回 LRC（逐行）。
     *
     * ## 为什么优先 KRC
     *
     * 酷狗歌词接口的 `fmt` 参数决定格式（实测同一首歌）：
     *
     * | fmt | 大小 | 格式 |
     * |---|---|---|
     * | `lrc` | 811 B | 逐行 `[00:03.75]愛舐め上手芳しき` |
     * | `krc` | 7744 B | **逐字** `[191,3515]<0,836,0>黑<836,493,0>縁...` |
     *
     * KRC 的逐字格式：
     * ```
     * [行起始ms,行时长ms]<字相对偏移ms,字时长ms,0>字<...>字...
     * ```
     *
     * 宿主内部的歌词模型（`com.xuncorp.spc.lyrics.LyricsLine` /
     * `LyricsCell(startTime=...)`）本身就是逐字结构，所以喂 KRC 才能
     * 逐字高亮；喂 LRC 只能整行高亮。
     */
    fun lyricByHash(hash: String): String? {
        val best = searchLyric(hash).firstOrNull() ?: return null
        // 先试 KRC（逐字）
        lyric(best, fmt = "krc")?.takeIf { it.isNotBlank() }?.let { return it }
        // 退回 LRC（逐行）
        return lyric(best, fmt = "lrc")
    }

    // ---------------------------------------------------------------- 歌手

    /**
     * 歌手的单曲列表。
     *
     * ## 返回结构（实测 `/artist/audios`）
     *
     * `data` 是**数组**（不是常见的 `data.songs`），每项字段与榜单歌曲类似：
     * `hash` / `audio_id` / `album_id` / `trans_param.union_cover` 等。
     *
     * @param artistId 酷狗内部 author_id（搜索结果 `Singers[].id`）
     * @param sort `hot` = 按热度，其它值按时间
     */
    fun artistSongs(artistId: String, page: Int = 1, pageSize: Int = 30, sort: String = "hot"): List<Song> {
        if (artistId.isBlank()) return emptyList()
        val json = get(
            "/artist/audios",
            mapOf(
                "id" to artistId,
                "page" to page.toString(),
                "pagesize" to pageSize.toString(),
                "sort" to sort,
            ),
        ) ?: return emptyList()

        // data 可能是数组，也可能包在 songs/info 里
        val list = JsonLite.asArray(JsonLite.dig(json, "data"))
            ?: JsonLite.asArray(JsonLite.dig(json, "data", "songs"))
            ?: JsonLite.asArray(JsonLite.dig(json, "data", "info"))
            ?: return emptyList()

        val songs = list.mapNotNull { parseSong(it) }
        Diag.info(TAG, "歌手 $artistId → ${songs.size} 首")
        return songs
    }

    // ---------------------------------------------------------------- 歌单 / 榜单 / 推荐

    /**
     * 我的歌单列表。
     *
     * ## 字段名（实测 `/user/playlist` 的 `data.info[]`）
     *
     * ```
     * listid = 1              ← 歌单 id（不是 specialid）
     * name   = 默认收藏        ← 名称（不是 specialname）
     * count  = 5              ← **曲目数（不是 songcount）**
     * m_count= 5              ← 曲目数副本
     * pic    = http://...     ← 封面（不是 img）
     * intro  = ""             ← 简介
     * ```
     *
     * 早期实现读 `songcount` / `specialid` / `specialname` / `img`，
     * 这些都是**另一个接口**（`/user/playlist` 的旧版或移动端变体）的字段名，
     * 在这个接口里全都不存在 —— 结果歌单能列出来但曲目数恒为 0。
     * 现在每个字段都用 [firstString] / [firstInt] 按优先级探测，两种命名都兼容。
     */
    fun userPlaylists(): List<Playlist> {
        val json = get("/user/playlist", emptyMap()) ?: return emptyList()
        val info = JsonLite.dig(json, "data", "info")
        val list = JsonLite.asArray(info).orEmpty().mapNotNull { item ->
            val obj = JsonLite.asObject(item) ?: return@mapNotNull null
            // **必须优先取 global_collection_id**：歌单内的歌曲接口
            // （`/playlist/track/all`）只认它，传 listid 会返回空。
            val id = firstString(obj, "global_collection_id", "specialid", "listid", "listId")
                ?: return@mapNotNull null
            Playlist(
                id = id,
                name = firstString(obj, "specialname", "name", "listname") ?: "未命名歌单",
                coverUrl = firstString(obj, "img", "pic", "cover"),
                // 曲目数：count / m_count 是本接口的字段；songcount 是旧版字段，留着兜底
                songCount = firstInt(obj, "count", "m_count", "songcount", "song_count"),
                playCount = firstLong(obj, "playcount", "play_count"),
                description = firstString(obj, "intro", "description"),
            )
        }
        Diag.info(TAG, "我的歌单 → ${list.size} 个")
        return list
    }

    /**
     * 歌单内的歌曲。
     *
     * ## 参数：必须传 `global_collection_id`，不能用 listid
     *
     * `/user/playlist` 返回的 `listid`（如 `1`）**不能**直接用于本接口 ——
     * 实测传 `listid=1` 返回 `status:0`（无数据）。必须用
     * `global_collection_id`（形如 `collection_3_1072630322_1_0`）。
     *
     * ## 返回结构
     *
     * `data.songs[]`，每项字段：`hash` / `name` / `audio_id` / `album_id` /
     * `add_mixsongid` / `size` / `extname` / `mvhash` 等。
     * 注意歌名是 **`name`**，不是 `songname` 也不是 `filename` ——
     * [parseSong] 已覆盖。
     */
    fun playlistSongs(globalCollectionId: String, page: Int = 1, pageSize: Int = 50): List<Song> {
        if (globalCollectionId.isBlank()) return emptyList()
        val json = get(
            "/playlist/track/all",
            mapOf(
                "id" to globalCollectionId,
                "page" to page.toString(),
                "pagesize" to pageSize.toString(),
            ),
        ) ?: return emptyList()

        val list = JsonLite.asArray(JsonLite.dig(json, "data", "songs"))
            ?: JsonLite.asArray(JsonLite.dig(json, "data", "info"))
            ?: return emptyList()

        val songs = list.mapNotNull { parseSong(it) }
        Diag.info(TAG, "歌单 $globalCollectionId → ${songs.size} 首")
        return songs
    }

    /**
     * 榜单列表。
     *
     * 实测响应：`data.info[]`，每项含 `rankid` / `rankname` / `imgurl` / `songcount`。
     * 少数字段可能缺失，全部走容错取值。
     */
    fun ranks(): List<Rank> {
        val json = get("/rank/list", emptyMap()) ?: return emptyList()
        val info = JsonLite.dig(json, "data", "info")
        val ranks = JsonLite.asArray(info).orEmpty().mapNotNull { item ->
            val obj = JsonLite.asObject(item) ?: return@mapNotNull null
            val id = firstString(obj, "rankid", "rankId", "id") ?: return@mapNotNull null
            Rank(
                id = id,
                name = firstString(obj, "rankname", "rankName", "name") ?: "未命名榜单",
                coverUrl = firstString(obj, "imgurl", "bannerurl", "img", "cover"),
                songCount = firstInt(obj, "songcount", "songCount", "song_num"),
            )
        }
        Diag.info(TAG, "榜单列表 → ${ranks.size} 个")
        return ranks
    }

    /**
     * 榜单内的歌曲列表。
     *
     * ## 响应结构（实测）
     *
     * 歌曲数组可能在 `data.songlist` / `data.list` / `data.songs` 之一 ——
     * 上游 `rank.rs` 里有同款探测逻辑，这里保持一致的顺序。
     * 早期实现只读 `data.info`（那是**榜单列表**用的字段，不是歌曲），
     * 所以永远拿到空列表，导致「排行榜内列表无法点击」。
     */
    fun rankSongs(rankId: String, page: Int = 1, pageSize: Int = 50): List<Song> {
        val json = get(
            "/rank/audio",
            mapOf("rankid" to rankId, "page" to page.toString(), "pagesize" to pageSize.toString()),
        ) ?: return emptyList()

        val candidates = listOf("songlist", "list", "songs", "info")
        val list = candidates.firstNotNullOfOrNull { JsonLite.asArray(JsonLite.dig(json, "data", it)) }
            ?: return emptyList()

        val songs = list.mapNotNull { parseSong(it) }
        Diag.info(TAG, "榜单 $rankId → ${songs.size} 首")
        return songs
    }

    /**
     * 每日推荐。
     *
     * ## 响应结构（实测 Rust 版 `/everyday/recommend`）
     *
     * ```
     * { "status":1, "data": { "song_list": [ ... 30 首 ... ] } }
     * ```
     *
     * 注意 `song_list` 里的歌曲字段是 **snake_case**，与搜索结果的 PascalCase 不同：
     * `filename` / `songname` / `hash` / `timelen` / `pay_type` / `album_id`。
     * [parseSong] 两种命名都兼容。
     *
     * 早期实现读的是 `data.songs` 与 `data.info`，两个都不存在，
     * 于是永远返回空列表 —— 这正是「每日推荐无法获取」的原因。
     */
    fun dailyRecommend(): List<Song> {
        val json = get("/everyday/recommend", emptyMap()) ?: return emptyList()

        // 三种可能的容器名，按优先级探测，兼容不同上游版本。
        val candidates = listOf("song_list", "songs", "info")
        val list = candidates.firstNotNullOfOrNull { JsonLite.asArray(JsonLite.dig(json, "data", it)) }
            ?: return emptyList()

        val songs = list.mapNotNull { parseSong(it) }
        Diag.info(TAG, "每日推荐 → ${songs.size} 首")
        return songs
    }

    // ---------------------------------------------------------------- 登录

    data class QrKey(val key: String, val imageBase64: String)

    fun qrKey(): QrKey? {
        // 加随机参数绕过服务端 2 分钟缓存 —— 否则会反复拿到同一个已过期的 key。
        val json = get("/login/qr/key", mapOf("_t" to System.nanoTime().toString())) ?: return null
        val key = JsonLite.asString(JsonLite.dig(json, "data", "qrcode")) ?: return null
        val img = JsonLite.asString(JsonLite.dig(json, "data", "qrcode_img")).orEmpty()
        return QrKey(key, img)
    }

    /**
     * 轮询扫码状态。
     *
     * @return `status`：0 过期 / 1 等待扫码 / 2 待确认 / 4 成功。
     *         成功时同时返回 token、userid、nickname。
     */
    data class QrStatus(
        val status: Int,
        val token: String?,
        val userId: String?,
        val nickname: String?,
    )

    fun qrCheck(key: String): QrStatus? {
        val json = get("/login/qr/check", mapOf("key" to key)) ?: return null
        val data = JsonLite.dig(json, "data")
        return QrStatus(
            status = JsonLite.asInt(JsonLite.dig(data, "status")),
            token = JsonLite.asString(JsonLite.dig(data, "token")),
            userId = JsonLite.asString(JsonLite.dig(data, "userid")),
            nickname = JsonLite.asString(JsonLite.dig(data, "nickname")),
        )
    }

    // ---------------------------------------------------------------- 底层

    /**
     * 把任意接口返回的歌曲对象解析成 [Song]。
     *
     * ## 为什么字段名有这么多种
     *
     * 酷狗不同接口返回的歌曲字段命名**不统一**（实测）：
     *
     * | 接口 | 命名风格 | 歌名字段 | 取流键 |
     * |---|---|---|---|
     * | `/search` | PascalCase | `OriSongName` | `FileHash` |
     * | `/everyday/recommend` | snake_case | `songname` | `hash` |
     * | `/rank/audio` | snake_case | `songname` | **无 hash**，用 `album_audio_id` |
     *
     * 所以这里按「先 PascalCase、后 snake_case」的顺序逐个探测。
     * **不要**只按一种命名写死 —— 那会导致某些列表永远为空。
     *
     * ## 榜单歌曲的特殊之处
     *
     * `/rank/audio` 的歌曲对象**根本没有 hash 字段**，只有：
     * `audio_id` / `album_audio_id` / `album_id`。实测
     * `GET /song/url?album_audio_id=967249675` 能直接拿到 CDN 直链，
     * 所以 hash 为空时保留 `albumAudioId` 作为替代取流键。
     * hash 和 albumAudioId 都没有的条目直接丢弃（无法播放，不该出现在列表里）。
     */
    private fun parseSong(item: Any?): Song? {
        val obj = JsonLite.asObject(item) ?: return null

        val hash = firstString(obj, "FileHash", "hash", "File_hash", "filehash").orEmpty()
        val albumAudioId = firstString(
            obj,
            "album_audio_id",
            "albumAudioId",
            "album_audioid",
            "mixsongid",
            "MixSongID",
            "EMixSongID",
        ).orEmpty()

        // 两个取流键都没有 => 这首歌点不了，不要放进列表。
        if (hash.isBlank() && albumAudioId.isBlank()) return null

        val rawName = firstString(
            obj, "FileName", "filename", "ori_audio_name", "OriSongName", "name", "audio_name",
        ) ?: "未知曲目"

        // 歌名字段因接口而异：
        //   /search             OriSongName（纯歌名）
        //   /everyday/recommend songname（纯歌名）
        //   /playlist/track/all **name**，格式是「歌手 - 歌名」
        //   /artist/audios      **audio_name**（纯歌名）+ author_name（歌手）
        //
        // 所以对 name 这种复合格式，要拆出歌名部分；同时下面 singer 也会
        // 从 name 里拆 —— 歌单接口**没有单独的歌手字段**。
        val composite = firstString(obj, "name")?.takeIf { it.contains(" - ") }

        val title = firstString(
            obj, "OriSongName", "songname", "SongName", "official_songname", "audio_name",
        )
            ?: composite?.substringAfter(" - ")?.trim()?.takeIf { it.isNotBlank() }
            ?: rawName

        val fileName = if (rawName == title && composite == null) {
            // 没有「歌手 - 歌名」结构时，用 title 兜底
            title
        } else {
            rawName
        }

        val singer = firstString(obj, "SingerName", "singername", "singer_name", "author_name")
            ?: JsonLite.asArray(obj["Singers"]).orEmpty()
                .mapNotNull { JsonLite.asString(JsonLite.asObject(it)?.get("name")) }
                .joinToString("、")
                .ifBlank {
                    JsonLite.asArray(obj["authors"]).orEmpty()
                        .mapNotNull { JsonLite.asString(JsonLite.asObject(it)?.get("author_name")) }
                        .joinToString("、")
                }
                // 歌单接口：从「歌手 - 歌名」里拆歌手
                .ifBlank { composite?.substringBefore(" - ")?.trim().orEmpty() }
                .ifBlank { "未知歌手" }

        val size = firstLong(obj, "FileSize", "filesize", "file_size", "size")

        // 歌手 ID：搜索接口在 Singers[].id，也可能是 SingerId 数组或单值
        val artistIds = buildList {
            JsonLite.asArray(obj["Singers"]).orEmpty().forEach { item ->
                JsonLite.asString(JsonLite.asObject(item)?.get("id"))
                    ?.takeIf { it.isNotBlank() }
                    ?.let { add(it) }
            }
            if (isEmpty()) {
                JsonLite.asArray(obj["SingerId"]).orEmpty().forEach { item ->
                    JsonLite.asString(item)?.takeIf { it.isNotBlank() }?.let { add(it) }
                }
            }
            if (isEmpty()) {
                firstString(obj, "SingerId", "author_id")?.let { add(it) }
            }
        }

        return Song(
            fileName = fileName,
            title = title,
            singer = singer,
            hash = hash,
            durationSec = parseDurationSec(obj, size),
            imageUrl = firstString(obj, "Image", "img", "album_image", "transparent_url")
                ?: JsonLite.asString(JsonLite.dig(obj["trans_param"], "union_cover")),
            fileSize = size,
            payType = firstInt(obj, "PayType", "pay_type"),
            albumName = firstString(obj, "AlbumName", "album_name", "remark"),
            albumAudioId = albumAudioId,
            artistIds = artistIds,
        )
    }

    /**
     * 解析时长，统一成**秒**。
     *
     * ## 单位不一致（实测）
     *
     * | 接口 | 字段 | 单位 | 样例 |
     * |---|---|---|---|
     * | `/search` | `Duration` | 秒 | 299 |
     * | `/playlist/track/all` | `timelen` | **毫秒** | 270027 |
     *
     * ## 为什么不能只靠量级判断
     *
     * 最初用「> 100000 视为毫秒」，但歌单里有大量**几十秒的片段**
     * （如「青柠 (36秒片段)」`timelen=36101`），36101 < 100000 会被
     * 误判成 36101 秒 → 渲染出 `601:41` 这种荒谬时长。
     *
     * 现在用**文件大小交叉校验**：mp3 的码率下限约 32 kbps，
     * 所以「秒数 × 4000 字节/秒」是保守上界。若换算出的秒数对应的大小
     * 远超实际文件，就说明这个数值是毫秒。
     *
     * @param fileSize 同一对象里的 `size` 字段（字节），没有则传 0
     */
    private fun parseDurationSec(obj: Map<String, Any?>, fileSize: Long): Int {
        val raw = firstInt(obj, "Duration", "duration", "timelen", "timeLength")
        if (raw <= 0) return 0

        // 有文件大小时用它交叉校验，这是最可靠的判据
        if (fileSize > 0) {
            // 128kbps ≈ 16000 B/s；用 4000 B/s 作下限（≈32kbps），足够宽松
            val maxSecIfUnitIsSec = fileSize / 4000
            if (raw > maxSecIfUnitIsSec) return raw / 1000
            return raw
        }

        // 没有文件大小：退回量级判断（100000 秒 ≈ 27 小时）
        return if (raw > 100_000) raw / 1000 else raw
    }

    /** 按顺序取第一个非空字符串字段。 */
    private fun firstString(obj: Map<String, Any?>, vararg keys: String): String? =
        keys.firstNotNullOfOrNull { JsonLite.asString(obj[it])?.takeIf { s -> s.isNotBlank() } }

    private fun firstInt(obj: Map<String, Any?>, vararg keys: String): Int =
        keys.firstNotNullOfOrNull { k -> obj[k]?.let { JsonLite.asInt(it) } } ?: 0

    private fun firstLong(obj: Map<String, Any?>, vararg keys: String): Long =
        keys.firstNotNullOfOrNull { k -> obj[k]?.let { JsonLite.asLong(it) } } ?: 0L

    private fun get(path: String, params: Map<String, String>): Any? {
        val http = SidecarManager.baseUrl
        if (http == null) {
            lastError = "在线服务未启动"
            Diag.warn(TAG, "边车未就绪，跳过 $path")
            return null
        }

        val query = buildString {
            append(params.entries.joinToString("&") { (k, v) ->
                "${enc(k)}=${enc(v)}"
            })
            AuthStore.token?.let { if (it.isNotBlank()) append("&token=${enc(it)}") }
            AuthStore.userId?.let { if (it.isNotBlank()) append("&userid=${enc(it)}") }
        }
        val full = "$http$path" + if (query.isBlank()) "" else "?$query"

        var conn: HttpURLConnection? = null
        return try {
            conn = (URI(full).toURL().openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("User-Agent", "KugouLite-SPW")
                // 登录态同时走 cookie：部分接口只认 cookie 不认 query。
                val cookie = buildString {
                    AuthStore.token?.let { append("token=$it; ") }
                    AuthStore.userId?.let { append("userid=$it; ") }
                }
                if (cookie.isNotBlank()) setRequestProperty("Cookie", cookie.trim())
            }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""

            // 登录/验证失败的判定。
            //
            // - `20028`：本次请求需要验证（行为指纹反爬，未登录时取流就是它）
            // - `20017` / `20018`：**登录态失效**（token 过期或被踢）。
            //   实测 `/user/playlist` 与 `/user/detail` 在 token 过期时返回
            //   `{"status":0,"error_code":20017}` 且 HTTP 502。
            //
            // 早期只判 20028，导致 token 过期时 UI 显示「暂无歌单」，
            // 用户会以为账号里真的没有歌单。现在三种错误码都归为「需要登录」。
            val needsLogin = body.contains("\"errcode\":20028") ||
                body.contains("本次请求需要验证") ||
                body.contains("\"error_code\":20017") ||
                body.contains("\"error_code\":20018")
            if (needsLogin) {
                lastNeedsLogin = true
                lastError = "登录已失效，请重新扫码登录"
                Diag.warn(TAG, "$path 需要重新登录（HTTP $code）")
                return null
            }
            lastNeedsLogin = false

            if (code !in 200..299) {
                lastError = "服务返回 $code"
                Diag.warn(TAG, "$path 返回 $code")
                return null
            }

            if (body.isBlank()) {
                lastError = "空响应"
                return null
            }
            JsonLite.parse(body)
        } catch (e: IOException) {
            lastError = "网络错误：${e.message}"
            Diag.warn(TAG, "$path 网络错误：${e.message}")
            null
        } catch (e: SecurityException) {
            lastError = "网络权限被拒绝"
            Diag.warn(TAG, "$path 权限错误：${e.message}")
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun enc(value: String): String =
        URLEncoder.encode(value, "UTF-8")
}
