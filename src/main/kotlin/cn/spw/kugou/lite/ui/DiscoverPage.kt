package cn.spw.kugou.lite.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.spw.kugou.lite.diag.Diag
import cn.spw.kugou.lite.sidecar.Async
import cn.spw.kugou.lite.sidecar.DiscoverApi
import cn.spw.kugou.lite.sidecar.DiscoverConfig
import cn.spw.kugou.lite.sidecar.KugouApi
import cn.spw.kugou.lite.sidecar.PersonalFm
import cn.spw.kugou.lite.sidecar.SidecarManager
import cn.spw.kugou.lite.sidecar.Song
import cn.spw.kugou.lite.sidecar.rememberAsyncState

/**
 * 发现页主体 —— 1:1 复刻 MD3Music 的区块式内容流。
 *
 * ## 五个区块
 *
 * 私人 FM / 每日推荐 / 刷歌推荐 / 新歌速递 / 新碟上架。
 * 每个区块独立加载、独立失败，隐藏的区块不发请求。
 *
 * ## 布局
 *
 * - 单列：纵向堆叠
 * - 双列：除私人 FM 外两两并排（每列 weight(1f)）
 * - 私人 FM 在两种布局下都独占整行
 *
 * 布局与区块顺序、显示开关由 [DiscoverConfig] 提供，在「编辑」面板里改。
 *
 * ## 组合期安全
 *
 * 所有网络请求都在 [ApiExec] 后台线程（经 `MutableAsyncState.load`），
 * `load` 块内一律 `runCatching`，绝不把异常抛进组合树 —— 宿主对插件
 * 组合异常的处理是 `PLUGIN_FATAL`，会直接终止进程。
 */

/** 专辑列表项（避免在 UI 层重复声明类型）。 */
private typealias AlbumBriefState = cn.spw.kugou.lite.sidecar.AlbumBrief

