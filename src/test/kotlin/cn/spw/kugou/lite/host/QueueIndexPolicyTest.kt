package cn.spw.kugou.lite.host

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * N13：队列重建的下标换算与 path 选择。
 */
class QueueIndexPolicyTest {

    // ------------------------------------------------ 下标换算

    @Test
    fun `没有过滤时下标不变`() {
        assertEquals(2, QueueIndexPolicy.remap(listOf(true, true, true, true), 2))
    }

    @Test
    fun `前面被过滤掉一条时下标前移`() {
        // 原下标 2（第 3 首），它前面第 0 条被丢掉 → 新下标 1
        assertEquals(1, QueueIndexPolicy.remap(listOf(false, true, true, true), 2))
    }

    @Test
    fun `前面被过滤掉多条时下标按实际保留数前移`() {
        assertEquals(1, QueueIndexPolicy.remap(listOf(false, false, true, true), 3))
    }

    @Test
    fun `当前项本身被过滤时落到后面第一首`() {
        // 原下标 1 被丢掉，它后面是原下标 2 → 新下标 1
        assertEquals(1, QueueIndexPolicy.remap(listOf(true, false, true), 1))
    }

    @Test
    fun `末尾项被过滤时不越界`() {
        assertEquals(1, QueueIndexPolicy.remap(listOf(true, true, false), 2))
    }

    @Test
    fun `全部被过滤时返回 0 不抛异常`() {
        assertEquals(0, QueueIndexPolicy.remap(listOf(false, false), 1))
    }

    // ------------------------------------------------ path 选择

    @Test
    fun `当前曲目的流式地址优先于缓存`() {
        // 流式播放：缓存为空，但宿主正在用 live 地址 —— 必须保住它
        assertEquals(
            "http://live/a.mp3",
            QueueIndexPolicy.pathFor(isCurrent = true, lastQueuedPath = "http://live/a.mp3", cachedPath = null),
        )
    }

    @Test
    fun `当前曲目没有 live 地址时退回缓存`() {
        assertEquals(
            "/cache/a.mp3",
            QueueIndexPolicy.pathFor(isCurrent = true, lastQueuedPath = "", cachedPath = "/cache/a.mp3"),
        )
    }

    @Test
    fun `非当前曲目只用缓存路径`() {
        assertEquals(
            "/cache/b.mp3",
            QueueIndexPolicy.pathFor(isCurrent = false, lastQueuedPath = "http://live/b.mp3", cachedPath = "/cache/b.mp3"),
        )
    }

    @Test
    fun `非当前曲目无缓存时为空串（等后台补）`() {
        assertEquals("", QueueIndexPolicy.pathFor(isCurrent = false, lastQueuedPath = null, cachedPath = null))
    }
}
