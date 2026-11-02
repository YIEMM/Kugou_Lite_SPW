import com.xuncorp.spw.workshop.gradle.PluginPermission
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("java-library")
    id("org.jetbrains.kotlin.kapt")
    alias(libs.plugins.jetbrainsKotlinJvm)
    alias(libs.plugins.compose.compiler)
    id("com.xuncorp.spw.workshop") version "0.1.0-dev23"
}

group = "cn.spw.kugou"
version = "0.2.0"

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget = JvmTarget.JVM_25
        // Kotlin 2.4.20 不再接受 -Xjvm-default=all
        freeCompilerArgs.add("-jvm-default=enable")
        optIn.add("com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi")
    }
}

dependencies {
    compileOnly(libs.spw.workshop.api)
    kapt(libs.spw.workshop.api)

    // Compose 与 kotlin-stdlib 由宿主提供，必须 compileOnly（见上方说明）
    compileOnly(libs.compose.ui)
    compileOnly(libs.compose.foundation)
    compileOnly(kotlin("stdlib"))

    // ASM 是插件自己的依赖，要打包进 lib/
    implementation("org.ow2.asm:asm:9.10.1")

    testImplementation(kotlin("test"))
    testImplementation(libs.compose.runtime)
    // 元数测试要用 BoxScope / ColumnScope / PaddingValues 声明 composable lambda 类型
    testImplementation(libs.compose.foundation)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
    // 单元测试要读真实宿主归档；找不到时测试内部会 skip
    systemProperty(
        "spw.host.archive",
        System.getenv("SPW_HOST_ARCHIVE")
            ?: "E:/steam/steamapps/common/Salt Player for Windows/app/ffmpeg-x64.dll"
    )
}

spmod {
    PluginClass = "cn.spw.kugou.lite.KugouLitePlugin"
    PluginId = "cn.spw.kugou.lite"
    PluginName = "Kugou Lite"
    PluginDescription = "把酷狗曲库接入 Salt Player：搜索、歌单、榜单、每日推荐，点歌即播。"
    PluginVersion = version.toString()
    PluginProvider = "spw-kugou-lite"
    PluginHasConfig = true
    // CLASS_TRANSFORM：页面注入必需
    // LIBRARY_READ：切歌监听要读「当前播放曲目」（WorkshopApi.playback()
    //                .getCurrentMediaItem()），缺它抛 PluginPermissionDeniedException
    PluginPermissions = listOf(
        PluginPermission.CLASS_TRANSFORM,
        PluginPermission.LIBRARY_READ,
    )
    PluginOpenSourceUrl = "https://github.com/"
}

/**
 * 原生组件校验。
 *
 * `src/main/resources/native/` 下的两个 dll 由 `sidecar/build_native.ps1`
 * 生成（Rust 边车 + JNI 桥接层）。它们**体积大且是构建产物**，
 * 因此不进版本库（见 `.gitignore`），但打包时必须存在。
 *
 * 这里在 `processResources` 之前做一次检查，把「忘了跑 build_native.ps1」
 * 变成一条清晰的构建失败提示，而不是打出一个缺 dll 的包、等用户装上才发现。
 */
val nativeDir = layout.projectDirectory.dir("src/main/resources/native")
val requiredNativeLibs = listOf("kugou_server.dll", "kugou_bridge.dll")

val checkNativeLibs = tasks.register("checkNativeLibs") {
    description = "校验 Rust 边车与 JNI 桥接 dll 是否已构建"
    group = "verification"

    doLast {
        val missing = requiredNativeLibs.filter { !nativeDir.file(it).asFile.isFile }
        if (missing.isNotEmpty()) {
            throw GradleException(
                """
                |缺少原生组件：${missing.joinToString(", ")}
                |
                |请先执行（需要 rustup + Visual Studio C++ 工具链）：
                |    cd sidecar
                |    .\build_native.ps1
                |
                |生成位置：src/main/resources/native/
                |""".trimMargin()
            )
        }
        val total = requiredNativeLibs.sumOf { nativeDir.file(it).asFile.length() }
        logger.lifecycle(
            "原生组件就绪：${requiredNativeLibs.joinToString(", ")}（共 ${"%.2f".format(total / 1024.0 / 1024.0)} MB）"
        )
    }
}

tasks.named("processResources") {
    dependsOn(checkNativeLibs)
}
