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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import cn.spw.kugou.lite.sidecar.DiscoverConfig

/**
 * 发现页「编辑」面板。
 *
 * 对应 MD3Music 的 `DiscoverSectionManager`：布局（单列/双列）、
 * 区块排序与显示开关、重置。所有改动即时写入 [DiscoverConfig]，
 * 返回发现页时由 `LaunchedEffect(reloadTick)` 重新读取生效。
 */

/** 区块 id → 中文名。 */
private val SECTION_LABELS = mapOf(
    "fm" to "私人 FM",
    "daily" to "每日推荐",
    "discover" to "刷歌推荐",
    "newSongs" to "新歌速递",
    "newAlbums" to "新碟上架",
)

@Composable
fun DiscoverEditorPage() {
    var config by remember { mutableStateOf(DiscoverConfig.load()) }

    /** 每次改动都即时持久化。 */
    fun apply(next: DiscoverConfig.Config) {
        config = next
        DiscoverConfig.save(next)
    }

    SaltScreen(title = "编辑发现页", onBackClick = { PageNav.backHome() }) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
        ) {
            // ---------------------------------------------------------- 布局
            SaltGroupTitle("页面布局")
            SaltCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    LayoutChip(
                        label = "单列",
                        selected = config.layout == DiscoverConfig.Layout.SINGLE,
                        modifier = Modifier.weight(1f),
                    ) {
                        apply(config.copy(layout = DiscoverConfig.Layout.SINGLE))
                    }
                    LayoutChip(
                        label = "双列",
                        selected = config.layout == DiscoverConfig.Layout.PAIR,
                        modifier = Modifier.weight(1f),
                    ) {
                        apply(config.copy(layout = DiscoverConfig.Layout.PAIR))
                    }
                }
            }

            // ---------------------------------------------------------- 区块
            SaltGroupTitle("区块（点击 ⇅ 调整顺序，点击 ○/● 开关显示）")
            SaltCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                config.order.forEachIndexed { index, id ->
                    val visible = id !in config.hidden
                    val label = SECTION_LABELS[id] ?: id
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 上移 / 下移
                        Column(
                            Modifier.padding(end = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text3(
                                text = "▲",
                                color = if (index > 0) KugouTheme.colors.text else KugouTheme.colors.faint,
                                modifier = Modifier.clickable(enabled = index > 0) {
                                    apply(config.move(id, -1))
                                },
                            )
                            Text3(
                                text = "▼",
                                color = if (index < config.order.lastIndex) {
                                    KugouTheme.colors.text
                                } else {
                                    KugouTheme.colors.faint
                                },
                                modifier = Modifier.clickable(enabled = index < config.order.lastIndex) {
                                    apply(config.move(id, 1))
                                },
                            )
                        }

                        Column(Modifier.weight(1f)) {
                            Text3(text = label, color = KugouTheme.colors.text)
                            Text3(
                                text = if (visible) "显示在发现页" else "未显示",
                                color = KugouTheme.colors.muted,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }

                        // 显示开关
                        Text3(
                            text = if (visible) "● 开" else "○ 关",
                            color = if (visible) KugouTheme.colors.text else KugouTheme.colors.faint,
                            modifier = Modifier.clickable {
                                val nextHidden = if (visible) {
                                    config.hidden + id
                                } else {
                                    config.hidden - id
                                }
                                apply(config.copy(hidden = nextHidden))
                            },
                        )
                    }
                    if (index < config.order.lastIndex) {
                        androidx.compose.foundation.layout.Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .height(1.dp)
                                .background(KugouTheme.colors.line),
                        )
                    }
                }
            }

            // ---------------------------------------------------------- 重置
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { apply(DiscoverConfig.default()) }
                    .padding(vertical = 18.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                Text3("重置为默认", color = KugouTheme.colors.muted)
            }
        }
    }
}

/** 布局选项胶囊。 */
@Composable
private fun LayoutChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val c = KugouTheme.colors
    androidx.compose.foundation.layout.Box(
        modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .background(if (selected) c.selectedBackground else c.cardBackground)
            .then(
                Modifier.border(
                    1.dp,
                    c.line,
                    androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                ),
            )
            .clickable { onClick() }
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text3(text = label, color = if (selected) c.text else c.muted)
    }
}
