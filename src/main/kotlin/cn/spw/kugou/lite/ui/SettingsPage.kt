package cn.spw.kugou.lite.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import cn.spw.kugou.lite.diag.Diag
import cn.spw.kugou.lite.sidecar.ApiExec
import cn.spw.kugou.lite.sidecar.AudioCache
import cn.spw.kugou.lite.sidecar.AuthStore
import cn.spw.kugou.lite.sidecar.Settings

/**
 * 设置页。
 *
 * 包含三块：
 * 1. **音质偏好** —— 选择自动/128k/320k/无损。
 *    注意酷狗音质与会员等级绑定，非会员选高音质会静默降级，
 *    所以每个档位下方标注了「通常需要会员」。
 * 2. **缓存管理** —— 显示占用并支持一键清理。
 * 3. **账号** —— 显示当前登录态，支持退出登录。
 *
 * ## 为什么音质要如实标注
 *
 * 服务端对非会员请求 flac/320 常直接返回 128k 或失败。如果 UI 按请求值
 * 显示「无损」，用户会以为拿到了高音质。所以：
 * - 选择器只表达**偏好**
 * - 实际音质由 [cn.spw.kugou.lite.sidecar.StreamInfo.qualityText] 在播放时如实反映
 */
@Composable
fun SettingsPage() {
    var quality by remember { mutableStateOf(Settings.quality) }
    var cacheText by remember { mutableStateOf("计算中…") }

    // 首次进入时算一次缓存占用（可能较慢，放后台）
    androidx.compose.runtime.LaunchedEffect(Unit) {
        ApiExec.run({ r ->
            r.onSuccess { size ->
                cacheText = formatSize(size)
            }
            r.onFailure { cacheText = "统计失败" }
        }) {
            AudioCache.usage()
        }
    }

    Column(
        Modifier.fillMaxWidth().padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SaltGroupTitle("音质")

        SaltCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Settings.Quality.entries.forEach { q ->
                QualityRow(
                    quality = q,
                    selected = quality == q,
                    onClick = {
                        Settings.setQuality(q)
                        quality = q
                        Diag.info("[settings] 音质切换为 ${q.label}")
                    },
                )
            }
        }

        SaltGroupTitle("缓存")

        SaltCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text3("音频缓存", color = Color(0xFFEDEDED))
                    Text3(
                        "已下载的歌曲与封面",
                        color = Color(0xFF9A9A9A),
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Text3(cacheText, color = Color(0xFF9A9A9A))
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        cacheText = "清理中…"
                        ApiExec.run({ r ->
                            r.onSuccess { cacheText = "0 B" }
                            r.onFailure { cacheText = "清理失败" }
                        }) {
                            AudioCache.clear()
                            AudioCache.usage()
                        }
                    }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text3("清除缓存", color = Color(0xFF4CC2FF))
            }
        }

        SaltGroupTitle("账号")

        SaltCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text3(
                    text = if (AuthStore.isLoggedIn) {
                        "已登录：${AuthStore.nickname ?: AuthStore.userId}"
                    } else {
                        "未登录"
                    },
                    color = Color(0xFFEDEDED),
                )
                Text3(
                    text = "登录后可取流播放、读取我的歌单",
                    color = Color(0xFF9A9A9A),
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (AuthStore.isLoggedIn) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            AuthStore.clear()
                            Diag.info("[settings] 已退出登录")
                            PageNav.go(KugouLiteTab.Home)
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text3("退出登录", color = Color(0xFFE8736A))
                }
            }
        }
    }
}

/** 音质选项行（单选样式）。 */
@Composable
private fun QualityRow(
    quality: Settings.Quality,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text3(
                text = quality.label,
                color = if (selected) Color(0xFF4CC2FF) else Color(0xFFEDEDED),
            )
            Text3(
                text = quality.hint,
                color = Color(0xFF9A9A9A),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        // 选中标记：用字符而不是图标，避免依赖宿主图标资源
        if (selected) {
            Text3("✓", color = Color(0xFF4CC2FF))
        }
    }
}

/** 字节数格式化。 */
private fun formatSize(bytes: Long): String = when {
    bytes <= 0 -> "0 B"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    else -> "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
}
