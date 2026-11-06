package cn.spw.kugou.lite.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.spw.kugou.lite.diag.Diag
import cn.spw.kugou.lite.host.PlaybackBridge
import cn.spw.kugou.lite.host.TrackWatcher
import cn.spw.kugou.lite.sidecar.ApiExec
import cn.spw.kugou.lite.sidecar.Async
import cn.spw.kugou.lite.sidecar.AudioCache
import cn.spw.kugou.lite.sidecar.AuthStore
import cn.spw.kugou.lite.sidecar.Id3CoverWriter
import cn.spw.kugou.lite.sidecar.KugouApi
import cn.spw.kugou.lite.sidecar.SidecarManager
import cn.spw.kugou.lite.sidecar.rememberAsyncState

/**
 * 酷狗 Lite 页面 —— 渲染在**宿主导航内部**，不是浮层。
 *
 * 结构完全由宿主原生 Salt 组件组成，外观与宿主二级页面一致。
 *
 * ## P3 结构
 *
 * ```
 * SaltScreen
 *   ├ Home       → 分组入口（搜索 / 歌单 / 榜单 / 每日推荐 / 账户 / 关于）
 *   ├ Search     → [SearchPage]
 *   └ 其他子页   → 占位（P3 后续补）
 * ```
 *
 * ## 布局硬约束（都是真机踩坑换来的，改之前先看 docs/verify-P2.md）
 *
 * 1. **不要 `fillMaxSize()`** —— 外框自带滚动容器，撑满视口会让滚动范围归零。
 * 2. **外层不加水平 padding** —— `ItemOuterTitle` 自带 16dp、`Card` 不带，
 *    外层统一加会让分组标题比卡片文字多缩进 16px。水平留白下放到 `Card`。
 * 3. **底部留 16dp** —— 否则滚到底时最后一张卡片紧贴播放栏。
 */
@Composable
fun KugouLitePage() {
    // 主题：跟随宿主的深色设置（含「跟随系统」的情形），每秒刷新一次。
    // 所有子页面从 LocalKugouColors 取色，不再硬编码。
    val dark = rememberHostDark()
    KugouLiteTheme(dark) {
        KugouLiteBody()
    }
}

/** 主题包装（供宿主注入点直接调用时保持一层）。 */
@Composable
private fun KugouLiteTheme(dark: Boolean, content: @Composable () -> Unit) {
    KugouThemeProvider(dark, content)
}

@Composable
private fun KugouLiteBody() {
    // 主页永远是根；子页用宿主原生返回按钮回上一级。
    when (PageNav.tab) {
        KugouLiteTab.Home -> HomePage()

        KugouLiteTab.Search -> SubPage(title = "搜索", onBack = { PageNav.backHome() }) {
            // 搜索页点歌走统一的播放流程（与歌单/榜单/歌手页一致）
            SearchPage(onPick = { _, queue, index -> playSongsFromQueue(queue, index) })
        }

        KugouLiteTab.Playlists -> SubPage(title = "我的歌单", onBack = { PageNav.backHome() }) {
            PlaylistPage()
        }

        KugouLiteTab.Ranks -> SubPage(title = "排行榜", onBack = { PageNav.backHome() }) {
            RankPage()
        }

        KugouLiteTab.Daily -> SubPage(title = "每日推荐", onBack = { PageNav.backHome() }) {
            DailyPage()
        }

        // 歌单详情 / 榜单详情共用同一个歌曲列表页，标题由 PageNav 决定。
        KugouLiteTab.SongList -> SubPage(
            title = PageNav.detailTitle.ifBlank { "歌曲列表" },
            onBack = { PageNav.backHome() },
        ) {
            SongListPage()
        }

        KugouLiteTab.Login -> SubPage(title = "登录", onBack = { PageNav.backHome() }) {
            LoginPage()
        }

        KugouLiteTab.Settings -> SubPage(title = "设置", onBack = { PageNav.backHome() }) {
            SettingsPage()
        }

        KugouLiteTab.Artist -> SubPage(
            title = PageNav.detailTitle.ifBlank { "歌手" },
            onBack = { PageNav.backHome() },
        ) {
            ArtistPage()
        }

        KugouLiteTab.Album -> SubPage(
            title = PageNav.detailTitle.ifBlank { "专辑" },
            onBack = { PageNav.backHome() },
        ) {
            AlbumPage()
        }
    }
}

