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
import androidx.compose.foundation.layout.BoxWithConstraints
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
import cn.spw.kugou.lite.sidecar.ExploreApi
import cn.spw.kugou.lite.sidecar.Id3CoverWriter
import cn.spw.kugou.lite.sidecar.KugouApi
import cn.spw.kugou.lite.sidecar.MutableAsyncState
import cn.spw.kugou.lite.sidecar.Playlist
import cn.spw.kugou.lite.sidecar.PlaylistManage
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Spacer
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
/**
 * 我的音乐（原「我的歌单」）。
 *
 * ## 2026-11 改版
 *
 * 三段式分组升级为：**顶部统计卡**（我喜欢/自建/收藏 计数）+ **过滤 Tab**
 * （全部/我喜欢/自建/收藏）+ **封面网格**（每项真实封面 + 名称 + 曲目数）。
 * 工具栏右侧「＋」新建歌单（[PlaylistManage.create]），自建歌单行尾「…」
 * 弹菜单（删除；重命名因上游 30203 暂不提供，见裁决 R1）。
 *
 * ## 为什么仍按 listid 识别系统歌单
 *
 * 酷狗把「我喜欢」「默认收藏」都实现为自建歌单（`type=0`），只有 listid
 * 固定（我喜欢=2 / 默认收藏=1）。按名字识别会被用户改名破坏。
 *
 * ## 为什么删除后不回读
 *
 * `/playlist/del` 成功后读缓存有 3–8 秒延迟（实测），立即回读会看到旧数据。
 * UI 先本地剔除再后台 [PlaylistManage.verifyGone] 轮询确认，失败才回滚提示。
 */
