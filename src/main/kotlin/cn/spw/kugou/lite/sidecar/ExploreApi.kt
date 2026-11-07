package cn.spw.kugou.lite.sidecar

import cn.spw.kugou.lite.diag.Diag
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 探索类接口：播放历史 / 歌手扩展 / 每日推荐历史 / 风格推荐。
 *
 * ## 实测口径（2026-11-07，规格附录字段矩阵）
 *
 * - `/user/history`：`data.songs[]`（每条 `ot` = unix 秒 + `info{…}`），
 *   `data.has_more`。**返回顺序不是时间序**，这里按 `ot` 降序重排后再按
 *   本地日期分组——用户看到的是「今天 / 昨天 / …」而不是乱序。
 *   `info.timelen` 是**毫秒**，转成秒。
 * - `/artist/detail`：`data`（author_name/author_id/sizable_avatar/album_count/
 *   song_count/fansnums/intro/user_status）。
 * - `/artist/albums`：`data[]` + **顶层** `total`。
 * - `/artist/follow|unfollow`：请求成功但 `user_status` 不回变（裁决 R3）→
 *   状态由 UI 层乐观维护，本对象只负责发请求。
 * - `/everyday/history`：`data.lists[]`（date=YYYYMMDD / history_title / song_list[]）。
 * - `/everyday/style/recommend?tagid=`：`data.song_list[]`（time_length 是**秒**）。
 *
 * 歌曲条目字段在 style/recommend 与 everyday/history 里完全一致，
 * 与 `SearchApi` 用的别名不同，所以这里单独解析（不硬塞进 KugouApi.parseSong）。
 */
object ExploreApi {

    private const val TAG = "explore"

    /** 播放历史的一天（按本地时区分组）。 */
    data class HistoryDay(val dateKey: String, val label: String, val songs: List<Song>)

    /** 歌手详情（数字概览 + 关注状态）。 */
    data class ArtistDetail(
        val id: String,
        val name: String,
        val avatarUrl: String?,
        val albumCount: Int,
        val songCount: Int,
        val fansNums: Long,
        val intro: String?,
        val userStatus: Int,
    )

    /** 歌手专辑分页（total 在顶层，不在 data 里）。 */
    data class AlbumPage(val total: Int, val items: List<AlbumBrief>)

    /** 每日推荐历史的一天。 */
    data class EverydayDay(val dateKey: String, val title: String, val songs: List<Song>)

    // ------------------------------------------------------------ 网络

    /** 播放历史（按 `ot` 降序 + 本地日期分组）。 */
    fun history(page: Int = 1, pageSize: Int = 50): List<HistoryDay> =
        parseHistory(KugouApi.getRaw("/user/history", mapOf("page" to page.toString(), "pagesize" to pageSize.toString())))

    /** 歌手详情。 */
    fun artistDetail(id: String): ArtistDetail? =
        parseArtistDetail(KugouApi.getRaw("/artist/detail", mapOf("id" to id)))

    /** 歌手专辑列表。 */
    fun artistAlbums(id: String, page: Int = 1, pageSize: Int = 30): AlbumPage =
        parseArtistAlbums(
            KugouApi.getRaw(
                "/artist/albums",
                mapOf("id" to id, "page" to page.toString(), "pagesize" to pageSize.toString()),
            ),
        )

    /** 关注歌手。返回上游受理结果（true = status 1）。 */
    fun follow(id: String): Boolean {
        if (!AuthStore.isLoggedIn) {
            KugouApi.markNeedsLogin()
            return false
        }
        val root = KugouApi.getRaw("/artist/follow", mapOf("id" to id)) ?: return false
        val ok = JsonLite.asInt(JsonLite.dig(root, "status")) == 1
        Diag.info(TAG, "关注歌手 $id 受理=$ok")
        return ok
    }

