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
     * 取播放地址。
     *
     * @return 成功返回 [StreamInfo]；付费/无权限/未登录返回 null，
     *         具体原因看 [lastNeedsLogin] 与 [lastError]。
     */
    fun streamUrl(hash: String, quality: String = "128"): StreamInfo? {
        val json = get("/song/url", mapOf("hash" to hash, "quality" to quality)) ?: return null

        val url = JsonLite.asString(JsonLite.dig(json, "url"))
        if (url.isNullOrBlank()) {
            val fail = JsonLite.asArray(JsonLite.dig(json, "fail_process")).orEmpty()
                .mapNotNull { JsonLite.asString(it) }
            lastError = if (fail.contains("buy") || fail.contains("pkg")) {
                "该曲目需要酷狗会员"
            } else {
                "无可用地址"
            }
            Diag.info(TAG, "取流无地址 hash=$hash fail=$fail")
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

    /** 第二步：按候选 id + accesskey 取解码后的 LRC 文本。 */
    fun lyric(candidate: LyricCandidate): String? {
        val json = get(
            "/lyric",
            mapOf(
                "id" to candidate.id,
                "accesskey" to candidate.accessKey,
                "fmt" to "lrc",
                "decode" to "true",
            ),
        ) ?: return null
        return JsonLite.asString(JsonLite.dig(json, "decodeContent"))
            ?: JsonLite.asString(JsonLite.dig(json, "content"))
    }

    /** 一步取歌词：内部串起两步。 */
    fun lyricByHash(hash: String): String? {
        val best = searchLyric(hash).firstOrNull() ?: return null
        return lyric(best)
    }

    // ---------------------------------------------------------------- 歌单 / 榜单 / 推荐

    fun userPlaylists(): List<Playlist> {
        val json = get("/user/playlist", emptyMap()) ?: return emptyList()
        val info = JsonLite.dig(json, "data", "info")
        return JsonLite.asArray(info).orEmpty().mapNotNull { item ->
            val obj = JsonLite.asObject(item) ?: return@mapNotNull null
            Playlist(
                id = JsonLite.asString(obj["specialid"]) ?: JsonLite.asString(obj["listid"]) ?: return@mapNotNull null,
                name = JsonLite.asString(obj["specialname"]) ?: JsonLite.asString(obj["name"]) ?: "未命名歌单",
                coverUrl = JsonLite.asString(obj["img"]) ?: JsonLite.asString(obj["pic"]),
                songCount = JsonLite.asInt(obj["songcount"]),
                playCount = JsonLite.asLong(obj["playcount"]),
                description = JsonLite.asString(obj["intro"]),
            )
        }
    }

    fun playlistSongs(playlistId: String, page: Int = 1, pageSize: Int = 50): List<Song> {
        val json = get(
            "/playlist/track/all",
            mapOf("id" to playlistId, "page" to page.toString(), "pagesize" to pageSize.toString()),
        ) ?: return emptyList()
        val songs = JsonLite.dig(json, "data", "songs")
        return JsonLite.asArray(songs).orEmpty().mapNotNull { parseSong(it) }
    }

    fun ranks(): List<Rank> {
        val json = get("/rank/list", emptyMap()) ?: return emptyList()
        val info = JsonLite.dig(json, "data", "info")
        return JsonLite.asArray(info).orEmpty().mapNotNull { item ->
            val obj = JsonLite.asObject(item) ?: return@mapNotNull null
            Rank(
                id = JsonLite.asString(obj["rankid"]) ?: return@mapNotNull null,
                name = JsonLite.asString(obj["rankname"]) ?: "未命名榜单",
                coverUrl = JsonLite.asString(obj["imgurl"]) ?: JsonLite.asString(obj["bannerurl"]),
                songCount = JsonLite.asInt(obj["songcount"]),
            )
        }
    }

    fun rankSongs(rankId: String, page: Int = 1, pageSize: Int = 50): List<Song> {
        val json = get(
            "/rank/audio",
            mapOf("rankid" to rankId, "page" to page.toString(), "pagesize" to pageSize.toString()),
        ) ?: return emptyList()
        val songs = JsonLite.dig(json, "data", "info")
        return JsonLite.asArray(songs).orEmpty().mapNotNull { parseSong(it) }
    }

    /** 每日推荐。响应结构在不同版本间有差异，这里两种都试。 */
    fun dailyRecommend(): List<Song> {
        val json = get("/everyday/recommend", emptyMap()) ?: return emptyList()
        val direct = JsonLite.dig(json, "data", "songs")
        val fromInfo = JsonLite.dig(json, "data", "info")
        val list = JsonLite.asArray(direct) ?: JsonLite.asArray(fromInfo) ?: return emptyList()
        return list.mapNotNull { parseSong(it) }
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

    private fun parseSong(item: Any?): Song? {
        val obj = JsonLite.asObject(item) ?: return null
        val hash = JsonLite.asString(obj["FileHash"])
            ?: JsonLite.asString(obj["hash"])
            ?: return null
        if (hash.isBlank()) return null

        val fileName = JsonLite.asString(obj["FileName"])
            ?: JsonLite.asString(obj["filename"])
            ?: JsonLite.asString(obj["OriSongName"])
            ?: "未知曲目"

        val title = JsonLite.asString(obj["OriSongName"])
            ?: JsonLite.asString(obj["songname"])
            ?: JsonLite.asString(obj["SongName"])
            ?: fileName.substringAfter(" - ", fileName)

        val singer = JsonLite.asString(obj["SingerName"])
            ?: JsonLite.asString(obj["singername"])
            ?: JsonLite.asArray(obj["Singers"]).orEmpty()
                .mapNotNull { JsonLite.asString(JsonLite.asObject(it)?.get("name")) }
                .joinToString("、")
                .ifBlank { "未知歌手" }

        return Song(
            fileName = fileName,
            title = title,
            singer = singer,
            hash = hash,
            durationSec = JsonLite.asInt(obj["Duration"] ?: obj["timelen"] ?: obj["timeLength"]),
            imageUrl = JsonLite.asString(obj["Image"]) ?: JsonLite.asString(obj["img"]),
            fileSize = JsonLite.asLong(obj["FileSize"] ?: obj["filesize"]),
            payType = JsonLite.asInt(obj["PayType"] ?: obj["pay_type"]),
            albumName = JsonLite.asString(obj["AlbumName"]) ?: JsonLite.asString(obj["album_name"]),
        )
    }

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

            // 只有**真正的登录/验证失败**才标记 lastNeedsLogin。
            // 早期版本写成 `code == 502 || body.contains("20028")`，把任何 502
            // 网关错误都当成「需要登录」——实测 /rank/list 返回 200 但 body 里
            // 含 20028 字段之外的内容时会被误判，UI 就错误提示用户去登录。
            // 现在严格按 errcode 判断。
            val needsLogin = body.contains("\"errcode\":20028") ||
                body.contains("本次请求需要验证")
            if (needsLogin) {
                lastNeedsLogin = true
                lastError = "需要登录酷狗账号"
                Diag.warn(TAG, "$path 需要登录（$code）")
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
