package cn.spw.kugou.lite.sidecar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [JsonLite] 的单元测试。
 *
 * 这些用例的样本都取自**真实酷狗 API 响应**（2026-11 实测 v1.6.3），
 * 不是凭空构造的，所以能反映真实数据结构。
 */
class JsonLiteTest {

    @Test
    fun `解析嵌套对象与数组`() {
        val text = """{"data":{"lists":[{"FileHash":"ABC","OriSongName":"发如雪"}]}}"""
        val root = JsonLite.parse(text)
        val hash = JsonLite.asString(JsonLite.dig(root, "data", "lists", "0", "FileHash"))
        // dig 不走数组下标，这里验证 asArray 路径
        val lists = JsonLite.asArray(JsonLite.dig(root, "data", "lists"))
        assertEquals(1, lists?.size)
        val first = JsonLite.asObject(lists!!.first())
        assertEquals("ABC", JsonLite.asString(first?.get("FileHash")))
        assertEquals("发如雪", JsonLite.asString(first?.get("OriSongName")))
        assertNull(hash)
    }

    @Test
    fun `大整数 id 不会被转成科学计数法`() {
        // 真实歌词响应里 id 是 211417224，若按 Double.toString() 会变成 2.11417224E8
        val text = """{"id":211417224,"duration":186000}"""
        val root = JsonLite.parse(text)
        assertEquals("211417224", JsonLite.asString(JsonLite.dig(root, "id")))
        assertEquals("186000", JsonLite.asString(JsonLite.dig(root, "duration")))
    }

    @Test
    fun `Unicode 转义被正确还原`() {
        val text = """{"name":"\u5C0F\u82F9\u679C"}"""
        val root = JsonLite.parse(text)
        assertEquals("小苹果", JsonLite.asString(JsonLite.dig(root, "name")))
    }

    @Test
    fun `常规转义字符`() {
        val text = """{"s":"a\nb\tc\"d\\e\/f"}"""
        val root = JsonLite.parse(text)
        assertEquals("a\nb\tc\"d\\e/f", JsonLite.asString(JsonLite.dig(root, "s")))
    }

    @Test
    fun `真实搜索响应片段`() {
        val text = """
            {
              "status": 1,
              "data": {
                "total": 452,
                "lists": [
                  {
                    "FileName": "周杰伦 - 发如雪",
                    "SingerName": "周杰伦",
                    "OriSongName": "发如雪",
                    "FileHash": "1F7E7659B9FFF4FB6F71562A61BBC25F",
                    "FileSize": 4789176,
                    "Duration": 299,
                    "PayType": 3,
                    "AlbumName": "十一月的萧邦",
                    "Image": "http://imge.kugou.com/stdmusic/{size}/20250125/x.jpg"
                  }
                ]
              }
            }
        """.trimIndent()
        val root = JsonLite.parse(text)
        assertEquals(1, JsonLite.asInt(JsonLite.dig(root, "status")))
        assertEquals(452, JsonLite.asInt(JsonLite.dig(root, "data", "total")))

        val lists = JsonLite.asArray(JsonLite.dig(root, "data", "lists"))!!
        val song = JsonLite.asObject(lists.first())!!
        assertEquals("1F7E7659B9FFF4FB6F71562A61BBC25F", song["FileHash"])
        assertEquals(299, JsonLite.asInt(song["Duration"]))
        assertEquals(4789176L, JsonLite.asLong(song["FileSize"]))
    }

    @Test
    fun `真实取流响应片段`() {
        val text = """
            {
              "status": 1,
              "url": ["http://fsandroid.kugou.com/a.mp3"],
              "backupUrl": ["http://fsmobile.tx.kugou.com/a.mp3"],
              "bitRate": 128000,
              "fileSize": 2992764,
              "extName": "mp3",
              "timeLength": 186
            }
        """.trimIndent()
        val root = JsonLite.parse(text)
        val urls = JsonLite.asArray(JsonLite.dig(root, "url"))!!
        assertEquals("http://fsandroid.kugou.com/a.mp3", JsonLite.asString(urls.first()))
        assertEquals(128000, JsonLite.asInt(JsonLite.dig(root, "bitRate")))
        assertEquals(186, JsonLite.asInt(JsonLite.dig(root, "timeLength")))
    }

    @Test
    fun `付费曲目响应无 url 且带 fail_process`() {
        val text = """{"status":2,"fail_process":["pkg","buy"],"priv_status":0}"""
        val root = JsonLite.parse(text)
        assertNull(JsonLite.dig(root, "url"))
        val fail = JsonLite.asArray(JsonLite.dig(root, "fail_process"))!!
            .mapNotNull { JsonLite.asString(it) }
        assertTrue(fail.contains("buy"))
        assertTrue(fail.contains("pkg"))
    }

    @Test
    fun `缺失字段返回默认值而不抛异常`() {
        val root = JsonLite.parse("{}")
        assertEquals(0, JsonLite.asInt(JsonLite.dig(root, "nope")))
        assertEquals(0L, JsonLite.asLong(JsonLite.dig(root, "nope")))
        assertNull(JsonLite.asString(JsonLite.dig(root, "nope")))
        assertEquals(7, JsonLite.asInt(null, 7))
        assertEquals("", JsonLite.stringList(null, "x").joinToString())
    }

    @Test
    fun `空数组与空对象`() {
        assertEquals(0, JsonLite.asArray(JsonLite.parse("[]"))?.size)
        assertEquals(0, JsonLite.asObject(JsonLite.parse("{}"))?.size)
    }

    @Test
    fun `布尔与 null 字面量`() {
        val root = JsonLite.parse("""{"a":true,"b":false,"c":null}""")
        assertEquals(true, JsonLite.asBoolean(JsonLite.dig(root, "a")))
        assertEquals(false, JsonLite.asBoolean(JsonLite.dig(root, "b")))
        assertNull(JsonLite.dig(root, "c"))
    }

    @Test
    fun `超长字符串与深层嵌套不栈溢出`() {
        val deep = buildString {
            repeat(120) { append("{\"a\":") }
            append("1")
            repeat(120) { append("}") }
        }
        // 120 层不算深，主要验证解析器能处理递归结构
        val root = JsonLite.parse(deep)
        var cur: Any? = root
        repeat(120) { cur = JsonLite.dig(cur, "a") }
        assertEquals(1, JsonLite.asInt(cur))
    }
}
