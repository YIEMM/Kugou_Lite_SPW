package cn.spw.kugou.lite.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

/**
 * 通用的「封面 + 标题 + 副标题」列表行。
 *
 * 用于歌单、排行榜、专辑等**集合型**条目。与 [SongRow] 的区别：
 * - SongRow 面对的是单曲（有歌手、时长、收藏按钮）
 * - 本组件面对的是集合（封面更大、副标题是曲目数/说明）
 *
 * ## 视觉规格（参考主流音乐客户端的歌单列表）
 *
 * - 封面 56dp 圆角 8dp —— 足够看清又不会让行太高
 * - 标题 15sp 主色，副标题 12sp 次要色
 * - 行内边距 14dp 垂直，让列表有呼吸感
 * - 悬停/点击时整行高亮（由 clickable 的 ripple 提供）
 *
 * @param coverUrl 封面地址；为空或加载失败时显示音符占位
 * @param title 主标题
 * @param subtitle 副标题（曲目数等）
 * @param badge 右上角标记（如「我喜欢」用 ♥）
 * @param onClick 点击回调
 */
@Composable
fun CollectionRow(
    coverUrl: String?,
    title: String,
    subtitle: String,
    badge: String? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverBox(coverUrl, 56)
        Column(
            Modifier
                .padding(start = 12.dp)
                .weight(1f),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text3(text = title, color = Color(0xFFEDEDED))
                if (!badge.isNullOrBlank()) {
                    Text3(text = badge, color = Color(0xFFE8736A))
                }
            }
            if (subtitle.isNotBlank()) {
                Text3(
                    text = subtitle,
                    color = Color(0xFF9A9A9A),
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
        Text3("›", color = Color(0xFF5E5E5E))
    }
}

/**
 * 方形封面（带圆角与占位）。
 *
 * 加载中与加载失败都显示音符占位，保证行高稳定 ——
 * 图片异步到达时不会让整个列表跳动。
 */
@Composable
fun CoverBox(url: String?, sizeDp: Int, cornerDp: Int = 8) {
    val bitmap = rememberCover(url)
    val shape = RoundedCornerShape(cornerDp.dp)
    Box(
        Modifier
            .size(sizeDp.dp)
            .background(Color(0xFF2E2E2E), shape),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(sizeDp.dp).clip(shape),
            )
        } else {
            Text3("♪", color = Color(0xFF6E6E6E))
        }
    }
}
