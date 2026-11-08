package cn.spw.kugou.lite.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 插件图标（矢量，替代之前的文字字形）。
 *
 * ## 为什么自己定义 ImageVector
 *
 * 之前用 `♪ ☰ ☀ ▦ ↻ ⚙` 这类 Unicode 字符当图标，真机验证暴露两个问题：
 * 1. **`☀` 被系统渲染成彩色 emoji**（页面上是一坨橙色），完全脱离中性灰设计
 * 2. 各字符来自不同字体、粗细与视觉重量不一致
 *
 * 宿主 JVM 里虽有 `androidx.compose.material.icons.*`，但那是宿主私有依赖，
 * 插件没有把它声明进 classpath，直接引用会在运行时 `NoClassDefFoundError`。
 * 所以这里用**标准 `ImageVector.Builder`**（`androidx.compose.ui.graphics.vector`
 * 是 Compose UI 的公共 API，宿主必然提供）手写路径数据 —— 24×24 视口、
 * Material 风格线性图标，颜色由 `tint` 决定（默认跟随文字色）。
 *
 * ## 用法
 *
 * ```kotlin
 * KugouIcon(KugouIcons.Search, size = 18.dp, tint = KugouTheme.colors.text)
 * ```
 */
object KugouIcons {

    /** 我的音乐：耳机 / 音符。 */
    val Music: ImageVector by lazy {
        icon("Music") {
            moveTo(12f, 3f); curveTo(7.03f, 3f, 3f, 7.03f, 3f, 12f)
            curveTo(3f, 16.97f, 7.03f, 21f, 12f, 21f); curveTo(16.97f, 21f, 21f, 16.97f, 21f, 12f)
            curveTo(21f, 7.03f, 16.97f, 3f, 12f, 3f); close()
            moveTo(10f, 17f); curveTo(8.9f, 17f, 8f, 16.1f, 8f, 15f)
            curveTo(8f, 13.9f, 8.9f, 13f, 10f, 13f); curveTo(10.74f, 13f, 11.41f, 13.4f, 11.75f, 14f)
            lineTo(15f, 14f); lineTo(15f, 8f); lineTo(19f, 7f); lineTo(19f, 9f)
            lineTo(16.5f, 9.5f); lineTo(16.5f, 15f); curveTo(16.5f, 16.1f, 15.6f, 17f, 14.5f, 17f)
            close()
        }
    }

    /** 排行榜：三条横线（列表）。 */
    val Ranks: ImageVector by lazy {
        icon("Ranks") {
            moveTo(4f, 6f); horizontalLineTo(20f); verticalLineTo(8f); horizontalLineTo(4f); close()
            moveTo(4f, 11f); horizontalLineTo(20f); verticalLineTo(13f); horizontalLineTo(4f); close()
            moveTo(4f, 16f); horizontalLineTo(14f); verticalLineTo(18f); horizontalLineTo(4f); close()
        }
    }

    /** 每日推荐：日历。 */
    val Daily: ImageVector by lazy {
        icon("Daily") {
            moveTo(19f, 4f); horizontalLineTo(18f); verticalLineTo(2f); horizontalLineTo(16f); verticalLineTo(4f)
            horizontalLineTo(8f); verticalLineTo(2f); horizontalLineTo(6f); verticalLineTo(4f); horizontalLineTo(5f)
            curveTo(3.89f, 4f, 3f, 4.9f, 3f, 6f); verticalLineTo(20f)
            curveTo(3f, 21.1f, 3.89f, 22f, 5f, 22f); horizontalLineTo(19f)
            curveTo(20.1f, 22f, 21f, 21.1f, 21f, 20f); verticalLineTo(6f)
            curveTo(21f, 4.9f, 20.1f, 4f, 19f, 4f); close()
            moveTo(19f, 20f); horizontalLineTo(5f); verticalLineTo(9f); horizontalLineTo(19f); close()
            moveTo(8f, 12f); horizontalLineTo(11f); verticalLineTo(15f); horizontalLineTo(8f); close()
        }
    }

