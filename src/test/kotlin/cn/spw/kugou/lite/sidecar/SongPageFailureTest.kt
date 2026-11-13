package cn.spw.kugou.lite.sidecar

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 歌单接口失败必须可被识别（BUG-03）。
 *
 * ## 问题
 *
 * `KugouApi.get()` 网络失败返回 null，而 `byGidSongPage` 把它变成
 * `SongPage(emptyList(), 0, 0)` —— 与「服务端明确返回空列表」一模一样。
 * UI 因此永远显示「该列表暂无歌曲」：网络一抖，用户会以为歌单被删了，
 * 而且**没有任何重试入口**。
 *
 * ## 契约
 *
 * `SongPage.failed`：
 * - `true`  → 请求链路失败（无响应 / HTTP 非 200 / 响应体不可解析）
 * - `false` → 请求成功，空列表就是真的没歌
 *
 * UI 据此把「暂无歌曲」换成「加载失败 + 重试」。
 */
class SongPageFailureTest {

    private var server: HttpServer? = null

    @AfterTest
    fun tearDown() {
        setBaseUrl(null)
        server?.stop(0)
        server = null
    }

    /** 起一个只响应固定状态码/响应体的本地服务，并把边车 baseUrl 指过去。 */
    private fun serve(status: Int, body: String) {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/playlist/track/all") { exchange ->
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        s.start()
        server = s
        setBaseUrl("http://127.0.0.1:${s.address.port}")
    }

    /** `SidecarManager.baseUrl` 是 private，测试用反射改；传 null 复原。 */
    private fun setBaseUrl(value: String?) {
        val field = SidecarManager::class.java.getDeclaredField("baseUrl").apply { isAccessible = true }
        field.set(SidecarManager, value)
    }

    // ------------------------------------------------------------------

    @Test
    fun `HTTP 500 时 failed 为 true`() {
        serve(500, """{"status":0,"error_code":500}""")

        val page = KugouApi.playlistSongPage("collection_3_1_2_0")

        assertEquals(0, page.songs.size)
        assertTrue(page.failed, "HTTP 500 必须标记为失败，否则 UI 会显示「该列表暂无歌曲」")
    }

    @Test
    fun `响应体不可解析时 failed 为 true`() {
        serve(200, """{"status":0}""")

        val page = KugouApi.playlistSongPage("collection_3_1_2_0")

        assertEquals(0, page.songs.size)
        assertTrue(page.failed, "拿不到 data.info/data.songs 就是失败，不是空列表")
    }

    @Test
    fun `成功但为空列表时 failed 为 false`() {
        serve(200, """{"status":1,"data":{"info":[],"count":0}}""")

        val page = KugouApi.playlistSongPage("collection_3_1_2_0")

        assertEquals(0, page.songs.size)
        assertFalse(page.failed, "请求成功且服务端说没有歌 —— 这是真空列表，不能报失败")
    }
}
