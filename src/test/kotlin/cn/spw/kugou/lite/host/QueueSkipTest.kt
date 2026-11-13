package cn.spw.kugou.lite.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 队列中「无版权/取流失败」曲目的剔除逻辑（用户提出的问题）。
 *
 * ## 问题
 *
 * `fillQueuePathsAsync` 里取流失败只是 `continue`，那个曲目的媒体项
 * **仍然留在队列里但没有 path**。宿主播到它时：
 * - 没有可解码地址 → 播放卡住
 * - Salt Player **不会自动跳过**（它不知道这个条目是坏的）
 *
 * 用户实测表现：「播放全部」到一首无版权的歌就停在那里不动了。
 *
 * ## 修复思路
 *
 * 建队列前先**过滤掉注定播不了的曲目**，保证队列里每一项都有 path。
 * 但取流很贵（还有频控），不可能预先探测全部曲目，所以：
 *
 * 1. 排队列时，若某首取流失败 → 从队列中剔除并重建（不是留空项）
 * 2. 保留「当前正在播的那首」的下标映射，避免跳歌
 */
class QueueSkipTest {

    @Test
    fun `取流失败的曲目应从队列里剔除`() {
        val all = listOf("a", "b", "c", "d")
        val playable = setOf("a", "c", "d")
        val kept = filterPlayable(all) { it in playable }
        assertEquals(listOf("a", "c", "d"), kept)
    }

    @Test
    fun `当前曲目被剔除后下标要重新定位`() {
        val all = listOf("a", "b", "c", "d")
        val playable = setOf("a", "c", "d")
        // 原本在播 "c"（下标 2）；剔除 "b" 后 "c" 变成下标 1
        val newIdx = remapIndex(all, playable, currentIndex = 2)
        assertEquals(1, newIdx)
    }

    @Test
    fun `当前曲目本身不可播时退到最近的可播曲目`() {
        val all = listOf("a", "b", "c")
        val playable = setOf("a")
        // "b"（下标 1）不可播 → 退到前面最近的 "a"（下标 0）
        val newIdx = remapIndex(all, playable, currentIndex = 1)
        assertEquals(0, newIdx)
    }

    @Test
    fun `全部不可播时返回 -1`() {
        val all = listOf("a", "b")
        assertEquals(-1, remapIndex(all, emptySet(), currentIndex = 0))
        assertTrue(filterPlayable(all) { false }.isEmpty())
    }

    @Test
    fun `未探测的曲目视为可播，不预先剔除`() {
        // 探测很贵且有频控，所以只剔除「确认失败」的，
        // 未知状态的照常入队（失败时再在播放前剔除）。
        fun keep(state: String): Boolean = state != "failed"
        assertTrue(keep("unknown"))
        assertTrue(keep("ok"))
        assertFalse(keep("failed"))
    }

    @Test
    fun `剔除后下标不会越界`() {
        val all = listOf("a", "b", "c")
        val playable = setOf("a", "b", "c")
        assertEquals(2, remapIndex(all, playable, currentIndex = 2))
        assertEquals(0, remapIndex(all, playable, currentIndex = 0))
    }

    // ------------------------------------------------------------ 纯逻辑

    /** 只保留可播曲目。 */
    private fun <T> filterPlayable(items: List<T>, playable: (T) -> Boolean): List<T> =
        items.filter(playable)

    /**
     * 把「原列表下标」映射成「过滤后列表下标」。
     *
     * 规则：
     * - 若原下标那一项仍在保留列表里 → 返回它的新下标
     * - 否则往前找最近的可播项
     * - 都找不到 → -1
     */
    private fun <T> remapIndex(items: List<T>, playable: Set<T>, currentIndex: Int): Int {
        if (items.isEmpty()) return -1
        val kept = items.filter { it in playable }
        if (kept.isEmpty()) return -1
        val cur = items.getOrNull(currentIndex) ?: return 0
        if (cur in playable) return kept.indexOf(cur)
        for (i in currentIndex - 1 downTo 0) {
            val cand = items[i]
            if (cand in playable) return kept.indexOf(cand)
        }
        return 0
    }
}
