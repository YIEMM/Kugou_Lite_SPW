package cn.spw.kugou.lite.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.onSizeChanged

/**
 * 全页面改版的公共组件层。
 *
 * ## 组件
 *
 * - [PageHero] —— 详情/歌手页的头部横幅（大封面 + 标题 + 元信息 + 操作组）
 * - [SlidingTabBar] —— 顶部 Tab 分段，滑动下划线（LS StatsWindow.kt:648-686 同款思路）
 * - [DataTableRow] —— 密集表格行（#/封面/标题歌手/专辑/时长），悬停高亮
 * - [EntryTile] —— 快捷入口磁贴
 *
 * ## 设计约定
 *
 * - 全部取色 `KugouTheme.colors`；accent 只允许出现在 [SlidingTabBar] 的下划线
 * - 悬停高亮用 `drawBehind`（状态变化只重画不重建背景链；库内无 `Modifier.drawWith`，
 *   `drawBehind` 是其等价写法 —— 对应 LS `drawWith { drawRect }` 的意图）
 * - 断点常量与 LS 对齐：表格行高 65dp、大封面 48dp/圆角 4dp、<440dp 隐藏专辑列
 */

/** 专辑列显示的最小内容宽度（dp）。低于此宽度表格行隐藏专辑列。 */
internal const val TABLE_ALBUM_MIN_WIDTH_DP = 440f

/** 表格行高（dp）。 */
internal const val TABLE_ROW_HEIGHT_DP = 65

/** 表格行大封面尺寸（dp）。 */
internal const val TABLE_COVER_DP = 48

/** 表格行的可点单元（歌手名/专辑名单独可跳转时由页面传入）。 */
internal data class DataTableCells(
    val onArtist: ((String) -> Unit)? = null,
    val onAlbum: ((String) -> Unit)? = null,
)

/**
 * 懒加载判定：这个 Tab 现在**是否需要发请求**。
 *
 * ## 为什么单独抽成函数
 *
 * 曾写成 `if (state.value == null) load()` —— `MutableAsyncState.value`
 * 的静态类型是非空 `Async<T>`（初值 `Async.Idle`），该条件**恒为 false**，
 * 搜索页四个 Tab 因此永不发请求、永久停在「正在搜索…」。
 * 编译能过，既有测试也全绿（这是判空写错，不是逻辑缺失）。
 *
 * 规则：Idle（从未加载）与 Failure（失败可重试）要发；
 * Loading（在飞）与 Success（已有数据）不发。
 */
internal fun <T> shouldLoadForTab(state: cn.spw.kugou.lite.sidecar.Async<T>): Boolean =
    when (state) {
        is cn.spw.kugou.lite.sidecar.Async.Idle -> true
        is cn.spw.kugou.lite.sidecar.Async.Failure -> true
        is cn.spw.kugou.lite.sidecar.Async.Loading -> false
        is cn.spw.kugou.lite.sidecar.Async.Success -> false
    }

/**
 * 封面网格的列数（按内容宽度）。
 *
 * 复用发现页的断点口径（`discoverColumnCount`：每列 ≥300dp、上限 3），
 * 但网格卡最小可用宽度比区块卡更窄，所以这里用 240dp 一档、上限 4。
 *
 * **不要在页面里写死 `chunked(3)`** —— 窄窗口会挤成三列小图，
 * 违反规格 §8.1「窄窗口降级正常」。
 */
internal fun coverGridColumns(availableWidthDp: Float): Int {
    if (availableWidthDp <= 0f) return 1
    return (availableWidthDp / 240f).toInt().coerceIn(1, 4)
}

/** 把列表按列数切行（供 `Column + Row` 手写网格用，避免 LazyVerticalGrid 的嵌套滚动问题）。 */
internal fun <T> gridRows(items: List<T>, columns: Int): List<List<T>> =
    items.chunked(columns.coerceAtLeast(1))

/**
 * 页头横幅：大封面 + 标题 + 元信息 + 操作组。
 *
 * 自带 `padding(horizontal = 16.dp)`（布局铁律：外层 Column 不加水平 padding，
 * 每个组件自己负责水平留白）。
 */
