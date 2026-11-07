package cn.spw.kugou.lite.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import cn.spw.kugou.lite.host.TrackWatcher
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import cn.spw.kugou.lite.sidecar.ArtistRef
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import cn.spw.kugou.lite.sidecar.AuthStore
import cn.spw.kugou.lite.diag.Diag
import cn.spw.kugou.lite.host.PlaybackBridge
import cn.spw.kugou.lite.sidecar.ApiExec
import cn.spw.kugou.lite.sidecar.Async
import cn.spw.kugou.lite.sidecar.AudioCache
import cn.spw.kugou.lite.sidecar.Id3CoverWriter
import cn.spw.kugou.lite.sidecar.KugouApi
import cn.spw.kugou.lite.sidecar.MutableAsyncState
import cn.spw.kugou.lite.sidecar.Playlist
import cn.spw.kugou.lite.sidecar.Rank
import cn.spw.kugou.lite.sidecar.Song
import cn.spw.kugou.lite.sidecar.rememberAsyncState

/**
 * 我的歌单。
 *
 * ## 三段式分组
 *
 * 酷狗把「我喜欢」「默认收藏」都实现为**自建歌单**（`type=0`），
 * 和真正的用户自建歌单混在一起。如果平铺展示，用户很难一眼找到
 * 「我喜欢」—— 而这个恰恰是最常用的入口。
 *
 * 所以按重要度分段：
 * 1. **我的收藏夹** —— 系统歌单（我喜欢 / 默认收藏），置顶带 ♥
 * 2. **我的歌单** —— `type=0` 的用户自建
 * 3. **收藏的歌单** —— `type=1`，别人创建、我收藏的
 *
 * 需要登录（`/user/playlist` 走账号态）。未登录或 token 过期时显示
 * 引导文案而不是空列表 —— 否则用户会以为「没有歌单」而不是「需要重新登录」。
 */
