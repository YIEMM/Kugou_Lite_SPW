package cn.spw.kugou.lite.ui

import cn.spw.kugou.lite.sidecar.Async
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 搜索页懒加载判定的回归测试。
 *
 * ## 这个测试防的是什么
 *
 * 整分支审查（2026-11-08）发现 Critical：搜索页四个 Tab 用
 * `if (state.value == null)` 判断「是否需要发请求」，而
 * `MutableAsyncState.value` 的静态类型是**非空** `Async<T>`
 * （初值 `Async.Idle`），所以该条件恒为 false —— 回车后永远停在
 * 「正在搜索…」，一个请求都不发。
 *
 * 编译能过、135 个既有测试全绿都发现不了它，因为这是「判空写错」
 * 而非「逻辑缺失」。所以把判定抽成纯函数并在这里钉死。
 */
class SearchLoadPolicyTest {

    @Test
    fun `Idle 状态必须发起请求`() {
        assertTrue(shouldLoadForTab(Async.Idle))
    }

    @Test
    fun `Loading 中不重复发起`() {
        assertFalse(shouldLoadForTab(Async.Loading))
    }

    @Test
    fun `Success 已有数据不再发起`() {
        assertFalse(shouldLoadForTab(Async.Success("data")))
    }

    @Test
    fun `Failure 允许重试`() {
        assertTrue(shouldLoadForTab(Async.Failure("boom")))
    }
}
