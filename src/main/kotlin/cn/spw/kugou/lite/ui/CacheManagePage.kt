package cn.spw.kugou.lite.ui

import java.util.Locale
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.spw.kugou.lite.diag.Diag
import cn.spw.kugou.lite.sidecar.ApiExec
import cn.spw.kugou.lite.sidecar.Async
import cn.spw.kugou.lite.sidecar.AudioCache
import cn.spw.kugou.lite.sidecar.CacheEntry
import cn.spw.kugou.lite.sidecar.rememberAsyncState
import java.io.File

/**
 * 缓存管理页（B1）。
 *
 * ## 为什么要单独一页
 *
 * 设置页原本只有「占用大小 + 一键清空」。用户反馈：清缓存时歌词跟着没了、
 * 想只删某一首都做不到。本页提供：
 *
 * - **逐条列出**：音质、大小、最后修改时间（越新越靠前）；
 * - **单曲删除**：音频 + 随附歌词一起删（两步确认，防误触）；
 * - **清空全部**：与设置页的清除等效。
 *
 * 文件名是净化后的 hash（酷狗取流键），对用户不直观但如实展示前 8 位；
 * 音质与大小才是选删依据。
 */
@Composable
fun CacheManagePage() {
    val state = rememberAsyncState<List<CacheEntry>>()
    var reload by remember { mutableStateOf(0) }
    /** 等待二次确认删除的条目（null = 无）。点「删除」→ 变「确认？」→ 再点生效。 */
    var pendingDelete by remember { mutableStateOf<CacheEntry?>(null) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(reload) { state.load { AudioCache.entries() } }

    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        notice?.let {
            SaltCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Text3(it, color = KugouTheme.colors.text)
            }
        }

        when (val s = state.value) {
            is Async.Idle,
            is Async.Loading -> StateHint("正在扫描缓存…")

            is Async.Failure -> StateHint("扫描失败：${s.message}")

            is Async.Success -> {
                val entries = s.value
                val total = entries.sumOf { it.totalBytes }

                // ----------------------------------------------- 统计 + 清空
                SaltCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text3(
                                "${entries.size} 条缓存 · ${formatCacheSize(total)}",
                                color = KugouTheme.colors.text,
                            )
                            Text3(
                                "删除单曲会连同它的歌词一起删除",
                                color = KugouTheme.colors.muted,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        if (entries.isNotEmpty()) {
                            Text3(
                                text = if (busy) "清理中…" else "清空全部",
                                color = KugouTheme.colors.danger,
                                modifier = Modifier.clickable(enabled = !busy) {
                                    busy = true
                                    ApiExec.run({ r ->
                                        r.onSuccess {
                                            busy = false
                                            notice = "缓存已清空"
                                            reload++
                                        }
                                        r.onFailure {
                                            busy = false
                                            notice = "清理失败：${it.message}"
                                        }
                                    }) {
                                        AudioCache.clear()
                                        AudioCache.usage()
                                    }
                                },
                            )
                        }
                    }
                }

                if (entries.isEmpty()) {
                    StateHint("缓存为空：播放过的歌曲会先下载到本地")
                } else {
                    // ------------------------------------------- 条目列表
                    entries.forEach { entry ->
                        val confirming = pendingDelete?.audio == entry.audio
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !busy) {
                                    if (confirming) {
                                        // 二次确认 → 真删
                                        pendingDelete = null
                                        busy = true
                                        val target = entry.audio
                                        ApiExec.run({ r ->
                                            r.onSuccess { ok ->
                                                busy = false
                                                notice = if (ok == true) {
                                                    Diag.info("[cache-mgr] 已删除 ${target.name}")
                                                    "已删除 ${cacheLabel(entry)}"
                                                } else "删除失败"
                                                reload++
                                            }
                                            r.onFailure {
                                                busy = false
                                                notice = "删除失败：${it.message}"
                                            }
                                        }) {
                                            AudioCache.deleteEntry(target)
                                        }
                                    } else {
                                        pendingDelete = entry
                                    }
                                }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text3(
                                    cacheLabel(entry),
                                    color = KugouTheme.colors.text,
                                )
                                Text3(
                                    "${entry.ext.uppercase()} · ${formatCacheSize(entry.totalBytes)} · ${formatAge(entry.audio)}",
                                    color = KugouTheme.colors.muted,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                            Text3(
                                text = if (confirming) "确认删除？" else "删除",
                                color = if (confirming) KugouTheme.colors.danger else KugouTheme.colors.muted,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 条目主标题：音质 + hash 前缀（hash 对用户是 opaque 的，如实展示短前缀）。 */
private fun cacheLabel(entry: CacheEntry): String {
    val stem = entry.audio.nameWithoutExtension
    val hash = stem.substringBeforeLast('-', stem)
    val prefix = hash.take(8) + if (hash.length > 8) "…" else ""
    return when (entry.quality) {
        "" -> "缓存 $prefix"
        else -> "缓存 $prefix（${entry.quality}）"
    }
}

/** 字节数的人类可读形式。 */
private fun formatCacheSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format(Locale.ROOT, "%.2f GB", bytes / 1024.0 / 1024 / 1024)
    bytes >= 1024L * 1024 -> String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024)
    bytes >= 1024L -> String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

/** 距最后修改的粗略时长。 */
private fun formatAge(file: File): String {
    val ageMs = System.currentTimeMillis() - file.lastModified()
    val min = ageMs / 60_000
    return when {
        min < 1 -> "刚刚"
        min < 60 -> "$min 分钟前"
        min < 60 * 24 -> "${min / 60} 小时前"
        else -> "${min / 60 / 24} 天前"
    }
}
