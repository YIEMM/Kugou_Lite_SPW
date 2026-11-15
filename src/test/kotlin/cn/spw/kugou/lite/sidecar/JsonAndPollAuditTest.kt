package cn.spw.kugou.lite.sidecar

import cn.spw.kugou.lite.ui.QrPollSession
import cn.spw.kugou.lite.ui.runQrPoll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 2026-11-15 审计的回归断言（N03 深度上限 / N07 过期即停）。
 */
class JsonAndPollAuditTest {

    // ---------------------------------------------------------- N03

    @Test
    fun `N03 超深嵌套抛 Malformed 而不是 StackOverflowError`() {
        // 10 万层嵌套数组：修复前直接 StackOverflowError（Error 无法被 catch 住）
        val deep = "[".repeat(100_000) + "]".repeat(100_000)
        assertFailsWith<JsonLite.Malformed> {
            JsonLite.parse(deep)
        }
    }

    @Test
    fun `N03 正常深度仍能解析`() {
        val ok = """{"a":{"b":{"c":[1,2,{"d":"x"}]}}}""".trimIndent()
        val parsed = JsonLite.parse(ok)
        assertTrue(parsed is Map<*, *>, "正常 JSON 必须能解析")
    }

    // ---------------------------------------------------------- N07

    @Test
    fun `N07 二维码过期后立即停止轮询`() {
        var checks = 0
        var texts = mutableListOf<String>()
        val ok = runQrPoll(
            key = "KEY",
            session = QrPollSession(),
            attempts = 150,
            intervalMs = 0,
            sleep = {},
            check = {
                checks++
                KugouApi.QrStatus(status = 0, token = null, userId = null, nickname = null)
            },
            onText = { texts.add(it) },
        )
        assertFalse(ok, "过期不应返回成功")
        assertEquals(1, checks, "过期后必须立刻停止，只应查询 1 次；实际 $checks 次")
        assertTrue(texts.any { it.contains("过期") }, "应提示过期")
    }
}
