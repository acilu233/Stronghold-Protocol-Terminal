package com.stronghold.launcher;

/**
 * libnode 的 Java 侧入口。
 *
 * 加载顺序不能变：
 *   1. libc++_shared.so  —— NDK 的 C++ 运行时，libnode.so 依赖它
 *   2. libnode.so        —— nodejs-mobile 预编译的 Node 运行时
 *   3. libnodebridge.so  —— 本项目自己编译的 JNI 胶水（node_bridge.cpp）
 *
 * nativeStartNode 是阻塞调用：它在**当前线程**上跑 Node 的事件循环，直到进程退出才返回。
 * 所以必须在专用线程里调用（见 NodeService）。
 */
public final class NodeRunner {

    public static final String TAG = "SHL-Native";

    private static boolean loaded = false;

    private NodeRunner() {}

    public static synchronized void load() {
        if (loaded) return;
        System.loadLibrary("c++_shared");
        System.loadLibrary("node");
        System.loadLibrary("nodebridge");
        loaded = true;
    }

    /** 阻塞直到 Node 退出。 */
    public static void startNode(String[] args, String cwd) {
        load();
        nativeStartNode(args, cwd);
    }

    private static native void nativeStartNode(String[] args, String cwd);
}