    /** 歌单广场：网格。 */
    val Square: ImageVector by lazy {
        icon("Square") {
            moveTo(3f, 3f); horizontalLineTo(10f); verticalLineTo(10f); horizontalLineTo(3f); close()
            moveTo(14f, 3f); horizontalLineTo(21f); verticalLineTo(10f); horizontalLineTo(14f); close()
            moveTo(3f, 14f); horizontalLineTo(10f); verticalLineTo(21f); horizontalLineTo(3f); close()
            moveTo(14f, 14f); horizontalLineTo(21f); verticalLineTo(21f); horizontalLineTo(14f); close()
        }
    }

    /** 播放历史：时钟。 */
    val History: ImageVector by lazy {
        icon("History") {
            moveTo(12f, 3f); curveTo(7.03f, 3f, 3f, 7.03f, 3f, 12f)
            curveTo(3f, 16.97f, 7.03f, 21f, 12f, 21f); curveTo(16.97f, 21f, 21f, 16.97f, 21f, 12f)
            curveTo(21f, 7.03f, 16.97f, 3f, 12f, 3f); close()
            moveTo(12f, 19f); curveTo(8.13f, 19f, 5f, 15.87f, 5f, 12f)
            curveTo(5f, 8.13f, 8.13f, 5f, 12f, 5f); curveTo(15.87f, 5f, 19f, 8.13f, 19f, 12f)
            curveTo(19f, 15.87f, 15.87f, 19f, 12f, 19f); close()
            moveTo(11f, 7f); horizontalLineTo(13f); verticalLineTo(12.5f); horizontalLineTo(17f)
            verticalLineTo(14.5f); horizontalLineTo(11f); close()
        }
    }

    /** 设置：齿轮（简化）。 */
    val Settings: ImageVector by lazy {
        icon("Settings") {
            moveTo(12f, 8f); curveTo(9.79f, 8f, 8f, 9.79f, 8f, 12f)
            curveTo(8f, 14.21f, 9.79f, 16f, 12f, 16f); curveTo(14.21f, 16f, 16f, 14.21f, 16f, 12f)
            curveTo(16f, 9.79f, 14.21f, 8f, 12f, 8f); close()
            moveTo(12f, 14f); curveTo(10.9f, 14f, 10f, 13.1f, 10f, 12f)
            curveTo(10f, 10.9f, 10.9f, 10f, 12f, 10f); curveTo(13.1f, 10f, 14f, 10.9f, 14f, 12f)
            curveTo(14f, 13.1f, 13.1f, 14f, 12f, 14f); close()
            moveTo(19.43f, 12.98f); curveTo(19.47f, 12.66f, 19.5f, 12.34f, 19.5f, 12f)
            curveTo(19.5f, 11.66f, 19.47f, 11.34f, 19.43f, 11.02f)
            lineTo(21.54f, 9.37f); lineTo(19.54f, 5.9f); lineTo(17.04f, 6.9f)
            curveTo(16.45f, 6.46f, 15.79f, 6.12f, 15.07f, 5.91f); lineTo(14.7f, 3.26f)
            lineTo(10.7f, 3.26f); lineTo(10.33f, 5.91f)
            curveTo(9.61f, 6.12f, 8.95f, 6.46f, 8.36f, 6.9f); lineTo(5.86f, 5.9f)
            lineTo(3.86f, 9.37f); lineTo(5.97f, 11.02f)
            curveTo(5.93f, 11.34f, 5.9f, 11.66f, 5.9f, 12f)
            curveTo(5.9f, 12.34f, 5.93f, 12.66f, 5.97f, 12.98f)
            lineTo(3.86f, 14.63f); lineTo(5.86f, 18.1f); lineTo(8.36f, 17.1f)
            curveTo(8.95f, 17.54f, 9.61f, 17.88f, 10.33f, 18.09f); lineTo(10.7f, 20.74f)
            lineTo(14.7f, 20.74f); lineTo(15.07f, 18.09f)
            curveTo(15.79f, 17.88f, 16.45f, 17.54f, 17.04f, 17.1f); lineTo(19.54f, 18.1f)
            lineTo(21.54f, 14.63f); close()
        }
    }

