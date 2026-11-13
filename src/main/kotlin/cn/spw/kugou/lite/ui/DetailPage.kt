package cn.spw.kugou.lite.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import cn.spw.kugou.lite.sidecar.AlbumBrief
import cn.spw.kugou.lite.sidecar.Async
import cn.spw.kugou.lite.sidecar.DiscoverApi
import cn.spw.kugou.lite.sidecar.KugouApi
import cn.spw.kugou.lite.sidecar.Song
import cn.spw.kugou.lite.sidecar.rememberAsyncState

/**
 * 统一详情页 —— 合并原先 5 条详情路径。
 *
 * | detailType | 来源页面 | 数据 | 操作组 |
 * |---|---|---|---|
 * | `playlist` | 我的音乐 / 搜索歌单 / 广场 | `/playlist/track/all` | 播放全部 |
 * | `rank` | 排行榜列表 | `/rank/audio` | 播放全部 |
 * | `square` | 歌单广场 | 同 playlist（gid） | 播放全部 |
 * | `daily` | 每日推荐 | `/everyday/recommend` | 播放全部 |
 * | `discover` | 发现页区块「›」 | `DiscoverApi.*`（按 sectionId） | 播放全部 |
 * | `album` | 歌曲行专辑名 / 发现新碟 | `/album/songs` | 播放全部 |
 *
 * ## 为什么合并
 *
 * 旧实现里 `SongListPage`（歌单/榜单）与 `DiscoverDetailPage`（发现区块）
 * 是两套几乎一样的代码，改动要同步两处；专辑项点击还只记日志不跳转。
 * 合并后一个文件管全部详情，`PageNav.detailType` 决定标题与数据源。
 *
 * ## 表格列
 *
 * 统一用 [DataTableRow]（# / 封面 / 标题+歌手 / 专辑 / 时长）；
 * 内容宽度 < 440dp 隐藏专辑列（LS 断点，规格 §5.8）。
 * 榜单类型额外在歌曲行下方显示歌手（榜单数据无专辑名时自然为空）。
 */
