package cn.spw.kugou.lite.sidecar

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * .lrc 必须原子落盘（用户反馈「偶发无歌词，重新读取缓存就好」）。
 *
 * ## 现场
 *
 * 用户观察到：修完 [total:0] 之后，从「必定没歌词」变成
 * 「有概率没歌词」，重新读取缓存（再播一次）就正常。
 *
 * 说明 .lrc 内容是对的，只是宿主读取时文件还没写完：
 * 旧实现直接 lrc.writeText(text) —— 先把文件截断为 0 再写。
 * 宿主的歌词加载在另一条协程上，恰好撞上这个窗口就读到空/半截
 * 内容，判为「无歌词」。音频下载早就用 .part + rename 的原子写法，
 * 歌词却漏了 —— 这就是「有概率触发」的来源。
 *
 * ## 判定
 *
 * - 写完后内容完整
 * - 不残留 .part 临时文件
 * - 重写时旧内容被替换
 */
class AtomicLrcWriteTest {

    private lateinit var dir: File

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("lrc-atomic").toFile()
    }

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun audio(): File {
        val f = File(dir, "abc-128.mp3")
        f.writeBytes(ByteArray(32))
        return f
    }

    @Test
    fun `写入后内容完整且无临时文件残留`() {
        val mp3 = audio()
        val plain = "[00:01.00]hello\n[00:02.00]world"
        assertTrue(AudioCache.writeLrcSidecarForTest(mp3, plain))

        val lrc = File(dir, "abc-128.lrc")
        assertTrue(lrc.isFile, "歌词文件应存在")
        val body = lrc.readText(Charsets.UTF_8)
        assertTrue(body.contains("hello") && body.contains("world"), "内容必须完整，实际：" + body)
        assertTrue(body.contains("KugouLite/"), "应带生成器戳")

        val leftovers = dir.listFiles().orEmpty().filter { it.name.endsWith(".part") }
        assertTrue(leftovers.isEmpty(), "不得残留 .part，实际：" + leftovers.map { it.name })
    }

    @Test
    fun `重写时旧内容被替换`() {
        val mp3 = audio()
        AudioCache.writeLrcSidecarForTest(mp3, "[00:01.00]old")

        val lrc = File(dir, "abc-128.lrc")
        // 制造旧版本戳，触发重写
        lrc.writeText("[by:KugouLite/v4]\n[00:01.00]stale", Charsets.UTF_8)

        assertTrue(AudioCache.writeLrcSidecarForTest(mp3, "[00:02.00]new"))
        val body = lrc.readText(Charsets.UTF_8)
        assertTrue(body.contains("new"), "应写入新内容，实际：" + body)
        assertFalse(body.contains("stale"), "旧内容应被替换")
    }
}
