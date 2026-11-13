package cn.spw.kugou.lite.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 酷狗音乐的品牌图标（来自用户桌面上的 `Kugou.svg`）。
 *
 * ## 这份路径数据是怎么来的
 *
 * 原始 SVG 的 `<path>` 里带着**两层 transform**：
 *
 * ```text
 * <g transform="translate(174.00, 24.00) scale(0.4414)">
 *   <path transform="translate(-97.24713134765625, 77.7977294921875)" d="M328.178326 0h367.600683…"/>
 * ```
 *
 * 而且 `d` 里混用了 `m / h / c / a / z`（含**椭圆弧** `a`，PathBuilder 虽然支持
 * `arcTo`，但手写几个大圆环的弧参数极易错）。
 *
 * 所以路径由脚本离线展开后写死在这里：
 *
 * 1. 按 SVG 语义把两层 transform 复合成 `P' = (174 + 0.4414·(x − 97.247), 24 + 0.4414·(y + 77.798))`；
 * 2. 把相对指令统一成绝对坐标，**椭圆弧按标准公式拆成 ≤90° 的三次贝塞尔**；
 * 3. 取包围盒（正方形 451.97×451.97）等比缩放到 24×24 并居中。
 *
 * 于是这里只剩 `moveTo / lineTo / curveTo / close` 四种指令，与
 * [KugouIcons] 里其它图标的写法完全一致，不需要运行时解析 SVG。
 *
 * ## 为什么用 NonZero 填充就能出「镂空」
 *
 * 5 段子路径的有向面积正负交替（外框 +、圆环 −、内圆 +、两笔「K」−），
 * `PathFillType.NonZero` 下负向子路径自然成为洞 —— 这正是原 SVG 的观感，
 * 不需要手动设置 `EvenOdd`。
 *
 * ## 颜色
 *
 * 品牌蓝 `#008AD4`。宿主侧栏的原生图标是灰的，但品牌入口用品牌色更易辨识，
 * 而且这里用 `Image` + 预着色的 `SolidColor` 绘制、**不传 colorFilter**，
 * 不会被宿主的内容色灰化。
 */
internal object KugouLogo {

    /** 品牌蓝（取自原始 SVG 的 `fill="#008AD4"`）。 */
    val BrandColor: Color = Color(0xFF008AD4)

