# 版权与使用声明（NOTICE）

**卫戍协议：终端**（本仓库）是为上游同人复刻《卫戍协议：盟约 · Stronghold Protocol》
（GitHub：`sganggs/Stronghold-Protocol`）编写的**非官方安卓启动器壳**。

本启动器与上游项目作者、与上海鹰角网络科技有限公司（Hypergryph）、Yostar 及其关联方
**均无任何关联，也未获其授权或认可**。

## 1. 代码许可证：GPL-3.0-or-later

本仓库自己编写的源代码与文档（`android/` 启动器壳）以
**GNU 通用公共许可证第 3 版或（由你选择）任何更新版本**（GPL-3.0-or-later）发布，
全文见 [LICENSE](LICENSE)。你可以按该许可证的条件使用、修改和再分发这些代码。

采用与上游相同的许可证，是因为本启动器与上游 GPL-3.0-or-later 项目互操作（运行时调用其代码）。

## 2. 不包含游戏代码与素材

本仓库**不包含**上游项目的源代码，也**不包含**《明日方舟》/「卫戍协议」相关的任何名称、角色、
美术、Spine 模型、界面图、音乐音效、文本与游戏数据。

- 游戏本体（代码与素材）由启动器在**运行时从上游 Releases 下载**，不由本仓库分发。
- 《明日方舟》及「卫戍协议」相关素材**版权归上海鹰角网络科技有限公司及其授权方所有**
  （© Hypergryph / Yostar 及其许可人），**不适用 GPL**，**仅限学习与个人非商业使用**。
- 上游对其代码与素材的完整声明，见上游仓库的 `NOTICE.md`。

## 3. 仅限非商业用途

本项目仅供**学习、研究和个人非商业娱乐**。不得用于任何形式的盈利，包括但不限于：
出售或付费分发、收费开服或会员、植入广告、打赏 / 赞助 / 众筹、打包进任何收费产品或服务。
（GPL 本身允许商业使用**代码**；上述限制针对的是不属于本项目的游戏素材与数据。）

## 4. 第三方组件

- `android/app/src/main/jniLibs/arm64-v8a/libnode.so`（Node.js 运行时二进制，由 nodejs-mobile 系构建）
  遵循 MIT 许可，许可证全文见 [third-party/Node.js-LICENSE.txt](third-party/Node.js-LICENSE.txt)。

完整清单见 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)。

## 5. 权利人通知与删除

如果你是相关权利人，认为本项目的任何内容不妥，请在本仓库提交 Issue（或通过 GitHub 联系仓库所有者），
我们会尽快删除相关内容乃至下架整个仓库。

## 6. 免责声明

本项目按「原样」提供，不附带任何明示或暗示的担保。使用本项目的风险由使用者自行承担。

---

**English summary.** Unofficial, non-commercial Android launcher for the fan remake
`Stronghold-Protocol`. Not affiliated with or endorsed by the upstream project, Hypergryph or Yostar.
This repo's own code is GPL-3.0-or-later. It bundles **no** game code or assets — the game is downloaded
from the upstream Releases at runtime. All Arknights names, art, audio and data are © their respective
owners, are not covered by the GPL, and may be used for study and personal non-commercial purposes only.
`libnode.so` is Node.js (MIT). No warranty of any kind.
