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

    private val pool = Executors.newFixedThreadPool(3) { runnable ->
        Thread(runnable, "kugou-lite-api-${counter.incrementAndGet()}").apply {
            // 守护线程：宿主退出时不阻止 JVM 关闭
            isDaemon = true
            // 低优先级，别和 UI 渲染抢 CPU
            priority = Thread.MIN_PRIORITY + 2
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
            pool.execute {
                val result = try {
                    Result.success(block())
                } catch (e: Exception) {
                    Diag.warn(TAG, "后台任务异常：${e.message}")
                    Result.failure(e)
                }
                try {
                    onResult(result)
                } catch (e: Exception) {
                    Diag.warn(TAG, "回传结果异常：${e.message}")
                }
            }
        } catch (e: Exception) {
            // 线程池已关闭（插件卸载中）时忽略
            Diag.warn(TAG, "提交任务失败：${e.message}")
        }
    }

    fun shutdown() {
        runCatching { pool.shutdownNow() }
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

    fun load(block: () -> T) {
        value = Async.Loading
        ApiExec.run({ result ->
            value = result.fold(
                onSuccess = { Async.Success(it) },
                onFailure = { Async.Failure(it.message ?: "请求失败") },
            )
        }, block)
    }

    fun reset() {
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
