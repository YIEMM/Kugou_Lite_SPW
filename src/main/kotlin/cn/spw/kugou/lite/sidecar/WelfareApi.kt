package cn.spw.kugou.lite.sidecar

import cn.spw.kugou.lite.diag.Diag
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Properties

/**
 * 福利模块（签到 / 领 VIP），参考 md3Music 的签到实现。
 *
 * ## md3 的签到流程（照搬）
 *
 * 1. 用服务器时间 `/server/now` 算 `receive_day`（本地时区可能错一天）
 * 2. `/youth/day/vip?receive_day=...` 领「畅听 VIP」（POST，body 带日期）
 * 3. `/youth/day/vip/upgrade` 升级「概念版 VIP」（POST）
 * 4. 可选：`/youth/vip` 广告上报领 VIP 小时数（一天有次数上限 30002）
 *
 * 错误码：131001/20033 今日已领取、20030 已升级过、20018 登录过期、
 * 20028 需二次验证/风控、20002 上游风控（本账号实测稳定返回）。
 *
 * ## 「自动 + 失败提示」
 *
 * [WelfareSettings.autoSignIn] 打开后，每次插件页启动会尝试一次；
 * 同一天只试一次（本地按账号隔离记录），失败把原因显示在设置页。
 *
 * ⚠️ 这些接口是酷狗「概念版/青春版」的福利，官方随时可能停用或风控。
 * 本模块只负责如实调用并展示结果，不保证一定领得到。
 */
object WelfareApi {

    private const val TAG = "welfare"
    private const val FILE_NAME = "welfare.properties"
    private const val KEY_AUTO = "autoSignIn"
    // tried = 今天已尝试过（防自动重复请求）；signed = 今天确实领到了（日历打勾）
    private const val KEY_TRIED_PREFIX = "tried_"
    private const val KEY_SIGNED_PREFIX = "signed_"
    private const val KEY_LAST_RESULT = "lastResult"

    private var file: File? = null
    @Volatile private var cache: Properties? = null

    fun configure(dataDir: File) {
        file = File(dataDir, FILE_NAME)
        cache = null
    }

    /** 自动签到开关（默认关，避免每次启动都打上游风控接口）。 */
    var autoSignIn: Boolean
        get() = props().getProperty(KEY_AUTO, "false") == "true"
        set(value) = update { it.setProperty(KEY_AUTO, value.toString()) }

    /** 上次签到结果文案（供设置页显示）。 */
    var lastResult: String
        get() = props().getProperty(KEY_LAST_RESULT, "").orEmpty()
        set(value) = update { it.setProperty(KEY_LAST_RESULT, value) }

    /** 今天是否已经尝试过签到（按账号隔离）。 */
    fun alreadyTriedToday(userId: String, day: String): Boolean =
        props().getProperty("${KEY_TRIED_PREFIX}${userId}_$day") == "1"

    /** 标记今天已尝试（无论成败，避免同一天反复打风控接口）。 */
    fun markTriedToday(userId: String, day: String) = update { p ->
        p.setProperty("${KEY_TRIED_PREFIX}${userId}_$day", "1")
        // 只保留最近 40 天的记录，避免文件无限增长
        pruneDayKeys(p, KEY_TRIED_PREFIX, 40)
    }

    // ------------------------------------------------------------ 网络

    /** 服务器时间戳（秒）；失败返回本地时间。 */
    fun serverTimestampSec(): Long {
        val json = KugouApi.getRaw("/server/now") ?: return System.currentTimeMillis() / 1000
        return JsonLite.asLong(JsonLite.dig(json, "data", "timestamp"))
            .takeIf { it > 0 } ?: (System.currentTimeMillis() / 1000)
    }

    /** 领畅听 VIP（基础签到）。@return (是否成功或已领, 文案) */
    fun claimDayVip(day: String): Pair<Boolean, String> {
        val json = KugouApi.postRaw(
            "/youth/day/vip",
            mapOf("receive_day" to day),
        ) ?: return false to "签到请求无响应，请稍后重试"
        val code = JsonLite.asInt(JsonLite.dig(json, "error_code"))
        val status = JsonLite.asInt(JsonLite.dig(json, "status"))
        Diag.info(TAG, "领畅听VIP day=$day status=$status code=$code")
        return when {
            status == 1 || code == 0 -> true to "畅听 VIP 领取成功"
            code == 131001 || code == 20033 -> true to "今日已签到，无需重复领取"
            else -> false to welfareMessage(code)
        }
    }

