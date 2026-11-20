package cn.spw.kugou.lite.ui

import java.util.Locale
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import cn.spw.kugou.lite.sidecar.ApiExec
import cn.spw.kugou.lite.sidecar.Async
import cn.spw.kugou.lite.sidecar.AlbumBrief
import cn.spw.kugou.lite.sidecar.KugouApi
import cn.spw.kugou.lite.sidecar.Playlist
import cn.spw.kugou.lite.sidecar.SearchApi
import cn.spw.kugou.lite.sidecar.SearchResult
import cn.spw.kugou.lite.sidecar.Song
import cn.spw.kugou.lite.sidecar.rememberAsyncState

/**
 * 搜索页。
 *
 * ## 为什么用 `BasicTextField` 而不是宿主原生输入框
 *
 * 宿主（Salt / Voxzen）**没有暴露可复用的输入框原生组件** —— 已用
 * `list_markers.py` 扫描过全部 42147 个 class，`TextField` 相关的
 * `com.moriafly.salt.ui.*` 组件只有展示型（Text / Card / Item / Switch 等），
 * 没有文本输入。因此这里退回 Compose Foundation 的 `BasicTextField` 自绘外观，
 * 其余所有容器与按钮仍用宿主原生控件。
 *
 * ## 搜索不自动触发
 *
 * 只在用户按「搜索」或回车时发请求。一边打字一边请求会打爆第三方接口
 * （它有 2 分钟缓存和频率限制），也会让 UI 抖动。
 */
/**
 * 搜索页 —— 2026-11 改版：热搜词 + 输入建议 + 综合 Tab。
 *
 * ## 三态
 *
 * 1. **空态**（未提交检索词）：搜索框 + 热搜词 chips（`/search/hot`），
 *    输入中显示建议列表（`/search/suggest`，300ms 防抖，点建议即检索）
 * 2. **结果态**（回车或点热搜/建议）：`SlidingTabBar` 四个 Tab
 *    （单曲/歌手/专辑/歌单），**懒加载**——首次切入才发对应请求；
 *    计数来自各端点 total（`/search/complex` 实测不可用，裁决 R2）
 * 3. 各 Tab 内容：单曲 `DataTableRow`、歌手/专辑/歌单 `CoverGridCard` 网格
 *
 * ## 回车提交保留
 *
 * `SearchBox` 的 `onPreviewKeyEvent` 回车拦截不变；只提交不实时搜索，
 * 建议是唯一跟随输入的请求（防抖 + 轻量）。
 */
/** 搜索结果的封面网格列数（内容区宽度稳定，固定三列）。 */
private const val SEARCH_GRID_COLUMNS = 3

