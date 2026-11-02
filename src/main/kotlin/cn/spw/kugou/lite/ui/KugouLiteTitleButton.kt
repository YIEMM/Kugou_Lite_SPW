package cn.spw.kugou.lite.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.unit.dp

/**
 * 宿主标题栏上的「Kugou Lite」入口按钮。
 *
 * <p>**为什么用宿主 `BasicCaptionButton`（[SaltCaptionButton]）而不是自绘 `Box`**：
 * 宿主标题栏右侧那一条 `CaptionButtons` 是一个 `Row`，里面按顺序放
 * 「账户 / 刷新应用库 / 调试…」等按钮。自绘组件塞进去会有两个问题：
 * 一是尺寸不参与 Row 的间距规则，视觉上被排到行首或错位；
 * 二是没有宿主的悬停底色与无障碍语义。用宿主自己的 caption 按钮后，
 * 它就是那一行里的**普通同级子项**，宽度、图标大小、悬停态、按下态全部自动一致。</p>
 *
 * <p>**为什么不会与 □/✕ 重叠**：这个按钮由 `afterComposable` 追加在
 * `DebugCaptionButton` 之后，是 `CaptionButtons` 那个 `Row` 的**行内子项**，
 * 由 Row 的顺序布局决定位置，不存在浮层那种「叠在别人上面」的可能。
 * 这正是修掉「点关闭按钮导致宿主窗口消失」那个问题的关键。</p>
 *
 * <p>**图标**：宿主没有暴露可复用的图标资源，所以用 [Canvas] 自绘一个
 * 「三根频谱柱」图形。颜色用宿主播放态强调色，深色主题下也可读。</p>
 *
 * <h3>必须 `align(Alignment.Center)`（真机踩坑）</h3>
 *
 * <p>宿主 `BasicCaptionButton` 内部是 `Box` 容器，宿主自己的图标都用
 * `BoxScope.align(Modifier, Alignment.Center) + size(iconSize)` 居中放置
 * （javap 实证：`BoxScope.align` 后紧跟 `SizeKt.size`）。</p>
 *
 * <p>`BasicCaptionButton` 的 `content` 接收者就是 `BoxScope`，而我们直接塞了个
 * `Canvas` 却**忘了 align** —— `Box` 默认按 `TopStart` 放置，于是图标偏上偏左。
 * 真机实测：宿主原生图标 y ∈ [11, 28]，我们的 y ∈ [4, 21]，**上移 7px**。
 * 补上 `align(Alignment.Center)` 后与宿主完全对齐。</p>
 *
 * <p>**注意接收者类型**：`SaltCaptionButton` 的内容是 `BoxScope.() -> Unit`
 * （宿主 `BasicCaptionButton` 的 `content` 是 `Function3<BoxScope,...>`）。
 * 这里不要写 `content: @Composable () -> Unit` 之类无接收者的类型，
 * 否则编译成 `Function2`，真机上 `ClassCastException` 崩掉宿主进程。</p>
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun KugouLiteTitleButton() {
    var hovered by remember { mutableStateOf(false) }

    SaltCaptionButton(
        onClick = { PageState.requestOpen() },
        contentDescription = "Kugou Lite",
        enabled = true,
    ) {
        Canvas(
            Modifier
                // 必须：宿主 Box 默认 TopStart，不居中就会偏上偏左。
                .align(Alignment.Center)
                .size(18.dp)
                .onPointerEvent(PointerEventType.Enter) { hovered = true }
                .onPointerEvent(PointerEventType.Exit) { hovered = false }
        ) {
            // 三根高度递增的频谱柱，底部对齐；悬停时提亮。
            //
            // 几何按「填满画布」算：3 柱 + 2 间隙 = 100% 宽度，左右各留半个间隙的
            // 视觉呼吸。早期用了 barWidth=0.18/gap=0.10/start=0.10，总宽只有 0.74，
            // 右侧余 0.16 空白 —— 真机量出来图标只有 15px 宽，而宿主原生图标是 18px，
            // 视觉上明显偏小偏瘦。
            val accent = if (hovered) Color(0xFF7FD4FF) else Color(0xFF4CC2FF)
            val gap = size.width * 0.12f
            val barWidth = (size.width - gap * 2f) / 3f
            val heights = floatArrayOf(0.45f, 0.75f, 1.0f)
            var x = 0f
            for (h in heights) {
                val barHeight = size.height * h
                drawRoundRect(
                    color = accent,
                    topLeft = Offset(x, size.height - barHeight),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
                )
                x += barWidth + gap
            }
        }
    }
}
