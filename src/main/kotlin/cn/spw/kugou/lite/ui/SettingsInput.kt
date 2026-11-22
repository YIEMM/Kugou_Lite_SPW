package cn.spw.kugou.lite.ui

/**
 * 设置页自定义输入的解析校验（用户需求：设置内容更加自定义）。
 *
 * 快捷档（1/2/5/10/20 GB、限流 4/6/8）保留一键选择；「自定义」入口展开
 * 文本输入框，任意整数经这里解析：合法值归一化返回，非法输入返回 null
 * 由 UI 提示错误（不静默取默认值，避免用户以为设置成功）。
 */
internal fun parseCacheLimitGb(input: String): Int? =
    input.trim().toIntOrNull()?.coerceIn(1, 100)

internal fun parseCoverRate(input: String): Int? =
    input.trim().toIntOrNull()?.coerceIn(1, 20)
