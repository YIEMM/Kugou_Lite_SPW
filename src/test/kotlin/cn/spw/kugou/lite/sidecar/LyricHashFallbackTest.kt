package cn.spw.kugou.lite.sidecar

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 取歌词用的 key 回退（用户反馈「加载无缓存音乐都会显示无歌词」）。
 *
 * ## 现场
 *
 * 从专辑页点歌后，缓存文件名是纯数字：
 *
 *     已缓存 386662632-128.mp3（2545 KB）
 *     元数据已更新（封面=true 歌词=false），需通知宿主重读
 *
 * 说明该曲 **hash 为空**（榜单/专辑部分接口只给 album_audio_id），
 * 而取歌词时用的是裸 `song.hash` —— 空串请求 /search/lyric 必然查不到，
 * 于是首次播放的歌统统没有歌词；等下次播放时歌词已在缓存里才正常。
 *
 * 项目里取流、收藏都用同一套回退：`hash.ifBlank { albumAudioId }`，
 * 唯独取歌词漏了。
 *
 * ## 判定
 *
 * 歌词 key 必须与取流一致：hash 优先，空白则退回 albumAudioId。
 */
class LyricHashFallbackTest {

    @Test
    fun `有 hash 时优先用 hash`() {
        assertEquals("ABCDEF", KugouApi.lyricKeyFor(hash = "ABCDEF", albumAudioId = "123"))
    }

    @Test
    fun `hash 为空时退回 albumAudioId`() {
        assertEquals("386662632", KugouApi.lyricKeyFor(hash = "", albumAudioId = "386662632"))
    }

    @Test
    fun `hash 为空白字符时也退回 albumAudioId`() {
        assertEquals("386662632", KugouApi.lyricKeyFor(hash = "   ", albumAudioId = "386662632"))
    }

    @Test
    fun `两者都没有时返回空串`() {
        assertEquals("", KugouApi.lyricKeyFor(hash = "", albumAudioId = ""))
    }
}
