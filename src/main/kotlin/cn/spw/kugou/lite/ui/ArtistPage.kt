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
import cn.spw.kugou.lite.sidecar.ApiExec
import cn.spw.kugou.lite.sidecar.Async
import cn.spw.kugou.lite.sidecar.ExploreApi
import cn.spw.kugou.lite.sidecar.KugouApi
import cn.spw.kugou.lite.sidecar.Song
import cn.spw.kugou.lite.sidecar.rememberAsyncState

/**
 * 歌手页（2026-11 改版）。
 *
 * ## 结构
 *
 * 头部横幅（头像 + 名字 + 专辑/歌曲/粉丝数 + 关注按钮）→ 三个 Tab：
 * **热门歌曲** / **专辑** / **歌手信息**。
 *
 * ## 关注状态（裁决 R3）
 *
 * `/artist/detail` 的 `user_status` 实测**不随 follow/unfollow 变化**
 * （请求成功但状态不变），所以：
 * - 首帧以 `user_status` 作参考（0=未关注）
 * - 点击后**乐观翻转** UI 状态并立即发请求
 * - 请求失败才回滚
 *
 * 这是装饰性功能，短暂不一致可接受；真机验证时会确认最终语义。
 */
@Composable
fun ArtistPage() {
    val artistId = PageNav.artistId
    val detailState = rememberAsyncState<ExploreApi.ArtistDetail?>()
    val hotState = rememberAsyncState<List<Song>>()
    val albumState = rememberAsyncState<ExploreApi.AlbumPage>()
    var tab by remember { mutableStateOf(0) }
    var following by remember { mutableStateOf(false) }
    var followBusy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(artistId) {
        if (artistId.isBlank()) return@LaunchedEffect
        detailState.load { ExploreApi.artistDetail(artistId) }
        hotState.load { runCatching { KugouApi.artistSongs(artistId, sort = "hot") }.getOrDefault(emptyList()) }
        albumState.load { ExploreApi.artistAlbums(artistId) }
    }
    // 详情到达后初始化关注状态：服务端 user_status 或本地记录（R3 关注态不回落）
    LaunchedEffect(detailState.value) {
        (detailState.value as? Async.Success)?.value?.let { d ->
            following = FollowStore.isFollowing(artistId, d.userStatus)
        }
    }

    val detail = (detailState.value as? Async.Success)?.value
    val name = detail?.name ?: PageNav.detailTitle.ifBlank { "歌手" }
    val meta = buildString {
        if (detail != null) {
            if (detail.albumCount > 0) append("${detail.albumCount} 张专辑 · ")
            append("${detail.songCount} 首歌曲")
            if (detail.fansNums > 0) append(" · ${fansText(detail.fansNums)} 粉丝")
        } else {
            append("歌手")
        }
    }

    // ⚠️ 不要在这里再套 SaltScreen —— 调用方（KugouLitePage 的 SubPage）
    // 已经提供宿主原生页框（标题栏 + 返回键 + 滚动容器）。双层会得到双标题栏、
    // 双返回键与嵌套滚动，且内层返回键回落 PageState::requestBack 会直接退出
    // 整个插件页而不是弹栈（整分支审查 Critical）。
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val showAlbum = maxWidth.value >= TABLE_ALBUM_MIN_WIDTH_DP
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                PageHero(coverUrl = detail?.avatarUrl?.replace("{size}", "240"), title = name, meta = meta) {
                    HeroChip(if (following) "已关注" else "＋ 关注") {
                        if (followBusy) return@HeroChip
                        // 未登录：明确提示，不发请求、不改状态（避免静默回滚）
                        if (!cn.spw.kugou.lite.sidecar.AuthStore.isLoggedIn) {
                            notice = "关注需要先登录（设置 → 登录）"
                            return@HeroChip
                        }
                        followBusy = true
                        val target = !following
                        following = target // 乐观翻转
                        FollowStore.setFollowing(artistId, target) // 本地记住（R3）
                        ApiExec.run(onResult = { res ->
                            val ok = res.getOrDefault(false)
                            if (!ok) {
                                following = !target
                                FollowStore.setFollowing(artistId, !target)
                                notice = "关注操作失败，请稍后重试"
                                Diag.warn("[artist] 关注操作失败，已回滚")
                            }
                            followBusy = false
                        }) {
                            if (target) ExploreApi.follow(artistId) else ExploreApi.unfollow(artistId)
                        }
                    }
                    val hot = (hotState.value as? Async.Success)?.value.orEmpty()
                    if (hot.isNotEmpty()) {
                        HeroChip("▶ 播放热门") { playSongsFromQueue(hot, 0) }
                    }
                }

                notice?.let {
                    Text3(
                        text = it,
                        color = KugouTheme.colors.muted,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }

                SlidingTabBar(
                    tabs = listOf("热门歌曲", "专辑", "信息"),
                    selected = tab,
                    onSelect = { tab = it },
                )

                when (tab) {
                    0 -> HotSongs(hotState.value, showAlbum)
                    1 -> Albums(albumState.value)
                    else -> Info(detail)
                }
        }
    }
}

