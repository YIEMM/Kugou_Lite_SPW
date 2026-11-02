package cn.spw.kugou.lite.host

import cn.spw.kugou.lite.diag.Diag
import java.lang.reflect.Method
import kotlin.ranges.IntRange

/**
 * 宿主替换守卫的默认参数掩码兼容层。
 *
 * ## 为什么需要它（真机故障记录）
 *
 * 宿主的 `replaceComposable` 引擎内部有一道守卫：**拒绝非零的 Compose 默认参数掩码**。
 * 而宿主导航调用 `ModConfigScreen(route, viewModel = <默认>)` 时，Compose 编译器
 * 追加的 `defaultMask` 是 `2`（第 1 位表示第 1 个业务参数走默认值）。
 * 于是：
 * - `replaceComposable` 注册**成功**（无异常、返回有效 handle）；
 * - 但组合期守卫判定 `mask != 0` 后**直接执行原组件**，我们的 `Content` 永不进入。
 *
 * 真机现象极具迷惑性：导航确实跳到 `ModConfigContent(cn.spw.kugou.lite)`，
 * 页框、返回按钮、侧栏都正常，**唯独内容仍是宿主原生的「模组管理」**，
 * 插件日志里连一行 `PageHook` 都没有。同位置的 `afterComposable` 探针却能正常触发 ——
 * 因为 after 语义不经过替换守卫。
 *
 * ## 解法
 *
 * 只针对**本插件注册的那一个 site** 做最小手术：把该 site 的「允许的默认参数区间」
 * 临时改成 `IntRange.EMPTY`，并挂上一个自定义 transformer。
 * 不动宿主全局 transformer、不动其它 site、不动任何宿主类定义。
 *
 * 参考实现（spw-netease-plugin `HostReplacementDefaults.kt`）在 1.19.0 上用的就是
 * 这套做法；本插件在 **1.19.3 上已核对混淆名未变**：
 * - site：`com.xuncorp.voxzen.workshop.hooks.ԩ$ԭ`（码位 U+0529 与 U+052D）
 * - transformer：`com.xuncorp.voxzen.workshop.hooks.ԭ`（码位 U+052D）
 *
 * ## 为什么不写字面量转义
 *
 * kapt 会把 Kotlin 文件交给 Java 前端做注解处理，而 Java 在**词法阶段**就把
 * 「反斜杠 + u + 四个十六进制」当转义处理 —— 连注释里的也会，导致
 * `kaptKotlin` 直接报「非法的 Unicode 逃逸」。所以这里一律用 [cp] 拼码位，
 * 源码里绝不出现反斜杠加 u 的组合。
 */
internal object HostReplacementDefaults {

    private const val TAG = "replacement"

    /**
     * 按码位拼出混淆字符。
     *
     * **必须声明在所有使用它的属性之前**：Kotlin `object` 的属性按文本顺序初始化，
     * 但方法本身在任何时候都可调用；放在最前面是为了让阅读顺序与求值顺序一致。
     */
    private fun cp(code: Int): String = code.toChar().toString()

    /** hook site 内部类：`hooks.ԩ$ԭ`。 */
    private val SITE: String =
        "com.xuncorp.voxzen.workshop.hooks." + cp(0x0529) + "$" + cp(0x052D)

    /**
     * transformer 的**静态类型**（getter/setter 的签名类型）：`hooks.Ϳ`。
     *
     * 真机诊断确认：site 的 `getTransformer` 返回的是基类 `Ϳ`（码位 U+037F），
     * 而**不是**具体实现类。早期把它写成 `ԭ`（U+052D）导致
     * `Array contains no element matching the predicate`。
     */
    private val TRANSFORMER_TYPE: String = "com.xuncorp.voxzen.workshop.hooks." + cp(0x037F)

    /**
     * 要实例化的 transformer **实现类**：`hooks.ԭ`。
     *
     * 构造参数是 site 实例，用 `getConstructor(site.javaClass)` 取。
     */
    private val TRANSFORMER_IMPL: String = "com.xuncorp.voxzen.workshop.hooks." + cp(0x052D)

    /** 调用点信息类：`hooks.Ԫ$Ԩ`。 */
    private val SITE_INFO: String =
        "com.xuncorp.voxzen.workshop.hooks." + cp(0x052A) + "$" + cp(0x0528)

