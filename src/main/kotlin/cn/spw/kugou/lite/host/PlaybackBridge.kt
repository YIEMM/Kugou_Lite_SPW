package cn.spw.kugou.lite.host

import cn.spw.kugou.lite.diag.Diag
import cn.spw.kugou.lite.sidecar.AudioCache
import cn.spw.kugou.lite.sidecar.KugouApi
import cn.spw.kugou.lite.sidecar.Song
import cn.spw.kugou.lite.sidecar.StreamInfo
import java.io.File
import java.lang.reflect.InvocationTargetException

/**
 * 把酷狗歌曲推进 Salt Player 的播放队列。
 *
 * ## 逆向依据（1.19.3 实测）
 *
 * 宿主播放链路用 `com.xuncorp.pisces.PiscesMediaItem` 表示一首歌，构造器是
 * 六参数全 `String`：
 *
 * ```
 * PiscesMediaItem(String, String, String, String, String, String)
 * ```
 *
 * 六个字段名被 R8 擦掉了（javap 只看到 `?`、LDC 是空串），所以语义是
 * 从**唯一调用方** `androidx/compose/ui/U+04F2` 的字节码反推的：
 *
 * ```
 * new PiscesMediaItem(
 *     Track.getId(),          // 1 id
 *     Track.getTitle(),       // 2 标题
 *     Track.getArtist(),      // 3 歌手
 *     Track.getAlbum(),       // 4 专辑
 *     Track.getAlbumArtist(), // 5 专辑艺术家
 *     <Track → Vri → String>  // 6 音频 URI  ← 这是播放地址
 * )
 * ```
 *
 * 六个参数的顺序就是这样，不要凭直觉改 —— 第 6 位才是 URI，
 * 把 URL 放到别的槽位会导致宿主拿到 `null` 解码器，表现为「点了没反应」。
 *
 * ## 入队方式
 *
 * `PlaybackController` 有两个重载：
 * - `setPlaybackQueue(PiscesMediaItem)`              单曲（我们用这个）
 * - `setPlaybackQueue(List<PiscesMediaItem>, int)`   整列表 + 起始索引
 *
 * 目前只做单曲试听（P4 范围），整列表入队留给后续。
 *
 * ## 线程
 *
 * 反射调用 + 取流都是阻塞操作，必须在 [cn.spw.kugou.lite.sidecar.ApiExec]
 * 的后台线程执行，绝不能在组合期调用。
 */
object PlaybackBridge {

    private const val TAG = "play"

/**
 * 建队列后，后台最多给「当前曲目之后」几首预取流地址。
 *
 * 不能对整条队列逐首发请求：ApiExec 只有 3 个线程，122 首会把线程池
 * 占满，用户新的点击只能排队（表现为「点了不播放」）；连发还会触发
 * 酷狗频控。更靠后的曲目由 TrackWatcher 切歌时按需补齐。
 */
private const val QUEUE_FILL_AHEAD = 3

    private const val CONTROLLER_CLASS = "com.xuncorp.voxzen.service.PlaybackController"
    private const val MEDIA_ITEM_CLASS = "com.xuncorp.pisces.PiscesMediaItem"
    private const val SOURCE_REGISTRY_CLASS = "com.xuncorp.voxzen.cast.MusicVideoAudioSourceRegistry"

    @Volatile
    private var controller: Any? = null

    @Volatile
    private var sourceRegistry: Any? = null

    @Volatile
    private var mediaItemCtor: java.lang.reflect.Constructor<*>? = null

    /** `updateMediaItems(PiscesMediaItem...)` —— 把曲目信息同步进队列。 */
    @Volatile
    private var updateMediaItemsMethod: java.lang.reflect.Method? = null

    /**
     * `setPlaybackAudioSource(String trackId, String path, long position, boolean playWhenReady)`
     * —— **切换音频源的正式入口**。
     */
    @Volatile
    private var setAudioSourceMethod: java.lang.reflect.Method? = null

    /** 兜底用：`setPlaybackQueue(List, int)`。 */
    @Volatile
    private var setQueueList: java.lang.reflect.Method? = null

    /** `pause()` —— 停旧音频，**不要**用 releaseAudioEngine()（会让下次静音）。 */
    @Volatile
    private var pauseMethod: java.lang.reflect.Method? = null

    /** `prepare()` —— 必须在 play() 之前调用。 */
    @Volatile
    private var prepareMethod: java.lang.reflect.Method? = null

    /** `play()`。 */
    @Volatile
    private var playMethod: java.lang.reflect.Method? = null

