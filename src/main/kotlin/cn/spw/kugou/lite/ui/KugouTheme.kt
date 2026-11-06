package cn.spw.kugou.lite.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 主题层：全部配色集中在这里，页面不再硬编码颜色。
 *
 * ## 为什么必须做这一层
 *
 * 早期页面把颜色写死成 `Color(0xFF2A2A2A)` 这类深色值。后果：
 * - 宿主切到**浅色主题**时，深色背景上的深色文字完全看不清
 * - 想调一处配色要改十几个文件
 *
 * 现在统一成一套**语义化 Token**：页面只说「这是次要文字」，
 * 具体颜色由主题决定。
 *
 * ## 深色/浅色判定顺序
 *
 * 1. 宿主显式设置（`AppConfig.isDarkTheme`）—— 用户选了深色就是深色
 * 2. 宿主未设置时回退到系统主题（skiko 的 `currentSystemTheme`）
 *
 * 这个顺序很重要：宿主允许「跟随系统」，此时宿主的 `isDarkTheme`
 * 为 null，必须自己看系统。
 *
 * ## 参考实现
 *
 * 配色数值参考 SPW-Listen-Stats 的 `StatsColors`（它从 SPW 官方
 * 经典设置卡片采样得来），保证和宿主原生观感一致。
 */
data class KugouColors(
    val isDark: Boolean,

    /** 页面底色。 */
    val pageBackground: Color,
    /** 卡片底色。 */
    val cardBackground: Color,
    /** 主文字。 */
    val text: Color,
    /** 次要文字（歌手名、曲目数、提示）。 */
    val muted: Color,
    /** 更弱的文字（占位符）。 */
    val faint: Color,
    /** 分隔线 / 卡片描边。 */
    val line: Color,
    /** 浅色区块（输入框、标签底色）。 */
    val subtle: Color,
    /** 悬停高亮。 */
    val hover: Color,
    /** 强调色（可点文字、选中态）。 */
    val accent: Color,
    /** 强调色的淡化版（选中背景）。 */
    val accentSoft: Color,
    /** 危险操作（退出登录、删除）。 */
    val danger: Color,
    /** 收藏心形。 */
    val favorite: Color,
    /** 封面占位块底色。 */
    val coverPlaceholder: Color,
)

/** 深色主题（默认，最贴合 SPW 的经典外观）。 */
val DarkKugouColors = KugouColors(
    isDark = true,
    pageBackground = Color(0xFF202124),
    cardBackground = Color(0xFF2A2A2A),
    text = Color(0xFFEDEDEE),
    muted = Color(0xFFA0A3AB),
    faint = Color(0xFF6E6E72),
    line = Color(0xFF37393E),
    subtle = Color(0xFF2B2D32),
    hover = Color(0xFF34363C),
    accent = Color(0xFF79B5FF),
    accentSoft = Color(0x1779B5FF),
    danger = Color(0xFFFF8A8A),
    favorite = Color(0xFFE8736A),
    coverPlaceholder = Color(0xFF2E2E32),
)

/** 浅色主题。 */
val LightKugouColors = KugouColors(
    isDark = false,
    pageBackground = Color(0xFFF8F8F8),
    cardBackground = Color(0xFFFCFCFC),
    text = Color(0xFF25272B),
    muted = Color(0xFF777B82),
    faint = Color(0xFFA8ABB2),
    line = Color(0xFFE9EAED),
    subtle = Color(0xFFF5F6F8),
    hover = Color(0xFFF1F3F6),
    accent = Color(0xFF2777D8),
    accentSoft = Color(0x132777D8),
    danger = Color(0xFFCF4545),
    favorite = Color(0xFFE0503F),
    coverPlaceholder = Color(0xFFE8E9EC),
)

/** 当前主题，页面通过它取色。 */
val LocalKugouColors = staticCompositionLocalOf { DarkKugouColors }

/** 便捷读取：`KugouTheme.colors.text`。 */
object KugouTheme {
    val colors: KugouColors
        @Composable get() = LocalKugouColors.current
}

/**
 * 主题提供者。
 *
 * 把 [LocalKugouColors] 注入到子树，页面里的 `KugouTheme.colors` 就能取到。
 *
 * @param dark 是否深色；由调用方从宿主读取（见 [rememberHostDark]）
 */
@Composable
fun KugouThemeProvider(dark: Boolean, content: @Composable () -> Unit) {
    val colors = if (dark) DarkKugouColors else LightKugouColors
    CompositionLocalProvider(LocalKugouColors provides colors) {
        content()
    }
}

/**
 * 读取宿主的深色设置，并在变化时刷新。
 *
 * ## 为什么每秒轮询
 *
 * 宿主的主题切换没有对插件暴露回调。轮询开销极小（一次反射读字段），
 * 换来的是用户在宿主里切主题后插件能实时跟着变。
 *
 * 参考实现（SPW-Listen-Stats）也是 1 秒轮询。
 *
 * @return 当前是否深色；读不到时默认深色（与宿主默认外观一致）
 */
@Composable
fun rememberHostDark(): Boolean {
    var dark by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        while (true) {
            dark = withContext(Dispatchers.IO) { readHostDark() ?: readSystemDark() }
            delay(1_000)
        }
    }
    return dark
}

/**
 * 读宿主的显式主题设置。
 *
 * 返回 null 表示宿主设为「跟随系统」。
 */
private fun readHostDark(): Boolean? = try {
    val cls = Class.forName("com.xuncorp.voxzen.util.AppConfig", true, KugouTheme::class.java.classLoader)
    val inst = cls.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
    val m = cls.getMethod("isDarkTheme").apply { isAccessible = true }
    m.invoke(inst) as? Boolean
} catch (t: Throwable) {
    null
}

/** 读系统主题（skiko 提供）。 */
private fun readSystemDark(): Boolean = try {
    val cls = Class.forName("org.jetbrains.skiko.SystemTheme", true, KugouTheme::class.java.classLoader)
    val darkEnum = cls.getField("DARK").get(null)
    val current = Class.forName("org.jetbrains.skiko.SkikoKt", true, KugouTheme::class.java.classLoader)
        .getMethod("getCurrentSystemTheme")
        .invoke(null)
    current == darkEnum
} catch (t: Throwable) {
    // 读不到就当作深色，与宿主默认外观一致
    true
}

/**
 * 读宿主的窗口材质风格（`None` / `WindowsAcrylic` / `WindowsMica` / `WindowsMicaAlt`）。
 *
 * Mica/Acrylic 下页面应当是**半透明**的，让系统绘制的背景透出来。
 * 早期实现用不透明底色，会把宿主的材质效果完全盖住。
 */
fun readWindowStyle(): String = try {
    val cls = Class.forName("com.xuncorp.voxzen.util.AppConfig", true, KugouTheme::class.java.classLoader)
    val inst = cls.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
    val v = cls.getMethod("getSpwWindowStyle").apply { isAccessible = true }.invoke(inst)
    v?.toString() ?: "None"
} catch (t: Throwable) {
    "None"
}

/**
 * 是否使用透明材质底色。
 *
 * Mica/Acrylic 生效时页面底色应为透明，让 DWM 的模糊背景透出。
 */
fun isMaterialWindow(style: String): Boolean =
    style == "WindowsAcrylic" || style == "WindowsMica" || style == "WindowsMicaAlt"
