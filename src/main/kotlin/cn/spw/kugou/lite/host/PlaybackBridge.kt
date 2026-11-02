package cn.spw.kugou.lite.host

import cn.spw.kugou.lite.diag.Diag
import cn.spw.kugou.lite.sidecar.Song
import cn.spw.kugou.lite.sidecar.StreamInfo
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

    /** `setPlaybackQueue(List<PiscesMediaItem>, int)` —— 宿主实际用的重载。 */
    @Volatile
    private var setQueueList: java.lang.reflect.Method? = null

    /** `prepare()` —— 必须在 play() 之前调用，否则没有音频源。 */
    @Volatile
    private var prepareMethod: java.lang.reflect.Method? = null

    /** `play()`。 */
    @Volatile
    private var playMethod: java.lang.reflect.Method? = null

    /**
     * 播放一首歌。
     *
     * @param stream 已取到的播放信息（含 CDN 直链）
     * @return 成功入队并开始播放返回 true；任一环节失败返回 false 并写诊断日志。
     */
    fun play(song: Song, stream: StreamInfo): Boolean {
        return try {
            val ctrl = ensureController() ?: return false
            val trackId = song.hash.ifBlank { song.albumAudioId }
            val item = newMediaItem(song, trackId, stream.url) ?: return false

            // **关键**：先向 MusicVideoAudioSourceRegistry 登记「这条 trackId 的
            // 音频来源是 stream.url」，否则宿主会拿 trackId 去自己的曲库里找，
            // 找不到就弹「未载入歌曲，请选择一首歌曲播放」——真机踩过。
            //
            // 逆向依据：MusicVideoAudioSourceRegistry.matches(PiscesMediaItem)
            // 同时比对 Source.trackId==item 的 id 字段、Source.location==item 的
            // uri 字段，两者都相等才认定「这条外部音频源适用」。
            activateSource(trackId, stream.url)

            // **调用顺序必须与宿主一致**（实测 androidx/compose/ui/U+05D4 的
            // 字节码）：
            //
            //   setPlaybackQueue(List, int) → prepare() → play()
            //
            // 早期实现只用单曲重载且**漏了 prepare()**，宿主因此没有音频源，
            // 表现为播放栏显示歌名但弹出「未载入歌曲，请选择一首歌曲播放」，
            // 且播控按钮消失。
            val setQueue = setQueueList ?: return false
            setQueue.invoke(ctrl, listOf(item), 0)

            prepareMethod?.invoke(ctrl)
            playMethod?.invoke(ctrl)

            Diag.info(TAG, "已入队播放「${song.title}」${stream.qualityText}（${stream.bitRate / 1000}kbps）")
            true
        } catch (t: Throwable) {
            val cause = (t as? InvocationTargetException)?.targetException ?: t
            Diag.warn(TAG, "播放失败：${cause.javaClass.simpleName}: ${cause.message}")
            false
        }
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
        if (queueList == null) {
            Diag.warn(TAG, "PlaybackController 上找不到 setPlaybackQueue(List, int)")
            return null
        }
        queueList.isAccessible = true

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
        prepare.isAccessible = true
        play.isAccessible = true

        setQueueList = queueList
        prepareMethod = prepare
        playMethod = play
        controller = ctrl
        Diag.info(TAG, "已解析播放控制器（setPlaybackQueue(List,int) → prepare() → play()）")
        return ctrl
    }

    private fun newMediaItem(song: Song, trackId: String, uri: String): Any? {
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

        // 参数顺序见类文档：id / title / artist / album / albumArtist / uri
        return ctor.newInstance(
            trackId,
            song.title,
            song.singer,
            song.albumName ?: "",
            song.singer,
            uri,
        )
    }
}
