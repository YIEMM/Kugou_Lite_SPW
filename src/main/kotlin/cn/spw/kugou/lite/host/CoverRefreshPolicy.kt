package cn.spw.kugou.lite.host

/**
 * 「要不要让宿主重读封面」的判定。
 *
 * ## 为什么需要
 *
 * 用户反馈：播放某首歌时**没有封面、没有歌词**，但先放别的歌再回来放它就正常。
 *
 * 真机日志（2026-11-14 21:57 Overdose）表明写入是成功的：
 *
 * ```
 * 21:57:25.287 [cache] 已缓存 …-128.mp3（3080 KB）
 * 21:57:25.331 [id3]   已写入封面帧（80220 字节）
 * 21:57:25.339 [cache] 已写入逐字歌词（7782 字符）
 * 21:57:25.343 [play]  已重建音频源并播放「Overdose」
 * ```
 *
 * 而宿主的封面链路是：
 *
 * ```
 * PlaybackService.refreshCurrentCover → TagParser.readImage(path, w, h)
 * ```
 *
 * 它在切换曲目时读一次文件；如果那一刻 APIC 帧还没落盘（或读取早于写入
 * 完成），就拿不到封面，并停在 ♪ 占位 —— 直到下一次切歌才重读。
 * 这正是「第二次播放就正常」的成因。
 *
 * 参考实现 Salt-Extend-Online 的 `PlaybackCoverRefresh` 也是同样的结论：
 * 写完 artwork 后必须**在 UI 线程显式调 `refreshCurrentCover()`**。
 *
 * ## 只在真的写了元数据时刷新
 *
 * 命中完整缓存时不需要刷新，否则每次切歌都要重解码封面（浪费且会闪）。
 */
internal object CoverRefreshPolicy {

    /**
     * 本次切歌是否写入了封面/歌词（从而需要通知宿主重读）。
     */
    fun shouldRefresh(coverWritten: Boolean, lyricsWritten: Boolean): Boolean =
        coverWritten || lyricsWritten

    /**
     * 封面刷新的冷却。
     *
     * 写入后的短时间内多次切歌可能反复触发重读，导致封面闪烁；
     * 同一首曲目在 [cooldownMs] 内只刷新一次。
     */
    class Cooldown(private val cooldownMs: Long = 5_000L) {
        private var lastId: String? = null
        private var lastAtMs: Long = 0L

        fun shouldRefresh(trackId: String, nowMs: Long): Boolean {
            if (trackId == lastId && nowMs - lastAtMs < cooldownMs) return false
            lastId = trackId
            lastAtMs = nowMs
            return true
        }

        fun reset() {
            lastId = null
            lastAtMs = 0L
        }
    }
}
