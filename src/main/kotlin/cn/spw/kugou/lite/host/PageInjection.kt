package cn.spw.kugou.lite.host

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composer
import androidx.compose.runtime.currentComposer
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.spw.workshop.api.hook.ComposableHook
import com.xuncorp.spw.workshop.api.hook.HookHandle
import com.xuncorp.spw.workshop.api.hook.UiHookCall
import cn.spw.kugou.lite.diag.Diag
import cn.spw.kugou.lite.ui.KugouLitePage
import cn.spw.kugou.lite.ui.KugouLiteTitleButton
import cn.spw.kugou.lite.ui.PageState

/**
 * 注入层：**标题栏入口 + 导航内页面**。
 *
 * ## 设计修正（重要）
 *
 * 早期版本在 `MainWindowContent` 上 `afterComposable` 插入一个铺满整个窗口的浮层。
 * 这在真机上暴露了两个问题，已彻底废弃：
 *
 * 1. **浮层顶栏与宿主标题栏重叠**。浮层从 (0,0) 开始铺满，它自己的「关闭」按钮正好压在
 *    宿主右上角的 □/✕ caption 按钮上。用户点击时命中哪一个取决于 Compose 的命中测试顺序，
 *    实测导致宿主窗口消失（进程仍存活）——这不是崩溃，是点到了宿主的最小化/关闭。
 *    教训：**注入内容绝不能与宿主 caption 区重叠**。
 * 2. **浮层不是「导航内部」**。宿主的标题栏、侧栏、底部播放条都被盖住，
 *    视觉上完全脱离宿主，与「插件页面应该是播放器的一部分」的预期不符。
 *
 * 现方案改为两段式，与网易云工坊插件（`spw-netease-plugin`）验证过的做法一致：
 *
 * - **入口**：在 `CaptionButtons`（宿主标题栏右侧「账户 / 刷新应用库」那一条 Row）里，
 *   以 `afterComposable` 追加一个宿主原生 `BasicCaptionButton`。它是**行内同级子项**，
 *   由宿主 Row 布局管理，因此永远不可能与 □/✕ 重叠。
 * - **页面**：用 `replaceComposable` 替换宿主导航页 `ModConfigScreen` 的调用点
 *   （唯一调用方是 `ComposableSingletons$AppNavigation_desktopKt`），并且**只在本插件自己的
 *   路由**（`ScreenRoute$ModConfigContent.pluginId == cn.spw.kugou.lite`）时才绘制酷狗页面，
 *   其它插件、路由缺失、类型不认识一律**回调宿主原方法**。
 *
 * 这样酷狗页面就渲染在宿主导航内容区里，标题栏、侧栏、底部播放条全部保留，
 * 页面外框由宿主 `BasicScreen` 提供，外观与宿主自己的二级页面完全一致。
 *
 * ## 为什么「页面」要挂在 ModConfigScreen 而不是新建路由
 *
 * 宿主的 `ScreenRoute` 是编译进宿主的固定集合（`ScreenRoute$Workshop`、`$ModManagement`、
 * `$ModConfigContent` 等），导航分发是对这些类型的 `when`。插件**无法新增**路由类型，
 * 也无法让宿主的导航分发认识一个新类型。`ModConfigScreen` 是宿主给所有工坊插件预留的
 * 「本插件配置页」容器，它的路由里带 `pluginId`，所以这是唯一能进入导航内部的官方通道。
 *
 * ## 硬约束
 *
 * - 宿主把插件组合期抛出的异常升级成 `FATAL_FAILURE` / `PLUGIN_FATAL` 并**终止进程**，
 *   而 Compose 编译器禁止在 `@Composable` 调用外套 try-catch
 *   （`ILLEGAL_RUN_CATCHING_AROUND_COMPOSABLE`），因此**组合期兜底在框架层面不可能**。
 *   安全必须靠设计保证：所有反射 ABI 在安装阶段预检（[HostSalt.validate]），
 *   页面组件全部是宿主原生组件，不做任何可能抛异常的数据访问。
 * - 绘制前必须校验路由，非本插件路由一律让宿主原方法接管。
 */
object PageInjection {

    private const val TAG = "inject"

    /** 本插件在宿主工坊里的插件 ID，与 `build.gradle.kts` 的 `spmod` 配置一致。 */
    const val PLUGIN_ID = "cn.spw.kugou.lite"

