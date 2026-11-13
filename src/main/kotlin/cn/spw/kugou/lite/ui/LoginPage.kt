package cn.spw.kugou.lite.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import cn.spw.kugou.lite.diag.Diag
import cn.spw.kugou.lite.sidecar.ApiExec
import cn.spw.kugou.lite.sidecar.AuthStore
import cn.spw.kugou.lite.sidecar.KugouApi
import cn.spw.kugou.lite.sidecar.SidecarManager

/**
 * 扫码登录页。
 *
 * ## 为什么需要它
 *
 * 酷狗 token 会过期（实测几小时后 `/user/playlist` 返回 `error_code 20017`）。
 * 早期只有登录态持久化、没有重新登录入口，token 一过期，歌单页就永远显示
 * 「暂无歌单」，用户无从恢复。
 *
 * ## 流程
 *
 * ```
 * 1. GET /login/qr/key        → 拿 qrcode key + base64 PNG
 * 2. 展示二维码
 * 3. 每 2 秒 GET /login/qr/check?key=xxx
 *    status: 0 过期 / 1 等待扫码 / 2 待确认 / 4 成功
 * 4. status==4 → 保存 token/userid/nickname 到 AuthStore
 * ```
 *
 * ## 轮询放在哪里
 *
 * 用 [LaunchedEffect] 起一个循环，页面卸载时自动取消。
 * **不要**在组合体里直接调用 API（阻塞渲染线程会让宿主卡死）。
 */
@Composable
fun LoginPage() {
    var qrBase64 by remember { mutableStateOf<String?>(null) }
    var statusText by remember { mutableStateOf("正在获取二维码…") }
    var key by remember { mutableStateOf("") }
    // 每次点「刷新二维码」递增，触发 LaunchedEffect 重启
    var generation by remember { mutableStateOf(0) }

    // 1) 拉二维码
    LaunchedEffect(generation) {
        statusText = "正在获取二维码…"
        qrBase64 = null
        ApiExec.run({ r ->
            r.onSuccess { pair ->
                if (pair != null) {
                    key = pair.first
                    qrBase64 = pair.second
                    statusText = "请用酷狗 App 扫码"
                } else {
                    statusText = "获取二维码失败：${KugouApi.lastError ?: "未知错误"}"
                }
            }
            r.onFailure { statusText = "获取二维码失败：${it.message}" }
        }) {
            // 边车是懒启动的（发现页首次请求时才拉起）。登录页可能是宿主启动后
            // 访问的第一个页面（token 过期场景），必须自负边车启动，
            // 否则 qrKey 永远撞上「边车未就绪」，二维码永远出不来。
            SidecarManager.ensureStarted() ?: return@run null
            val result = KugouApi.qrKey() ?: return@run null
            result.key to result.imageBase64.substringAfter("base64,", "")
        }
    }

    // 2) 轮询扫码状态
    //
    // ## 为什么不再用 ApiExec（BUG-04）
    //
    // `ApiExec` 的线程池**只有 3 个线程**，而轮询要跑最多 5 分钟（2s × 150）。
    // 早期把整个循环丢进那个池，且离开页面/刷新二维码都不会停：
    // - 反复进出登录页 → 同时挂起多个轮询，其它接口全部排队；
    // - 登录成功后旧循环还继续占着线程跑满 5 分钟。
    //
    // 现在跑在**自己的 daemon 线程**上，并由 [QrPollSession] 取消：
    // 页面卸载（`onDispose`）或 key 变化（刷新二维码）时 `cancel()`，
    // 循环在下一轮开头就退出，不再发请求。
    val pollSession = remember { mutableStateOf<QrPollSession?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            // 真机验证观测点：BUG-04 的核心证据就是这一行——离开页面轮询必须停
            pollSession.value?.let {
                Diag.info("[login] 离开登录页：取消扫码轮询")
                it.cancel()
            }
        }
    }
    LaunchedEffect(key) {
        if (key.isBlank()) return@LaunchedEffect
        // 刷新二维码 = 上一轮作废，避免两个循环同时在跑
        pollSession.value?.cancel()
        val session = QrPollSession()
        pollSession.value = session
        Diag.info("[login] 扫码轮询启动（独立 daemon 线程，不占 ApiExec 池）")
        Thread({
            runQrPoll(
                key = key,
                session = session,
                onText = { statusText = it },
                onSuccess = { token, userId, nickname ->
                    AuthStore.save(token, userId, nickname)
                    statusText = "登录成功：${nickname ?: userId}"
                    Diag.info("[login] 登录成功 user=${nickname ?: userId}")
                },
            )
        }, "kugou-login-poll").apply { isDaemon = true }.start()
    }

    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        SaltGroupTitle("扫码登录")
        SaltCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text3(text = statusText, color = KugouTheme.colors.text)

                if (AuthStore.isLoggedIn) {
                    Text3(
                        text = "当前已登录：${AuthStore.nickname ?: AuthStore.userId}",
                        color = KugouTheme.colors.text,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }

                Text3(
                    text = "登录后可取流播放、读取我的歌单。token 保存在本地插件目录。",
                    color = KugouTheme.colors.muted,
                    modifier = Modifier.padding(top = 8.dp),
                )

                val b64 = qrBase64
                if (b64 != null) {
                    val bitmap = remember(b64) { decodePng(b64) }
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = "酷狗登录二维码",
                            modifier = Modifier.padding(top = 12.dp).size(220.dp),
                        )
                    }
                }

                Text3(
                    text = "刷新二维码",
                    color = KugouTheme.colors.text,
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .clickable { generation++ },
                )
            }
        }
    }
}

/**
 * 把 base64 PNG 解码成 [ImageBitmap]。
 *
 * 用 AWT 的 `ImageIO` 而不是 Compose 自带的解码器：宿主是桌面 JVM，
 * `java.desktop` 模块一定存在（宿主自己就是 Swing/AWT 应用），
 * 而 Compose 的 `decodeToImageBitmap` 在桌面端依赖 skiko，插件不便直接引。
 */
private fun decodePng(b64: String): ImageBitmap? = try {
    val bytes = java.util.Base64.getDecoder().decode(b64)
    val img = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(bytes))
    img?.toComposeImageBitmap()
} catch (e: Throwable) {
    Diag.warn("[login] 二维码解码失败：${e.message}")
    null
}
