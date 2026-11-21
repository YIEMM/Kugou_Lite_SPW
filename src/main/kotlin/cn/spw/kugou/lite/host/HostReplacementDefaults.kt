package cn.spw.kugou.lite.host

import cn.spw.kugou.lite.diag.Diag
import java.lang.reflect.Method

/**
 * 为本插件的替换点准备好默认参数掩码兼容。
 *
 * ## 反混淆策略（R18–R20）
 *
 * 早期把 hooks.site / transformer / 调用点信息类的混淆类名用码位常量写死——
 * 宿主重新混淆后全部失效。现在全部改为**结构特征匹配**（编译后不变）：
 *
 * | 旧写死 | 新结构特征 |
 * |---|---|
 * | SITE 类名 | registrar 的 Map 值里「有 0 参返回 Method 的方法」的实例（[matches] 已有） |
 * | prepareSite 类型校验 | 同上结构组合（IntRange 0 参 + IntRange 1 参 + Method 0 参） |
 * | TRANSFORMER_TYPE | target.method 的 declaringClass（getTransformer 返回的 Method 的声明类） |
 * | TRANSFORMER_IMPL | priorTransformer 的运行时类（宿主当前挂的 transformer 实例同类） |
 * | SITE_INFO 类名 | 「候选的 0 参方法返回的对象自身有 0 参返回 Class 的方法」（[matches] 已有） |
 */
internal object HostReplacementDefaults {

    private const val TAG = "replacement"

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
            // 结构特征（R20）：site 类 = registrar 的 Map 值里「有 0 参返回 Method 的方法」的实例。
            // 早期用写死的 SITE 类名 isInstance 过滤，宿主重新混淆即失效。
            val siteCandidates = registrar.javaClass.declaredFields
                .filter { Map::class.java.isAssignableFrom(it.type) }
                .flatMap { field ->
                    field.isAccessible = true
                    (field.get(registrar) as? Map<*, *>)?.values?.filterNotNull().orEmpty()
                }
                .filter { hasMethodReturningMethod(it) }
                .distinct()

            if (siteCandidates.isEmpty()) {
                Diag.warn("[$TAG] 未在 registrar 中找到任何 hook site（宿主结构可能已变）")
                return null
            }

            Diag.info("[$TAG] 候选 hook site 数量=${siteCandidates.size}")
            siteCandidates.forEach { candidate ->
                val cls = candidate.javaClass
                Diag.info(
                    "[$TAG]   候选 " + cls.name +
                        " methods=" + cls.declaredMethods.joinToString("|") { it.name + "(" + it.parameterCount + "):" + it.returnType.simpleName }
                )
            }

            val site = siteCandidates.firstOrNull { candidate -> matches(candidate, original, caller) }
            if (site == null) {
                Diag.warn("[$TAG] 未找到与目标匹配的 hook site（候选 ${siteCandidates.size} 个）")
                return null
            }
            prepareSite(site)
        }.onFailure {
            Diag.warn("[$TAG] 准备默认参数兼容失败：" + (it.message ?: it.javaClass.simpleName))
        }.getOrNull()
    }

    /** 结构特征：对象有「0 参、返回 Method」的方法（site 类的标志）。 */
    private fun hasMethodReturningMethod(obj: Any): Boolean =
        obj.javaClass.methods.any { it.parameterCount == 0 && it.returnType == Method::class.java }

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

            // SITE_INFO 结构特征（R20）：候选的 0 参方法返回的对象，自身有「0 参返回 Class」的方法。
            // 早期写死 SITE_INFO 类名做返回类型比对，混淆即失效。
            val infoMethod = cls.declaredMethods
                .map { it to runCatching { it.invoke(candidate) }.getOrNull() }
                .firstOrNull { (m, result) ->
                    m.parameterCount == 0 && result != null && hasMethodReturningClass(result)
                }
            if (infoMethod == null) {
                Diag.warn("[$TAG] 候选 ${cls.name} 无调用点信息方法（结构特征不匹配）")
                return false
            }
            val info = infoMethod.second!!
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

    /** 结构特征：对象有「0 参、返回 Class」的方法。 */
    private fun hasMethodReturningClass(obj: Any): Boolean =
        obj.javaClass.declaredMethods.any { it.parameterCount == 0 && it.returnType == Class::class.java }

    /** 对单个 site 做最小手术：清空允许区间 + 换 transformer。 */
    private fun prepareSite(site: Any): AutoCloseable {
        val type = site.javaClass
        // 结构校验（R20）替代写死的 SITE 类名比对：site 必须同时具备
        // 「0 参返回 IntRange」「1 参收 IntRange」「0 参返回 Method」三个方法（读/写/目标）。
        val methods = type.declaredMethods
        val read = methods.singleOrNull { it.returnType == IntRange::class.java && it.parameterCount == 0 }
            ?: throw IllegalStateException("Unsupported UI hook site（无 IntRange 0 参方法）: " + type.name)
        val write = methods.singleOrNull { it.parameterTypes.toList() == listOf(IntRange::class.java) }
            ?: throw IllegalStateException("Unsupported UI hook site（无 IntRange 1 参方法）: " + type.name)

        val target = type.methods
            .singleOrNull { it.parameterCount == 0 && it.returnType == Method::class.java }
            ?.invoke(site) as? Method
            ?: throw IllegalStateException("Unsupported UI hook site（无 0 参返回 Method）: " + type.name)
        val expected = target.parameterCount - 1
        val current = read.invoke(site) as IntRange
        check(current == (expected..expected)) {
            "Unexpected Compose default-mask layout: " + current + " vs " + expected
        }

        // TRANSFORMER_TYPE（R20）：getTransformer 返回的 Method 的**声明类**就是 transformer 基类——
        // 早期写死其混淆名（基类 U+037F），宿主重新混淆即失效。
        val getTransformer = methods.singleOrNull {
            it.parameterCount == 0 && it.returnType == target.declaringClass
        } ?: throw IllegalStateException("site 无返回 transformer 基类的方法: " + type.name)
        val setTransformer = methods.singleOrNull {
            it.parameterCount == 1 && it.parameterTypes[0] == target.declaringClass
        } ?: throw IllegalStateException("site 无收 transformer 基类的 setter: " + type.name)

        val priorRange = current
        val priorTransformer = getTransformer.invoke(site)

        try {
            write.invoke(site, IntRange.EMPTY)
            // TRANSFORMER_IMPL（R20）：宿主当前挂着的 transformer 实例的运行时类就是实现类——
            // 同一个宿主版本里只有一种实现，直接取同类构造新实例，无需写死类名。
            val implClass = priorTransformer?.javaClass
                ?: throw IllegalStateException("site 当前无 transformer 实例可参照: " + type.name)
            val transformer = implClass.getConstructor(type).newInstance(site)
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
