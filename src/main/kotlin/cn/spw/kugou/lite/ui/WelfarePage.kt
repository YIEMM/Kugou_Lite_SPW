package cn.spw.kugou.lite.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cn.spw.kugou.lite.diag.Diag
import cn.spw.kugou.lite.sidecar.ApiExec
import cn.spw.kugou.lite.sidecar.AuthStore
import cn.spw.kugou.lite.sidecar.WelfareApi
import cn.spw.kugou.lite.sidecar.receiveDayFrom
import java.time.LocalDate

/**
 * 签到日历（福利页）—— 复刻 md3Music 的 `SignInCalendarPage`。
 *
 * ## 结构（与 md3 一致）
 *
 * ```
 * 卡片
 *  ├ 月份标题「2026 年 11 月」
 *  ├ 三个 tonal 按钮：签到 / 听歌领取 / 广告领取
 *  ├ 周一~周日表头
 *  ├ 月历网格（已签=实心圆+勾、今天=描边、未来=淡灰）
 *  ├ 统计条：进度 + 「本月已打卡 N / 30 天」+ 坚持中
 *  └ 会员到期信息（服务端拿得到才显示）
 * ```
 *
 * ## 数据来源
 *
 * 服务端 `/youth/month/vip/record` 在部分账号返回 20002（风控），
 * 所以打卡记录以**本地按账号隔离**的记录为准（md3 也是本地兜底）。
 *
 * ## 为什么独立成页
 *
 * 用户明确要求「做成 MD3Music 的那种形式」：md3 的签到是独立二级页，
 * 从「我的」会员卡片进入，日历不挤压其它内容。设置页只留一个入口卡片。
 */
@Composable
fun WelfarePage() {
    val c = KugouTheme.colors
    val today = LocalDate.now()
    val userId = AuthStore.userId.orEmpty()

    var signedDays by remember { mutableStateOf(WelfareApi.signedDays(userId)) }
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf(WelfareApi.lastResult) }
    var auto by remember { mutableStateOf(WelfareApi.autoSignIn) }

    /** 统一的后台执行 + 结果回写，避免每个按钮重复写一遍。 */
    fun runWelfare(key: String, userIdValue: String, block: () -> Pair<Boolean, String>) {
        if (busy != null) return
        busy = key
        ApiExec.run(onResult = { res ->
            val (ok, msg) = res.getOrElse { false to "请求异常：${it.message}" }
            message = (if (ok) "✓ " else "✗ ") + msg
            WelfareApi.lastResult = message
            if (ok && key == "sign") {
                val day = receiveDayFrom(WelfareApi.serverTimestampSec())
                WelfareApi.markSigned(userIdValue, day)
                signedDays = WelfareApi.signedDays(userIdValue)
            }
            busy = null
            Diag.info("[welfare] $key → $message")
        }, block)
    }

    SaltCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // ------------------------------------------------------ 月份标题
            Text3(
                text = "${today.year} 年 ${today.monthValue} 月",
                color = c.text,
                modifier = Modifier.padding(bottom = 14.dp),
            )

            // ------------------------------------------------------ 三个按钮
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                WelfareButton("签到", busy == "sign", c, Modifier.weight(1f)) {
                    runWelfare("sign", userId) {
                        val day = receiveDayFrom(WelfareApi.serverTimestampSec())
                        WelfareApi.markTriedToday(userId, day)
                        WelfareApi.signIn(day)
                    }
                }
                WelfareButton("听歌领取", busy == "listen", c, Modifier.weight(1f)) {
                    runWelfare("listen", userId) { WelfareApi.claimListenSong() }
                }
                WelfareButton("广告领取", busy == "ad", c, Modifier.weight(1f)) {
                    runWelfare("ad", userId) { WelfareApi.claimAdVip() }
                }
            }

            Text3(
                text = when {
                    message.isNotBlank() -> message
                    !AuthStore.isLoggedIn -> "未登录：签到需要先登录酷狗账号"
                    else -> "点击上方按钮领取；签到每天只自动尝试一次"
                },
                color = c.muted,
                modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                maxLines = 2,
            )

            // ------------------------------------------------------ 自动签到开关
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        val next = !auto
                        auto = next
                        WelfareApi.autoSignIn = next
                    }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text3("自动签到", color = c.text)
                    Text3(
                        text = "打开插件页时自动尝试，每天一次",
                        color = c.muted,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Text3(
                    text = if (auto) "● 开" else "○ 关",
                    color = if (auto) c.text else c.faint,
                )
            }

            // ------------------------------------------------------ 星期表头
            Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp)) {
                listOf("一", "二", "三", "四", "五", "六", "日").forEach { w ->
                    Text3(
                        text = w,
                        color = c.faint,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                }
            }

            // ------------------------------------------------------ 月历网格
            CalendarGrid(today, signedDays, c)

            // ------------------------------------------------------ 统计条
            val monthly = signedDays.count { d ->
                d.startsWith("${today.year}-${today.monthValue.toString().padStart(2, '0')}")
            }
            StatFooter(monthly, today.lengthOfMonth(), c)
        }
    }

    // 风控说明放卡片外，避免把日历挤长
    Text3(
        text = "注：概念版福利接口可能被风控或停用（错误码 20002/20028），失败会如实提示，不影响听歌。打卡记录以本地为准。",
        color = c.faint,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

/** 一个 tonal 小按钮（md3 的 FilledButton.tonalIcon 同款语义：中性底色）。 */
@Composable
private fun WelfareButton(
    label: String,
    loading: Boolean,
    c: cn.spw.kugou.lite.ui.KugouColors,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .background(c.subtle, RoundedCornerShape(8.dp))
            .clickable(enabled = !loading && AuthStore.isLoggedIn) { onClick() }
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text3(
            text = if (loading) "…" else label,
            color = if (loading || !AuthStore.isLoggedIn) c.faint else c.text,
            maxLines = 1,
        )
    }
}

