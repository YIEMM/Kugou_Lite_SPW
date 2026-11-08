package cn.spw.kugou.lite.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 页面内部的子页导航 —— 栈式。
 *
 * ## 为什么不走宿主导航
 *
 * 宿主的 `Navigator.navigate(ScreenRoute)` 需要构造宿主自己的 `ScreenRoute`
 * 子类，而每个子页都得有一个 route —— 那要把插件内部状态序列化进宿主路由栈，
 * 复杂度高且容易与宿主返回逻辑打架。这里用页面内状态栈：`KugouLitePage`
 * 根据 [tab]（栈顶帧）渲染不同子页，宿主返回键走 [PageState] → [backHome]。
 *
 * ## 栈式导航（v0.3 起）
 *
 * 旧实现是「单层 tab + returnTo」，无法支撑「广场 → 详情 → 歌手 → 专辑」
 * 这类嵌套流（返回会丢中间页）。现在：
 *
 * - `go(t)`：直达顶层页（Home/Search/Playlists/…），**清栈**
 * - `push(tab)`：当前帧入栈，换上带 detail 状态的新帧
 * - `pop()`：弹栈恢复上一帧（detail 状态随帧恢复，不残留）
 * - `openXxx(...)`：填帧字段后 push，老调用点签名不变
 *
 * detail 状态（title/rankId/playlistId/…）是**栈顶帧的投影属性**：
 * getter 读栈顶、setter 写栈顶，外部调用点零改动。
 */
object PageNav {

    /** 一帧 = 一层导航现场。 */
    data class Frame(
        val tab: KugouLiteTab,
        val title: String = "",
        val rankId: String = "",
        val playlistId: String = "",
        val artistId: String = "",
        val albumId: String = "",
        val sectionId: String = "",
        val detailType: String = "",
    )

    /** 导航栈：`stack[0]` 是 Home 帧之下被压住的帧，最后一个元素是当前帧之下的一层。 */
    private val backStack = mutableStateListOf<Frame>()

    /** 当前帧。 */
    private var current by mutableStateOf(Frame(KugouLiteTab.Home))

    /** 发现页配置变更计数：离开编辑面板时递增，触发发现页重新读取配置。 */
    var discoverRefresh: Int by mutableStateOf(0)
        private set

    // ------------------------------------------------------------ 栈顶投影（兼容旧调用点）

    /** 当前子页（栈顶帧的 tab）。 */
    var tab: KugouLiteTab
        get() = current.tab
        internal set(value) { current = current.copy(tab = value) }

    /** 二级页标题（如榜单名/歌单名）。 */
    var detailTitle: String
        get() = current.title
        internal set(value) { current = current.copy(title = value) }

    /** 二级页要加载的榜单 id。 */
    var detailRankId: String
        get() = current.rankId
        internal set(value) { current = current.copy(rankId = value) }

    /** 二级页要加载的歌单 id。 */
    var detailPlaylistId: String
        get() = current.playlistId
        internal set(value) { current = current.copy(playlistId = value) }

    /** 二级页的歌手 id。 */
    var artistId: String
        get() = current.artistId
        internal set(value) { current = current.copy(artistId = value) }

    /** 二级页的专辑 id。 */
    var albumId: String
        get() = current.albumId
        internal set(value) { current = current.copy(albumId = value) }

    /** 发现页区块详情的区块 id。 */
    var detailSectionId: String
        get() = current.sectionId
        internal set(value) { current = current.copy(sectionId = value) }

    /** 详情类型（playlist/rank/square/daily/discoverSong/…），任务 10 起使用。 */
    var detailType: String
        get() = current.detailType
        internal set(value) { current = current.copy(detailType = value) }

    // ------------------------------------------------------------ 栈操作

    /** 栈深度（不含当前帧）。 */
    fun stackDepth(): Int = backStack.size

    /** 直达顶层页：清栈、清 detail、换帧。 */
    fun go(target: KugouLiteTab) {
        backStack.clear()
        current = Frame(target)
    }

    /** 当前帧入栈，切换到新帧。 */
    fun push(target: KugouLiteTab) {
        backStack.add(current)
        current = Frame(target)
    }