    /**
     * 导航页挂载点：宿主 `ModConfigScreen`。
     *
     * `resolve_target.py` 实测（1.19.3）：
     * - 声明类 `androidx/compose/ui/<假名 0B8F>`（`ModConfigScreenKt`）
     * - 方法 `<假名 037F>`，源码 `ModConfigScreen.kt:49`
     * - 业务参数 `[ScreenRoute$ModConfigContent, <ViewModel 混淆名>]`
     * - **唯一调用方** `com/xuncorp/voxzen/ui/navigation/ComposableSingletons$AppNavigation_desktopKt`
     */
    private const val PAGE_CLASS = "com.xuncorp.voxzen.ui.screen.workshop.ModConfigScreen"
    private const val PAGE_FUNCTION = "ModConfigScreen"

    /** 宿主路由类（未混淆，可直接用类名）。 */
    private const val ROUTE_CLASS = "com.xuncorp.voxzen.ui.navigation.ScreenRoute\$ModConfigContent"

    /**
     * 标题栏按钮锚点：宿主 `DebugCaptionButton`。
     *
     * 它的**唯一调用方**是 `CaptionButtons`（`androidx/compose/ui/<假名 0A88>`，
     * `MainTitleBar.desktop.kt:25`），而 `CaptionButtons` 正是宿主标题栏右侧
     * 「账户 / 刷新应用库」那一条按钮 Row。`afterComposable` 追加的是 Row 的行内同级子项。
     */
    private const val TITLE_ANCHOR_CLASS = "com.xuncorp.voxzen.ui.main.titlebar.DebugCaptionButton"
    private const val TITLE_ANCHOR_FUNCTION = "DebugCaptionButton"

    private var pageHandle: HookHandle? = null
    private var titleHandle: HookHandle? = null
    private var defaultsCompat: AutoCloseable? = null

    /** 上一次 PageHook 的归属判定，用于避免逐帧写日志。 */
    @Volatile
    private var lastWasOurs: Boolean? = null

    /** 宿主 `ModConfigScreen` 的原始静态方法，用于「非本插件路由」时回调。 */
    @Volatile
    private var original: java.lang.reflect.Method? = null

    /**
     * 后台安装。
     *
     * **必须异步**：全量扫描宿主归档约 8 秒，同步跑会卡住宿主启动
     * （实测 20:57:23.2 开始扫，20:57:31.4 才扫完）。
     */
    fun installAsync() {
        Thread({
            runCatching { install() }
                .onFailure { Diag.error("[$TAG] 异步安装异常", it) }
        }, "kugou-lite-install").apply { isDaemon = true }.start()
    }

    /**
     * 安装全部注入。失败回滚并写诊断，**绝不抛异常**。
     *
     * 顺序很重要：先解析页面挂载点（它决定功能是否可用），再解析标题栏入口
     * （没有入口就没有打开方式，但页面本身仍然可用）。
     */
    fun install(): Boolean {
        if (pageHandle != null) {
            Diag.info("[$TAG] 已安装，跳过")
            return true
        }
        return runCatching {
            val idx = HostTargets.index(WorkshopApi::class.java)

            // ---- 1. 原生组件 ABI 预检：缺失就整体放弃，绝不在组合期才发现 ----
            HostSalt.validate()
            if (!HostSalt.isAvailable()) {
                Diag.error("[$TAG] 宿主原生组件不完整，放弃注入（宿主版本可能不受支持）")
                return false
            }

            // ---- 2. 页面挂载点 ----
            val page = HostTargets.resolveByUniqueCaller(idx, PAGE_CLASS, PAGE_FUNCTION)
            if (page == null) {
                Diag.error("[$TAG] 页面挂载点解析失败：" + HostTargets.explain(idx, PAGE_CLASS, PAGE_FUNCTION))
                return false
            }
            Diag.info(
                "[$TAG] 页面 target=${page.className}.${page.methodName} " +
                    "inClass=${page.inClass} params=${page.parameterTypes}"
            )
            original = bindOriginal(page)
            pageHandle = WorkshopApi.hookRegistrar.replaceComposable(
                className = page.className,
                methodName = page.methodName,
                inClass = page.inClass,
                parameterTypes = page.parameterTypes,
                priority = 100,
                content = PageHook,
            )
            PageState.pageInstalled.value = true
            Diag.info("[$TAG] replaceComposable 注册成功（$PAGE_CLASS）")

            // ---- 2b. 放开替换守卫的默认参数掩码 ----
            // 宿主导航调用 ModConfigScreen 时 viewModel 走默认值 → defaultMask=2，
            // 而宿主替换守卫**拒绝非零掩码**，会静默回退执行原组件。
            // 这一步只对「本插件这一个 site」做最小手术，必须成功，否则替换形同虚设。
            defaultsCompat = HostReplacementDefaults.prepare(
                WorkshopApi.hookRegistrar, original!!, page.inClass,
            )
            if (defaultsCompat == null) {
                Diag.error("[$TAG] 默认参数掩码兼容准备失败，替换不会生效，放弃安装")
                uninstall()
                return false
            }

            // ---- 3. 标题栏入口 ----
            val title = HostTargets.resolveByUniqueCaller(idx, TITLE_ANCHOR_CLASS, TITLE_ANCHOR_FUNCTION)
            if (title == null) {
                Diag.warn("[$TAG] 标题栏入口解析失败：" + HostTargets.explain(idx, TITLE_ANCHOR_CLASS, TITLE_ANCHOR_FUNCTION))
            } else {
                Diag.info(
                    "[$TAG] 标题栏 target=${title.className}.${title.methodName} " +
                        "inClass=${title.inClass} params=${title.parameterTypes}"
                )
                titleHandle = WorkshopApi.hookRegistrar.afterComposable(
                    className = title.className,
                    methodName = title.methodName,
                    inClass = title.inClass,
                    parameterTypes = title.parameterTypes,
                    priority = 100,
                    content = TitleButtonHook,
                )
                PageState.titleInstalled.value = true
                Diag.info("[$TAG] afterComposable 注册成功（$TITLE_ANCHOR_CLASS）")
            }
            true
        }.onFailure {
            Diag.error("[$TAG] 安装异常", it)
            uninstall()
        }.getOrDefault(false)
    }