/** 子页外壳：宿主原生页框 + 返回按钮。 */
@Composable
private fun SubPage(
    title: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    SaltScreen(title = title, onBackClick = onBack) {
        content()
    }
}

@Composable
private fun HomePage() {
    val sidecarState = rememberAsyncState<String?>()
    LaunchedEffect(Unit) {
        if (!SidecarManager.isRunning()) {
            sidecarState.load { SidecarManager.ensureStarted() }
        }
    }

    SaltScreen(title = "Kugou Lite") {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
        ) {
            // 顶栏：大标题 + 副标题，建立页面层级
            HomeHeader()

            // 快捷入口：搜索和每日推荐是最高频动作，做成并排大按钮
            SectionTitle("快速开始")
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                QuickAction(
                    title = "搜索",
                    subtitle = "歌名 / 歌手",
                    modifier = Modifier.weight(1f),
                    onClick = { PageNav.go(KugouLiteTab.Search) },
                )
                QuickAction(
                    title = "每日推荐",
                    subtitle = "今天为你挑的",
                    modifier = Modifier.weight(1f),
                    onClick = { PageNav.go(KugouLiteTab.Daily) },
                )
            }

            SectionTitle("我的音乐")
            ThemeCard(Modifier.padding(horizontal = 16.dp)) {
                HomeEntry("我的歌单", "自建歌单与收藏") { PageNav.go(KugouLiteTab.Playlists) }
                HairLine()
                HomeEntry("排行榜", "飙升榜 / 热歌榜") { PageNav.go(KugouLiteTab.Ranks) }
            }

            SectionTitle("账户")
            ThemeCard(Modifier.padding(horizontal = 16.dp)) {
                HomeEntry(
                    title = if (AuthStore.isLoggedIn) {
                        "已登录：" + (AuthStore.nickname ?: AuthStore.userId)
                    } else {
                        "未登录"
                    },
                    subtitle = if (AuthStore.isLoggedIn) {
                        "点击可重新扫码登录"
                    } else {
                        "扫码登录后可取流、读歌单、收藏"
                    },
                ) { PageNav.go(KugouLiteTab.Login) }
                HairLine()
                HomeEntry("设置", "音质、缓存、退出登录") { PageNav.go(KugouLiteTab.Settings) }
                HairLine()
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    CaptionText(
                        text = when (val s = sidecarState.value) {
                            is Async.Loading -> "正在准备在线服务…"
                            else -> SidecarManager.statusText()
                        },
                        color = KugouTheme.colors.faint,
                    )
                }
            }

            SectionTitle("关于")
            ThemeCard(Modifier.padding(horizontal = 16.dp)) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    BodyText("Kugou Lite", color = KugouTheme.colors.text, size = 14)
                    CaptionText(
                        text = "酷狗曲库接入 Salt Player。搜索、歌单、榜单、逐字歌词。",
                        color = KugouTheme.colors.muted,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

/** 主页顶栏。 */
@Composable
private fun HomeHeader() {
    val c = KugouTheme.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        BodyText("Kugou Lite", color = c.text, size = 21, weight = 600)
        CaptionText(
            text = "酷狗曲库接入 Salt Player",
            color = c.muted,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

/** 快捷入口按钮。用 accentSoft 作底色，与卡片区分开形成层级。 */
@Composable
private fun QuickAction(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val c = KugouTheme.colors
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(c.accentSoft)
            .border(1.dp, c.line, RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        BodyText(title, color = c.accent, size = 15, weight = 500)
        CaptionText(subtitle, color = c.muted, size = 11)
    }
}

/** 主页列表项。 */
@Composable
private fun HomeEntry(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    val c = KugouTheme.colors
    HoverRow(onClick = onClick) {
        Column(Modifier.weight(1f)) {
            BodyText(title, color = c.text, size = 14)
            CaptionText(subtitle, color = c.muted, modifier = Modifier.padding(top = 2.dp))
        }
        CaptionText("\u203a", color = c.faint)
    }
}

/** 卡片内分隔线。 */
@Composable
private fun HairLine() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(1.dp)
            .background(KugouTheme.colors.line),
    )
}
