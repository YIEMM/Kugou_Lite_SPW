package cn.spw.kugou.lite.host

import cn.spw.kugou.lite.diag.Diag
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import java.io.File
import java.util.jar.JarFile

/**
 * 宿主注入目标解析器。
 *
 * ## 为什么不能硬编码混淆名
 *
 * 宿主 UI 类被混淆成 Unicode 码点名（`androidx/compose/ui/Ɏ`），**每次更新都会重排**。
 * `spw-listen-stats` 的版本映射表在 1.19.3 上已经失效（它记的 1.19.1 值是 U+0443，
 * 1.19.3 真实值是 U+024E）。
 *
 * ## 两类稳定特征
 *
 * 1. **LDC 源码标记**（方法体内）：`com.xuncorp...MainTitleBar (MainTitleBar.kt:78)`
 *    既给出原始类名，又给出源文件行号，是 Kotlin 编译器产物，跨版本稳定。
 * 2. **Methodref**（常量池）：证明「真的调用了」，而非「字符串里提到过」。
 *
 * ## 两个必须处理的坑
 *
 * 1. **LDC 加载的是 CONSTANT_String（tag 8），它再指向 UTF-8 项。**
 *    ASM 的 `visitLdcInsn` 已经帮我们解好了这层，不要自己解析常量池。
 * 2. **必须排除自引用。** 声明类自己的其他方法引用同一函数也会产生 Methodref，
 *    那不是宿主调用点。不排除就会得到「调用方不唯一」的假结论。
 */
object HostTargets {

    /** 源码标记：`包.类.函数 (文件.kt:行号)`。最后一段是函数名。 */
    private val MARKER = Regex("""^([A-Za-z_$][\w$.]*(?:\.[A-Za-z_$][\w$]*)+) \(([^()]+\.kt):(\d+)\)$""")

    private const val COMPOSER = "androidx.compose.runtime.Composer"

    /** 一个已解析的 Hook 目标。 */
    data class Target(
        /** 被替换组件的（可能混淆的）类名。 */
        val className: String,
        /** 被替换组件的方法名。 */
        val methodName: String,
        /** **调用方**类名 —— Hook API 的 `inClass`，必须是调用方而非被调用方。 */
        val inClass: String,
        /** 业务参数（已剔除 Composer 与合成 int）。 */
        val parameterTypes: List<String>,
        /** 被替换方法的完整描述符。 */
        val descriptor: String
    )

    /** 一条「声明」记录：某个类的某个方法体内带有源码标记。 */
    data class Decl(
        val obfClass: String,
        val obfMethod: String,
        val descriptor: String,
        val original: String,
        val source: String,
        val line: Int
    ) {
        /** 原始函数简单名（标记的最后一段）。 */
        val originalName: String get() = original.substringAfterLast('.')

        /**
         * 业务参数：剔除 `Composer` 及其**之后**的全部合成参数
         * （`changed` / `default` 位掩码都是 `int`）。
         *
         * 只滤 `Composer` 不滤后面的 int 会让注册签名不匹配——
         * 实测 `MainWindowContent` 的 `boolean` 被解析成 `boolean, int, int`。
         */
        val businessParams: List<String>
            get() {
                val types = Type.getArgumentTypes(descriptor)
                val at = types.indexOfFirst { it.className == COMPOSER }
                val biz = if (at >= 0) types.take(at) else types.toList()
                return biz.map { it.className }
            }
    }

    /** 一条调用点记录。 */
    data class CallRecord(
        val calleeOwner: String,
        val calleeMethod: String,
        val calleeDesc: String,
        val callerClass: String
    )

    /** 扫描结果索引。 */
    class Index(
        val archive: File,
        val decls: List<Decl>,
        val calls: List<CallRecord>
    ) {
        val declsByClass: Map<String, List<Decl>> = decls.groupBy { it.obfClass }
        /** 原始全名 → 声明。 */
        val byOriginal: Map<String, List<Decl>> = decls.groupBy { it.original }
    }

