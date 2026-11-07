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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import cn.spw.kugou.lite.sidecar.Async
import cn.spw.kugou.lite.sidecar.KugouApi
import cn.spw.kugou.lite.sidecar.Song
import cn.spw.kugou.lite.sidecar.rememberAsyncState

/**
 * 歌手页：热门单曲。
 *
 * 从歌曲行点击歌手名进入，[PageNav.artistId] 与 [PageNav.detailTitle]
 * 由调用方设置好。
 *
 * ## 排序切换
 *
 * 酷狗 `/artist/audios` 的 `sort` 参数：
 * - `hot` → 按热度（默认）
 * - 其它 → 按时间
 *
 * 两种都保留，便于用户找新歌。
 */
@Composable
fun ArtistPage() {
    val artistId = PageNav.artistId
    val state = rememberAsyncState<List<Song>?>()
    var sort by remember { mutableStateOf("hot") }

    LaunchedEffect(artistId, sort) {
        state.load {
            if (artistId.isBlank()) emptyList() else KugouApi.artistSongs(artistId, sort = sort)
        }
    }

    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        SaltGroupTitle(PageNav.detailTitle.ifBlank { "歌手" })

        // 排序切换
        SaltCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                SortTab("最热", selected = sort == "hot") { sort = "hot" }
                SortTab("最新", selected = sort != "hot") { sort = "new" }
            }
        }

        SaltCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                when (val s = state.value) {
                    is Async.Idle, is Async.Loading -> ListRow("正在加载…", "")
                    is Async.Failure -> ListRow("加载失败：${s.message}", "")
                    is Async.Success -> {
                        val list = s.value
                        when {
                            list == null -> ListRow(
                                if (KugouApi.lastNeedsLogin) "需要登录酷狗账号"
                                else "加载失败：${KugouApi.lastError ?: "未知错误"}",
                                "",
                            )
                            list.isEmpty() -> ListRow("该歌手暂无歌曲", "")
                            else -> list.forEachIndexed { index, song ->
                                SongRow(song = song, onClick = { onArtistSongPicked(list, index) })
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 排序标签。 */
@Composable
private fun SortTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Text3(
        text = label,
        color = if (selected) KugouTheme.colors.text else KugouTheme.colors.muted,
        modifier = Modifier.clickable { onClick() },
    )
}

/**
 * 歌手页点歌。
 *
 * 与其它页面一致：后台取流 → 缓存 → 建整条队列 → 播放。
 */
private fun onArtistSongPicked(queue: List<Song>, index: Int) {
    cn.spw.kugou.lite.ui.playSongsFromQueue(queue, index)
}
