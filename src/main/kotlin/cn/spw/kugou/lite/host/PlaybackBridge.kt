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

    @Volatile
    private var controller: Any? = null

    @Volatile
    private var mediaItemCtor: java.lang.reflect.Constructor<*>? = null

    @Volatile
    private var setQueueSingle: java.lang.reflect.Method? = null

    /**
     * 播放一首歌。
     *
     * @param stream 已取到的播放信息（含 CDN 直链）
     * @return 成功入队并开始播放返回 true；任一环节失败返回 false 并写诊断日志。
     */
    fun play(song: Song, stream: StreamInfo): Boolean {
        return try {
            val ctrl = ensureController() ?: return false
            val item = newMediaItem(song, stream) ?: return false
            val method = setQueueSingle ?: return false

            method.invoke(ctrl, item)
            // 有些状态下 setPlaybackQueue 只入队不自动播，这里显式触发一次。
            runCatching {
                ctrl.javaClass.methods
                    .firstOrNull { it.name == "play" && it.parameterCount == 0 }
                    ?.invoke(ctrl)
            }
            Diag.info(TAG, "已入队播放「${song.title}」${stream.qualityText}（${stream.bitRate / 1000}kbps）")
            true
        } catch (t: Throwable) {
            val cause = (t as? InvocationTargetException)?.targetException ?: t
            Diag.warn(TAG, "播放失败：${cause.javaClass.simpleName}: ${cause.message}")
            false
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

        val single = ctrl.javaClass.methods.firstOrNull {
            it.name == "setPlaybackQueue" && it.parameterCount == 1
        }
        if (single == null) {
            Diag.warn(TAG, "PlaybackController 上找不到 setPlaybackQueue(PiscesMediaItem)")
            return null
        }
        single.isAccessible = true

        setQueueSingle = single
        controller = ctrl
        Diag.info(TAG, "已解析播放控制器")
        return ctrl
    }

    private fun newMediaItem(song: Song, stream: StreamInfo): Any? {
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
            song.hash.ifBlank { song.albumAudioId },
            song.title,
            song.singer,
            song.albumName ?: "",
            song.singer,
            stream.url,
        )
    }
}
