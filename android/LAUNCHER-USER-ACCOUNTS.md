# 用户账号体系设计（launcher 侧）

> 目标：把「博士代号」从一次性输入升级为**本地用户账号**，用客户端自持的稳定 UID
> 把**单机 origin** 与**联机 origin** 两套割裂的数据整合到同一个用户下。
>
> 硬约束：**`game/` 目录一个字节都不能改**（上游更新会覆盖），且改完要能扛住覆盖。
> → 所有 UI 改造走 `inject.js` 注入层；所有跨 origin 协调走原生桥。

---

## 1. 背景：为什么两套数据是分开的

`localStorage` 按 **origin**（`scheme://host:port`）隔离，而 App 有两个入口：

| 场景 | 入口 URL | origin |
|---|---|---|
| 单机 | `web.loadUrl(BASE + "/")` | `http://127.0.0.1:3000` |
| 联机 | `Launcher.go(邀请链接)` | `http://<主机IP>:3000` |

实测（MIX 2S，同一 App，同名键，值不同）：

| 键 | 单机 | 联机 |
|---|---|---|
| `sp.name` | `"Master"` | `"你好"` |
| `sp.pref.stats` | 1289B / 4 条 | 650B / 1 条 |
| `sp.pref.recentRooms` | `["WFKV","YBCC","NLBV","BSRV"]` | `["LZBM"]` |

→ **不是 bug，是必然。** 要打通，必须有第三方（原生侧）当中转站。

---

## 2. 已确认的技术事实

### 2.1 服务端没有"长期用户"概念
`game/server/net.js:159`：
```js
do playerId = 'p_' + randomBytes(5).toString('hex'); while (this.byPlayerId.has(playerId));
```
- `playerId` 纯随机，每次新建会话都变
- 会话靠 token 恢复，但 `reconnectWindowMs = 10 * 60_000`（10 分钟）后清理
- → **稳定 UID 必须由客户端自己造**

### 2.2 标题页 DOM 可注入（实测验证）
`.title-login` 的子节点：
```
LABEL.field          博士代号 · <input maxlength=12>
BUTTON.btn--primary  「开始」
DIV.title-conn       状态栏
```
**关键**：往 `.title-login` 插入自有元素后，触发 preact 重渲染，元素**存活**
（实测 `probeAlive: true`）→ 注入层可安全插入 UI。

### 2.3 战绩记录是严格 schema（**重要约束**）
`game/public/js/ui/stats.js`：
- `normalizeRecord()` 第 175-201 行：**未知字段一律丢弃**
- `compactRecord()` 第 293-306 行：`COMPACT_FRAME` **硬编码白名单**
```js
const COMPACT_FRAME = ['id','v','t','end','victory','roundsPassed','lastRound',
  'hiddenReached','hiddenCleared','reason','modeId','difficulty','roomMode','durationMs','selfId'];
```

→ **往里塞一个自定义 `tag` 字段会被游戏自己剥掉**，行不通。

**替代方案**：战绩列表已有一列「模式」（`roomMode`: solo→单人 / coop→同盟）。
主客标签可以通过**注入层改写该 <td> 的渲染结果**（DOM 后处理），
而不是往数据里加字段 —— 需要从 `rec.id` 反查一份"主客映射表"（存在原生侧）。

### 2.4 原生侧已有持久化设施
`MainActivity.java:604`：`private static final String PREFS = "xj_launcher";`
已用 `getSharedPreferences(PREFS, MODE_PRIVATE)` 存 `net` / `host`。
→ 用户目录直接沿用这个 SharedPreferences（或同目录下另开一个 key）。

---

## 3. 用户已拍板的六个决定

| # | 决定 |
|---|---|
| 1 | UID 格式按设计建议：`u_` + 随机 hex，本地生成，永不重复 |
| 2 | **用户上限 10 个** |
| 3 | `sp.pref.settings` 等**所有设置都跟用户走**（优先级：用户 > 全局） |
| 4 | 战绩**合并**；记录后加**简短的主/客标签** |
| 5 | 老数据**只保留 `Master`**（丢弃 `你好` 那套） |
| 6 | 先后顺序按建议：**先做本设计（阶段 1→2→3），本地资源代理放最后** |