    /** 24×24 视口、归一化后的品牌图标。 */
    val Vector: ImageVector by lazy {
        ImageVector.Builder(
            name = "KugouLogo",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(BrandColor)) {
                // 外框：圆角方形
                moveTo(7.692f, 0.000f)
                lineTo(16.308f, 0.000f)
                curveTo(18.983f, 0.000f, 19.953f, 0.278f, 20.931f, 0.801f)
                curveTo(21.897f, 1.312f, 22.688f, 2.103f, 23.199f, 3.069f)
                curveTo(23.722f, 4.047f, 24.000f, 5.017f, 24.000f, 7.692f)
                lineTo(24.000f, 16.308f)
                curveTo(24.000f, 18.983f, 23.722f, 19.953f, 23.199f, 20.931f)
                curveTo(22.688f, 21.897f, 21.897f, 22.688f, 20.931f, 23.199f)
                curveTo(19.953f, 23.722f, 18.983f, 24.000f, 16.308f, 24.000f)
                lineTo(7.692f, 24.000f)
                curveTo(5.017f, 24.000f, 4.047f, 23.722f, 3.069f, 23.199f)
                curveTo(2.103f, 22.687f, 1.312f, 21.897f, 0.801f, 20.930f)
                curveTo(0.278f, 19.953f, 0.000f, 18.983f, 0.000f, 16.308f)
                lineTo(0.000f, 7.692f)
                curveTo(0.000f, 5.017f, 0.278f, 4.047f, 0.801f, 3.069f)
                curveTo(1.313f, 2.103f, 2.103f, 1.312f, 3.070f, 0.801f)
                curveTo(4.047f, 0.278f, 5.017f, 0.000f, 7.692f, 0.000f)
                close()
                // 圆环外沿（负向 → 挖掉）
                moveTo(11.535f, 2.011f)
                curveTo(8.742f, 2.121f, 6.034f, 3.484f, 4.274f, 5.653f)
                curveTo(2.813f, 7.419f, 2.009f, 9.638f, 2.000f, 11.930f)
                curveTo(1.982f, 13.597f, 2.385f, 15.242f, 3.172f, 16.712f)
                curveTo(3.941f, 18.144f, 5.046f, 19.369f, 6.392f, 20.282f)
                curveTo(8.064f, 21.418f, 10.042f, 22.017f, 12.063f, 22.000f)
                curveTo(13.922f, 21.991f, 15.741f, 21.463f, 17.315f, 20.474f)
                curveTo(19.072f, 19.368f, 20.444f, 17.745f, 21.240f, 15.827f)
                curveTo(22.022f, 13.959f, 22.202f, 11.851f, 21.773f, 9.874f)
                curveTo(21.213f, 7.242f, 19.527f, 4.879f, 17.231f, 3.478f)
                curveTo(15.523f, 2.428f, 13.538f, 1.917f, 11.535f, 2.012f)
                close()
                // 内圆（正向 → 实心）
                moveTo(11.545f, 3.254f)
                curveTo(13.199f, 3.167f, 14.844f, 3.551f, 16.288f, 4.362f)
                curveTo(18.155f, 5.412f, 19.574f, 7.109f, 20.278f, 9.133f)
                curveTo(20.892f, 10.885f, 20.912f, 12.833f, 20.365f, 14.604f)
                curveTo(19.804f, 16.402f, 18.678f, 17.972f, 17.155f, 19.080f)
                curveTo(15.640f, 20.188f, 13.807f, 20.776f, 11.930f, 20.758f)
                curveTo(10.167f, 20.749f, 8.448f, 20.204f, 7.002f, 19.194f)
                curveTo(5.398f, 18.084f, 4.212f, 16.469f, 3.633f, 14.606f)
                curveTo(3.118f, 12.941f, 3.107f, 11.160f, 3.602f, 9.489f)
                curveTo(4.164f, 7.621f, 5.332f, 5.993f, 6.922f, 4.862f)
                curveTo(8.277f, 3.895f, 9.881f, 3.336f, 11.544f, 3.254f)
                close()
                // 「K」竖笔（负向 → 挖掉）
                moveTo(8.652f, 7.256f)
                curveTo(8.325f, 10.422f, 8.025f, 13.591f, 7.694f, 16.756f)
                curveTo(8.474f, 16.767f, 9.253f, 16.753f, 10.032f, 16.764f)
                curveTo(10.349f, 13.592f, 10.710f, 10.424f, 11.018f, 7.252f)
                curveTo(10.230f, 7.268f, 9.441f, 7.261f, 8.652f, 7.256f)
                close()
                // 「K」斜笔（负向 → 挖掉）
                moveTo(13.777f, 7.898f)
                curveTo(12.952f, 8.714f, 12.086f, 9.489f, 11.279f, 10.324f)
                curveTo(10.837f, 10.810f, 10.471f, 11.457f, 10.577f, 12.135f)
                curveTo(10.610f, 12.612f, 10.876f, 13.025f, 11.144f, 13.405f)
                curveTo(11.929f, 14.511f, 12.665f, 15.651f, 13.444f, 16.761f)
                curveTo(14.446f, 16.756f, 15.448f, 16.765f, 16.450f, 16.757f)
                curveTo(15.480f, 15.522f, 14.538f, 14.264f, 13.592f, 13.010f)
                curveTo(13.359f, 12.686f, 13.067f, 12.355f, 13.039f, 11.939f)
                curveTo(12.999f, 11.576f, 13.257f, 11.279f, 13.487f, 11.033f)
                curveTo(14.759f, 9.773f, 16.039f, 8.523f, 17.305f, 7.257f)
                curveTo(16.357f, 7.269f, 15.409f, 7.262f, 14.461f, 7.258f)
                curveTo(14.229f, 7.467f, 14.006f, 7.685f, 13.777f, 7.898f)
                close()
            }
        }.build()
    }
}
