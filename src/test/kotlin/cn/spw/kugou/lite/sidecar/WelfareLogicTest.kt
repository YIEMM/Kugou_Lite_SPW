package cn.spw.kugou.lite.sidecar

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 签到日期与错误码的纯逻辑测试（参考 md3Music 的签到实现）。
 *
 * md3 的签到要点：
 * - 用服务器时间算 receive_day（本地时间可能跨时区错一天）
 * - 按账号隔离本地已签记录
 * - 错误码：131001 今日已领取 / 20030 已升级过 / 20028 需验证或风控
 */
class WelfareLogicTest {

    @Test
    fun `服务器时间戳转成 YYYY-MM-DD`() {
        // 2026-11-08 06:00 UTC = 14:00 +08
        val ts = 1794117600L
        assertEquals("2026-11-08", receiveDayFrom(ts, zoneOffsetHours = 8))
    }

    @Test
    fun `UTC 时区下同一时间戳仍是同一天`() {
        val ts = 1794117600L
        assertEquals("2026-11-08", receiveDayFrom(ts, zoneOffsetHours = 0))
    }

    @Test
    fun `本地已签记录的键带 userid`() {
        assertEquals("welfare_signed_1072630322", signedDaysKey("1072630322"))
    }

    @Test
    fun `错误码映射`() {
        assertEquals("今日已签到，无需重复领取", welfareMessage(131001))
        assertEquals("已升级过概念版，无需重复领取", welfareMessage(20030))
        assertEquals("今日已签到，无需重复领取", welfareMessage(20033))
        assertEquals("登录已过期，请重新扫码登录", welfareMessage(20018))
        assertEquals("领取失败：领取次数已达上限", welfareMessage(20034))
        assertEquals("酷狗拒绝领取：账号可能不符合资格，或该功能已停用", welfareMessage(20028))
        // 20002 实测为上游风控/网关拒绝，给明确提示而不是「未知错误」
        assertEquals("酷狗拒绝领取（账号风控或功能已停用），可稍后重试或前往手机端领取", welfareMessage(20002))
    }

    @Test
    fun `未知错误码带原始码`() {
        assertEquals("领取失败（错误码：99999）", welfareMessage(99999))
    }
}