@Composable
fun PlaylistPage() {
    val state = rememberAsyncState<List<Playlist>?>()
    var reload by remember { mutableStateOf(0) }
    var filter by remember { mutableStateOf(0) }              // 0全部 1我喜欢 2自建 3收藏
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    /** 等待二次确认删除的歌单 listid（null = 无待确认）。 */
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(reload) { state.load { KugouApi.userPlaylists() } }

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

                        // ------------------------------------------- 统计卡
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            StatCard("${system.firstOrNull { it.isFavorites }?.songCount ?: 0}", "我喜欢", Modifier.weight(1f))
                            StatCard("${own.size}", "自建歌单", Modifier.weight(1f))
                            StatCard("${collected.size}", "收藏歌单", Modifier.weight(1f))
                        }

                        // ------------------------------------------- 过滤 Tab
                        val tabs = listOf("全部 ${list.size}", "我喜欢", "自建 ${own.size}", "收藏 ${collected.size}")
                        SlidingTabBar(tabs = tabs, selected = filter, onSelect = { filter = it })

                        // ------------------------------------------- 工具栏（新建）
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text3(
                                text = "＋ 新建歌单",
                                color = KugouTheme.colors.text,
                                modifier = Modifier.clickable {
                                    creating = !creating
                                    newName = ""
                                    notice = null
                                },
                            )
                        }

                        // 新建输入行
                        if (creating) {
                            SaltCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    InlineField(
                                        value = newName,
                                        placeholder = "歌单名称",
                                        onValueChange = { newName = it },
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text3(
                                        text = if (busy) "创建中…" else "确定",
                                        color = if (busy) KugouTheme.colors.faint else KugouTheme.colors.text,
                                        modifier = Modifier
                                            .padding(start = 12.dp)
                                            .clickable(enabled = !busy && newName.isNotBlank()) {
                                                busy = true
                                                val name = newName.trim()
                                                ApiExec.run(onResult = {
                                                    busy = false
                                                    if (it.getOrNull() == null) {
                                                        notice = if (KugouApi.lastNeedsLogin) "请先登录" else "创建失败"
                                                    } else {
                                                        creating = false
                                                        newName = ""
                                                        reload++   // 重新拉列表确认
                                                    }
                                                }) { PlaylistManage.create(name) }
                                            },
                                    )
                                }
                            }
                        }

                        notice?.let {
                            Text3(
                                text = it,
                                color = KugouTheme.colors.muted,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }

                        // ------------------------------------------- 分组网格
                        val shown: List<Playlist> = when (filter) {
                            1 -> system
                            2 -> own
                            3 -> collected
                            else -> list
                        }

                        if (shown.isEmpty()) {
                            StatusBlock("这里还没有歌单")
                        } else {
                            SaltCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                                BoxWithConstraints(Modifier.fillMaxWidth()) {
                                val cols = coverGridColumns(maxWidth.value)
                                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                    gridRows(shown, cols).forEach { row ->
                                        Row(Modifier.fillMaxWidth()) {
                                            row.forEach { pl ->
                                                Box(Modifier.weight(1f)) {
                                                    CoverGridCard(
                                                        coverUrl = pl.coverAt(300),
                                                        title = (if (pl.isFavorites) "♥ " else "") + pl.name,
                                                        subtitle = "${pl.songCount} 首",
                                                        modifier = Modifier.padding(4.dp),
                                                    ) { PageNav.openPlaylist(pl.id, pl.name, pl.songCount) }
                                                    // 自建歌单：行尾「…」→ 两步确认删除
                                                    // （误触即永久删歌单，必须有确认层）
                                                    if (pl.isOwn && !system.contains(pl)) {
                                                        if (pendingDeleteId == pl.listId) {
                                                            // ---- 确认态：铺满整卡的确认条，避免误触
                                                            Row(
                                                                Modifier
                                                                    .matchParentSize()
                                                                    .background(
                                                                        KugouTheme.colors.subtle,
                                                                        androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                                                                    ),
                                                                horizontalArrangement = Arrangement.Center,
                                                                verticalAlignment = Alignment.CenterVertically,
                                                            ) {
                                                                Text3(
                                                                    text = "取消",
                                                                    color = KugouTheme.colors.muted,
                                                                    modifier = Modifier
                                                                        .clickable { pendingDeleteId = null }
                                                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                                                )
                                                                Text3(
                                                                    text = if (busy) "删除中…" else "确认删除",
                                                                    color = if (busy) KugouTheme.colors.faint else KugouTheme.colors.danger,
                                                                    modifier = Modifier
                                                                        .clickable(enabled = !busy) {
                                                                            busy = true
                                                                            val id = pl.listId
                                                                            val nm = pl.name
                                                                            ApiExec.run(onResult = { res ->
                                                                                val ok = res.getOrDefault(false)
                                                                                busy = false
                                                                                pendingDeleteId = null
                                                                                notice = when {
                                                                                    !ok && KugouApi.lastNeedsLogin -> "请先登录"
                                                                                    !ok -> "删除失败"
                                                                                    else -> "已删除「$nm」"
                                                                                }
                                                                                if (ok) {
                                                                                    reload++
                                                                                    // 后台确认消失（读缓存延迟）。
                                                                                    // 注意：Sleep 在独立线程做，不占 ApiExec 池。
                                                                                    Thread {
                                                                                        val gone = PlaylistManage.verifyGone(id)
                                                                                        if (!gone) {
                                                                                            notice = "「$nm」可能仍在服务端，请刷新确认"
                                                                                        }
                                                                                    }.apply { isDaemon = true }.start()
                                                                                }
                                                                            }) { PlaylistManage.delete(id) }
                                                                        }
                                                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                                                )
                                                            }
                                                        } else {
                                                            Text3(
                                                                text = "…",
                                                                color = KugouTheme.colors.muted,
                                                                modifier = Modifier
                                                                    .align(Alignment.TopEnd)
                                                                    .padding(end = 2.dp)
                                                                    .background(
                                                                        KugouTheme.colors.cardBackground,
                                                                        androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                                                                    )
                                                                    .clickable { pendingDeleteId = pl.listId }
                                                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                            repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                                        }
                                    }
                                }
                                }
                            }
                        }
                    }
                }

                        // ------------------------------------------- 签到日历入口
                        // 对应 md3「我的」页会员卡片左侧入口：一个独立的二级页，
                        // 不在设置里堆表单（用户明确要求 MD3 那种形式）。
                        val monthPrefix = java.time.LocalDate.now().let {
                            "${it.year}-${it.monthValue.toString().padStart(2, '0')}"
                        }
                        val signedCount = cn.spw.kugou.lite.sidecar.WelfareApi
                            .signedDays(AuthStore.userId.orEmpty())
                            .count { it.startsWith(monthPrefix) }
                        SaltCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { PageNav.go(KugouLiteTab.Welfare) }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text3("签到日历", color = KugouTheme.colors.text)
                                    Text3(
                                        text = "本月已打卡 $signedCount 天 · 领畅听/概念版 VIP",
                                        color = KugouTheme.colors.muted,
                                        modifier = Modifier.padding(top = 2.dp),
                                    )
                                }
                                Text3("›", color = KugouTheme.colors.faint)
                            }
                        }

            }
        }
    }
}

