package cn.spw.kugou.lite.sidecar

import cn.spw.kugou.lite.diag.Diag
import java.io.File
import java.util.Properties

/**
 * 发现页「编辑」面板的配置持久化。
 *
 * ## 配置内容
 *
 * - 布局：单列 / 双列
 * - 区块顺序与显示开关
 * - 私人 FM 档位下标
 *
 * ## 为什么独立成一个文件
 *
 * 编辑面板（UI）与发现页（UI）都要读写同一份配置；把它放在纯数据层，
 * 两边只依赖 [Config]，不各自解析 properties，避免两处漂移。
 *
 * ## 容错原则
 *
 * 配置文件可能被手动编辑、被旧版本写过、或升级后新增了区块。
 * [fromProperties] 一律按「未知 ID 忽略、缺失补末尾、非法值回退默认」处理，
 * 任何解析异常都回退 [default]，绝不抛给组合树。
 */
object DiscoverConfig {

    private const val TAG = "discover-config"
    private const val FILE_NAME = "discover_config.properties"

    /** 页面布局。 */
    enum class Layout {
        SINGLE,
        PAIR,
        ;

        companion object {
            fun from(value: String?): Layout = when (value?.lowercase()) {
                "pair" -> PAIR
                else -> SINGLE
            }
        }
    }

    /**
     * 一份配置快照。
     *
     * @param order 全部区块的顺序（含隐藏的）
     * @param hidden 被关闭的区块 id
     */
    data class Config(
        val layout: Layout,
        val order: List<String>,
        val hidden: Set<String>,
        val fmStationIndex: Int,
    ) {
        /** 实际要渲染、要发请求的区块，保持 [order] 的相对顺序。 */
        val visible: List<String> get() = order.filter { it !in hidden }

        /** 把配置序列化成键值映射。 */
        fun toProperties(): Map<String, String> = mapOf(
            KEY_LAYOUT to if (layout == Layout.PAIR) "pair" else "single",
            KEY_ORDER to order.joinToString(","),
            KEY_HIDDEN to hidden.joinToString(","),
            KEY_FM_STATION to fmStationIndex.toString(),
        )

        /**
         * 移动区块顺序。
         *
         * @param delta -1 上移 / +1 下移；越界时原样返回
         */
        fun move(id: String, delta: Int): Config {
            val from = order.indexOf(id)
            if (from < 0) return this
            val to = from + delta
            if (to < 0 || to >= order.size) return this
            val next = order.toMutableList()
            val item = next.removeAt(from)
            next.add(to, item)
            return copy(order = next)
        }
    }

    /** 区块 id 固定，顺序即默认顺序。新增区块一律追加末尾，不插入中间。 */
    val SECTION_IDS = listOf("fm", "daily", "discover", "newSongs", "newAlbums")

    private const val KEY_LAYOUT = "layout"
    private const val KEY_ORDER = "order"
    private const val KEY_HIDDEN = "hidden"
    private const val KEY_FM_STATION = "fmStationIndex"

    private var file: File? = null

    @Volatile
    private var cached: Config? = null

    fun default(): Config = Config(
        layout = Layout.SINGLE,
        order = SECTION_IDS.toList(),
        hidden = emptySet(),
        fmStationIndex = 0,
    )

    /**
     * 从键值映射还原配置。
     *
     * - `layout` 只认 `single` / `pair`
     * - `order` 去未知 ID、去重，再把 [SECTION_IDS] 里缺的按原顺序补末尾
     * - `hidden` 只保留 [SECTION_IDS] 中的 id
     * - `fmStationIndex` 用 `toIntOrNull()` 解析且必须在 `0..3`
     */
    fun fromProperties(p: Map<String, String>): Config = try {
        val layout = Layout.from(p[KEY_LAYOUT])

        val order = ArrayList<String>()
        for (id in (p[KEY_ORDER] ?: "").split(',')) {
            val trimmed = id.trim()
            if (trimmed in SECTION_IDS && trimmed !in order) order.add(trimmed)
        }
        // 新增的区块补到末尾，不重置用户已有的顺序
        for (id in SECTION_IDS) {
            if (id !in order) order.add(id)
        }

        val hidden = (p[KEY_HIDDEN] ?: "")
            .split(',')
            .map { it.trim() }
            .filter { it in SECTION_IDS }
            .toSet()

        val station = p[KEY_FM_STATION]?.toIntOrNull()?.takeIf { it in 0..3 } ?: 0

        Config(
            layout = layout,
            order = order,
            hidden = hidden,
            fmStationIndex = station,
        )
    } catch (e: Exception) {
        Diag.warn(TAG, "配置解析失败，使用默认值：${e.message}")
        default()
    }

    /** 绑定插件私有目录并读入配置。 */
    fun configure(dataDir: File) {
        file = File(dataDir, FILE_NAME)
        load()
    }

    /** 读取配置（带内存缓存）。文件不存在时返回默认值。 */
    fun load(): Config {
        cached?.let { return it }
        val f = file
        if (f == null || !f.isFile) {
            cached = default()
            return cached!!
        }
        val props = Properties()
        val c = try {
            f.inputStream().use { props.load(it) }
            val map = props.stringPropertyNames().associateWith { props.getProperty(it).orEmpty() }
            fromProperties(map)
        } catch (e: Exception) {
            Diag.warn(TAG, "读取配置失败，使用默认值：${e.message}")
            default()
        }
        cached = c
        Diag.info(TAG, "已载入：布局=${c.layout} 可见=${c.visible.size} 档位=${c.fmStationIndex}")
        return c
    }

    /** 保存配置并更新缓存。 */
    fun save(c: Config) {
        cached = c
        val f = file ?: run {
            Diag.warn(TAG, "未配置数据目录，配置仅保存在内存")
            return
        }
        try {
            f.parentFile?.mkdirs()
            val props = Properties()
            c.toProperties().forEach { (k, v) -> props.setProperty(k, v) }
            f.outputStream().use { props.store(it, "Kugou Lite discover config") }
        } catch (e: Exception) {
            Diag.warn(TAG, "保存配置失败：${e.message}")
        }
    }
}
