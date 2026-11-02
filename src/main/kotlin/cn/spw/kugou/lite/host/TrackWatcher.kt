package cn.spw.kugou.lite.host

import cn.spw.kugou.lite.diag.Diag
import cn.spw.kugou.lite.sidecar.AudioCache
import cn.spw.kugou.lite.sidecar.Id3CoverWriter
import cn.spw.kugou.lite.sidecar.KugouApi
import cn.spw.kugou.lite.sidecar.Song
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 切歌监听：宿主切到队列里的下一首时，自动补上音频源。
 *
 * ## 为什么需要它
 *
 * 建队列时只能为**当前曲目**准备本地文件，队列里其余曲目的 `path` 是空占位。
 * 宿主切过去时没有音频源，表现为「切过去没声音」。
 *
 * ## 为什么用轮询而不是事件
 *
 * 官方的 `PlaybackExtensionPoint.onStateChanged(State)` 只给状态枚举
 * （Idle/Buffering/Ready/Ended），**拿不到当前曲目**。
 * 而 `PlaybackController.getPlaybackQueueState()` 返回的 StateFlow 需要
 * kotlinx-coroutines 收集 —— 插件没有 coroutines 编译期依赖（宿主自带但
 * 未声明 compileOnly），直接用会编译失败。
 *
 * 所以用**官方只读 API + 轮询**：
 * ```
 * WorkshopApi.playback().getCurrentMediaItem()   // 官方 API，返回当前曲目
 * ```
 * 每 700 ms 读一次，id 变化就认为切歌了。
 *
 * ## 为什么 id 前缀很重要
 *
 * 我们构造的曲目 id 是 `kugou-<hash>`。轮询时只处理这个前缀，
 * 避免干扰宿主自己播放本地曲库时的行为。
 */
object TrackWatcher {

    private const val TAG = "watch"

    /** 我们的曲目 id 前缀，见 [PlaybackBridge] 的 buildTrackId。 */
    private const val ID_PREFIX = "kugou-"

