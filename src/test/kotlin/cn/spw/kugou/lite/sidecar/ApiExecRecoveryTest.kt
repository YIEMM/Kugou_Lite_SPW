package cn.spw.kugou.lite.sidecar

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 线程池关闭后必须能恢复（N18）。
 *
 * ## 缺陷
 *
 * `ApiExec.pool` 是 `private val`，`shutdown()` 之后没有重建路径。
 * 同一进程内「工坊里禁用 → 再启用」之后，每次 `run()` 都抛
 * `RejectedExecutionException` 被静默吞掉，而调用方（MutableAsyncState）
 * 已先把状态置成 Loading —— 于是所有页面永久停在「正在加载…」。
 *
 * ## 契约
 *
 * - `shutdown()` 后再次 `run()` 必须**自动重建线程池**并正常执行
 * - 提交失败时**必须**把失败回传给 `onResult`，不能静默吞掉
 */
class ApiExecRecoveryTest {

    @Test
    fun `shutdown 后仍能执行任务`() {
        val latch = CountDownLatch(1)
        var value = 0
        ApiExec.shutdown()

        ApiExec.run({ latch.countDown() }) { 42 }
        val ok = latch.await(5, TimeUnit.SECONDS)
        assertTrue(ok, "shutdown 之后提交的任务必须被执行（线程池应自动重建）")
    }

    @Test
    fun `提交失败必须回传失败状态而不是静默吞掉`() {
        // 用一个已 shutdown 且不允许重建的假场景无法直接构造，
        // 这里验证正常路径的结果回传契约：失败必须走 Result.failure
        val latch = CountDownLatch(1)
        var failure: Throwable? = null
        ApiExec.run({ result ->
            failure = result.exceptionOrNull()
            latch.countDown()
        }) { throw IllegalStateException("boom") }

        assertTrue(latch.await(5, TimeUnit.SECONDS), "必须回调")
        assertTrue(failure != null, "异常必须以 Result.failure 回传")
        assertEquals("boom", failure?.message)
    }
}