    /**
     * 每首曲目**上一次入队时**的 path（trackId → path）。
     *
     * 切歌时用它判断「宿主之前有没有机会加载这一项」：
     * 空串 = 当时是空占位，宿主加载必然失败 → 需要重建音频源。
     * 见 [TrackStartPolicy]。
     */
    private val lastQueuedPath = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** 主动启动播放的冷却，避免宿主状态抖动时反复打断刚起的播放。 */
    private val startCooldown = TrackStartPolicy.Cooldown()

    /**
     * `refreshCurrentCover()` —— 让宿主重新从本地文件读封面。
     *
     * 宿主的封面链路是 `PlaybackService.refreshCurrentCover`
     * → `TagParser.readImage(path)`，只在切歌时自动读一次。
     * 首次下载时我们写 ID3 之前它已经读过，于是停在 ♪ 占位，
     * 必须显式再触发一次。
     */
    @Volatile
    private var refreshCoverMethod: java.lang.reflect.Method? = null

    /** 封面刷新的冷却，避免连续切歌时反复重解码。 */
    private val coverCooldown = CoverRefreshPolicy.Cooldown()

    /**
     * 播放一首歌。
     *
     * ## 关键：宿主对「曲库外的 HTTP 曲目」经常**根本不去打开**
     *
     * 这来自 SPW_connect_Moe 网易云插件的实证注释：
     *
     * > Detects a selected HTTP item that the host never opens.
     * > **Position/Ready are not audio evidence.**
     *
     * 也就是说：
     * - 播放栏显示歌名 ✅
     * - 播控按钮出现 ✅
     * - SMTC 有曲目信息 ✅
     * - 但 `STATUS=Stopped`，宿主从未发起取流 ❌
     *
     * 这三项「看起来在播」的信号**都不能证明真的在播**。唯一可信的证据是
     * 「宿主有没有向音频地址发 HTTP 请求」。
     *
     * ## 所以走本地文件
     *
     * 网易云插件的做法是：HTTP 直链入队 → 等一段时间 → 若宿主没来拉流，
     * 就把音频**完整下载成临时文件**，用本地绝对路径重新入队。本地文件路径
     * 是宿主最成熟的播放通路，不依赖任何外部音源机制。
     *
     * 本实现直接采用后者（少一次失败的等待）：
     *
     * ```
     * 1. 下载音频到 <插件数据目录>/audio-cache/<id>.mp3
     * 2. 用本地绝对路径构造 PiscesMediaItem
     * 3. updateMediaItems → setPlaybackAudioSource → prepare → play
     * ```
     *
     * @param stream 已取到的播放信息（含 CDN 直链）
     * @param localFile 已下载到本地的音频文件；取流成功后由调用方准备
     * @return 成功返回 true；失败返回 false 并写诊断日志。
     */
    fun play(song: Song, stream: StreamInfo, localFile: File?): Boolean {
        return try {
            val ctrl = ensureController() ?: return false
            val trackId = buildTrackId(song)

            // 优先用本地文件：宿主对本地路径的播放通路最可靠。
            val path = localFile?.absolutePath ?: stream.url
            val usingLocal = localFile != null && localFile.isFile && localFile.length() > 0

            val item = newMediaItem(song, trackId, path) ?: return false

            // 1) 先停旧音频（不要用 releaseAudioEngine，会让下次静音）
            runCatching { pauseMethod?.invoke(ctrl) }

            // 2) **先建队列**，再更新项。
            //
            // 网易云插件的 LocalPlaybackBridge 注释写着
            // 「Uses the host's queued UpdateMediaItems command:
            //   **no queue replacement** or database writes」——
            // 意思是 `updateMediaItems` 只**更新已有队列项**，它自己不会建队列。
            //
            // 早期实现跳过建队列直接 updateMediaItems，结果队列里没有这一项，
            // 更新静默无效：播放栏和 SMTC 一直显示上一次的曲目（实测
            // ARTIST 停在旧的 trackId、ALBUM 停在旧 URL），状态 Stopped。
            setQueueList?.invoke(ctrl, listOf(item), 0)

            // 3) 曲目信息同步进队列（变长参数，反射要传该参数类型的数组）
            updateMediaItemsMethod?.let { m ->
                val paramType = m.parameterTypes[0]
                val arr = java.lang.reflect.Array.newInstance(paramType.componentType, 1)
                java.lang.reflect.Array.set(arr, 0, item)
                m.invoke(ctrl, arr)
            }

            // 4) 切换音频源
            val ok = if (setAudioSourceMethod != null) {
                setAudioSourceMethod!!.invoke(ctrl, trackId, path, 0L, true)
                true
            } else {
                false
            }

            // 5) 准备并播放
            prepareMethod?.invoke(ctrl)
            playMethod?.invoke(ctrl)

            Diag.info(
                TAG,
                "已播放「${song.title}」${if (usingLocal) "本地文件" else "HTTP 直链"}" +
                    " ${stream.qualityText}（${stream.bitRate / 1000}kbps）" +
                    if (ok) "" else "［降级：无 setPlaybackAudioSource］",
            )
            true
        } catch (t: Throwable) {
            val cause = (t as? InvocationTargetException)?.targetException ?: t
            Diag.warn(TAG, "播放失败：${cause.javaClass.simpleName}: ${cause.message}")
            false
        }
    }

