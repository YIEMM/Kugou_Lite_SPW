package cn.spw.kugou.lite.sidecar

import cn.spw.kugou.lite.diag.Diag
import java.io.File
import java.util.Properties

/**
 * 插件设置（持久化到插件数据目录）。
 *
 * ## 为什么不用 `preference_config.json`
 *
 * 那个文件是**宿主设置页**的声明式配置，适合放「按钮」这类一次性动作，
 * 不适合放「音质档位」这种需要实时读写、且插件页面自己也要改的状态。
 *
 * 所以设置存在插件私有目录的 `settings.properties` 里：
 * - 宿主设置页的按钮只负责触发动作（清缓存等）
 * - 插件页面内的选择器直接读写这里
 *
 * ## 音质档位的现实约束
 *
 * 酷狗的音质与**账号会员等级**绑定。非会员请求 `flac` 时，服务端通常：
 * - 返回 128k 音源（静默降级），或
 * - 返回错误/无地址
 *
 * 所以 UI 上必须如实显示**实际拿到的音质**，不能按请求值虚标。
 * 这部分逻辑在 [StreamInfo.qualityText] 与调用方处理。
 */
object Settings {

    private const val TAG = "settings"
    private const val FILE_NAME = "settings.properties"

    private const val KEY_QUALITY = "quality"
    private const val KEY_CACHE_LIMIT_GB = "cacheLimitGb"
    private const val KEY_CACHE_DIR_OVERRIDE = "cacheDirOverride"
    private const val KEY_COVER_RATE_VISIBLE = "coverRateVisible"
    private const val KEY_COVER_RATE_INVISIBLE = "coverRateInvisible"

    /**
     * 封面请求限流（次/秒，用户需求 2026-11-21：视图内 5~8、视图外 2）。
     * 视图内默认 6（范围中段），可在设置页调整；视图外固定 2 也开放调整。
     */
    @Volatile
    var coverRateVisible: Int = 6
        private set

    @Volatile
    var coverRateInvisible: Int = 2
        private set

    /**
     * 缓存容量上限（GB，用户需求：默认 1 GB）。
     * 音频缓存与封面缓存共用同一个上限（按目录分别 enforce，配额相同）。
     */
    @Volatile
    var cacheLimitGb: Int = 1
        private set

    /** 缓存根目录覆盖（空 = 用默认插件数据目录）。变更时 UI 必须提示迁移或删除。 */
    @Volatile
    var cacheDirOverride: String = ""
        private set

    /**
     * 可选音质档位。
     *
     * 取值与酷狗接口的 `quality` 参数一致。
     */
    enum class Quality(val value: String, val label: String, val hint: String) {
        AUTO("auto", "自动", "优先高音质，失败自动降级"),
        STANDARD("128", "标准 128k", "所有账号都可用"),
        HIGH("320", "高品 320k", "通常需要会员"),
        LOSSLESS("flac", "无损 FLAC", "需要会员"),
        ;

        companion object {
            fun from(value: String?): Quality =
                entries.firstOrNull { it.value == value } ?: AUTO
        }
    }

    @Volatile
    var quality: Quality = Quality.AUTO
        private set

    private var file: File? = null

    fun configure(dataDir: File) {
        file = File(dataDir, FILE_NAME)
        load()
    }

    private fun load() {
        val f = file ?: return
        if (!f.isFile) {
            Diag.info(TAG, "无已保存设置，使用默认值")
            return
        }
        try {
            val props = Properties()
            f.inputStream().use { props.load(it) }
            quality = Quality.from(props.getProperty(KEY_QUALITY))
            cacheLimitGb = props.getProperty(KEY_CACHE_LIMIT_GB)?.toIntOrNull()?.coerceIn(1, 100) ?: 1
            cacheDirOverride = props.getProperty(KEY_CACHE_DIR_OVERRIDE).orEmpty()
            coverRateVisible = props.getProperty(KEY_COVER_RATE_VISIBLE)?.toIntOrNull()?.coerceIn(1, 20) ?: 6
            coverRateInvisible = props.getProperty(KEY_COVER_RATE_INVISIBLE)?.toIntOrNull()?.coerceIn(1, 20) ?: 2
            Diag.info(TAG, "已载入设置：音质=${quality.label}")
        } catch (e: Exception) {
            Diag.warn(TAG, "读取设置失败：${e.message}")
        }
    }

    fun setQuality(q: Quality) {
        quality = q
        save()
        Diag.info(TAG, "音质已设为 ${q.label}")
    }

    /** 设置缓存容量上限（GB）；生效需重启（目录配额在 configure 时应用）。 */
    fun setCacheLimitGb(gb: Int) {
        cacheLimitGb = gb.coerceIn(1, 100)
        save()
        Diag.info(TAG, "缓存上限已设为 $cacheLimitGb GB")
    }

    /**
     * 变更缓存根目录。调用前 UI 必须让用户明确选择「迁移」或「删除」：
     * 迁移用 [StorageMover.move]，删除用 [StorageMover.deleteAll]，两者都在
     * 变更前对旧目录执行。本方法只记录新位置。
     */
    fun setCacheDirOverride(path: String) {
        cacheDirOverride = path.trim()
        save()
        Diag.info(TAG, "缓存目录已变更：${cacheDirOverride.ifBlank { "(默认)" }}")
    }

    /** 设置封面限流（次/秒）；立即生效（CoverRateLimiter.DEFAULT 引用可变字段）。 */
    fun setCoverRate(visible: Int, invisible: Int) {
        coverRateVisible = visible.coerceIn(1, 20)
        coverRateInvisible = invisible.coerceIn(1, 20)
        save()
        Diag.info(TAG, "封面限流已设为 $coverRateVisible/$coverRateInvisible 每秒")
    }

    private fun save() {
        val f = file ?: run {
            Diag.warn(TAG, "未配置数据目录，设置仅保存在内存")
            return
        }
        try {
            f.parentFile?.mkdirs()
            val props = Properties().apply {
                setProperty(KEY_QUALITY, quality.value)
                setProperty(KEY_CACHE_LIMIT_GB, cacheLimitGb.toString())
                setProperty(KEY_CACHE_DIR_OVERRIDE, cacheDirOverride)
                setProperty(KEY_COVER_RATE_VISIBLE, coverRateVisible.toString())
                setProperty(KEY_COVER_RATE_INVISIBLE, coverRateInvisible.toString())
            }
            f.outputStream().use { props.store(it, "Kugou Lite settings") }
        } catch (e: Exception) {
            Diag.warn(TAG, "保存设置失败：${e.message}")
        }
    }

    /**
     * 把设置里的音质档位转成接口参数。
     *
     * `AUTO` 走 128（最稳），实际能否取到更高音质由 [resolveQualityChain] 决定。
     */
    fun requestQuality(): String = when (quality) {
        Quality.AUTO, Quality.STANDARD -> "128"
        Quality.HIGH -> "320"
        Quality.LOSSLESS -> "flac"
    }

    /**
     * 音质降级链：按用户偏好从高到低。
     *
     * 非会员请求 flac 失败时，会依次退到 320 → 128，
     * 而不是直接失败。
     */
    fun resolveQualityChain(): List<String> = when (quality) {
        Quality.AUTO -> listOf("320", "128")
        Quality.STANDARD -> listOf("128")
        Quality.HIGH -> listOf("320", "128")
        Quality.LOSSLESS -> listOf("flac", "320", "128")
    }
}
