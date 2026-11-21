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

    /**
     * 读写互斥锁（N24）。
     *
     * `setFollowing` 是「读 cache → 算新集合 → 写回 cache + 落盘」的读-改-写序列，
     * 两个歌手并发关注时，后写者会用自己的快照整体覆盖，丢掉前一次的更新。
     * 所有触及 cache / file 的路径（configure / setFollowing / load）都串行在这一把锁上，
     * 与 FavoriteCache（N11）的 `AtomicReference + @Synchronized` 模式一致。
     */
    private val lock = Any()

    fun configure(dataDir: File) {
        synchronized(lock) {
            file = File(dataDir, FILE_NAME)
            cache = null
        }
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

    /**
     * 记录关注 / 取消关注，立即落盘。
     *
     * 读-改-写 + 落盘**全程持锁**（N24）：落盘也在锁内，否则两个线程的
     * Properties.store 可能交错，后完成的旧快照把新快照覆盖掉 ——
     * 内存 cache 是对的、磁盘却丢了一条，重启后又变回丢更新现场。
     * load() 内部同样走这把锁（可重入），不会自锁。
     */
    fun setFollowing(artistId: String, following: Boolean) {
        if (artistId.isBlank()) return
        synchronized(lock) {
            val next = if (following) load() + artistId else load() - artistId
            cache = next
            val f = file ?: return
            runCatching {
                f.parentFile?.mkdirs()
                val props = Properties()
                props.setProperty(KEY_IDS, to(next))
                atomicWriteProperties(f, props)
            }.onFailure { Diag.warn("[follow] 保存失败：${it.message}") }
        }
    }

    private fun load(): Set<String> {
        synchronized(lock) {
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

    /**
     * properties 原子落盘（N24）：先写 .part 再 rename。
     *
     * 进程在 `store` 中途被杀会留下半截 follow.properties，
     * 读取失败按空集合处理 —— 用户点过的关注全部丢失。
     * rename 在同一目录内原子生效，读者要么看到完整的旧文件，要么看到完整的新文件。
     */
    private fun atomicWriteProperties(target: File, props: Properties) {
        val part = File(target.parentFile, target.name + ".part")
        part.outputStream().use { props.store(it, "Kugou Lite followed artists") }
        if (!part.renameTo(target)) {
            // rename 不能覆盖已存在文件时先删目标再改名（与 AudioCache.atomicWriteText 同模式）
            target.delete()
            if (!part.renameTo(target)) {
                throw IllegalStateException("rename 失败：${part.name} -> ${target.name}")
            }
        }
    }
}