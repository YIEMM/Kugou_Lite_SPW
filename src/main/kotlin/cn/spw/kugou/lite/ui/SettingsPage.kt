package cn.spw.kugou.lite.ui

import java.util.Locale
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import cn.spw.kugou.lite.BuildInfo
import cn.spw.kugou.lite.diag.Diag
import cn.spw.kugou.lite.sidecar.ApiExec
import cn.spw.kugou.lite.sidecar.AudioCache
import cn.spw.kugou.lite.sidecar.AuthStore
import cn.spw.kugou.lite.sidecar.Settings
import cn.spw.kugou.lite.sidecar.StorageMover
import java.io.File
import androidx.compose.ui.draw.clip

/**
 * 设置页。
 *
 * 包含三块：
 * 1. **音质偏好** —— 选择自动/128k/320k/无损。
 *    注意酷狗音质与会员等级绑定，非会员选高音质会静默降级，
 *    所以每个档位下方标注了「通常需要会员」。
 * 2. **缓存管理** —— 显示占用并支持一键清理。
 * 3. **账号** —— 显示当前登录态，支持退出登录。
 *
 * ## 为什么音质要如实标注
 *
 * 服务端对非会员请求 flac/320 常直接返回 128k 或失败。如果 UI 按请求值
 * 显示「无损」，用户会以为拿到了高音质。所以：
 * - 选择器只表达**偏好**
 * - 实际音质由 [cn.spw.kugou.lite.sidecar.StreamInfo.qualityText] 在播放时如实反映
 */