    /**
     * 取出宿主原方法句柄。
     *
     * `replaceComposable` 的语义是「替换该调用点」，所以插件有义务在不想绘制时
     * **自己回调原方法**，否则其它插件的配置页会变成空白。
     * 这里按解析出的类名/方法名/参数列表反射取静态方法 —— 参数列表由 `HostTargets` 给出，
     * 不写死任何混淆名。
     */
    private fun bindOriginal(page: HostTargets.Target): java.lang.reflect.Method? = runCatching {
        val type = Class.forName(page.className, false, HostTargets::class.java.classLoader)
        val want = page.parameterTypes.map { name ->
            when (name) {
                "boolean" -> Boolean::class.javaPrimitiveType
                "int" -> Int::class.javaPrimitiveType
                "long" -> Long::class.javaPrimitiveType
                "float" -> Float::class.javaPrimitiveType
                else -> Class.forName(name, false, HostTargets::class.java.classLoader)
            }
        }
        val params = want + listOf(
            androidx.compose.runtime.Composer::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        )
        val m = type.getDeclaredMethod(page.methodName, *params.toTypedArray())
        check(java.lang.reflect.Modifier.isStatic(m.modifiers)) { "宿主配置页入口必须是静态方法" }
        m.isAccessible = true
        m
    }.onFailure { Diag.warn("[$TAG] 原方法句柄获取失败：" + (it.message ?: it.javaClass.simpleName)) }
        .getOrNull()

    /** 卸载。**幂等**。 */
    fun uninstall() {
        runCatching { NavigatorBridge.uninstall() }.onFailure { Diag.error("[$TAG] 导航桥卸载异常", it) }
        runCatching { pageHandle?.unhook() }.onFailure { Diag.error("[$TAG] 页面卸载异常", it) }
        runCatching { defaultsCompat?.close() }.onFailure { Diag.error("[$TAG] 默认参数兼容还原异常", it) }
        runCatching { titleHandle?.unhook() }.onFailure { Diag.error("[$TAG] 标题栏卸载异常", it) }
        pageHandle = null
        defaultsCompat = null
        titleHandle = null
        original = null
        lastWasOurs = null
        PageState.pageInstalled.value = false
        PageState.titleInstalled.value = false
        PageState.close()
        Diag.info("[$TAG] 已卸载")
    }