/** 歌手页专辑网格列数（内容区宽度稳定，固定三列）。 */
private const val ARTIST_GRID_COLUMNS = 3

/** 粉丝数文本。 */
private fun fansText(n: Long): String = when {
    n >= 10_000 -> "${n / 10_000}万"
    else -> n.toString()
}

/** 头部操作胶囊。 */
@Composable
private fun HeroChip(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text3(text = label, color = KugouTheme.colors.text, maxLines = 1)
    }
}

/** Tab 1：热门歌曲。 */
@Composable
private fun HotSongs(state: Async<List<Song>>, showAlbum: Boolean) {
    when (val s = state) {
        is Async.Idle, is Async.Loading -> ArtistStatus("正在加载…")
        is Async.Failure -> ArtistStatus("加载失败：${s.message}")
        is Async.Success -> {
            val list = s.value
            if (list.isEmpty()) {
                ArtistStatus("该歌手暂无歌曲")
            } else {
                SaltCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        list.forEachIndexed { index, song ->
                            DataTableRow(
                                index = "%02d".format(index + 1),
                                coverUrl = song.coverUrl(120),
                                title = song.title,
                                subtitle = song.singer,
                                album = song.albumName,
                                duration = song.durationText,
                                showAlbum = showAlbum,
                                onRowClick = { playSongsFromQueue(list, index) },
                                cells = DataTableCells(
                                    onAlbum = if (song.albumId.isNotBlank()) {
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
}

/** Tab 2：专辑网格。 */
@Composable
private fun Albums(state: Async<ExploreApi.AlbumPage>) {
    when (val s = state) {
        is Async.Idle, is Async.Loading -> ArtistStatus("正在加载…")
        is Async.Failure -> ArtistStatus("加载失败：${s.message}")
        is Async.Success -> {
            val page = s.value
            if (page.items.isEmpty()) {
                ArtistStatus("该歌手暂无专辑")
            } else {
                SaltCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        gridRows(page.items, ARTIST_GRID_COLUMNS).forEach { row ->
                            Row(Modifier.fillMaxWidth()) {
                                row.forEach { al: AlbumBrief ->
                                    CoverGridCard(
                                        coverUrl = al.coverAt(300),
                                        title = al.name,
                                        subtitle = al.artist,
                                        modifier = Modifier.weight(1f).padding(4.dp),
                                    ) { PageNav.openAlbum(al.id, al.name) }
                                }
                                repeat(ARTIST_GRID_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Tab 3：歌手信息。 */
@Composable
private fun Info(detail: ExploreApi.ArtistDetail?) {
    if (detail == null) {
        ArtistStatus("暂无信息")
        return
    }
    SaltCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            val intro = detail.intro?.takeIf { it.isNotBlank() }
            if (intro == null) {
                Text3("暂无简介", color = KugouTheme.colors.muted)
            } else {
                Text3(text = intro, color = KugouTheme.colors.text)
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                SmallStat("${detail.songCount}", "歌曲")
                SmallStat("${detail.albumCount}", "专辑")
                SmallStat(fansText(detail.fansNums), "粉丝")
            }
        }
    }
}

/** 信息页小指标。 */
@Composable
private fun SmallStat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text3(text = value, color = KugouTheme.colors.text)
        Text3(text = label, color = KugouTheme.colors.muted, modifier = Modifier.padding(top = 1.dp))
    }
}

/** 状态提示。 */
@Composable
private fun ArtistStatus(text: String) {
    SaltCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Box(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 22.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text3(text, color = KugouTheme.colors.muted)
        }
    }
}
