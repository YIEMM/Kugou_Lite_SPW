package cn.spw.kugou.lite.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.spw.kugou.lite.sidecar.AlbumBrief
import cn.spw.kugou.lite.sidecar.Song

/**
 * 发现页的单个区块卡片。
 *
 * ## 与 MD3Music 的对应
 *
 * 参考 `discover_page.dart` 的 `_SectionBlock`：标题行 + `›` 进详情，
 * 下面只放前 4 条（`/home/discover` 首屏 4 首，新碟 3 张封面）。
 * 刻意克制条数，避免首屏被一个区块占满。
 *
 * ## 为什么用 SaltCard
 *
 * 卡片底色、圆角、描边由宿主 `SaltTheme` 提供，自绘永远对不齐原生观感。
 */

/**
 * 歌曲类区块（每日推荐 / 刷歌推荐 / 新歌速递）。
 *
 * @param songs 已截取前 4 条的列表
 * @param allSongs 该区块的完整列表，点第 i 条时用来建整条播放队列
 * @param onMore 点击标题右侧 `›`
 * @param onPick 点某条歌曲，参数为（完整列表, 下标）
 */
@Composable
fun DiscoverSectionCard(
    title: String,
    songs: List<Song>,
    allSongs: List<Song>,
    onMore: () -> Unit,
    onPick: (List<Song>, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (songs.isEmpty()) return
    SaltCard(modifier) {
        SectionHeader(title, onMore)
        Column(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 6.dp)) {
            songs.forEachIndexed { index, song ->
                val globalIndex = allSongs.indexOfFirst { it.hash == song.hash && it.hash.isNotBlank() }
                    .takeIf { it >= 0 } ?: index
                SectionSongRow(song) { onPick(allSongs, globalIndex) }
            }
        }
    }
}

/**
 * 专辑类区块（新碟上架）。
 *
 * @param albums 3 张封面
 * @param onMore 点击 `›`
 * @param onPick 点某张封面，参数为（完整列表, 下标）
 */
@Composable
fun AlbumSectionCard(
    title: String,
    albums: List<AlbumBrief>,
    allAlbums: List<AlbumBrief>,
    onMore: () -> Unit,
    onPick: (List<AlbumBrief>, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (albums.isEmpty()) return
    SaltCard(modifier) {
        SectionHeader(title, onMore)
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            albums.forEachIndexed { index, album ->
                Column(
                    Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CoverBox(
                        url = album.coverAt(240),
                        sizeDp = 64,
                        cornerDp = 6,
                    )
                    Text3(
                        text = album.name,
                        color = KugouTheme.colors.text,
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .clickable {
                                val i = allAlbums.indexOfFirst { it.id == album.id }
                                    .takeIf { it >= 0 } ?: index
                                onPick(allAlbums, i)
                            },
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** 区块标题行：左侧标题，右侧 `›` 进详情。 */
@Composable
private fun SectionHeader(title: String, onMore: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 14.dp, top = 10.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text3(title, color = KugouTheme.colors.text)
        Text3(
            text = "›",
            color = KugouTheme.colors.muted,
            modifier = Modifier.clickable { onMore() },
        )
    }
}

/** 区块内一行紧凑歌曲：44dp 封面 + 歌名 + 歌手；单行省略对齐 MD3，保证各卡等高。 */
@Composable
private fun SectionSongRow(song: Song, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 6.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverBox(song.coverUrl(120), 40, 5)
        Column(Modifier.padding(start = 9.dp).weight(1f)) {
            Text3(
                text = song.title,
                color = KugouTheme.colors.text,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Text3(
                text = song.singer,
                color = KugouTheme.colors.muted,
                modifier = Modifier.padding(top = 1.dp),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
}
