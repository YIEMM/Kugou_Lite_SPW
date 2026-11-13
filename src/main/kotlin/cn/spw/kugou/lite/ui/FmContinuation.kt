package cn.spw.kugou.lite.ui

import cn.spw.kugou.lite.diag.Diag
import cn.spw.kugou.lite.host.PlaybackBridge
import cn.spw.kugou.lite.host.TrackWatcher
import cn.spw.kugou.lite.sidecar.KugouApi
import cn.spw.kugou.lite.sidecar.Song

/**
 * 私人 FM 续播的「追加一批」逻辑。
 *
 * ## 为什么要抽成类（BUG-02）
 *
 * 旧实现把续拉回调写成 `FmCard` 的内联 lambda，并且把**点击播放时的
 * 第一批 5 首**（`songs`）闭包了进去。续拉回调会被 [TrackWatcher] 触发
 * **多次**（队列剩余 ≤2 首就触发一次），而回调里重建宿主队列用的是：
 *
 * ```kotlin
 * PlaybackBridge.appendToQueue(allSongs = songs + fresh, …)   // ❌
 * ```
 *
 * 第二次触发时宿主队列被整体替换成 `original + fresh2`——
 * **第一批追加、尚未播完的歌全部消失**（BUG-02，用户反馈「FM 播着播着
 * 队列越续越短」）。
 *
 * 修复：以 [TrackWatcher] 的**当前登记队列**为准。它随每次
 * [TrackWatcher.appendToQueue] / [TrackWatcher.dropFromQueue] 同步更新，
 * 是唯一的队列真相源；宿主重建用 `snapshot()`，天然包含此前追加的批次。
 *
 * 依赖都走构造注入（默认指向真实单例），单测用假的 snapshot/append
 * 就能模拟「连续两轮续拉」，不需要真机播完整批。
 *
 * @param loadBatch 拉一批新歌（去重后的增量）
 * @param snapshot  当前登记队列（默认 [TrackWatcher.snapshot]）
 * @param currentTrackId 当前播放曲目 id（默认 [TrackWatcher.currentTrackId]）
 * @param appendWatcher 把新批次登记进切歌监听（默认 [TrackWatcher.appendToQueue]）
 * @param appendHost 用完整列表重建宿主播放队列（默认 [PlaybackBridge.appendToQueue]）
 */
internal class FmContinuation(
    private val loadBatch: () -> List<Song>,
    private val snapshot: () -> List<Song> = { TrackWatcher.snapshot() },
    private val currentTrackId: () -> String = { TrackWatcher.currentTrackId() },
    private val appendWatcher: (List<Song>) -> Unit = { TrackWatcher.appendToQueue(it) },
    private val appendHost: (List<Song>, String) -> Unit = { all, cur ->
        PlaybackBridge.appendToQueue(all, cur, resolveStream = { KugouApi.streamUrl(it) })
    },
) {

    /**
     * 触发一次续拉。
     *
     * @return 实际追加的曲目；空列表 = 没有新歌（全部听过/接口失败），不再续。
     */
    fun extend(): List<Song> {
        val fresh = loadBatch()
        if (fresh.isEmpty()) {
            Diag.info("[discover] FM 续播：本批全听过，停止续拉")
            return emptyList()
        }
        // 先登记（监听器要知道新歌、后续 snapshot 也要含它们），
        // 再用「当前登记队列」重建宿主队列 —— 顺序不能反。
        appendWatcher(fresh)
        appendHost(snapshot(), currentTrackId())
        Diag.info("[discover] FM 续播：追加 ${fresh.size} 首（登记队列共 ${snapshot().size} 首）")
        return fresh
    }
}
