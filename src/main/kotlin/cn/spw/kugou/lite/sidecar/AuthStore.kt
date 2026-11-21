package cn.spw.kugou.lite.sidecar

import cn.spw.kugou.lite.diag.Diag
import java.io.File
import java.util.Properties

/**
 * 登录态持久化。
 *
 * ## 为什么要持久化
 *
 * 边车的 token 只存在**进程内存**里。插件重启（或宿主重开）后边车进程是新的，
 * 登录态就没了 —— 每次都要用户重新扫码，这不可接受。
 *
 * 所以登录成功后立刻把 `token` / `userid` 落盘，下次启动时通过环境变量注入
 * [SidecarManager]，让它带着登录态起来。
 *
 * ## 存在哪
 *
 * `<插件数据目录>/auth.properties`。这是 **workshop 的插件私有数据目录**，
 * 不在用户文档里，卸载插件时一起清掉。
 *
 * ## ⚠️ 安全说明
 *
 * 这里存的是酷狗账号的登录 token，等同于密码。明文落盘在插件私有目录里，
 * 权限跟随宿主进程。**不要**把它打印到日志（[load] 只记录「有无」不记录值），
 * 也不要提交到版本库。
 */
object AuthStore {

    private const val TAG = "auth"
    private const val FILE_NAME = "auth.properties"
    private const val KEY_TOKEN = "token"
    private const val KEY_TOKEN_ENC = "tokenEnc"
    private const val KEY_USERID = "userid"
    private const val KEY_NICKNAME = "nickname"

    private var file: File? = null

    /**
     * token 加密器（用户需求 2026-11-21：token 加密存储）。
     *
     * token 等同于密码，早期明文落盘。现在经 AES-256-GCM 加密后存 `tokenEnc`，
     * 旧明文 `token` 键在 load 时自动迁移（读出→加密→重写→删除明文键）。
     */
    private var cryptor: TokenCryptor? = null

    @Volatile
    var token: String? = null
        private set

    @Volatile
    var userId: String? = null
        private set

    @Volatile
    var nickname: String? = null
        private set

    fun configure(dataDir: File) {
        file = File(dataDir, FILE_NAME)
        cryptor = TokenCryptor(dataDir)
        load()
    }

    val isLoggedIn: Boolean get() = !token.isNullOrBlank() && !userId.isNullOrBlank()

    private fun load() {
        val f = file ?: return
        if (!f.isFile) {
            Diag.info(TAG, "无已保存登录态")
            return
        }
        try {
            val props = Properties()
            f.inputStream().use { props.load(it) }
            userId = props.getProperty(KEY_USERID)?.takeIf { it.isNotBlank() }
            nickname = props.getProperty(KEY_NICKNAME)
            // 密文优先；旧明文 token 迁移为密文后删除明文键
            val encToken = props.getProperty(KEY_TOKEN_ENC)?.takeIf { it.isNotBlank() }
            token = if (encToken != null) {
                cryptor?.decrypt(encToken)
            } else {
                val plain = props.getProperty(KEY_TOKEN)?.takeIf { it.isNotBlank() }
                if (plain != null) {
                    migratePlainToken(f, props, plain)
                    plain
                } else null
            }
            Diag.info(TAG, "已载入登录态：登录=${isLoggedIn} 用户=${nickname ?: userId}")
        } catch (e: Exception) {
            Diag.warn(TAG, "读取登录态失败：${e.message}")
        }
    }

    /** 旧明文 token 一次性迁移为密文存储；迁移失败保留明文（下次再试），不影响本次登录态。 */
    private fun migratePlainToken(f: File, props: Properties, plain: String) {
        runCatching {
            val enc = cryptor?.encrypt(plain) ?: return
            props.remove(KEY_TOKEN)
            props.setProperty(KEY_TOKEN_ENC, enc)
            f.outputStream().use { props.store(it, "Kugou Lite login state") }
            Diag.info(TAG, "登录 token 已从明文迁移为加密存储")
        }
    }

    fun save(token: String, userId: String, nickname: String?) {
        this.token = token
        this.userId = userId
        this.nickname = nickname

        val f = file ?: run {
            Diag.warn(TAG, "未配置数据目录，登录态仅保存在内存")
            return
        }
        try {
            f.parentFile?.mkdirs()
            // token 加密存储（用户需求）：AES-256-GCM，密钥在插件私有目录 token.key
            val enc = cryptor?.encrypt(token) ?: run {
                Diag.warn(TAG, "token 加密失败，本次登录态不落盘（内存保留）")
                return
            }
            val props = Properties().apply {
                setProperty(KEY_TOKEN_ENC, enc)
                setProperty(KEY_USERID, userId)
                nickname?.let { setProperty(KEY_NICKNAME, it) }
            }
            f.outputStream().use { props.store(it, "Kugou Lite login state") }
            Diag.info(TAG, "登录态已保存（token 加密）：用户 ${nickname ?: userId}")
        } catch (e: Exception) {
            Diag.warn(TAG, "保存登录态失败：${e.message}")
        }
    }

    fun clear() {
        token = null
        userId = null
        nickname = null
        runCatching { file?.delete() }
        Diag.info(TAG, "登录态已清除")
    }
}