    @Volatile
    private var cached: Index? = null

    /** 进程内缓存（一次扫描约 10 秒，绝不能每次调用都扫）。 */
    fun index(anchor: Class<*>): Index = cached ?: synchronized(this) {
        cached ?: buildIndex(locateArchive(anchor) ?: error("找不到宿主归档")).also { cached = it }
    }

    fun indexOf(archive: File): Index = cached ?: synchronized(this) {
        cached ?: buildIndex(archive).also { cached = it }
    }

    /**
     * 定位宿主归档。
     *
     * SPW 把宿主打成 `app/ffmpeg-x64.dll`——**不是 `.jar` 扩展名**，但 `JarFile` 能读。
     *
     * @param anchor 由**宿主 ClassLoader** 加载的类。插件里传 `WorkshopApi::class.java`。
     *        **不要传插件自己的类**——它的 codeSource 指向插件目录。
     */
    fun locateArchive(anchor: Class<*>): File? {
        // 1) codeSource
        runCatching {
            anchor.protectionDomain?.codeSource?.location?.let { loc ->
                if (loc.protocol == "file") {
                    val f = File(loc.toURI())
                    if (f.isFile && f.length() > 1_000_000) return f
                }
            }
        }
        // 2) 资源 jar URL
        runCatching {
            val res = anchor.getResource("/" + anchor.name.replace('.', '/') + ".class")
            if (res?.protocol == "jar") {
                val u = (res.openConnection() as java.net.JarURLConnection).jarFileURL
                if (u.protocol == "file") {
                    val f = File(u.toURI())
                    if (f.isFile) return f
                }
            }
        }
        // 3) 已知安装路径
        return KNOWN_ARCHIVES.firstOrNull { it.isFile }
    }

    private val KNOWN_ARCHIVES: List<File> by lazy {
        listOf(
            System.getenv("SPW_HOST_ARCHIVE"),
            "E:/steam/steamapps/common/Salt Player for Windows/app/ffmpeg-x64.dll",
            "C:/Program Files/Salt Player for Windows/app/ffmpeg-x64.dll",
            "C:/Program Files (x86)/Salt Player for Windows/app/ffmpeg-x64.dll"
        ).filterNotNull().map(::File)
    }

    private fun buildIndex(archive: File): Index {
        val decls = ArrayList<Decl>(4096)
        val calls = ArrayList<CallRecord>(16384)
        JarFile(archive).use { jar ->
            val entries = jar.entries()
            while (entries.hasMoreElements()) {
                val e = entries.nextElement()
                if (!e.name.endsWith(".class")) continue
                val bytes = runCatching { jar.getInputStream(e).use { it.readBytes() } }.getOrNull() ?: continue
                runCatching { declsOf(bytes) }.getOrNull()?.let { decls += it }
                runCatching { callsOf(bytes) }.getOrNull()?.let { calls += it }
            }
        }
        Diag.info("[host] archive=$archive decls=${decls.size} calls=${calls.size}")
        return Index(archive, decls, calls)
    }

    /**
     * 挑出唯一调用点。
     *
     * 判定规则（严格，宁缺毋滥）：
     * 1. 找所有调用 `calleeClass.functionName` 的类
     * 2. 按完整签名过滤、**排除声明类自己**
     * 3. 恰好只有一个类调用它 → 用它作 inClass；多个 → 返回 null
     *
     * 返回 null 表示「不存在**或存在歧义**」，两种情况都必须降级，
     * 绝不能"挑一个看着像的"硬渲染——那正是早期探针渲染不出内容的原因。
     */
    fun resolveByUniqueCaller(idx: Index, calleeClass: String, functionName: String): Target? {
        val decl = idx.byOriginal[calleeClass]?.filter { it.originalName == functionName }?.singleOrNull()
            ?: return null

        val callers = idx.calls
            .filter {
                it.calleeOwner == decl.obfClass &&
                    it.calleeMethod == decl.obfMethod &&
                    it.calleeDesc == decl.descriptor &&
                    it.callerClass != decl.obfClass      // 排除自引用
            }
            .groupBy { it.callerClass }
            .mapValues { (_, v) -> v.size }

        val unique = callers.entries.singleOrNull()?.takeIf { it.value == 1 } ?: return null

        return Target(
            className = decl.obfClass,
            methodName = decl.obfMethod,
            inClass = unique.key,
            parameterTypes = decl.businessParams,
            descriptor = decl.descriptor
        )
    }

