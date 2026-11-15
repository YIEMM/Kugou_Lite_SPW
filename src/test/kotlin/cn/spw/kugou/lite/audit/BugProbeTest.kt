package cn.spw.kugou.lite.audit

import cn.spw.kugou.lite.host.TrackWatcher
import cn.spw.kugou.lite.sidecar.AudioCache
import cn.spw.kugou.lite.sidecar.Id3CoverWriter
import cn.spw.kugou.lite.sidecar.JsonLite
import cn.spw.kugou.lite.sidecar.KugouApi
import cn.spw.kugou.lite.sidecar.Async
import cn.spw.kugou.lite.sidecar.MutableAsyncState
import cn.spw.kugou.lite.sidecar.SidecarManager
import cn.spw.kugou.lite.sidecar.Song
import cn.spw.kugou.lite.sidecar.SquareApi
import cn.spw.kugou.lite.ui.CoverLoader
import cn.spw.kugou.lite.ui.FollowStore
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 临时缺陷探针：只用于本次分析，断言的是**当前（有缺陷）的行为**，
 * 目的是把代码走查结论变成可复现证据。分析结束后删除。
 */
class BugProbeTest {

    private fun song(hash: String = "H", albumAudioId: String = ""): Song = Song(
        fileName = "x",
        title = "x",
        singer = "",
        hash = hash,
        durationSec = 0,
        imageUrl = null,
        fileSize = 0,
        payType = 0,
        albumName = null,
        albumAudioId = albumAudioId,
    )

    // ---------------------------------------------------------------- probe 1

    @Test
    fun `probe01 FLAC 缓存必须被识别（BUG-07 已修，转为回归断言）`() {
        val base = Files.createTempDirectory("kugou-audit-cache").toFile()
        AudioCache.configure(base)
        // 新命名规则：<净化后的 key>-<请求音质>.<容器扩展名>
        val cached = File(File(base, "audio-cache"), "H-128.flac")
        cached.parentFile.mkdirs()
        // N05 修复后 AudioCache 会校验文件头，占位文件必须带合法 FLAC 魔数
        cached.writeBytes("fLaC".toByteArray(Charsets.ISO_8859_1) + ByteArray(16 * 1024))
        assertTrue(cached.isFile)

        val hit = AudioCache.localFileOrNull(song("H"))
        assertNotNull(hit, "FLAC 缓存必须命中，否则无损每次都重新下载")
        assertTrue(hit.name.endsWith(".flac"), "实际命中：${hit.name}")
    }

    // ---------------------------------------------------------------- probe 2

    @Test
    fun `probe02 自己歌单短曲目 timelen(ms) 被当成秒`() {
        val m = KugouApi::class.java
            .getDeclaredMethod("parseOwnListSong", Any::class.java)
            .apply { isAccessible = true }

        @Suppress("UNCHECKED_CAST")
        fun parse(timelen: Int): Song {
            val s = m.invoke(
                KugouApi,
                mapOf(
                    "name" to "歌手 - 短歌.mp3",
                    "hash" to "H",
                    "timelen" to timelen,
                ),
            ) as Song?
            assertNotNull(s)
            return s
        }

        // 90 秒的曲目：timelen=90000ms。实际被当成 90000 秒。
        val short = parse(90_000)
        assertEquals(90_000, short.durationSec)
        assertEquals("--:--", short.durationText)

        // 7 秒片段：7209ms -> 被当成 7209 秒，界面显示 120:09
        assertEquals(7_209, parse(7_209).durationSec)
        assertEquals("120:09", parse(7_209).durationText)

        // 阈值边界：100000 恰好落进「按秒」分支
        assertEquals(100_000, parse(100_000).durationSec)
        // 100001 才按毫秒换算
        assertEquals(100, parse(100_001).durationSec)
    }

    // ---------------------------------------------------------------- probe 3