---

## 4. 架构

```
              ┌──────────────────────────────────────────┐
              │  原生侧（Java）：用户目录（唯一跨 origin 的层）│
              │  SharedPreferences "xj_launcher"          │
              │    xj.users      = [ {uid,name,createdAt} ]│
              │    xj.user.cur   = "u_xxxx"               │
              │    xj.userdata.<uid>.<origin> = JSON      │  ← 每个用户在每个 origin 的快照
              │    xj.matchside  = { <recId>: "host"|"guest" } │  ← 主客映射
              └───────────────┬──────────────────────────┘
                              │ 原生桥（@JavascriptInterface）
              ┌───────────────┴───────────────┐
              │                               │
      ┌───────▼────────┐            ┌────────▼───────┐
      │ 单机 origin    │            │ 联机 origin    │
      │ 127.0.0.1:3000 │            │ 主机IP:3000    │
      │                │            │                │
      │ inject.js:     │            │ inject.js:     │
      │  进入→铺数据   │            │  进入→铺数据   │
      │  离开→收数据   │            │  离开→收数据   │
      └────────────────┘            └────────────────┘
```

**为什么必须这样分层**：JS 受同源策略限制，**读不到别的 origin 的 localStorage**。
只有原生侧能同时持有两边数据。

---

## 5. 数据模型

### 5.1 用户目录
```json
// SharedPreferences: xj.users
[
  { "uid": "u_8f3a2c1d", "name": "Master",   "createdAt": 1791726000000 },
  { "uid": "u_5b91e0aa", "name": "测试员",   "createdAt": 1791726100000 }
]
// SharedPreferences: xj.user.cur  →  "u_8f3a2c1d"
```

**UID 不变**：改名只动 `name`，`uid` 永不变。上限 **10** 个，超出拒绝创建。

### 5.2 每个用户的数据快照
```json
// SharedPreferences: xj.userdata.<uid>.<originTag>
// originTag: "local"（单机）或 "remote:<host>"（联机，按主机区分）
{
  "sp.name": "Master",
  "sp.pref.settings": {...},
  "sp.pref.loadout": {...},
  "sp.pref.ownership": [...],
  "sp.pref.diy": {...},
  "sp.pref.stats": {...},
  "sp.pref.lang": "zh",
  "sp.pref.recentRooms": [...],
  "sp.pref.lobby.mode": "coop",
  "sp.pref.lobby.difficulty": "ABYSS"
}
// 注意：不含 sp.token / sp.tokens（令牌与 origin 绑定，不能跨用户搬）
```

### 5.3 主客映射
```json
// SharedPreferences: xj.matchside
{ "r1.a3f9c2e1": "host", "r1.b71d0452": "guest" }
// rec.id → 该局本机是主机还是客机
```

---

## 6. 原生桥 API（新增）

| 方法 | 签名 | 用途 |
|---|---|---|
| `usersJson()` | `String` | 返回用户列表 JSON（含当前 uid） |
| `createUser(name)` | `String` | 建用户，返回新 uid（超 10 个返回 `""`） |
| `renameUser(uid, name)` | `boolean` | 改名（uid 不变） |
| `deleteUser(uid)` | `boolean` | 删用户（含其数据） |
| `currentUser()` | `String` | 返回当前 uid |
| `switchUser(uid)` | `boolean` | 切换当前用户 |
| `pullUserData(originTag)` | `String` | 取该用户在该 origin 的快照 JSON |
| `pushUserData(originTag, json)` | `boolean` | 存回快照 |
| `markMatchSide(recId, side)` | `void` | 记录该局主客 |
| `matchSide(recId)` | `String` | 查询该局主客 |

（全部沿用现有 `Launcher` 对象，`@JavascriptInterface` 注解。）