@Composable
fun SettingsPage() {
    var quality by remember { mutableStateOf(Settings.quality) }
    var cacheText by remember { mutableStateOf("计算中…") }
    var limitText by remember { mutableStateOf<String?>(null) }
    var rateText by remember { mutableStateOf<String?>(null) }
    var moveText by remember { mutableStateOf<String?>(null) }

    // 首次进入时算一次缓存占用（可能较慢，放后台）
    androidx.compose.runtime.LaunchedEffect(Unit) {
        ApiExec.run({ r ->
            r.onSuccess { size ->
                cacheText = formatSize(size)
            }
            r.onFailure { cacheText = "统计失败" }
        }) {
            AudioCache.usage()
        }
    }

    Column(
        Modifier.fillMaxWidth().padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SaltGroupTitle("音质")

        SaltCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Settings.Quality.entries.forEach { q ->
                QualityRow(
                    quality = q,
                    selected = quality == q,
                    onClick = {
                        Settings.setQuality(q)
                        quality = q
                        Diag.info("[settings] 音质切换为 ${q.label}")
                    },
                )
            }
        }

        SaltGroupTitle("缓存")

        SaltCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        Diag.info("[settings] 前往缓存管理")
                        PageNav.push(KugouLiteTab.CacheManage)
                    }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text3("音频缓存", color = KugouTheme.colors.text)
                    Text3(
                        "已下载的歌曲与封面 · 点按可逐条管理",
                        color = KugouTheme.colors.muted,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Text3(cacheText, color = KugouTheme.colors.muted)
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        cacheText = "清理中…"
                        ApiExec.run({ r ->
                            r.onSuccess { cacheText = "0 B" }
                            r.onFailure { cacheText = "清理失败" }
                        }) {
                            AudioCache.clear()
                            AudioCache.usage()
                        }
                    }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text3("清除缓存", color = KugouTheme.colors.text)
            }
        }

        SaltGroupTitle("缓存容量与位置")

        SaltCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text3(
                    "容量上限：当前 " + Settings.cacheLimitGb + " GB",
                    color = KugouTheme.colors.text,
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(1, 2, 5, 10, 20).forEach { gb ->
                        Text3(
                            gb.toString() + "G",
                            color = if (Settings.cacheLimitGb == gb) KugouTheme.colors.accent else KugouTheme.colors.faint,
                            modifier = Modifier
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                                .clickable {
                                    Settings.setCacheLimitGb(gb)
                                    limitText = "已设为 " + gb + " GB（重启后生效）"
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
                limitText?.let {
                    Text3(it, color = KugouTheme.colors.faint, modifier = Modifier.padding(top = 6.dp))
                }
                // 自定义输入（用户需求：设置内容更加自定义——快捷档之外支持任意值）
                var showLimitInput by remember { mutableStateOf(false) }
                var limitInput by remember { mutableStateOf("") }
                Text3(
                    "自定义容量…",
                    color = KugouTheme.colors.accent,
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .clickable { showLimitInput = !showLimitInput; limitInput = "" },
                )
                if (showLimitInput) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.foundation.text.BasicTextField(
                            value = limitInput,
                            onValueChange = { limitInput = it },
                            textStyle = androidx.compose.ui.text.TextStyle(color = KugouTheme.colors.text),
                            modifier = Modifier
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                                .width(120.dp),
                            decorationBox = { inner ->
                                androidx.compose.foundation.layout.Box {
                                    if (limitInput.isEmpty()) Text3("1~100 的整数", color = KugouTheme.colors.faint)
                                    inner()
                                }
                            },
                        )
                        Text3(
                            "应用",
                            color = KugouTheme.colors.accent,
                            modifier = Modifier.clickable {
                                val v = parseCacheLimitGb(limitInput)
                                if (v == null) {
                                    limitText = "输入无效：请输入 1~100 的整数"
                                } else {
                                    Settings.setCacheLimitGb(v)
                                    limitText = "已设为 " + v + " GB（重启后生效）"
                                }
                            },
                        )
                    }
                }
                // 封面请求限流（用户需求：可在设置中调整；自定义支持任意 1~20）
                var rateInputMode by remember { mutableStateOf(false) }
                var rateInput by remember { mutableStateOf("") }
                var rateInputInv by remember { mutableStateOf("") }
                Text3(
                    "封面限流：视图内 " + Settings.coverRateVisible + " / 视图外 " + Settings.coverRateInvisible + " 每秒",
                    color = KugouTheme.colors.text,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(4, 6, 8).forEach { v ->
                        Text3(
                            "视图内 " + v,
                            color = if (Settings.coverRateVisible == v) KugouTheme.colors.accent else KugouTheme.colors.faint,
                            modifier = Modifier
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                                .clickable {
                                    Settings.setCoverRate(v, Settings.coverRateInvisible)
                                    rateText = "已设为 视图内 " + v + " / 视图外 " + Settings.coverRateInvisible
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                    Text3(
                        "自定义…",
                        color = KugouTheme.colors.accent,
                        modifier = Modifier
                            .clickable { rateInputMode = !rateInputMode; rateInput = "" }
                            .padding(vertical = 6.dp),
                    )
                }
                if (rateInputMode) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.foundation.text.BasicTextField(
                            value = rateInput,
                            onValueChange = { rateInput = it },
                            textStyle = androidx.compose.ui.text.TextStyle(color = KugouTheme.colors.text),
                            modifier = Modifier
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                                .width(60.dp),
                            decorationBox = { inner ->
                                androidx.compose.foundation.layout.Box {
                                    if (rateInput.isEmpty()) Text3("视图内 1~20", color = KugouTheme.colors.faint)
                                    inner()
                                }
                            },
                        )
                        Text3("视图外", color = KugouTheme.colors.faint, modifier = Modifier.padding(vertical = 6.dp))
                        androidx.compose.foundation.text.BasicTextField(
                            value = rateInputInv,
                            onValueChange = { rateInputInv = it },
                            textStyle = androidx.compose.ui.text.TextStyle(color = KugouTheme.colors.text),
                            modifier = Modifier
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                                .width(60.dp),
                            decorationBox = { inner ->
                                androidx.compose.foundation.layout.Box {
                                    if (rateInputInv.isEmpty()) Text3("1~20", color = KugouTheme.colors.faint)
                                    inner()
                                }
                            },
                        )
                        Text3(
                            "应用",
                            color = KugouTheme.colors.accent,
                            modifier = Modifier.clickable {
                                val vin = parseCoverRate(rateInput)
                                val vinv = parseCoverRate(rateInputInv)
                                if (vin == null || vinv == null) {
                                    rateText = "输入无效：请输入 1~20 的整数"
                                } else {
                                    Settings.setCoverRate(vin, vinv)
                                    rateText = "已设为 视图内 " + vin + " / 视图外 " + vinv
                                }
                            },
                        )
                    }
                }
                rateText?.let {
                    Text3(it, color = KugouTheme.colors.faint, modifier = Modifier.padding(top = 6.dp))
                }
                Text3(
                    "缓存位置：变更时可选迁移或删除旧数据",
                    color = KugouTheme.colors.faint,
                    modifier = Modifier.padding(top = 12.dp),
                )
                var showMove by remember { mutableStateOf(false) }
                if (showMove) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text3(
                            "迁移到默认目录",
                            color = KugouTheme.colors.accent,
                            modifier = Modifier
                                .clickable {
                                    ApiExec.run({ rr -> rr.onSuccess { showMove = false } }) {
                                        val old = File(Settings.cacheDirOverride)
                                        if (old.isDirectory) {
                                            // 按扩展名分流：音频进 audio-cache，其余（img/lrc/krc）进 cover-cache 同级目录
                                            // （歌词实际与音频同目录，迁移用「音频侧整体 move」覆盖不了 lrc——
                                            // 简化：全部迁到 dataDir/audio-cache 的兄弟结构不可行，改为按扩展名复制）
                                            val defAudio = File(Diag.dataDir, "audio-cache")
                                            val r = StorageMover.move(old, defAudio)
                                            Settings.setCacheDirOverride("")
                                            moveText = "迁移完成 " + r.moved + " 个（失败 " + r.failed + "），重启后生效"
                                        } else moveText = "旧目录不存在"
                                    }
                                }
                                .padding(vertical = 4.dp),
                        )
                        Text3(
                            "删除旧数据",
                            color = KugouTheme.colors.danger,
                            modifier = Modifier
                                .clickable {
                                    ApiExec.run({ rr -> rr.onSuccess { showMove = false } }) {
                                        StorageMover.deleteAll(File(Settings.cacheDirOverride))
                                        Settings.setCacheDirOverride("")
                                        moveText = "旧缓存已删除"
                                    }
                                }
                                .padding(vertical = 4.dp),
                        )
                    }
                }
                moveText?.let {
                    Text3(it, color = KugouTheme.colors.faint, modifier = Modifier.padding(top = 6.dp))
                }
                // 只有设置过覆盖目录时才显示「变更位置」入口（回到默认后无需再变更）
                if (Settings.cacheDirOverride.isBlank()) {
                    Text3(
                        "变更缓存位置（高级）",
                        color = KugouTheme.colors.accent,
                        modifier = Modifier
                            .clickable {
                                // 目录选择对话框依赖宿主 Swing/AWT；简化为提示用户改配置文件。
                                // TODO: 用 JFileChooser 提供图形化目录选择。
                                moveText = "请在设置文件 settings.properties 的 cacheDirOverride 填入新目录后重启"
                            }
                            .padding(top = 12.dp, bottom = 4.dp),
                    )
                }
            }
        }

        SaltGroupTitle("账号")

        SaltCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text3(
                    text = if (AuthStore.isLoggedIn) {
                        "已登录：${AuthStore.nickname ?: AuthStore.userId}"
                    } else {
                        "未登录"
                    },
                    color = KugouTheme.colors.text,
                )
                Text3(
                    text = "登录后可取流播放、读取我的歌单",
                    color = KugouTheme.colors.muted,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }

            // -------------------------------------------------- 登录 / 重新登录
            //
            // ## 为什么登录后也要有这个入口
            //
            // token 会过期（几小时后 /user/playlist 返回 20017）。过期后
            // AuthStore 里仍留着旧 token（isLoggedIn=true），唯一的重新登录
            // 入口却在发现页需要登录的区块里（要滚动才能看到），设置页反而
            // 只有「退出登录」。用户被逼着先退出再找入口，路径不通顺。
            //
            // 登录页本身不依赖登录态（扫码成功会覆盖旧 token），所以这里
            // 两种状态都放行：未登录显示「去登录」，已登录显示「重新扫码登录」。
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        Diag.info("[settings] 前往登录页")
                        PageNav.go(KugouLiteTab.Login)
                    }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text3(
                    text = if (AuthStore.isLoggedIn) "重新扫码登录" else "去登录",
                    color = KugouTheme.colors.text,
                )
            }

            if (AuthStore.isLoggedIn) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            AuthStore.clear()
                            Diag.info("[settings] 已退出登录")
                            PageNav.go(KugouLiteTab.Home)
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text3("退出登录", color = KugouTheme.colors.danger)
                }
            }
        }

        // ------------------------------------------------------ 福利（独立签到日历页）
        SaltGroupTitle("福利")

        SaltCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { PageNav.go(KugouLiteTab.Welfare) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text3("签到日历", color = KugouTheme.colors.text)
                    Text3(
                        text = "每日签到 / 听歌领取 / 广告领取 · 打卡记录",
                        color = KugouTheme.colors.muted,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Text3("›", color = KugouTheme.colors.faint)
            }
        }

        // ------------------------------------------------------ 关于（对齐骨架）
        SaltGroupTitle("关于")

        SaltCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text3("Kugou Lite", color = KugouTheme.colors.text)
                Text3(
                    text = "Salt Player 酷狗在线听歌插件 · " + BuildInfo.summary,
                    color = KugouTheme.colors.muted,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Text3(
                    text = "音频由本地 Rust 边车取流；歌词与封面写入本地缓存",
                    color = KugouTheme.colors.muted,
                    modifier = Modifier.padding(top = 6.dp),
                )
                // 构建时间单独一行：反馈问题时能确认用户装的是哪次构建
                Text3(
                    text = "构建于 " + BuildInfo.builtAt,
                    color = KugouTheme.colors.faint,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/** 音质选项行（单选样式）。 */
@Composable
private fun QualityRow(
    quality: Settings.Quality,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text3(
                text = quality.label,
                color = KugouTheme.colors.text,
            )
            Text3(
                text = quality.hint,
                color = KugouTheme.colors.muted,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        // 选中标记：用字符而不是图标，避免依赖宿主图标资源
        if (selected) {
            Text3("✓", color = KugouTheme.colors.text)
        }
    }
}

/** 字节数格式化。 */
private fun formatSize(bytes: Long): String = when {
    bytes <= 0 -> "0 B"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0)
    else -> String.format(Locale.ROOT, "%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
}
