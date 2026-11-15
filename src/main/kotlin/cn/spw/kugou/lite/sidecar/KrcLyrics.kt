package cn.spw.kugou.lite.sidecar

import java.util.Base64 as JBase64
import java.util.Locale

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
     * 从 KRC 里解出**翻译**（中文译文）行。
     *
     * ## `[language:]` 里其实有两种东西
     *
     * 解码后是 JSON：
     * ```json
     * { "content": [
     *     { "type": 0, "language": 0,
     *       "lyricContent": [["ha tsu ","ne ","mi ","ku "], ...] },   // 罗马音
     *     { "type": 1, "language": 0,
     *       "lyricContent": [["不小心说出"], ["想留在你身边哪怕一刻也好"], ...] }  // 中文译文
     * ] }
     * ```
     *
     * **必须按 `type` 挑**：
     * - `type = 0` → 罗马音（逐字读音，如 `ha tsu`）
     * - `type = 1` → 翻译（中文意思）
     *
     * 早期实现直接取 `content[0]`，把罗马音当成了译文 ——
     * 真机表现为歌词下方显示 `ha tsu` / `shi` / `kyo ku` 这些拼音。
     *
     * @return 与歌词行对齐的译文数组；无译文返回空列表
     */
    fun extractTranslation(krc: String): List<String> =
        extractLanguageLines(krc, wantType = 1)

    /**
     * 是否有**实际可读**的中文译文（不是只有罗马音）。
     *
     * 用户反馈「みむかゥわナイストライ没有翻译」：该曲的 `[language:]`
     * 段只有 `type=0`（罗马音），`extractTranslation` 返回空列表。
     * 这里要求至少有一行非空译文，用于决定是否要去找同名其它版本。
     */
    fun hasMeaningfulTranslation(krc: String): Boolean =
        extractTranslation(krc).any { it.isNotBlank() }

    /**
     * 从 KRC 里解出**罗马音**行。
     *
     * 结构见 [extractTranslation]。罗马音是**逐字**数组
     * （`["ha tsu ", "ne ", ...]`），这里拼接成整行。
     */
    fun extractRomanization(krc: String): List<String> =
        extractLanguageLines(krc, wantType = 0)

    /**
     * 按 `type` 抽取 `[language:]` 里的行。
     *
     * @param wantType 0 = 罗马音，1 = 翻译
     */
    private fun extractLanguageLines(krc: String, wantType: Int): List<String> {
        val m = LANGUAGE_PATTERN.find(krc) ?: return emptyList()
        return try {
            val json = String(decodeBase64(m.groupValues[1]), Charsets.UTF_8)
            val root = JsonLite.parse(json) ?: return emptyList()
            val content = JsonLite.asArray(JsonLite.dig(root, "content")) ?: return emptyList()

            // 找到 type 匹配的那一项
            val target = content
                .mapNotNull { JsonLite.asObject(it) }
                .firstOrNull { JsonLite.asInt(it["type"], -1) == wantType }
                ?: return emptyList()

            val lines = JsonLite.asArray(target["lyricContent"]) ?: return emptyList()
            lines.map { item ->
                val arr = JsonLite.asArray(item)
                if (arr != null) {
                    // 逐字数组 → 拼接成整行
                    arr.mapNotNull { JsonLite.asString(it) }.joinToString("").trim()
                } else {
                    JsonLite.asString(item)?.trim().orEmpty()
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
    /**
     * 把 KRC 转成**纯文本**逐行 LRC（不带 `<mm:ss.xx>` 逐字标记）。
     *
     * ## 只用于「纯文本」场景
     *
     * 目前唯一的正当用途是往音频文件写 **ID3 USLT 帧**：USLT 是纯文本，
     * 逐字标记会被当普通字符显示出来。
     *
     * ## ⚠️ 不要用在 PlaybackExtensionPoint 上
     *
     * 宿主 `updateLyrics` / `onBeforeLoadLyrics` 的返回值会喂给它的逐字
     * 歌词模型（`LyricsLine` + `LyricsCell`），必须用 [toEnhancedLrc] 保留
     * `<mm:ss.xx>` 标记才有逐字高亮。早期误用本函数导致逐字歌词退化
     * 成整行高亮（用户反馈「我的逐字歌词呢？」）。
     */
    fun toPlainLrc(krc: String): String =
        WORD_PATTERN_LRC.replace(toEnhancedLrc(krc), "")

    /** 匹配增强 LRC 里的 `<mm:ss.xx>` 逐字标记。 */
    private val WORD_PATTERN_LRC = Regex("""<\d{2}:\d{2}\.\d{2}>""")

    fun toEnhancedLrc(krc: String, withTranslation: Boolean = true): String {
        // 优先中文译文（type=1）；没有就退化为罗马音（type=0）。
        // 用户反馈「みむかゥわナイストライ没有翻译」：该曲 KRC 只有罗马音，
        // 早期只取译文 → 完全没有翻译行。至少给用户读音。
        val translation = if (withTranslation) {
            extractTranslation(krc).takeIf { it.any { line -> line.isNotBlank() } }
                ?: extractRomanization(krc)
        } else {
            emptyList()
        }
        val cleaned = sanitize(krc)
        val out = StringBuilder()

        // 保留头部元信息。
        //
        // 两条硬性要求（见 EnhancedLrcTagTest）：
        // 1. **[offset:]** 必须存在。用户反馈「人是猫」显示无歌词：该曲的
        //    .lrc 内容完全正常，但上游 KRC 头部没有 [offset:]，而缓存里
        //    缺 [offset:] 的 4 个文件恰好就是显示无歌词的那 4 首
        //    （其余 31 首都有 [offset:0]）。缺省补 0。
        // 2. **[id:]** 要丢弃。它是酷狗内部字段（值形如 $00000000），
        //    对宿主没有意义，且和缺失的 [offset:] 总是同时出现。
        var sawOffset = false
        for (line in cleaned.lineSequence()) {
            val t = line.trim()
            if (t.isEmpty()) continue
            if (t.startsWith("[") && !LINE_PATTERN.matches(t)) {
                if (t.startsWith("[id:")) continue
                // [total:] 是上游 KRC 的「歌词总时长」字段，值常见为 0。
                // 宿主把它当歌词时长解析，0 会被判为无效歌词 ——
                // 用户反馈「播放时显示无歌词」的 3 个文件全部带 [total:0]。
                // 该标签对宿主没有正面作用，直接丢弃。
                if (t.startsWith("[total:")) continue
                if (t.startsWith("[offset:")) sawOffset = true
                if (t.startsWith("[ti:") || t.startsWith("[ar:") ||
                    t.startsWith("[al:") || t.startsWith("[by:") ||
                    t.startsWith("[offset:")
                ) {
                    out.appendLine(t)
                }
            }
        }
        if (!sawOffset) {
            out.appendLine("[offset:0]")
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

            // 翻译行：紧跟在原文后，**必须复用原文行的同一时间戳**。
            //
            // 宿主的歌词模型是「一行 = 主文本 + 副文本」：
            //
            //     LyricsLine(startTime, endTime, lyricsCells, pureMainText, pureSubText)
            //
            // 它靠**相同时间戳**把紧随其后的一行识别为上一行的译文，
            // 填进 `pureSubText`，渲染时译文显示在主文本下方（受宿主设置项
            // `show_player_lyrics_sub_text` 控制），逐字单元则来自主文本行。
            //
            // 曾误改成 +10ms「避免时间戳重复」，结果译文被当成**独立的歌词行**：
            // 主文本与译文交替出现，逐字也失效（用户反馈「我的逐字歌词呢」）。
            // 时间戳必须一致。
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
        // 必须显式指定 Locale.ROOT：Kotlin 的 String.format 默认取
        // Locale.getDefault()，在 ar / fa / bn 等「本地数字形态」环境下
        // %d 会输出 U+0660..U+0669 之类的本地数字，写出的 .lrc 时间戳
        // 不再匹配 [mm:ss.xx] —— 宿主解析失败，整首无歌词。
        // 这类文件内容本身就是坏的，换任何读取时机都救不回来。
        return String.format(Locale.ROOT, "%02d:%02d.%02d", mm, ss, cs)
    }
}
