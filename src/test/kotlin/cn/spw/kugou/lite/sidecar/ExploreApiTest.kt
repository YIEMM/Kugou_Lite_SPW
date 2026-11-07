package cn.spw.kugou.lite.sidecar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [ExploreApi] 纯解析测试 —— 样例 JSON 逐字来自 2026-11-07 实测
 * （规格附录字段矩阵）。分组用相对时间戳，任何时区跑都成立。
 */
class ExploreApiTest {

    // ------------------------------------------------------------ 播放历史

    @Test
    fun `播放历史解析_按ot降序分日期组`() {
        // 以「今天正午」为锚点：任何时刻跑都稳定落在同一天，不会因凌晨跨天 flake
        val zone = java.time.ZoneId.systemDefault()
        val anchor = java.time.LocalDate.now(zone).atTime(12, 0).atZone(zone).toEpochSecond()
        val today = anchor
        val todayEarlier = anchor - 3600          // 今天 11:00
        val yesterday = anchor - 86_400           // 昨天 12:00
        val root = JsonLite.parse(
            """{"status":1,"data":{"has_more":1,"songs":[
                {"ot":$todayEarlier,"info":{"name":"分かっちゃいないね","singername":"花譜","hash":"H3","cover":"http://x/{size}/3.jpg","timelen":208000,"mixsongid":3,"album_id":300}},
                {"ot":$yesterday,"info":{"name":"夜に駆ける","singername":"YOASOBI","hash":"H2","cover":"http://x/{size}/2.jpg","timelen":251000,"mixsongid":2,"album_id":200}},
                {"ot":$today,"info":{"name":"魔法使いと振り子時計","singername":"しゃろう","hash":"H1","cover":"http://x/{size}/1.jpg","timelen":281000,"mixsongid":63968379,"album_id":100}}]}}""",
        )
        val days = ExploreApi.parseHistory(root)
        assertEquals(2, days.size)                       // 今天 + 昨天
        assertEquals(2, days[0].songs.size)              // 今天两条
        assertEquals("魔法使いと振り子時計", days[0].songs[0].title)   // 组内 ot 降序（输入是乱序）
        assertEquals("分かっちゃいないね", days[0].songs[1].title)
        assertEquals("夜に駆ける", days[1].songs[0].title)            // 昨天一组
        assertEquals("H1", days[0].songs[0].hash)
        assertEquals(281, days[0].songs[0].durationSec)  // timelen ms → 秒
        assertEquals("63968379", days[0].songs[0].mixSongId)
        assertEquals("今天", days[0].label)
        assertEquals("昨天", days[1].label)
    }

    @Test
    fun `播放历史解析_hasmore与null回空`() {
        val root = JsonLite.parse("""{"status":1,"data":{"has_more":0,"songs":[]}}""")
        assertTrue(ExploreApi.parseHistory(root).isEmpty())
        assertTrue(ExploreApi.parseHistory(null).isEmpty())
    }

    // ------------------------------------------------------------ 歌手详情/专辑/关注

    @Test
    fun `歌手详情解析_含数字概览与user_status`() {
        val root = JsonLite.parse(
            """{"status":1,"data":{"author_name":"初音ミク","author_id":"83837",
                "sizable_avatar":"http://singerimg.kugou.com/uploadpic/softhead/{size}/x.jpg",
                "album_count":10186,"song_count":300,"mv_count":1844,"fansnums":2809093,
                "user_status":0,"intro":"虚拟歌手"}}""",
        )
        val d = ExploreApi.parseArtistDetail(root)!!
        assertEquals("初音ミク", d.name)
        assertEquals("83837", d.id)
        assertTrue(d.avatarUrl!!.contains("{size}"))
        assertEquals(10186, d.albumCount)
        assertEquals(2809093L, d.fansNums)
        assertEquals(0, d.userStatus)
    }

    @Test
    fun `歌手详情解析_null回null`() {
        assertEquals(null, ExploreApi.parseArtistDetail(null))
    }

    @Test
    fun `歌手专辑解析_复用AlbumBrief`() {
        val root = JsonLite.parse(
            """{"total":10186,"error_code":0,"data":[
                {"album_name":"Blue","author_name":"Hana、初音ミク",
                 "sizable_cover":"http://imge.kugou.com/stdmusic/{size}/x.jpg"}]}""",
        )
        val page = ExploreApi.parseArtistAlbums(root)
        assertEquals(10186, page.total)
        assertEquals("Blue", page.items[0].name)
        assertEquals("Hana、初音ミク", page.items[0].artist)
    }

    // ------------------------------------------------------------ 每日推荐历史 / 风格推荐

    @Test
    fun `每日推荐历史解析_日期与歌单`() {
        val root = JsonLite.parse(
            """{"data":{"lists":[
                {"date":"20261106","history_title":"2026.11.06 推荐歌单",
                 "song_list":[{"songname":"情一动心就痛","hash":"H4","author_name":"海生",
                               "album_name":"情一动心就痛","sizable_cover":"http://x/{size}/a.jpg",
                               "time_length":232,"album_audio_id":61682972,"mixsongid":61682972}]},
                {"date":"20261105","history_title":"2026.11.05 推荐歌单","song_list":[]}]}}""",
        )
        val days = ExploreApi.parseEverydayHistory(root)
        assertEquals(2, days.size)
        assertEquals("20261106", days[0].dateKey)
        assertEquals("2026.11.06 推荐歌单", days[0].title)
        assertEquals(1, days[0].songs.size)
        assertEquals("情一动心就痛", days[0].songs[0].title)
        assertEquals(232, days[0].songs[0].durationSec)   // time_length 秒，不除 1000
    }

    @Test
    fun `风格推荐解析_复用Song且time_length为秒`() {
        val root = JsonLite.parse(
            """{"data":{"title":"华语流行","tagids":"S14","song_list":[
                {"songname":"你听得到","hash":"D7F42691CB6134BA8AA247F0A1DBD060","author_name":"周杰伦",
                 "album_name":"叶惠美","sizable_cover":"http://x/{size}/b.jpg","time_length":254,
                 "album_audio_id":966846,"mixsongid":123}]}}""",
        )
        val songs = ExploreApi.parseStyleRecommend(root)
        assertEquals(1, songs.size)
        assertEquals("你听得到", songs[0].title)
        assertEquals("D7F42691CB6134BA8AA247F0A1DBD060", songs[0].hash)
        assertEquals(254, songs[0].durationSec)
        assertEquals("966846", songs[0].albumAudioId)
    }
}
