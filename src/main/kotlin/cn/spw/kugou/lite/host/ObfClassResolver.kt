package cn.spw.kugou.lite.host

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 按 LDC 源码标记的**原始全名**反查宿主混淆类名（R18/R19/R20 的落地件）。
 *
 * ## 为什么需要它
 *
 * HostSalt / HostReplacementDefaults 早期把 SaltUI 组件、hooks.site 等混淆类名
 * 用码位常量（cp(0x0622) 之类）写死 —— 宿主重新混淆后这些码点全变，插件立刻失效。
 * 宿主 Kotlin 编译产物保留了 LDC 源码标记（`原始全名 (文件.kt:行号)`），
 * 这是编译后不变的特征（R20）；[HostTargets] 的扫描 Index 已把它建成
 * `byOriginal` 原始名 → 声明 的反向索引，本类在其上加 per-Index 缓存（R21）。
 *
 * ## 契约
 *
 * - [resolve] 命中返回混淆类名；查不到返回 **null**（调用方记日志降级，R22）；
 *   同一原始名对应多个声明时抛 [IllegalStateException]（无法唯一定位，宁可失败）。
 * - 结果按 Index 缓存：同一次宿主扫描内重复查询零开销。
 */
object ObfClassResolver {

    /** per-Index 缓存：原始全名#函数名 → 混淆类名（空串 = 确认不存在）。 */
    private val cache = ConcurrentHashMap<HostTargets.Index, ConcurrentHashMap<String, String>>()

    /**
     * 按「原始全名 + 原始函数简单名」反查混淆类名。
     *
     * 例如查 SaltUI Text：resolve(idx, "com.moriafly.salt.ui.Text.Text", "Text")。
     * 原始全名的最后一段与 [functionName] 必须一致（防同名类误配）。
     */
    fun resolve(idx: HostTargets.Index, originalFullName: String, functionName: String): String? {
        val perIndex = cache.computeIfAbsent(idx) { ConcurrentHashMap() }
        // 缓存键含函数名：同一原始类名可能有多条声明。
        // ConcurrentHashMap 不收 null value：未命中用空串哨兵表示「确认不存在」。
        val cacheKey = originalFullName + "#" + functionName
        if (perIndex.containsKey(cacheKey)) return perIndex[cacheKey]?.ifEmpty { null }
        var decls = idx.byOriginal[originalFullName]
            ?.filter { it.originalName == functionName }
        // 回退：LDC 标记的原始全名有两种形态（包.类 / 包.类.函数），精确键 miss 时
        // 按「以 originalFullName.functionName 结尾」找唯一键——两种形态都兼容。
        if (decls.isNullOrEmpty()) {
            val suffix = originalFullName + "." + functionName
            val keys = idx.byOriginal.keys.filter { it.endsWith(suffix) }
            decls = keys.singleOrNull()
                ?.let { key -> idx.byOriginal[key]?.filter { it.originalName == functionName } }
        }
        if (decls.isNullOrEmpty()) {
            perIndex[cacheKey] = ""
            return null
        }
        // 多声明处理：R8 会把同一源文件的同名 @Composable 重载全部重命名到同一个混淆类，
        // 此时各声明的 obfClass 必然相同——取它就是正确的类名（我们要的只是类名）。
        // 只有 obfClass 互相不同（真歧义）才拒绝。
        val distinctClasses = decls.map { it.obfClass }.distinct()
        val obf = if (distinctClasses.size == 1) distinctClasses[0]
        else throw IllegalStateException(
            originalFullName + "#" + functionName + " 有 " + decls.size + " 个声明且类名不同，无法唯一定位；候选：" + distinctClasses
        )
        perIndex[cacheKey] = obf
        return obf
    }

    /** 便捷重载：宿主 JAR 归档直接解析（内部走 HostTargets 缓存 Index）。 */
    fun resolve(archive: File, originalFullName: String, functionName: String): String? =
        resolve(HostTargets.indexOf(archive), originalFullName, functionName)

    /** 测试用：清空缓存（生产不调用）。 */
    internal fun clearCacheForTest() = cache.clear()
}