    /**
     * 页面 Hook。
     *
     * 用**显式对象子类**而不是内联 lambda：确保 `Content` 是具名 override 方法，
     * 避免 lambda 被放进 `ComposableSingletons$XxxKt` 后在插件 ClassLoader 下初始化失败。
     *
     * 逻辑：
     * 1. 读第 0 个业务参数（当前 `ScreenRoute`）；
     * 2. 不是 `ModConfigContent` 或 `pluginId` 不是本插件 → 回调宿主原方法，原样绘制；
     * 3. 是本插件 → 画酷狗页面。
     */
    private object PageHook : ComposableHook() {
        @Composable
        override fun Content(call: UiHookCall) {
            // 组合期读一次宿主 Navigator 并缓存 —— 页面 hook 位于宿主导航组合树内，
            // 所以这里能读到 LocalNavigator。标题栏按钮点击时用缓存值导航。
            NavigatorBridge.rememberHostNavigator()
            val route = runCatching { call.arguments.firstOrNull() }.getOrNull()
            // 注意：这里**不能**逐帧写日志。Content 每次组合都会执行，
            // 落盘 IO 会拖慢宿主帧率，且日志会迅速膨胀。只在判定结果变化时记一次。
            val ours = isOurRoute(route)
            if (ours != lastWasOurs) {
                lastWasOurs = ours
                Diag.info(
                    "[$TAG] PageHook 路由切换 ours=" + ours +
                        " route=" + (route?.javaClass?.name ?: "null")
                )
            }
            if (!ours) {
                drawOriginal(call, currentComposer)
                return
            }
            KugouLitePage()
        }
    }

    /**
     * 标题栏按钮 Hook。
     *
     * 只在宿主 `CaptionButtons` 那一行里追加一个原生 caption 按钮。
     * 按钮内容是**自绘的频谱/音符图标**（`Canvas`），因为宿主不提供可复用的图标资源；
     * 但按钮的**外框、尺寸、悬停态、无障碍语义**全部来自宿主 `BasicCaptionButton`，
     * 所以视觉与宿主其它 caption 按钮一致，且严格参与 Row 布局。
     */
    private object TitleButtonHook : ComposableHook() {
        @Composable
        override fun Content(call: UiHookCall) {
            // 标题栏同样位于宿主组合树内，在这里读 Navigator 可以覆盖
            // 「用户从未打开过页面 hook 就点了按钮」的情形。
            NavigatorBridge.rememberHostNavigator()
            KugouLiteTitleButton()
        }
    }

    /**
     * 判断当前路由是否属于本插件。
     *
     * 三重校验，任一不满足都返回 false（宁可回调原方法，也不误画）：
     * 1. 路由对象非空且类名精确等于宿主 `ScreenRoute$ModConfigContent`；
     * 2. 能反射取到 `getPluginId()`；
     * 3. 插件 ID 精确等于 [PLUGIN_ID]。
     *
     * 这些全部是普通反射，不涉及 Compose，异常由 `runCatching` 吃掉 ——
     * 本函数**不是** `@Composable`，所以包 try-catch 完全合法。
     */
    private fun isOurRoute(route: Any?): Boolean {
        if (route == null) return false
        return runCatching {
            if (route.javaClass.name != ROUTE_CLASS) return false
            val id = route.javaClass.getMethod("getPluginId").invoke(route) as? String
            id == PLUGIN_ID
        }.getOrDefault(false)
    }

    /**
     * 回调宿主原始配置页。
     *
     * <p>宿主导航调用点用的是默认 ViewModel（`defaultMask` 第 1 位为 1 → 掩码 2），
     * 但为了不丢宿主显式传入的 ViewModel，这里按「第 1 个参数是否为 null」决定掩码：
     * null 表示宿主走了默认值，传 2；非 null 表示显式传入，传 0。</p>
     *
     * <p>**`composer` 必须由调用方传入**：`currentComposer` 是 `@Composable` getter，
     * 只能在 composable 上下文里读。本函数是普通函数，读它编译不过。</p>
     *
     * <p>**这里也不能包 try-catch**：本函数内部虽然不直接写 `@Composable` 调用，
     * 但 `method.invoke` 的目标就是宿主 composable 入口，Compose 编译器会把
     * `runCatching` 判定为「包住 composable 调用」并报
     * `ILLEGAL_RUN_CATCHING_AROUND_COMPOSABLE`。宿主的 `replaceComposable` 语义本就
     * 要求「无有效注册时执行原组件」，所以直接调用、让异常按普通 Compose 异常传播，
     * 与宿主自身行为一致。</p>
     */
    private fun drawOriginal(call: UiHookCall, composer: androidx.compose.runtime.Composer) {
        val method = original ?: return
        val args = call.arguments
        val viewModel = args.getOrNull(1)
        method.invoke(
            null, args.getOrNull(0), viewModel, composer, 0,
            if (viewModel == null) 2 else 0,
        )
    }
}