    private const val POLL_MS = 700L

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "kugou-lite-watch").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY + 1
        }
    }

    @Volatile
    private var running = false

    /** 上次处理过的曲目 id，用于判断是否真的切歌了。 */
    private val lastHandled = AtomicReference<String?>(null)

    /** 当前队列（id → Song），由 [updateQueue] 在建立队列时填充。 */
    @Volatile
    private var queueMap: Map<String, Song> = emptyMap()

    /**
     * 登记当前播放队列，供切歌时查表。
     *
     * @param songs 队列里的全部歌曲（顺序与建队列时一致）
     */
    fun updateQueue(songs: List<Song>) {
        queueMap = songs.associateBy { trackIdOf(it) }
        Diag.info(TAG, "已登记队列 ${queueMap.size} 首")
    }

    fun start() {
        if (running) return
        running = true
        executor.execute {
            Diag.info(TAG, "切歌监听已启动（轮询 ${POLL_MS}ms）")
            while (running) {
                try {
                    pollOnce()
                } catch (t: Throwable) {
                    // 监听线程绝不能抛出去，否则整个监听静默死掉
                    Diag.warn(TAG, "轮询异常：${t.javaClass.simpleName}: ${t.message}")
                }
                try {
                    Thread.sleep(POLL_MS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
    }

    fun stop() {
        running = false
        runCatching { executor.shutdownNow() }
        lastHandled.set(null)
        queueMap = emptyMap()
        Diag.info(TAG, "切歌监听已停止")
    }

    // ---------------------------------------------------------------- 内部

    private fun pollOnce() {
        val currentId = readCurrentTrackId() ?: return
        if (!currentId.startsWith(ID_PREFIX)) return

        val previous = lastHandled.get()
        if (currentId == previous) return

        // 首次拿到（例如刚建完队列）也要处理一次，确保当前曲目已绑定
        lastHandled.set(currentId)

        val song = queueMap[currentId] ?: run {
            Diag.warn(TAG, "切到未知曲目 $currentId（不在已登记队列里）")
            return
        }
        Diag.info(TAG, "检测到切歌：「${song.title}」")

        // 后台补音频源（本方法已在监听线程，直接同步做）
        val stream = KugouApi.streamUrl(song) ?: run {
            Diag.warn(TAG, "切歌补流失败：${KugouApi.lastError ?: "未知原因"}")
            return
        }
        val local = AudioCache.ensureLocal(song, stream)
        runCatching {
            val lrc = KugouApi.lyricByHash(song.hash)
            if (!lrc.isNullOrBlank() && local != null) {
                Id3CoverWriter.writeLyrics(local, lrc)
            }
        }
        PlaybackBridge.rebindCurrent(song, stream, local)
    }

    /**
     * 读当前播放曲目的 id。
     *
     * ## 两条路径
     *
     * 1. **首选**：`PlaybackController.getPlaybackQueueState()` 返回的
     *    `StateFlow<PlaybackQueueState>`，从中取当前索引对应的曲目 id。
     *    这是宿主内部真正维护的队列状态。
     * 2. **兜底**：官方 `WorkshopApi.playback().getCurrentMediaItem()`。
     *    实测它在很多时刻返回 null（宿主内部有缓存/生命周期问题），
     *    所以只作为补充。
     *
     * ## 为什么直接反射读 StateFlow 而不 collect
     *
     * 插件没有 kotlinx-coroutines 编译期依赖。但 `StateFlow.getValue()`
     * 是一个普通 getter，反射直接读当前值即可，不需要 collect。
     */
    private fun readCurrentTrackId(): String? {
        readFromQueueState()?.let { return it }
        return readFromWorkshopApi()
    }

    /** 路径 1：从 PlaybackController 的队列状态里取当前曲目。 */
    private fun readFromQueueState(): String? = try {
        val ctrlClass = Class.forName(
            "com.xuncorp.voxzen.service.PlaybackController", true, javaClass.classLoader,
        )
        val ctrl = ctrlClass.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
        val stateFlow = ctrlClass.getMethod("getPlaybackQueueState").apply { isAccessible = true }.invoke(ctrl)

        // StateFlow.getValue() —— 反射读当前值，无需 collect。
        // 注意：实现类是 kotlinx.coroutines.flow.ReadonlyStateFlow（包内可见），
        // 直接 invoke 会抛 IllegalAccessException，必须 setAccessible(true)。
        val valueMethod = stateFlow.javaClass.getMethod("getValue").apply { isAccessible = true }
        val state = valueMethod.invoke(stateFlow) ?: return null

        // PlaybackQueueState 有 6 个字段（两个 List、两个 int、一个 Mode、一个 boolean）。
        // 逐个试：找到「List 且元素数量与队列长度一致」的那个，再配对 int 索引。
        val fields = state.javaClass.declaredFields.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
        var currentList: List<*>? = null
        var currentIndex = -1

        for (f in fields) {
            f.isAccessible = true
            val v = runCatching { f.get(state) }.getOrNull()
            if (v is List<*> && v.isNotEmpty()) {
                // 取元素最多的那个 List 作为「完整队列」
                if (currentList == null || v.size > (currentList?.size ?: 0)) {
                    currentList = v
                }
            }
        }
        for (f in fields) {
            f.isAccessible = true
            val v = runCatching { f.get(state) }.getOrNull()
            if (v is Int) {
                // 索引必须落在列表范围内
                if (currentList != null && v >= 0 && v < currentList.size) {
                    currentIndex = v
                    break
                }
            }
        }

        val list = currentList ?: return null
        if (currentIndex < 0) return null

        val entry = list.getOrNull(currentIndex) ?: return null
        val id = extractItemId(entry)
        if (!reportedQueueShape) {
            reportedQueueShape = true
            Diag.info(
                TAG,
                "队列状态：listSize=${list.size} index=$currentIndex " +
                    "entry=${entry.javaClass.name.substringAfterLast('.')} id=$id",
            )
        }
        id
    } catch (t: Throwable) {
        val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
        if (!reportedQueueFailure) {
            reportedQueueFailure = true
            Diag.warn(TAG, "读队列状态失败：${cause.javaClass.simpleName}: ${cause.message}")
        }
        null
    }

    /**
     * 从队列条目里挖出 `PiscesMediaItem` 再取 id。
     *
     * 队列条目结构（实测 `com.xuncorp.spc.core.queue.Ϳ`）：
     * ```
     * class Ϳ<E>(val int, val E)   // E 就是 PiscesMediaItem
     * ```
     * 即「(位置索引, 曲目)」二元组。泛型擦除后 `E` 编译成 `Object`，
     * 所以按**字段类型**找对象，不能按名字（名字被混淆成 `?`）。
     *
     * `PiscesMediaItem` 的 getter 名字同样被混淆，但它的 `toString`
     * 模板是固定的 `PiscesMediaItem(id='...', title='...', ...)` ——
     * 这是最稳的取 id 方式（我们从常量池里确认过这个模板）。
     */
    private fun extractItemId(entry: Any, depth: Int = 0): String? {
        if (depth > 3) return null

        // 1) 本身是 PiscesMediaItem：从 toString 里抠 id
        runCatching {
            val s = entry.toString()
            if (s.startsWith("PiscesMediaItem(id='")) {
                val start = s.indexOf("id='") + 4
                val end = s.indexOf('\'', start)
                if (end > start) {
                    val id = s.substring(start, end)
                    if (id.isNotBlank()) return id
                }
            }
        }

        // 2) 否则遍历字段找内层对象（跳过基本类型与 String）
        for (f in entry.javaClass.declaredFields) {
            if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
            f.isAccessible = true
            val v = runCatching { f.get(entry) }.getOrNull() ?: continue
            if (v is String || v is Number || v is Boolean) continue
            extractItemId(v, depth + 1)?.let { return it }
        }
        return null
    }

    /** 路径 2：官方 API（常返回 null，仅作兜底）。 */
    private fun readFromWorkshopApi(): String? = try {
        val api = Class.forName("com.xuncorp.spw.workshop.api.WorkshopApi", true, javaClass.classLoader)
        val playback = api.getMethod("playback").apply { isAccessible = true }.invoke(null) ?: return null
        val item = playback.javaClass.getMethod("getCurrentMediaItem").apply { isAccessible = true }.invoke(playback) ?: return null
        item.javaClass.getMethod("getId").apply { isAccessible = true }.invoke(item) as? String
    } catch (t: Throwable) {
        null
    }

    // 只报一次，避免每 700ms 刷屏
    @Volatile private var reportedQueueFailure = false
    @Volatile private var reportedQueueShape = false

    private fun trackIdOf(song: Song): String =
        "$ID_PREFIX${song.hash.ifBlank { song.albumAudioId }}"
}
