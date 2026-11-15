package cn.spw.kugou.lite.sidecar

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 私人 FM 歌曲必须解析出 `songId`（续流游标的另一半）。
 *
 * ## 真机发现（2026-11-15）
 *
 * 加了游标日志后发现 `songId=` 一直是空的。打印 FM 原始响应字段才看清：
 * `songid` 确实存在，但值是**数字** `318.0`，不是字符串。
 *
 * `JsonLite.asString` 对 Number 走 `numberToPlainString`（318.0 → "318"），
 * 所以能正确转出；但如果只认 String 就会漏掉。
 *
 * ## 为什么 songId 重要
 *
 * `/personal/fm` 的续流游标是「上一首的 hash + songid」
 * （对照 MD3Music `getPersonalFm(hash, songId, action)`）。缺 songId 时
 * 上游只能按 hash 粗粒度续流，命中率下降。
 */
class FmSongIdTest {

    private fun songJson(songid: Any?): String {
        val idPart = if (songid == null) "" else ",\"songid\":$songid"
        return """{"hash":"ABC","songname":"测试","author_name":"某人","time_length":200$idPart}""".trimIndent()
    }

    @Test
    fun `数字型 songid 转成整数串`() {
        val song = KugouApi.parseSongForTest(JsonLite.parse(songJson("318.0")))
        assertEquals("318", song?.songId, "318.0 应转成 \"318\"，不能留小数点")
    }

    @Test
    fun `字符串型 songid 原样保留`() {
        val song = KugouApi.parseSongForTest(JsonLite.parse(songJson("\"987654\"")))
        assertEquals("987654", song?.songId)
    }

    @Test
    fun `缺失 songid 时为空串`() {
        val song = KugouApi.parseSongForTest(JsonLite.parse(songJson(null)))
        assertEquals("", song?.songId)
    }
}