    /** 诊断用：说明为什么解析不出来。 */
    fun explain(idx: Index, calleeClass: String, functionName: String): String {
        val all = idx.byOriginal[calleeClass]
            ?: return "宿主没有带标记的类 $calleeClass"
        val matched = all.filter { it.originalName == functionName }
        return when {
            matched.isEmpty() -> "$calleeClass 下没有函数 $functionName；候选：${all.map { it.originalName }}"
            matched.size > 1 -> "$calleeClass.$functionName 有 ${matched.size} 个重载，无法唯一定位"
            else -> {
                val d = matched[0]
                val sites = idx.calls.count {
                    it.calleeOwner == d.obfClass && it.calleeMethod == d.obfMethod &&
                        it.calleeDesc == d.descriptor && it.callerClass != d.obfClass
                }
                "声明 ${d.obfClass}.${d.obfMethod}，外部调用点 $sites 处"
            }
        }
    }

    // ------------------------------------------------------------- ASM

    private fun declsOf(bytes: ByteArray): List<Decl> {
        val out = ArrayList<Decl>(4)
        val cn = arrayOfNulls<String>(1)
        ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visit(v: Int, a: Int, n: String, s: String?, sup: String?, i: Array<out String>?) {
                cn[0] = n.replace('/', '.')
            }

            override fun visitMethod(
                a: Int, name: String, desc: String, s: String?, e: Array<out String>?
            ): MethodVisitor = object : MethodVisitor(Opcodes.ASM9) {
                override fun visitLdcInsn(value: Any?) {
                    val text = value as? String ?: return
                    val m = MARKER.matchEntire(text) ?: return
                    val cls = cn[0] ?: return
                    out += Decl(
                        obfClass = cls,
                        obfMethod = name,
                        descriptor = desc,
                        original = m.groupValues[1],
                        source = m.groupValues[2],
                        line = m.groupValues[3].toIntOrNull() ?: 0
                    )
                }
            }
        }, 0)
        return out
    }

    /**
     * 收集 Composable 静态调用点。
     *
     * 只收 `INVOKESTATIC` 且参数里含 `Composer`、返回 void 的调用——
     * 这正是 Compose 编译后的 Composable 函数特征。
     */
    private fun callsOf(bytes: ByteArray): List<CallRecord> {
        val out = ArrayList<CallRecord>(32)
        val caller = arrayOfNulls<String>(1)
        ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visit(v: Int, a: Int, n: String, s: String?, sup: String?, i: Array<out String>?) {
                caller[0] = n.replace('/', '.')
            }

            override fun visitMethod(
                a: Int, name: String, desc: String, s: String?, e: Array<out String>?
            ): MethodVisitor = object : MethodVisitor(Opcodes.ASM9) {
                override fun visitMethodInsn(op: Int, owner: String, nm: String, d: String, itf: Boolean) {
                    if (op == Opcodes.INVOKESTATIC) {
                        val args = Type.getArgumentTypes(d)
                        val hasComposer = args.any { it.className == COMPOSER }
                        if (hasComposer && Type.getReturnType(d) == Type.VOID_TYPE) {
                            out += CallRecord(
                                calleeOwner = owner.replace('/', '.'),
                                calleeMethod = nm,
                                calleeDesc = d,
                                callerClass = caller[0] ?: ""
                            )
                        }
                    }
                }
            }
        }, 0)
        return out
    }
}
