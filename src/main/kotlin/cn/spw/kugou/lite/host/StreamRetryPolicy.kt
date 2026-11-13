package cn.spw.kugou.lite.host

/**
 * 切歌补流失败的重试策略（BUG-05）。
 *
 * ## 为什么需要它
 *
 * 补流失败时，失败曲目仍是宿主的「当前曲目」，而 `TrackWatcher.pollOnce`
 * 的 `currentId == previous` 早退意味着**永远不会再处理它** —— 用户听到
 * 持续静音，只能手动切歌。审计里写的是「30 秒内不重试」，实际是「永不重试」。
 *
 * 本类把重试判定抽成纯状态机，便于单测：
 *
 * - **延迟触发**：失败后等 `delayMs` 再试（立即重试大概率还是失败，
 *   且会打爆取流接口）；
 * - **次数上限**：`maxRetries` 次后放弃（放弃后由调用方打日志，
 *   等用户手动切歌；`exhausted()` 让调用方知道该清理/提示了）；
 * - **曲目作废**：当前曲目变成别的歌，旧的重试状态无意义。
 *
 * 全程不碰时钟与线程：时间由调用方（轮询循环）传入，状态只是普通字段。
 *
 * @param delayMs 失败到首次重试的间隔（之后每次失败同样顺延）
 * @param maxRetries 最多重试次数
 */
internal class StreamRetryPolicy(
    private val delayMs: Long = 5_000L,
    private val maxRetries: Int = 3,
) {

    /** 待重试的曲目 id；null = 无待重试。 */
    @Volatile
    private var pendingId: String? = null

    /** 下次允许重试的时间戳（毫秒）。 */
    @Volatile
    private var nextAtMs: Long = 0L

    /** 已执行的重试次数（针对 [pendingId]）。 */
    @Volatile
    private var attempted: Int = 0

    /** 补流失败时登记。同一曲目反复失败则只顺延时间、累计次数。 */
    fun onFailure(trackId: String, nowMs: Long) {
        if (pendingId != trackId) {
            pendingId = trackId
            attempted = 0
        }
        nextAtMs = nowMs + delayMs
    }

    /** 补流成功 / 登记新队列 / 停止监听时清除。 */
    fun onResolved() {
        pendingId = null
        nextAtMs = 0L
        attempted = 0
    }

    /**
     * 当前曲目此刻是否应重试。
     *
     * 只读判定（不自增次数）；调用方决定重试后须跟一次 [markAttempted]。
     */
    fun dueRetry(trackId: String, nowMs: Long): Boolean {
        val id = pendingId ?: return false
        if (id != trackId) return false
        if (nowMs < nextAtMs) return false
        return attempted < maxRetries
    }

    /** 记一次已发生的重试。 */
    fun markAttempted() {
        attempted++
    }

    /**
     * 重试次数是否已用尽（且冷却已过）。调用方据此打「放弃」日志并 [onResolved]。
     */
    fun exhausted(trackId: String, nowMs: Long): Boolean {
        val id = pendingId ?: return false
        if (id != trackId) return false
        if (nowMs < nextAtMs) return false
        return attempted >= maxRetries
    }

    /** 仅供测试观测：当前曲目已重试的次数。 */
    fun attemptCount(trackId: String): Int =
        if (pendingId == trackId) attempted else 0
}
