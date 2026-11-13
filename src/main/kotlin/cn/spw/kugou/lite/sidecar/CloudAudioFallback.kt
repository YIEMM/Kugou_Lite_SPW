package cn.spw.kugou.lite.sidecar

/**
 * 云歌单「未发布音频」回退到官方版本的辅助逻辑。
 *
 * ## 用户反馈
 *
 * 歌单里的「我们的歌」音频不对、没封面；但搜索同名是正常的。
 *
 * ## 实测根因（2026-11）
 *
 * | 来源 | hash | 状态 |
 * |---|---|---|
 * | 歌单（云上传文件） | `68FE9FFA…` | `/song/url` → 31833；`/song/url/new` → `Deny: the audio isn't publish.` |
 * | 搜索（官方版本） | `DF70C2A0…` | 正常播放 |
 *
 * 名字都是「刘大拿 - 我们的歌」，但 hash 不同：歌单里那个是**用户自己上传、
 * 酷狗侧已下架/未发布**的文件，官方版本是另一条资源。
 *
 * 修法：歌单里的 hash 取流失败时，用「歌手 + 歌名」搜索官方版本，
 * 命中后替换 hash 播放（并取其封面）。见 [KugouApi.streamUrl]。
 */

/** 搜索结果的精简表示，用于挑选回退版本。 */
data class SearchHit(val title: String, val singer: String, val hash: String)

/** 去掉 .mp3 等扩展名；若有「歌手 - 歌名」前缀，只取歌名部分。 */
fun normalizeSongTitle(raw: String): String {
    val noExt = raw.replace(Regex("""\.(mp3|flac|m4a|ogg|wav|ape)$""", RegexOption.IGNORE_CASE), "")
    val idx = noExt.indexOf(" - ")
    return if (idx > 0) noExt.substring(idx + 3).trim() else noExt.trim()
}

/** 从「歌手 - 歌名.mp3」里拆出歌手；没有前缀返回空串。 */
fun singerFromComposite(raw: String): String {
    val noExt = raw.replace(Regex("""\.(mp3|flac|m4a|ogg|wav|ape)$""", RegexOption.IGNORE_CASE), "")
    val idx = noExt.indexOf(" - ")
    return if (idx > 0) noExt.substring(0, idx).trim() else ""
}

/** 只有在「新 hash 与原 hash 不同且都非空」时才值得回退。 */
fun canFallback(original: String, found: String): Boolean =
    original.isNotBlank() && found.isNotBlank() && !original.equals(found, ignoreCase = true)

/**
 * 从搜索结果里挑最合适的：精确歌名 > 歌手一致 > 其它。
 * 目的是避免同名串歌（用户反馈「音频不对」）。
 */
fun preferSameSinger(candidates: List<SearchHit>, singer: String): SearchHit? {
    if (candidates.isEmpty()) return null
    val exact = candidates.filter { !it.title.contains("(") && !it.title.contains("（") }
    val pool = exact.ifEmpty { candidates }
    return pool.firstOrNull { it.singer.equals(singer, ignoreCase = true) }
        ?: pool.firstOrNull { singer.isNotBlank() && (it.singer.contains(singer) || singer.contains(it.singer)) }
        ?: pool.firstOrNull()
}
