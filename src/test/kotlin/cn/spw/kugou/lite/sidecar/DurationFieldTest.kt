package cn.spw.kugou.lite.sidecar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 时长字段兼容性回归测试。
 *
 * ## 这个测试防的是什么
 *
 * 真机验证发现：每日推荐页所有曲目时长显示 `--:--`。
 * 根因是 `parseSong` 的时长解析只找 `Duration/duration/timelen/timeLength`，
 * 而 `/everyday/recommend` 用的是 **`time_length`**（带下划线）。
 * 字段名没命中 → durationSec=0 → 界面显示 `--:--`。
 *
 * 四个接口的时长字段风格各不相同，这里逐一钉住：
 *
 * | 接口 | 字段 | 单位 |
 * |---|---|---|
 * | `/search` | `timeLength` / `Duration` | 秒 |
 * | `/playlist/track/all` | `timelen` | 毫秒 |
 * | `/everyday/recommend` | **`time_length`** | 秒 |
 * | `/user/history` 的 `info` | `timelen` | 毫秒 |
 */
class DurationFieldTest {

    /** 用 parseSongForTest（KugouApi 已开 internal 包装）解析单条记录。 */
    private fun parse(json: String): Song? =
        KugouApi.parseSongForTest(JsonLite.parse(json))

    @Test
    fun `每日推荐 time_length 能解析出时长`() {
        val song = parse(
            """{"songname":"NIGHT DANCER","hash":"ABC","author_name":"imase",
                "time_length":194,"album_id":"1","mixsongid":2}""",
        )
        assertEquals(194, song?.durationSec, "time_length 是秒，应直接采用")
    }

    @Test
    fun `歌单 timelen 毫秒会被换算`() {
        val song = parse(
            """{"name":"歌手 - 歌名","hash":"ABC","timelen":270027,"album_id":"1"}""",
        )
        // 无文件大小时按量级判：270027 > 100000 → /1000 = 270 秒
        assertEquals(270, song?.durationSec)
    }

    @Test
    fun `搜索 timeLength 秒直接采用`() {
        val song = parse(
            """{"OriSongName":"发如雪","FileHash":"ABC","timeLength":299,"album_id":"1"}""",
        )
        assertEquals(299, song?.durationSec)
    }

    @Test
    fun `时长缺失时为0且不崩`() {
        val song = parse("""{"songname":"无时长","hash":"ABC","album_id":"1"}""")
        assertEquals(0, song?.durationSec)
    }

    @Test
    fun `秒级短片段不会被误当毫秒`() {
        // 36 秒片段，timelen=36101（毫秒）→ 36 秒；若判据错会得到 36101 秒
        val song = parse(
            """{"name":"青柠 (36秒片段)","hash":"ABC","timelen":36101,"size":1444000}""",
        )
        assertEquals(36, song?.durationSec)
    }
}
