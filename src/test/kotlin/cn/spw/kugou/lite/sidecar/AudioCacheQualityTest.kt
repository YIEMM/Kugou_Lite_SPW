package cn.spw.kugou.lite.sidecar

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 音频缓存的命名与命中规则（BUG-07 / BUG-08 / BUG-36）。
 *
 * ## 三个缺陷
 *
 * 1. **BUG-07**：`localFileOrNull` 只拼 `<key>.mp3`，而 `ensureLocal` 会按返回的
 *    容器写 `<key>.flac`。于是无损缓存**写了但永远查不到**，每次播放都重新下载。
 * 2. **BUG-08**：音质档位不在缓存键里。设置从「标准」切到「无损」后，
 *    仍然命中之前那份 128k 文件 —— 用户听不出任何区别，还以为无损没生效。
 * 3. **BUG-36**：`key` 直接进文件名。上游 hash 万一含 `/` 或 `..`，
 *    就能把文件写到缓存目录之外。
 *
 * ## 契约（本轮固化）
 *
 * ```text
 * 缓存文件名 = <净化后的 key>-<请求音质>.<容器扩展名>
 *             例：ABCDEF-128.mp3、ABCDEF-flac.flac
 * 查找顺序   = 已知容器扩展名逐个探测（mp3 / flac / m4a / ogg / aac / wav）
 * 音质键     = Settings.requestQuality()（用户**请求**的档位，不是服务端实际给的）
 * ```
 *
 * 为什么用「请求的音质」而不是「实际返回的音质」：`resolveQualityChain()` 会回退
 * （请求 flac 拿到 320 是常态）。若按实际档位命名，下次请求 flac 就查不到
 * `-320.mp3`，又变成重复下载。按请求档位命名，一次请求 → 一个稳定键。
 */
class AudioCacheQualityTest {

    private fun song(hash: String = "H", albumAudioId: String = "") = Song(
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

    /** 建一套全新的缓存目录并返回 `audio-cache` 子目录。 */
    private fun newCacheDir(): File {
        val base = Files.createTempDirectory("kugou-cache-q").toFile()
        AudioCache.configure(base)
        return File(base, "audio-cache").apply { mkdirs() }
    }

    /**
     * 临时切换音质，结束后复位。
     *
     * `Settings.quality` 是 private + 公开 setter（setter 会落盘），所以只能走
     * [Settings.setQuality]；复位时用 [Settings.Quality.from] 反推原档位
     * —— `AUTO` 与 `STANDARD` 的 `requestQuality()` 都是 `128`，
     * 对缓存键而言等价，复位成 STANDARD 不影响语义。
     */
    private fun withQuality(quality: Settings.Quality, block: () -> Unit) {
        val old = Settings.Quality.from(Settings.requestQuality())
        Settings.setQuality(quality)
        try {
            block()
        } finally {
            Settings.setQuality(old)
        }
    }

    /**
     * 造一个「看起来是完整音频」的占位缓存文件。
     *
     * 必须带正确的容器魔数且体积够大：N05 修复后 AudioCache 会校验文件头，
     * 32 字节的垃圾文件不再被当作有效缓存（这正是要防的场景）。
     * 这里按扩展名写入对应魔数，保持测试语义（测的是「命中/不命中」，不是校验器）。
     */
    private fun put(dir: File, name: String): File =
        File(dir, name).apply {
            parentFile?.mkdirs()
            val magic = when {
                name.endsWith(".flac") -> "fLaC".toByteArray(Charsets.ISO_8859_1)
                name.endsWith(".mp3") -> byteArrayOf(
                    'I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0, 0, 0, 0, 0,
                )
                name.endsWith(".ogg") -> "OggS".toByteArray(Charsets.ISO_8859_1)
                name.endsWith(".m4a") -> byteArrayOf(0, 0, 0, 32) + "ftyp".toByteArray(Charsets.ISO_8859_1)
                name.endsWith(".wav") -> "RIFF".toByteArray(Charsets.ISO_8859_1)
                else -> ByteArray(0)
            }
            // 16KB 填充，越过 MIN_AUDIO_BYTES
            writeBytes(magic + ByteArray(16 * 1024))
        }

    // ------------------------------------------------------------------

    @Test
    fun `FLAC 缓存能被命中`() {
        val dir = newCacheDir()
        put(dir, "H-128.flac")

        withQuality(Settings.Quality.STANDARD) {
            val hit = AudioCache.localFileOrNull(song("H"))
            assertNotNull(hit, "同目录下的 .flac 必须被识别，否则无损每次都重下")
            assertTrue(hit.name.endsWith(".flac"), "实际命中：${hit.name}")
        }
    }

    @Test
    fun `切换音质后不复用旧音质缓存`() {
        val dir = newCacheDir()
        put(dir, "H-128.mp3")

        withQuality(Settings.Quality.STANDARD) {
            assertNotNull(AudioCache.localFileOrNull(song("H")), "标准档应命中 128k")
        }
        withQuality(Settings.Quality.HIGH) {
            assertNull(
                AudioCache.localFileOrNull(song("H")),
                "切到 320k 后不能再复用 128k 文件，否则用户切了音质却听不出变化",
            )
        }
        withQuality(Settings.Quality.LOSSLESS) {
            assertNull(AudioCache.localFileOrNull(song("H")), "切到无损后同理")
        }
    }

    @Test
    fun `同一首歌不同音质可并存`() {
        val dir = newCacheDir()
        put(dir, "H-128.mp3")
        put(dir, "H-flac.flac")

        withQuality(Settings.Quality.STANDARD) {
            val hit = AudioCache.localFileOrNull(song("H"))
            assertNotNull(hit)
            assertEquals("H-128.mp3", hit.name)
        }
        withQuality(Settings.Quality.LOSSLESS) {
            val hit = AudioCache.localFileOrNull(song("H"))
            assertNotNull(hit)
            assertEquals("H-flac.flac", hit.name)
        }
    }

    @Test
    fun `无音质后缀的旧缓存不再命中`() {
        // 旧命名 `<key>.mp3` 无法判断音质，继续命中就等于让 BUG-08 复活。
        // 这条行为是**有意的**：旧文件由用户「清除缓存」回收。
        val dir = newCacheDir()
        put(dir, "H.mp3")

        withQuality(Settings.Quality.STANDARD) {
            assertNull(AudioCache.localFileOrNull(song("H")), "旧命名不得命中")
        }
    }

    @Test
    fun `缓存键会净化路径字符`() {
        // 上游 hash 万一带 ../，必须被净化后再进文件名。
        val stem = cacheStemOf("../../etc/passwd", "")
        assertNotNull(stem)
        assertFalse(stem.contains('/'), "不得含路径分隔符：$stem")
        assertFalse(stem.contains('\\'), "不得含反斜杠：$stem")
        assertFalse(stem.contains(".."), "不得保留上跳段：$stem")
        assertTrue(stem.isNotBlank())
    }

    @Test
    fun `缓存文件名把音质与容器都写进名字`() {
        assertEquals("ABC-128.mp3", cacheName("ABC", "128", "mp3"))
        assertEquals("ABC-flac.flac", cacheName("ABC", "flac", "FLAC"))
        // 音质为空时按标准档兜底，避免出现 `ABC-.mp3` 这种无法复现的名字
        assertEquals("ABC-128.mp3", cacheName("ABC", "", ""))
    }
}
