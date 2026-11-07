package cn.spw.kugou.lite.sidecar

import cn.spw.kugou.lite.diag.Diag

/**
 * 歌单广场模型（2026-11-07 实测，规格附录字段矩阵）。
 */
/** 广场歌单卡：`collectionId` 是 global_collection_id（进统一详情页的钥匙）。 */
data class SquarePlaylist(
    val name: String,
    val coverUrl: String?,
    val playCount: Long,
    val collectionId: String,
)

/** 广场标签（父分组的子标签）。 */
data class SquareTag(val tagId: String, val name: String)

/** 广场父分组（精选/语种/风格/场景/主题…），`children` 可为空。 */
data class SquareTagGroup(val tagId: String, val name: String, val children: List<SquareTag>)

/**
 * 歌单广场 API。
 *
 * - 标签：`/playlist/tags` → `data[]` 父分组（tag_id/tag_name/son[]）
 * - 歌单：`/top/playlist?module_id=2&category_id=&page=&pagesize=` →
 *   `data.special_list[]`（specialname/flexible_cover/imgurl/play_count/
 *   global_collection_id）+ `data.has_next`；**精选 = category_id=0**
 * - `/sheet/…` 是曲谱接口（实测），与歌单广场无关，勿用
 * - name 带 `<em>` 高亮标签 → [stripEm] 统一剥离
 */
object SquareApi {

    private const val TAG = "square"

    /** 分页结果：条目 + 是否还有下一页。 */
    data class SquarePage(val items: List<SquarePlaylist>, val hasNext: Boolean)

    // ------------------------------------------------------------ 网络

    /** 父分组标签。 */
    fun tags(): List<SquareTagGroup> = parseTags(KugouApi.getRaw("/playlist/tags"))

    /** 广场歌单（分页）。 */
    fun playlists(moduleId: String = "2", categoryId: String, page: Int, pageSize: Int = 30): SquarePage =
        parsePlaylists(
            KugouApi.getRaw(
                "/top/playlist",
                mapOf(
                    "module_id" to moduleId,
                    "category_id" to categoryId,
                    "page" to page.toString(),
                    "pagesize" to pageSize.toString(),
                ),
            ),
        )

    // ------------------------------------------------------------ 纯解析

    internal fun parseTags(root: Any?): List<SquareTagGroup> {
        val arr = JsonLite.asArray(JsonLite.dig(root, "data")) ?: return emptyList()
        return arr.mapNotNull { item ->
            val obj = JsonLite.asObject(item) ?: return@mapNotNull null
            val tagId = JsonLite.asString(obj["tag_id"]) ?: return@mapNotNull null
            val name = stripEm(JsonLite.asString(obj["tag_name"]) ?: return@mapNotNull null)
            val children = JsonLite.asArray(obj["son"]).orEmpty().mapNotNull { son ->
                val s = JsonLite.asObject(son) ?: return@mapNotNull null
                val sid = JsonLite.asString(s["tag_id"]) ?: return@mapNotNull null
                SquareTag(tagId = sid, name = stripEm(JsonLite.asString(s["tag_name"]) ?: return@mapNotNull null))
            }
            SquareTagGroup(tagId = tagId, name = name, children = children)
        }
    }

    internal fun parsePlaylists(root: Any?): SquarePage {
        val data = JsonLite.asObject(JsonLite.dig(root, "data")) ?: return SquarePage(emptyList(), false)
        val items = JsonLite.asArray(data["special_list"]).orEmpty().mapNotNull { item ->
            val obj = JsonLite.asObject(item) ?: return@mapNotNull null
            val name = stripEm(JsonLite.asString(obj["specialname"]) ?: return@mapNotNull null)
            val gid = JsonLite.asString(obj["global_collection_id"]) ?: return@mapNotNull null
            SquarePlaylist(
                name = name,
                coverUrl = JsonLite.asString(obj["flexible_cover"]) ?: JsonLite.asString(obj["imgurl"]),
                playCount = JsonLite.asLong(obj["play_count"]),
                collectionId = gid,
            )
        }
        val hasNext = JsonLite.asInt(data["has_next"]) == 1
        Diag.info(TAG, "广场歌单 → ${items.size} 条（hasNext=$hasNext）")
        return SquarePage(items, hasNext)
    }

    /** 播放量文本：≥1万 显示「x.x万」，否则原数。 */
    internal fun playCountText(count: Long): String = when {
        count >= 10_000 -> {
            val w = count / 10_000.0
            val v = if (w >= 100) w.toInt().toString() else String.format("%.1f", w).removeSuffix(".0")
            "${v}万"
        }
        else -> count.toString()
    }

    internal fun stripEm(s: String?): String =
        s?.replace("<em>", "")?.replace("</em>", "") ?: ""
}