    /**
     * 为本插件的替换点准备好默认参数掩码兼容。
     *
     * @param registrar 宿主的 [com.xuncorp.spw.workshop.api.hook.HookRegistrar] 实例
     * @param original  被替换的宿主 composable 原始 [Method]
     * @param caller    调用点所在类名（`inClass`）
     * @return 还原句柄；失败返回 null（调用方应据此放弃替换）
     */
    fun prepare(registrar: Any, original: Method, caller: String): AutoCloseable? {
        return runCatching {
            val siteType = Class.forName(SITE, false, registrar.javaClass.classLoader)
            val sites = registrar.javaClass.declaredFields
                .filter { Map::class.java.isAssignableFrom(it.type) }
                .flatMap { field ->
                    field.isAccessible = true
                    (field.get(registrar) as? Map<*, *>)?.values?.filterNotNull().orEmpty()
                }
                .filter { siteType.isInstance(it) }
                .distinct()

            if (sites.isEmpty()) {
                Diag.warn("[$TAG] 未在 registrar 中找到任何 hook site（宿主结构可能已变）")
                return null
            }

            Diag.info("[$TAG] 候选 hook site 数量=${sites.size}")
            sites.forEach { candidate ->
                val cls = candidate.javaClass
                Diag.info(
                    "[$TAG]   候选 " + cls.name +
                        " methods=" + cls.declaredMethods.joinToString("|") { it.name + "(" + it.parameterCount + "):" + it.returnType.simpleName }
                )
            }

            val site = sites.firstOrNull { candidate -> matches(candidate, original, caller) }
            if (site == null) {
                Diag.warn("[$TAG] 未找到与目标匹配的 hook site（候选 ${sites.size} 个）")
                return null
            }
            prepareSite(site)
        }.onFailure {
            Diag.warn("[$TAG] 准备默认参数兼容失败：" + (it.message ?: it.javaClass.simpleName))
        }.getOrNull()
    }

    /** 判断某个 site 是否就是「替换 original、且 inClass 为 caller」那一个。 */
    private fun matches(candidate: Any, original: Method, caller: String): Boolean {
        val cls = candidate.javaClass
        return runCatching {
            val targetMethod = cls.methods
                .filter { it.returnType == Method::class.java && it.parameterCount == 0 }
                .firstOrNull()
            if (targetMethod == null) {
                Diag.warn("[$TAG] 候选 ${cls.name} 无「返回 Method」的 0 参方法")
                return false
            }
            val target = targetMethod.invoke(candidate) as Method
            if (target != original) {
                Diag.info("[$TAG] 候选 ${cls.name} 目标方法不匹配：${target.name}${target.parameterTypes.contentToString()}")
                return false
            }

            val infoTypeName = SITE_INFO
            val infoMethod = cls.declaredMethods
                .firstOrNull { it.parameterCount == 0 && it.returnType.name == infoTypeName }
            if (infoMethod == null) {
                Diag.warn("[$TAG] 候选 ${cls.name} 无 0 参且返回 $infoTypeName 的方法")
                return false
            }
            val info = infoMethod.invoke(candidate)
            val inClassMethod = info.javaClass.declaredMethods
                .firstOrNull { it.returnType == Class::class.java && it.parameterCount == 0 }
            if (inClassMethod == null) {
                Diag.warn("[$TAG] 候选 ${cls.name} 的调用点信息无「返回 Class」的 0 参方法")
                return false
            }
            val inClass = inClassMethod.invoke(info) as Class<*>
            Diag.info("[$TAG] 候选 ${cls.name} inClass=${inClass.name}（目标 $caller）")
            inClass.name == caller
        }.onFailure {
            Diag.warn("[$TAG] 候选 ${cls.name} 匹配异常：" + (it.message ?: it.javaClass.simpleName))
        }.getOrDefault(false)
    }

    /** 对单个 site 做最小手术：清空允许区间 + 换 transformer。 */
    private fun prepareSite(site: Any): AutoCloseable {
        val type = site.javaClass
        check(type.name == SITE) { "Unsupported UI hook site: " + type.name }

        val methods = type.declaredMethods
        val read = methods.single { it.returnType == IntRange::class.java && it.parameterCount == 0 }
        val write = methods.single { it.parameterTypes.toList() == listOf(IntRange::class.java) }

        val target = type.methods
            .single { it.parameterCount == 0 && it.returnType == Method::class.java }
            .invoke(site) as Method
        val expected = target.parameterCount - 1
        val current = read.invoke(site) as IntRange
        check(current == (expected..expected)) {
            "Unexpected Compose default-mask layout: " + current + " vs " + expected
        }

        val getTransformer = methods.single {
            it.parameterCount == 0 && it.returnType.name == TRANSFORMER_TYPE
        }
        val setTransformer = methods.single {
            it.parameterCount == 1 && it.parameterTypes[0].name == TRANSFORMER_TYPE
        }

        val priorRange = current
        val priorTransformer = getTransformer.invoke(site)

        try {
            write.invoke(site, IntRange.EMPTY)
            val transformerType = Class.forName(TRANSFORMER_IMPL, true, type.classLoader)
            val transformer = transformerType.getConstructor(type).newInstance(site)
            setTransformer.invoke(site, transformer)
            Diag.info("[$TAG] 已放开默认参数掩码（原区间=$priorRange，允许区间=空）")
            return AutoCloseable {
                runCatching {
                    write.invoke(site, priorRange)
                    setTransformer.invoke(site, priorTransformer)
                    Diag.info("[$TAG] 已还原默认参数掩码")
                }
            }
        } catch (t: Throwable) {
            runCatching {
                write.invoke(site, priorRange)
                setTransformer.invoke(site, priorTransformer)
            }
            throw t
        }
    }
}
