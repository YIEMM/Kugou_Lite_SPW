package cn.spw.kugou.lite.sidecar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [DiscoverApi] 五个接口的解析测试。
 *
 * 输入全部是**真实响应片段**（`JsonLite.parse` 构造），不发网络请求 ——
 * 解析逻辑必须能在离线状态下被完整断言。
 *
 * 覆盖计划里点名的三个审查重点：
 * 1. 刷歌推荐的歌曲藏在 `data.items[].song_info` 里；
 * 2. 新歌速递的 `data` 直接是数组（不是 `data.songs`）；
 * 3. 新碟上架按地区分组（`chn/eur/jpn/kor`）。
 */
class DiscoverApiTest {

    @Test
    fun `每日推荐解析 song_list`() {
        val j = JsonLite.parse(
            """{"status":1,"data":{"song_list":[
                {"songname":"夜に駆ける","hash":"AAA","author_name":"YOASOBI","timelen":261000}]}}""",
        )
        val songs = DiscoverApi.parseSongList(j, "song_list")
        assertEquals(1, songs.size)
        assertEquals("夜に駆ける", songs[0].title)
        assertEquals("AAA", songs[0].hash)
    }

    @Test
    fun `刷歌推荐解析 items 里的 song_info`() {
        val j = JsonLite.parse(
            """{"status":1,"data":{"items":[
                {"item_type":"song","song_info":{"songname":"臙脂","hash":"BBB","author_name":"重音テト"}}]}}""",
        )
        val songs = DiscoverApi.parseHomeDiscover(j)
        assertEquals(1, songs.size)
        assertEquals("臙脂", songs[0].title)
    }

    @Test
    fun `新歌速递 data 是数组时也能解析`() {
        val j = JsonLite.parse(
            """{"status":1,"data":[
                {"audio_name":"NEON HEARTBEAT","hash":"CCC","author_name":"初音ミク"}]}""",
        )
        val songs = DiscoverApi.parseTopSong(j)
        assertEquals(1, songs.size)
        assertEquals("NEON HEARTBEAT", songs[0].title)
    }

    @Test
    fun `新碟上架合并四个地区`() {
        val j = JsonLite.parse(
            """{"status":1,"data":{
                "chn":[{"albumid":1,"albumname":"华语碟","singername":"A","imgurl":"http://x/{size}/a.jpg"}],
                "jpn":[{"albumid":2,"albumname":"日语碟","singername":"B","imgurl":"http://x/{size}/b.jpg"}]}}""",
        )
        val albums = DiscoverApi.parseTopAlbum(j)
        assertEquals(2, albums.size)
        assertEquals("华语碟", albums[0].name)
        assertTrue(albums[0].coverUrl!!.contains("{size}"))
    }

    @Test
    fun `私人 FM 解析出 cur_mark`() {
        val j = JsonLite.parse(
            """{"status":1,"data":{"cur_mark":"MK1","song_list":[
                {"songname":"随机歌","hash":"DDD"}]}}""",
        )
        val batch = DiscoverApi.parseFm(j)
        assertEquals("MK1", batch.curMark)
        assertEquals("DDD", batch.songs[0].hash)
    }

    @Test
    fun `异常响应返回空列表`() {
        assertTrue(DiscoverApi.parseSongList(null, "song_list").isEmpty())
        assertTrue(DiscoverApi.parseHomeDiscover(JsonLite.parse("""{"data":{}}""")).isEmpty())
    }
}