    /**
     * 播放整个列表（歌单 / 榜单 / 每日推荐）。
     *
     * ## 关键：队列里**每一项**都要有可用的 path
     *
     * 宿主在以下时机都会按「队列项的 path」去加载音频与元数据
     * （封面走 `TagParser.readImage(path)`，歌词走同名 `.lrc`）：
     *
     * - 切歌
     * - 拖动进度条
     * - 切回上一首
     * - 播放结束时自动进下一首
     *
     * 早期实现只有当前曲目有真实 path，其余是空占位，于是：
     * - 切过去 → 找不到文件 → 弹「未载入歌曲」
     * - 封面/歌词读不到 → 播放栏一直是 ♪ 占位、没有逐字歌词
     *
     * 现在的做法：**为整个队列预先取流并缓存**（后台串行做，
     * 命中缓存时几乎零开销）。这样每一项的 path 都是真实本地文件，
     * 宿主在任何时机都能正常加载。
     *
     * ## 为什么不在主线程全量下载
     *
     * 一个 50 首的榜单会下载几百 MB。所以：
     * - 当前曲目**同步**保证可用（调用方已准备好）再建队列
     * - 其余曲目**异步**在后台逐个补齐，并更新队列项
     *
     * @param songs 整个列表
     * @param startIndex 从第几首开始播
     * @param resolveStream 取流函数
     * @param localFile 已下载好的当前曲目（可为 null）
     * @return 成功返回 true
     */
    fun playQueue(
        songs: List<Song>,
        startIndex: Int,
        resolveStream: (Song) -> StreamInfo?,
        localFile: File?,
    ): Boolean {
        if (songs.isEmpty()) return false
        val idx = startIndex.coerceIn(0, songs.lastIndex)
        val ctrl = ensureController() ?: return false
        return try {
            // 当前曲目：优先用调用方准备好的本地文件，否则现取
            val currentSong = songs[idx]
            val currentPath = localFile?.absolutePath
                ?: resolveStream(currentSong)?.url
                ?: return false

            // 其余曲目：先用已知的本地缓存路径（若是之前播过的），
            // 没有就让后台补 path。
            //
            // ⚠️ 这里**不做**预探测：逐首发取流会触发酷狗频控，
            // 把本来能播的歌误判为不可播。真正取不到地址的曲目，
            // 由 fillQueuePathsAsync 在后台补齐失败时从队列剔除
            // （见 removeFromQueue）—— 只在真实失败后处理，不预先猜测。
            val items = songs.mapIndexed { i, s ->
                val trackId = buildTrackId(s)
                val path = if (i == idx) currentPath else cachedLocalPath(s) ?: ""
                newMediaItem(s, trackId, path)
            }.filterNotNull()

            if (items.isEmpty()) return false

            runCatching { pauseMethod?.invoke(ctrl) }

            // **一次性建整条队列，当前项的 path 已经是正确的**，
            // 不再额外 updateMediaItems / setPlaybackAudioSource。
            //
            // 早期实现在 setQueueList 之后又调了一次
            // updateItem + setAudioSource + prepare + play，
            // 导致同一首歌被加载两次：宿主开始播 0.3 秒后，
            // 第二次加载把它重置回 0 秒重新开始 —— 就是用户说的「抖动」。
            setQueueList?.invoke(ctrl, items, idx)

            // 记录每项入队时的 path：切歌时用它判断宿主此前能否加载成功
            // （空占位 = 加载必然失败 → 需要重建音频源）。见 TrackStartPolicy。
            songs.forEachIndexed { i, s ->
                lastQueuedPath[buildTrackId(s)] =
                    if (i == idx) currentPath else (cachedLocalPath(s) ?: "")
            }
            // 新队列 = 新上下文，冷却作废
            startCooldown.reset()

            prepareMethod?.invoke(ctrl)
            playMethod?.invoke(ctrl)

            Diag.info(TAG, "已建立播放队列：${items.size} 首，从第 ${idx + 1} 首「${currentSong.title}」开始")

            // 后台补齐整个队列的 path（命中缓存时几乎零开销）
            fillQueuePathsAsync(ctrl, songs, idx, resolveStream)
            true
        } catch (t: Throwable) {
            val cause = (t as? InvocationTargetException)?.targetException ?: t
            Diag.warn(TAG, "建立播放队列失败：${cause.javaClass.simpleName}: ${cause.message}")
            false
        }
    }