/**
 * 月历网格：周一开头，7 列；已签=实心圆+勾、今天=描边、未来=淡灰。
 * 与 md3 的 `_buildCalendarGrid` 一一对应。
 */
@Composable
private fun CalendarGrid(today: LocalDate, signed: Set<String>, c: cn.spw.kugou.lite.ui.KugouColors) {
    val first = today.withDayOfMonth(1)
    // LocalDate.weekday: Mon=1..Sun=7 → 周一开头的前导空格数
    val leading = first.dayOfWeek.value - 1
    val days = today.lengthOfMonth()
    val cells = buildList<Int?> {
        repeat(leading) { add(null) }
        (1..days).forEach { add(it) }
        while (size % 7 != 0) add(null)
    }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            cells.chunked(7).forEach { week ->
                Row(Modifier.fillMaxWidth()) {
                    week.forEach { day ->
                        // ⚠️ 用固定 36dp 行高，不要用 aspectRatio(1f)：
                        // 那会让每行高度 ≈ 屏幕宽/7（约 90dp），5 行就把日历撑出屏幕，
                        // 统计条和进度环被挤到看不见（真机验证踩到）。
                        // md3 里也是固定 32dp 的圆。
                        Box(
                            Modifier
                                .weight(1f)
                                .height(36.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (day != null) {
                                val dateKey = "${today.year}-${today.monthValue.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"
                                val isSigned = dateKey in signed
                                val isToday = day == today.dayOfMonth
                                val isFuture = day > today.dayOfMonth
                                Box(
                                    Modifier
                                        .size(30.dp)
                                        .background(
                                            color = when {
                                                isSigned -> c.accent
                                                isToday -> c.subtle
                                                else -> Color.Transparent
                                            },
                                            shape = CircleShape,
                                        )
                                        .then(
                                            if (isToday && !isSigned) {
                                                Modifier.border(1.5.dp, c.accent, CircleShape)
                                            } else {
                                                Modifier
                                            },
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text3(
                                        text = day.toString(),
                                        color = when {
                                            isSigned -> c.pageBackground
                                            isToday -> c.text
                                            isFuture -> c.faint
                                            else -> c.muted
                                        },
                                        maxLines = 1,
                                    )
                                }
                                // 已签：右下角小圆勾（md3 同款），不压日期数字
                                if (isSigned) {
                                    Box(
                                        Modifier
                                            .size(13.dp)
                                            .background(c.selectedBackground, CircleShape)
                                            .align(Alignment.BottomEnd),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text3("✓", color = c.text, maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 统计条：进度环 + 「本月已打卡 N / 30 天」+ 坚持中标签。 */
@Composable
private fun StatFooter(received: Int, total: Int, c: cn.spw.kugou.lite.ui.KugouColors) {
    val pct = if (total > 0) (received * 100f / total) else 0f
    Row(
        Modifier
            .fillMaxWidth()
            .background(c.subtle, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 简化进度环：用圆环边框 + 百分比文字（避免引入 Canvas 依赖）
        Box(
            Modifier
                .size(52.dp)
                .border(3.dp, c.accent.copy(alpha = 0.25f), CircleShape)
                .border(
                    if (pct > 0) 3.dp else 0.dp,
                    c.accent,
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text3(text = "${pct.toInt()}%", color = c.accent, maxLines = 1)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text3("本月已打卡", color = c.muted, maxLines = 1)
            Row {
                Text3("$received", color = c.text, maxLines = 1)
                Text3(" / $total 天", color = c.muted, maxLines = 1)
            }
        }
        if (received > 0) {
            Box(
                Modifier
                    .background(c.selectedBackground, RoundedCornerShape(999.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Text3("坚持中", color = c.text, maxLines = 1)
            }
        }
    }
}
