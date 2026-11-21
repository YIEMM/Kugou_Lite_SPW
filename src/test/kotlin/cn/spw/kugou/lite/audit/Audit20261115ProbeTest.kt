package cn.spw.kugou.lite.audit

import cn.spw.kugou.lite.sidecar.ApiExec
import cn.spw.kugou.lite.sidecar.AudioCache
import cn.spw.kugou.lite.sidecar.ExploreApi
import cn.spw.kugou.lite.sidecar.JsonLite
import cn.spw.kugou.lite.sidecar.KrcLyrics
import cn.spw.kugou.lite.sidecar.KugouApi
import cn.spw.kugou.lite.sidecar.MutableAsyncState
import cn.spw.kugou.lite.sidecar.SearchApi
import cn.spw.kugou.lite.sidecar.SearchHit
import cn.spw.kugou.lite.sidecar.Settings
import cn.spw.kugou.lite.sidecar.SidecarManager
import cn.spw.kugou.lite.sidecar.Song
import cn.spw.kugou.lite.sidecar.cacheName
import cn.spw.kugou.lite.sidecar.preferSameSinger
import cn.spw.kugou.lite.sidecar.sanitizeCacheKey
import cn.spw.kugou.lite.ui.QrPollSession
import cn.spw.kugou.lite.ui.runQrPoll
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Locale
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
 * 2026-11-15 缺陷扫描：临时探针。
 *
 * **这些断言描述的是「当前有缺陷的行为」**，不是期望行为 ——
 * 通过 = 缺陷仍在，失败 = 已被修好（此时应改写成回归断言）。
 * 仅用于本次分析取证，不属于产品测试集。
 */
class Audit20261115ProbeTest {

    /** 本机 %TEMP% 不可写（sandbox），临时目录一律建在项目 build/ 下。 */
    private fun newTempDir(tag: String): File {
        val base = File("build/audit-tmp").apply { mkdirs() }
        return File(base, "$tag-${System.nanoTime()}").apply { mkdirs() }
    }

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

    // ------------------------------------------------------------ P01 Locale

