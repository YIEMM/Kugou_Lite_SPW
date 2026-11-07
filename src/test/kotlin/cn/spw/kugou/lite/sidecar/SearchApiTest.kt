package cn.spw.kugou.lite.sidecar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [SearchApi] 纯解析测试 —— 样例 JSON 逐字来自 2026-11-07 实测
 * （规格附录字段矩阵，边车 39823 实抓）。网络层经 [KugouApi.getRaw]，
 * 登录态失效返回 null 的传播以 `parse(null) → 空结果` 形态覆盖。
 */
class SearchApiTest {

    // ------------------------------------------------------------ 热搜

    @Test
    fun `热搜解析_拍平多榜单keywords并保序`() {
        val root = JsonLite.parse(
            """{"status":1,"data":{"list":[
                {"name":"热搜榜","keywords":[{"keyword":"烂奇迹","reason":"x"},{"keyword":"时过境迁"}]},
                {"name":"新歌榜","keywords":[{"keyword":"向云端"}]}]}}""",
        )
        assertEquals(listOf("烂奇迹", "时过境迁", "向云端"), SearchApi.parseHotWords(root))
    }

    @Test
    fun `热搜解析_null与缺容器回空`() {
        assertTrue(SearchApi.parseHotWords(null).isEmpty())
        assertTrue(SearchApi.parseHotWords(JsonLite.parse("""{"status":1}""")).isEmpty())
    }

    // ------------------------------------------------------------ 输入建议

    @Test
    fun `建议解析_拍平RecordDatas并去重保序`() {
        val root = JsonLite.parse(
            """{"status":1,"data":[{"RecordDatas":[{"HintInfo":"向云端"},{"HintInfo":"向云端 黄绮珊"},{"HintInfo":"向云端"}]}]}""",
        )
        assertEquals(listOf("向云端", "向云端 黄绮珊"), SearchApi.parseSuggest(root))
    }

    @Test
    fun `建议解析_null回空`() {
        assertTrue(SearchApi.parseSuggest(null).isEmpty())
    }

    // ------------------------------------------------------------ 歌单搜索

    @Test
    fun `歌单搜索解析_剥em标签_gid作id_type为收藏`() {
        val root = JsonLite.parse(
            """{"status":1,"data":{"total":480,"info":[
                {"specialid":1406408,"playcount":1019616,"songcount":133,
                 "specialname":"<em>初音</em>未来歌曲精选｜世界第一的公主殿下",
                 "imgurl":"http://imge.kugou.com/soft/collection/{size}/20191121/x.jpg",
                 "gid":"collection_3_1085322500_150_0","nickname":"ACG小编-蘑菇娘"}]}}""",
        )
        val page = SearchApi.parseSpecial(root)
        assertEquals(480, page.total)
        assertEquals(1, page.items.size)
        val pl = page.items[0]
        assertEquals("初音未来歌曲精选｜世界第一的公主殿下", pl.name) // em 已剥离
        assertEquals("collection_3_1085322500_150_0", pl.id)          // gid 作详情页 id
        assertEquals(133, pl.songCount)
        assertEquals(1019616L, pl.playCount)
        assertEquals(1, pl.type)     // 搜索到的都是别人的歌单 → type=1 收藏
        assertTrue(pl.coverUrl!!.contains("{size}"))
    }

    @Test
    fun `歌单搜索解析_null回空页`() {
        assertEquals(0, SearchApi.parseSpecial(null).total)
        assertTrue(SearchApi.parseSpecial(null).items.isEmpty())
    }

    // ------------------------------------------------------------ 专辑搜索

    @Test
    fun `专辑搜索解析_复用AlbumBrief保留size占位`() {
        val root = JsonLite.parse(
            """{"status":1,"data":{"total":500,"info":[
                {"albumid":213578107,"albumname":"ミラーミラー (feat. Hatsune Miku, Kasane Teto)",
                 "singername":"初音ミク、重音テト、TAK","imgurl":"http://imge.kugou.com/stdmusic/{size}/20261022/x.jpg",
                 "songcount":2}]}}""",
        )
        val page = SearchApi.parseAlbums(root)
        assertEquals(500, page.total)
        val al = page.items[0]
        assertEquals("213578107", al.id)
        assertEquals("ミラーミラー (feat. Hatsune Miku, Kasane Teto)", al.name)
        assertEquals("初音ミク、重音テト、TAK", al.artist)
        assertTrue(al.coverUrl!!.contains("{size}"))
    }

    @Test
    fun `专辑搜索解析_null回空页`() {
        assertTrue(SearchApi.parseAlbums(null).items.isEmpty())
    }

    // ------------------------------------------------------------ 歌手搜索

    @Test
    fun `歌手搜索解析_顶层数组_id转字符串`() {
        val root = JsonLite.parse(
            """{"status":1,"data":[
                {"singername":"初音ミク","singerid":83837},
                {"singername":"子如初音乐","singerid":7480935}]}""",
        )
        val artists = SearchApi.parseArtists(root)
        assertEquals(2, artists.size)
        assertEquals("初音ミク", artists[0].name)
        assertEquals("83837", artists[0].id)
        assertEquals(null, artists[0].avatarUrl) // 实测无头像字段，网格用占位
    }

    @Test
    fun `歌手搜索解析_null回空`() {
        assertTrue(SearchApi.parseArtists(null).isEmpty())
    }
}
