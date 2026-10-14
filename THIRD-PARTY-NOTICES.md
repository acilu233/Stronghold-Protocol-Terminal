# 第三方组件清单

| 组件 | 位置 | 许可证 | 说明 |
|---|---|---|---|
| Node.js（libnode） | `android/app/src/main/jniLibs/arm64-v8a/libnode.so` | MIT | 内嵌的 Node.js 运行时二进制；许可证全文见 [third-party/Node.js-LICENSE.txt](third-party/Node.js-LICENSE.txt) |
| Node.js 公共头文件 | `android/app/src/main/cpp/include/node/**` | MIT | 编译 JNI 胶水（`node_bridge.cpp`）所需，随 Node.js 分发 |
| AndroidX / Gradle / NDK 等构建期依赖 | 不随仓库分发 | 各自 | 由构建工具按需获取，不进入本仓库 |

> 游戏本体（含《明日方舟》素材）**不随本仓库分发**，由启动器运行时从上游 Releases 下载。
> 其版权与使用限制见 [NOTICE.md](NOTICE.md)。
