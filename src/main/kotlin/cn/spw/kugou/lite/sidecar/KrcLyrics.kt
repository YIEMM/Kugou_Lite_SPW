package cn.spw.kugou.lite.sidecar

/**
 * 酷狗 KRC（逐字歌词）格式处理。
 *
 * ## KRC 是什么
 *
 * 酷狗歌词接口的 `fmt=krc` 返回**逐字**歌词，而 `fmt=lrc` 只有逐行。
 * 同一首歌实测对比：
 *
 * | fmt | 大小 | 内容样例 |
 * |---|---|---|
 * | `lrc` | 811 B | `[00:03.75]愛舐め上手芳しき` |
 * | `krc` | 7744 B | `[191,3515]<0,836,0>黑<836,493,0>縁...` |
 *
 * ## KRC 行格式
 *
 * ```
 * [行起始ms,行时长ms]<字相对偏移ms,字时长ms,保留位>字<...>字...
 * ```
 *
 * 例：`[191,3515]<0,836,0>黑<836,493,0>縁 <1329,232,0>(Chlo...`
 *
 * - `[191,3515]` —— 整行从 191ms 开始，持续 3515ms
 * - `<0,836,0>黑` —— 第 0ms 起，这个字占 836ms
 * - `<836,493,0>縁` —— 第 836ms 起（相对行首），占 493ms
 *
 * ## 为什么要剥离 `[language:...]`
 *
 * KRC 里有一个 `[language:<base64>]` 段，内容是**翻译歌词**的 JSON
 * （base64 编码）。宿主的 `.lrc` 解析器不认识这个标签，留着可能干扰解析。
 * 翻译暂时不用，直接剥掉。
 */
object KrcLyrics {

    private val LINE_PATTERN = Regex("""^\[(\d+),(\d+)](.*)$""")
    private val WORD_PATTERN = Regex("""<(\d+),(\d+),(\d+)>""")
    private val LANGUAGE_PATTERN = Regex("""\[language:[A-Za-z0-9+/=]+]\s*""")

    /**
     * 是否是 KRC 格式。
     *
     * 判据：**同时**满足
     * 1. 存在逐字标签 `<offset,duration,flag>`
     * 2. 存在「行头 `[起始ms,时长ms]`」形式的行
     *
     * 注意第 2 条必须用「毫秒数字」而不是「mm:ss」——
     * 标准 LRC 的行头是 `[00:03.75]`（含冒号），KRC 是 `[191,3515]`（纯数字逗号）。
     * 早期用宽松正则，把 `[00:03.75]` 也匹配上了，导致普通 LRC 被误判为 KRC。
     */
    fun isKrc(text: String): Boolean {
        if (text.isBlank()) return false
        if (!WORD_PATTERN.containsMatchIn(text)) return false
        // 逐行检查，必须有至少一行是 "[纯数字,纯数字]" 形式
        return text.lineSequence().any { LINE_PATTERN.matches(it.trim()) }
    }

    /**
     * 清洗 KRC：剥掉 `[language:...]` 翻译段。
     *
     * 其余内容原样保留 —— 宿主自己的解析器会处理逐字标签。
     */
    fun sanitize(krc: String): String {
        if (krc.isBlank()) return krc
        return LANGUAGE_PATTERN.replace(krc, "")
    }

    /**
     * 把 KRC 转成**增强 LRC**（带逐字时间戳的标准 LRC）。
     *
     * ## 为什么可能需要转换
     *
     * 宿主 `TagParser.readLyricsByLrc` 读的是同名 `.lrc` 文件，然后交给
     * `com.xuncorp.spc.lyrics` 包解析。该包的模型是 `LyricsLine` + `LyricsCell`
     * （逐字单元），所以它**有能力**表达逐字。
     *
     * 但我们无法确定它认不认酷狗的 `<offset,dur,0>` 私有标签。
     * 增强 LRC 是业界通用写法，兼容性更好：
     *
     * ```
     * [00:03.75]<00:03.75>愛<00:04.33>舐<00:04.92>め
     * ```
     *
     * 即每行先给行时间，再给每个字的绝对时间。
     *
     * @return 转换后的文本；若无法解析则返回原始 KRC 的清洗版
     */
    fun toEnhancedLrc(krc: String): String {
        val cleaned = sanitize(krc)
        val out = StringBuilder()

        // 保留头部元信息标签（[id:]/[ti:]/[ar:]/[al:]/[by:] 等）
        for (line in cleaned.lineSequence()) {
            val t = line.trim()
            if (t.isEmpty()) continue
            if (t.startsWith("[") && !LINE_PATTERN.matches(t)) {
                // 非 "行时间" 的元信息标签，原样保留
                if (t.startsWith("[id:") || t.startsWith("[ti:") || t.startsWith("[ar:") ||
                    t.startsWith("[al:") || t.startsWith("[by:") || t.startsWith("[total:") ||
                    t.startsWith("[offset:")
                ) {
                    out.appendLine(t)
                }
                continue
            }

            val m = LINE_PATTERN.find(t) ?: continue
            val lineStart = m.groupValues[1].toLongOrNull() ?: continue
            val body = m.groupValues[3]

            // 逐个字提取：<offset,dur,0>字
            val cells = ArrayList<Pair<Long, String>>() // (绝对ms, 字)
            var cursor = 0
            val matches = WORD_PATTERN.findAll(body).toList()
            for ((idx, wm) in matches.withIndex()) {
                val offset = wm.groupValues[1].toLongOrNull() ?: 0
                val textStart = wm.range.last + 1
                val textEnd = if (idx + 1 < matches.size) matches[idx + 1].range.first else body.length
                val word = if (textStart < textEnd) body.substring(textStart, textEnd) else ""
                if (word.isNotEmpty()) {
                    cells.add((lineStart + offset) to word)
                }
                cursor = textEnd
            }

            if (cells.isEmpty()) continue

            // 输出：[行起始] <字1绝对时间>字1 <字2绝对时间>字2 ...
            out.append(formatTime(lineStart))
            for ((absMs, word) in cells) {
                out.append('<').append(formatTimeInner(absMs)).append('>').append(word)
            }
            out.appendLine()
        }

        return if (out.isEmpty()) cleaned else out.toString()
    }

    /** `[mm:ss.xx]` 形式（方括号）。 */
    private fun formatTime(ms: Long): String = "[${formatTimeInner(ms)}]"

    /** `mm:ss.xx` 形式（不带括号，用于 `<...>` 内）。 */
    private fun formatTimeInner(ms: Long): String {
        val totalSec = ms / 1000
        val mm = totalSec / 60
        val ss = totalSec % 60
        val cs = (ms % 1000) / 10
        return "%02d:%02d.%02d".format(mm, ss, cs)
    }
}
