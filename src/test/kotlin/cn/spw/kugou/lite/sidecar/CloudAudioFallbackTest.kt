package cn.spw.kugou.lite.sidecar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 云歌单「未发布音频」回退到官方版本的判定测试。
 *
 * ## 用户反馈
 *
 * 歌单里的「我们的歌」音频不对、没封面；但搜索同名是正常的。
 *
 * ## 实测根因
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
 * 命中后替换 hash 播放（并取其封面）。
 */
class CloudAudioFallbackTest {

    @Test
    fun `歌名规范化要去掉扩展名与复合歌手前缀`() {
        assertEquals("我们的歌", normalizeSongTitle("刘大拿 - 我们的歌.mp3"))
        assertEquals("ハレハレヤ (朗朗晴天)", normalizeSongTitle("猫瑾 - ハレハレヤ (朗朗晴天).mp3"))
    }

    @Test
    fun `没有歌手前缀时原样去扩展名`() {
        assertEquals("别让我等待", normalizeSongTitle("别让我等待.mp3"))
    }

    @Test
    fun `从复合名里拆出歌手`() {
        assertEquals("刘大拿", singerFromComposite("刘大拿 - 我们的歌.mp3"))
        assertEquals("", singerFromComposite("只有歌名.mp3"))
    }

    @Test
    fun `搜索命中要与原 hash 不同才算回退`() {
        // 搜到自己（同一个未发布 hash）没有意义，必须换到别的资源
        val original = "68FE9FFA23EA353F7ABCFD9856B66FFF"
        assertFalse(canFallback(original, original))
        assertTrue(canFallback(original, "DF70C2A0A219F448E7D86AA8B6E0C522"))
    }

    @Test
    fun `空 hash 不能回退`() {
        assertFalse(canFallback("", "DF70C2A0A219F448E7D86AA8B6E0C522"))
        assertFalse(canFallback("68FE9FFA", ""))
    }

    @Test
    fun `搜索结果要匹配歌手才接受`() {
        // 只按歌名搜会命中一堆同名（王力宏、陌粒、DJ 版…），
        // 必须优先挑歌手一致的，避免串到别的歌
        val candidates = listOf(
            SearchHit("我们的歌", "王力宏", "4E294957"),
            SearchHit("我们的歌", "刘大拿", "DF70C2A0"),
            SearchHit("我们的歌 (DJ阿哲版)", "山野", "88071940"),
        )
        val picked = preferSameSinger(candidates, "刘大拿")
        assertEquals("DF70C2A0", picked?.hash)
    }

    @Test
    fun `歌手不一致但只有一个精确歌名时也可用`() {
        val candidates = listOf(SearchHit("我们的歌", "陌粒", "EA7EE5D3"))
        val picked = preferSameSinger(candidates, "刘大拿")
        assertEquals("EA7EE5D3", picked?.hash)
    }

    @Test
    fun `模糊歌名（带DJ版）不应被优先选中`() {
        val candidates = listOf(
            SearchHit("我们的歌 (DJ阿哲版)", "山野", "88071940"),
            SearchHit("我们的歌", "王力宏", "4E294957"),
        )
        val picked = preferSameSinger(candidates, "刘大拿")
        // 精确歌名优先于带后缀的
        assertEquals("4E294957", picked?.hash)
    }
}