    /** 取关歌手。 */
    fun unfollow(id: String): Boolean {
        if (!AuthStore.isLoggedIn) {
            KugouApi.markNeedsLogin()
            return false
        }
        val root = KugouApi.getRaw("/artist/unfollow", mapOf("id" to id)) ?: return false
        val ok = JsonLite.asInt(JsonLite.dig(root, "status")) == 1
        Diag.info(TAG, "取关歌手 $id 受理=$ok")
        return ok
    }

    /** 每日推荐历史（按接口给定顺序，已是新→旧）。 */
    fun everydayHistory(): List<EverydayDay> =
        parseEverydayHistory(KugouApi.getRaw("/everyday/history"))

    /** 风格推荐（tagid 如 S14=华语流行）。 */
    fun styleRecommend(tagId: String): List<Song> =
        parseStyleRecommend(KugouApi.getRaw("/everyday/style/recommend", mapOf("tagid" to tagId)))

    // ------------------------------------------------------------ 纯解析

    internal fun parseHistory(root: Any?): List<HistoryDay> {
        val data = JsonLite.asObject(JsonLite.dig(root, "data")) ?: return emptyList()
        val raw = JsonLite.asArray(data["songs"]).orEmpty()

        // 逐条抽出 (ot 秒, Song)，过滤脏条目
        val entries = raw.mapNotNull { item ->
            val obj = JsonLite.asObject(item) ?: return@mapNotNull null
            val ot = JsonLite.asLong(obj["ot"])
            val info = JsonLite.asObject(obj["info"]) ?: return@mapNotNull null
            val song = parseSongEntry(info) ?: return@mapNotNull null
            if (ot <= 0) return@mapNotNull null
            ot to song
        }.sortedByDescending { it.first } // 组内与整体都按时间降序

        if (entries.isEmpty()) return emptyList()

        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val out = ArrayList<HistoryDay>()
        var currentKey: String? = null
        var bucket = ArrayList<Song>()
        var bucketDate: LocalDate? = null

        fun flush() {
            if (bucket.isNotEmpty()) {
                out.add(HistoryDay(dateKey = currentKey ?: "", label = dayLabel(bucketDate, today), songs = bucket.toList()))
            }
        }

        for ((ot, song) in entries) {
            val date = Instant.ofEpochSecond(ot).atZone(zone).toLocalDate()
            val key = date.toString()
            if (key != currentKey) {
                flush()
                currentKey = key
                bucket = ArrayList()
                bucketDate = date
            }
            bucket.add(song)
        }
        flush()
        Diag.info(TAG, "播放历史 → ${out.size} 天 / ${entries.size} 首")
        return out
    }

    /** 「今天」「昨天」「M月d日」——按本地日期算，跨天边界用它而不是字符串比较。 */
    internal fun dayLabel(date: LocalDate?, today: LocalDate): String {
        if (date == null) return ""
        val diff = java.time.temporal.ChronoUnit.DAYS.between(date, today)
        return when (diff) {
            0L -> "今天"
            1L -> "昨天"
            2L -> "前天"
            else -> "${date.monthValue}月${date.dayOfMonth}日"
        }
    }

    internal fun parseArtistDetail(root: Any?): ArtistDetail? {
        val d = JsonLite.asObject(JsonLite.dig(root, "data")) ?: return null
        val name = JsonLite.asString(d["author_name"]) ?: return null
        val id = JsonLite.asString(d["author_id"]) ?: ""
        return ArtistDetail(
            id = id,
            name = name,
            avatarUrl = JsonLite.asString(d["sizable_avatar"]),
            albumCount = JsonLite.asInt(d["album_count"]),
            songCount = JsonLite.asInt(d["song_count"]),
            fansNums = JsonLite.asLong(d["fansnums"]),
            intro = JsonLite.asString(d["intro"]),
            userStatus = JsonLite.asInt(d["user_status"]),
        )
    }

