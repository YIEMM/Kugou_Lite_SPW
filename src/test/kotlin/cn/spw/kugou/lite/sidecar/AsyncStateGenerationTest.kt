package cn.spw.kugou.lite.sidecar

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 异步状态必须丢弃「过期请求」的结果（BUG-13）。
 *
 * ## 问题
 *
 * `MutableAsyncState.load` 过去没有任何请求代号：谁后完成谁写回。
 * 于是用户手快切歌单 / 切 Tab / 改搜索词时，**先发后到**的旧请求会把新结果覆盖掉，
 * 界面上出现「我点的是 A，显示的是 A 之前那个 B 的内容」。
 *
 * 这一处修好，详情页、广场分类、搜索建议、FM 档位同时受益
 * （它们的过期回调污染都是同一个根因）。
 *
 * ## 契约
 *
 * - 每次 `load` 领一个自增代号，只有**代号仍是最新**的结果才允许写回；
 * - `reset()` 让所有在飞请求作废（切关键词/切分类时用）。
 */
class AsyncStateGenerationTest {

    private fun awaitValue(state: MutableAsyncState<String>, predicate: (Async<String>) -> Boolean) {
        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline) {
            if (predicate(state.value)) return
            Thread.sleep(10)
        }
        throw AssertionError("等待状态超时：${state.value}")
    }

    @Test
    fun `旧请求晚到不得覆盖新结果`() {
        val state = MutableAsyncState<String>()
        val oldStarted = CountDownLatch(1)
        val releaseOld = CountDownLatch(1)

        // A：先发，卡住
        state.load {
            oldStarted.countDown()
            releaseOld.await(5, TimeUnit.SECONDS)
            "OLD"
        }
        assertTrue(oldStarted.await(2, TimeUnit.SECONDS))

        // B：后发，先完成
        state.load { "NEW" }
        awaitValue(state) { it is Async.Success && it.value == "NEW" }

        // A 这时才回来 —— 必须被丢弃
        releaseOld.countDown()
        Thread.sleep(300)
        assertEquals(
            "NEW",
            (state.value as? Async.Success)?.value,
            "过期请求覆盖了新结果：界面会显示上一个歌单/上一个关键词的内容",
        )
    }

    @Test
    fun `reset 之后在飞请求不得写回`() {
        val state = MutableAsyncState<String>()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)

        state.load {
            started.countDown()
            release.await(5, TimeUnit.SECONDS)
            "STALE"
        }
        assertTrue(started.await(2, TimeUnit.SECONDS))

        state.reset()
        release.countDown()
        Thread.sleep(300)

        assertEquals(Async.Idle, state.value, "reset 之后仍在飞的请求不能把旧数据写回来")
    }

    @Test
    fun `最新请求仍能正常写回`() {
        val state = MutableAsyncState<String>()
        state.load { "ONLY" }
        awaitValue(state) { it is Async.Success }
        assertEquals("ONLY", (state.value as Async.Success).value)
    }

    @Test
    fun `失败结果同样受代号保护`() {
        val state = MutableAsyncState<String>()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)

        state.load {
            started.countDown()
            release.await(5, TimeUnit.SECONDS)
            error("旧请求失败")
        }
        assertTrue(started.await(2, TimeUnit.SECONDS))

        state.load { "NEW" }
        awaitValue(state) { it is Async.Success && it.value == "NEW" }

        release.countDown()
        Thread.sleep(300)
        assertTrue(state.value is Async.Success, "旧请求的 Failure 也不能覆盖新的 Success")
    }
}
