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
 * 这里改用**页面内状态切换**：`KugouLitePage` 内部根据 [KugouLiteTab] 渲染
 * 不同子页，宿主返回键统一回到主页（已在 [PageState] 里处理）。
 *
 * ## 为什么用 object + mutableStateOf
 *
 * Compose 的 `MutableState` 跨组合稳定，且能被 [SaltScreen] 的重组正确响应。
 * 用 object 便于 `PageInjection` 在卸载时重置状态。
 */
object PageNav {

    /** 当前子页。 */
    var tab: KugouLiteTab by mutableStateOf(KugouLiteTab.Home)
        internal set

    fun go(target: KugouLiteTab) {
        tab = target
    }

    /** 回到主页。返回 true 表示消费了这次返回事件。 */
    fun backHome(): Boolean {
        if (tab == KugouLiteTab.Home) return false
        tab = KugouLiteTab.Home
        return true
    }

    fun reset() {
        tab = KugouLiteTab.Home
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
}