/** 统计卡：大数字 + 小标签（中性底色，不用 accent）。 */
@Composable
private fun StatCard(value: String, label: String, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Box(
        modifier
            .background(KugouTheme.colors.subtle, androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp, vertical = 11.dp),
    ) {
        Column {
            Text3(text = value, color = KugouTheme.colors.text)
            Text3(text = label, color = KugouTheme.colors.muted, modifier = Modifier.padding(top = 1.dp))
        }
    }
}

/** 行内单行输入框（新建歌单名；失焦/回车不额外处理，由「确定」提交）。 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun InlineField(
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.text.BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(
            color = KugouTheme.colors.text,
            fontSize = 14.sp,
        ),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(KugouTheme.colors.accent),
        modifier = modifier,
        decorationBox = { inner ->
            if (value.isEmpty()) {
                Text3(text = placeholder, color = KugouTheme.colors.faint)
            }
            inner()
        },
    )
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

/** 排行榜列表（封面网格，点卡片进统一详情页）。 */
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
                        SaltCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                            BoxWithConstraints(Modifier.fillMaxWidth()) {
                            val cols = coverGridColumns(maxWidth.value)
                            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                gridRows(list, cols).forEach { row ->
                                    Row(Modifier.fillMaxWidth()) {
                                        row.forEach { r ->
                                            CoverGridCard(
                                                coverUrl = r.coverUrl?.replace("{size}", "300"),
                                                title = r.name,
                                                subtitle = if (r.songCount > 0) "${r.songCount} 首" else "榜单",
                                                modifier = Modifier.weight(1f).padding(4.dp),
                                            ) { PageNav.openRank(r.id, r.name) }
                                        }
                                        repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                                    }
                                }
                            }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 每日推荐：今日 / 风格推荐 / 历史 三个 Tab。
 *
 * - **今日**：`/everyday/recommend`（现有 `KugouApi.dailyRecommend`）
 * - **风格推荐**：`/everyday/style/recommend?tagid=`，tagid 用实测可用的
 *   `S14`（华语流行）等；首版把「风格」做成 chips 供切换
 * - **历史**：`/everyday/history` → 按日期分组，选中某天再展示曲目表格
 */
