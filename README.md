<div align="center">

# 卫戍协议：终端

**Stronghold-Protocol : Terminal**

《卫戍协议：盟约》的 **非官方安卓启动器** —— 把上游同人复刻一键装进手机，直接在手机上跑起来。

![license](https://img.shields.io/badge/license-GPL--3.0--or--later-blue)
![platform](https://img.shields.io/badge/platform-Android%2011%2B%20(arm64)-3DDC84)
![made by AI](https://img.shields.io/badge/made%20by-100%25%20AI-ff69b4)
![version](https://img.shields.io/badge/version-v0.1-orange)

</div>

---

> [!IMPORTANT]
> ## 🤖 本项目完全由 AI 制作
> 本项目的**全部代码、界面、架构与本文档，100% 由 AI（WorkBuddy）编写**，人类只负责提出需求与真机测试。
> 请以「AI 生成项目」的预期来使用它 —— 可能有个别粗糙、欠打磨之处，欢迎通过 Issue 反馈。

---

> [!TIP]
> ## 🙏 致敬上游作者
> **这个仓库只是「启动器外壳」；游戏本体（代码、美术、Spine 模型、音频、玩法、数据）全部来自上游同人复刻项目：**
>
> # ➡️ [**sganggs / Stronghold-Protocol**](https://github.com/sganggs/Stronghold-Protocol) ⭐
>
> **没有上游作者的开源，就没有这个启动器。请务必去给上游点一个 Star。**
>
> 同时致敬《明日方舟》版权方 **Hypergryph（上海鹰角网络）/ Yostar** —— 相关素材版权归其所有，
> 本项目**不包含、不分发任何游戏素材**。

---

## 📖 项目概要

**卫戍协议：终端** 是《卫戍协议：盟约》同人复刻（上游 [sganggs/Stronghold-Protocol](https://github.com/sganggs/Stronghold-Protocol)）的**安卓启动器**。

上游游戏本体是一套 Node.js 服务 + 浏览器前端，本来需要在电脑上开服、再用浏览器连。本项目把整套运行时**塞进一个安卓 App**：

- App 内置 **Node.js 运行时（libnode）**，无需电脑、无需 Root，手机上直接跑；
- 用内置 **WebView** 直接打开本机游戏页面，等同"把电脑和浏览器合二为一"；
- 对游戏的所有增强（联机切换、邀请链接直达等）**全部通过运行时注入实现，不改上游 `game/` 一个字节**。

> 本仓库是**空壳**：APK 里**不含任何游戏代码或素材**。游戏本体在首次启动时由启动器从上游 Releases 自动下载（**完整包**，含全部素材）。

---

## ✨ 功能特性

| 功能 | 说明 |
|---|---|
| 🚀 一键运行 | 内置 Node.js 运行时，手机上直接跑游戏，无需电脑、无需 Root |
| 📦 空壳安装 | APK 不含游戏代码/素材；首次启动自动从上游 Releases 拉取 |
| ⬇️ 首装自动下载 | 下载上游**完整包**（约 556MB，含全部美术 / 音频素材），**多镜像并发测速**自动选最快 |
| 🔄 应用内更新 | 菜单「检查更新」→ 展示该版本**更新日志** → 原地进度条下载**增量包**（约几 MB）→ 自动重启应用 |
| 🔍 启动静默检查 | 每次启动后台检查更新，有新版本自动静默下载，不打断当前游戏 |
| 🌐 联机切换 | 局域网 / 外部连接（内网穿透）一键切换 |
| 🔗 邀请链接直达 | 把完整邀请链接粘进大厅密钥框，直接跳转加入 |
| 📋 复制联机地址 | 一键复制本机局域网地址发给好友 |
| 🎛 顶部折叠菜单 | 返回本地 / 检查更新 / 复制地址 / 刷新 / 联机方式 / 关于 |
| 📱 全屏适配 | 隐藏系统栏，适配刘海 / 挖孔屏 |
| 👤 多账号 | 启动器侧账号系统（单机 / 联机数据互不干扰） |
| 🧩 零改游戏 | 全部增强走注入，绝不动上游 `game/` |

---

## 🚀 使用方法

### 1. 安装

从本仓库的 **Releases** 下载最新 APK 安装即可（要求 **Android 11+ / arm64-v8a**）。
首次安装若提示"未知来源"，允许安装即可。

### 2. 首次启动

首次启动会自动从上游下载**完整包**（约 **556MB**，已含全部美术 / 音频素材，**建议连 WiFi**）。开屏页会显示下载进度，耐心等它跑完。

### 3. 单机游玩

启动进入大厅后，创建 / 加入房间即可开玩。

### 4. 局域网联机（同一个 WiFi）

1. 点顶部小箭头 **⬆** 展开菜单；
2. 「联机方式」切到 **局域网**；
3. 点「复制联机地址」，把地址发给同一 WiFi 下的好友；
4. 好友用浏览器打开该地址即可加入。

### 5. 公网联机（不在一起）

用內网穿透工具（如樱花 frp、Tailscale、ZeroTier 等）拿到一个公网地址后：

1. 菜单「联机方式」切到 **外部连接**；
2. 把穿透地址粘进输入框 → 点「加入」；
3. 或直接把对方给的**完整邀请链接**粘到大厅的密钥框，一键直达。

### 6. 更新到新版本

菜单 → **「检查更新」**：会显示当前版本、上游最新版本以及**该版本的更新日志**，点「立即更新」后**原地显示下载进度条**，下载完自动重启并生效。
（另外每次启动也会在后台静默检查一次，有更新自动下载，下次启动生效。）

### 7. 顶部折叠菜单

点屏幕顶部中间的**小箭头**展开，包含：
**返回本地 / 检查更新 / 复制联机地址 / 刷新页面 / 联机方式 / 关于**。
箭头可以左右拖动，避开画面遮挡。

---

## 🛠 自行构建

> 由于是空壳，构建**不需要**上游 `game/` 目录。

1. 安装：**JDK 17**、**Android SDK / NDK**、**Git LFS**（仓库里的 `libnode.so` 走 LFS）；
2. `git clone` 本仓库（会自动拉取 LFS 大文件；若只拿到指针，执行 `git lfs pull`）；
3. 用 Gradle 8.9 构建：

```bash
cd android
gradle assembleDebug        # 产物：app/build/outputs/apk/debug/app-debug.apk
```

版本号在 `android/app/build.gradle` 的 `versionCode` / `versionName`。

---

## 📁 目录结构

```
Stronghold-Protocol-Terminal/
├── README.md                    ← 本文件
├── LICENSE                      GPL-3.0 全文
├── NOTICE.md                    版权与使用声明
├── THIRD-PARTY-NOTICES.md       第三方组件清单
├── third-party/                 第三方许可证全文（Node.js 等）
├── HANDOFF.md                   开发交接与修改日志
└── android/                     安卓工程（Gradle）
    └── app/
        ├── build.gradle         版本号 / 打包 / payload
        ├── bootstrap/
        │   └── android-main.mjs 安卓专用 Node 入口（起服务、首启下载素材）
        └── src/main/
            ├── AndroidManifest.xml
            ├── java/com/stronghold/launcher/   启动器 Java 源码
            ├── res/                            资源（布局 / 图标 / 颜色 / 字符串）
            ├── assets/inject/                  注入脚本（改游戏 UI 的唯一手段）
            ├── cpp/                            JNI 胶水（nodebridge）
            └── jniLibs/arm64-v8a/libnode.so    内嵌 Node 运行时（Git LFS）
```

**Java 源码**（`android/app/src/main/java/com/stronghold/launcher/`）：

| 文件 | 作用 |
|---|---|
| `MainActivity.java` | 主界面：启动编排、全屏 / 挖孔、WebView、注入、顶部菜单、检查更新 UI |
| `NodeService.java` | 前台服务：起 Node 前完成首装 / 更新 → 起 Node → 健康检查 |
| `NodeRunner.java` | 加载 `libnode.so` 并调用 `node::Start` |
| `ProjectInstaller.java` | 把 APK 内 `android-main.mjs` 铺到应用目录 |
| `GameUpdater.java` | 下载后处理：解压完整包 / 增量包、读写版本戳与 pending 标记 |
| `UpdateRepo.java` | 上游访问层：版本检查、更新日志、三种包地址、**多镜像并发测速下载** |
| `UpdateService.java` | 更新下载前台服务（增量包），广播进度 |
| `UserStore.java` | 启动器侧账号系统 |

> ⚠️ 维护约定：**本文件描述当前目录结构，每次增删目录 / 文件后请同步更新本文件。**

---

## ⚖️ 许可证与声明

- 本仓库（启动器壳）以 **GPL-3.0-or-later** 发布，全文见 [`LICENSE`](LICENSE)（与上游一致）。
- 本启动器是上游同人复刻《卫戍协议：盟约》的**非官方安卓启动器**，与上游作者、
  与上海鹰角网络 / Yostar（《明日方舟》版权方）**均无关联、未获授权**。
- 本仓库**不含**任何游戏代码或素材；游戏本体由启动器在运行时从上游 Releases 下载。
  游戏素材版权归原权利人，**仅限学习与个人非商业使用，严禁任何形式的盈利**。
- 第三方组件（`libnode.so` = Node.js，MIT）见 [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md)。
- 完整条款与英文摘要见 [`NOTICE.md`](NOTICE.md)。

---

## 🙏 致谢

- **[sganggs/Stronghold-Protocol](https://github.com/sganggs/Stronghold-Protocol)** —— 上游同人复刻，本项目的全部游戏内容来源。
- **[nodejs-mobile](https://github.com/nodejs-mobile)** / **[Node.js](https://nodejs.org/)** —— 内嵌的 Node 运行时。
- **上海鹰角网络（Hypergryph）/ Yostar** —— 《明日方舟》及「卫戍协议」玩法版权方。
- **WorkBuddy** —— 本项目的全部代码与文档由 AI 编写。