@Composable
internal fun PageHero(
    coverUrl: String?,
    title: String,
    meta: String,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val c = KugouTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CoverBox(coverUrl, 86, 8)
        Column(Modifier.weight(1f)) {
            Text3(
                text = title,
                color = c.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text3(
                text = meta,
                color = c.muted,
                modifier = Modifier.padding(top = 3.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                Modifier.padding(top = 9.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = actions,
            )
        }
    }
}

/**
 * 顶部 Tab 分段 + 滑动下划线。
 *
 * 下划线是一条 2dp 的 accent 横条，`animateFloatAsState` 同时插值
 * left 与 width（LS 的做法）；未选中项 muted，选中项 text。
 */
@Composable
internal fun SlidingTabBar(
    tabs: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = KugouTheme.colors
    val gap = 16.dp
    val density = LocalDensity.current
    val gapPx = with(density) { gap.toPx() }
    var widths by remember(tabs) { mutableStateOf(List(tabs.size) { 0f }) }

    val safeSelected = selected.coerceIn(0, (tabs.size - 1).coerceAtLeast(0))
    fun targetLeft(): Float {
        var x = 0f
        for (i in 0 until safeSelected) x += widths.getOrElse(i) { 0f } + gapPx
        return x
    }
    val left by animateFloatAsState(
        targetValue = targetLeft(),
        animationSpec = tween(180),
        label = "tabLeft",
    )
    val width by animateFloatAsState(
        targetValue = widths.getOrElse(safeSelected) { 0f },
        animationSpec = tween(180),
        label = "tabWidth",
    )

    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .drawBehind {
                if (width > 1f) {
                    drawRect(
                        color = c.accent,
                        topLeft = Offset(left, size.height - 2.dp.toPx()),
                        size = Size(width, 2.dp.toPx()),
                    )
                }
            },
        horizontalArrangement = Arrangement.spacedBy(gap),
    ) {
        tabs.forEachIndexed { i, label ->
            // ⚠️ 两个真机踩过的坑，改之前先看注释：
            // 1. clickable 必须在 padding **之后**，否则点击区只有文字那 ~18px
            // 2. 外层**不能**加 horizontalScroll —— 滚动手势会拦掉点击，
            //    Tab 一个都点不动（日志证实 onSelect 从不触发）。
            //    Tab 数量本来就不多（≤6），用 weight 平分即可，不需要滚动。
            Text3(
                text = label,
                color = if (i == safeSelected) c.text else c.muted,
                modifier = Modifier
                    .weight(1f)
                    .onSizeChanged { sz ->
                        widths = widths.toMutableList().also { it[i] = sz.width.toFloat() }
                    }
                    .padding(horizontal = 4.dp, vertical = 9.dp)
                    .clickable { onSelect(i) },
                maxLines = 1,
            )
        }
    }
}

/**
 * 密集表格行：# / 封面 / 标题+歌手 / 专辑 / 时长。
 *
 * - [showAlbum]：页面按内容宽度传入（`maxWidth >= TABLE_ALBUM_MIN_WIDTH_DP`）
 * - 悬停：指针进入铺 `colors.hover`，离开恢复
 * - 歌手名/专辑名可通过 [cells] 单独可点（下划线语义用 text 色，不用 accent）
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun DataTableRow(
    index: String,
    coverUrl: String?,
    title: String,
    subtitle: String,
    album: String?,
    duration: String,
    showAlbum: Boolean = true,
    onRowClick: () -> Unit,
    modifier: Modifier = Modifier,
    cells: DataTableCells = DataTableCells(),
) {
    val c = KugouTheme.colors
    var hovered by remember { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .height(TABLE_ROW_HEIGHT_DP.dp)
            .drawBehind { if (hovered) drawRect(c.hover) }
            .clickable { onRowClick() }
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text3(text = index, color = c.faint, modifier = Modifier.width(24.dp), maxLines = 1)
        CoverBox(coverUrl, TABLE_COVER_DP, 4)
        Column(Modifier.weight(1f)) {
            Text3(
                text = title,
                color = c.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text3(
                text = subtitle,
                color = c.muted,
                modifier = Modifier
                    .padding(top = 1.dp)
                    .then(
                        if (cells.onArtist != null) {
                            Modifier.clickable { cells.onArtist.invoke(subtitle) }
                        } else {
                            Modifier
                        },
                    ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (showAlbum) {
            Text3(
                text = album.orEmpty(),
                color = c.muted,
                modifier = Modifier
                    .width(110.dp)
                    .then(
                        if (cells.onAlbum != null) {
                            Modifier.clickable { cells.onAlbum.invoke(album.orEmpty()) }
                        } else {
                            Modifier
                        },
                    ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text3(
            text = duration,
            color = c.muted,
            modifier = Modifier.width(44.dp),
            maxLines = 1,
        )
    }
}

/**
 * 快捷入口磁贴：图标 + 文字，悬停高亮。
 *
 * 在父 Row 里用 `Modifier.weight(1f)` 平分宽度（调用方负责）。
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun EntryTile(
    label: String,
    glyph: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = KugouTheme.colors
    var hovered by remember { mutableStateOf(false) }
    Column(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .drawBehind { if (hovered) drawRect(c.hover) }
            .clickable { onClick() }
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text3(text = glyph, color = c.text)
        Text3(
            text = label,
            color = c.muted,
            modifier = Modifier.padding(top = 3.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 封面网格卡：填满单元格宽度的方形封面 + 标题 + 副标题。
 *
 * 与 [CoverBox] 的区别：封面宽度跟随单元格（`fillMaxWidth().aspectRatio(1f)`），
 * 加载中/失败显示音符占位。供搜索页/歌单广场/我的音乐等封面网格共用。
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun CoverGridCard(
    coverUrl: String?,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    val c = KugouTheme.colors
    var hovered by remember { mutableStateOf(false) }
    val bitmap = rememberCover(coverUrl?.replace("{size}", "300"))
    Column(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .drawBehind { if (hovered) drawRect(c.hover) }
            .clickable { onClick() }
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .padding(9.dp),
    ) {
        val shape = RoundedCornerShape(6.dp)
        androidx.compose.foundation.layout.Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .background(c.coverPlaceholder, shape),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                androidx.compose.foundation.Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(shape),
                )
            } else {
                Text3(text = "♪", color = c.faint)
            }
        }
        Text3(
            text = title,
            color = c.text,
            modifier = Modifier.padding(top = 7.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text3(
            text = subtitle,
            color = c.muted,
            modifier = Modifier.padding(top = 1.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