    /**
     * 把新歌**追加**到当前播放队列末尾，不打断正在播放的曲目。
     *
     * ## 实现方式（用户反馈 #2 / #4）
     *
     * 宿主的 `setPlaybackQueue` 是**整体替换**语义，没有单独的 append。
     * 所以做法是：
     * 1. 读出当前正在播的曲目 id，在新列表里找到它的下标
     * 2. 用「原队列 + 新歌」重建整条队列，起始下标 = 当前曲目下标
     * 3. 重建后**不调 play()**（保持播放状态），只补齐新歌的 path
     *
     * ## 为什么不直接调 setQueueList + play
     *
     * 调用 `play()` 会让宿主从头播当前曲目 —— 用户会听到正在放的歌跳回
     * 开头。实测 `setPlaybackQueue` 只重建队列、不改变播放状态，
     * 所以只调它（+ prepare）即可。
     *
     * @param allSongs 追加后的完整列表（顺序 = 期望的播放顺序）
     * @param currentTrackId 当前正在播放的曲目 id（用它定位下标）
     * @return true = 追加成功
     */
    fun appendToQueue(
        allSongs: List<Song>,
        currentTrackId: String,
        resolveStream: (Song) -> StreamInfo?,
    ): Boolean {
        if (allSongs.isEmpty()) return false
        val ctrl = ensureController() ?: return false
        return try {
            val idx = allSongs.indexOfFirst { buildTrackId(it) == currentTrackId }
                .takeIf { it >= 0 } ?: 0

            // 用已缓存的本地路径建项；没缓存的新歌留空由后台补
            val items = allSongs.mapIndexed { i, s ->
                val path = if (i == idx) cachedLocalPath(s) ?: "" else cachedLocalPath(s) ?: ""
                newMediaItem(s, buildTrackId(s), path)
            }.filterNotNull()

            if (items.isEmpty()) return false

            setQueueList?.invoke(ctrl, items, idx)
            Diag.info(TAG, "已追加队列：共 ${items.size} 首，当前仍为第 ${idx + 1} 首「${allSongs[idx].title}」")

            // 只补新歌的 path，跳过正在播的那首（updateMediaItems 会打断播放）
            fillQueuePathsAsync(ctrl, allSongs, idx, resolveStream)
            true
        } catch (t: Throwable) {
            val cause = (t as? InvocationTargetException)?.targetException ?: t
            Diag.warn(TAG, "追加队列失败：${cause.javaClass.simpleName}: ${cause.message}")
            false
        }
    }