    /** 搜索。 */
    val Search: ImageVector by lazy {
        icon("Search") {
            moveTo(15.5f, 14f); horizontalLineTo(14.71f); lineTo(14.43f, 13.73f)
            curveTo(15.41f, 12.59f, 16f, 11.11f, 16f, 9.5f)
            curveTo(16f, 5.91f, 13.09f, 3f, 9.5f, 3f); curveTo(5.91f, 3f, 3f, 5.91f, 3f, 9.5f)
            curveTo(3f, 13.09f, 5.91f, 16f, 9.5f, 16f); curveTo(11.11f, 16f, 12.59f, 15.41f, 13.73f, 14.43f)
            lineTo(14f, 14.71f); verticalLineTo(15.5f); lineTo(19f, 20.49f); lineTo(20.49f, 19f); close()
            moveTo(9.5f, 14f); curveTo(7.01f, 14f, 5f, 11.99f, 5f, 9.5f)
            curveTo(5f, 7.01f, 7.01f, 5f, 9.5f, 5f); curveTo(11.99f, 5f, 14f, 7.01f, 14f, 9.5f)
            curveTo(14f, 11.99f, 11.99f, 14f, 9.5f, 14f); close()
        }
    }

    /** 播放。 */
    val Play: ImageVector by lazy {
        icon("Play") {
            moveTo(8f, 5f); verticalLineTo(19f)
            lineTo(19f, 12f); close()
        }
    }

    /** 返回（左箭头）。 */
    val Back: ImageVector by lazy {
        icon("Back") {
            moveTo(20f, 11f); horizontalLineTo(7.83f); lineTo(13.42f, 5.41f)
            lineTo(12f, 4f); lineTo(4f, 12f); lineTo(12f, 20f); lineTo(13.41f, 18.59f)
            lineTo(7.83f, 13f); horizontalLineTo(20f); close()
        }
    }

    /** 收藏（心形）。 */
    val Favorite: ImageVector by lazy {
        icon("Favorite") {
            moveTo(12f, 21.35f); lineTo(10.55f, 20.03f)
            curveTo(5.4f, 15.36f, 2f, 12.28f, 2f, 8.5f)
            curveTo(2f, 5.42f, 4.42f, 3f, 7.5f, 3f)
            curveTo(9.24f, 3f, 10.91f, 3.81f, 12f, 5.09f)
            curveTo(13.09f, 3.81f, 14.76f, 3f, 16.5f, 3f)
            curveTo(19.58f, 3f, 22f, 5.42f, 22f, 8.5f)
            curveTo(22f, 12.28f, 18.6f, 15.36f, 13.45f, 20.04f); close()
        }
    }

    /** 返回「上一级」的通用箭头（详情页头部用）。 */
    val ChevronRight: ImageVector by lazy {
        icon("ChevronRight") {
            moveTo(8.59f, 16.59f); lineTo(13.17f, 12f)
            lineTo(8.59f, 7.41f); lineTo(10f, 6f); lineTo(16f, 12f)
            lineTo(10f, 18f); close()
        }
    }

    // ------------------------------------------------------------ 构造辅助

    /** 24×24 视口、1 倍缩放、纯色填充的参数化入口。 */
    private fun icon(name: String, block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black), pathBuilder = block)
        }.build()
}

/**
 * 绘制插件矢量图标。
 *
 * @param tint 图标颜色；默认取当前主题的正文色（`KugouTheme.colors.text`）。
 *             用 `rememberVectorPainter` 而不是 material 的 `Icon` 组件，
 *             因为宿主没有暴露 material3 的 Icon，而 painter 是 Compose UI 公共 API。
 */
@Composable
internal fun KugouIcon(
    vector: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
    tint: Color = KugouTheme.colors.text,
) {
    val painter = rememberVectorPainter(vector)
    Image(
        painter = painter,
        contentDescription = null,
        modifier = modifier.size(size),
        colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(tint),
    )
}
