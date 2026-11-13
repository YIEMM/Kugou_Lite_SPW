package cn.spw.kugou.lite.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp

/**
 * 左侧栏里的「酷狗音乐」一行。
 *
 * <h3>为什么图标要 `align(Alignment.Center)`</h3>
 *
 * 宿主 `SidebarItemLayout` 的内容槽位接收者是 `BoxScope`，`Box` 默认按 `TopStart`
 * 放置子项。标题栏按钮那次真机踩过同样的坑：忘了 `align` 图标就偏上偏左
 * （实测上移 7px）。所以图标必须显式居中。
 *
 * <h3>为什么不用 `colorFilter` 着色</h3>
 *
 * 品牌图标已经用 [KugouLogo.BrandColor] 预着色（`SolidColor` 填充）。宿主侧栏
 * 的原生图标是主题灰，但品牌入口保留品牌蓝更容易辨识；并且**不传 tint**，
 * 避免宿主的内容色把图标灰化（参考实现在 0.8.29 之前就吃过这个亏）。
 *
 * <h3>选中态</h3>
 *
 * [PageState.isCurrentPage] 直接读宿主导航栈 —— 不维护自己的 tab，
 * 否则返回键、程序化跳转都会让高亮失同步。
 */
@Composable
fun KugouSidebarRow() {
    SaltSidebarRow(
        label = LABEL,
        selected = PageState.isCurrentPage(),
        onClick = { PageState.requestOpen() },
    ) {
        Image(
            painter = rememberVectorPainter(KugouLogo.Vector),
            contentDescription = LABEL,
            modifier = Modifier
                .align(Alignment.Center)
                .size(ICON_SIZE),
        )
    }
}

/** 侧栏与无障碍描述共用的名字。 */
internal const val LABEL = "酷狗音乐"

/**
 * 图标尺寸。
 *
 * 取 18dp 与宿主 caption 按钮的图标一致；宿主自己的侧栏图标也是这一档
 * （`SidebarItemLayout` 内部按 `LocalDensity` 换算，18dp 在 100% DPI 下是 18px）。
 */
private val ICON_SIZE = 18.dp
