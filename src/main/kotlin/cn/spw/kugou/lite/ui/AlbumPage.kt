package cn.spw.kugou.lite.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import cn.spw.kugou.lite.sidecar.Album
import cn.spw.kugou.lite.sidecar.Async
import cn.spw.kugou.lite.sidecar.KugouApi
import cn.spw.kugou.lite.sidecar.Song
import cn.spw.kugou.lite.sidecar.rememberAsyncState

/**
 * 专辑页：头部信息 + 曲目列表。
 *
 * 从歌曲行点击专辑名进入（[PageNav.albumId] 由调用方设置好）。
 */
@Composable
fun AlbumPage() {
    val albumId = PageNav.albumId
    val detailState = rememberAsyncState<Album?>()
    val songsState = rememberAsyncState<List<Song>?>()

    LaunchedEffect(albumId) {
        detailState.load { if (albumId.isBlank()) null else KugouApi.albumDetail(albumId) }
        songsState.load { if (albumId.isBlank()) emptyList() else KugouApi.albumSongs(albumId) }
    }

    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        // 头部：封面 + 专辑名 + 歌手 + 发行信息
        SaltCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.Top,
            ) {
                val album = (detailState.value as? Async.Success)?.value
                AlbumCover(album?.coverUrl(300))

                Column(Modifier.padding(start = 14.dp)) {
                    Text3(
                        text = album?.name ?: PageNav.detailTitle.ifBlank { "专辑" },
                        color = KugouTheme.colors.text,
                    )
                    if (album != null) {
                        Text3(
                            text = album.artist,
                            color = KugouTheme.colors.muted,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        val meta = buildList {
                            album.type?.takeIf { it.isNotBlank() }?.let { add(it) }
                            album.publishDate?.takeIf { it.isNotBlank() }?.let { add(it) }
                            if (album.songCount > 0) add("${album.songCount} 首")
                        }.joinToString(" · ")
                        if (meta.isNotBlank()) {
                            Text3(
                                text = meta,
                                color = KugouTheme.colors.muted,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                }
            }
        }

        SaltGroupTitle("曲目")

        SaltCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                when (val s = songsState.value) {
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
                            list.isEmpty() -> ListRow("该专辑暂无曲目", "")
                            // 统一骨架：曲序 + 封面 + 歌名歌手 + 时长（专辑页不重复显示专辑列）
                            else -> list.forEachIndexed { index, song ->
                                DataTableRow(
                                    index = (index + 1).toString().padStart(2, '0'),
                                    coverUrl = song.coverUrl(120),
                                    title = song.title,
                                    subtitle = song.singer,
                                    album = null,
                                    duration = song.durationText,
                                    showAlbum = false,
                                    onRowClick = { playSongsFromQueue(list, index) },
                                    favorite = song,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 专辑封面（120dp 方块）。 */
@Composable
private fun AlbumCover(url: String?) {
    val bitmap = rememberCover(url)
    Box(
        Modifier
            .size(120.dp)
            .background(KugouTheme.colors.coverPlaceholder, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(120.dp).clip(RoundedCornerShape(8.dp)),
            )
        } else {
            Text3("♪", color = KugouTheme.colors.faint)
        }
    }
}

/**
 * 专辑曲目行：序号 + 歌名 + 歌手时长。
 *
 * 与 [SongRow] 的区别是左侧显示**曲序**而不是封面 ——
 * 同一张专辑的曲目封面都一样，重复展示没有信息量。
 */
@Composable
private fun AlbumTrackRow(index: Int, song: Song, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text3(
            text = index.toString().padStart(2, '0'),
            color = KugouTheme.colors.faint,
        )
        Column(Modifier.weight(1f)) {
            Text3(text = song.title, color = KugouTheme.colors.text)
            val sub = buildString {
                append(song.singer)
                if (song.durationSec > 0) {
                    append(" · ")
                    append(song.durationText)
                }
            }
            Text3(
                text = sub,
                color = KugouTheme.colors.muted,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
