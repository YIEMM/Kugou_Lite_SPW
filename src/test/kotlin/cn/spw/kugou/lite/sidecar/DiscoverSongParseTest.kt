package cn.spw.kugou.lite.sidecar

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 刷歌推荐（/home/discover）song_info 的字段解析回归（用户反馈：刷歌推荐没有歌词）。
 *
 * 该接口的 hash 字段名是 **hash_128**（不是 hash/FileHash），早期 parseSong
 * 找不到 → Song.hash 空 → 歌词 key 回退 album_audio_id → /search/lyric 404
 * → 整个区块点歌都没有歌词。取流不受影响（rustserver 支持 album_audio_id）。
 */
class DiscoverSongParseTest {

    /** 真机抓包的 song_info 形态：hash_128 + album_audio_id + mixsongid。 */
    @Test
    fun parsesHash128Field() {
        val json = """{"songname":"你有没有真的爱过我","author_name":"阿图表妹","mixsongid":"879490214","album_audio_id":879490214,"hash_128":"FE39668F68770C3FB3BE57A3E86CCC10","timelength_128":243000}"""
        val song = KugouApi.parseSongForTest(JsonLite.parse(json))!!
        assertEquals("FE39668F68770C3FB3BE57A3E86CCC10", song.hash)
        assertEquals("879490214", song.albumAudioId)
        assertEquals("你有没有真的爱过我", song.title)
    }

    /** 歌词 key：hash_128 解析后优先用 hash（而不是回退 album_audio_id）。 */
    @Test
    fun lyricKeyUsesHash128WhenParsed() {
        assertEquals("FE39668F", KugouApi.lyricKeyFor("FE39668F", "").take(8))
    }

    /** 兼容：其它接口给的还是标准 hash 字段。 */
    @Test
    fun standardHashStillParsed() {
        val json = """{"songname":"x","hash":"ABC123","album_audio_id":42}"""
        val song = KugouApi.parseSongForTest(JsonLite.parse(json))!!
        assertEquals("ABC123", song.hash)
    }
}
