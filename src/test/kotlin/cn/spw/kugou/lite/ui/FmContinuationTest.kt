package cn.spw.kugou.lite.ui

import cn.spw.kugou.lite.sidecar.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 私人 FM 续播的追加逻辑（BUG-02）。
 *
 * ## 背景
 *
 * 旧实现把「点播放时捕获的第一批 5 首」闭包进了续拉回调，每次续拉都用
 * `initial + fresh` 重建宿主队列 —— 第二次续拉时第一批追加的歌**还没播完
 * 就被丢掉了**（宿主队列被整体替换成 initial + fresh2）。
 *
 * 修复：以 [cn.spw.kugou.lite.host.TrackWatcher] 当前登记队列为准
 * （它随每次追加/剔除同步更新），而不是最初捕获的批次。
 *
 * 这里的用例用假队列/假追加器模拟 TrackWatcher 的行为，
 * 连续触发两轮续拉，断言第二次传给宿主的完整队列包含全部三批。
 */
class FmContinuationTest {

    private fun song(hash: String) = Song(
        fileName = hash,
        title = "t-$hash",
        singer = "测试歌手",
        hash = hash,
        durationSec = 100,
        imageUrl = null,
        fileSize = 0,
        payType = 0,
        albumName = null,
    )

    /** 可连续出批的假批次源。 */
    private class BatchSource(var batches: List<List<Song>>) {
        private var next = 0
        fun take(): List<Song> = batches.getOrNull(next++) ?: emptyList()
    }

    @Test
    fun `连续两轮续拉不丢中间批次`() {
        val original = (1..5).map { song("k$it") }
        val fresh1 = (6..10).map { song("k$it") }
        val fresh2 = (11..15).map { song("k$it") }

        // 模拟 TrackWatcher 的登记队列：初始为点击播放时的第一批
        var registered = original.toList()
        var currentId = "kugou-k3" // 假设正在播第 3 首
        val hostAppends = mutableListOf<List<Song>>()

        val source = BatchSource(listOf(fresh1, fresh2))
        val cont = FmContinuation(
            loadBatch = { source.take() },
            snapshot = { registered },
            currentTrackId = { currentId },
            appendWatcher = { added -> registered = registered + added },
            appendHost = { all, _ -> hostAppends += all },
        )

        // 第一轮续拉（此时队列 = original，播到中段触发）
        cont.extend()
        // 第二轮续拉（此时队列 = original+fresh1，播到 fresh1 尾段触发）
        cont.extend()

        assertEquals(2, hostAppends.size)
        // BUG-02 的断言点：第二次重建宿主队列必须包含此前追加的 fresh1
        assertEquals(
            original + fresh1 + fresh2,
            hostAppends.last(),
            "第二次续拉重建的宿主队列丢失了之前追加的批次（BUG-02）",
        )
        // 登记队列同样要完整
        assertEquals(original + fresh1 + fresh2, registered)
    }

    @Test
    fun `本批全听过时不再追加`() {
        var registered = listOf(song("k1"))
        val hostAppends = mutableListOf<List<Song>>()
        val watcherAppends = mutableListOf<List<Song>>()

        val cont = FmContinuation(
            loadBatch = { emptyList() },
            snapshot = { registered },
            currentTrackId = { "" },
            appendWatcher = { watcherAppends += it },
            appendHost = { all, _ -> hostAppends += all },
        )

        val added = cont.extend()

        assertTrue(added.isEmpty(), "没有新歌时应返回空")
        assertEquals(0, watcherAppends.size, "没有新歌时不应登记追加")
        assertEquals(0, hostAppends.size, "没有新歌时不应重建宿主队列")
    }

    @Test
    fun `宿主追加携带当前播放曲目定位`() {
        val original = (1..3).map { song("k$it") }
        val fresh = (4..6).map { song("k$it") }
        var registered = original.toList()
        var receivedId = ""

        val cont = FmContinuation(
            loadBatch = { fresh },
            snapshot = { registered },
            currentTrackId = { "kugou-k2" },
            appendWatcher = { added -> registered = registered + added },
            appendHost = { _, cur -> receivedId = cur },
        )

        cont.extend()

        assertEquals("kugou-k2", receivedId, "重建宿主队列必须携带当前曲目 id 用于定位下标")
    }
}