    internal fun parseArtistAlbums(root: Any?): AlbumPage {
        if (root == null) return AlbumPage(0, emptyList())
        val total = JsonLite.asInt(JsonLite.dig(root, "total"))
        val items = JsonLite.asArray(JsonLite.dig(root, "data")).orEmpty().mapNotNull { item ->
            val obj = JsonLite.asObject(item) ?: return@mapNotNull null
            val name = JsonLite.asString(obj["album_name"]) ?: return@mapNotNull null
            AlbumBrief(
                id = JsonLite.asString(obj["album_id"]) ?: "",
                name = name,
                artist = JsonLite.asString(obj["author_name"]) ?: "",
                coverUrl = JsonLite.asString(obj["sizable_cover"]),
            )
        }
        return AlbumPage(total, items)
    }

    internal fun parseEverydayHistory(root: Any?): List<EverydayDay> {
        val lists = JsonLite.asArray(JsonLite.dig(root, "data", "lists")) ?: return emptyList()
        return lists.mapNotNull { item ->
            val obj = JsonLite.asObject(item) ?: return@mapNotNull null
            val dateKey = JsonLite.asString(obj["date"]) ?: return@mapNotNull null
            val title = JsonLite.asString(obj["history_title"]) ?: dateKey
            val songs = JsonLite.asArray(obj["song_list"]).orEmpty().mapNotNull { s ->
                JsonLite.asObject(s)?.let { parseSongEntry(it) }
            }
            EverydayDay(dateKey = dateKey, title = title, songs = songs)
        }
    }

    internal fun parseStyleRecommend(root: Any?): List<Song> {
        val list = JsonLite.asArray(JsonLite.dig(root, "data", "song_list")) ?: return emptyList()
        return list.mapNotNull { s -> JsonLite.asObject(s)?.let { parseSongEntry(it) } }
    }

    /**
     * 解析歌曲条目。
     *
     * ⚠️ 同一首曲在两类接口里**字段名不同**（实测）：
     *
     * | 字段 | `/user/history` 的 `info` | `/everyday/…`、`/artist/…` |
     * |---|---|---|
     * | 歌名 | `name` | `songname` |
     * | 歌手 | `singername` | `author_name` |
     * | 时长 | `timelen`（**毫秒**） | `time_length`（**秒**） |
     * | 封面 | `cover` | `sizable_cover` |
     *
     * 因此这里按「先 A 后 B」依次回退，并且**时长单独判定**：
     * 一个 281000 的 `timelen` 绝不能当成 281000 秒。
     */
    internal fun parseSongEntry(obj: Map<String, Any?>): Song? {
        val title = JsonLite.asString(obj["songname"])
            ?: JsonLite.asString(obj["name"])
            ?: return null
        val hash = JsonLite.asString(obj["hash"]) ?: ""

        // 时长：time_length 是秒；timelen 是毫秒，需要 /1000
        val durationSec = when {
            obj["time_length"] != null -> JsonLite.asInt(obj["time_length"])
            obj["timelen"] != null -> (JsonLite.asLong(obj["timelen"]) / 1000L).toInt()
            else -> 0
        }

        return Song(
            fileName = JsonLite.asString(obj["filename"]) ?: title,
            title = title,
            singer = JsonLite.asString(obj["author_name"])
                ?: JsonLite.asString(obj["singername"])
                ?: "",
            hash = hash,
            durationSec = durationSec,
            imageUrl = JsonLite.asString(obj["sizable_cover"])
                ?: JsonLite.asString(obj["cover"]),
            fileSize = JsonLite.asLong(obj["file_size"]),
            payType = JsonLite.asInt(obj["pay_type"]),
            albumName = JsonLite.asString(obj["album_name"]),
            albumAudioId = JsonLite.asString(obj["album_audio_id"]) ?: "",
            albumId = JsonLite.asString(obj["album_id"]) ?: "",
            mixSongId = JsonLite.asString(obj["mixsongid"]) ?: "",
        )
    }
}