    /**
     * 后台补齐队列里每一首的 path。
     *
     * 串行执行，避免同时下载多首把带宽和磁盘打满。
     * 每补好一首就 `updateMediaItems` 更新队列项，宿主马上就能加载
     * 它的封面与歌词。
     *
     * ## 为什么跳过两首
     *
     * - `skipIndex`（当前正在播的）：**绝不能更新**，`updateMediaItems`
     *   会打断正在播放的音频（表现为播放几百毫秒后被重置回 0 秒）。
     * - 已经命中缓存的：path 本来就正确，不需要更新。
     */
    private fun fillQueuePathsAsync(
        ctrl: Any,
        songs: List<Song>,
        skipIndex: Int,
        resolveStream: (Song) -> StreamInfo?,
    ) {
        Thread({
            // ⚠️ 只补「当前曲目之后最近几首」，不要对整条队列逐首发请求。
            //
            // 用户反馈「点击之后不会直接播放」：早期版本后台对 122 首里
            // 每一首未缓存的都发取流请求，而 ApiExec 只有 3 个线程，
            // 很快被占满，用户新的点击只能排队 → 看起来点了没反应。
            // 另外连发还会触发酷狗频控。
            //
            // 真正取不到地址的曲目由 removeFromQueue 在失败时剔除，
            // 但只对这几首做，避免打爆线程池。
            val from = (skipIndex + 1).coerceAtMost(songs.size)
            val to = (from + QUEUE_FILL_AHEAD).coerceAtMost(songs.size)
            val dead = ArrayList<Song>()
            for (i in from until to) {
                val s = songs[i]
                // 已命中本地缓存的曲目 path 本来就正确，不再重复取流下载。
                if (cachedLocalPath(s) != null) continue
                try {
                    val stream = KugouApi.streamUrl(s, allowFallback = false)
                    if (stream == null) {
                        dead.add(s)
                        Diag.info(TAG, "队列剔除「${s.title}」：${KugouApi.lastError ?: "取流失败"}")
                        continue
                    }
                    // 先取歌词，再让 AudioCache 一并补齐（音频 + 封面 + 逐字歌词）
                    val lrc = runCatching { KugouApi.lyricByHash(s.hash, s.title, s.singer) }.getOrNull()
                    val local = AudioCache.ensureLocal(s, stream, lrc)
                    val path = local?.absolutePath ?: stream.url
                    newMediaItem(s, buildTrackId(s), path)?.let { updateItem(ctrl, it) }
                    lastQueuedPath[buildTrackId(s)] = path
                    Diag.info(TAG, "队列补齐 ${i + 1}/${songs.size}「${s.title}」")
                } catch (t: Throwable) {
                    dead.add(s)
                    Diag.warn(TAG, "队列补齐失败「${s.title}」：${t.javaClass.simpleName}")
                }
            }
            if (dead.isNotEmpty()) {
                removeFromQueue(ctrl, dead)
            }
            Diag.info(TAG, "队列 path 补齐完成（本次 ${to - from} 首，剔除 ${dead.size} 首不可播）")
        }, "kugou-lite-queue-fill").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    /**
     * 把不可播的曲目从宿主队列里移除（用户反馈的「卡住」问题）。
     *
     * 宿主 `setPlaybackQueue` 是整体替换语义，所以做法是：
     * 读出当前队列（我们维护的 `queueList`），去掉 dead 项后重建，
     * 并保持当前曲目的下标。
     */
    private fun removeFromQueue(ctrl: Any, dead: List<Song>) {
        val deadIds = dead.map { buildTrackId(it) }.toHashSet()
        val current = TrackWatcher.currentTrackId()
        // 同步剔除监听器里登记的队列，否则它的下标会错位
        val remaining = TrackWatcher.dropFromQueue(dead)
        if (remaining.isEmpty()) return
        if (remaining.none { buildTrackId(it) !in deadIds }) return
        val newIdx = remaining.indexOfFirst { buildTrackId(it) == current }.takeIf { it >= 0 } ?: 0
        runCatching {
            val items = remaining.mapNotNull { s ->
                newMediaItem(s, buildTrackId(s), cachedLocalPath(s) ?: "")
            }
            if (items.isEmpty()) return@runCatching
            setQueueList?.invoke(ctrl, items, newIdx)
            prepareMethod?.invoke(ctrl)
            Diag.info(TAG, "已从队列移除 ${dead.size} 首不可播曲目，剩 ${items.size} 首")
        }.onFailure { Diag.warn(TAG, "移除不可播曲目失败：${it.message}") }
    }

    /** 已缓存音频的本地路径；没有返回 null。 */
    private fun cachedLocalPath(song: Song): String? {
        val f = AudioCache.localFileOrNull(song) ?: return null
        return f.absolutePath
    }

    /** 更新队列里的某一项。 */
    private fun updateItem(ctrl: Any, item: Any) {
        updateMediaItemsMethod?.let { m ->
            val paramType = m.parameterTypes[0]
            val arr = java.lang.reflect.Array.newInstance(paramType.componentType, 1)
            java.lang.reflect.Array.set(arr, 0, item)
            m.invoke(ctrl, arr)
        }
    }

    /**
     * 切歌后确保新曲目的音频源可用。
     *
     * ## 只更新队列项，不强推播放
     *
     * 宿主自己的切歌流程已经会加载队列里下一项的 path。我们只需要
     * 保证那一项的 path 是有效的本地文件即可。
     *
     * 早期实现在这里又调了 `setPlaybackAudioSource + prepare + play`，
     * 与宿主自己的加载叠加，导致：
     * - 播放几百毫秒后被重置回 0 秒（用户反馈的「抖动」）
     * - 或直接打断播放
     *
     * 所以现在：
     * - path 已正确（命中缓存）→ 什么都不做
     * - path 需要更新 → 只 `updateMediaItems`，让宿主按新 path 加载
     */
    fun rebindCurrent(song: Song, stream: StreamInfo, localFile: File?): Boolean {
        val ctrl = controller ?: ensureController() ?: return false
        val trackId = buildTrackId(song)
        val path = localFile?.absolutePath
            ?: stream.url.takeIf { it.isNotBlank() }
            ?: run {
                Diag.warn(TAG, "重新绑定失败：既无本地文件也无流地址「${song.title}」")
                return false
            }

        // 切歌发生时，这一项在宿主队列里原本有没有可用地址？
        // 这决定了下面的启动方式（见 TrackStartPolicy 的注释表格）。
        val hadValidPath = lastQueuedPath[trackId].orEmpty().isNotBlank()

        return try {
            val item = newMediaItem(song, trackId, path)
            if (item != null) {
                updateItem(ctrl, item)
                lastQueuedPath[trackId] = path
                Diag.info(TAG, "已更新队列项「${song.title}」path=${if (localFile != null) "本地" else "HTTP"}")
            }

            // ---------------------------------------------------------------
            // 关键修复：updateMediaItems 只改元数据，**不会启动播放**。
            //
            // 真机证据（2026-11-14 21:09）：点「下一首」后这里只更新了队列项，
            // 播放栏停在 ♪ 占位、▶ 未播放、进度 0 秒。宿主的 next() 只移动索引，
            // 不会因为队列项的 path 变成有效文件就自动加载音频。
            //
            // 参考实现 Salt-Extend-Online 的 SaltPlaybackStartBindings 同样在
            // 加载完成后补一次 play()，印证这是宿主侧共性行为。
            // ---------------------------------------------------------------
            ensurePlaying(ctrl, song, trackId, path, hadValidPath)

            // 若刚才真的写了封面/歌词，宿主的播放条还停在旧状态（或 ♪ 占位），
            // 必须让它重读一次文件 —— 否则表现为「第一次播放没封面没歌词，
            // 切一下别的歌再回来才正常」。判定见 CoverRefreshPolicy。
            maybeRefreshCover(ctrl, song, trackId)
            true
        } catch (t: Throwable) {
            val cause = (t as? InvocationTargetException)?.targetException ?: t
            Diag.warn(TAG, "更新队列项失败：${cause.javaClass.simpleName}: ${cause.message}")
            false
        }
    }

    /**
     * 元数据刚写入时，让宿主重新读取封面（与歌词）。
     *
     * ## 为什么必须显式触发
     *
     * 宿主的封面链路 `PlaybackService.refreshCurrentCover` →
     * `TagParser.readImage(path)` 只在**切歌**时自动跑一次。首次播放时
     * 我们才刚下载文件并写入 APIC 帧，宿主的读取早于写入完成，于是拿到
     * 空结果并停在 ♪ 占位 —— 这正是用户看到的「第一次没封面没歌词，
     * 先放别的歌再回来就正常」。
     *
     * 参考实现 Salt-Extend-Online 的 PlaybackCoverRefresh 也是同样做法：
     * 写完后在 **UI 线程**显式调 `refreshCurrentCover()`。
     *
     * ## 线程
     *
     * 宿主该方法会碰 Compose 状态，必须在 EDT（Swing 事件线程）执行。
     * 用 `SwingUtilities.invokeLater` 投递，失败静默降级（封面缺失
     * 不该影响播放）。
     */
    private fun maybeRefreshCover(ctrl: Any, song: Song, trackId: String) {
        val refresh = refreshCoverMethod ?: return
        val writeAt = AudioCache.lastMetadataWriteAt
        if (writeAt <= 0L) return
        // 只在「刚刚写过」时才刷新（10 秒窗口内），避免陈旧写入误触发
        if (System.currentTimeMillis() - writeAt > 10_000L) return
        if (!coverCooldown.shouldRefresh(trackId, System.currentTimeMillis())) return

        runCatching {
            javax.swing.SwingUtilities.invokeLater {
                runCatching {
                    refresh.invoke(ctrl)
                    Diag.info(TAG, "已请求宿主重读封面「${song.title}」")
                }.onFailure {
                    Diag.warn(TAG, "刷新封面失败（不影响播放）：${it.message}")
                }
            }
        }.onFailure {
            Diag.warn(TAG, "投递封面刷新失败：${it.message}")
        }
    }

    /**
     * 切歌后确保音频真的开始播放。
     *
     * 分两种动作（判定见 [TrackStartPolicy]）：
     * - [TrackStartPolicy.Action.RebindAndPlay]：path 之前是空占位，
     *   宿主加载必然失败 → 重建音频源 + prepare + play；
     * - [TrackStartPolicy.Action.PlayOnly]：path 一直有效，宿主已加载 →
     *   只补 play()，避免重复加载导致「播 0.3 秒跳回 0 秒」的抖动。
     *
     * 同一曲目有冷却（3 秒）防止宿主状态抖动时反复打断刚起的播放。
     */
    private fun ensurePlaying(
        ctrl: Any,
        song: Song,
        trackId: String,
        path: String,
        hadValidPath: Boolean,
    ) {
        val action = TrackStartPolicy.decide(hadValidPath, hasPlayablePath = path.isNotBlank())
        if (action == TrackStartPolicy.Action.None) return
        if (!startCooldown.shouldStart(trackId, System.currentTimeMillis())) {
            Diag.info(TAG, "跳过重复启动「${song.title}」（同一首冷却中）")
            return
        }
        when (action) {
            TrackStartPolicy.Action.None -> Unit
            TrackStartPolicy.Action.PlayOnly -> {
                runCatching { prepareMethod?.invoke(ctrl) }
                runCatching { playMethod?.invoke(ctrl) }
                Diag.info(TAG, "已补启动播放「${song.title}」（path 原本有效，不重载）")
            }
            TrackStartPolicy.Action.RebindAndPlay -> {
                runCatching { pauseMethod?.invoke(ctrl) }
                setAudioSourceMethod?.invoke(ctrl, trackId, path, 0L, true)
                runCatching { prepareMethod?.invoke(ctrl) }
                runCatching { playMethod?.invoke(ctrl) }
                Diag.info(TAG, "已重建音频源并播放「${song.title}」")
            }
        }
    }

    /**
     * 生成稳定的曲目 id。
     *
     * 网易云插件用 `netease-<数字 id>` 这种带前缀的形式，宿主内部似乎会按 id
     * 判断曲目归属。这里用 `kugou-<hash 或 albumAudioId>` 保持同样风格。
     * 前缀还能避免与宿主本地曲库的 id 撞车。
     */
    private fun buildTrackId(song: Song): String {
        val raw = song.hash.ifBlank { song.albumAudioId }
        return "kugou-$raw"
    }

    /**
     * 当前播放的曲目是否属于 [songs] 这批。
     *
     * 供详情页判断「用户是否正在播这个歌单」—— 只有是的时候才该在
     * 翻页加载后同步宿主播放队列，否则浏览歌单会打断正在播的别的歌。
     */
    fun isPlayingFrom(songs: List<Song>): Boolean {
        val cur = TrackWatcher.currentTrackId()
        if (cur.isBlank()) return false
        return songs.any { buildTrackId(it) == cur }
    }

    /**
     * 登记外部音频源。
     *
     * `MusicVideoAudioSourceRegistry.activate(String trackId, String location)`：
     * - trackId —— 与 [PiscesMediaItem] 第 1 个参数一致
     * - location —— 与第 6 个参数（URI）一致，宿主据此直接播放该地址
     *
     * 失败不致命：宿主会回退到自己的曲库查找（对本地曲库有效）。
     */
    private fun activateSource(trackId: String, location: String) {
        try {
            val registry = sourceRegistry ?: run {
                val type = Class.forName(SOURCE_REGISTRY_CLASS, true, javaClass.classLoader)
                val inst = type.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
                sourceRegistry = inst
                inst
            }
            val activate = registry.javaClass.methods.firstOrNull {
                it.name == "activate" && it.parameterCount == 2
            } ?: run {
                Diag.warn(TAG, "找不到 activate(trackId, location)")
                return
            }
            activate.invoke(registry, trackId, location)
            Diag.info(TAG, "已登记音频源 trackId=${trackId.take(16)}… location=${location.take(48)}…")
        } catch (t: Throwable) {
            val cause = (t as? InvocationTargetException)?.targetException ?: t
            Diag.warn(TAG, "登记音频源失败（不影响本地曲库播放）：${cause.javaClass.simpleName}")
        }
    }

    /** 清掉登记的外部音频源。离开页面时调用，避免影响宿主自己的播放。 */
    fun clearSource() {
        runCatching {
            sourceRegistry?.javaClass?.methods
                ?.firstOrNull { it.name == "clear" && it.parameterCount == 0 }
                ?.invoke(sourceRegistry)
        }
    }

    // ---------------------------------------------------------------- 反射

    private fun ensureController(): Any? {
        controller?.let { return it }

        val ctrl = try {
            val type = Class.forName(CONTROLLER_CLASS, true, javaClass.classLoader)
            // Kotlin object：INSTANCE 静态字段
            val instanceField = type.getDeclaredField("INSTANCE").apply { isAccessible = true }
            instanceField.get(null)
        } catch (e: Throwable) {
            Diag.warn(TAG, "找不到 PlaybackController：${e.javaClass.simpleName}: ${e.message}")
            return null
        }

        val queueList = ctrl.javaClass.methods.firstOrNull {
            it.name == "setPlaybackQueue" && it.parameterCount == 2
        }
        queueList?.isAccessible = true

        val updateItems = ctrl.javaClass.methods.firstOrNull {
            it.name == "updateMediaItems" && it.parameterCount == 1 &&
                it.parameterTypes[0].isArray
        }
        updateItems?.isAccessible = true

        val setSource = ctrl.javaClass.methods.firstOrNull {
            it.name == "setPlaybackAudioSource" && it.parameterCount == 4
        }
        setSource?.isAccessible = true

        // 封面刷新：宿主的封面链路只在切歌时读一次文件，
        // 首次下载/写 ID3 时读到的是「还没有封面」的版本，于是停在 ♪ 占位。
        // 参考实现 Salt-Extend-Online 的 PlaybackCoverRefresh 同样在写入后
        // 显式调 refreshCurrentCover()。
        val refreshCover = ctrl.javaClass.methods.firstOrNull {
            it.name == "refreshCurrentCover" && it.parameterCount == 0
        }
        refreshCover?.isAccessible = true
        refreshCoverMethod = refreshCover

        val pause = ctrl.javaClass.methods.firstOrNull {
            it.name == "pause" && it.parameterCount == 0
        }
        val prepare = ctrl.javaClass.methods.firstOrNull {
            it.name == "prepare" && it.parameterCount == 0
        }
        val play = ctrl.javaClass.methods.firstOrNull {
            it.name == "play" && it.parameterCount == 0
        }
        if (prepare == null || play == null) {
            Diag.warn(TAG, "PlaybackController 上找不到 prepare()/play()")
            return null
        }
        pause?.isAccessible = true
        prepare.isAccessible = true
        play.isAccessible = true

        updateMediaItemsMethod = updateItems
        setAudioSourceMethod = setSource
        setQueueList = queueList
        pauseMethod = pause
        prepareMethod = prepare
        playMethod = play
        controller = ctrl

        Diag.info(
            TAG,
            "已解析播放控制器：setPlaybackAudioSource=${setSource != null} " +
                "updateMediaItems=${updateItems != null} pause=${pause != null} " +
                "refreshCurrentCover=${refreshCover != null}",
        )
        return ctrl
    }

    /**
     * 构造 `PiscesMediaItem`。
     *
     * ## 六个参数的语义（从常量池里的 toString 模板确认）
     *
     * ```
     * PiscesMediaItem(id='.', title='.', artist='.', album='.', albumArtist='.', path='.')
     * ```
     *
     * 六个字段依次是：**id / title / artist / album / albumArtist / path**。
     * 第 6 位 `path` 放音频地址。
     *
     * ## 为什么 artist 槽位传歌手而不是 trackId
     *
     * 早期为了迎合 `MusicVideoAudioSourceRegistry.matches` 的比对位置，
     * 曾把 trackId 塞进 artist 槽，结果播放栏副标题直接显示出 `967249675`
     * 这种内部 id —— UI 读的就是 artist 位。
     *
     * 现在的正确路径是 `setPlaybackAudioSource(trackId, path, ...)`，
     * trackId 通过**参数**单独传给宿主，不需要污染 artist 字段。
     */
    private fun newMediaItem(song: Song, trackId: String, path: String): Any? {
        val ctor = mediaItemCtor ?: run {
            val c = try {
                Class.forName(MEDIA_ITEM_CLASS, true, javaClass.classLoader)
                    .getDeclaredConstructor(
                        String::class.java, String::class.java, String::class.java,
                        String::class.java, String::class.java, String::class.java,
                    )
            } catch (e: Throwable) {
                Diag.warn(TAG, "找不到 PiscesMediaItem 六参构造器：${e.message}")
                return null
            }
            c.isAccessible = true
            mediaItemCtor = c
            c
        }

        return ctor.newInstance(
            trackId,                    // 1 id
            song.title,                 // 2 标题
            song.singer,                // 3 歌手（UI 显示副标题用这里）
            song.albumName ?: "",       // 4 专辑
            song.singer,                // 5 专辑艺术家
            path,                       // 6 path ← 音频地址（队列里可为空，切歌时补）
        )
    }
}
