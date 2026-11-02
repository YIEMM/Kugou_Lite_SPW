package cn.spw.kugou.lite.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.spw.kugou.lite.sidecar.ApiExec
import cn.spw.kugou.lite.sidecar.Async
import cn.spw.kugou.lite.sidecar.KugouApi
import cn.spw.kugou.lite.sidecar.SearchResult
import cn.spw.kugou.lite.sidecar.Song
import cn.spw.kugou.lite.sidecar.rememberAsyncState

/**
 * 搜索页。
 *
 * ## 为什么用 `BasicTextField` 而不是宿主原生输入框
 *
 * 宿主（Salt / Voxzen）**没有暴露可复用的输入框原生组件** —— 已用
 * `list_markers.py` 扫描过全部 42147 个 class，`TextField` 相关的
 * `com.moriafly.salt.ui.*` 组件只有展示型（Text / Card / Item / Switch 等），
 * 没有文本输入。因此这里退回 Compose Foundation 的 `BasicTextField` 自绘外观，
 * 其余所有容器与按钮仍用宿主原生控件。
 *
 * ## 搜索不自动触发
 *
 * 只在用户按「搜索」或回车时发请求。一边打字一边请求会打爆第三方接口
 * （它有 2 分钟缓存和频率限制），也会让 UI 抖动。
 */
@Composable
fun SearchPage(onPick: (Song) -> Unit) {
    var keyword by remember { mutableStateOf("") }
    val state = rememberAsyncState<SearchResult?>()

    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        SaltGroupTitle("搜索酷狗")
        SaltCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                SearchBox(
                    value = keyword,
                    onValueChange = { keyword = it },
                    onSubmit = { if (keyword.isNotBlank()) state.load { KugouApi.search(keyword) } },
                )
                SaltText(
                    "输入歌名或歌手后回车",
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        when (val s = state.value) {
            is Async.Idle -> Unit
            is Async.Loading -> StatusCard("正在搜索…")
            is Async.Failure -> StatusCard("搜索失败：${s.message}")
            is Async.Success -> {
                val result = s.value
                if (result == null) {
                    StatusCard(
                        if (KugouApi.lastNeedsLogin) "需要先登录酷狗账号（「账户」页扫码）"
                        else "搜索失败：${KugouApi.lastError ?: "未知错误"}"
                    )
                } else if (result.songs.isEmpty()) {
                    StatusCard("没有找到「$keyword」相关的歌曲")
                } else {
                    SaltGroupTitle("找到 ${result.total} 首")
                    SaltCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        LazyColumn(Modifier.fillMaxWidth().height(360.dp)) {
                            items(result.songs) { song ->
                                SongRow(song = song, onClick = { onPick(song) })
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 自绘搜索框。 */
@Composable
private fun SearchBox(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(38.dp)
            .background(Color(0xFF2A2A2A), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = Color(0xFFEDEDED), fontSize = 14.sp),
            cursorBrush = SolidColor(Color(0xFF4CC2FF)),
            modifier = Modifier.weight(1f),
        )
        Text3(
            text = "搜索",
            color = Color(0xFF4CC2FF),
            modifier = Modifier.clickable { onSubmit() },
        )
    }
}

/** 状态提示卡片。 */
@Composable
private fun StatusCard(message: String) {
    SaltCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text3(text = message, color = Color(0xFF9A9A9A))
        }
    }
}

/**
 * 自绘文本。
 *
 * 用 Compose 的 `Text` 是因为这几处需要自定义颜色/字号，而宿主的
 * `SaltText` 只暴露 text/modifier/maxLines/overflow（不带 style），
 * 无法表达「次要说明文字用暗一点的颜色」这种层级。
 */
@Composable
internal fun Text3(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.text.BasicText(
        text = text,
        modifier = modifier,
        style = TextStyle(color = color, fontSize = 14.sp),
    )
}