@Composable
fun SearchPage(onPick: (Song, List<Song>, Int) -> Unit) {
    var keyword by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf("") }
    var selectedTab by remember { mutableStateOf(0) }

    val songState = rememberAsyncState<SearchResult?>()
    val artistState = rememberAsyncState<List<cn.spw.kugou.lite.sidecar.Artist>>()
    val albumState = rememberAsyncState<SearchApi.SearchPage<AlbumBrief>?>()
    val specialState = rememberAsyncState<SearchApi.SearchPage<Playlist>?>()
    val hotState = rememberAsyncState<List<String>>()
    val suggestState = rememberAsyncState<List<String>>()

    // 热搜词：进页面拉一次
    LaunchedEffect(Unit) { hotState.load { SearchApi.hotWords() } }
    // 输入建议：300ms 防抖；已提交的同词不再拉建议
    LaunchedEffect(keyword) {
        // 关键词一变就作废旧建议（BUG-53）：否则 300ms 防抖期间界面上还是
        // 上一个词的建议，用户点了会搜到他没输入的东西。
        suggestState.reset()
        kotlinx.coroutines.delay(300)
        if (keyword.isNotBlank() && keyword != submitted) {
            suggestState.load { SearchApi.suggest(keyword) }
        }
    }
    // 懒加载：切到某 Tab 才发该 Tab 的请求；单曲 Tab 在提交时立即加载。
    // 判定走 shouldLoadForTab（曾用 `== null` 恒假导致一个请求都不发，见该函数注释）。
    LaunchedEffect(submitted, selectedTab) {
        if (submitted.isBlank()) return@LaunchedEffect
        when (selectedTab) {
            0 -> if (shouldLoadForTab(songState.value)) {
                songState.load { KugouApi.search(submitted) }
            }
            1 -> if (shouldLoadForTab(artistState.value)) {
                artistState.load { SearchApi.searchArtists(submitted) }
            }
            2 -> if (shouldLoadForTab(albumState.value)) {
                albumState.load { SearchApi.searchAlbums(submitted) }
            }
            3 -> if (shouldLoadForTab(specialState.value)) {
                specialState.load { SearchApi.searchSpecial(submitted) }
            }
        }
    }

    val showResults = submitted.isNotBlank()
    val suggestLoaded = (suggestState.value as? Async.Success)?.value
    val showSuggest = !showResults && keyword.isNotBlank() && !suggestLoaded.isNullOrEmpty()
    val showHot = !showResults && !showSuggest

    BoxWithConstraints(Modifier.fillMaxWidth()) {
    val showAlbum = maxWidth.value >= TABLE_ALBUM_MIN_WIDTH_DP
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        SaltCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                SearchBox(
                    value = keyword,
                    onValueChange = { keyword = it },
                    onSubmit = {
                        if (keyword.isNotBlank()) {
                            submitted = keyword
                            selectedTab = 0
                            songState.reset()
                            artistState.reset()
                            albumState.reset()
                            specialState.reset()
                        }
                    },
                )
            }
        }

        when {
            // -------------------------------------------------- 空态：热搜 + 建议
            showHot -> {
                when (val h = hotState.value) {
                    is Async.Success -> if (h.value.isNotEmpty()) {
                        SaltGroupTitle("热搜")
                        SaltCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
                                androidx.compose.foundation.layout.FlowRow {
                                    h.value.take(20).forEach { word ->
                                        HotChip(word) {
                                            keyword = word
                                            submitted = word
                                            selectedTab = 0
                                            songState.reset(); artistState.reset(); albumState.reset(); specialState.reset()
                                        }
                                    }
                                }
                            }
                        }
                    }
                    else -> Unit // 热搜拉取中/失败不占位
                }
            }

            // -------------------------------------------------- 输入中：建议
            showSuggest -> {
                val list = (suggestState.value as Async.Success).value
                SaltCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        list.take(10).forEach { hint ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        keyword = hint
                                        submitted = hint
                                        selectedTab = 0
                                        songState.reset(); artistState.reset(); albumState.reset(); specialState.reset()
                                    }
                                    .padding(horizontal = 16.dp, vertical = 9.dp),
                            ) {
                                Text3("⌕", color = KugouTheme.colors.faint)
                                Text3(
                                    text = hint,
                                    color = KugouTheme.colors.text,
                                    modifier = Modifier.padding(start = 10.dp),
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }

            // -------------------------------------------------- 结果态：综合 Tab
            showResults -> {
                val songTotal = (songState.value as? Async.Success)?.value?.total?.takeIf { it > 0 }
                // 歌手端点实测**无 total 字段**，所以不显示计数（不要用当前页条数冒充）
                val albumTotal = (albumState.value as? Async.Success)?.value?.total?.takeIf { it > 0 }
                val specialTotal = (specialState.value as? Async.Success)?.value?.total?.takeIf { it > 0 }
                val tabs = listOf(
                    "单曲" + (songTotal?.let { " $it" } ?: ""),
                    "歌手",
                    "专辑" + (albumTotal?.let { " $it" } ?: ""),
                    "歌单" + (specialTotal?.let { " $it" } ?: ""),
                )
                SlidingTabBar(tabs = tabs, selected = selectedTab, onSelect = { selectedTab = it })

                when (selectedTab) {
                    0 -> SongResultList(submitted, songState.value, showAlbum, onPick)
                    1 -> ArtistGrid(artistState.value)
                    2 -> AlbumGrid(albumState.value)
                    3 -> SpecialGrid(specialState.value)
                }
            }
        }
    }
    }
}

