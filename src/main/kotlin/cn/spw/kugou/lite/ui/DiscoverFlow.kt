package cn.spw.kugou.lite.ui

/**
 * 发现页流式布局的纯逻辑 —— 列数自适应与瀑布流分列。
 *
 * ## 为什么独立成文件
 *
 * 这两条规则是发现页观感的核心，且必须是**可单测的纯函数**：
 * UI 层（[DiscoverPage]）只消费结果，不在组合期里做算术。
 *
 * ## 规则
 *
 * - 列数 = 可用宽度 ÷ [DISCOVER_MIN_COLUMN_WIDTH_DP]，向下取整，保底 1、封顶 [DISCOVER_MAX_COLUMNS]
 * - 分列按「轮转」：第 i 个区块进第 `i % 列数` 列。这样：
 *   1. 视觉阅读顺序与配置顺序一致（自上而下逐行扫过各列）
 *   2. 每列独立往下排 —— 矮卡片下方立刻排进下一个区块，
 *      不会像「等高行」那样在卡底撑出一块页面底色的空洞
 */

/** 每列（含区块卡片与列间距）至少要有这么宽，才会再多分一列。 */
internal const val DISCOVER_MIN_COLUMN_WIDTH_DP = 300f

/** 列数上限：内容区块最多 4 个，列再多只会让每列过窄。 */
internal const val DISCOVER_MAX_COLUMNS = 3

/**
 * 按可用宽度算列数。非法宽度（0/负数，理论不该出现）保底单列。
 */
internal fun discoverColumnCount(availableWidthDp: Float): Int {
    if (availableWidthDp <= 0f) return 1
    return (availableWidthDp / DISCOVER_MIN_COLUMN_WIDTH_DP)
        .toInt()
        .coerceIn(1, DISCOVER_MAX_COLUMNS)
}

/**
 * 把区块 id 按列数轮转分成瀑布列。
 *
 * @param ids 区块 id，调用方须先把「独占整行」的区块（私人 FM）剔除
 * @param columns 列数；非法值按 1 处理
 * @return 非空的列（列数多于区块数时，尾部的空列会被丢弃）
 */
internal fun discoverFlowColumns(ids: List<String>, columns: Int): List<List<String>> {
    if (ids.isEmpty()) return emptyList()
    val cols = columns.coerceAtLeast(1)
    val buckets = List(cols) { mutableListOf<String>() }
    ids.forEachIndexed { index, id -> buckets[index % cols].add(id) }
    return buckets.filter { it.isNotEmpty() }
}