@Composable
fun DiscoverPage() {
    // 每次从编辑面板返回（discoverRefresh 递增）都重新读配置，
    // 这样布局/排序/开关立刻生效，且不必重发网络请求。
    var config by remember(PageNav.discoverRefresh) { mutableStateOf(DiscoverConfig.load()) }
    var fmStation by remember { mutableStateOf(config.fmStationIndex) }
    var reloadTick by remember { mutableStateOf(0) }

    // 5 个区块各自的 state。用 remember 保证重组不丢。
    val daily = rememberAsyncState<List<Song>>()
    val discover = rememberAsyncState<List<Song>>()
    val newSongs = rememberAsyncState<List<Song>>()
    val newAlbums = rememberAsyncState<List<AlbumBriefState>>()
    val fm = rememberAsyncState<List<Song>>()
    var fmCurMark by remember { mutableStateOf("") }

    // 一次性加载：先确保边车就绪，再按 visible 顺序请求。
    // reloadTick 变化时重跑（编辑面板改了配置 / 用户点了重试）。
    LaunchedEffect(reloadTick) {
        if (!SidecarManager.isRunning()) {
            runCatching { SidecarManager.ensureStarted() }
        }
        config = DiscoverConfig.load()
        fmStation = config.fmStationIndex

        val visible = config.visible
        if ("daily" in visible) {
            daily.load { runCatching { DiscoverApi.dailyRecommend() }.getOrDefault(emptyList()) }
        }
        if ("discover" in visible) {
            discover.load { runCatching { DiscoverApi.homeDiscover() }.getOrDefault(emptyList()) }
        }
        if ("newSongs" in visible) {
            newSongs.load { runCatching { DiscoverApi.newSongs() }.getOrDefault(emptyList()) }
        }
        if ("newAlbums" in visible) {
            newAlbums.load { runCatching { DiscoverApi.newAlbums() }.getOrDefault(emptyList()) }
        }
        if ("fm" in visible) {
            fm.load {
                val batch = runCatching { DiscoverApi.personalFm(fmStation, null) }
                    .getOrDefault(cn.spw.kugou.lite.sidecar.FmBatch(emptyList(), ""))
                fmCurMark = batch.curMark
                batch.songs
            }
        }
    }

    SaltScreen(title = "发现") {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // ---------------------------------------------------------- 搜索
            SearchEntry { PageNav.go(KugouLiteTab.Search) }

            val pair = config.layout == DiscoverConfig.Layout.PAIR

            // ---------------------------------------------------------- 私人 FM（独占整行）
            if ("fm" in config.visible) {
                FmCard(
                    state = fm.value,
                    stationIndex = fmStation,
                    onPlay = { songs -> playSongsFromQueue(songs, 0) },
                    onSwitchStation = { next ->
                        fmStation = next
                        val c = config.copy(fmStationIndex = next)
                        DiscoverConfig.save(c)
                        config = c
                        fm.load {
                            val batch = runCatching { DiscoverApi.personalFm(next, null) }
                                .getOrDefault(cn.spw.kugou.lite.sidecar.FmBatch(emptyList(), ""))
                            fmCurMark = batch.curMark
                            batch.songs
                        }
                    },
                    onRetry = { reloadTick++ },
                )
            }

            // ---------------------------------------------------------- 其余 4 个区块
            val sections = config.visible.filter { it != "fm" }
            if (pair) {
                // 两两一组并排
                sections.chunked(2).forEach { group ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        group.forEach { id ->
                            Column(Modifier.weight(1f)) {
                                SectionContent(
                                    id = id,
                                    daily = daily.value,
                                    discover = discover.value,
                                    newSongs = newSongs.value,
                                    newAlbums = newAlbums.value,
                                    onRetry = { reloadTick++ },
                                )
                            }
                        }
                        // 奇数个时补一个空列保持等宽
                        if (group.size == 1) androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                    }
                }
            } else {
                sections.forEach { id ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        SectionContent(
                            id = id,
                            daily = daily.value,
                            discover = discover.value,
                            newSongs = newSongs.value,
                            newAlbums = newAlbums.value,
                            onRetry = { reloadTick++ },
                        )
                    }
                }
            }

            // ---------------------------------------------------------- 编辑入口
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { PageNav.openDiscoverEditor() }
                    .padding(vertical = 14.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text3("✎ 编辑", color = KugouTheme.colors.muted)
            }
        }
    }
}

/** 顶部紧凑搜索入口。 */
@Composable
private fun SearchEntry(onClick: () -> Unit) {
    SaltCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .padding(horizontal = 16.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text3("⌕", color = KugouTheme.colors.muted)
            Text3(
                text = "搜索歌曲、歌手、专辑",
                color = KugouTheme.colors.faint,
                modifier = Modifier.padding(start = 8.dp).weight(1f),
            )
            Text3("›", color = KugouTheme.colors.faint)
        }
    }
}

/** 私人 FM 卡片（独占整行）。 */
@Composable
private fun FmCard(
    state: Async<List<Song>>,
    stationIndex: Int,
    onPlay: (List<Song>) -> Unit,
    onSwitchStation: (Int) -> Unit,
    onRetry: () -> Unit,
) {
    SaltCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text3("私人 FM", color = KugouTheme.colors.text)
            val label = PersonalFm.stations.getOrElse(stationIndex) { PersonalFm.stations[0] }.label
            Text3(
                text = "$label ⇄",
                color = KugouTheme.colors.muted,
                modifier = Modifier.clickable {
                    onSwitchStation((stationIndex + 1) % PersonalFm.stations.size)
                },
            )
        }

        when (val s = state) {
            is Async.Idle, is Async.Loading -> StateHint("正在获取私人 FM…")
            is Async.Failure -> StateHint("加载失败：${s.message}")
            is Async.Success -> {
                val songs = s.value
                if (songs.isEmpty() && KugouApi.lastNeedsLogin) {
                    LoginHint()
                } else if (songs.isEmpty()) {
                    StateHint("暂无推荐")
                } else {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPlay(songs) }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CoverBox(songs.firstOrNull()?.coverUrl(120), 52, 8)
                        Column(Modifier.padding(start = 11.dp).weight(1f)) {
                            Text3(songs.first().title, color = KugouTheme.colors.text)
                            Text3(
                                text = "随机的下一首 · ${songs.size} 首待播",
                                color = KugouTheme.colors.muted,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        Text3("▶ 播放", color = KugouTheme.colors.text)
                    }
                }
            }
        }
    }
}