/** 搜索结果·单曲：DataTableRow 列表，行点击进播放队列。 */
@Composable
private fun SongResultList(
    query: String,
    state: Async<SearchResult?>,
    showAlbum: Boolean,
    onPick: (Song, List<Song>, Int) -> Unit,
) {
    when (val s = state) {
        is Async.Idle, is Async.Loading -> StatusCard("正在搜索…")
        is Async.Failure -> StatusCard("搜索失败：${s.message}")
        is Async.Success -> {
            val result = s.value
            when {
                result == null -> StatusCard(
                    if (KugouApi.lastNeedsLogin) "需要先登录酷狗账号（设置 → 扫码登录）"
                    else "搜索失败：${KugouApi.lastError ?: "未知错误"}",
                )
                result.songs.isEmpty() -> StatusCard("没有找到「$query」相关的歌曲")
                else -> {
                    SaltGroupTitle("找到 ${result.total} 首")
                    SaltCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            result.songs.forEachIndexed { index, song ->
                                DataTableRow(
                                    index = String.format(Locale.ROOT, "%02d", index + 1),
                                    coverUrl = song.coverUrl(120),
                                    title = song.title,
                                    subtitle = song.singer,
                                    album = song.albumName,
                                    duration = song.durationText,
                                    showAlbum = showAlbum,
                                    onRowClick = { onPick(song, result.songs, index) },
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

/** 搜索结果·歌手网格（实测无头像 → 占位音符封面）。 */
@Composable
private fun ArtistGrid(state: Async<List<cn.spw.kugou.lite.sidecar.Artist>>) {
    when (val s = state) {
        is Async.Idle, is Async.Loading -> StatusCard("正在搜索…")
        is Async.Failure -> StatusCard("搜索失败：${s.message}")
        is Async.Success -> {
            val artists = s.value
            if (artists.isEmpty()) {
                StatusCard("没有找到相关歌手")
            } else {
                SaltGroupTitle("歌手")
                SaltCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        gridRows(artists, SEARCH_GRID_COLUMNS).forEach { row ->
                            Row(Modifier.fillMaxWidth()) {
                                row.forEach { artist ->
                                    CoverGridCard(
                                        coverUrl = null,
                                        title = artist.name,
                                        subtitle = "歌手",
                                        modifier = Modifier.weight(1f).padding(4.dp),
                                    ) { PageNav.openArtist(artist.id, artist.name) }
                                }
                                repeat(SEARCH_GRID_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 搜索结果·专辑网格。 */
@Composable
private fun AlbumGrid(state: Async<SearchApi.SearchPage<AlbumBrief>?>) {
    when (val s = state) {
        is Async.Idle, is Async.Loading -> StatusCard("正在搜索…")
        is Async.Failure -> StatusCard("搜索失败：${s.message}")
        is Async.Success -> {
            val page = s.value
            if (page == null || page.items.isEmpty()) {
                StatusCard("没有找到相关专辑")
            } else {
                SaltGroupTitle("专辑")
                SaltCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        gridRows(page.items, SEARCH_GRID_COLUMNS).forEach { row ->
                            Row(Modifier.fillMaxWidth()) {
                                row.forEach { album ->
                                    CoverGridCard(
                                        coverUrl = album.coverAt(300),
                                        title = album.name,
                                        subtitle = album.artist,
                                        modifier = Modifier.weight(1f).padding(4.dp),
                                    ) { PageNav.openAlbum(album.id, album.name) }
                                }
                                repeat(SEARCH_GRID_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 搜索结果·歌单网格（点进统一详情页，gid）。 */
@Composable
private fun SpecialGrid(state: Async<SearchApi.SearchPage<Playlist>?>) {
    when (val s = state) {
        is Async.Idle, is Async.Loading -> StatusCard("正在搜索…")
        is Async.Failure -> StatusCard("搜索失败：${s.message}")
        is Async.Success -> {
            val page = s.value
            if (page == null || page.items.isEmpty()) {
                StatusCard(
                    if (KugouApi.lastNeedsLogin) "需要先登录酷狗账号"
                    else "没有找到相关歌单",
                )
            } else {
                SaltGroupTitle("歌单")
                SaltCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        gridRows(page.items, SEARCH_GRID_COLUMNS).forEach { row ->
                            Row(Modifier.fillMaxWidth()) {
                                row.forEach { pl ->
                                    CoverGridCard(
                                        coverUrl = pl.coverAt(300),
                                        title = pl.name,
                                        subtitle = "${pl.songCount} 首",
                                        modifier = Modifier.weight(1f).padding(4.dp),
                                    ) { PageNav.openPlaylist(pl.id, pl.name) }
                                }
                                repeat(SEARCH_GRID_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 热搜词 chip（选中态用 subtle，不用 accent —— 反目标约束）。 */
@Composable
private fun HotChip(word: String, onClick: () -> Unit) {
    Text3(
        text = word,
        color = KugouTheme.colors.text,
        modifier = Modifier
            .padding(end = 8.dp, bottom = 8.dp)
            .background(
                KugouTheme.colors.subtle,
                RoundedCornerShape(999.dp),
            )
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 5.dp),
        maxLines = 1,
    )
}

/**
 * 自绘搜索框（支持回车提交）。
 *
 * ## 回车提交的实现
 *
 * 用 `Modifier.onPreviewKeyEvent` 拦 `Key.Enter`。选 `onPreviewKeyEvent`
 * 而不是 `onKeyEvent`：BasicTextField 内部会消费方向键/编辑键，
 * 预览阶段拦截才能保证回车一定被我们拿到。
 *
 * 同时保留右侧「搜索」按钮点击，两种方式都能触发。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SearchBox(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(38.dp)
            .background(KugouTheme.colors.subtle, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = KugouTheme.colors.text, fontSize = 14.sp),
            cursorBrush = SolidColor(KugouTheme.colors.accent),
            modifier = Modifier
                .weight(1f)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown &&
                        (event.key == Key.Enter || event.key == Key.NumPadEnter)
                    ) {
                        onSubmit()
                        true
                    } else {
                        false
                    }
                },
        )
        Text3(
            text = "搜索",
            color = KugouTheme.colors.text,
            modifier = Modifier.clickable { onSubmit() },
        )
    }
}

/** 状态提示卡片。 */
@Composable
private fun StatusCard(message: String) {
    SaltCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text3(text = message, color = KugouTheme.colors.muted)
        }
    }
}

/**
 * 自绘文本。
 *
 * 用 Compose 的 `Text` 是因为这几处需要自定义颜色/字号，而宿主的
 * `SaltText` 只暴露 text/modifier/maxLines/overflow（不带 style），
 * 无法表达「次要说明文字用暗一点的颜色」这种层级。
 */
@Composable
internal fun Text3(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: androidx.compose.ui.text.style.TextOverflow = androidx.compose.ui.text.style.TextOverflow.Clip,
) {
    androidx.compose.foundation.text.BasicText(
        text = text,
        modifier = modifier,
        style = TextStyle(color = color, fontSize = 14.sp),
        maxLines = maxLines,
        overflow = overflow,
    )
}
