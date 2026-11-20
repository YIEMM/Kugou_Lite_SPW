package cn.spw.kugou.lite.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.spw.kugou.lite.sidecar.Async
import cn.spw.kugou.lite.sidecar.ApiExec
import cn.spw.kugou.lite.sidecar.ExploreApi
import cn.spw.kugou.lite.sidecar.KugouApi
import cn.spw.kugou.lite.sidecar.SquareApi
import cn.spw.kugou.lite.sidecar.SquarePlaylist
import cn.spw.kugou.lite.sidecar.SquareTagGroup
import cn.spw.kugou.lite.sidecar.rememberAsyncState

/**
 * 歌单广场。
 *
 * ## 数据源（实测，规格附录）
 *
 * - 分类：`/playlist/tags` → 6 个父分组（场景/主题/语种/风格/心情/年代）；
 *   这里取前 5 个作 Tab，其余归入最后一个。**「精选」= category_id=0**（默认推荐流）。
 * - 歌单：`/top/playlist?module_id=2&category_id=&page=&pagesize=`
 *   → `special_list[]` + `has_next`；「加载更多」用底部按钮追加（不做无限滚动）。
 *
 * ## 为什么不用 /sheet/…
 *
 * `/sheet/…` 实测是**曲谱广场**接口（返回 AI 弹唱谱），与歌单无关 —— 裁决见附录。
 */
@Composable
fun SquarePage() {
    val tagsState = rememberAsyncState<List<SquareTagGroup>>()
    val listState = rememberAsyncState<SquareApi.SquarePage>()
    var tab by remember { mutableStateOf(0) }                 // 0=精选，1..=各父分组
    var activeTag by remember { mutableStateOf("0") }         // 当前 category_id
    var page by remember { mutableStateOf(1) }
    var loaded by remember { mutableStateOf(ArrayList<SquarePlaylist>()) }
    var hasNext by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { tagsState.load { SquareApi.tags() } }

    val groups = (tagsState.value as? Async.Success)?.value.orEmpty()
    val tabs = listOf("精选") + groups.take(5).map { it.name }

    fun reload() {
        page = 1
        loaded = ArrayList()
        hasNext = true
        listState.load { SquareApi.playlists(categoryId = activeTag, page = 1) }
    }

    // 首次进入 / Tab 切换时拉第一页
    LaunchedEffect(activeTag) { reload() }

    // 第一页结果回填
    LaunchedEffect(listState.value) {
        val s = listState.value
        if (s is Async.Success) {
            loaded = ArrayList(s.value.items)
            hasNext = s.value.hasNext
        }
    }

    // 外层由 KugouLitePage 的 SubPage 提供宿主原生页框（标题 + 返回），
    // 这里不要再套 SaltScreen（双层会双标题栏，且内层返回键会退出整个插件页）。
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            if (tabs.size > 1) {
                SlidingTabBar(tabs = tabs, selected = tab, onSelect = { idx ->
                    tab = idx
                    activeTag = if (idx == 0) "0" else groups.getOrNull(idx - 1)?.tagId ?: "0"
                })
            }

            // 子标签 chips（当前父分组的 son[]）
            val children = if (tab > 0) groups.getOrNull(tab - 1)?.children.orEmpty() else emptyList()
            if (children.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    FlowRow {
                        children.take(12).forEach { child ->
                            TagChip(child.name, child.tagId == activeTag) {
                                activeTag = child.tagId
                            }
                        }
                    }
                }
            }

            when (val s = listState.value) {
                is Async.Idle, is Async.Loading -> if (loaded.isEmpty()) StatusCardSimple("正在加载…")
                is Async.Failure -> StatusCardSimple("加载失败：${s.message}")
                is Async.Success -> Unit
            }

            if (loaded.isNotEmpty()) {
                SaltCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        gridRows(loaded, SQUARE_GRID_COLUMNS).forEach { row ->
                            Row(Modifier.fillMaxWidth()) {
                                row.forEach { pl ->
                                    CoverGridCard(
                                        coverUrl = pl.coverUrl?.replace("{size}", "300"),
                                        title = pl.name,
                                        subtitle = SquareApi.playCountText(pl.playCount) + " 播放",
                                        modifier = Modifier.weight(1f).padding(4.dp),
                                    ) { PageNav.openPlaylist(pl.collectionId, pl.name) }
                                }
                                repeat(SQUARE_GRID_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }

                if (hasNext) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Text3(
                            text = if (loadingMore) "加载中…" else "加载更多",
                            color = if (loadingMore) KugouTheme.colors.faint else KugouTheme.colors.text,
                            modifier = Modifier.clickable(enabled = !loadingMore) {
                                loadingMore = true
                                val next = page + 1
                                ApiExec.run(onResult = { res ->
                                    val r = res.getOrNull()
                                    if (r != null) {
                                        page = next
                                        loaded = ArrayList(loaded + r.items)
                                        hasNext = r.hasNext
                                    }
                                    loadingMore = false
                                }) { SquareApi.playlists(categoryId = activeTag, page = next) }
                            },
                        )
                    }
                }
            } else if (listState.value is Async.Success) {
                StatusCardSimple("这个分类暂时没有歌单")
            }
        }
    }

/** 播放历史：按日期分组的密集列表（`/user/history`，实测返回非时间序，API 层已重排）。 */
@Composable
fun HistoryPage() {
    val state = rememberAsyncState<List<ExploreApi.HistoryDay>>()
    LaunchedEffect(Unit) { state.load { ExploreApi.history() } }

    // 外层由 SubPage 提供页框（同 SquarePage 的说明）。
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            when (val s = state.value) {
                is Async.Idle, is Async.Loading -> StatusCardSimple("正在加载…")
                is Async.Failure -> StatusCardSimple("加载失败：${s.message}")
                is Async.Success -> {
                    val days = s.value
                    when {
                        days.isEmpty() -> StatusCardSimple(
                            if (KugouApi.lastNeedsLogin) "需要登录酷狗账号"
                            else "还没有播放记录",
                        )
                        else -> days.forEach { day ->
                            SaltGroupTitle(day.label)
                            SaltCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                    day.songs.forEachIndexed { i, song ->
                                        DataTableRow(
                                            index = "▶",
                                            coverUrl = song.coverUrl(120),
                                            title = song.title,
                                            subtitle = song.singer,
                                            album = song.albumName,
                                            duration = song.durationText,
                                            onRowClick = { playSongsFromQueue(day.songs, i) },
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
    }

/** 广场封面网格列数（内容区宽度稳定，固定三列）。 */
private const val SQUARE_GRID_COLUMNS = 3

/** 标签 chip。 */
@Composable
private fun TagChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text3(
        text = label,
        color = if (selected) KugouTheme.colors.text else KugouTheme.colors.muted,
        modifier = Modifier
            .padding(end = 8.dp, bottom = 8.dp)
            .background(
                if (selected) KugouTheme.colors.selectedBackground else KugouTheme.colors.subtle,
                RoundedCornerShape(999.dp),
            )
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 5.dp),
        maxLines = 1,
    )
}

/** 简单状态卡（本文件内部用，避免依赖 BrowsePages 的私有实现）。 */
@Composable
private fun StatusCardSimple(text: String) {
    SaltCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Box(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text3(text, color = KugouTheme.colors.muted)
        }
    }
}
