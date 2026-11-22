package cn.spw.kugou.lite.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * 设置页自定义输入的校验纯函数（用户需求：设置内容更加自定义——快捷档之外支持任意输入）。
 */
class SettingsInputValidationTest {

    /** 容量：合法整数在 1~100 之间，返回归一化值。 */
    @Test
    fun cacheLimitValidRange() {
        assertEquals(5, parseCacheLimitGb("5"))
        assertEquals(1, parseCacheLimitGb("1"))
        assertEquals(100, parseCacheLimitGb("100"))
    }

    /** 容量：越界自动钳制到 1~100。 */
    @Test
    fun cacheLimitClampsOutOfRange() {
        assertEquals(1, parseCacheLimitGb("0"))
        assertEquals(1, parseCacheLimitGb("-3"))
        assertEquals(100, parseCacheLimitGb("500"))
    }

    /** 容量：非法输入返回 null（UI 提示错误而非误应用）。 */
    @Test
    fun cacheLimitRejectsGarbage() {
        assertNull(parseCacheLimitGb("abc"))
        assertNull(parseCacheLimitGb(""))
        assertNull(parseCacheLimitGb("1.5"))
    }

    /** 限流：合法整数 1~20。 */
    @Test
    fun coverRateValidRange() {
        assertEquals(7, parseCoverRate("7"))
        assertEquals(1, parseCoverRate("1"))
        assertEquals(20, parseCoverRate("20"))
    }

    /** 限流：越界钳制到 1~20。 */
    @Test
    fun coverRateClampsOutOfRange() {
        assertEquals(1, parseCoverRate("0"))
        assertEquals(20, parseCoverRate("99"))
    }

    /** 限流：非法输入返回 null。 */
    @Test
    fun coverRateRejectsGarbage() {
        assertNull(parseCoverRate("six"))
        assertNull(parseCoverRate(""))
    }
}
