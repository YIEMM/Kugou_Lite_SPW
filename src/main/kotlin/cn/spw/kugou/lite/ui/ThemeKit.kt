package cn.spw.kugou.lite.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 基础组件库（全部走主题，不再硬编码颜色）。
 *
 * ## 与旧版的区别
 *
 * 旧版每个组件都写死 `Color(0xFF2A2A2A)` 之类，浅色主题下不可用。
 * 现在一律从 [KugouTheme.colors] 取色。
 *
 * ## 为什么用 BasicText
 *
 * 宿主的 Compose 环境不暴露 `material3` 的 `Text`（编译期没有该依赖）。
 * `BasicText` + 显式 `TextStyle` 是全项目统一的文字渲染方式，
 * 也能精确控制字号。
 *
 * ## 悬停反馈
 *
 * 桌面端没有触摸，指针悬停是最重要的可用性线索。
 * [HoverRow] 在指针进入时切换背景色，让用户明确知道哪里可点。
 */

/** 主文字。 */
@Composable
fun BodyText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = KugouTheme.colors.text,
    size: Int = 14,
    weight: Int = 400,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = TextStyle(
            color = color,
            fontSize = size.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight(weight),
        ),
    )
}

/** 次要文字。 */
@Composable
fun CaptionText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = KugouTheme.colors.muted,
    size: Int = 12,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = TextStyle(color = color, fontSize = size.sp),
    )
}

/** 强调文字（可点）。 */
@Composable
fun AccentText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = KugouTheme.colors.accent,
    size: Int = 14,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = TextStyle(color = color, fontSize = size.sp),
    )
}

/**
 * 卡片容器。
 *
 * 深色主题下用比页面稍亮的底色 + 细描边；
 * 浅色主题下用近白底 + 浅灰描边。
 */
@Composable
fun ThemeCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val c = KugouTheme.colors
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(c.cardBackground)
            .border(1.dp, c.line, RoundedCornerShape(10.dp)),
    ) {
        Column(Modifier.fillMaxWidth()) { content() }
    }
}

/**
 * 分组标题（左侧一小段中性竖条）。
 *
 * 参考 SPW-Listen-Stats 的 SectionHeading：标题用 `text`，
 * 图标用 `muted`。竖条用 `faint` 中性灰，不染蓝 —— 蓝色在 Salt 里
 * 只作极少量点缀，大面积使用会显得不像原生界面。
 */
@Composable
fun SectionTitle(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = KugouTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .width(3.dp)
                    .height(13.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(c.faint),
            )
            BodyText(
                text = title,
                color = c.text,
                size = 13,
                weight = 500,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        trailing?.invoke()
    }
}

/**
 * 可悬停的整行容器。
 *
 * 指针进入时铺一层 [KugouTheme.colors.hover]，离开时恢复。
 * 这是桌面列表最基础的交互反馈。
 *
 * content 接收 [RowScope]，调用方可以直接用 `Modifier.weight(1f)`
 * 让某一段占据剩余宽度（例如标题列 + 右侧箭头）。
 *
 * `onPointerEvent` 在 Compose 里仍标记为实验性 API，需要显式 OptIn。
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun HoverRow(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    var hovered by remember { mutableStateOf(false) }
    val c = KugouTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .background(if (hovered) c.hover else Color.Transparent)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}

/**
 * 分段控件（胶囊形单选）。
 *
 * 参考实现的 SegmentedControl：选中项是一块**中性灰底** + 细描边，
 * 文字始终是 `text`。不换成蓝色底 —— 那是 Material 风格，
 * 不是 Salt Player 的观感。
 */
@Composable
fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = KugouTheme.colors
    Row(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(c.subtle)
            .border(1.dp, c.line, RoundedCornerShape(8.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Box(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (selected) c.selectedBackground else Color.Transparent)
                    .then(
                        if (selected) Modifier.border(1.dp, c.line, RoundedCornerShape(6.dp))
                        else Modifier,
                    )
                    .clickable { onSelect(index) }
                    .padding(horizontal = 14.dp, vertical = 5.dp),
            ) {
                BasicText(
                    text = label,
                    style = TextStyle(
                        color = if (selected) c.text else c.muted,
                        fontSize = 12.sp,
                    ),
                )
            }
        }
    }
}

/**
 * 空态 / 加载态 / 错误态提示。
 *
 * 统一成一个组件，避免各页面各写一套导致留白与字号不一致。
 */
@Composable
fun StateHint(
    text: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    val c = KugouTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BasicText(text = text, style = TextStyle(color = c.muted, fontSize = 13.sp))
        action?.invoke()
    }
}

/** 带背景的标签（如「会员」「无损」）。 */
@Composable
fun TagChip(text: String, modifier: Modifier = Modifier) {
    val c = KugouTheme.colors
    Box(
        modifier
            .clip(RoundedCornerShape(4.dp))
            .background(c.accentSoft)
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        BasicText(text = text, style = TextStyle(color = c.accent, fontSize = 10.sp))
    }
}
