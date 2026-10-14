# 卫戍协议：终端 · Stronghold-Protocol: Terminal

《明日方舟》季节玩法「卫戍协议：盟约」的**非官方同人复刻**（[sganggs/Stronghold-Protocol](https://github.com/sganggs/Stronghold-Protocol)）的**安卓启动器**：把整套 Node.js 游戏运行时塞进一个安卓 App，用手机即开即玩，无需电脑、无需 Root。

![version](https://img.shields.io/badge/version-0.1-2ea44f)
![license](https://img.shields.io/badge/code%20license-GPL--3.0--or--later-blue)
![platform](https://img.shields.io/badge/platform-Android%2011%2B%20%7C%20arm64-3DDC84)
![made by AI](https://img.shields.io/badge/made%20by-100%25%20AI-ff69b4)

## 声明

> [!IMPORTANT]
> ### 🤖 本项目完全由 AI 制作 （是的，包括这个README）
> 本项目的**全部代码、界面、架构与本文档，100% 由 AI（WorkBuddy）编写**，人类只负责提出需求与真机测试。
> 请以「AI 生成项目」的预期来使用它 —— 可能有个别粗糙、欠打磨之处，欢迎通过 Issue 反馈。

> [!TIP]
> ### 🙏 致敬上游作者
> 这个仓库只是「**外壳**」；游戏本体（代码、美术、Spine 模型、音频、玩法、数据）**全部来自上游同人复刻项目**：
>
> ### ➡️ [sganggs / Stronghold-Protocol](https://github.com/sganggs/Stronghold-Protocol) ⭐
>
> **没有上游作者的开源，就没有这个启动器。请务必去给上游点一个 Star。**
> 同时致敬《明日方舟》版权方 **Hypergryph（上海鹰角网络）/ Yostar** —— 相关素材版权归其所有，本项目**不分发任何游戏素材**。

> [!IMPORTANT]
> - 本项目是**非官方启动器**，与上游同人复刻作者、与上海鹰角网络科技有限公司（Hypergryph）、Yostar 及其关联方**没有任何关系**，未获其授权或认可。
> - 《明日方舟》及「卫戍协议」相关的名称、角色、美术、音乐、音效、文本与数据等素材，版权归原权利人所有，这些素材**不适用**本项目的 GPL-3.0 许可证。
> - 本仓库**不含**任何游戏代码或素材：APK 只含启动器壳，游戏本体在首次启动时由启动器从上游 Releases 下载。请勿将下载到的素材用于本启动器以外的用途或单独再分发。
> - 仅供学习交流与个人非商业使用，**严禁任何形式的盈利**（售卖 / 付费分发 / 收费开服 / 广告 / 打赏 / 会员等）。完整条款见 [NOTICE.md](NOTICE.md)。
> - 权利人如认为本项目侵犯其权益，请通过 Issue 联系，我们会**立即删除**相关内容。
> - 本项目按「现状」提供，**不提供任何担保**，使用风险自负。

English summary: [below](#english).

## 目录

- [声明](#声明) · [简介](#简介) · [功能一览](#功能一览)
- [快速开始](#快速开始)：[下载 APK](#下载-apk) · [首次启动](#首次启动) · [系统要求](#系统要求)
- [联机](#联机) · [操作](#操作) · [文档](#文档) · [项目结构](#项目结构)
- [从源码构建](#从源码构建开发者) · [许可证](#许可证) · [致谢](#致谢) · [贡献](#贡献)

## 简介

上游游戏本体是一套 Node.js 服务 + 浏览器前端：本来需要在电脑上开服，再用浏览器连过去玩。本项目把这个运行时**整个塞进一个安卓 App**，让手机自己就能开服游玩（单机 / 联机都行）：

- **内置 Node.js 运行时（libnode）**：不需要电脑、不需要 Root，装上就能跑；
- **内置 WebView** 直接打开本机游戏页面，相当于「把电脑和浏览器合二为一」；
- 对游戏的增强（联机方式切换、邀请链接直达等）**全部通过运行时注入实现，不改上游一个字节**。

本仓库是**空壳**：APK 里**不含任何游戏代码或素材**，只含启动器与 Node 运行时。**游戏本体在首次启动时由启动器从上游 Releases 自动下载**（完整包，含全部素材），下载完自动落位、直接开玩。

> 与上游的关系：上游可在 Windows / macOS / Linux 上开服，本启动器则是**安卓端的开箱即用载体**；游戏内容本身完全由上游提供与维护，本仓库不做任何游戏内容。

## 功能一览

- **一键运行**：内置 Node.js 运行时，手机上直接跑，无需电脑、无需 Root。
- **空壳安装**：APK 不含游戏代码 / 素材；首次启动自动从上游 Releases 拉取，下载完自动落位。
- **首装自动下载**：下载上游**完整包**（约 556 MB，含全部美术 / 音频素材），**多镜像并发测速**自动选最快。
- **应用内更新**：菜单「检查更新」→ 展示该版本**更新日志** → 原地进度条下载**增量包**（约几 MB）→ 自动重启应用。
- **启动静默检查**：每次启动后台检查更新，有新版本自动静默下载，下次启动生效，不打断当前游戏。
- **联机**：局域网 / 外部连接（内网穿透）一键切换。
- **邀请链接直达**：把完整邀请链接粘进大厅密钥框，直接跳转加入。
- **复制联机地址**：一键复制本机局域网地址发给好友。
- **顶部折叠菜单**：返回本地 / 检查更新 / 复制地址 / 刷新 / 联机方式 / 关于；箭头可左右拖动避开画面。
- **全屏适配**：隐藏系统栏，适配刘海 / 挖孔屏（含横屏左侧挖孔）。
- **多账号**：启动器侧账号系统，单机 / 联机数据互不干扰。
- **零改游戏**：全部增强走注入，绝不动上游 `game/` 一个字节。

## 快速开始

### 下载 APK

到 [Releases](../../releases/latest) 下载最新版本的 APK 安装即可：

- `Stronghold-Protocol-Terminal-v<版本>-release.apk`：**推荐**，正式版（体积更小）。
- `Stronghold-Protocol-Terminal-v<版本>-debug.apk`：调试版（含调试符号，一般用不到）。

要求 **Android 11+ / arm64-v8a**。首次安装若提示「未知来源」，允许即可。

> 只负责玩的话，看到这里就够了。想自己改代码 / 自己编译，见 [从源码构建](#从源码构建开发者)。

### 首次启动

首次启动会自动从上游下载**完整包**（约 **556 MB**，已含全部美术 / 音频素材，**建议连 WiFi**）。开屏页会显示下载进度（文件大小与百分比），下完自动解压并启动，耐心等它跑完即可。下载走多镜像并发测速，并校验下载物为有效 zip，失败会自动换镜像。

### 系统要求

| 项目 | 要求 |
|---|---|
| 设备 | Android 11（API 30）及以上，arm64-v8a |
| 存储 | 游戏本体解压后约 700–850 MB（+ 首次下载的压缩包，装完可清缓存）|
| 网络 | 首次启动下载完整包约 556 MB（建议 WiFi）；之后对局流量很小 |
| 权限 | 通知（用于「服务运行中 / 更新进度」的前台通知）、网络 |

> 说明：本 App 内置的 Node 运行时（`libnode.so`）约 117 MB，是启动器的**底座**、必须随 APK 分发，因此 APK 体积约 38–42 MB（压缩后），无法更小。

## 联机

游戏本体自带联机：一人开服当房主，其他人连进来合作（1–4 人，空位可加 AI 队友）。本启动器负责让手机成为**房主**或**加入方**。

| 方式 | 怎么做 | 适合 |
|---|---|---|
| **同一局域网直连** | 菜单「联机方式」切到「局域网」→ 点「复制联机地址」，把地址发给同一 WiFi 下的好友，好友用浏览器打开即可 | 同一个家、宿舍、网吧 |
| **组网工具（虚拟局域网）** | 例如 Tailscale、ZeroTier、EasyTier：开服的手机和朋友都装同一个工具、加入同一网络，朋友访问手机的虚拟 IP | 固定的几个熟人；不暴露公网 |
| **内网穿透 / 隧道** | 例如樱花 frp、Cloudflare `cloudflared`：手机跑穿透客户端，朋友直接打开网址 | 不想折腾路由器、没有公网 IP |

三种方式的具体操作：

1. 点顶部小箭头 **⬆** 展开菜单；
2. 「联机方式」选择 **局域网** 或 **外部连接**；选「外部连接」时把穿透得到的地址填进输入框 → 点「加入」；
3. 或直接把对方给的**完整邀请链接**粘到大厅的密钥框，一键直达。

> 提到的组网 / 穿透工具只是举例，与本项目没有任何关系，费用与使用规则请以其官方说明为准。

## 操作

| 操作 | 方法 |
|---|---|
| 打开菜单 | 点屏幕**顶部中间的小箭头 ⬆**（可左右拖动避开画面）|
| 返回本地 | 菜单「返回本地」（联机时回到本机服务器）|
| 检查更新 | 菜单「检查更新」→ 显示当前 / 上游版本 + 该版本更新日志 → 「立即更新」原地下载并重启 |
| 复制联机地址 | 菜单「复制联机地址」，得到本机局域网地址（`http://<手机IP>:3000`）|
| 切换联机方式 | 菜单「联机方式」在 **局域网 / 外部连接** 间切换（切换后菜单不收起）|
| 填穿透地址 | 「外部连接」下出现输入框，粘贴地址 → 点「加入」|
| 刷新页面 | 菜单「刷新页面」|
| 关于 | 菜单「关于」|

游戏内玩法（招募、摆阵、作战等）见上游的 [玩法指南](https://github.com/sganggs/Stronghold-Protocol) 与游戏内左下角「玩法说明」。

## 文档

| 文档 | 内容 |
|---|---|
| [README.md](README.md) | 本文件：项目介绍与使用方法 |
| [NOTICE.md](NOTICE.md) | 版权与使用声明 |
| [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md) | 第三方组件清单（含 Node.js 许可证全文）|

## 项目结构

| 路径 | 内容 |
|---|---|
| `android/app/src/main/java/com/stronghold/launcher/` | 启动器 Java 源码（主界面、服务、下载与更新）|
| `android/app/bootstrap/android-main.mjs` | 安卓专用 Node 入口：起服务、首启下载素材编排、多镜像测速 |
| `android/app/src/main/assets/inject/` | 注入脚本（`inject.js` / `inject.css`），改游戏 UI 的唯一手段 |
| `android/app/src/main/cpp/` | JNI 胶水（`node_bridge.cpp`）与 Node 公共头文件 |
| `android/app/src/main/jniLibs/arm64-v8a/libnode.so` | 内嵌的 Node 运行时（**Git LFS**）|
| `android/gradlew` · `android/gradle/wrapper/` | Gradle wrapper（8.9）：clone 后可直接 `./gradlew assembleDebug` |
| `third-party/` | 第三方许可证全文 |

Java 源码一览：

| 文件 | 作用 |
|---|---|
| `MainActivity.java` | 主界面：启动编排、全屏 / 挖孔、WebView、注入、顶部菜单、检查更新 UI |
| `NodeService.java` | 前台服务：起 Node 前完成首装 / 更新 → 起 Node → 健康检查 |
| `NodeRunner.java` | 加载 `libnode.so` 并调用 `node::Start` |
| `ProjectInstaller.java` | 把 APK 内 `android-main.mjs` 铺到应用目录 |
| `GameUpdater.java` | 下载后处理：解压完整包 / 增量包、读写版本戳与 pending 标记 |
| `UpdateRepo.java` | 上游访问层：版本检查、更新日志、三种包地址、多镜像并发测速下载 |
| `UpdateService.java` | 更新下载前台服务（增量包），广播进度 |
| `UserStore.java` | 启动器侧账号系统 |

> ⚠️ 维护约定：本文件描述当前目录结构，每次增删目录 / 文件后请同步更新本文件。

## 从源码构建（开发者）

> 这一节面向**想自己改启动器**的人（改注入脚本、改界面、改下载源等）。**只想玩的话，直接装 Releases 里的 APK 就行，不需要看这里。**

本仓库是**空壳**，构建**不需要**上游 `game/` 目录（游戏本体是运行时下载的）。

### 前置依赖

| 依赖 | 版本 / 说明 |
|---|---|
| **JDK 17** | 设置好 `JAVA_HOME`；Gradle 与源码 `compileOptions` 都要求 17 |
| **Android SDK** | 需 `compileSdk 34`（Android 14）平台 + 配套 `build-tools` |
| **Android NDK** | **30.0.16138531**（须与 `app/build.gradle` 的 `ndkVersion` 一致）|
| **CMake** | **3.22.1**（编译 JNI 胶水层；建议用 SDK Manager 安装）|
| [**Git LFS**](https://git-lfs.com) | `libnode.so`（约 117 MB）走 LFS，没装只会拿到指针文件 |
| **adb**（可选）| 装机调试用 |

> Gradle **不需要你手动装** —— 仓库自带 wrapper，首次执行会自动下载 Gradle 8.9（约 130 MB，会存到 `~/.gradle`）。

### 构建

```bash
git lfs install                                  # 一次性：启用 LFS
git clone https://github.com/acilu233/Stronghold-Protocol-Terminal.git
cd Stronghold-Protocol-Terminal/android

./gradlew assembleDebug        # 调试版
./gradlew assembleRelease      # 发布版
```

Windows / PowerShell 下用 **`gradlew.bat`** 代替 `./gradlew`。产物：

| 构建类型 | 产物路径 |
|---|---|
| `assembleDebug` | `android/app/build/outputs/apk/debug/app-debug.apk` |
| `assembleRelease` | `android/app/build/outputs/apk/release/app-release.apk` |

关于 release 签名：`app/build.gradle` 里**有意**用 debug 密钥签 release 包（换正式密钥会导致手机上已装版本无法覆盖升级，且卸载会清掉存档）。想上应用商店时再换，届时务必先做存档迁移预案。

联网说明：首次构建需要网络（下载 Gradle 发行包与 Android 构建依赖）；依赖齐备后可用 `./gradlew assembleDebug --offline` 离线构建。

### 常见问题

| 现象 | 处理 |
|---|---|
| `jniLibs/arm64-v8a/libnode.so` 只有几百字节 | LFS 没拉到 → `git lfs pull` |
| 提示找不到 SDK / NDK / CMake | 在 `android/local.properties` 写入 `sdk.dir=<你的 SDK 路径>`，并用 SDK Manager 安装上表列出的 NDK 与 CMake 版本 |
| `./gradlew` 报 `bad interpreter` 或 `^M` | 行尾被改成 CRLF 了 → `git config core.autocrlf false` 后重新检出该文件 |
| 构建卡在下载 | 可在 `~/.gradle/init.gradle` 里配国内镜像仓库 |

版本号在 `android/app/build.gradle` 的 `versionCode` / `versionName`。

## 许可证

- **代码**：本仓库（启动器壳）自己编写的代码以 **GPL-3.0-or-later** 发布，全文见 [LICENSE](LICENSE)。
  采用与上游相同的许可证，是因为本启动器与上游 GPL-3.0-or-later 项目互操作（运行时调用其代码）。
- **游戏素材不在许可范围内**：《明日方舟》相关的美术、音乐、音效、文本与数据等版权归原权利人所有，不适用 GPL，使用限制见上方的[声明](#声明)和 [NOTICE.md](NOTICE.md)。
- **第三方组件**：内嵌的 `libnode.so`（Node.js，MIT）以及 Node 公共头文件，许可证全文见 [third-party/Node.js-LICENSE.txt](third-party/Node.js-LICENSE.txt)，清单见 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)。

## 致谢

- [**sganggs/Stronghold-Protocol**](https://github.com/sganggs/Stronghold-Protocol) —— 上游同人复刻，本启动器的全部游戏内容来源。
- [**nodejs-mobile**](https://github.com/nodejs-mobile) / [**Node.js**](https://nodejs.org/) —— 内嵌的 Node 运行时（`libnode.so`）。
- **上海鹰角网络（Hypergryph）/ Yostar** —— 《明日方舟》及「卫戍协议」玩法版权方。
- **WorkBuddy** —— 本项目的全部代码与文档由 AI 编写。

感谢上游作者与维护者，以及鹰角网络带来的这款游戏。

## 贡献

欢迎提 Issue 反馈 bug 或改进建议，也欢迎 PR：

- 本项目由 AI 编写，风格可能与常规项目不同；发现问题直接开 Issue 即可。
- **请不要提交任何游戏素材文件**（游戏本体不进本仓库，也不要用本仓库分发素材）。
- 本项目坚持非商业：请不要提交广告、付费、打赏等任何形式的变现功能。
- 提交的代码将以 GPL-3.0-or-later 发布。

---

## English

An **unofficial, non-commercial Android launcher** for the fan remake [sganggs/Stronghold-Protocol](https://github.com/sganggs/Stronghold-Protocol). It bundles a Node.js runtime (`libnode`) so the game server runs **on the phone itself** — no PC, no root, play in a built-in WebView.

- **Made by AI:** all of this project's code, UI, architecture and docs were written **100 % by AI (WorkBuddy)**; a human only provided requirements and on-device testing. Expect an "AI-generated project" — rough edges are possible; issues welcome.
- **Bundles no game content:** the APK is only the launcher shell. On first launch it downloads the **full bundle** (~556 MB, art & audio included) from the upstream [Releases](https://github.com/sganggs/Stronghold-Protocol/releases) via a multi-mirror race, extracts it and starts the server. In-app updates fetch only the small incremental `-update.zip` and show that release's changelog.
- **Install:** grab the APK from this repo's Releases (Android 11+ / arm64-v8a); first launch needs ~556 MB (Wi-Fi recommended).
- **Play with friends:** menu → switch *LAN / External*, copy the LAN address for a same-Wi-Fi friend, or paste an invite link to join.
- **Credits:** the game belongs to the upstream fan project — **please star it**. Arknights assets are © Hypergryph / Yostar.
- **Disclaimer:** not affiliated with or endorsed by the upstream project, Hypergryph or Yostar. All Arknights names, art, audio, text and data are © their respective owners and are **not** covered by this project's GPL licence. For study and personal non-commercial use only — no monetisation of any kind. Content will be removed on request of the rights holders. Provided "as is", without warranty.
- **License:** code GPL-3.0-or-later ([LICENSE](LICENSE)); game assets excluded. `libnode.so` is Node.js (MIT), see [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).
