package cn.spw.kugou.lite.sidecar

/**
 * 极简 JSON 解析器。
 *
 * ## 为什么不用 kotlinx-serialization / Gson
 *
 * 插件产物是打进宿主进程的 shadow jar，里面每多一个第三方库就多一份
 * **与宿主类路径冲突**的风险（宿主自带 Kotlin、Compose、Okio 等）。
 * 而这里只需要读几个固定结构的 JSON，用 150 行手写解析器完全够，
 * 且零依赖、零冲突面。
 *
 * ## 只实现 JSON 的子集
 *
 * 支持：`null` / `true` / `false` / 数字 / 字符串（含 Unicode 码点转义）/
 * 数组 / 对象。不支持：注释、尾逗号（标准 JSON 也不支持）。
 *
 * > **写这份文件时的坑**：kapt 在**词法阶段**处理 Unicode 转义，源码里任何位置
 * > 出现「反斜杠 + u + 4 位十六进制」（**包括注释和 KDoc**）都会让
 * > `kaptKotlin` 报「非法的 Unicode 逃逸」。所以本文档不能直接写出转义示例，
 * > 代码里也统一用 `Char(0x0C)` 这种构造形式。
 *
 * ## 解析结果是 `Any?`
 *
 * - 对象 → `Map<String, Any?>`
 * - 数组 → `List<Any?>`
 * - 字符串 → `String`
 * - 数字 → `Double`（整数也统一成 Double，取值时用 [asInt] / [asLong] 转换）
 * - 布尔 → `Boolean`
 * - null → `null`
 *
 * 取值辅助函数都做好容错：类型不符或缺失时返回默认值，**绝不抛异常**。
 */
object JsonLite {

    fun parse(text: String): Any? = Parser(text).parseValue()

    // ------------------------------------------------------------ 取值辅助

    @Suppress("UNCHECKED_CAST")
    fun asObject(value: Any?): Map<String, Any?>? = value as? Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    fun asArray(value: Any?): List<Any?>? = value as? List<Any?>

    fun asString(value: Any?): String? = when (value) {
        is String -> value
        is Number -> numberToPlainString(value)
        is Boolean -> value.toString()
        else -> null
    }

    fun asInt(value: Any?, default: Int = 0): Int = when (value) {
        is Number -> value.toInt()
        is String -> value.trim().toDoubleOrNull()?.toInt() ?: default
        else -> default
    }

    fun asLong(value: Any?, default: Long = 0L): Long = when (value) {
        is Number -> value.toLong()
        is String -> value.trim().toDoubleOrNull()?.toLong() ?: default
        else -> default
    }

    fun asBoolean(value: Any?, default: Boolean = false): Boolean = when (value) {
        is Boolean -> value
        is String -> value.equals("true", true) || value == "1"
        is Number -> value.toInt() != 0
        else -> default
    }

    /** 按路径逐层取子对象；任一层缺失返回 null。 */
    fun dig(root: Any?, vararg path: String): Any? {
        var cur: Any? = root
        for (key in path) {
            cur = asObject(cur)?.get(key) ?: return null
        }
        return cur
    }

    /** 取数组里每个元素的某个字符串字段，过滤掉空值。 */
    fun stringList(value: Any?, field: String): List<String> =
        asArray(value).orEmpty().mapNotNull { asString(asObject(it)?.get(field)) }

    /**
     * Double 转回不带 `.0` 的字符串。
     *
     * JSON 解析把整数全变成 Double，若直接 `toString()` 会把 id `211417224`
     * 变成 `2.11417224E8`，拼进 URL 就废了。
     */
    private fun numberToPlainString(value: Number): String {
        val d = value.toDouble()
        return if (d == Math.floor(d) && !d.isInfinite() && Math.abs(d) < 1e15) {
            d.toLong().toString()
        } else {
            d.toString()
        }
    }

    // ------------------------------------------------------------ 解析器

    private class Parser(private val src: String) {
        private var pos = 0

        fun parseValue(): Any? {
            skipWhitespace()
            if (pos >= src.length) throw Malformed("意外结束")
            return when (val c = src[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> parseLiteral("true", true)
                'f' -> parseLiteral("false", false)
                'n' -> parseLiteral("null", null)
                else -> {
                    if (c == '-' || c.isDigit()) parseNumber()
                    else throw Malformed("非法字符 '$c' @$pos")
                }
            }
        }

        private fun parseObject(): Map<String, Any?> {
            expect('{')
            val out = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') { pos++; return out }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                expect(':')
                skipWhitespace()
                out[key] = parseValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> { pos++ }
                    '}' -> { pos++; return out }
                    else -> throw Malformed("对象内期望 , 或 } @$pos")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            expect('[')
            val out = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') { pos++; return out }
            while (true) {
                skipWhitespace()
                out.add(parseValue())
                skipWhitespace()
                when (peek()) {
                    ',' -> { pos++ }
                    ']' -> { pos++; return out }
                    else -> throw Malformed("数组内期望 , 或 ] @$pos")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (pos >= src.length) throw Malformed("字符串未闭合")
                when (val c = src[pos++]) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= src.length) throw Malformed("转义未闭合")
                        when (val e = src[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append(Char(0x0C))
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > src.length) throw Malformed("\\u 不完整")
                                val hex = src.substring(pos, pos + 4)
                                pos += 4
                                val code = hex.toIntOrNull(16)
                                    ?: throw Malformed("非法 \\u 转义 $hex")
                                sb.append(code.toChar())
                            }
                            else -> throw Malformed("未知转义 \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun parseNumber(): Double {
            val start = pos
            if (peek() == '-') pos++
            while (pos < src.length && (src[pos].isDigit() || src[pos] in ".eE+-")) pos++
            val raw = src.substring(start, pos)
            return raw.toDoubleOrNull() ?: throw Malformed("非法数字 $raw")
        }

        private fun parseLiteral(word: String, result: Any?): Any? {
            if (!src.startsWith(word, pos)) throw Malformed("期望 $word @$pos")
            pos += word.length
            return result
        }

        private fun expect(c: Char) {
            if (pos >= src.length || src[pos] != c) throw Malformed("期望 '$c' @$pos")
            pos++
        }

        // 用 Char(0) 而不是 Unicode 转义字面量：kapt 在**词法阶段**就处理
        // Unicode 转义，源码任何位置（含注释）出现「反斜杠 + u + 4 位十六进制」
        // 都会让 kaptKotlin 报「非法的 Unicode 逃逸」，构建直接失败。
        private fun peek(): Char = if (pos < src.length) src[pos] else Char(0)

        private fun skipWhitespace() {
            while (pos < src.length && src[pos].let { it == ' ' || it == '\n' || it == '\r' || it == '\t' }) pos++
        }
    }

    class Malformed(message: String) : RuntimeException(message)
}