/** 单个歌曲类区块（按 id 分发）。 */
@Composable
private fun SectionContent(
    id: String,
    daily: Async<List<Song>>,
    discover: Async<List<Song>>,
    newSongs: Async<List<Song>>,
    newAlbums: Async<List<AlbumBriefState>>,
    onRetry: () -> Unit,
) {
    when (id) {
        "daily" -> SongSection("每日推荐", id, daily, onRetry)
        "discover" -> SongSection("刷歌推荐", id, discover, onRetry)
        "newSongs" -> SongSection("新歌速递", id, newSongs, onRetry)
        "newAlbums" -> AlbumSection("新碟上架", id, newAlbums, onRetry)
    }
}

/** 歌曲类区块：加载/失败/空态 + 4 条卡片。 */
@Composable
private fun SongSection(
    title: String,
    id: String,
    state: Async<List<Song>>,
    onRetry: () -> Unit,
) {
    when (val s = state) {
        is Async.Idle, is Async.Loading -> {
            SaltCard(Modifier.fillMaxWidth()) {
                StateHint("正在加载…")
            }
        }
        is Async.Failure -> {
            SaltCard(Modifier.fillMaxWidth()) {
                StateHint("加载失败：${s.message}") {
                    Text3("点击重试", color = KugouTheme.colors.text, modifier = Modifier.clickable { onRetry() })
                }
            }
        }
        is Async.Success -> {
            val all = s.value
            when {
                all.isEmpty() && KugouApi.lastNeedsLogin -> {
                    SaltCard(Modifier.fillMaxWidth()) { LoginHint() }
                }
                all.isEmpty() -> Unit // 折叠，不占高度
                else -> DiscoverSectionCard(
                    title = title,
                    songs = all.take(4),
                    allSongs = all,
                    onMore = { PageNav.openDiscoverDetail(id, title) },
                    onPick = { list, index -> playSongsFromQueue(list, index) },
                )
            }
        }
    }
}

/** 专辑类区块：3 张封面。 */
@Composable
private fun AlbumSection(
    title: String,
    id: String,
    state: Async<List<AlbumBriefState>>,
    onRetry: () -> Unit,
) {
    when (val s = state) {
        is Async.Idle, is Async.Loading -> {
            SaltCard(Modifier.fillMaxWidth()) { StateHint("正在加载…") }
        }
        is Async.Failure -> {
            SaltCard(Modifier.fillMaxWidth()) {
                StateHint("加载失败：${s.message}") {
                    Text3("点击重试", color = KugouTheme.colors.text, modifier = Modifier.clickable { onRetry() })
                }
            }
        }
        is Async.Success -> {
            val all = s.value
            when {
                all.isEmpty() && KugouApi.lastNeedsLogin -> {
                    SaltCard(Modifier.fillMaxWidth()) { LoginHint() }
                }
                all.isEmpty() -> Unit
                else -> AlbumSectionCard(
                    title = title,
                    albums = all.take(3),
                    allAlbums = all,
                    onMore = { PageNav.openDiscoverDetail(id, title) },
                    onPick = { _, index ->
                        Diag.info("[discover] 点击专辑 ${all.getOrNull(index)?.name}")
                    },
                )
            }
        }
    }
}

/** 未登录引导（不阻断其他区块）。 */
@Composable
private fun LoginHint() {
    StateHint("登录后查看推荐") {
        Text3(
            text = "去登录",
            color = KugouTheme.colors.text,
            modifier = Modifier.clickable { PageNav.go(KugouLiteTab.Login) },
        )
    }
}
