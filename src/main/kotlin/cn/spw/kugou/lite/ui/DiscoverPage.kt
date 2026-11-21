package cn.spw.kugou.lite.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
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
 * ## 布局（自动适配 + 瀑布流）
 *
 * - 列数按页面可用宽度自动适配：每列至少 300dp，1–3 列（规则见 [discoverColumnCount]）
 * - 内容区块按「轮转」分进各列，**每列独立往下排**：矮卡片下方立刻排入下一个区块，
 *   不会出现等高行布局在卡底撑出的空洞（规则见 [discoverFlowColumns]）
 * - 私人 FM 无论几列都独占整行
 *
 * 区块顺序与显示开关由 [DiscoverConfig] 提供，在「编辑」面板里改。
 *
 * ## 组合期安全
 *
 * 所有网络请求都在 [ApiExec] 后台线程（经 `MutableAsyncState.load`），
 * `load` 块内一律 `runCatching`，绝不把异常抛进组合树 —— 宿主对插件
 * 组合异常的处理是 `PLUGIN_FATAL`，会直接终止进程。
 */

/** 专辑列表项（避免在 UI 层重复声明类型）。 */
private typealias AlbumBriefState = cn.spw.kugou.lite.sidecar.AlbumBrief

/**
 * 取一批 FM 歌曲，并把游标与去重接上。
 *
 * ## 为什么需要这个函数
 *
 * 早期实现把 `cur_mark` 当分页游标，但上游每次都回同一个 `99999998`，
 * 于是每轮拿到同一批歌，第二次就被去重全挡 ——「无限续播」只能成功一次。
 *
 * 现在（对照 MD3Music 参考实现）：
 * 1. 用**上一首**的 hash + songid 作为续流游标
 * 2. 带游标要不到新歌时，退回不带游标再要一次
 * 3. 用 [PersonalFm.Session.filterFresh] 按 hash 去重，过滤已听过的
 *
 * 整批都听过时 `filterFresh` 返回空列表 —— 这是**正常成功**，不是失败，
 * 调用方不该据此报错（对应规格的审查重点 5）。
 *
 * @return 本批可播的新歌（可能为空）
 */