@Composable
fun DetailPage() {
    val type = PageNav.detailType.ifBlank {
        // 兼容旧调用点（openRank/openPlaylist 已带 type，这里兜底）
        when {
            PageNav.detailRankId.isNotBlank() -> "rank"
            PageNav.detailPlaylistId.isNotBlank() -> "playlist"
            PageNav.detailSectionId.isNotBlank() -> "discover"
            PageNav.albumId.isNotBlank() -> "album"
            else -> "playlist"
        }
    }
    val rankId = PageNav.detailRankId
    val playlistId = PageNav.detailPlaylistId
    val sectionId = PageNav.detailSectionId
    val albumId = PageNav.albumId
    val title = PageNav.detailTitle.ifBlank { defaultTitle(type) }
    val declaredCount = PageNav.declaredCount

    val songs = rememberAsyncState<List<Song>>()
    val albums = rememberAsyncState<List<AlbumBrief>>()
    // 分页：以服务端声明的总数为准判断是否还有下一页。
    // ⚠️ 不能用「本页是否拉满」—— 酷狗对 pagesize 有内部上限，
    // 请求 50 只回 48 是常态，那样判定会让第 49 首之后的歌永远拉不到
    // （用户反馈「50 首的歌单只显示 48 首」）。
    var page by remember { mutableStateOf(1) }
    var allSongs by remember { mutableStateOf(ArrayList<Song>()) }
    var hasMore by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    /** 服务端声明的曲目总数（0 = 未知）。优先用它，其次用列表页带过来的 declaredCount。 */
    var serverTotal by remember { mutableStateOf(0) }
    /** 首页服务端返回的**原始**条数（含被版权屏蔽的占位项，用于分页判定）。 */
    var firstPageRawCount by remember { mutableStateOf(0) }
    /** 累计因版权屏蔽被丢弃的条目数（用于头部提示）。 */
    var droppedTotal by remember { mutableStateOf(0) }

    LaunchedEffect(type, rankId, playlistId, sectionId, albumId) {
        page = 1
        allSongs = ArrayList()
        hasMore = true
        serverTotal = 0
        firstPageRawCount = 0
        droppedTotal = 0
        when (type) {
            "rank" -> songs.load { KugouApi.rankSongs(rankId) }
            "album" -> songs.load {
                val p = KugouApi.albumSongPage(albumId, page = 1)
                serverTotal = p.total
                firstPageRawCount = p.rawCount
                droppedTotal = p.droppedCount
                p.songs
            }
            "daily" -> songs.load { runCatching { DiscoverApi.dailyRecommend() }.getOrDefault(emptyList()) }
            "discover" -> when (sectionId) {
                "daily" -> songs.load { runCatching { DiscoverApi.dailyRecommend() }.getOrDefault(emptyList()) }
                "discover" -> songs.load { runCatching { DiscoverApi.homeDiscover() }.getOrDefault(emptyList()) }
                "newSongs" -> songs.load { runCatching { DiscoverApi.newSongs() }.getOrDefault(emptyList()) }
                "newAlbums" -> albums.load { runCatching { DiscoverApi.newAlbums() }.getOrDefault(emptyList()) }
                else -> songs.load { emptyList() }
            }
            // playlist / square 同源：都用 global_collection_id 取曲目
            else -> songs.load {
                val p = KugouApi.playlistSongPage(playlistId, page = 1)
                serverTotal = p.total
                firstPageRawCount = p.rawCount
                droppedTotal = p.droppedCount
                p.songs
            }
        }
    }
    // 首页结果回填 + 判定是否还有下一页
    LaunchedEffect(songs.value) {
        val s = songs.value
        if (s is Async.Success) {
            allSongs = ArrayList(s.value)
            val total = if (serverTotal > 0) serverTotal else declaredCount
            hasMore = when (type) {
                // 歌单有屏蔽占位：按「翻过的位置」判断
                "playlist", "square" -> hasMoreAfterPage(
                    pageNo = 1,
                    pageSize = PLAYLIST_PAGE_SIZE,
                    rawCount = firstPageRawCount,
                    serverTotal = total,
                )
                // 专辑没有屏蔽占位问题，按已解析条数判断
                "album" -> hasMorePages(s.value.size, total)
                else -> false
            }
        }
    }

    /**
     * 拉下一页并追加（手动按钮与自动续拉共用）。
     *
     * 自动续拉由 [TrackWatcher] 在「队列快播完」时回调，跑在监听线程上；
     * 所以这里自己负责防重入（[loadingMore]）。
     */
    fun requestNextPage() {
        if (loadingMore || !hasMore) return
        if (type != "playlist" && type != "square" && type != "album") return
        loadingMore = true
        val next = page + 1
        cn.spw.kugou.lite.sidecar.ApiExec.run(onResult = { res ->
            val pageData = res.getOrNull()
            val more = pageData?.songs.orEmpty()
            page = next
            // ⚠️ 总数只在**第 1 页**可信：实测第 2 页的 list_info.count 会变成 1
            // （服务端只对首页返回准确总数）。所以这里只在「还没有可信总数」时采纳，
            // 绝不每页覆盖 —— 否则第 2 页会把 122 覆盖成 1，分页当场停住。
            if (serverTotal <= 0 && pageData != null && pageData.total > 0) {
                serverTotal = pageData.total
            }
            if (pageData != null) droppedTotal += pageData.droppedCount
            // 去重：服务端在某些边界会重复返回上一页末尾的曲目，按 hash/albumAudioId 过滤
            val existingKeys = allSongs.map { s -> s.hash.ifBlank { s.albumAudioId } }.toHashSet()
            val fresh = more.filter { s ->
                val key = s.hash.ifBlank { s.albumAudioId }
                key.isNotBlank() && existingKeys.add(key)
            }
            val merged = ArrayList<Song>(allSongs.size + fresh.size).apply {
                addAll(allSongs); addAll(fresh)
            }
            if (fresh.isNotEmpty()) {
                allSongs = merged
                // 关键：把新歌同步进宿主的播放队列，否则自动续播时新歌没路径
                runCatching {
                    cn.spw.kugou.lite.host.TrackWatcher.appendToQueue(fresh)
                    cn.spw.kugou.lite.host.PlaybackBridge.appendToQueue(
                        allSongs = merged,
                        currentTrackId = currentPlayingTrackId(),
                        resolveStream = { s -> KugouApi.streamUrl(s) },
                    )
                }.onFailure { Diag.warn("[detail] 追加队列失败：${it.message}") }
            } else {
                allSongs = merged
            }
            // 判据（三件事分开，别混）：
            // - 「这页有没有数据」用**原始条数**（含被屏蔽占位，否则会被误判为到底）
            // - 「还有没有下一页」用**服务端声明总数** + 已翻页覆盖的位置
            val rawCount = pageData?.rawCount ?: more.size
            val total = if (serverTotal > 0) serverTotal else declaredCount
            hasMore = hasMoreAfterPage(
                pageNo = next,
                pageSize = PLAYLIST_PAGE_SIZE,
                rawCount = rawCount,
                serverTotal = total,
            )
            loadingMore = false
            Diag.info("[detail] 加载第 $next 页：${more.size} 首（累计 ${allSongs.size}，总数 $total，还有更多=$hasMore）")
        }) { if (type == "album") KugouApi.albumSongPage(albumId, page = next) else KugouApi.playlistSongPage(playlistId, page = next) }
    }

    SaltScreen(title = title, onBackClick = { PageNav.backHome() }) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val showAlbum = maxWidth.value >= TABLE_ALBUM_MIN_WIDTH_DP
            Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                // ---------------------------------------------- 头部横幅
                val list = (songs.value as? Async.Success)?.value.orEmpty()
                val albumList = (albums.value as? Async.Success)?.value.orEmpty()
                val heroCover = list.firstOrNull()?.coverUrl(240)
                    ?: albumList.firstOrNull()?.coverAt(240)
                PageHero(
                    coverUrl = heroCover,
                    title = title,
                    meta = heroMeta(type, list.size, albumList.size, declaredCount, droppedTotal),
                ) {
                    if (list.isNotEmpty()) {
                        ActionChip("▶ 播放全部") { playSongsFromQueue(list, 0) }
                    }
                    if (type == "playlist" || type == "square") {
                        ActionChip("♥ 收藏") { Diag.info("[detail] 收藏歌单（入口占位）") }
                    }
                }

                // ---------------------------------------------- 内容
                when {
                    type == "discover" && sectionId == "newAlbums" -> AlbumGridBlock(albums.value)
                    else -> {
                        SongTableBlock(
                            state = songs.value,
                            allSongs = allSongs,
                            showAlbum = showAlbum,
                            // 播放时把「自动续拉」能力交给监听器（用户反馈 #2）：
                            // 队列快播完时它会回调这里拉下一页并追加，无需用户手动点。
                            needMore = if (type == "playlist" || type == "square") {
                                { requestNextPage() }
                            } else {
                                null
                            },
                        )
                        // 手动「加载更多」（与自动续拉共用同一个 requestNextPage）
                        if (hasMore && (type == "playlist" || type == "square" || type == "album")) {
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                Text3(
                                    text = if (loadingMore) "加载中…" else "加载更多",
                                    color = if (loadingMore) KugouTheme.colors.faint else KugouTheme.colors.text,
                                    modifier = Modifier.clickable(enabled = !loadingMore) { requestNextPage() },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 当前正在播放的曲目 id（供追加队列时定位下标）。
 *
 * 读不到时返回空串 —— [cn.spw.kugou.lite.host.PlaybackBridge.appendToQueue]
 * 会把空 id 当成「找不到」从而退化为从第 1 首重建，不会崩。
 */
private fun currentPlayingTrackId(): String = runCatching {
    cn.spw.kugou.lite.host.TrackWatcher.currentTrackId()
}.getOrDefault("")

/** 标题兜底（PageNav 没给 title 时按类型给默认名）。 */
private fun defaultTitle(type: String): String = when (type) {
    "rank" -> "榜单"
    "square" -> "歌单"
    "daily" -> "每日推荐"
    "album" -> "专辑"
    "discover" -> "发现"
    else -> "歌单"
}

/**
 * 头部元信息文案。
 *
 * @param declared 列表页声明的曲目数（0 = 未知）
 * @param dropped 已被丢弃的条目数（版权屏蔽占位 `{"fileid":x,"shield":1}`，
 *        只有 fileid 没有任何歌曲信息，无法显示成有效行）
 */
private fun heroMeta(
    type: String,
    songCount: Int,
    albumCount: Int,
    declared: Int = 0,
    dropped: Int = 0,
): String {
    val base = when (type) {
        "album" -> if (songCount > 0) "专辑 · $songCount 首" else "专辑"
        "rank" -> if (songCount > 0) "榜单 · $songCount 首" else "榜单"
        "daily" -> if (songCount > 0) "每日推荐 · $songCount 首" else "每日推荐"
        "discover" -> if (albumCount > 0) "新碟 · $albumCount 张" else if (songCount > 0) "$songCount 首" else "发现"
        else -> if (songCount > 0) "歌单 · $songCount 首" else "歌单"
    }
    // 有版权受限曲目就如实说明，避免用户以为「歌单少了歌」
    return if (dropped > 0) {
        "$base（另有 $dropped 首因版权限制不可播放）"
    } else if (declared > songCount && songCount > 0) {
        "$base（声明 $declared 首，${declared - songCount} 首暂不可用）"
    } else {
        base
    }
}

/** 操作组小胶囊（中性底色，不用 accent）。 */
@Composable
private fun ActionChip(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text3(text = label, color = KugouTheme.colors.text, maxLines = 1)
    }
}

/** 歌曲表格块：状态 + 行。
 *
 * @param allSongs 累计已加载（含分页追加），用于建播放队列
 * @param needMore 播放时的续拉回调，透传给 [playSongsFromQueue]
 */
@Composable
private fun SongTableBlock(
    state: Async<List<Song>>,
    allSongs: List<Song>,
    showAlbum: Boolean,
    needMore: (() -> Unit)? = null,
) {
    when (val s = state) {
        is Async.Idle, is Async.Loading -> DetailStatus("正在加载…")
        is Async.Failure -> DetailStatus("加载失败：${s.message}")
        is Async.Success -> {
            val display = allSongs.ifEmpty { s.value }
            when {
                display.isEmpty() -> DetailStatus(
                    if (KugouApi.lastNeedsLogin) "需要登录酷狗账号" else "该列表暂无歌曲",
                )
                else -> SaltCardBlock {
                    display.forEachIndexed { index, song ->
                        DataTableRow(
                            index = "%02d".format(index + 1),
                            coverUrl = song.coverUrl(120),
                            title = song.title,
                            subtitle = song.singer,
                            album = song.albumName,
                            duration = song.durationText,
                            showAlbum = showAlbum,
                            onRowClick = { playSongsFromQueue(display, index, needMore) },
                            cells = DataTableCells(
                                onArtist = if (song.artists.firstOrNull()?.id?.isNotBlank() == true) {
                                    { PageNav.openArtist(song.artists.first().id, song.artists.first().name) }
                                } else null,
                                onAlbum = if (!song.albumId.isBlank()) {
                                    { PageNav.openAlbum(song.albumId, song.albumName ?: "专辑") }
                                } else null,
                            ),
                        )
                    }
                }
            }
        }
    }
}

/** 专辑网格块（发现页「新碟上架」详情）。 */
@Composable
private fun AlbumGridBlock(state: Async<List<AlbumBrief>>) {
    when (val s = state) {
        is Async.Idle, is Async.Loading -> DetailStatus("正在加载…")
        is Async.Failure -> DetailStatus("加载失败：${s.message}")
        is Async.Success -> {
            val list = s.value
            if (list.isEmpty()) {
                DetailStatus("暂无专辑")
            } else {
                SaltCardBlock {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                        gridRows(list, DETAIL_GRID_COLUMNS).forEach { row ->
                            Row(Modifier.fillMaxWidth()) {
                                row.forEach { album ->
                                    CoverGridCard(
                                        coverUrl = album.coverAt(300),
                                        title = album.name,
                                        subtitle = album.artist,
                                        modifier = Modifier.weight(1f).padding(4.dp),
                                    ) { PageNav.openAlbum(album.id, album.name) }
                                }
                                repeat(DETAIL_GRID_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 详情页专辑网格列数（内容区宽度稳定，固定三列）。 */
private const val DETAIL_GRID_COLUMNS = 3

/** 统一的外层卡片（保持 16dp 水平留白）。 */
@Composable
private fun SaltCardBlock(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    SaltCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), content = content)
    }
}

/** 状态提示。 */
@Composable
private fun DetailStatus(text: String) {
    SaltCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Box(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 22.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text3(text, color = KugouTheme.colors.muted)
        }
    }
}
