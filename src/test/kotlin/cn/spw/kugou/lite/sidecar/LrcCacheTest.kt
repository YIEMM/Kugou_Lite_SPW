package cn.spw.kugou.lite.sidecar

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 歌词缓存失效判定的回归测试（用户反馈「有些歌词没有翻译」）。
 *
 * ## 根因
 *
 * 早期代码不会从 KRC 的 `[language:]` 段提取译文，写出的 `.lrc` 只有原文。
 * 后来 `writeLrcSidecar` 加了「已有非空 `.lrc` 就跳过」，
 * 这些旧文件因此**永远不会被新代码重写** —— 表现为部分歌一直没翻译。
 *
 * 修法：写出时加版本戳 `[by:KugouLite/vN]`，读到旧/无戳文件就重新生成。
 */
class LrcCacheTest {

    @Test
    fun `当前版本的 lrc 不需要重写`() {
        assertFalse(needsLrcRewrite("[by:KugouLite/$LRC_GENERATOR_VERSION]" + "\n[00:01.00]hello"))
    }

    @Test
    fun `旧版本戳需要重写`() {
        assertTrue(needsLrcRewrite("[by:KugouLite/v1]" + "\n[00:01.00]hello"))
    }

    @Test
    fun `无版本戳的旧文件需要重写`() {
        assertTrue(needsLrcRewrite("[ti:song]" + "\n[00:01.00]hello"))
        assertTrue(needsLrcRewrite("[by:]" + "\n[00:01.00]hello"))
    }

    @Test
    fun `空文件需要重写`() {
        assertTrue(needsLrcRewrite(""))
    }

    @Test
    fun `版本戳写进 by 标签`() {
        assertTrue(withGeneratorTag("[00:01.00]hello").contains("KugouLite/$LRC_GENERATOR_VERSION"))
    }
}
