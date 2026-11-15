package cn.spw.kugou.lite.sidecar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import cn.spw.kugou.lite.diag.Diag
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 后台任务执行与 Compose 状态封装。
 *
 * ## 为什么必须后台 + 状态回传
 *
 * 所有 [KugouApi] 方法都是阻塞 HTTP 调用。**绝对不能在 composable 里直接调**：
 *
 * 1. 组合发生在渲染线程，网络阻塞会让宿主整个 UI 卡死；
 * 2. 宿主把插件组合期的异常视为 `FATAL_FAILURE`，直接终止进程；
 * 3. 即使不崩，也拿不到「加载中」的中间态，体验很差。
 *
 * 所以统一走：后台线程执行 → 结果写进 Compose `MutableState` → 重组时读。
 *
 * ## 线程池
 *
 * 固定 3 个线程。不用 `Dispatchers.IO`，因为插件不依赖 kotlinx-coroutines
 * （宿主自带但我们不便断言版本），用 JDK 自带的 [Executors] 更稳。
 */
object ApiExec {

    private const val TAG = "exec"

    private val counter = AtomicInteger(0)

    /**
     * 线程池。**可重建**（N18）。
     *
     * 早期是 `private val`：`shutdown()` 之后没有任何重建路径，于是同一进程内
     * 「工坊里禁用 → 再启用」之后，每次提交都抛 RejectedExecutionException
     * 被静默吞掉，而调用方已把状态置成 Loading —— 所有页面永久「正在加载…」。
     */
    @Volatile
    private var pool: java.util.concurrent.ExecutorService? = null

    private val poolLock = Any()

    /** 取线程池；已关闭则重建。 */
    private fun ensurePool(): java.util.concurrent.ExecutorService {
        pool?.let { if (!it.isShutdown) return it }
        synchronized(poolLock) {
            pool?.let { if (!it.isShutdown) return it }
            val created = Executors.newFixedThreadPool(3) { runnable ->
                Thread(runnable, "kugou-lite-api-${counter.incrementAndGet()}").apply {
                    // 守护线程：宿主退出时不阻止 JVM 关闭
                    isDaemon = true
                    // 低优先级，别和 UI 渲染抢 CPU
                    priority = Thread.MIN_PRIORITY + 2
                }
            }
            pool = created
            Diag.info(TAG, "线程池已就绪")
            return created
        }
    }

    /**
     * 提交后台任务，完成后在主线程之外更新 [onResult]。
     *
     * [onResult] 由调用方在 Compose 里用 `state.value = ...` 的写法接收 ——
     * Compose 的 `MutableState` 本身是线程安全的，可以跨线程写。
     */
    fun <T> run(onResult: (Result<T>) -> Unit, block: () -> T) {
        try {
            ensurePool().execute {
                val result = try {
                    Result.success(block())
                } catch (e: Throwable) {
                    // 捕获 Throwable：StackOverflowError（N03）这类 Error
                    // 若漏出去会让工作线程静默死亡，页面永久「加载中」。
                    Diag.warn(TAG, "后台任务异常：${e.javaClass.simpleName}: ${e.message}")
                    Result.failure(e)
                }
                try {
                    onResult(result)
                } catch (e: Throwable) {
                    Diag.warn(TAG, "回传结果异常：${e.javaClass.simpleName}: ${e.message}")
                }
            }
        } catch (e: Throwable) {
            // 提交失败**必须回传**（N18）：否则调用方停在 Loading 永不恢复。
            Diag.warn(TAG, "提交任务失败：${e.javaClass.simpleName}: ${e.message}")
            runCatching { onResult(Result.failure(e)) }
        }
    }

    /**
     * 关闭线程池。**之后仍可再次 run()** —— [ensurePool] 会自动重建（N18）。
     *
     * 插件在工坊里被「禁用 → 再启用」时走的就是这条路径，
     * 若不可重建则所有页面永久「正在加载…」。
     */
    fun shutdown() {
        synchronized(poolLock) {
            runCatching { pool?.shutdownNow() }
            pool = null
        }
    }
}

/** 异步数据的三种状态。 */
sealed interface Async<out T> {
    data object Idle : Async<Nothing>
    data object Loading : Async<Nothing>
    data class Success<T>(val value: T) : Async<T>
    data class Failure(val message: String) : Async<Nothing>
}

/**
 * 手工管理的异步状态容器。
 *
 * 用法：
 * ```
 * val state = rememberAsyncState<List<Song>>()
 * // 在事件回调里（不要写在组合体内）：
 * state.load { KugouApi.search(keyword)?.songs ?: emptyList() }
 * // 重组时读：
 * when (val s = state.value) { ... }
 * ```
 *
 * [load] 内部切后台线程，结果写回 `MutableState`（线程安全），
 * 触发重组时自动刷新 UI。
 */
@Composable
fun <T> rememberAsyncState(): MutableAsyncState<T> =
    remember { MutableAsyncState<T>() }

class MutableAsyncState<T> {
    var value: Async<T> by mutableStateOf<Async<T>>(Async.Idle)
        internal set

    /**
     * 请求代号。
     *
     * ## 为什么必须有（BUG-13）
     *
     * 早期实现是「谁后完成谁写回」：用户手快切歌单 / 切 Tab / 改搜索词时，
     * **先发后到**的旧请求会把新结果覆盖掉，界面上出现「点的是 A，显示的是 B」。
     *
     * 现在每次 [load] 领一个自增代号，回调写回前比对代号 ——
     * 只有仍是最新的请求才能落地。`reset()` 也自增，让在飞的请求全部作废。
     *
     * 用 `AtomicInteger` 而不是普通 `var`：回调跑在 `ApiExec` 的后台线程上，
     * 与 UI 线程的 [load] 存在真实的数据竞争。
     */
    private val generation = java.util.concurrent.atomic.AtomicInteger(0)

    fun load(block: () -> T) {
        // 先自增再置 Loading：这样先前那次请求的回调即使马上到达也会被判为过期。
        val mine = generation.incrementAndGet()
        value = Async.Loading
        ApiExec.run({ result ->
            if (generation.get() == mine) {
                value = result.fold(
                    onSuccess = { Async.Success(it) },
                    onFailure = { Async.Failure(it.message ?: "请求失败") },
                )
            }
        }, block)
    }

    fun reset() {
        // 让在飞的请求全部作废，避免 reset 之后又被旧结果填满（BUG-53 的同类问题）。
        generation.incrementAndGet()
        value = Async.Idle
    }
}

/** 边车随页面卸载而停止。 */
@Composable
fun SidecarLifecycle() {
    DisposableEffect(Unit) {
        onDispose {
            Diag.info("sidecar", "页面卸载，停止边车")
            SidecarManager.stop()
        }
    }
}
