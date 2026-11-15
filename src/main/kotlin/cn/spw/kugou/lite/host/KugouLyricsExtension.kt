package cn.spw.kugou.lite.host

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint
import org.pf4j.Extension

/**
 * 把歌词**直接投喂给宿主**的 PF4J 扩展点。
 *
 * 宿主 `PlaybackService$loadLyrics$2` 会先遍历所有
 * `PlaybackExtensionPoint` 调 `updateLyrics(MediaItem)`；返回非 null 时
 * 直接作为歌词使用，不再读文件。
 *
 * ## 两个必须同时满足的条件
 *
 * 1. **只按 id 精确匹配**。早期用「当前曲目」兜底，导致宿主问新歌歌词时
 *    拿到上一首的（用户反馈「歌词都是上一首歌的」）—— 错歌词比没歌词更糟。
 * 2. **查不到时短暂等待**。真机日志：
 *
 *       12:34:23 宿主查询 id=kugou-4EA48315 命中=false 长度=0
 *       12:35:07 宿主查询 id=kugou-4EA48315 命中=true  长度=2583
 *
 *    宿主在切歌瞬间就来问，而歌词接口还没返回。宿主只在 onLoaded 时问一次，
 *    问空就永远无歌词。所以这里做有界等待（最多 [WAIT_MS]），
 *    等到就返回正确歌词，等不到再放弃让宿主回退读文件。
 *
 * 只处理 `kugou-` 前缀曲目，绝不干扰宿主本地曲库。
 */
@Extension
class KugouLyricsExtension : PlaybackExtensionPoint {

    override fun updateLyrics(
        mediaItem: PlaybackExtensionPoint.MediaItem,
    ): String? = resolve(mediaItem)

    override fun onBeforeLoadLyrics(
        mediaItem: PlaybackExtensionPoint.MediaItem,
    ): String? = resolve(mediaItem)

    private fun resolve(mediaItem: PlaybackExtensionPoint.MediaItem): String? {
        val id = mediaItem.id.orEmpty()
        // 只认我们自己的曲目；宿主本地曲库一律返回 null 让它走自己的解析
        if (!id.startsWith("kugou-")) return null

        // 先看一眼：已就绪就直接给，绝大多数情况走这里。
        LyricsRegistry.get(id)?.let { return it }

        // 还没就绪（歌词接口在途）：有界等待，避免宿主问到空结果后永不重试。
        val hit = LyricsRegistry.awaitLyrics(id, WAIT_MS)
        cn.spw.kugou.lite.diag.Diag.info(
            "lyricsext",
            "宿主查询歌词 id=$id 命中=${hit != null} 长度=${hit?.length ?: 0}",
        )
        return hit
    }

    private companion object {
        /** 等待歌词就绪的上限。太长会卡住宿主加载线程，1.5s 足够一次接口往返。 */
        const val WAIT_MS = 1_500L
    }
}
