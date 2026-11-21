package cn.spw.kugou.lite.sidecar

import java.io.File

/**
 * 缓存目录容量上限（用户需求 2026-11-21：默认 1 GB，LRU 淘汰）。
 *
 * 淘汰顺序 = 文件 lastModified 从旧到新（越久没用越先淘汰）。
 * 返回被删除的文件列表供调用方记日志/更新界面；被删的歌词 `.lrc`
 * 由调用方决定是否连带删除（AudioCache 场景应同删）——本函数只删给定目录内的文件。
 */
object CacheQuota {

    /** 默认容量上限：1 GB。 */
    const val DEFAULT_LIMIT_BYTES: Long = 1L * 1024L * 1024L * 1024L

    /**
     * 强制目录总大小不超过 [limitBytes]。
     *
     * @param keepNames 豁免文件名集合（如正在播放的音频），不参与淘汰也不计入必删
     * @return 被删除的文件（按淘汰顺序）
     */
    fun enforce(dir: File, limitBytes: Long, keepNames: Set<String> = emptySet()): List<File> {
        val files = dir.listFiles()?.filter { it.isFile } ?: return emptyList()
        var total = files.sumOf { it.length() }
        if (total <= limitBytes) return emptyList()
        val removable = files.filter { it.name !in keepNames }
        val keep = files.filter { it.name in keepNames }.sumOf { it.length() }
        val evictable = removable.sortedBy { it.lastModified() }
        val removed = mutableListOf<File>()
        for (f in evictable) {
            if (total - keep <= limitBytes) break
            val size = f.length()
            if (f.delete()) {
                total -= size
                removed.add(f)
            }
        }
        return removed
    }

    /** 目录总大小（字节）。 */
    fun usage(dir: File): Long = dir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
}
