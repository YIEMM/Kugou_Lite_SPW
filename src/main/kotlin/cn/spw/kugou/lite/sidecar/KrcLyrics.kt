package cn.spw.kugou.lite.sidecar

import java.util.Base64 as JBase64

/**
 * 酷狗 KRC（逐字歌词）解析与转换。
 *
 * ## KRC 是什么
 *
 * 酷狗歌词接口的 `fmt=krc` 返回**逐字**歌词，而 `fmt=lrc` 只有逐行。
 *
 * ## KRC 行格式
 *
 * ```
 * [行起始ms,行时长ms]<字相对偏移ms,字时长ms,保留位>字<...>字...
 * ```
 *
 * 例：`[191,3515]<0,836,0>黑<836,493,0>縁`
 *
 * - `[191,3515]` —— 整行从 191ms 开始，持续 3515ms
 * - `<0,836,0>黑` —— 相对行首偏移 0ms，这个字占 836ms
 * - `<836,493,0>縁` —— 相对行首偏移 836ms，占 493ms
 *
 * 注意 `<offset,duration,0>` 里的 **offset 是相对行首的偏移**，
 * duration 是该字的演唱时长。空格字的 duration 常为 0。
 *
 * ## 翻译与音译
 *
 * KRC 里还有两个可选段：
 *
 * ```
 * [language:<base64>]   // 翻译歌词（中文意思）
 * [kana:<原文>]          // 音译（日语罗马字/假名）
 * ```
 *
 * `[language:]` 解码后是 JSON：
 * ```json
 * { "content": [ { "lyricContent": [[""], [""], ["翻译行1"], ...],
 *                  "type": 1, "language": 0 } ] }
 * ```
 * 其中 `lyricContent` 是**与歌词行一一对应**的数组（索引对齐），
 * `""` 表示该行无翻译。
 *
 * `[kana:]` 是逐字的音译数据，格式类似
 * `1えん1じ1か(1213,221)さ(1434,178)` —— 前面数字是分隔标记，
 * 括号里是时间。目前暂不解析（宿主的歌词视图不显示音译行）。
 */
object KrcLyrics {

    private val LINE_PATTERN = Regex("""^\[(\d+),(\d+)](.*)$""")
    private val WORD_PATTERN = Regex("""<(\d+),(\d+),(\d+)>""")
    private val LANGUAGE_PATTERN = Regex("""\[language:([A-Za-z0-9+/=]+)]\s*""")
    private val KANA_PATTERN = Regex("""\[kana:[^\]]*]\s*""")

    /** 是否是 KRC 格式。 */
    fun isKrc(text: String): Boolean {
        if (text.isBlank()) return false
        if (!WORD_PATTERN.containsMatchIn(text)) return false
        return text.lineSequence().any { LINE_PATTERN.matches(it.trim()) }
    }

