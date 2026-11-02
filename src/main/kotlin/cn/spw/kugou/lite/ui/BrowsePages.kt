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
import cn.spw.kugou.lite.diag.Diag
import cn.spw.kugou.lite.host.PlaybackBridge
import cn.spw.kugou.lite.sidecar.ApiExec
import cn.spw.kugou.lite.sidecar.Async
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
        list.forEach { s ->
            SongRow(song = s, onClick = { onSongPicked(s) })
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
        list.forEach { s ->
            SongRow(song = s, onClick = { onSongPicked(s) })
        }
    }
}

/**
 * 点歌的统一入口。
 *
 * 流程：后台取流 → 拿到 CDN 直链 → 构造 `PiscesMediaItem` 推进宿主队列。
 * 全程在 [ApiExec] 的后台线程，不阻塞 UI。
 *
 * 失败时只写日志、不弹窗：宿主把插件组合期异常视为 `FATAL_FAILURE`，
 * 而播放失败属于常见情况（付费曲、未登录、接口失效），必须静默降级。
 */
private fun onSongPicked(song: Song) {
    Diag.info("[page] 点歌「${song.title}」- ${song.singer}")
    ApiExec.run({ result ->
        result.onFailure { Diag.warn("[page] 取流异常：${it.message}") }
    }) {
        val stream = KugouApi.streamUrl(song)
        if (stream == null) {
            Diag.warn("[page] 取流失败：${KugouApi.lastError ?: "未知原因"}")
        } else {
            val ok = PlaybackBridge.play(song, stream)
            if (!ok) Diag.warn("[page] 入队失败")
        }
    }
}

/** 一行歌曲。点击回调由调用方决定用途。 */
@Composable
internal fun SongRow(song: Song, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text3(text = song.title, color = Color(0xFFEDEDED))
        val sub = buildString {
            append(song.singer)
            if (song.durationSec > 0) {
                append(" · ")
                append(song.durationText)
            }
        }
        Text3(
            text = sub,
            color = Color(0xFF9A9A9A),
            modifier = Modifier.padding(top = 2.dp),
        )
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
