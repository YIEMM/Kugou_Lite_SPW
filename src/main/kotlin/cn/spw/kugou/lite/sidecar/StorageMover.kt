package cn.spw.kugou.lite.sidecar

import java.io.File

/**
 * 缓存存储位置变更（用户需求 2026-11-21：变更时明确提示迁移或删除）。
 *
 * UI 层在用户选择新目录后调用 [moveOrDelete]：
 * - **迁移**：旧目录逐文件 move 到新目录（同盘 rename 原子；跨盘 copy+delete），
 *   失败的文件留在旧目录并计入返回值——迁移是尽力而为，不打断整体。
 * - **删除**：清空旧目录（用户已确认放弃这些缓存）。
 *
 * 两个动作都由 UI 明确提示后才会被调用；本类不做任何提示。
 */
object StorageMover {

    /** 迁移结果：成功迁移的文件数、失败留在旧目录的文件数。 */
    data class Result(val moved: Int, val failed: Int)

    /** 把旧目录内容整体迁移到新目录（两目录都须已存在）。 */
    fun move(oldDir: File, newDir: File): Result {
        var moved = 0
        var failed = 0
        val files = oldDir.listFiles()?.filter { it.isFile } ?: return Result(0, 0)
        newDir.mkdirs()
        for (f in files) {
            val target = File(newDir, f.name)
            val ok = if (f.renameTo(target)) {
                true
            } else {
                // 跨盘 rename 失败：copy + delete 兜底
                runCatching {
                    f.copyTo(target, overwrite = true)
                    f.delete()
                }.isSuccess
            }
            if (ok) moved++ else failed++
        }
        return Result(moved, failed)
    }

    /** 清空旧目录（用户选择"删除"）。@return 删除的文件数 */
    fun deleteAll(oldDir: File): Int {
        val files = oldDir.listFiles()?.filter { it.isFile } ?: return 0
        var n = 0
        for (f in files) if (f.delete()) n++
        return n
    }
}