---

## 7. 界面改造（inject.js，不动 game/）

### 7.1 标题页
```
改造前：
  [ 博士代号 输入框 ]
  [      开始       ]

改造后：
  [ 博士代号 输入框 ]   ← 与当前用户绑定，改名即改用户 name
  [   开始   ] [ 用户 ]  ← 开始按钮一分为二
```

**用户列表弹窗**：
```
┌──────────────────────────────┐
│  选择用户              [×]   │
│ ──────────────────────────── │
│  ● Master          (当前)    │
│  ○ 测试员                     │
│ ──────────────────────────── │
│  [ + 创建新用户 ]  （满 10 个则置灰）
│  [   重命名当前   ]
│  [    删除用户    ]
└──────────────────────────────┘
```

**注入方式**：
1. MutationObserver 监控标题页出现
2. 在 `.title-login` 内插入按钮（与现有联网面板同套路）
3. 输入框挂 `input` 监听 → 改名同步到原生侧
4. **不碰「开始」按钮**（保留游戏自身逻辑）

### 7.2 战绩列表主客标签
- 注入层后处理 `st-table--history` 的每一行
- 从 `<td>` 里拿记录 id（或按行序 + 时间反查）→ 查 `xj.matchside`
- 在「模式」列后追加 `<em class="xj-side">主</em>` / `客`

> ⚠️ 这一项受 §2.3 约束影响，实现时需先确认能否稳定拿到 `rec.id`。
> 若拿不到，退化为「按时间戳匹配」。

---

## 8. 同步时机

| 时机 | 动作 |
|---|---|
| 进入任一 origin，页面加载完 | `pullUserData` → 写入当前 origin 的 localStorage |
| 切换用户 | 先把当前 origin 数据 `pushUserData` 回收，再铺新用户的 |
| 离开 origin（返回本地 / 断连） | `pushUserData` 回收 |
| 定期（如 30s） | 增量 `pushUserData`，防意外退出丢数据 |

**冲突规则**：以「最近写入」为准（记录 `updatedAt`）。

---

## 9. 老数据迁移（决定 5：只保留 Master）

首次启动时（原生侧做一次性的 migration flag）：

1. 读单机 origin 现有 `sp.name` = `"Master"` → 建用户 `Master`
2. 把单机 origin 的 `sp.pref.*` 全部归入该用户
3. 联机 origin 的 `"你好"` **丢弃**（按决定 5），但其 `sp.pref.stats` 里
   那条 650B 记录**可选择性合并**进 Master（待确认）

> ⚠️ 迁移只能读**当前 origin** —— 所以需要用户先到两个 origin 各走一次，
> 或由注入层在每次进入时增量归档。这是实现时的关键细节。

---

## 10. 风险

| 风险 | 缓解 |
|---|---|
| `game/` 更新覆盖 | 全部改在 `inject.js` + Java，`game/` 零改动；已确认 `public/assets` 在受保护名单 |
| 战绩 schema 剥字段 | 不往记录里加字段，改用 DOM 后处理 + 外部映射表 |
| 跨 origin 数据丢失 | 每次切换/离开都回收；定期增量推送 |
| 令牌被误同步 | `sp.token`/`sp.tokens` **明确排除**在同步清单外 |
| 用户超上限 | 硬限 10，UI 置灰 |

---

## 11. 施工顺序

```
阶段 1  原生侧用户目录
  ├─ SharedPreferences 数据模型
  ├─ 10 个桥方法
  └─ 单机可验证：建用户 / 切换 / 改名

阶段 2  标题页 UI（inject.js）
  ├─ 开始按钮一分为二
  ├─ 用户列表弹窗
  └─ 名字输入框绑定当前用户

阶段 3  数据同步
  ├─ 进入/离开/切换的铺取
  ├─ 老数据迁移（保留 Master）
  └─ 战绩主客标签

阶段 4  本地资源代理（方案 2，之前的议题）
```