    @Test
    fun `probe03 playCountText 不受默认 Locale 影响（已修，转为回归断言）`() {
        val old = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            // 已修（N19）：统一 Locale.ROOT，德语环境也必须用点号
            assertEquals("1.5万", SquareApi.playCountText(15_000))
        } finally {
            Locale.setDefault(old)
        }
        assertEquals("1.5万", SquareApi.playCountText(15_000))
    }

    // ---------------------------------------------------------------- probe 4

    @Test
    fun `probe04 JSON 尾随垃圾被静默接受`() {
        val v = JsonLite.parse("""{"status":1} this-is-not-json""")
        assertEquals(1, JsonLite.asInt(JsonLite.dig(v, "status")))
    }

    @Test
    fun `probe05 超过 2^53 的整数 id 精度丢失`() {
        val v = JsonLite.parse("""{"fileid":9007199254740993}""")
        // 2^53+1 被 Double 掉成 2^53
        assertEquals(9007199254740992L, JsonLite.asLong(JsonLite.dig(v, "fileid")))
    }

    // ---------------------------------------------------------------- probe 6

    @Test
    fun `probe06 ID3v2_4 的 syncsafe 帧长被当大端读，APIC 检测不到`() {
        // 构造一个合法的 ID3v2.4 标签：一帧 APIC，body 200 字节。
        val body = ByteArray(200) { 0x41 }
        val bodySize = body.size
        val frame = ByteArray(10 + bodySize)
        "APIC".toByteArray(Charsets.ISO_8859_1).copyInto(frame, 0)
        // v2.4 帧长 = syncsafe：200 -> 00 00 01 48
        frame[4] = 0
        frame[5] = 0
        frame[6] = ((bodySize shr 7) and 0x7F).toByte()
        frame[7] = (bodySize and 0x7F).toByte()
        body.copyInto(frame, 10)

        val tagSize = frame.size // syncsafe 编码 210
        val header = ByteArray(10)
        header[0] = 'I'.code.toByte(); header[1] = 'D'.code.toByte(); header[2] = '3'.code.toByte()
        header[3] = 4; header[4] = 0; header[5] = 0
        header[6] = ((tagSize shr 21) and 0x7F).toByte()
        header[7] = ((tagSize shr 14) and 0x7F).toByte()
        header[8] = ((tagSize shr 7) and 0x7F).toByte()
        header[9] = (tagSize and 0x7F).toByte()

        val f = Files.createTempFile("kugou-audit-id3", ".mp3").toFile()
        try {
            f.writeBytes(header + frame)
            // 期望：true（帧确实存在）；实际：帧长按大端读成 328 > 标签长度，直接判定没有 APIC
            assertFalse(Id3CoverWriter.hasApicFrame(f), "v2.4 APIC 未被识别")
        } finally {
            f.delete()
        }
    }

    // ---------------------------------------------------------------- probe 7

    @Test
    fun `probe07 SingerName 里的 and 符号被当成多歌手分隔符`() {
        val json = JsonLite.parse(
            """{"FileHash":"H","OriSongName":"The Sound of Silence","SingerName":"Simon & Garfunkel","Duration":180}""",
        )
        val s = KugouApi.parseSongForTest(json)
        assertNotNull(s)
        // 实际被拆成两位不可点歌手
        assertEquals(listOf("Simon", "Garfunkel"), s.artists.map { it.name })
        assertEquals("Simon & Garfunkel", s.singer)
    }

    // ---------------------------------------------------------------- probe 8

    @Test
    fun `probe08 旧请求不得覆盖新结果（BUG-13 已修，转为回归断言）`() {
        val state = MutableAsyncState<String>()
        val oldStarted = CountDownLatch(1)
        val releaseOld = CountDownLatch(1)

        state.load {
            oldStarted.countDown()
            releaseOld.await(5, TimeUnit.SECONDS)
            "OLD"
        }
        assertTrue(oldStarted.await(2, TimeUnit.SECONDS))

        // 第二次请求（例如用户切到另一个歌单）先完成
        state.load { "NEW" }
        awaitValue(state) { it is Async.Success && it.value == "NEW" }

        // 旧请求随后完成，必须被代号机制丢弃
        releaseOld.countDown()
        Thread.sleep(300)
        assertEquals("NEW", (state.value as Async.Success).value, "旧结果覆盖了新结果")
    }

    private fun awaitValue(state: MutableAsyncState<String>, predicate: (Async<String>) -> Boolean) {
        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline) {
            if (predicate(state.value)) return
            Thread.sleep(10)
        }
        throw AssertionError("等待状态超时：${state.value}")
    }

    @Test
    fun `probe09 同一封面并发加载时第二个订阅者永远收不到回调`() {
        val png = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
        )
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/cover.png") { exchange ->
            // 故意拖慢，确保第二次 load 在第一次完成前进入
            Thread.sleep(400)
            exchange.sendResponseHeaders(200, png.size.toLong())
            exchange.responseBody.use { it.write(png) }
        }
        server.start()
        val base = Files.createTempDirectory("kugou-audit-cover").toFile()
        CoverLoader.configure(base)
        val url = "http://127.0.0.1:${server.address.port}/cover.png"

        val first = CountDownLatch(1)
        val second = CountDownLatch(1)
        val callbacks = AtomicInteger(0)
        try {
            CoverLoader.load(url) { callbacks.incrementAndGet(); first.countDown() }
            CoverLoader.load(url) { callbacks.incrementAndGet(); second.countDown() }

            assertTrue(first.await(3, TimeUnit.SECONDS), "首个加载应完成")
            // 期望两个调用者都拿到结果；实际 inFlight 去重直接 return，第二个回调永不触发
            assertFalse(second.await(2, TimeUnit.SECONDS), "第二个订阅者没有收到任何回调")
            assertEquals(1, callbacks.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `probe11 15 位整数 id 在 asString 里变成科学计数法`() {
        val v = JsonLite.parse("""{"album_audio_id":1234567890123456}""")
        // 期望原样数字串；实际 numberToPlainString 的阈值写成 < 1e15
        assertEquals("1.234567890123456E15", JsonLite.asString(JsonLite.dig(v, "album_audio_id")))
    }

    @Test
    fun `probe12 本地取关后服务端 user_status 仍为 1，重进页面又显示已关注`() {
        val base = Files.createTempDirectory("kugou-audit-follow").toFile()
        FollowStore.configure(base)
        // 用户取关成功：本地已删除
        FollowStore.setFollowing("artist-1", false)
        // 重进页面：服务端状态仍是 1（裁决 R3：user_status 不回落）
        assertTrue(FollowStore.isFollowing("artist-1", serverStatus = 1))
    }

    @Test
    fun `probe13 TrackWatcher 停止后无法再次启动`() {
        TrackWatcher.start()
        Thread.sleep(100)
        TrackWatcher.stop()
        // 期望 start 能重启监听；实际单线程 executor 已被 shutdownNow，execute 直接抛
        assertFailsWith<RejectedExecutionException> {
            TrackWatcher.start()
        }
    }

    @Test
    fun `probe14 歌单接口失败必须可识别（BUG-03 已修，转为回归断言）`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/playlist/track/all") { exchange ->
            val body = "{\"status\":0,\"error_code\":500}".toByteArray()
            exchange.sendResponseHeaders(500, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()

        val field = SidecarManager::class.java.getDeclaredField("baseUrl").apply { isAccessible = true }
        field.set(SidecarManager, "http://127.0.0.1:${server.address.port}")
        try {
            val page = KugouApi.playlistSongPage("collection_3_1_2_0")
            assertEquals(0, page.songs.size)
            // 期望：失败必须带 failed 标记，UI 才能显示「加载失败」而不是「暂无歌曲」
            assertTrue(page.failed, "HTTP 500 必须标记 failed")
            assertNotNull(KugouApi.lastError)
        } finally {
            field.set(SidecarManager, null)
            server.stop(0)
        }
    }

    @Test
    fun `probe10 ExploreApi 无取流键的条目仍进入列表`() {
        val json = JsonLite.parse(
            """{"data":{"song_list":[{"songname":"无键歌曲","author_name":"A","time_length":120}]}}""",
        )
        val songs = cn.spw.kugou.lite.sidecar.ExploreApi.parseStyleRecommend(json)
        assertEquals(1, songs.size)
        assertFalse(songs[0].canStream)
    }
}
