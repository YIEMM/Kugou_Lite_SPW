package cn.spw.kugou.lite.sidecar

/**
 * 歌单曲目接口的选择策略。
 *
 * ## 为什么需要两套接口（用户报告的「122 首只剩 118 首」）
 *
 * 酷狗对**版权屏蔽**曲目，在两个接口上的行为完全不同（2026-11 实测）：
 *
 * | 接口 | 参数 | 屏蔽曲目 | 122 首实测 |
 * |---|---|---|---|
 * | `/playlist/track/all` | `global_collection_id` | 只回 `{"fileid":95,"shield":1}` 占位 | 解析出 **118** 首 |
 * | `/playlist/track/all/new` | `listid`（数字） | **返回完整曲目信息** | 解析出 **122** 首 |
 *
 * 官方客户端和 MoeKoe 都能显示 122 首，就是因为走的是后者。
 *
 * ## 选择规则
 *
 * - **自己的歌单**（有数字 `listid`）→ 优先 `/playlist/track/all/new`
 * - 别人的歌单 / 广场 / 搜索结果（只有 `collection_xxx` gid）→ 只能走 gid 接口
 * - `/new` 失败（非 200 / status≠1）→ 自动回退 gid 接口，保证不空列表
 */
enum class PlaylistSource {
    /** 走 `/playlist/track/all/new`（用 listid，能拿到屏蔽曲目）。 */
    OwnList,

    /** 走 `/playlist/track/all`（用 gid）。 */
    ByGid,
}

/**
 * 选接口。
 *
 * @param listId 数字 listid（`/user/playlist` 的 `listid`）；空或非数字表示不可用
 * @param gid `collection_xxx` 形式的 gid
 */
fun pickPlaylistSource(listId: String, gid: String): PlaylistSource =
    if (listId.isNotBlank() && listId.all { it.isDigit() }) PlaylistSource.OwnList
    else PlaylistSource.ByGid

/**
 * 把 `/playlist/track/all/new` 的复合名字拆成 (歌手, 歌名)。
 *
 * 该接口的 `name` 形如 `歌手 - 歌名.mp3`（如 `猫瑾 - ハレハレヤ (朗朗晴天).mp3`），
 * 而 `singername` / `songname` 是空的，所以必须自己拆。
 *
 * 规则：
 * - 去掉结尾的扩展名（`.mp3` / `.flac` / `.m4a` / `.ogg` / `.wav`）
 * - 按**第一个** ` - ` 拆（歌名里可能还有连字符）
 * - 没有 ` - ` 时整串当歌名、歌手为空
 */
fun splitCompositeName(raw: String): Pair<String, String> {
    if (raw.isBlank()) return "" to ""
    val withoutExt = raw.replace(Regex("""\.(mp3|flac|m4a|ogg|wav|ape)$""", RegexOption.IGNORE_CASE), "")
    val idx = withoutExt.indexOf(" - ")
    return if (idx > 0) {
        withoutExt.substring(0, idx).trim() to withoutExt.substring(idx + 3).trim()
    } else {
        "" to withoutExt.trim()
    }
}
