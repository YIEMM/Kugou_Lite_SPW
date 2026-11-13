package cn.spw.kugou.lite.ui

import cn.spw.kugou.lite.sidecar.KugouApi
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 扫码轮询的可取消性与状态映射（BUG-04）。
 *
 * ## 背景
 *
 * 轮询循环原来跑在 `ApiExec` 的线程池里（**全池只有 3 个线程**），
 * 而且离开登录页、刷新二维码都不会停：
 *
 * - 反复进出登录页 → 同时挂起多个轮询，其他接口全部排队；
 * - 登录成功后旧循环还继续跑满 5 分钟。
 *
 * 修法：轮询挪到独立 daemon 线程，由 [QrPollSession] 取消。
 * 下面的用例用**假的 sleep/check** 把 5 分钟压成毫秒来验证这些行为。
 */
class LoginPollTest {

    private fun status(
        code: Int,
        token: String? = null,
        userId: String? = null,
        nickname: String? = null,
    ) = KugouApi.QrStatus(code, token, userId, nickname)

    @Test
    fun `确认登录后立即成功并停止`() {
        val session = QrPollSession()
        val calls = AtomicInteger(0)
        var success: Triple<String, String, String?>? = null

        val ok = runQrPoll(
            key = "K",
            session = session,
            sleep = {},
            check = {
                calls.incrementAndGet()
                status(4, token = "T", userId = "U", nickname = "N")
            },
            onSuccess = { t, u, n -> success = Triple(t, u, n) },
        )

        assertTrue(ok)
        assertEquals(Triple("T", "U", "N"), success)
        assertEquals(1, calls.get(), "成功后不应再轮询")
    }

    @Test
    fun `cancel 之后不再发起新的检查`() {
        val session = QrPollSession()
        val calls = AtomicInteger(0)

        // 第一次 check 里取消：模拟「用户离开登录页」
        val ok = runQrPoll(
            key = "K",
            session = session,
            attempts = 100,
            sleep = {},
            check = {
                calls.incrementAndGet()
                session.cancel()
                status(1)
            },
        )

        assertFalse(ok)
        assertEquals(1, calls.get(), "取消后必须立即停，否则线程池会被占满（BUG-04）")
    }

    @Test
    fun `已取消的会话不会发任何请求`() {
        val session = QrPollSession().also { it.cancel() }
        val calls = AtomicInteger(0)

        val ok = runQrPoll(
            key = "K",
            session = session,
            sleep = {},
            check = { calls.incrementAndGet(); status(1) },
        )

        assertFalse(ok)
        assertEquals(0, calls.get())
    }

    @Test
    fun `次数用尽会退出并提示过期`() {
        val session = QrPollSession()
        val calls = AtomicInteger(0)
        val texts = mutableListOf<String>()

        val ok = runQrPoll(
            key = "K",
            session = session,
            attempts = 3,
            sleep = {},
            check = { calls.incrementAndGet(); status(1) },
            onText = { texts += it },
        )

        assertFalse(ok)
        assertEquals(3, calls.get(), "不能超过上限（防无限轮询）")
        assertTrue(texts.last().contains("过期"), "用尽后要提示过期，否则用户不知道要刷新")
    }

    @Test
    fun `扫码与待确认状态只更新文案不终止`() {
        val session = QrPollSession()
        val seen = mutableListOf<Int>()
        val texts = mutableListOf<String>()
        var i = 0

        runQrPoll(
            key = "K",
            session = session,
            attempts = 3,
            sleep = {},
            check = {
                val code = when (i++) {
                    0 -> 1
                    1 -> 2
                    else -> 0
                }
                seen += code
                status(code)
            },
            onText = { texts += it },
        )

        assertEquals(listOf(1, 2, 0), seen)
        assertTrue(texts.any { it.contains("扫码") })
        assertTrue(texts.any { it.contains("确认") })
        assertTrue(texts.any { it.contains("过期") })
    }

    @Test
    fun `缺 token 的成功状态不当作登录成功`() {
        val session = QrPollSession()
        var success = false

        val ok = runQrPoll(
            key = "K",
            session = session,
            attempts = 1,
            sleep = {},
            check = { status(4, token = null, userId = "U") },
            onSuccess = { _, _, _ -> success = true },
        )

        assertFalse(ok)
        assertFalse(success, "token 为空时保存登录态会产生一个打不了接口的假登录")
    }

    @Test
    fun `空 key 直接返回`() {
        val calls = AtomicInteger(0)
        val ok = runQrPoll(
            key = "",
            session = QrPollSession(),
            sleep = {},
            check = { calls.incrementAndGet(); status(1) },
        )
        assertFalse(ok)
        assertEquals(0, calls.get())
    }
}
