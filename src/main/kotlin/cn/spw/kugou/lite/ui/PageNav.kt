package cn.spw.kugou.lite.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 页面内部的子页导航。
 *
 * ## 为什么不走宿主导航
 *
 * 宿主的 `Navigator.navigate(ScreenRoute)` 需要构造宿主自己的 `ScreenRoute`
 * 子类，而每个子页都得有一个 route —— 那要把插件内部状态序列化进宿主路由栈，
 * 复杂度高且容易与宿主返回逻辑打架。
 *
 * 这里改用**页面内状态切换**：`KugouLitePage` 内部根据 [tab] 渲染不同子页，
 * 宿主返回键统一回到主页（已在 [PageState] 里处理）。
 *
 * ## 两级导航
 *
 * ```
 * Home
 *  ├ Search            （搜索）
 *  ├ Playlists         （歌单列表）──> SongList(歌单详情)
 *  ├ Ranks             （榜单列表）──> SongList(榜单歌曲)
 *  └ Daily             （每日推荐，直接是歌曲列表）
 * ```
 *
 * [SongList] 带 [title] 参数，返回时回到来源页（[returnTo]）。
 */
object PageNav {

    /** 当前子页。 */
    var tab: KugouLiteTab by mutableStateOf(KugouLiteTab.Home)
        internal set

    /** 二级页标题（如榜单名）。 */
    var detailTitle: String by mutableStateOf("")
        internal set

    /** 二级页要加载的榜单 id（空表示不是榜单详情）。 */
    var detailRankId: String by mutableStateOf("")
        internal set

    /** 二级页的专辑 id（空表示不是专辑页）。 */
    var albumId: String by mutableStateOf("")
        internal set

    /** 二级页的歌手 id（空表示不是歌手页）。 */
    var artistId: String by mutableStateOf("")
        internal set

    /** 二级页要加载的歌单 id（空表示不是歌单详情）。 */
    var detailPlaylistId: String by mutableStateOf("")
        internal set

    /** 二级页返回时回到哪一页。 */
    private var returnTo: KugouLiteTab = KugouLiteTab.Home

    fun go(target: KugouLiteTab) {
        if (target != KugouLiteTab.SongList) returnTo = tab
        tab = target
    }

    /** 打开榜单详情。 */
    fun openRank(rankId: String, title: String) {
        detailRankId = rankId
        detailPlaylistId = ""
        detailTitle = title
        returnTo = KugouLiteTab.Ranks
        tab = KugouLiteTab.SongList
    }

    /** 打开歌单详情。 */
    fun openPlaylist(playlistId: String, title: String) {
        detailPlaylistId = playlistId
        detailRankId = ""
        detailTitle = title
        returnTo = KugouLiteTab.Playlists
        tab = KugouLiteTab.SongList
    }

    /** 打开歌手页。 */
    fun openArtist(artistId: String, title: String) {
        this.artistId = artistId
        detailRankId = ""
        detailPlaylistId = ""
        detailTitle = title
        returnTo = tab
        tab = KugouLiteTab.Artist
    }

    /** 打开专辑页。 */
    fun openAlbum(albumId: String, title: String) {
        this.albumId = albumId
        detailRankId = ""
        detailPlaylistId = ""
        artistId = ""
        detailTitle = title
        returnTo = tab
        tab = KugouLiteTab.Album
    }

    /**
     * 返回上一级。
     *
     * @return true 表示消费了这次返回（还有上级页面）；false 表示已在 Home。
     */
    fun backHome(): Boolean {
        when (tab) {
            KugouLiteTab.Home -> return false
            KugouLiteTab.SongList, KugouLiteTab.Artist, KugouLiteTab.Album -> {
                tab = returnTo
                clearDetail()
                return true
            }
            else -> {
                tab = KugouLiteTab.Home
                return true
            }
        }
    }

    private fun clearDetail() {
        detailTitle = ""
        detailRankId = ""
        detailPlaylistId = ""
        artistId = ""
        albumId = ""
    }

    fun reset() {
        tab = KugouLiteTab.Home
        clearDetail()
        returnTo = KugouLiteTab.Home
    }
}

/** 插件内部子页。 */
enum class KugouLiteTab {
    /** 主页（分组入口）。 */
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
}
