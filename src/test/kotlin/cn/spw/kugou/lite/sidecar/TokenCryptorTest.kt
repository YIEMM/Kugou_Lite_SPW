package cn.spw.kugou.lite.sidecar

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * TokenCryptor 的加解密往返与文件格式测试（用户需求：token 加密存储）。
 */
class TokenCryptorTest {

    /** 加密再解密还原原文。 */
    @Test
    fun roundtripRestoresPlaintext(@TempDir dir: File) {
        val c = TokenCryptor(dir)
        val secret = "kugou-token-abc-123-中文-🔥"
        val enc = c.encrypt(secret)!!
        assertNotEquals(secret, enc)
        assertEquals(secret, c.decrypt(enc))
    }

    /** 密文不含明文片段（抽样检查）。 */
    @Test
    fun ciphertextHidesPlaintext(@TempDir dir: File) {
        val c = TokenCryptor(dir)
        val enc = c.encrypt("SUPER-SECRET-TOKEN")!!
        assertFalse(enc.contains("SUPER"))
        assertFalse(enc.contains("SECRET"))
    }

    /** 每次加密用不同随机 IV，密文不可比。 */
    @Test
    fun differentIvEachTime(@TempDir dir: File) {
        val c = TokenCryptor(dir)
        val e1 = c.encrypt("same-input")!!
        val e2 = c.encrypt("same-input")!!
        assertNotEquals(e1, e2)
        assertEquals("same-input", c.decrypt(e1))
        assertEquals("same-input", c.decrypt(e2))
    }

    /** 密钥文件只生成一次，重复使用同一把。 */
    @Test
    fun keyFileStableAcrossInstances(@TempDir dir: File) {
        val c1 = TokenCryptor(dir)
        val enc = c1.encrypt("persisted")!!
        val c2 = TokenCryptor(dir)
        assertEquals("persisted", c2.decrypt(enc))
        val keyFile = File(dir, "token.key")
        assertTrue(keyFile.isFile)
        assertEquals(32, keyFile.length())
    }

    /** 密钥文件损坏时重新生成，旧密文解不开返回 null（不抛异常）。 */
    @Test
    fun corruptKeyYieldsNull(@TempDir dir: File) {
        val c1 = TokenCryptor(dir)
        val enc = c1.encrypt("will-fail")
        File(dir, "token.key").writeBytes(ByteArray(32))
        val c2 = TokenCryptor(dir)
        assertEquals(null, c2.decrypt(enc!!))
    }

    /** 篡改密文解密失败返回 null（GCM 认证拒绝）。 */
    @Test
    fun tamperedCiphertextRejected(@TempDir dir: File) {
        val c = TokenCryptor(dir)
        val enc = c.encrypt("integrity-matters")!!
        val bytes = java.util.Base64.getDecoder().decode(enc)
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x01).toByte()
        assertEquals(null, c.decrypt(java.util.Base64.getEncoder().encodeToString(bytes)))
    }
}
