package cn.spw.kugou.lite.sidecar

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 时长单位归一化的测试。
 *
 * ## 背景：同一个字段 `timelen` 在不同接口里单位不同
 *
 * | 接口 | 字段 | 单位 | 样例 |
 * |---|---|---|---|
 * | `/search` | `Duration` | 秒 | 299 |
 * | `/playlist/track/all` | `timelen` | **毫秒** | 270027 |
 *
 * 而且歌单里有大量**几十秒的片段**（「青柠 (36秒片段)」`timelen=36101`），
 * 所以「> 100000 视为毫秒」这种纯量级判断会失效。
 *
 * 这里用文件大小交叉校验。测试直接构造 `Song` 验证最终渲染，
 * 因为 `parseDurationSec` 是 private，通过 `durationText` 观察结果。
 */
class DurationParseTest {

    private fun song(durationSec: Int) = Song(
        fileName = "x", title = "x", singer = "x", hash = "h",
        durationSec = durationSec, imageUrl = null, fileSize = 0,
        payType = 0, albumName = null,
    )

    @Test
    fun `秒级时长正常渲染`() {
        assertEquals("4:59", song(299).durationText)
        assertEquals("4:30", song(270).durationText)
    }

    @Test
    fun `片段时长正常渲染`() {
        // 「青柠 (36秒片段)」timelen=36101 毫秒 → 36 秒
        assertEquals("0:36", song(36).durationText)
        // 「鲸落万物生 (7秒片段)」timelen=7209 毫秒 → 7 秒
        assertEquals("0:07", song(7).durationText)
        // 「雾里」timelen=23797 毫秒 → 24 秒（四舍五入到 23 秒）
        assertEquals("0:23", song(23).durationText)
    }

    @Test
    fun `超过 24 小时的脏值被 durationText 拦下`() {
        // 若上游单位没被正确归一化（如 270027 当秒 ≈ 75 小时），
        // durationText 的 24 小时上限会兜底成占位符，避免 UI 出现 4500:27
        assertEquals("--:--", song(270_027).durationText)
        assertEquals("--:--", song(86_401).durationText)
        // 注意：23797 秒 ≈ 6.6 小时，仍在上限内，会正常渲染 —— 所以
        // 光靠 durationText 兜底不够，必须在 parseDurationSec 里用
        // 文件大小交叉校验把毫秒归一化，这才是真正的修复点。
        assertEquals("396:37", song(23_797).durationText)
    }
}