    /** 升级概念版 VIP（签到第二步）。 */
    fun upgradeDayVip(): Pair<Boolean, String> {
        val json = KugouApi.postRaw("/youth/day/vip/upgrade", emptyMap())
            ?: return false to "升级请求无响应"
        val code = JsonLite.asInt(JsonLite.dig(json, "error_code"))
        val status = JsonLite.asInt(JsonLite.dig(json, "status"))
        Diag.info(TAG, "升级概念版 status=$status code=$code")
        return when {
            status == 1 || code == 0 -> true to "概念版 VIP 升级成功"
            code == 20030 || code == 131001 -> true to "已升级过概念版，无需重复领取"
            else -> false to welfareMessage(code)
        }
    }

    /** 广告上报领 VIP 小时数（独立福利，有每日次数上限）。 */
    fun claimAdVip(): Pair<Boolean, String> {
        val json = KugouApi.postRaw("/youth/vip", emptyMap())
            ?: return false to "广告领取请求无响应"
        val code = JsonLite.asInt(JsonLite.dig(json, "error_code"))
        val status = JsonLite.asInt(JsonLite.dig(json, "status"))
        val hours = JsonLite.asInt(JsonLite.dig(json, "data", "award_vip_hour"))
        Diag.info(TAG, "广告领取 status=$status code=$code hours=$hours")
        return when {
            status == 1 -> true to if (hours > 0) "领取成功，获得 $hours 小时 VIP" else "领取成功"
            code == 30002 -> true to "今日领取次数已用光"
            else -> false to welfareMessage(code)
        }
    }

    /**
     * 听歌上报领 VIP（md3 的「听歌领取」按钮）。
     *
     * 端点 `/youth/listen/song`，Rust 里对应 youth.rs 的 `handle_listen_song`。
     * `130012` = 今日已领取，视为成功。
     */
    fun claimListenSong(): Pair<Boolean, String> {
        val json = KugouApi.postRaw("/youth/listen/song", emptyMap())
            ?: return false to "听歌领取请求无响应"
        val code = JsonLite.asInt(JsonLite.dig(json, "error_code"))
        val status = JsonLite.asInt(JsonLite.dig(json, "status"))
        Diag.info(TAG, "听歌领取 status=$status code=$code")
        return when {
            status == 1 -> true to "听歌领取成功"
            code == 130012 -> true to "今日已领取"
            else -> false to welfareMessage(code)
        }
    }

    /**
     * 本地已签日期集合（yyyy-MM-dd）。
     *
     * 服务端 `/youth/month/vip/record` 在部分账号返回 20002（风控），
     * 所以日历以本地记录为准 —— 与 md3 的「本地兜底」一致。
     */
    fun signedDays(userId: String): Set<String> {
        val prefix = "${KEY_SIGNED_PREFIX}${userId}_"
        return props().stringPropertyNames()
            .filter { it.startsWith(prefix) && props().getProperty(it) == "1" }
            .map { it.removePrefix(prefix) }
            .toSet()
    }

    /**
     * 标记某天签到**成功**（日历打勾用）。
     *
     * 与 [markTriedToday] 区分：try 是「今天试过一次」（防重复请求），
     * success 是「确实领到了」（打卡日历）。当前实现合并成一条记录：
     * 签到成功时才写入打勾；失败只写 tried 不入 signedDays。
     */
    fun markSigned(userId: String, day: String) = update { p ->
        p.setProperty("${KEY_SIGNED_PREFIX}${userId}_$day", "1")
        // signed_ 键同样只增不减 —— 一年下来 365 条，文件无限膨胀（N17）。
        // 与 tried_ 同策略：按日期保留最近 60 天（日历一屏最多两个月）。
        pruneDayKeys(p, KEY_SIGNED_PREFIX, 60)
    }

    /** 完整签到：领畅听 → 升级概念版。第一步失败（非已领）就不做第二步。 */
    fun signIn(day: String): Pair<Boolean, String> {
        val (claimOk, claimMsg) = claimDayVip(day)
        if (!claimOk) return false to claimMsg
        val (upgradeOk, upgradeMsg) = upgradeDayVip()
        return if (upgradeOk) {
            // 成功才在日历打勾；失败只算「已尝试」
            markSigned(AuthStore.userId.orEmpty(), day)
            true to "签到成功（概念版会员）"
        } else {
            false to "畅听 VIP 已领取，但升级概念版失败：$upgradeMsg"
        }
    }

    // ------------------------------------------------------------ 存储

