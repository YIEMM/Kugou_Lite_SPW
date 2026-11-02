package cn.spw.kugou.lite.host

import cn.spw.kugou.lite.diag.Diag
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
     * ## 与单曲播放的区别
     *
     * 单曲用 `setPlaybackQueue(listOf(item), 0)` 建一条只有一首的队列；
     * 这里把**整个列表**一次性建进队列，之后宿主自己的「下一首」就能工作。
     *
     * ## 音频源的现实约束
     *
     * 宿主的音频源是按 trackId 登记的（`setPlaybackAudioSource`），
     * 一次只能指向一个地址。所以：
     * - **当前这首**用本地文件（保证立即出声）
     * - **其余曲目**先把 path 写成 HTTP 直链，切歌时由
     *   [cn.spw.kugou.lite.ui] 侧的「切歌监听」再补登记本地文件
     *
     * 这样队列是完整的、可翻页的，而不是「只能播一首」。
     *
     * @param songs 整个列表
     * @param startIndex 从第几首开始播
     * @param localFile 已下载好的当前曲目（可为 null）
     * @return 成功返回 true
     */
    fun playQueue(
        songs: List<Song>,
        startIndex: Int,
        streamOf: (Song) -> StreamInfo?,
        localFile: File?,
    ): Boolean {
        if (songs.isEmpty()) return false
        val idx = startIndex.coerceIn(0, songs.lastIndex)
        val ctrl = ensureController() ?: return false
        return try {
            // 为每首歌构造 MediaItem。只有起始曲目拿得到已下载的本地文件，
            // 其余用 HTTP 直链占位（切歌时再补）。
            val items = songs.mapIndexed { i, s ->
                val trackId = buildTrackId(s)
                val path = if (i == idx && localFile != null) {
                    localFile.absolutePath
                } else {
                    // 队列里的其他曲目：先放一个稳定的占位 path。
                    // 真正播放时由切歌监听替换成本地文件。
                    ""
                }
                newMediaItem(s, trackId, path)
            }.filterNotNull()

            if (items.isEmpty()) return false

            runCatching { pauseMethod?.invoke(ctrl) }

            // 一次性建整条队列
            setQueueList?.invoke(ctrl, items, idx)

            // 更新当前项的 path 为本地文件
            val current = items.getOrNull(idx) ?: return false
            updateMediaItemsMethod?.let { m ->
                val paramType = m.parameterTypes[0]
                val arr = java.lang.reflect.Array.newInstance(paramType.componentType, 1)
                java.lang.reflect.Array.set(arr, 0, current)
                m.invoke(ctrl, arr)
            }

            val song = songs[idx]
            val trackId = buildTrackId(song)
            val path = localFile?.absolutePath ?: streamOf(song)?.url ?: return false
            setAudioSourceMethod?.invoke(ctrl, trackId, path, 0L, true)

            prepareMethod?.invoke(ctrl)
            playMethod?.invoke(ctrl)

            Diag.info(TAG, "已建立播放队列：${items.size} 首，从第 ${idx + 1} 首「${song.title}」开始")
            true
        } catch (t: Throwable) {
            val cause = (t as? InvocationTargetException)?.targetException ?: t
            Diag.warn(TAG, "建立播放队列失败：${cause.javaClass.simpleName}: ${cause.message}")
            false
        }
    }

    /**
     * 切歌后重新登记音频源。
     *
     * 宿主切到队列里的下一首时，那首的 path 还是空占位，需要在这里
     * 取流 + 下载 + 重新登记。由 UI 侧在检测到曲目变化时调用。
     */
    fun rebindCurrent(song: Song, stream: StreamInfo, localFile: File?): Boolean {
        val ctrl = controller ?: ensureController() ?: return false
        val trackId = buildTrackId(song)
        val path = localFile?.absolutePath ?: stream.url
        return try {
            setAudioSourceMethod?.invoke(ctrl, trackId, path, 0L, true)
            prepareMethod?.invoke(ctrl)
            playMethod?.invoke(ctrl)
            Diag.info(TAG, "已重新绑定音频源「${song.title}」")
            true
        } catch (t: Throwable) {
            Diag.warn(TAG, "重新绑定失败：${t.javaClass.simpleName}")
            false
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
                "updateMediaItems=${updateItems != null} pause=${pause != null}",
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
