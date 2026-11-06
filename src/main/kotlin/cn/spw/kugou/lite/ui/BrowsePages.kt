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
 * 需要登录（`/user/playlist` 走账号态）。未登录或 token 过期时显示引导文案
 * 而不是空列表 —— 否则用户会以为「没有歌单」而不是「需要重新登录」。
 */
@Composable
fun PlaylistPage() {
    val state = rememberAsyncState<List<Playlist>?>()
    LaunchedEffect(Unit) { state.load { KugouApi.userPlaylists() } }

    ListScaffold(
        state = state,
        groupTitle = "我的歌单",
        emptyText = "暂无歌单",
        loginHint = "需要登录酷狗账号（token 可能已过期）",
    ) { list ->
        list.forEach { pl ->
            ListRow(
                title = pl.name,
                subtitle = "${pl.songCount} 首",
                onClick = { PageNav.openPlaylist(pl.id, pl.name) },
            )
        }
    }
}

/** 排行榜列表。每一项可点进歌曲列表。 */
@Composable
fun RankPage() {
    val state = rememberAsyncState<List<Rank>?>()
    LaunchedEffect(Unit) { state.load { KugouApi.ranks() } }

    ListScaffold(
        state = state,
        groupTitle = "排行榜",
        emptyText = "暂无榜单",
        loginHint = "需要登录酷狗账号",
    ) { list ->
        list.forEach { r ->
            ListRow(
                title = r.name,
                subtitle = if (r.songCount > 0) "${r.songCount} 首" else "点击查看",
                onClick = { PageNav.openRank(r.id, r.name) },
            )
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
 * 一行歌曲：封面 + 歌名 + 歌手·时长。
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
        Column(Modifier.padding(start = 12.dp)) {
            Text3(text = song.title, color = Color(0xFFEDEDED))

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
                        Text3("、", color = Color(0xFF9A9A9A))
                    }
                    if (artist.clickable) {
                        Text3(
                            text = artist.name,
                            color = Color(0xFF6FA8DC),
                            modifier = Modifier.clickable {
                                PageNav.openArtist(artist.id, artist.name)
                            },
                        )
                    } else {
                        Text3(text = artist.name, color = Color(0xFF9A9A9A))
                    }
                }
                if (song.durationSec > 0) {
                    Text3(text = " · ${song.durationText}", color = Color(0xFF9A9A9A))
                }
            }
        }
    }
}

/** 列表行的小封面（48dp 圆角方块），无图时显示音符占位。 */
@Composable
private fun SongCover(url: String?) {
    val bitmap = rememberCover(url)
    Box(
        Modifier
            .size(48.dp)
            .background(Color(0xFF2E2E2E), RoundedCornerShape(6.dp)),
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
            Text3(text = "♪", color = Color(0xFF6E6E6E))
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
        Text3(text = title, color = Color(0xFFEDEDED))
        if (subtitle.isNotBlank()) {
            Text3(
                text = subtitle,
                color = Color(0xFF9A9A9A),
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
