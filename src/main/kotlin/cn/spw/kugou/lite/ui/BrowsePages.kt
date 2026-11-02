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
 * 需要登录（`/user/playlist` 走账号态）。未登录时显示引导文案而不是空列表 ——
 * 否则用户会以为「没有歌单」而不是「需要登录」。
 */
@Composable
fun PlaylistPage() {
    val state = rememberAsyncState<List<Playlist>?>()
    LaunchedEffect(Unit) { state.load { KugouApi.userPlaylists() } }

    ListScaffold(state) { list ->
        list.forEach { pl ->
            ListRow(title = pl.name, subtitle = "${pl.songCount} 首")
        }
    }
}

/** 排行榜列表。 */
@Composable
fun RankPage() {
    val state = rememberAsyncState<List<Rank>?>()
    LaunchedEffect(Unit) { state.load { KugouApi.ranks() } }

    ListScaffold(state) { list ->
        list.forEach { r ->
            ListRow(
                title = r.name,
                subtitle = if (r.songCount > 0) "${r.songCount} 首" else "",
            )
        }
    }
}

/** 每日推荐。 */
@Composable
fun DailyPage() {
    val state = rememberAsyncState<List<Song>?>()
    LaunchedEffect(Unit) { state.load { KugouApi.dailyRecommend() } }

    ListScaffold(state) { list ->
        list.forEach { s ->
            ListRow(title = s.title, subtitle = "${s.singer} · ${s.durationText}")
        }
    }
}

/** 列表行。 */
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
 */
@Composable
private fun <T> ListScaffold(
    state: MutableAsyncState<List<T>?>,
    groupTitle: String = "列表",
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
                                if (KugouApi.lastNeedsLogin) "需要先登录酷狗账号" else "加载失败",
                                "",
                            )
                            list.isEmpty() -> ListRow(
                                if (KugouApi.lastNeedsLogin) "需要先登录酷狗账号" else "暂无内容",
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