    /** 弹栈恢复上一帧；栈空时停在 Home 不崩。@return true 表示消费了这次返回。 */
    fun pop(): Boolean {
        val from = current.tab
        val prev = backStack.removeLastOrNull()
        current = prev ?: Frame(KugouLiteTab.Home)
        // 从编辑面板回来时，让发现页重新读取（可能改了布局/排序/开关）
        if (from == KugouLiteTab.DiscoverEditor) discoverRefresh++
        return true
    }

    /**
     * 兼容旧语义的返回入口：Home 返回 false（不消费，交给宿主），
     * 顶层页回 Home，详情帧逐层 pop。
     */
    fun backHome(): Boolean {
        if (current.tab == KugouLiteTab.Home) {
            cn.spw.kugou.lite.diag.Diag.info("[nav] backHome: 已在 Home，交给宿主")
            return false
        }
        cn.spw.kugou.lite.diag.Diag.info("[nav] backHome: 当前=${current.tab} 栈深=${backStack.size}")
        if (backStack.isEmpty()) {
            val wasEditor = current.tab == KugouLiteTab.DiscoverEditor
            current = Frame(KugouLiteTab.Home)
            if (wasEditor) discoverRefresh++
            return true
        }
        return pop()
    }

    // ------------------------------------------------------------ open 系列（填帧后 push）

    /** 打开榜单详情。 */
    fun openRank(rankId: String, title: String) {
        pushDetail(Frame(KugouLiteTab.SongList, title = title, rankId = rankId, detailType = "rank"))
    }

    /** 打开歌单详情。 */
    fun openPlaylist(playlistId: String, title: String) {
        pushDetail(Frame(KugouLiteTab.SongList, title = title, playlistId = playlistId, detailType = "playlist"))
    }

    /** 打开歌手页。 */
    fun openArtist(artistId: String, title: String) {
        pushDetail(Frame(KugouLiteTab.Artist, title = title, artistId = artistId, detailType = "artist"))
    }

    /** 打开专辑页。 */
    fun openAlbum(albumId: String, title: String) {
        pushDetail(Frame(KugouLiteTab.Album, title = title, albumId = albumId, detailType = "album"))
    }

    /** 打开发现页某个区块的详情页（完整列表）。 */
    fun openDiscoverDetail(sectionId: String, title: String) {
        pushDetail(Frame(KugouLiteTab.DiscoverDetail, title = title, sectionId = sectionId, detailType = "discover"))
    }

    /** 打开每日推荐（顶层页）。 */
    fun openDaily() {
        go(KugouLiteTab.Daily)
    }

    /** 打开歌单广场。 */
    fun openSquare() {
        go(KugouLiteTab.Square)
    }

    /** 打开播放历史。 */
    fun openHistory() {
        go(KugouLiteTab.History)
    }

    /** 打开发现页编辑面板。 */
    fun openDiscoverEditor() {
        pushDetail(Frame(KugouLiteTab.DiscoverEditor))
    }

    /** push 并保留来源帧（与 push 等价；帧字段由调用方填好）。 */
    private fun pushDetail(frame: Frame) {
        push(frame.tab)
        // push 已把旧帧入栈；把新帧的字段填上（detail 状态属于新帧）
        current = frame
    }

    fun reset() {
        backStack.clear()
        current = Frame(KugouLiteTab.Home)
    }
}

/** 插件内部子页。 */
enum class KugouLiteTab {
    /** 主页（发现页）。 */
    Home,

    /** 搜索页。 */
    Search,

    /** 我的歌单列表。 */
    Playlists,

    /** 榜单列表。 */
    Ranks,

    /** 每日推荐。 */
    Daily,

    /** 歌曲列表（歌单详情 / 榜单详情共用一个页面）。 */
    SongList,

    /** 扫码登录。 */
    Login,

    /** 歌手页。 */
    Artist,

    /** 专辑页。 */
    Album,

    /** 设置。 */
    Settings,

    /** 歌单广场。 */
    Square,

    /** 播放历史。 */
    History,

    /** 发现页区块详情（完整列表）。 */
    DiscoverDetail,

    /** 发现页编辑面板。 */
    DiscoverEditor,
}