private fun loadFmBatch(session: PersonalFm.Session): List<Song> {
    // 续流游标 = **上一首**的 hash/songid（不是 cur_mark 分页，见 buildFmParams）。
    val cursor = session.lastSong

    fun fetch(h: String?, sid: String?): List<Song>? = runCatching {
        DiscoverApi.personalFm(session.stationIndex, cursorHash = h, cursorSongId = sid).songs
    }.getOrElse {
        Diag.warn("[discover] 私人 FM 请求失败：${it.message}")
        null
    }

    // 带游标要不到新歌就**退回不带游标再要一次**（参考实现 _append 的同款处理）：
    // 上游偶尔会因为没有更多推荐而返回空，此时从无游标重开才能继续。
    // 续播问题的关键线索：没有 songId 时上游只能按 hash 粗粒度续流，命中率下降。
    Diag.info("[discover] FM 续流游标 hash=${cursor?.hash?.take(8) ?: "-"} songId=${cursor?.songId ?: "-"}")
    var songs = fetch(cursor?.hash, cursor?.songId)
    if (songs.isNullOrEmpty() && cursor != null) {
        Diag.info("[discover] 带游标续流为空，退回无游标重取")
        songs = fetch(null, null)
    }
    val fresh = session.filterFresh(songs.orEmpty())
    // 记录本批最后一首作为下一次的游标
    if (fresh.isNotEmpty()) session.lastSong = fresh.last()
    return fresh
}

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
    /**
     * FM 会话：记住续流游标（上一首）与已听 hash。
     *
     * 用 `mutableStateOf` 持有而不是 `remember(fmStation)`：切档时我们在
     * 事件回调里**同步**换新 Session（清空游标与去重集合），随后 load 闭包
     * 捕获的就是新会话 —— 若用 `remember(fmStation)`，lambda 会在重组前
     * 捕获旧 session，把旧档位的游标带进新档位。
     */
    var fmSession by remember { mutableStateOf(PersonalFm.Session(fmStation)) }

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
            // 捕获成局部变量：load 的 block 跑在后台线程，不读 Compose 状态
            val session = fmSession
            fm.load { loadFmBatch(session) }
        }
    }

    SaltScreen(title = "发现") {
        // BoxWithConstraints 只为拿到页面可用宽度来算列数；内容仍在 Column 里排布
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = discoverColumnCount(maxWidth.value)
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // ------------------------------------------------------ 搜索
                SearchEntry { PageNav.go(KugouLiteTab.Search) }

                // ------------------------------------------------------ 快捷入口磁贴
                // 发现页替换了旧 HomePage 后，「我的音乐/排行榜/每日推荐/歌单广场/
                // 播放历史/设置」失去入口（建造者检查发现的回归）。这里补一行磁贴，
                // 保持「内容优先」的版面不被破坏。
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    EntryTile(label = "我的音乐", icon = KugouIcons.Music, onClick = { PageNav.go(KugouLiteTab.Playlists) }, modifier = Modifier.weight(1f))
                    EntryTile(label = "排行榜", icon = KugouIcons.Ranks, onClick = { PageNav.go(KugouLiteTab.Ranks) }, modifier = Modifier.weight(1f))
                    EntryTile(label = "每日推荐", icon = KugouIcons.Daily, onClick = { PageNav.openDaily() }, modifier = Modifier.weight(1f))
                    EntryTile(label = "歌单广场", icon = KugouIcons.Square, onClick = { PageNav.openSquare() }, modifier = Modifier.weight(1f))
                    EntryTile(label = "播放历史", icon = KugouIcons.History, onClick = { PageNav.openHistory() }, modifier = Modifier.weight(1f))
                    EntryTile(label = "设置", icon = KugouIcons.Settings, onClick = { PageNav.go(KugouLiteTab.Settings) }, modifier = Modifier.weight(1f))
                }

                // ------------------------------------------------------ 私人 FM（永远独占整行）
                if ("fm" in config.visible) {
                    FmCard(
                        state = fm.value,
                        stationIndex = fmStation,
                        onPlay = { songs ->
                            fmSession.markPlayed(songs)
                            // 私人 FM 续播（用户反馈 #4）：这批 5 首快播完时，
                            // 监听器回调再拉一批、去重后追加进队列。
                            // 追加逻辑抽成 FmContinuation（BUG-02）：宿主队列
                            // 重建必须以 TrackWatcher 当前登记队列为准，
                            // 不能用这里捕获的初始批次（否则第二次续拉会丢掉
                            // 之前追加、还没播完的歌）。
                            val session = fmSession
                            val continuation = FmContinuation(loadBatch = { loadFmBatch(session) })
                            playSongsFromQueue(
                                queue = songs,
                                index = 0,
                                needMore = { continuation.extend() },
                            )
                        },
                        onSwitchStation = { next ->
                            fmStation = next
                            val c = config.copy(fmStationIndex = next)
                            DiscoverConfig.save(c)
                            config = c
                            // 切档 = 全新会话：清空游标与已听 hash（规格第 6 节）
                            val fresh = PersonalFm.Session(next)
                            fmSession = fresh
                            // load 的 block 在后台线程执行，不读 Compose 状态，
                            // 故把会话捕获成局部变量传进去。
                            fm.load { loadFmBatch(fresh) }
                        },
                        onRetry = { reloadTick++ },
                    )
                }

                // ---------------------------------------------- 其余内容区块（瀑布流）
                // 按自动列数轮转分列，每列独立往下排；矮卡下方的空洞被下一区块填上。
                val sections = config.visible.filter { it != "fm" }
                val flowColumns = discoverFlowColumns(sections, columns)
                if (flowColumns.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        flowColumns.forEach { column ->
                            Column(
                                Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                column.forEach { id ->
                                    SectionContent(
                                        id = id,
                                        daily = daily.value,
                                        discover = discover.value,
                                        newSongs = newSongs.value,
                                        newAlbums = newAlbums.value,
                                        onRetry = { reloadTick++ },
                                        onRefreshDiscover = {
                                            discover.load { runCatching { DiscoverApi.homeDiscover() }.getOrDefault(emptyList()) }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                // ------------------------------------------------------ 编辑入口
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
    /** 刷歌推荐的手动刷新（换一批）；其它区块为 null。 */
    onRefreshDiscover: () -> Unit = {},
) {
    when (id) {
        "daily" -> SongSection("每日推荐", id, daily, onRetry)
        "discover" -> SongSection("刷歌推荐", id, discover, onRetry, onRefresh = onRefreshDiscover)
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
    /** 非空时成功态标题栏显示「换一批」。 */
    onRefresh: (() -> Unit)? = null,
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
                    // 「每日推荐」区块进每日推荐页（含风格/历史）；其余区块进统一详情页
                    onMore = {
                        if (id == "daily") PageNav.openDaily()
                        else PageNav.openDiscoverDetail(id, title)
                    },
                    onPick = { list, index -> playSongsFromQueue(list, index) },
                    onRefresh = onRefresh,
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