    /**
     * P01：LRC 时间戳用默认 Locale 格式化。
     *
     * `KrcLyrics.formatTimeInner` 写的是 `"%02d:%02d.%02d".format(...)`，
     * Kotlin 的 `String.format` 取 `Locale.getDefault()`。在 ar / fa / bn
     * 这类「本地数字形态」的 Locale 下，`%d` 输出的是本地数字
     * （ar 为 U+0660..U+0669），写出的 `.lrc` 时间戳不再匹配
     * `[mm:ss.xx]`，宿主直接判为「无歌词」。
     */
    @Test
    fun `P01 LRC 时间戳不受默认 Locale 影响（已修：改为回归断言）`() {
        val krc = "[1000,2000]<0,500,0>la<500,500,0>la"
        val old = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"))
            val lrc = KrcLyrics.toEnhancedLrc(krc)
            val line = lrc.lineSequence().last { it.isNotBlank() }

            // 已修：formatTimeInner 显式用 Locale.ROOT，时间戳恒为 ASCII。
            val asciiTimestamp = Regex("""^\[\d{2}:\d{2}\.\d{2}]""")
            assertTrue(
                asciiTimestamp.containsMatchIn(line),
                "阿拉伯语 Locale 下时间戳也必须是 ASCII：$line",
            )
            val hasLocalizedDigit = line.any { it in Char(0x0660)..Char(0x0669) }
            assertFalse(hasLocalizedDigit, "不应再出现本地数字：$line")
        } finally {
            Locale.setDefault(old)
        }
    }

    // ------------------------------------------------------ P02 时长启发式

    /**
     * P02：`parseDurationSec` 的「字节数交叉校验」会吃掉低码率长音频的时长。
     *
     * 判据是 `raw > fileSize / 4000` 就当成毫秒。一个 300 秒、400 KB
     * （约 10 kbps，语音/评书常见）的曲目算出 `300 > 100` → 走毫秒分支
     * → `300 / 1000 = 0` 秒 → 界面显示 `--:--`。
     */
    @Test
    fun `P02 低码率长音频的秒数被误判成毫秒后归零`() {
        val obj = JsonLite.parse(
            """{"FileHash":"H","OriSongName":"低码率","SingerName":"A","time_length":300,"size":400000}""",
        )
        val s = KugouApi.parseSongForTest(obj)
        assertNotNull(s)
        // 已修（N02）：低码率长音频不再被误判成毫秒后归零
        assertEquals(300, s.durationSec)
        assertEquals("5:00", s.durationText)
    }

    // ------------------------------------------------- P03 JSON 无深度限制

    /**
     * P03：`JsonLite` 递归下降没有深度上限。
     *
     * 上游返回畸形/被截断的嵌套数组（或恶意构造的响应）时抛的是
     * `StackOverflowError`（Error，不是 Exception）——
     * `ApiExec.run` 只 catch Exception，`KugouApi.get/post` 也只 catch
     * IOException/SecurityException，于是工作线程直接死掉、状态永远停在
     * Loading，日志里一行都没有。
     */
    @Test
    fun `P03 深层嵌套 JSON 抛可捕获的异常（已修，转为回归断言）`() {
        val deep = "[".repeat(100_000) + "]".repeat(100_000)
        // 已修（N03）：有深度上限，抛 Malformed（Exception）而不是 StackOverflowError
        assertFailsWith<JsonLite.Malformed> {
            JsonLite.parse(deep)
        }
    }

    // ------------------------------------------------- P04 缓存名未净化 ext

    /**
     * P04：`cacheName` 只净化了音质，**没有净化扩展名**。
     *
     * 扩展名来自上游 `StreamInfo.extName`（`/song/url` 的 `extName` 字段）。
     * BUG-36 的修复覆盖了 `key`（hash/albumAudioId）与音质，
     * 但 `"$stem-$q.$e"` 里的 `$e` 是原样拼接：上游若返回 `../x` 或带
     * 路径分隔符的值，`File(cacheDir, name)` 就会写出缓存目录。
     */
    @Test
    fun `P04 缓存文件名里的扩展名可路径穿越`() {
        val root = newTempDir("kugou-audit-ext")
        val cacheDir = File(root, "audio-cache").apply { mkdirs() }
        // 上游 extName 原样拼进文件名；带分隔符就等于把文件写出缓存目录
        val name = cacheName("abc", "128", "../../../evil")
        assertFalse(name.contains('/'), "已修（N04）：扩展名必须被净化：$name")

        val target = File(cacheDir, name).canonicalFile
        assertEquals(
            cacheDir.canonicalFile, target.parentFile,
            "已修（N04）：文件必须留在缓存目录里：${target.absolutePath}",
        )
    }

    /** P04b：净化函数本身会把不同 key 折叠成同一文件名（不同歌共用一个缓存文件）。 */
    @Test
    fun `P04b 净化后的缓存键会碰撞`() {
        assertEquals(sanitizeCacheKey("A/B"), sanitizeCacheKey("A_B"))
        assertEquals(cacheName(sanitizeCacheKey("A/B"), "128", "mp3"), cacheName(sanitizeCacheKey("A_B"), "128", "mp3"))
    }

    // --------------------------------------------- P05 短歌词反复重写

    /**
     * P05：`writeLrcSidecarLocked` 用 `lrc.length() > 100` 作为「已存在」判据。
     *
     * 少于 100 字节的歌词（纯音乐、短句、日文短歌）永远被认为「不存在」：
     * 每次播放都重写 .lrc / .krc。同一条判据也出现在 `hasFreshLyrics`，
     * 于是缓存命中路径每次都要再取一次歌词接口。
     */
    @Test
    fun `P05 短歌词每次写入都被当作不存在而重写`() {
        val dir = newTempDir("kugou-audit-lrc")
        val audio = File(dir, "H-128.mp3").apply { writeBytes(ByteArray(16)) }
        val short = """
            [by:KugouLite/v5]
            [offset:0]
            [00:01.00]la
            [00:02.00]la
        """.trimIndent()

        assertTrue(AudioCache.writeLrcSidecar(audio, short))
        val lrc = File(dir, "H-128.lrc")
        val first = lrc.lastModified()
        Thread.sleep(40)
        assertTrue(AudioCache.writeLrcSidecar(audio, short))
        val second = lrc.lastModified()

        // N06 已修复：内容没变（同版本戳）就不重写；短歌词也算新鲜。
        // 这里从「锁定缺陷」的探针断言翻转为「锁定修复」的回归断言。
        assertEquals(second, first, "短歌词（同版本戳）不应被重复写入")
        assertTrue(AudioCache.hasFreshLyrics(audio), "带版本戳的短歌词应当算新鲜")
    }

    // ------------------------------------------ P06 损坏缓存被当成有效缓存

    /**
     * P06：`localFileOrNull` 只检查 `isFile && length() > 0`。
     *
     * 下载/写 ID3 中途失败（`rewriteWithFrames` 会先 `setLength(0)` 再整段写，
     * 不是原子写）留下的半截文件、或用户手工放进来的垃圾文件，
     * 都会被当成「已缓存」直接交给宿主播放 → 无声/报错，而且
     * `ensureLocal` 也认为命中，永远不会重新下载。
     */
    @Test
    fun `P06 截断或垃圾缓存文件被当成可播放缓存`() {
        val base = newTempDir("kugou-audit-broken")
        AudioCache.configure(base)
        val junk = File(File(base, "audio-cache"), cacheName("H", Settings.requestQuality(), "mp3"))
        junk.parentFile.mkdirs()
        junk.writeBytes(byteArrayOf(1, 2, 3)) // 3 字节，明显不是音频

        val hit = AudioCache.localFileOrNull(song("H"))
        // 已修（N05）：垃圾文件不再被当成有效缓存
        assertNull(hit, "3 字节垃圾文件不能当成缓存命中")
    }

    // --------------------------------------- P07 扫码过期后继续轮询 5 分钟

    /**
     * P07：`runQrPoll` 对 `status == 0`（二维码已过期）只改文案，**不退出**。
     *
     * 审计 BUG-04 的修复把轮询挪出了 ApiExec 池并加了取消，但「过期即停」
     * 这一条漏了：过期码会继续被 check 到 150 次（5 分钟），
     * 用户看到「二维码已过期」却还在后台打接口。
     */
    @Test
    fun `P07 二维码过期后仍然把 150 次轮询全部打完`() {
        val session = QrPollSession()
        val checks = AtomicInteger(0)
        val texts = ArrayList<String>()

        val ok = runQrPoll(
            key = "expired-key",
            session = session,
            attempts = 6,
            intervalMs = 0,
            sleep = {},
            check = { key ->
                checks.incrementAndGet()
                KugouApi.QrStatus(status = 0, token = null, userId = null, nickname = null)
            },
            onText = { texts.add(it) },
        )

        assertFalse(ok)
        // 已修（N07）：过期后立刻停，只查 1 次
        assertEquals(1, checks.get(), "过期二维码必须立刻停止轮询")
        assertTrue(texts.size >= 1)
    }

    // ------------------------------------- P08 非 JSON 响应直接抛异常

    /**
     * P08：`KugouApi.get/post` 只 catch IOException / SecurityException。
     *
     * 网关返回 HTML 错误页（5xx）或截断的 JSON 时，`JsonLite.parse` 抛
     * `JsonLite.Malformed`（RuntimeException）穿透整个 API 层 ——
     * 与「失败返回 null」的契约不符。调用方若没包 runCatching（宿主扩展点、
     * 组合期回调），异常会直接进宿主线程。
     */
    @Test
    fun `P08 上游返回非 JSON 时 API 抛出异常而不是返回 null`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/search") { exchange ->
            val body = "<html><body>502 Bad Gateway</body></html>".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()

        val field = SidecarManager::class.java.getDeclaredField("baseUrl").apply { isAccessible = true }
        field.set(SidecarManager, "http://127.0.0.1:${server.address.port}")
        try {
            // 已修（N08）：解析失败返回 null，不再穿透异常
            assertNull(KugouApi.search("test"), "非 JSON 响应应返回 null")
        } finally {
            field.set(SidecarManager, null)
            server.stop(0)
        }
    }

    // --------------------------------- P09 无参数请求的 query 以 & 开头

    /**
     * P09：`get()` 无条件 `append("&token=…")`。
     *
     * `params` 为空（`/user/playlist`、`/rank/list`、`/everyday/recommend`
     * 都是空参数）且已登录时，URL 变成 `?&token=…&userid=…`。
     * 严格的上游/网关会把这当成空键参数（现有边车容忍，属潜在隐患）。
     */
    @Test
    fun `P09 空参数接口的 query 以 & 开头`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val seen = ArrayList<String>()
        server.createContext("/rank/list") { exchange ->
            seen.add(exchange.requestURI.rawQuery.orEmpty())
            val body = """{"status":1,"data":{"info":[]}}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()

        val field = SidecarManager::class.java.getDeclaredField("baseUrl").apply { isAccessible = true }
        field.set(SidecarManager, "http://127.0.0.1:${server.address.port}")
        val auth = Class.forName("cn.spw.kugou.lite.sidecar.AuthStore")
        val tokenField = auth.getDeclaredField("token").apply { isAccessible = true }
        val userField = auth.getDeclaredField("userId").apply { isAccessible = true }
        try {
            tokenField.set(auth.getField("INSTANCE").get(null), "T")
            userField.set(auth.getField("INSTANCE").get(null), "U")
            KugouApi.ranks()
            assertEquals(1, seen.size)
            assertTrue(seen[0].startsWith("&"), "实际 query：${seen[0]}")
        } finally {
            tokenField.set(auth.getField("INSTANCE").get(null), null)
            userField.set(auth.getField("INSTANCE").get(null), null)
            field.set(SidecarManager, null)
            server.stop(0)
        }
    }

    // ------------------------------- P10 线程池关闭后插件「假活」（子进程）

    /**
     * P10：`KugouLitePlugin.stop()` 调 `ApiExec.shutdown()`，而 `pool` 是 `val`、
     * 没有任何重建路径。同一 JVM 里插件被重新启用（工坊里关掉再打开、或宿主热重载）后，
     * `pool.execute` 每次都抛 `RejectedExecutionException`，被 `run` 的
     * `catch (e: Exception) { Diag.warn("提交任务失败") }` 静默吞掉 ——
     * 于是 `MutableAsyncState.load` 永远停在 `Async.Loading`，所有页面永久「加载中」。
     *
     * `shutdown()` 不可逆，所以放到**子进程**里复现，不污染本 JVM 的共享线程池。
     */
    @Test
    fun `P10 线程池关闭后请求静默失效并永久停在加载中`() {
        val java = File(System.getProperty("java.home"), "bin/java").absolutePath
        val cp = System.getProperty("java.class.path")
        val p = ProcessBuilder(java, "-cp", cp, "cn.spw.kugou.lite.audit.Audit20261115ProbeTestKt")
            .redirectErrorStream(true)
            .start()
        val out = p.inputStream.bufferedReader().use { it.readText() }
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "子进程超时：$out")
        // 已修（N18）：线程池可重建，状态不再永久 Loading、回调能执行
        assertTrue(out.contains("STATE=Success"), "应正常完成，实际：$out")
        assertTrue(out.contains("CALLBACK=true"), "回调必须执行，实际：$out")
    }

    // ------------------------------- P11 列表时长随 Locale 本地化

    /**
     * P11：`Models.Song.durationText` 用 `"%d:%02d".format(...)`，取默认 Locale。
     * 与已修的 `KrcLyrics.formatTimeInner` 是同一类问题，但发生在**曲目列表的时长列**：
     * ar-EG / fa / bn 下显示 `٤:٠٥`，lrc 之外的 UI 也脏了。
     */
    @Test
    fun `P11 曲目时长在阿拉伯语环境下渲染成本地数字`() {
        val song = ExploreApi.parseSongEntry(
            mapOf<String, Any?>("songname" to "x", "hash" to "h", "time_length" to 245),
        )
        assertNotNull(song)
        val old = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"))
            val text = song.durationText
            // 已修（N19）：统一 Locale.ROOT，恒为 ASCII
            assertFalse(
                text.any { it in Char(0x0660)..Char(0x0669) },
                "时长必须是 ASCII，实际：$text",
            )
            assertEquals("4:05", text)
        } finally {
            Locale.setDefault(old)
        }
    }

    // ------------------------------- P12 空 time_length 遮蔽 timelen

    /**
     * P12：`ExploreApi.parseSongEntry` 的时长判定是「键存在就用它」，
     * 而 `time_length` 偶发为空字符串 —— `obj["time_length"] != null` 成立、
     * `asInt("")` 得 0，于是**有效的毫秒字段 `timelen` 永远走不到**，
     * 播放历史里的歌全部显示 `--:--`。
     */
    @Test
    fun `P12 空字符串 time_length 遮蔽有效 timelen 导致时长归零`() {
        val entry = mapOf<String, Any?>(
            "songname" to "x",
            "hash" to "h",
            "time_length" to "",
            "timelen" to 281000.0,
        )
        val song = ExploreApi.parseSongEntry(entry)
        assertNotNull(song)
        // 已修（N20）：空串不再遮蔽有效 timelen
        assertEquals(281, song.durationSec, "应回退到 timelen（281000ms）")
        assertEquals("4:41", song.durationText)
    }

    // ------------------------------- P13 歌手 id 退化成 0

    /**
     * P13：`SearchApi.parseArtists` 用 `asLong(obj["singerid"])`，字段缺失/类型不符时
     * 得 0，于是 `Artist(id = "0")`。点进歌手页会请求 `/artist/detail?id=0`，
     * 页面全空却没有任何错误提示。
     */
    @Test
    fun `P13 歌手搜索结果缺 singerid 时 id 退化成 0`() {
        val root = JsonLite.parse("""{"data":[{"singername":"<em>某人</em>"}]}""")
        val artists = SearchApi.parseArtists(root)
        // 已修（N21）：id 无效的条目直接丢弃，不再产生可点击的空白页
        assertEquals(0, artists.size, "缺 singerid 的条目应被丢弃")
    }

    // ------------------------------- P14 回退选歌不看歌手

    /**
     * P14：云歌单里未发布的音频取流失败时，用「歌手 + 歌名」搜索官方版回退。
     * 但 `singerFromComposite` 对没有 `歌手 - ` 前缀的文件名返回空串，
     * 此时 `preferSameSinger(candidates, "")` 直接 `pool.firstOrNull()` ——
     * **完全不做歌手校验**，可能换成别人的翻唱，这正是「音频不对」的复现路径。
     */
    @Test
    fun `P14 回退选歌在歌手为空时直接取第一条`() {
        val candidates = listOf(
            SearchHit("我们的歌", "某翻唱歌手", "H1"),
            SearchHit("我们的歌", "刘大拿", "H2"),
        )
        // 已修（N22）：歌手无从判断时返回 null，宁可报不可用也不给错歌
        assertEquals(null, preferSameSinger(candidates, "")?.hash)
    }
}

/**
 * P10 的子进程入口。
 *
 * 在**独立 JVM** 里复现「插件被重新启用后线程池已死」：先 `shutdown()`，
 * 再 `load` 一个新状态并等 600ms，然后打印状态与「回调是否执行」。
 */
fun main() {
    // 已修（N18）：shutdown 后线程池会自动重建，状态必须能离开 Loading
    ApiExec.shutdown()
    val state = MutableAsyncState<Int>()
    state.load { 42 }
    Thread.sleep(600)
    println("STATE=" + state.value::class.simpleName)
    var called = false
    ApiExec.run({ called = true }) { 1 }
    Thread.sleep(300)
    println("CALLBACK=$called")
    println("END")
}
