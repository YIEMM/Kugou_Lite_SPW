package cn.spw.kugou.lite.host

/**
 * 切歌之后「要不要主动启动播放」的判定。
 *
 * ## 为什么需要它
 *
 * 宿主的 `next()` / `previous()` 只移动**队列索引**，并不会因为队列项的
 * path 变成了有效本地文件就自动加载音频。真机证据（2026-11-14 21:09）：
 *
 * ```
 * 21:09:11.372 [watch] 检测到切歌：「ハロ/ハワユ」
 * 21:09:11.380 [watch] 命中本地缓存，跳过取流
 * 21:09:11.385 [play]  已更新队列项「ハロ/ハワユ」path=本地
 * ```
 *
 * 之后没有任何音频活动：播放栏停在 ♪ 占位、▶ 未播放、进度 0 秒。
 * 即 `updateMediaItems` 只改元数据，**不触发加载与播放**。
 *
 * 参考实现 Salt-Extend-Online 的 `SaltPlaybackStartBindings` 在播放器
 * 加载完成回调里再 `play()` 一次，印证这是宿主侧共性行为。
 *
 * ## 为什么区分「重建」与「只补 play」
 *
 * 早期在 `setPlaybackQueue` 之后又 `setPlaybackAudioSource + prepare + play`，
 * 同一首被加载两次 → 播 0.3 秒后跳回 0 秒（用户说的「抖动」）。
 *
 * 所以只在宿主**没有机会成功加载**时才重建音频源：
 *
 * | 情形 | 宿主侧状态 | 动作 |
 * | --- | --- | --- |
 * | path 原来是空占位，现在补上了 | 加载过但失败 | [Action.RebindAndPlay] |
 * | path 一直是有效本地文件 | 已加载，只是未开播 | [Action.PlayOnly] |
 * | 仍无可用地址 | 无法播放 | [Action.None] |
 */
internal object TrackStartPolicy {

    /** 切歌后要让宿主做什么。 */
    enum class Action {
        /** 既无本地文件也无流地址：什么都做不了。 */
        None,

        /** 宿主已加载过该 path，只补一次 `play()`。 */
        PlayOnly,

        /** path 刚从空占位补齐：必须重建音频源再播放。 */
        RebindAndPlay,
    }

    /**
     * 决定本次切歌的启动动作。
     *
     * @param hadValidPath 切歌发生时，该曲目在宿主队列里**是否已经**有可用地址
     * @param hasPlayablePath 我们现在是否已经拿到了可用地址
     */
    fun decide(hadValidPath: Boolean, hasPlayablePath: Boolean = true): Action {
        if (!hasPlayablePath) return Action.None
        return if (hadValidPath) Action.PlayOnly else Action.RebindAndPlay
    }

    /**
     * 同一曲目的启动冷却。
     *
     * 宿主在切歌瞬间可能多次上报同一个 id（内部状态抖动、快速连点），
     * 每次启动都会打断刚开始的播放。冷却保证「一首歌只主动启动一次」。
     */
    class Cooldown(private val cooldownMs: Long = 3_000L) {
        private var lastId: String? = null
        private var lastAtMs: Long = 0L

        fun shouldStart(trackId: String, nowMs: Long): Boolean {
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
