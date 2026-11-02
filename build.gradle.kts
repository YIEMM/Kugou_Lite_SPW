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
    PluginPermissions = listOf(PluginPermission.CLASS_TRANSFORM)
    PluginOpenSourceUrl = "https://github.com/"
}
