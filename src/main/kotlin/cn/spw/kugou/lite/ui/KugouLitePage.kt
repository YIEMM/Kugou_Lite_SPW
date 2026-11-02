package cn.spw.kugou.lite.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.spw.kugou.lite.diag.Diag

/**
 * 酷狗 Lite 页面 —— 渲染在**宿主导航内部**，不是浮层。
 *
 * <p>结构完全由宿主原生 Salt 组件组成（[SaltScreen] / [SaltGroupTitle] / [SaltCard] /
 * [SaltItem] / [SaltText]），因此外观、间距、字号、明暗主题都与宿主自己的二级页面
 * （如「设置 → 关于」）一致：</p>
 *
 * ```
 * SaltScreen（宿主页框：标题、返回、滚动容器、内边距）
 *   └ SaltGroupTitle「搜索与发现」
 *   └ SaltCard
 *       └ SaltItem「搜索歌曲」
 *       └ SaltItem「每日推荐」
 *   └ SaltGroupTitle「我的音乐」
 *   └ SaltCard
 *       └ SaltItem「我的歌单」
 *       └ SaltItem「排行榜」
 *   └ SaltGroupTitle「关于」
 *   └ SaltCard
 *       └ SaltText（版本与阶段说明）
 * ```
 *
 * <p>**为什么不用 `LazyColumn`**：宿主 `BasicScreen` 内部已经提供滚动容器，
 * 页面内容只需普通 `Column`；再嵌一层 `LazyColumn` 会出现双层滚动，
 * 且 `LazyColumn` 需要有限高度约束，在 `BasicScreen` 的滚动上下文里容易测量失败。
 * P2 内容量很小，`Column` 足够。</p>
 *
 * <p>**接收者类型**：`SaltScreen` 的内容是 `ColumnScope.() -> Unit`
 * （对应宿主 `Function3`），`SaltCard` 的内容也是 `ColumnScope.() -> Unit`
 * （对应宿主 `Function3`）。显式写出接收者，省略会让 Kotlin 推断出错误的函数类型，
 * 与宿主 JVM 签名不匹配，真机上会 `ClassCastException` 崩掉宿主进程。</p>
 *
 * <p>**不要 `fillMaxSize()`**：外框自带滚动容器，内容高度必须由内容自己决定。
 * 撑满视口会把高度钉死、滚动范围变成 0，页面就滚不动了（真机踩过）。</p>
 *
 * <p>**底部要留 16dp**：外框给的垂直内边距只补偿迷你播放条高度，不含内容末尾的
 * 呼吸空间。缺了它，滚动到底时最后一张卡片会紧贴播放栏 —— 宿主页面有约 9px 间隙，
 * 插件为 0，视觉上明显突兀。</p>
 *
 * <p>**P2 阶段说明**：本阶段只交付「导航内页面骨架 + 标题栏入口」。
 * 列表项目前是占位项（点击只写日志，不发起网络请求）。
 * P3 接入搜索/歌单/榜单/每日推荐，P4 接点歌播放，P5 接歌词。</p>
 */
@Composable
fun KugouLitePage() {
    // onBackClick 传 null：走 PageState.requestBack() → 宿主导航栈 back()。
    // 返回键本身由 SaltScreen 渲染成宿主原生的 BasicScreenBackButton。
    SaltScreen(title = "Kugou Lite") {
        // ColumnScope 接收者由 SaltScreen 提供。
        //
        // **不要 fillMaxSize()** —— 外框的滚动容器要求内容高度由内容自己决定；
        // 撑满视口会把高度钉死，滚动范围变成 0，页面就滚不动了。
        //
        // **外层不加水平 padding** —— `ItemOuterTitle`（分组标题）自带 16dp 水平内边距，
        // 而 `Card` 不带。如果外层统一加 16dp，分组标题会变成 32dp、比卡片内文字多缩进
        // 16px（真机实测 270 vs 254，宿主是 254 vs 253）。
        //
        // 正确做法：外层只管底部留白，水平留白由各组件自己负责 ——
        //   - Card 加 padding(horizontal = 16.dp)
        //   - ItemOuterTitle 自带 16dp
        // 两者缩进量一致，分组标题才会与卡片内文字左对齐。
        //
        // bottom 16dp：外框的垂直内边距只补偿迷你播放条高度，不含内容末尾呼吸空间；
        // 缺了它滚到底时最后一张卡片会紧贴播放栏。
        Column(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SaltGroupTitle("搜索与发现")
            SaltCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                // ColumnScope 接收者：宿主 Card 的 content 是 Function3<ColumnScope,...>。
                SaltItem(
                    title = "搜索歌曲",
                    subtitle = "按歌名、歌手搜索在线曲库",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { Diag.info("[page] 点击「搜索歌曲」（P3 接入）") },
                )
                SaltItem(
                    title = "每日推荐",
                    subtitle = "每天更新的推荐歌单",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { Diag.info("[page] 点击「每日推荐」（P3 接入）") },
                )
            }

            SaltGroupTitle("我的音乐")
            SaltCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                SaltItem(
                    title = "我的歌单",
                    subtitle = "酷狗账号里的自建歌单与收藏",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { Diag.info("[page] 点击「我的歌单」（P3 接入）") },
                )
                SaltItem(
                    title = "排行榜",
                    subtitle = "飙升榜、热歌榜等官方榜单",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { Diag.info("[page] 点击「排行榜」（P3 接入）") },
                )
            }

            SaltGroupTitle("关于")
            SaltCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    SaltText("Kugou Lite")
                    SaltText(
                        "P2 阶段：页面已渲染在宿主导航内部，入口为标题栏右侧按钮。",
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    SaltText(
                        "P3 将接入搜索、歌单、榜单与每日推荐；P4 接点歌播放；P5 接歌词。",
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}
