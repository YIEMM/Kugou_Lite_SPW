package cn.spw.kugou.lite.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 关注状态本地持久化的纯逻辑测试（整分支审查 Important #9）。
 *
 * 服务端 `user_status` 不随 follow/unfollow 变化（裁决 R3），所以本地必须记住
 * 用户点过什么，否则重进歌手页按钮永远显示「＋ 关注」。
 */
class FollowStoreTest {

    @Test
    fun `加入与移除`() {
        val s = FollowStore.from("1,2,3")
        assertTrue(s.contains("2"))
        assertEquals("1,3", FollowStore.to(s - "2"))
    }

    @Test
    fun `空值与空白被忽略`() {
        val s = FollowStore.from("")
        assertTrue(s.isEmpty())
        assertTrue(FollowStore.from(" , , ").isEmpty())
    }

    @Test
    fun `往返序列化保持成员集合`() {
        val s = FollowStore.from("83837,84673")
        assertEquals(2, s.size)
        assertEquals(s, FollowStore.from(FollowStore.to(s)))
    }

    @Test
    fun `重复项去重`() {
        val s = FollowStore.from("1,1,2")
        assertEquals(2, s.size)
    }
}
