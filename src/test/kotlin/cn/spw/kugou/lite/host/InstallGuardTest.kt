package cn.spw.kugou.lite.host

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * N24 / BUG-32：安装入口的并发安全。
 *
 * ## 为什么测 InstallGuard 而不是 PageInjection.install
 *
 * PageInjection.install 依赖宿主 WorkshopApi（compileOnly，单测运行期不在
 * classpath），无法直接调用。审计指认的缺陷「幂等守卫无锁、installAsync 线程
 * 与 stop() 可并发」的修复方案是把「互斥 + 已安装快速返回 + 幂等卸载」抽成
 * [InstallGuard]，真实安装逻辑作为动作传入 —— 所以并发语义直接打在守卫上，
 * PageInjection 只是把它接在 install/uninstall 两端（改动可见性由全量编译保证）。
 *
 * ## 锁定的行为（对应 N06N16N17FixTest 的并发模式：CountDownLatch + 线程池）
 *
 * - 并发 install：动作体只执行一次，其余调用快速返回 true。
 * - install 与 uninstall 互斥：卸载不会插进正在进行的安装中间。
 * - 卸载后可重新安装。
 * - 安装失败（动作返回 false）不置位，下次 install 重试动作。
 */
class InstallGuardTest {

    private val pool = Executors.newFixedThreadPool(8)

    @AfterTest
    fun tearDown() {
        pool.shutdownNow()
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS), "线程池应正常退出")
    }

    @Test
    fun `并发 install 动作只执行一次且全部返回 true`() {
        val guard = InstallGuard()
        val actions = AtomicInteger(0)
        val start = CountDownLatch(1)
        val n = 8
        val results = BooleanArray(n)
        val done = CountDownLatch(n)
        repeat(n) { i ->
            pool.submit {
                start.await()
                results[i] = guard.install {
                    actions.incrementAndGet()
                    Thread.sleep(20) // 拉长窗口，让其余线程进入快速返回分支
                    true
                }
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS), "所有调用应完成")
        assertEquals(1, actions.get(), "安装动作必须只执行一次（并发重复安装即 N24 缺陷本体）")
        repeat(n) { i -> assertTrue(results[i], "每个调用都应返回 true") }
    }

    @Test
    fun `install 与 uninstall 互斥`() {
        val guard = InstallGuard()
        val inInstall = java.util.concurrent.atomic.AtomicBoolean(false)
        val overlapped = java.util.concurrent.atomic.AtomicBoolean(false)
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)

        pool.submit {
            start.await()
            guard.install {
                inInstall.set(true)
                Thread.sleep(50)
                inInstall.set(false)
                true
            }
            done.countDown()
        }
        pool.submit {
            start.await()
            // 卸载线程：若在安装动作执行期间进入，即互斥被破坏
            guard.uninstall {
                if (inInstall.get()) overlapped.set(true)
            }
            done.countDown()
        }
        start.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertFalse(overlapped.get(), "uninstall 不得在 install 动作执行期间进入（BUG-32 同源）")
    }

    @Test
    fun `卸载后可重新安装`() {
        val guard = InstallGuard()
        var count = 0
        assertTrue(guard.install { count++; true })
        guard.uninstall { }
        assertTrue(guard.install { count++; true })
        assertEquals(2, count, "卸载清位后 install 必须重新执行动作")
        // 再 install：已置位，不再执行
        assertTrue(guard.install { count++; true })
        assertEquals(2, count)
    }

    @Test
    fun `安装失败不置位且可重试`() {
        val guard = InstallGuard()
        assertFalse(guard.install { false }, "动作失败应返回 false")
        var retried = false
        assertTrue(guard.install { retried = true; true }, "失败后重试应执行动作")
        assertTrue(retried)
    }

    @Test
    fun `install 动作内回调 uninstall 不自锁（可重入）`() {
        val guard = InstallGuard()
        // 真实场景：install 动作中途失败调用 PageInjection.uninstall 回滚
        val r = guard.install {
            guard.uninstall { }
            true
        }
        assertTrue(r, "monitor 可重入，install 内嵌 uninstall 不应死锁")
    }
}