@Composable
fun PlaylistPage() {
    val state = rememberAsyncState<List<Playlist>?>()
    LaunchedEffect(Unit) { state.load { KugouApi.userPlaylists() } }

    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        when (val s = state.value) {
            is Async.Idle, is Async.Loading -> StatusBlock("正在加载…")
            is Async.Failure -> StatusBlock("加载失败：${s.message}")
            is Async.Success -> {
                val list = s.value
                when {
                    list == null -> StatusBlock(
                        if (KugouApi.lastNeedsLogin) "需要登录酷狗账号（token 可能已过期）"
                        else "加载失败：${KugouApi.lastError ?: "未知错误"}",
                    )
                    list.isEmpty() -> StatusBlock("暂无歌单")
                    else -> {
                        // 系统歌单（我喜欢 listid=2 / 默认收藏 listid=1）
                        val system = list.filter { it.isFavorites || it.listId == "1" }
                        val own = list.filter { it.isOwn && !system.contains(it) }
                        val collected = list.filter { !it.isOwn }

                        if (system.isNotEmpty()) {
                            SaltGroupTitle("我的收藏夹")
                            SaltCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                                system.forEach { pl ->
                                    CollectionRow(
                                        coverUrl = pl.coverAt(300),
                                        title = pl.name,
                                        subtitle = "${pl.songCount} 首",
                                        badge = if (pl.isFavorites) "♥" else null,
                                        onClick = { PageNav.openPlaylist(pl.id, pl.name) },
                                    )
                                }
                            }
                        }

                        if (own.isNotEmpty()) {
                            SaltGroupTitle("我的歌单")
                            SaltCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                                own.forEach { pl ->
                                    CollectionRow(
                                        coverUrl = pl.coverAt(300),
                                        title = pl.name,
                                        subtitle = "${pl.songCount} 首",
                                        onClick = { PageNav.openPlaylist(pl.id, pl.name) },
                                    )
                                }
                            }
                        }

                        if (collected.isNotEmpty()) {
                            SaltGroupTitle("收藏的歌单")
                            SaltCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                                collected.forEach { pl ->
                                    CollectionRow(
                                        coverUrl = pl.coverAt(300),
                                        title = pl.name,
                                        subtitle = "${pl.songCount} 首",
                                        onClick = { PageNav.openPlaylist(pl.id, pl.name) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 一段状态提示（加载中/失败/空）。 */
@Composable
private fun StatusBlock(text: String) {
    SaltCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Box(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text3(text, color = KugouTheme.colors.muted)
        }
    }
}

/** 排行榜列表。每一项可点进歌曲列表。 */
@Composable
fun RankPage() {
    val state = rememberAsyncState<List<Rank>?>()
    LaunchedEffect(Unit) { state.load { KugouApi.ranks() } }

    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        when (val s = state.value) {
            is Async.Idle, is Async.Loading -> StatusBlock("正在加载…")
            is Async.Failure -> StatusBlock("加载失败：${s.message}")
            is Async.Success -> {
                val list = s.value
                when {
                    list == null -> StatusBlock(
                        if (KugouApi.lastNeedsLogin) "需要登录酷狗账号"
                        else "加载失败：${KugouApi.lastError ?: "未知错误"}",
                    )
                    list.isEmpty() -> StatusBlock("暂无榜单")
                    else -> {
                        SaltGroupTitle("排行榜")
                        SaltCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                            list.forEach { r ->
                                CollectionRow(
                                    coverUrl = r.coverUrl,
                                    title = r.name,
                                    subtitle = if (r.songCount > 0) "${r.songCount} 首" else "点击查看",
                                    onClick = { PageNav.openRank(r.id, r.name) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 每日推荐。直接是歌曲列表，点歌即试听。 */
@Composable
fun DailyPage() {
    val state = rememberAsyncState<List<Song>?>()
    LaunchedEffect(Unit) { state.load { KugouApi.dailyRecommend() } }

    ListScaffold(
        state = state,
        groupTitle = "每日推荐",
        emptyText = "今日暂无推荐",
        loginHint = "需要登录酷狗账号",
    ) { list ->
        list.forEachIndexed { index, s ->
            SongRow(song = s, onClick = { onSongPicked(list, index) })
        }
    }
}

/** 歌单详情 / 榜单详情的歌曲列表。 */
@Composable
fun SongListPage() {
    val rankId = PageNav.detailRankId
    val playlistId = PageNav.detailPlaylistId
    val state = rememberAsyncState<List<Song>?>()

    LaunchedEffect(rankId, playlistId) {
        state.load {
            when {
                rankId.isNotBlank() -> KugouApi.rankSongs(rankId)
                playlistId.isNotBlank() -> KugouApi.playlistSongs(playlistId)
                else -> emptyList()
            }
        }
    }

    ListScaffold(
        state = state,
        groupTitle = PageNav.detailTitle.ifBlank { "歌曲列表" },
        emptyText = "该列表暂无歌曲",
        loginHint = "需要登录酷狗账号",
    ) { list ->
        list.forEachIndexed { index, s ->
            SongRow(song = s, onClick = { onSongPicked(list, index) })
        }
    }
}

/**
 * 通用的「从列表点歌」流程，供所有页面共用。
 *
 * ```
 * 取流 → 下载到本地缓存（含封面/歌词）→ 建立整条播放队列 → 播放
 * ```
 *
 * 集中在一处的好处：以后改进播放链路（比如预取下一首、音质重试）
 * 只需要改这里，不用同步改四个页面。
 */
internal fun playSongsFromQueue(queue: List<Song>, index: Int) {
    val song = queue.getOrNull(index) ?: return
    Diag.info("[page] 点歌「${song.title}」- ${song.singer}（${index + 1}/${queue.size}）")
    ApiExec.run({ result ->
        result.onFailure { Diag.warn("[page] 播放流程异常：${it.message}") }
    }) {
        val stream = KugouApi.streamUrl(song)
        if (stream == null) {
            Diag.warn("[page] 取流失败：${KugouApi.lastError ?: "未知原因"}")
            return@run
        }
        // 先取歌词，再让 AudioCache 一并补齐（音频 + 封面 + 逐字歌词）
        val lrc = runCatching { KugouApi.lyricByHash(song.hash) }.getOrNull()
        val local = AudioCache.ensureLocal(song, stream, lrc)
        if (local == null) {
            Diag.warn("[page] 本地缓存失败，回退 HTTP 直链")
        }

        val ok = PlaybackBridge.playQueue(queue, index, { s -> KugouApi.streamUrl(s) }, local)
        if (!ok) {
            Diag.warn("[page] 建立队列失败，回退单曲播放")
            PlaybackBridge.play(song, stream, local)
        }
        // 登记队列并启动切歌监听
        TrackWatcher.updateQueue(queue, index)
        TrackWatcher.start()
    }
}

/**
 * 点歌的统一入口（列表内点击）。
 *
 * @param queue 当前列表的全部歌曲（用于建队列）
 * @param index 被点击的曲目在列表中的下标
 */
private fun onSongPicked(queue: List<Song>, index: Int) = playSongsFromQueue(queue, index)

/**
 * 一行歌曲：封面 + 歌名 + 歌手·时长 + 收藏按钮。
 *
 * 封面来自酷狗 CDN（`trans_param.union_cover` 的 `{size}` 已替换成 120px），
 * 由 [CoverLoader] 异步加载 + 两级缓存。加载中/失败时显示音符占位块，
 * 保证行高稳定，不会因为图片加载而跳动。
 *
 * ## 每位歌手单独可点
 *
 * 一首歌常有多个歌手（`A、B、C`）。早期把它们当成**一个整体标签**，
 * 点击只能跳到第一位歌手，其余几位点不到。
 *
 * 现在按 [Song.artists] 逐位渲染：有 id 的用主色且可点，
 * 没 id 的（歌单接口不提供）用次要色且不可点，中间用顿号分隔。
 * 用 [FlowRow] 而不是 [Row]，歌手多时能自动换行，不会溢出。
 *
 * ## 收藏按钮
 *
 * 右侧的 ♡ / ♥ 点击切换「我喜欢」状态。用字符而不是图标，
 * 避免依赖宿主图标资源（宿主图标类名被混淆，不稳定）。
 * 未登录时点了会提示需要登录。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SongRow(song: Song, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SongCover(song.coverUrl(120))
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text3(text = song.title, color = KugouTheme.colors.text)

            FlowRow(
                modifier = Modifier.padding(top = 2.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                val artists = song.artists.ifEmpty {
                    // 兜底：接口没给结构，就展示拼接好的整串，不可点
                    listOf(ArtistRef(name = song.singer))
                }
                artists.forEachIndexed { index, artist ->
                    if (index > 0) {
                        Text3("、", color = KugouTheme.colors.muted)
                    }
                    if (artist.clickable) {
                        Text3(
                            text = artist.name,
                            color = KugouTheme.colors.text,
                            modifier = Modifier.clickable {
                                PageNav.openArtist(artist.id, artist.name)
                            },
                        )
                    } else {
                        Text3(text = artist.name, color = KugouTheme.colors.muted)
                    }
                }
                if (song.durationSec > 0) {
                    Text3(text = " · ${song.durationText}", color = KugouTheme.colors.muted)
                }
                // 专辑名可点（有 albumId 时）；用「·」与时长分隔开，
                // 避免和上面一行歌手挤在一起。
                val album = song.albumName
                if (!album.isNullOrBlank()) {
                    Text3(text = " · ", color = KugouTheme.colors.muted)
                    if (song.albumId.isNotBlank()) {
                        Text3(
                            text = album,
                            color = KugouTheme.colors.text,
                            modifier = Modifier.clickable {
                                PageNav.openAlbum(song.albumId, album)
                            },
                        )
                    } else {
                        Text3(text = album, color = KugouTheme.colors.muted)
                    }
                }
            }
        }
        FavoriteButton(song)
    }
}

/**
 * 收藏按钮。
 *
 * ## 状态来源
 *
 * 每次组合时读一次「我喜欢」列表判断是否已收藏 —— 简单但可靠。
 * 列表通常几十首，命中内存缓存后开销可接受。
 *
 * ## 为什么用字符
 *
 * 宿主图标组件类名被混淆，跨版本不稳定。用字符 ♡/♥ 兼容性最好，
 * 而且天然支持主题着色。
 */
@Composable
private fun FavoriteButton(song: Song) {
    var favorited by remember(song.hash) { mutableStateOf(false) }
    var busy by remember(song.hash) { mutableStateOf(false) }

    // 首次组合时查询一次收藏状态
    LaunchedEffect(song.hash) {
        if (!AuthStore.isLoggedIn) return@LaunchedEffect
        favorited = withContext(Dispatchers.IO) {
            runCatching { KugouApi.isFavorite(song) }.getOrDefault(false)
        }
    }

    Text3(
        text = when {
            busy -> "…"
            favorited -> "♥"
            else -> "♡"
        },
        color = when {
            favorited -> KugouTheme.colors.favorite
            else -> KugouTheme.colors.faint
        },
        modifier = Modifier
            .padding(start = 8.dp)
            .clickable(enabled = !busy) {
                if (!AuthStore.isLoggedIn) {
                    Diag.warn("[ui] 收藏需要先登录酷狗账号")
                    return@clickable
                }
                busy = true
                ApiExec.run({ r ->
                    busy = false
                    r.onSuccess { ok ->
                        if (ok) {
                            favorited = !favorited
                        } else {
                            Diag.warn("[ui] 收藏失败：${KugouApi.lastError ?: "未知原因"}")
                        }
                    }
                    r.onFailure { busy = false }
                }) {
                    if (favorited) {
                        KugouApi.removeFromFavorites(song)
                    } else {
                        KugouApi.addToFavorites(song)
                    }
                }
            },
    )
}

/** 列表行的小封面（48dp 圆角方块），无图时显示音符占位。 */
@Composable
private fun SongCover(url: String?) {
    val bitmap = rememberCover(url)
    Box(
        Modifier
            .size(48.dp)
            .background(KugouTheme.colors.coverPlaceholder, RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)),
            )
        } else {
            // 占位音符：与播放栏的 ♪ 呼应，避免空白方块
            Text3(text = "♪", color = KugouTheme.colors.faint)
        }
    }
}

/** 列表行（纯展示，可选点击）。 */
@Composable
internal fun ListRow(
    title: String,
    subtitle: String,
    onClick: (() -> Unit)? = null,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable { onClick() } else it }
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text3(text = title, color = KugouTheme.colors.text)
        if (subtitle.isNotBlank()) {
            Text3(
                text = subtitle,
                color = KugouTheme.colors.muted,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * 通用异步列表容器。
 *
 * 统一处理「加载中 / 失败 / 空 / 有数据」四种状态，避免每页重复写而漏掉某种
 * 状态（漏掉空状态是常见 bug：用户看到一片空白以为卡死）。
 *
 * [loginHint] 与 [emptyText] 分开：登录失效导致的空列表要说清楚原因，
 * 不能让用户以为是「真的没有内容」。
 */
@Composable
private fun <T> ListScaffold(
    state: MutableAsyncState<List<T>?>,
    groupTitle: String,
    emptyText: String,
    loginHint: String,
    content: @Composable (List<T>) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        SaltGroupTitle(groupTitle)
        SaltCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                when (val s = state.value) {
                    is Async.Idle, is Async.Loading -> ListRow("正在加载…", "")
                    is Async.Failure -> ListRow("加载失败：${s.message}", "")
                    is Async.Success -> {
                        val list = s.value
                        when {
                            list == null -> ListRow(
                                if (KugouApi.lastNeedsLogin) loginHint
                                else "加载失败：${KugouApi.lastError ?: "未知错误"}",
                                "",
                            )
                            list.isEmpty() -> ListRow(
                                if (KugouApi.lastNeedsLogin) loginHint else emptyText,
                                "",
                            )
                            else -> content(list)
                        }
                    }
                }
            }
        }
    }
}
