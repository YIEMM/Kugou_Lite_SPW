package cn.spw.kugou.lite.ui

import cn.spw.kugou.lite.diag.Diag
import java.io.File
import java.util.Properties

/**
 * 关注状态的本地持久化（纯逻辑，可单测）。
 *
 * ## 为什么需要本地记忆
 *
 * 服务端 `/artist/detail` 的 `user_status` 实测**不随 follow/unfollow 变化**
 * （裁决 R3：请求成功但状态不回落）。若不本地记住用户点过什么，
 * 重进歌手页按钮永远显示「＋ 关注」，用户会以为关注失败了。
 *
 * 于是：`user_status == 1` 或「本地记录里有这个歌手」→ 显示「已关注」。
 *
 * ## 存储
 *
 * `<插件数据目录>/follow.properties` 的一个键 `ids`，逗号分隔。
 * 读写全部 `runCatching`，坏文件当空集合处理，绝不抛给 UI。
 */
object FollowStore {

    private const val FILE_NAME = "follow.properties"
    private const val KEY_IDS = "ids"
    private var file: File? = null

    @Volatile
    private var cache: Set<String>? = null

    fun configure(dataDir: File) {
        file = File(dataDir, FILE_NAME)
        cache = null
    }

    // ------------------------------------------------------------ 纯逻辑（单测）

    internal fun from(raw: String?): Set<String> =
        raw.orEmpty().split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    internal fun to(ids: Set<String>): String = ids.sorted().joinToString(",")

    // ------------------------------------------------------------ 读写

    /** 是否已关注（服务端状态或本地记录）。 */
    fun isFollowing(artistId: String, serverStatus: Int): Boolean {
        if (artistId.isBlank()) return false
        if (serverStatus == 1) return true
        return load().contains(artistId)
    }

    /** 记录关注 / 取消关注，立即落盘。 */
    fun setFollowing(artistId: String, following: Boolean) {
        if (artistId.isBlank()) return
        val next = if (following) load() + artistId else load() - artistId
        cache = next
        val f = file ?: return
        runCatching {
            f.parentFile?.mkdirs()
            val props = Properties()
            props.setProperty(KEY_IDS, to(next))
            f.outputStream().use { props.store(it, "Kugou Lite followed artists") }
        }.onFailure { Diag.warn("[follow] 保存失败：${it.message}") }
    }

    private fun load(): Set<String> {
        cache?.let { return it }
        val f = file
        if (f == null || !f.isFile) {
            cache = emptySet()
            return emptySet()
        }
        val ids = runCatching {
            val props = Properties()
            f.inputStream().use { props.load(it) }
            from(props.getProperty(KEY_IDS))
        }.getOrElse {
            Diag.warn("[follow] 读取失败：${it.message}")
            emptySet()
        }
        cache = ids
        return ids
    }
}
