package cn.spw.kugou.lite.ui

/**
 * 发现页流式布局的纯逻辑 —— 列数自适应与分行。
 *
 * ## 为什么独立成文件
 *
 * 这两条规则是发现页观感的核心，且必须是**可单测的纯函数**：
 * UI 层（[DiscoverPage]）只消费结果，不在组合期里做算术。
 *
 * ## 规则
 *
 * - 列数 = 可用宽度 ÷ [DISCOVER_MIN_COLUMN_WIDTH_DP]，向下取整，保底 1、封顶 [DISCOVER_MAX_COLUMNS]
 * - 分行按列数切块（行内自左向右流动）；**末行只剩 1 项时独占整行**，
 *   避免半行空洞（用户在编辑面板关掉某个区块后必然出现奇数个区块）
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

/** 流式行里的一项：[span] 是它在本行占的列数（= 1，或末行落单时的总列数）。 */
internal data class DiscoverFlowItem(val id: String, val span: Int)

/**
 * 把区块 id 按列数切成流式行。
 *
 * @param ids 区块 id，调用方须先把「独占整行」的区块（私人 FM）剔除
 * @param columns 列数；非法值按 1 处理
 */
internal fun discoverFlowRows(ids: List<String>, columns: Int): List<List<DiscoverFlowItem>> {
    if (ids.isEmpty()) return emptyList()
    val cols = columns.coerceAtLeast(1)
    val rows: MutableList<List<DiscoverFlowItem>> = ids
        .chunked(cols)
        .map { chunk -> chunk.map { DiscoverFlowItem(it, 1) } }
        .toMutableList()
    val last = rows.last()
    if (last.size == 1) {
        // 末行只剩 1 项：让它横跨整行，不留空洞
        rows[rows.lastIndex] = listOf(DiscoverFlowItem(last[0].id, cols))
    }
    return rows
}
