package cn.spw.kugou.lite.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
    // 主页永远是根；子页用宿主原生返回按钮回上一级。
    when (PageNav.tab) {
        KugouLiteTab.Home -> HomePage()

        KugouLiteTab.Search -> SubPage(title = "搜索", onBack = { PageNav.backHome() }) {
            SearchPage(onPick = { song, queue, index ->
                Diag.info("[page] 搜索页点歌「${song.title}」- ${song.singer}（${index + 1}/${queue.size}）")
                ApiExec.run({ r -> r.onFailure { Diag.warn("[page] 播放流程异常：${it.message}") } }) {
                    val stream = KugouApi.streamUrl(song)
                    if (stream == null) {
                        Diag.warn("[page] 取流失败：${KugouApi.lastError ?: "未知原因"}")
                    } else {
                        val local = AudioCache.ensureLocal(song, stream)
                        if (local == null) Diag.warn("[page] 本地缓存失败，回退 HTTP 直链")
                        runCatching {
                            val lrc = KugouApi.lyricByHash(song.hash)
                            if (!lrc.isNullOrBlank() && local != null) {
                                Id3CoverWriter.writeLyrics(local, lrc)
                            }
                        }
                        val ok = PlaybackBridge.playQueue(queue, index, { s -> KugouApi.streamUrl(s) }, local)
                        if (!ok) {
                            Diag.warn("[page] 建立队列失败，回退单曲播放")
                            PlaybackBridge.play(song, stream, local)
                        }
                    }
                }
            })
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SaltGroupTitle("搜索与发现")
            SaltCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                SaltItem(
                    title = "搜索歌曲",
                    subtitle = "按歌名、歌手搜索在线曲库",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { PageNav.go(KugouLiteTab.Search) },
                )
                SaltItem(
                    title = "每日推荐",
                    subtitle = "每天更新的推荐歌单",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { PageNav.go(KugouLiteTab.Daily) },
                )
            }

            SaltGroupTitle("我的音乐")
            SaltCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                SaltItem(
                    title = "我的歌单",
                    subtitle = "酷狗账号里的自建歌单与收藏",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { PageNav.go(KugouLiteTab.Playlists) },
                )
                SaltItem(
                    title = "排行榜",
                    subtitle = "飙升榜、热歌榜等官方榜单",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { PageNav.go(KugouLiteTab.Ranks) },
                )
            }

            SaltGroupTitle("账户")
            SaltCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                SaltItem(
                    title = if (AuthStore.isLoggedIn) {
                        "已登录：${AuthStore.nickname ?: AuthStore.userId}"
                    } else {
                        "未登录"
                    },
                    subtitle = if (AuthStore.isLoggedIn) "点击可重新扫码登录" else "点击扫码登录（取流与歌单需要）",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { PageNav.go(KugouLiteTab.Login) },
                )
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text3(
                        text = when (val s = sidecarState.value) {
                            is Async.Loading -> "正在准备在线服务…"
                            // 已就绪或已失败时以 SidecarManager 的真实状态为准：
                            // 页面切换会重建 state 回到 Idle，但进程可能还在跑。
                            else -> SidecarManager.statusText()
                        },
                        color = androidx.compose.ui.graphics.Color(0xFF9A9A9A),
                    )
                }
            }

            SaltGroupTitle("关于")
            SaltCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text3("Kugou Lite", color = androidx.compose.ui.graphics.Color(0xFFEDEDED))
                    Text3(
                        "P3 阶段：搜索已接入，取流需登录酷狗账号。",
                        color = androidx.compose.ui.graphics.Color(0xFF9A9A9A),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}
