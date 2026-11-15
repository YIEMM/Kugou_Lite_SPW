package cn.spw.kugou.lite.ui

import cn.spw.kugou.lite.sidecar.KugouApi

/** 扫码轮询最多试多少次（2 秒一次 → 5 分钟）。 */
internal const val QR_POLL_ATTEMPTS = 150

/** 扫码轮询间隔。 */
internal const val QR_POLL_INTERVAL_MS = 2_000L

/**
 * 一次扫码轮询会话。
 *
 * ## 为什么需要「可取消」
 *
 * 轮询要跑最多 5 分钟。早期它**直接躺在 `ApiExec` 的线程池里**（那个池只有 3 个线程），
 * 而且离开登录页、点「刷新二维码」都不会让它停下：
 *
 * - 反复进出登录页 → 同时挂起好几个轮询线程，**其他接口全部排队**（BUG-04）；
 * - 登录成功后旧循环还会继续跑满 5 分钟。
 *
 * 现在轮询跑在**自己的 daemon 线程**上，并由本会话对象负责取消：
 * 离开页面（`onDispose`）或点刷新（`key` 变化）时 `cancel()`，循环下一轮就退出。
 *
 * `@Volatile`：`cancel()` 在 UI 线程调用，`cancelled` 在轮询线程读取。
 */
internal class QrPollSession {
    @Volatile
    var cancelled: Boolean = false
        private set

    fun cancel() {
        cancelled = true
    }
}

/**
 * 扫码轮询主循环（同步执行）。
 *
 * ## 为什么把它抽成纯函数
 *
 * 抽出来之后可以用**假的 `sleep` / `check`** 在单测里秒级验证三件事：
 * 1. `status=4`（已确认）时立即回调成功并停止；
 * 2. `cancel()` 之后**不再发起新的 check**（这是 BUG-04 的核心：可取消）；
 * 3. 次数用尽时正常退出，不会无限循环。
 *
 * 调用方负责把本函数放到独立 daemon 线程上跑，**不要放进 `ApiExec`**。
 *
 * @param key       二维码 key
 * @param session   会话；`cancelled` 为真则退出
 * @param sleep     注入点：单测传空实现即可秒级跑完
 * @param check     注入点：默认打真实接口
 * @param onText    状态文案回调（在后台线程调用，写 MutableState 是线程安全的）
 * @param onSuccess 登录成功回调（token / userId / 昵称）
 * @return 是否登录成功
 */
internal fun runQrPoll(
    key: String,
    session: QrPollSession,
    attempts: Int = QR_POLL_ATTEMPTS,
    intervalMs: Long = QR_POLL_INTERVAL_MS,
    sleep: (Long) -> Unit = { Thread.sleep(it) },
    check: (String) -> KugouApi.QrStatus? = { KugouApi.qrCheck(it) },
    onText: (String) -> Unit = {},
    onSuccess: (token: String, userId: String, nickname: String?) -> Unit = { _, _, _ -> },
): Boolean {
    if (key.isBlank()) return false
    var tried = 0
    while (tried < attempts) {
        // 先检查取消：刷新二维码/离开页面后不应再发请求
        if (session.cancelled) return false
        sleep(intervalMs)
        if (session.cancelled) return false
        tried++

        val status = check(key) ?: continue
        when (status.status) {
            0 -> {
                // 已过期就**立即停止**（N07）：早期只更新文案继续轮询，
                // 会把 150 次（5 分钟）全部打完，后台持续打接口。
                // 过期是不可恢复状态，必须让用户刷新二维码重新开始。
                onText("二维码已过期，请点下方刷新")
                return false
            }
            1 -> onText("请用酷狗 App 扫码")
            2 -> onText("已扫码，请在手机上确认")
            4 -> {
                val token = status.token
                val userId = status.userId
                if (token != null && userId != null) {
                    onSuccess(token, userId, status.nickname)
                    return true
                }
                onText("登录信息不完整，请刷新二维码重试")
            }
            else -> Unit // 未知状态：保持当前文案，继续轮询
        }
    }
    // 轮询次数用尽仍未登录：二维码此刻也已失效，提示刷新（与既有语义一致）
    onText("二维码已过期，请点下方刷新")
    return false
}
