package cn.spw.kugou.lite.sidecar

import java.io.File
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 登录 token 的对称加密（用户需求 2026-11-21：token 加密存储）。
 *
 * ## 算法与格式
 *
 * AES-256-GCM（认证加密）：密文 = base64(iv(12B) || ciphertext+tag)。
 * 每次加密生成随机 12 字节 IV，同一明文两次加密产生不同密文（防比对）。
 * GCM 的认证标签让任何密文篡改在解密时被拒绝（返回 null，不抛异常）。
 *
 * ## 密钥管理
 *
 * 32 字节随机密钥存在插件私有目录 `token.key`（首次使用时生成）。
 * 目录随插件卸载被宿主一起删除，密钥不离开本机。
 * 密钥文件损坏（长度不对）时重新生成——旧密文解不开（null），用户重新登录即可，
 * 比"用坏密钥解出垃圾 token"安全。
 *
 * ## 与 DPAPI 的取舍
 *
 * Windows DPAPI（CryptProtectData）能绑定用户，但要经 FFM 调 crypt32 并解析
 * CRYPTPROTECT 结构体，且密钥随用户配置文件迁移会失效。GCM+本机密钥文件
 * 实现简单、跨实例可解、测试友好；插件私有目录本身不在共享位置，
 * 对桌面播放器场景足够。
 */
class TokenCryptor(private val dir: File) {

    private val keyFile = File(dir, "token.key")
    private val key: SecretKeySpec? = loadOrCreateKey()

    /** 加密。返回 base64(iv || ciphertext+tag)；未初始化成功返回 null。 */
    fun encrypt(plaintext: String): String? {
        val k = key ?: return null
        return runCatching {
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, k, GCMParameterSpec(128, iv))
            val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            Base64.getEncoder().encodeToString(iv + ct)
        }.getOrNull()
    }

    /** 解密；密文格式错误或认证失败返回 null。 */
    fun decrypt(encoded: String): String? {
        val k = key ?: return null
        return runCatching {
            val all = Base64.getDecoder().decode(encoded)
            if (all.size <= 12) return null
            val iv = all.copyOfRange(0, 12)
            val ct = all.copyOfRange(12, all.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        }.getOrNull()
    }

    /** 读取或生成 32 字节密钥；损坏时重新生成。 */
    private fun loadOrCreateKey(): SecretKeySpec? = runCatching {
        dir.mkdirs()
        val bytes = if (keyFile.isFile && keyFile.length() == 32L) {
            keyFile.readBytes()
        } else {
            val fresh = ByteArray(32)
            SecureRandom().nextBytes(fresh)
            // 写入失败（权限）时返回 null：密钥都没落盘，重启后无法解密，宁可不用
            runCatching { keyFile.writeBytes(fresh) }.getOrElse { return null }
            fresh
        }
        if (bytes.size != 32) return null
        SecretKeySpec(bytes, "AES")
    }.getOrNull()
}
