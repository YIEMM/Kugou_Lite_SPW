package cn.spw.kugou.lite.host

import java.util.concurrent.ConcurrentHashMap

/**
 * 歌词文本的内存注册表 —— 宿主 [KugouLyricsExtension] 的数据源。
 *
 * ## 为什么必须走这条路
 *
 * 反汇编宿主 `PlaybackService$loadLyrics$2`：
 *
 * 1. 遍历所有 `PlaybackExtensionPoint`，调 `updateLyrics(MediaItem)` /
 *    `onBeforeLoadLyrics(MediaItem)`
 * 2. 任一返回非 null 字符串 → **直接当歌词用**，跳过后续文件读取
 * 3. 全返回 null 才回退 `TagParser.readLyrics(Vri(path))`
 *
 * 而 `TagParser.readLyrics` 只在 `PlaybackLyricsLoader.onLoaded` 时调用一次。
 * 我们的音频/歌词是在「检测到切歌」之后才下载写盘的，那一刻宿主已经读过
 * 并记住「无歌词」，之后不会重读 —— 所以补文件内容、原子写、内嵌 USLT 都
 * 无法根治（用户反馈「有几率无歌词，重新读取缓存就有」）。
 *
 * 走扩展点则完全绕开文件：歌词在内存里，宿主什么时候问都拿得到。
 */
object LyricsRegistry {

    /** trackId → 纯文本逐行 LRC（供宿主解析）。 */
    private val byId = ConcurrentHashMap<String, String>()

    /** 已确认「这首没有歌词」的 id，避免等待者空等到超时。 */
    private val noLyrics = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** 等待/唤醒用的监视器。 */
    private val monitor = Object()

    /**
     * 当前曲目 id。
     *
     * **仅供诊断/日志用，绝不参与歌词匹配**：用户反馈「歌词都是上一首的」
     * 就是因为它曾被当成兜底 —— 宿主问新歌歌词时新歌还没下载完，
     * 兜底拿到的是上一首，于是错歌词。
     */
    @Volatile
    var currentTrackId: String = ""

    /** 登记歌词；KRC 会转成纯文本逐行 LRC（不带逐字标记）。 */
    fun put(trackId: String, raw: String?) {
        if (trackId.isBlank() || raw.isNullOrBlank()) return
        val text = if (cn.spw.kugou.lite.sidecar.KrcLyrics.isKrc(raw)) {
            cn.spw.kugou.lite.sidecar.KrcLyrics.toPlainLrc(raw)
        } else raw
        if (text.isNotBlank()) {
            byId[trackId] = text
            noLyrics.remove(trackId)
            wakeAll()
        }
    }

    /**
     * 标记某曲目「确实没有歌词」，让等待者立即放弃而不是空等。
     *
     * 宿主扩展点在切歌时会阻塞等待；若接口明确返回空，必须尽快放行。
     */
    fun markNoLyrics(trackId: String) {
        if (trackId.isBlank()) return
        noLyrics.add(trackId)
        wakeAll()
    }

    /** 供扩展点查询。 */
    fun get(trackId: String): String? =
        if (trackId.isBlank()) null else byId[trackId]

    /**
     * 等待某个曲目的歌词就绪（最多 [timeoutMs]）。
     *
     * ## 为什么需要
     *
     * 真机日志（2026-11-15）：
     *
     *     12:34:23 宿主查询 id=kugou-4EA48315 命中=false 长度=0   ← 无歌词
     *     12:35:07 宿主查询 id=kugou-4EA48315 命中=true  长度=2583 ← 有歌词
     *
     * 宿主在切歌瞬间就问歌词，而歌词接口还在路上。宿主只在 onLoaded 时问
     * **一次**，问空就永远无歌词。所以这里短暂等待：歌词一登记立刻唤醒，
     * 超时则放弃（返回 null，让宿主回退读文件，不影响播放）。
     *
     * @return 歌词；超时或曲目无歌词返回 null
     */
    fun awaitLyrics(trackId: String, timeoutMs: Long): String? {
        if (trackId.isBlank()) return null
        byId[trackId]?.let { return it }

        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(monitor) {
            while (true) {
                byId[trackId]?.let { return it }
                // 已明确判定「这首没有歌词」就不必再等
                if (noLyrics.contains(trackId)) return null
                val remain = deadline - System.currentTimeMillis()
                if (remain <= 0L) return null
                runCatching { monitor.wait(remain) }
            }
        }
    }

    /** 唤醒所有等待者（登记/判定后调用）。 */
    private fun wakeAll() {
        synchronized(monitor) { monitor.notifyAll() }
    }

    /**
     * 当前曲目的歌词 —— **仅供测试/诊断**，不要在宿主查询路径上使用。
     *
     * 宿主问歌词时应当按它给的 id 精确查；用这个兜底会导致歌词错位。
     */
    internal fun current(): String? = get(currentTrackId)

    /** 队列换歌时更新当前曲目。 */
    fun markCurrent(trackId: String) {
        currentTrackId = trackId
    }
}