@Composable
fun DailyPage() {
    val today = rememberAsyncState<List<Song>?>()
    val style = rememberAsyncState<List<Song>>()
    val history = rememberAsyncState<List<ExploreApi.EverydayDay>>()
    var tab by remember { mutableStateOf(0) }
    var styleTag by remember { mutableStateOf("S14") }
    var pickedDay by remember { mutableStateOf<ExploreApi.EverydayDay?>(null) }

    LaunchedEffect(Unit) { today.load { KugouApi.dailyRecommend() } }
    LaunchedEffect(styleTag) { style.load { ExploreApi.styleRecommend(styleTag) } }
    LaunchedEffect(Unit) { history.load { ExploreApi.everydayHistory() } }

    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        SlidingTabBar(
            tabs = listOf("今日", "风格推荐", "历史"),
            selected = tab,
            onSelect = { tab = it },
        )

        when (tab) {
            0 -> SongTableState(today.value, "今日暂无推荐")
            1 -> {
                // 风格 chips（实测 S14=华语流行；其余 tagid 真机可再扩）
                val styles = listOf("S14" to "华语流行", "S13" to "欧美", "S15" to "日语", "S16" to "韩语")
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    androidx.compose.foundation.layout.FlowRow {
                        styles.forEach { (id, label) ->
                            Text3(
                                text = label,
                                color = if (id == styleTag) KugouTheme.colors.text else KugouTheme.colors.muted,
                                modifier = Modifier
                                    .padding(end = 8.dp, bottom = 8.dp)
                                    .background(
                                        if (id == styleTag) KugouTheme.colors.selectedBackground else KugouTheme.colors.subtle,
                                        androidx.compose.foundation.shape.RoundedCornerShape(999.dp),
                                    )
                                    .clickable { styleTag = id }
                                    .padding(horizontal = 12.dp, vertical = 5.dp),
                            )
                        }
                    }
                }
                SongTableState(style.value, "该风格暂无推荐")
            }
            else -> {
                val days = (history.value as? Async.Success)?.value.orEmpty()
                val day = pickedDay
                if (day == null) {
                    if (days.isEmpty()) {
                        StatusBlock("暂无推荐历史")
                    } else {
                        SaltGroupTitle("推荐历史")
                        SaltCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                            days.forEach { d ->
                                CollectionRow(
                                    coverUrl = d.songs.firstOrNull()?.coverUrl(240),
                                    title = d.title,
                                    subtitle = "${d.songs.size} 首",
                                    onClick = { pickedDay = d },
                                )
                            }
                        }
                    }
                } else {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text3(
                            text = "‹ 返回日期",
                            color = KugouTheme.colors.text,
                            modifier = Modifier.clickable { pickedDay = null },
                        )
                        Text3(
                            text = day.title,
                            color = KugouTheme.colors.muted,
                            modifier = Modifier.padding(start = 12.dp),
                            maxLines = 1,
                        )
                    }
                    SongTableState(Async.Success(day.songs), "该日无曲目")
                }
            }
        }
    }
}

/** 歌曲表格状态渲染（Daily 页内部复用）。 */
@Composable
private fun SongTableState(state: Async<List<Song>?>, emptyText: String) {
    when (val s = state) {
        is Async.Idle, is Async.Loading -> StatusBlock("正在加载…")
        is Async.Failure -> StatusBlock("加载失败：${s.message}")
        is Async.Success -> {
            val list = s.value
            when {
                list == null -> StatusBlock(
                    if (KugouApi.lastNeedsLogin) "需要登录酷狗账号"
                    else "加载失败：${KugouApi.lastError ?: "未知错误"}",
                )
                list.isEmpty() -> StatusBlock(emptyText)
                else -> SaltCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        list.forEachIndexed { i, song ->
                            DataTableRow(
                                index = "%02d".format(i + 1),
                                coverUrl = song.coverUrl(120),
                                title = song.title,
                                subtitle = song.singer,
                                album = song.albumName,
                                duration = song.durationText,
                                onRowClick = { playSongsFromQueue(list, i) },
                            )
                        }
                    }
                }
            }
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
internal fun playSongsFromQueue(queue: List<Song>, index: Int, needMore: (() -> Unit)? = null) {
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
        // 登记队列并启动切歌监听（带续拉回调：队列快播完时自动拉下一批）
        TrackWatcher.updateQueue(queue, index, needMore)
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