    /**
     * 从 KRC 里解出翻译行。
     *
     * @return 与歌词行对齐的翻译数组；无翻译返回空列表
     */
    fun extractTranslation(krc: String): List<String> {
        val m = LANGUAGE_PATTERN.find(krc) ?: return emptyList()
        val b64 = m.groupValues[1]
        return try {
            val json = String(decodeBase64(b64), Charsets.UTF_8)
            val root = JsonLite.parse(json) ?: return emptyList()
            // content 是数组，取第一个元素的 lyricContent
            val content = JsonLite.asArray(JsonLite.dig(root, "content")) ?: return emptyList()
            val first = JsonLite.asObject(content.firstOrNull()) ?: return emptyList()
            val lines = JsonLite.asArray(first["lyricContent"]) ?: return emptyList()
            lines.map { item ->
                // 每项是 ["翻译文本"] 形式的数组
                val arr = JsonLite.asArray(item)
                if (arr != null) {
                    arr.firstNotNullOfOrNull { JsonLite.asString(it) }?.takeIf { it.isNotBlank() } ?: ""
                } else {
                    JsonLite.asString(item)?.takeIf { it.isNotBlank() } ?: ""
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** base64 解码（容忍 URL-safe 与缺失填充）。 */
    private fun decodeBase64(text: String): ByteArray = try {
        JBase64.getDecoder().decode(text)
    } catch (e: IllegalArgumentException) {
        try {
            JBase64.getUrlDecoder().decode(text)
        } catch (e2: IllegalArgumentException) {
            try {
                JBase64.getMimeDecoder().decode(text)
            } catch (e3: IllegalArgumentException) {
                ByteArray(0)
            }
        }
    }

    /**
     * 清洗 KRC：剥掉 `[language:]` 与 `[kana:]` 段。
     *
     * 这两个标签宿主解析器不认识，留着可能干扰。
     */
    fun sanitize(krc: String): String {
        if (krc.isBlank()) return krc
        return KANA_PATTERN.replace(LANGUAGE_PATTERN.replace(krc, ""), "")
    }

    /**
     * 把 KRC 转成**增强 LRC**（带逐字时间戳的标准 LRC）。
     *
     * ## 格式
     *
     * ```
     * [00:11.04]<00:11.04>い<00:11.19>や<00:11.33>い<00:11.47>や
     * ```
     *
     * 每行先给行时间，再给每个字的**绝对时间**。
     *
     * ## 翻译怎么放
     *
     * 增强 LRC 没有标准的双语写法。这里采用业界常见做法：
     * **在原文行之后紧跟一行只含翻译**（行时间与原文相同）：
     *
     * ```
     * [00:11.04]<00:11.04>い<00:11.19>や<...
     * [00:11.04]呵，真慢啊
     * ```
     *
     * 这样宿主会把翻译当成独立的一行歌词显示在原文下方，
     * 与主流音乐 App 的双语歌词表现一致。
     *
     * @param withTranslation 是否带上翻译行
     */
    fun toEnhancedLrc(krc: String, withTranslation: Boolean = true): String {
        val translation = if (withTranslation) extractTranslation(krc) else emptyList()
        val cleaned = sanitize(krc)
        val out = StringBuilder()

        // 保留头部元信息
        for (line in cleaned.lineSequence()) {
            val t = line.trim()
            if (t.isEmpty()) continue
            if (t.startsWith("[") && !LINE_PATTERN.matches(t)) {
                if (t.startsWith("[id:") || t.startsWith("[ti:") || t.startsWith("[ar:") ||
                    t.startsWith("[al:") || t.startsWith("[by:") || t.startsWith("[total:") ||
                    t.startsWith("[offset:")
                ) {
                    out.appendLine(t)
                }
            }
        }

        // 逐行转换。翻译数组与「有时间的歌词行」按顺序对齐，
        // 所以只对能解析出行头的行递增索引。
        var lineIndex = 0
        for (line in cleaned.lineSequence()) {
            val t = line.trim()
            val m = LINE_PATTERN.find(t) ?: continue
            val lineStart = m.groupValues[1].toLongOrNull() ?: continue
            val body = m.groupValues[3]

            val cells = parseLine(body, lineStart)
            if (cells.isNotEmpty()) {
                out.append(formatTime(lineStart))
                for ((absMs, word) in cells) {
                    out.append('<').append(formatTimeInner(absMs)).append('>').append(word)
                }
                out.appendLine()
            }

            // 翻译行：紧跟在原文后，使用同一个行时间
            val tr = translation.getOrNull(lineIndex)?.trim().orEmpty()
            if (tr.isNotEmpty()) {
                out.append(formatTime(lineStart)).appendLine(tr)
            }
            lineIndex++
        }

        return if (out.isEmpty()) cleaned else out.toString()
    }

    /**
     * 解析一行的逐字单元。
     *
     * @param body 行内容（`<offset,dur,0>字<...>` 部分）
     * @param lineStartMs 该行的绝对起始时间（毫秒）
     * @return `(绝对时间ms, 字文本)` 列表
     *
     * ## 为什么必须传 lineStartMs
     *
     * KRC 的 `<offset,dur,0>` 里 offset 是**相对行首**的毫秒数。
     * 早期实现直接把它当绝对时间输出，导致一行的所有字都挤在 0ms 附近 ——
     * 这正是用户反馈「逐字歌词转换太垃圾」的原因。
     *
     * 例：行首 23129ms 的那一行，首字输出应是 `<00:23.13>` 而不是 `<00:00.00>`。
     */
    private fun parseLine(body: String, lineStartMs: Long): List<Pair<Long, String>> {
        val matches = WORD_PATTERN.findAll(body).toList()
        if (matches.isEmpty()) return emptyList()

        val result = ArrayList<Pair<Long, String>>(matches.size)

        for ((idx, wm) in matches.withIndex()) {
            val offset = wm.groupValues[1].toLongOrNull() ?: continue
            val textStart = wm.range.last + 1
            val textEnd = if (idx + 1 < matches.size) matches[idx + 1].range.first else body.length
            val word = if (textStart < textEnd) body.substring(textStart, textEnd) else ""
            if (word.isEmpty()) continue
            result.add((lineStartMs + offset) to word)
        }
        return result
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