/**
 * 歌单管理（写操作）：新建 / 删除 / 加歌 / 删歌 / 排序。
 *
 * ## 实测口径（裁决 R1/R4）
 *
 * - 新建：`/playlist/add?type=0&name=` → 成功返回 `data.info.listid` 与
 *   `global_collection_id`、`data.total_ver`（全局版本，后续 sort 用）
 * - **重命名不做**：`/playlist/update` 上游返回 30203（2026-11-07 实测），
 *   sidecar 受 AGPL 约束不可改 —— 等上游修复再接
 * - 删除：`/playlist/del?listid=&type=`（type=0/1 实测均可删自建歌单）；
 *   **读缓存延迟 3–8 秒** → [verifyGone] 轮询回读
 * - 加歌/删歌：`/playlist/tracks/add|del`（此前已真机验证）
 * - 所有写操作：未登录直接返回 false 不发请求
 *
 * @return 各写操作 true = 上游受理成功（status=1）；**受理 ≠ 已生效**，
 *         删除类操作务必配 [verifyGone]。
 */
object PlaylistManage {

    private const val TAG = "playlist-manage"

    /** 新建歌单。@return 三元 (listid, globalCollectionId, 全局 total_ver)；失败返回 null。 */
    fun create(name: String): Triple<String, String, String>? {
        if (!AuthStore.isLoggedIn) {
            KugouApi.markNeedsLogin()
            return null
        }
        if (name.isBlank()) return null
        val root = KugouApi.getRaw("/playlist/add", mapOf("name" to name, "type" to "0")) ?: return null
        if (JsonLite.asInt(JsonLite.dig(root, "status")) != 1) {
            Diag.warn(TAG, "新建歌单失败：${JsonLite.asString(JsonLite.dig(root, "error_msg"))}")
            return null
        }
        val listid = JsonLite.asString(JsonLite.dig(root, "data", "info", "listid")) ?: return null
        val gid = JsonLite.asString(JsonLite.dig(root, "data", "info", "global_collection_id")) ?: ""
        val ver = JsonLite.asString(JsonLite.dig(root, "data", "total_ver")) ?: ""
        Diag.info(TAG, "新建歌单「$name」listid=$listid gid=$gid")
        return Triple(listid, gid, ver)
    }

    /** 删除歌单（自建或收藏均可；上游受理后读缓存有延迟）。 */
    fun delete(listId: String): Boolean {
        if (!AuthStore.isLoggedIn) {
            KugouApi.markNeedsLogin()
            return false
        }
        if (listId.isBlank()) return false
        val root = KugouApi.getRaw("/playlist/del", mapOf("listid" to listId, "type" to "1")) ?: return false
        val ok = JsonLite.asInt(JsonLite.dig(root, "status")) == 1
        Diag.info(TAG, "删除歌单 listId=$listId 受理=$ok")
        return ok
    }

    /** 加歌到歌单（data 参数 `歌名|hash|album_id|mixsongid`，走 query 不走 body —— 上游用 q_str 取参）。 */
    fun addTrack(globalCollectionId: String, song: Song): Boolean {
        if (!AuthStore.isLoggedIn) {
            KugouApi.markNeedsLogin()
            return false
        }
        val data = "${song.title}|${song.hash}|${song.albumId}|${song.mixSongId}"
        val root = KugouApi.getRaw(
            "/playlist/tracks/add",
            mapOf("global_collection_id" to globalCollectionId, "data" to data),
        ) ?: return false
        val ok = JsonLite.asInt(JsonLite.dig(root, "status")) == 1
        Diag.info(TAG, "加歌「${song.title}」→ $globalCollectionId 受理=$ok")
        return ok
    }

    /** 从歌单删歌（data 参数 `hash|album_id` 或 fileid 数字；同样走 query）。 */
    fun delTrack(globalCollectionId: String, song: Song): Boolean {
        if (!AuthStore.isLoggedIn) {
            KugouApi.markNeedsLogin()
            return false
        }
        val data = "${song.hash}|${song.albumId}"
        val root = KugouApi.getRaw(
            "/playlist/tracks/del",
            mapOf("global_collection_id" to globalCollectionId, "data" to data),
        ) ?: return false
        val ok = JsonLite.asInt(JsonLite.dig(root, "status")) == 1
        Diag.info(TAG, "删歌「${song.title}」← $globalCollectionId 受理=$ok")
        return ok
    }

    /**
     * 删除回读轮询：读缓存延迟 3–8 秒，[isPresent] 返回 false（已消失）即成功。
     *
     * @param attempts 轮询次数（默认 5 次 × 2 秒 ≈ 覆盖实测最长延迟）
     */
    fun verifyGone(
        listId: String,
        attempts: Int = 5,
        delayMs: Long = 2_000,
        isPresent: (String) -> Boolean = { id -> checkStillPresent(id) },
    ): Boolean {
        repeat(attempts) {
            if (!isPresent(listId)) return true
            if (delayMs > 0) Thread.sleep(delayMs)
        }
        return false
    }

    /** 歌单是否仍存在（读用户歌单列表查 listid）。 */
    private fun checkStillPresent(listId: String): Boolean {
        val root = KugouApi.getRaw("/user/playlist") ?: return true // 拉不到当「还在」处理，宁误报不漏报
        val info = JsonLite.asArray(JsonLite.dig(root, "data", "info")).orEmpty()
        return info.any { JsonLite.asString(JsonLite.dig(it, "listid")) == listId }
    }
}
