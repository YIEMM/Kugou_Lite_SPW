package cn.spw.kugou.lite.sidecar

import java.util.Locale
import cn.spw.kugou.lite.diag.Diag
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * 原生库解压与路径处理。
 *
 * ## 为什么必须解压
 *
 * `System.load` **不能**直接从 jar 内读取 dll —— 它需要文件系统上的真实路径。
 * 所以要把打包进 `.spmod` 的 dll 复制到磁盘再加载。
 *
 * ## 放在哪
 *
 * `<插件数据目录>/sidecar/native/`。选这里而不是 `%TEMP%`：
 * - `%TEMP%` 可能被清理，导致下次启动残留半截文件；
 * - 实测这台机器的安全策略对 `%TEMP%` 写入有限制（链接器曾报 LNK1104），
 *   插件数据目录由宿主 workshop 管理，可写性有保证。
 *
 * ## 何时重新解压
 *
 * 用 SHA-256 做指纹：资源内容变化（升级插件）才重写，否则复用已有文件，
 * 避免每次启动都做几 MB 的磁盘写入。
 */
internal object NativeLibExtractor {

    private const val TAG = "native"

    /** 解压目录名。 */
    private const val DIR_NAME = "native"

    /**
     * 把 jar 内 `resources/native/<name>` 解压到 `<workDir>/native/<name>`。
     *
     * @return 解压后的文件
     */
    fun extract(workDir: File, name: String): File {
        val nativeDir = File(workDir, DIR_NAME).apply { mkdirs() }
        val target = File(nativeDir, name)

        val bytes = readResource(name) ?: throw IOException("插件资源缺失：native/$name")
        val digest = sha256(bytes)

        // 指纹文件记录上次解压的版本；内容没变就跳过写入。
        val stamp = File(nativeDir, "$name.sha256")
        if (target.isFile && stamp.isFile && stamp.readText().trim() == digest) {
            return target
        }

        // N24：写入必须原子（.part + rename），见 [atomicWriteBytes] / [atomicWriteText]。
        // 旧实现直接 writeBytes + writeText：进程在中间被杀会留下
        // 「旧 stamp + 半截新 dll」——下次启动指纹匹配直接跳过重写，
        // System.load 加载截断的 dll 会崩溃。
        // stamp 最后写：stamp 与 dll 不配套时指纹不匹配，下次启动会重写而不是跳过。
        atomicWriteBytes(target, bytes)
        atomicWriteText(stamp, digest)
        Diag.info(TAG, "已解压 $name（${bytes.size} 字节，sha256=${digest.take(12)}…）")
        return target
    }

    /**
     * 把目录前置到进程 `PATH`。
     *
     * 桥接层用 `LoadLibrary` 显式加载上游 dll，理论上不需要 PATH；
     * 但 Windows 加载 dll 时若上游 dll 的**依赖**（如 `VCRUNTIME140.dll`）
     * 不在默认搜索路径里，仍需 PATH 兜底。
     *
     * 注意：修改 `PATH` 只影响**当前进程**（宿主），退出即恢复。
     */
    fun prependToPath(dir: File) {
        try {
            val key = "PATH"
            val current = System.getenv(key).orEmpty()
            val prefix = dir.absolutePath
            if (current.split(File.pathSeparator).any { it.equals(prefix, ignoreCase = true) }) return
            val updated = if (current.isEmpty()) prefix else "$prefix${File.pathSeparator}$current"
            setEnv(key, updated)
        } catch (e: Throwable) {
            // 改 PATH 失败不致命：桥接层用绝对路径加载，多数情况仍可用。
            Diag.warn(TAG, "修改 PATH 失败（不影响主流程）：${e.message}")
        }
    }

    // ---------------------------------------------------------------- 内部

    private fun readResource(name: String): ByteArray? =
        NativeLibExtractor::class.java.classLoader?.getResourceAsStream("native/$name")?.use { it.readBytes() }

    /**
     * 字节原子写（N24）：先写 `<name>.part` 再 rename 到目标。
     *
     * 为什么不能直接 `writeBytes`：它先截断为 0 再逐块写，进程在中间被杀
     * 就留下半截文件；而指纹 stamp 后写，会出现「旧 stamp + 半截新 dll」，
     * 下次启动指纹匹配 → 跳过重写 → `System.load` 加载截断 dll 直接崩溃。
     * rename 在同一目录内原子生效：读者要么看到完整的旧文件，要么完整的新文件，
     * 半截内容只存在于 .part。rename 不能覆盖已存在文件时先删目标再改名
     * （与 AudioCache.atomicWriteText 同模式），仍失败则抛出并清理 .part。
     * internal 供单测直测。
     */
    /** 同一把锁串行化所有原子写：并发写同一目标时后写完整覆盖前写，杜绝删除窗口。 */
    private val writeLock = Any()

    internal fun atomicWriteBytes(target: File, bytes: ByteArray) {
        synchronized(writeLock) {
            val part = File(target.parentFile, target.name + ".part")
            try {
                part.writeBytes(bytes)
                if (!part.renameTo(target)) {
                    // 某些环境 rename 不能覆盖已存在文件：先删再改名（与 AudioCache 同模式）
                    target.delete()
                    if (!part.renameTo(target)) {
                        throw IllegalStateException("rename 失败：${part.name} -> ${target.name}")
                    }
                }
            } catch (t: Throwable) {
                runCatching { part.delete() }
                throw t
            }
        }
    }

    /**
     * 文本原子写（N24）：同 [atomicWriteBytes]，用于 sha256 指纹等小文件，
     * 避免半截指纹恰好被读出当成有效值。internal 供单测直测。
     */
    internal fun atomicWriteText(target: File, text: String) {
        synchronized(writeLock) {
            val part = File(target.parentFile, target.name + ".part")
            try {
                part.writeText(text, Charsets.UTF_8)
                if (!part.renameTo(target)) {
                    target.delete()
                    if (!part.renameTo(target)) {
                        throw IllegalStateException("rename 失败：${part.name} -> ${target.name}")
                    }
                }
            } catch (t: Throwable) {
                runCatching { part.delete() }
                throw t
            }
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { String.format(Locale.ROOT, "%02x", it) }

    /**
     * 在 JVM 里改环境变量。
     *
     * `System.getenv` 返回**不可修改**的 Map，所以必须走反射拿 `ProcessEnvironment`
     * 的内部 Map。这是长期存在的 hack，模块系统下需要 `--add-opens`，
     * 失败就静默降级（PATH 只是兜底手段）。
     */
    @Suppress("UNCHECKED_CAST")
    private fun setEnv(key: String, value: String) {
        try {
            val cls = Class.forName("java.lang.ProcessEnvironment")
            val field = cls.getDeclaredField("theUnmodifiableEnvironment")
            field.isAccessible = true
            val env = field.get(null) as MutableMap<String, String>
            env[key] = value
            return
        } catch (_: Throwable) {
            // 落到下面的备选实现
        }
        try {
            val cls = Class.forName("java.lang.ProcessEnvironment")
            val field = cls.getDeclaredField("theEnvironment")
            field.isAccessible = true
            val env = field.get(null) as MutableMap<String, String>
            env[key] = value
        } catch (e: Throwable) {
            Diag.warn(TAG, "反射修改环境变量失败：${e.javaClass.simpleName}")
        }
    }
}