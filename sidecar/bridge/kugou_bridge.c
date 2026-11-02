/*
 * kugou_bridge — JNI 桥接层
 *
 * 作用：把 Java 的 native 方法转发到上游 kugou_server.dll 的纯 C 函数。
 *
 * 为什么需要它：
 *   上游 kugou_server.dll（AGPL-3.0，派生自 md3Music）只导出纯 C 函数
 *   start_server / stop_server / is_server_running / get_server_port，
 *   没有导出 JNI 符号（它的 JNI 导出只编译进 Android 目标）。
 *   JVM 只能调用带 Java_* 符号的库，所以需要这一层。
 *
 * 设计要点：
 *   1. 上游 dll 的位置由 Java 侧显式传入（绝对路径），不靠 PATH 猜测。
 *   2. 用 LoadLibrary 动态加载而不是隐式链接：两个 dll 都在临时解压目录，
 *      隐式链接要求在进程启动时就能找到，做不到。
 *   3. 上游源码零修改 —— 满足 AGPL 合规要求，也便于同步上游。
 *
 * 许可证：MIT（本文件是我们的代码，不是上游代码）
 */

#include <jni.h>
#include <windows.h>
#include <stdio.h>

/* 函数指针类型，与上游 lib.rs 的 extern "C" 签名一一对应 */
typedef int  (*fn_start_server)(int port, const char *data_dir);
typedef void (*fn_stop_server)(void);
typedef int  (*fn_is_server_running)(void);
typedef int  (*fn_get_server_port)(void);

static HMODULE g_core = NULL;
static fn_start_server     g_start_server = NULL;
static fn_stop_server      g_stop_server = NULL;
static fn_is_server_running g_is_server_running = NULL;
static fn_get_server_port  g_get_server_port = NULL;

/* 包名 cn.spw.kugou.lite.sidecar，类 NativeBridge */
#define JNI_KLASS "cn_spw_kugou_lite_sidecar_NativeBridge"

/*
 * 类: cn.spw.kugou.lite.sidecar.NativeBridge
 * 方法: nInit(coreDllPath: String)
 *
 * 加载上游 dll 并解析四个导出符号。
 * 任何一步失败都抛 IllegalStateException，让 Java 侧能捕获并降级。
 */
JNIEXPORT void JNICALL
Java_cn_spw_kugou_lite_sidecar_NativeBridge_nInit(JNIEnv *env, jclass cls, jstring core_path)
{
    (void)cls;

    if (g_core != NULL) {
        return; /* 已初始化，幂等 */
    }

    if (core_path == NULL) {
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IllegalStateException"),
                         "core dll path is null");
        return;
    }

    const char *path = (*env)->GetStringUTFChars(env, core_path, NULL);
    if (path == NULL) {
        return; /* OutOfMemoryError 已由 JVM 抛出 */
    }

    g_core = LoadLibraryA(path);
    if (g_core == NULL) {
        char msg[512];
        snprintf(msg, sizeof(msg),
                 "LoadLibrary failed for '%s' (GetLastError=%lu)", path, GetLastError());
        (*env)->ReleaseStringUTFChars(env, core_path, path);
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IllegalStateException"), msg);
        return;
    }
    (*env)->ReleaseStringUTFChars(env, core_path, path);

    g_start_server      = (fn_start_server)      GetProcAddress(g_core, "start_server");
    g_stop_server       = (fn_stop_server)       GetProcAddress(g_core, "stop_server");
    g_is_server_running = (fn_is_server_running) GetProcAddress(g_core, "is_server_running");
    g_get_server_port   = (fn_get_server_port)   GetProcAddress(g_core, "get_server_port");

    if (!g_start_server || !g_stop_server || !g_is_server_running || !g_get_server_port) {
        FreeLibrary(g_core);
        g_core = NULL;
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IllegalStateException"),
                         "kugou_server.dll is missing one or more expected exports");
        return;
    }
}

JNIEXPORT jint JNICALL
Java_cn_spw_kugou_lite_sidecar_NativeBridge_nStartServer(JNIEnv *env, jclass cls, jint port, jstring data_dir)
{
    (void)cls;

    if (g_start_server == NULL) {
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IllegalStateException"),
                         "bridge not initialized; call nInit first");
        return 0;
    }
    if (data_dir == NULL) {
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IllegalArgumentException"),
                         "data dir is null");
        return 0;
    }

    const char *dir = (*env)->GetStringUTFChars(env, data_dir, NULL);
    if (dir == NULL) {
        return 0;
    }

    int result = g_start_server((int)port, dir);

    (*env)->ReleaseStringUTFChars(env, data_dir, dir);
    return (jint)result;
}

JNIEXPORT void JNICALL
Java_cn_spw_kugou_lite_sidecar_NativeBridge_nStopServer(JNIEnv *env, jclass cls)
{
    (void)env;
    (void)cls;
    if (g_stop_server != NULL) {
        g_stop_server();
    }
}

JNIEXPORT jint JNICALL
Java_cn_spw_kugou_lite_sidecar_NativeBridge_nIsServerRunning(JNIEnv *env, jclass cls)
{
    (void)env;
    (void)cls;
    if (g_is_server_running == NULL) {
        return 0;
    }
    return (jint)g_is_server_running();
}

JNIEXPORT jint JNICALL
Java_cn_spw_kugou_lite_sidecar_NativeBridge_nGetServerPort(JNIEnv *env, jclass cls)
{
    (void)env;
    (void)cls;
    if (g_get_server_port == NULL) {
        return 0;
    }
    return (jint)g_get_server_port();
}

/*
 * JVM 卸载时释放上游 dll。
 * 注意：宿主退出时 JVM 可能来不及调用它（进程直接终止），
 * 这没问题 —— 操作系统会回收。
 */
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved)
{
    (void)vm;
    (void)reserved;
    return JNI_VERSION_1_8;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM *vm, void *reserved)
{
    (void)vm;
    (void)reserved;
    if (g_core != NULL) {
        FreeLibrary(g_core);
        g_core = NULL;
    }
}