    /**
     * 存储锁（N17）。
     *
     * 早期 `props()` 只在 `cache` 为空时读文件，而 `cache` 只有 `@Volatile`
     * 没有 synchronized：首次并发调用会各自 load 出一个 Properties 实例，
     * 后写的把先写的整个覆盖 —— 读-改-写丢更新。现在所有读写都持这把锁。
     */
    private val lock = Any()

    /** 读缓存（持锁；首次从文件加载）。 */
    private fun props(): Properties = synchronized(lock) { propsLocked() }

    /** 必须已持 [lock] 才能调用。 */
    private fun propsLocked(): Properties {
        cache?.let { return it }
        val p = Properties()
        val f = file
        if (f != null && f.isFile) {
            runCatching { f.inputStream().use { p.load(it) } }
                .onFailure { Diag.warn(TAG, "读取福利配置失败：${it.message}") }
        }
        cache = p
        return p
    }

    /** 写文件 + 更新缓存（持锁）。 */
    private fun save(p: Properties) = synchronized(lock) { saveLocked(p) }

    /** 必须已持 [lock] 才能调用。 */
    private fun saveLocked(p: Properties) {
        cache = p
        val f = file ?: return
        runCatching {
            f.parentFile?.mkdirs()
            f.outputStream().use { p.store(it, "Kugou Lite welfare settings") }
        }.onFailure { Diag.warn(TAG, "保存福利配置失败：${it.message}") }
    }

    /**
     * 原子读-改-写（N17）：整个「读出 → 修改 → 落盘」在一个临界区里完成，
     * 并发调用不会互相覆盖。
     */
    private inline fun update(block: (Properties) -> Unit) {
        synchronized(lock) {
            val p = propsLocked()
            block(p)
            saveLocked(p)
        }
    }
}

// ---------------------------------------------------------------- 纯逻辑（单测）

/** 本地已签记录的键（按账号隔离，与 md3 的 `settings_signed_days_$userid` 同思路）。 */
fun signedDaysKey(userId: String): String = "welfare_signed_$userId"

/**
 * 按键尾部日期清理带日期的键，保留最新 [keep] 条（N17）。
 *
 * 键形如 `prefix_userId_yyyy-MM-dd`。清理规则：取每个键尾部的日期串
 * （最后一个 `_` 之后的部分）按字典序排序（ISO 日期字典序 = 时间序），
 * 从最旧开始删，直到剩余 [keep] 条。
 *
 * 早期只有 `tried_` 走这个逻辑（而且用的是「键名排序后按数量截断」，
 * 多账号混用时可能误删新账号记录）；`signed_` 则完全没有清理，
 * 一年积累 365 条让 welfare.properties 无限膨胀。
 *
 * 抽成纯函数便于单测（N06N16N17FixTest）。
 */
internal fun pruneDayKeys(p: Properties, prefix: String, keep: Int) {
    if (keep <= 0) return
    val keys = p.stringPropertyNames().filter { it.startsWith(prefix) }
    if (keys.size <= keep) return
    val sorted = keys.sortedBy { it.substringAfterLast('_') }
    sorted.take(keys.size - keep).forEach { p.remove(it) }
}

/** 服务器时间戳 → `YYYY-MM-DD`（按给定时区偏移，默认 +8）。 */
fun receiveDayFrom(timestampSec: Long, zoneOffsetHours: Int = 8): String =
    // Locale.ROOT（N19）：默认 Locale 下部分语言会用非 ASCII 数字，
    // 而签到接口要求严格的 yyyy-MM-dd。
    DateTimeFormatter.ofPattern("yyyy-MM-dd", java.util.Locale.ROOT)
        .withZone(ZoneOffset.ofHours(zoneOffsetHours))
        .format(Instant.ofEpochSecond(timestampSec))

/** 福利接口错误码 → 中文提示（对照 md3 的 `_mapYouthVipError`）。 */
fun welfareMessage(code: Int): String = when (code) {
    131001, 20033 -> "今日已签到，无需重复领取"
    20030 -> "已升级过概念版，无需重复领取"
    20006 -> "签名错误，请重新登录后重试"
    20010 -> "参数错误（领取日期格式有误）"
    20018 -> "登录已过期，请重新扫码登录"
    20028 -> "酷狗拒绝领取：账号可能不符合资格，或该功能已停用"
    20034 -> "领取失败：领取次数已达上限"
    // 实测本账号稳定返回 20002，上游风控/网关拒绝，给明确可操作的提示
    20002 -> "酷狗拒绝领取（账号风控或功能已停用），可稍后重试或前往手机端领取"
    else -> "领取失败（错误码：$code）"
}
