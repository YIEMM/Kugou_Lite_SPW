package cn.spw.kugou.lite.sidecar

import cn.spw.kugou.lite.diag.Diag

/**
 * 搜索扩展：热搜词 / 输入建议 / 综合结果各类型搜索。
 *
 * ## 实测口径（2026-11-07，规格附录字段矩阵）
 *
 * - `/search/hot`：`data.list[]` 是**多榜单**（热搜榜/新歌榜/…），每组 `keywords[]`；
 *   拍平保序，去重交给 UI 层不在这里（榜单本身不重复）。
 * - `/search/suggest`：`data[].RecordDatas[].HintInfo`，**data 是顶层数组**；
 *   实测同一词会重复出现 → 这里去重保序。
 * - `/search/special`（歌单）与 `/search/album`（专辑）：容器在 `data.info[]`，
 *   总数在 `data.total`；**name 带 `<em>` 搜索高亮标签，统一剥离**。
 * - `/search/artist`：`data[]` 顶层数组，只有 `singername/singerid`，
 *   无头像无 total —— 网格用占位头像，UI 不显示计数。
 * - `/search/complex`、`/search/mixed`：实测 `error_code=152 Parameter Error` 且
 *   lists 空，**弃用**（裁决 R2）——各 Tab 计数用各自端点的 total。
 *
 * 网络层全部经 [KugouApi.getRaw]：登录态失效（20017/20018）时它返回 null 并置
 * `lastNeedsLogin`，这里把 null 解析成空结果，UI 据此提示登录而不是「没有数据」。
 */
object SearchApi {

    private const val TAG = "search-ext"

    /** 分页搜索结果：总数 + 当前页条目。 */
    data class SearchPage<T>(val total: Int, val items: List<T>)

    // ------------------------------------------------------------ 网络

    /** 热搜词（拍平多榜单）。 */
    fun hotWords(): List<String> = parseHotWords(KugouApi.getRaw("/search/hot"))

    /** 输入建议（去重保序）。 */
    fun suggest(kw: String): List<String> {
        if (kw.isBlank()) return emptyList()
        return parseSuggest(KugouApi.getRaw("/search/suggest", mapOf("keywords" to kw)))
    }

    /** 歌单搜索（type 恒为收藏，进详情用 gid）。 */
    fun searchSpecial(kw: String, page: Int = 1, pageSize: Int = 30): SearchPage<Playlist> =
        parseSpecial(KugouApi.getRaw("/search/special", mapOf("keywords" to kw, "page" to page.toString(), "pagesize" to pageSize.toString())))

    /** 专辑搜索。 */
    fun searchAlbums(kw: String, page: Int = 1, pageSize: Int = 30): SearchPage<AlbumBrief> =
        parseAlbums(KugouApi.getRaw("/search/album", mapOf("keywords" to kw, "page" to page.toString(), "pagesize" to pageSize.toString())))

    /** 歌手搜索（无 total/无头像）。 */
    fun searchArtists(kw: String, pageSize: Int = 30): List<Artist> =
        parseArtists(KugouApi.getRaw("/search/artist", mapOf("keywords" to kw, "pagesize" to pageSize.toString())))

    // ------------------------------------------------------------ 纯解析（internal，单测钉住）

    internal fun parseHotWords(root: Any?): List<String> {
        val groups = JsonLite.asArray(JsonLite.dig(root, "data", "list")) ?: return emptyList()
        val out = ArrayList<String>()
        for (group in groups) {
            val kws = JsonLite.asArray(JsonLite.dig(group, "keywords")) ?: continue
            for (kw in kws) {
                JsonLite.asString(JsonLite.dig(kw, "keyword"))?.takeIf { it.isNotBlank() }?.let { out.add(it) }
            }
        }
        return out
    }

    internal fun parseSuggest(root: Any?): List<String> {
        val groups = JsonLite.asArray(JsonLite.dig(root, "data")) ?: return emptyList()
        val out = ArrayList<String>()
        val seen = HashSet<String>()
        for (group in groups) {
            val records = JsonLite.asArray(JsonLite.dig(group, "RecordDatas")) ?: continue
            for (r in records) {
                val hint = JsonLite.asString(JsonLite.dig(r, "HintInfo"))?.takeIf { it.isNotBlank() } ?: continue
                if (seen.add(hint)) out.add(hint)
            }
        }
        return out
    }

    internal fun parseSpecial(root: Any?): SearchPage<Playlist> {
        val data = JsonLite.asObject(JsonLite.dig(root, "data")) ?: return SearchPage(0, emptyList())
        val total = JsonLite.asInt(data["total"])
        val items = JsonLite.asArray(data["info"]).orEmpty().mapNotNull { item ->
            val obj = JsonLite.asObject(item) ?: return@mapNotNull null
            val name = stripEm(JsonLite.asString(obj["specialname"]) ?: return@mapNotNull null)
            Playlist(
                id = JsonLite.asString(obj["gid"]) ?: "",
                name = name,
                coverUrl = JsonLite.asString(obj["imgurl"]),
                songCount = JsonLite.asInt(obj["songcount"]),
                playCount = JsonLite.asLong(obj["playcount"]),
                description = null,
                listId = JsonLite.asString(obj["specialid"]) ?: "",
                type = 1,
                isFavorites = false,
            )
        }
        Diag.info(TAG, "歌单搜索 → ${items.size} 条（total=$total）")
        return SearchPage(total, items)
    }

    internal fun parseAlbums(root: Any?): SearchPage<AlbumBrief> {
        val data = JsonLite.asObject(JsonLite.dig(root, "data")) ?: return SearchPage(0, emptyList())
        val total = JsonLite.asInt(data["total"])
        val items = JsonLite.asArray(data["info"]).orEmpty().mapNotNull { item ->
            val obj = JsonLite.asObject(item) ?: return@mapNotNull null
            val name = stripEm(JsonLite.asString(obj["albumname"]) ?: return@mapNotNull null)
            AlbumBrief(
                id = JsonLite.asString(obj["albumid"]) ?: "",
                name = name,
                artist = stripEm(JsonLite.asString(obj["singername"]) ?: ""),
                coverUrl = JsonLite.asString(obj["imgurl"]),
            )
        }
        Diag.info(TAG, "专辑搜索 → ${items.size} 条（total=$total）")
        return SearchPage(total, items)
    }

    internal fun parseArtists(root: Any?): List<Artist> {
        val arr = JsonLite.asArray(JsonLite.dig(root, "data")) ?: return emptyList()
        return arr.mapNotNull { item ->
            val obj = JsonLite.asObject(item) ?: return@mapNotNull null
            val name = stripEm(JsonLite.asString(obj["singername"]) ?: return@mapNotNull null)
            // 缺 singerid 时 asLong 得 0，Artist(id="0") 仍非空 → 卡片可点 →
            // /artist/detail?id=0 → 空白页无提示（N21）。id 无效就直接丢弃该条。
            val id = JsonLite.asLong(obj["singerid"])
            if (id <= 0L) return@mapNotNull null
            Artist(id = id.toString(), name = name, avatarUrl = null)
        }
    }

    /** 剥离搜索结果的 `<em>`/`</em>` 高亮标签。 */
    internal fun stripEm(s: String?): String =
        s?.replace("<em>", "")?.replace("</em>", "") ?: ""
}
