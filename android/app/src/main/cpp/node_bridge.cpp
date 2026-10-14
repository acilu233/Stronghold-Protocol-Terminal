// ---------------------------------------------------------------------------
// node_bridge.cpp —— Java 世界通往 libnode 的唯一一扇门
//
// 做的事情很少，但每一件都必要：
//   1. 清掉代理类环境变量（不然 tools/assets/env-proxy.mjs 会尝试 spawn 自己，
//      而安卓上没有 child_process —— 当场死）
//   2. 把 Java String[] 转成 argv
//   3. chdir 到 node 项目根目录（server 用 import.meta.url 反推 ROOT，cwd 对了才找得到 public/）
//   4. 调 node::Start —— 这一句是阻塞的，直到 Node 退出才返回
// ---------------------------------------------------------------------------
#include <jni.h>
#include <android/log.h>
#include <unistd.h>

#include <string>
#include <vector>

#include "node.h"

#define LOG_TAG "SHL-Native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

/** 这些变量一旦存在就会让游戏的素材脚本走「重启自己」的分支，安卓上必死。 */
void clearProxyEnv() {
    static const char* kNames[] = {
        "HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy",
        "ALL_PROXY", "all_proxy", "NO_PROXY", "no_proxy",
        "NODE_USE_ENV_PROXY", "npm_config_proxy", "npm_config_https_proxy"
    };
    for (const char* n : kNames) {
        unsetenv(n);
    }
}

}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_stronghold_launcher_NodeRunner_nativeStartNode(JNIEnv* env, jclass /*clazz*/,
                                                        jobjectArray jargs, jstring jcwd) {
    clearProxyEnv();

    // ---- argv ----
    const jsize argc = jargs != nullptr ? env->GetArrayLength(jargs) : 0;
    std::vector<std::string> store;
    store.reserve(static_cast<size_t>(argc));
    for (jsize i = 0; i < argc; ++i) {
        jstring js = static_cast<jstring>(env->GetObjectArrayElement(jargs, i));
        if (js == nullptr) {
            store.emplace_back("");
            continue;
        }
        const char* c = env->GetStringUTFChars(js, nullptr);
        store.emplace_back(c != nullptr ? c : "");
        if (c != nullptr) env->ReleaseStringUTFChars(js, c);
        env->DeleteLocalRef(js);
    }

    std::vector<char*> argv;
    argv.reserve(store.size() + 1);
    for (std::string& s : store) argv.push_back(const_cast<char*>(s.c_str()));
    argv.push_back(nullptr);  // argv 必须以 nullptr 结尾

    // ---- 工作目录 ----
    if (jcwd != nullptr) {
        const char* c = env->GetStringUTFChars(jcwd, nullptr);
        if (c != nullptr) {
            if (chdir(c) != 0) {
                LOGE("chdir(%s) failed", c);
            } else {
                LOGI("cwd = %s", c);
            }
            env->ReleaseStringUTFChars(jcwd, c);
        }
    }

    LOGI("node::Start argc=%d", argc);
    for (int i = 0; i < argc; ++i) LOGI("  argv[%d] = %s", i, argv[i]);

    const int rc = node::Start(argc, argv.data());

    LOGI("node::Start returned %d", rc);
}
