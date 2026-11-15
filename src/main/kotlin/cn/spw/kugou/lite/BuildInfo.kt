package cn.spw.kugou.lite

import java.util.Properties

/**
 * 构建信息（由 Gradle 的 `generateBuildInfo` 任务生成 `build-info.properties`）。
 *
 * 关于页展示 commit，便于用户反馈问题时直接报版本。
 * 源码包构建拿不到 git 信息时全部降级为 `unknown`，不影响功能。
 */
object BuildInfo {

    private val props: Properties by lazy {
        Properties().apply {
            runCatching {
                BuildInfo::class.java.classLoader
                    ?.getResourceAsStream("build-info.properties")
                    ?.use { load(it) }
            }
        }
    }

    /** 短 commit（8 位）。 */
    val commit: String get() = props.getProperty("commit", "unknown")

    /** 完整 commit。 */
    val fullCommit: String get() = props.getProperty("fullCommit", "unknown")

    /** 分支名。 */
    val branch: String get() = props.getProperty("branch", "unknown")

    /** `clean` / `dirty` / `unknown`。 */
    val state: String get() = props.getProperty("state", "unknown")

    /** 构建时间 `yyyy-MM-dd HH:mm`。 */
    val builtAt: String get() = props.getProperty("builtAt", "unknown")

    /** 版本号（来自 Gradle `version`）。 */
    val version: String get() = props.getProperty("version", "unknown")

    /** 关于页一行摘要：`0.3.0 · 45fe152 (master, clean)`。 */
    val summary: String
        get() {
            val v = version.ifBlank { "unknown" }
            val c = commit.ifBlank { "unknown" }
            val extra = listOfNotNull(
                branch.takeIf { it.isNotBlank() && it != "unknown" },
                state.takeIf { it.isNotBlank() && it != "unknown" },
            ).joinToString(", ")
            return if (extra.isEmpty()) "$v · $c" else "$v · $c ($extra)"
        }
}
