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
import cn.spw.kugou.lite.sidecar.Id3CoverWriter
import cn.spw.kugou.lite.sidecar.KugouApi

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
        KugouLiteTab.Home -> DiscoverPage()

        // 发现页编辑面板
        KugouLiteTab.DiscoverEditor -> DiscoverEditorPage()

        KugouLiteTab.Search -> SubPage(title = "搜索", onBack = { PageNav.backHome() }) {
            // 搜索页点歌走统一的播放流程（与歌单/榜单/歌手页一致）
            SearchPage(onPick = { _, queue, index -> playSongsFromQueue(queue, index) })
        }

        KugouLiteTab.Playlists -> SubPage(title = "我的音乐", onBack = { PageNav.backHome() }) {
            PlaylistPage()
        }

        KugouLiteTab.Ranks -> SubPage(title = "排行榜", onBack = { PageNav.backHome() }) {
            RankPage()
        }

        KugouLiteTab.Daily -> SubPage(title = "每日推荐", onBack = { PageNav.backHome() }) {
            DailyPage()
        }

        // 统一详情页：歌单/榜单/广场/每日推荐/发现区块/专辑 共用
        KugouLiteTab.SongList -> DetailPage()

        KugouLiteTab.DiscoverDetail -> DetailPage()

        KugouLiteTab.Login -> SubPage(title = "登录", onBack = { PageNav.backHome() }) {
            LoginPage()
        }

        KugouLiteTab.Settings -> SubPage(title = "设置", onBack = { PageNav.backHome() }) {
            SettingsPage()
        }

        // 缓存管理：从设置页 push 进入，back 回设置（B1）
        KugouLiteTab.CacheManage -> SubPage(title = "缓存管理", onBack = { PageNav.backHome() }) {
            CacheManagePage()
        }

        KugouLiteTab.Square -> SubPage(title = "歌单广场", onBack = { PageNav.backHome() }) {
            SquarePage()
        }

        KugouLiteTab.History -> SubPage(title = "播放历史", onBack = { PageNav.backHome() }) {
            HistoryPage()
        }

        KugouLiteTab.Welfare -> SubPage(title = "签到日历", onBack = { PageNav.backHome() }) {
            WelfarePage()
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
