/* ==========================================================================================
 * inject.js — 安卓启动器注入脚本
 *
 * 由 MainActivity.shouldInterceptRequest 在 index.html 的 </body> 前插入，随页面一起执行。
 * 前提：game/ 目录一个字节都不能改（上游更新包覆盖 game/ 后本脚本仍要生效）。
 *   → 所以只能「从外部观察 DOM + 改写可控的公共 API」，不能依赖游戏内部的模块/导出。
 *
 * 本脚本做四件事：
 *   1. 联网模式切换  ——  在大厅「加入同盟」栏下方注入「局域网 / 外部连接」开关（别处一律没有）
 *   2. 外链直达      ——  在大厅密钥框里粘贴完整网址时，用 Launcher.go() 让 WebView 跳过去
 *   3. 复制链接改写  ——  房内「复制链接」时，把域名换成局域网真实 IP 或内网穿透网址
 *   4. 用户账号      ——  标题页「开始」右边加「用户」按钮 + 用户列表弹窗（建/切/改名/删），
 *                       解决单机(127.0.0.1) 与联机(<主机IP>) 两套 origin 数据割裂的问题；
 *                       用户目录由原生侧 UserStore 持有（唯一能跨 origin 的层）。
 *
 * 注：曾尝试的「界面缩放」已按用户要求整体移除（zoom 只能放大控件，棋盘由 canvas 按
 *     clientWidth 绘制，物理尺寸恒定不变，无法达到预期效果）。
 *
 * 与原生桥 Launcher 的约定（MainActivity 已提供）：
 *   Launcher.go(url)   跳转 WebView（仅 http/https）
 *   Launcher.home()    回到本机 127.0.0.1:3000
 *   Launcher.setNet(v) 保存联网模式（'lan' | 'wan'）以便下次启动恢复
 *   Launcher.getNet()  → 已保存的联网模式
 *   Launcher.setHost(v)/getHost() → 内网穿透网址
 *   Launcher.toast(msg) 原生 Toast（可选）
 *   Launcher.checkUpdate() 检查更新（弹原生对话框显示当前版本 + 上游最新版本，GitHub Releases）
 *   —— 用户账号（UserStore）：usersJson / createUser / renameUser / deleteUser /
 *      currentUser / switchUser / pullUserData / pushUserData / markMatchSide /
 *      matchSide / allSidesJson / isMigrated / setMigrated
 * ========================================================================================== */

/* ==========================================================================================
 * ⚠️ 本文件被 Java 从 XJ_EARLY_SPLIT 标记处切成两半，分别塞进 <head> 和 </body> 前。
 *    因此**两半必须是各自独立的、语法完整的脚本**：
 *      · 上半（早期段）自带 (function(){ ... })() 包装 —— 见本节开头；
 *      · 下半（UI 段）自带另一套 (function(){ ... })() 包装 —— 见标记之后。
 *    绝不能共用一个外层 IIFE 跨过标记 —— 那样切开后每一半都缺半边括号，
 *    浏览器会直接报语法错误，**整段代码一行都不执行**
 *    （踩过的坑：早期段失效 → 进联机要重新输名字 / 用户名不铺 / 数据不互通）。
 * ========================================================================================== */

/* ------------------------------------------------------------------------------------------
 * 【早期段】独立 IIFE —— 会被 Java 插到 <head> 之后，跑在游戏模块读 localStorage 之前。
 * ------------------------------------------------------------------------------------------ */
;(function () {
  'use strict';

  // ======================================================================================
  // 0. 早期段 —— 必须跑在游戏读 localStorage 之前（本段会被 Java 插到 <head> 里）
  //
  // 游戏的模块脚本会把自己的 sp.* 读进内存；如果等 </body> 前的注入段再铺数据，
  // 游戏早读完了，切档看起来"没生效"。所以铺档这件事必须抢在 <head> 阶段做。
  // 本段只碰 localStorage + 原生桥，不碰 DOM（此时还没有 body）。
  // ======================================================================================
  var EARLY_TAG = '__XJ_EARLY__';

  (function earlyRestore() {
    try {
      var B = window.Launcher;
      if (!B || typeof B.pullUserData !== 'function') return;   // 桥没准备好 → 交给后段

      var h = String(location.hostname || '');
      var tag = (h === '127.0.0.1' || h === 'localhost' || h === '::1') ? 'local' : ('remote:' + h);

      // 只在「游戏还没读」的时候铺。用 sessionStorage 标记，避免同一会话重复覆盖
      // （用户在游戏里改了设置，页面内跳转时不能被自己的旧档盖回去）。
      var guardKey = '__xj_restored__' + tag;
      if (sessionStorage.getItem(guardKey)) return;

      function isUserKey(k) {
        return k === 'sp.name' || k.indexOf('sp.pref.') === 0;
      }

      // 当前 localStorage 里的用户数据
      function currentSnapshot() {
        var o = {};
        for (var i = 0; i < localStorage.length; i++) {
          var k = localStorage.key(i);
          if (isUserKey(k)) o[k] = localStorage.getItem(k);
        }
        return o;
      }

      // 用快照覆盖（先清同类键，避免残留）
      function applySnapshot(snap) {
        var victims = [];
        for (var i = 0; i < localStorage.length; i++) {
          var k0 = localStorage.key(i);
          if (isUserKey(k0)) victims.push(k0);
        }
        for (var j = 0; j < victims.length; j++) localStorage.removeItem(victims[j]);
        for (var key in snap) {
          if (Object.prototype.hasOwnProperty.call(snap, key) && isUserKey(key) && snap[key] != null) {
            localStorage.setItem(key, String(snap[key]));
          }
        }
      }

      var raw = B.pullUserData(tag);
      var snap = null;
      if (typeof raw === 'string' && raw) {
        try { snap = JSON.parse(raw); } catch (e) { snap = null; }
      }
      var hasArchive = !!(snap && typeof snap === 'object' && Object.keys(snap).length > 0);

      if (hasArchive) {
        // 有档 → 铺开（这就是"切档"的落地点）
        // 远程房首个来访：这份档是从单机(local)继承来的（原生侧 pullMerged 干的），
        // 此时需要**落盘**到 remote 档，否则联机期间的改动没有落脚点。
        if (tag !== 'local' && typeof B.hasOwnData === 'function' && typeof B.seedRemoteData === 'function') {
          try {
            if (!B.hasOwnData(currentUidEarly(), tag)) B.seedRemoteData(tag);
          } catch (e) { /* ignore */ }
        }
        applySnapshot(snap);
      } else {
        // 该用户在这一端还没有档。
        // 若 localStorage 里已经有"无主"的用户数据 —— 那是这个 origin 的既有存档
        // （首次启用账号系统时的老数据），**认领**它，而不是抹掉。
        // 若 localStorage 本来就是空的（刚创建的用户 / 刚被清过），什么都不做。
        var existing = currentSnapshot();
        if (Object.keys(existing).length > 0 && typeof B.pushUserData === 'function') {
          try { B.pushUserData(tag, JSON.stringify(existing)); } catch (e) { /* ignore */ }
        }
      }

      // ---- 兜底：保证「代号」一定有值 ----
      // 用户要求：进联机不该再让他重新输入名字，直接用本地用户名。
      // 快照里可能没有 sp.name（老档 / 手改过的档），这时就用用户目录里的名字补上；
      // 实在连用户都没有（首次全新安装）才留空，交给游戏自己问。
      try {
        var curName = currentUserNameEarly();
        var nowName = '';
        try { nowName = String(localStorage.getItem('sp.name') || ''); } catch (e) { }
        if (!nowName && curName) {
          try { localStorage.setItem('sp.name', curName); } catch (e) { }
        }
      } catch (e) { /* ignore */ }

      sessionStorage.setItem(guardKey, '1');
    } catch (e) { /* 早期段绝不能抛 */ }

    /** 早期段里取当前 uid（桥可能还没挂上，安全返回 ""）。 */
    function currentUidEarly() {
      try {
        var B2 = window.Launcher;
        if (!B2 || typeof B2.currentUser !== 'function') return '';
        var u = B2.currentUser();
        return (typeof u === 'string') ? u : '';
      } catch (e) { return ''; }
    }

    /** 早期段里取当前用户的名字（从 usersJson 里按当前 uid 找）。 */
    function currentUserNameEarly() {
      try {
        var B3 = window.Launcher;
        if (!B3 || typeof B3.usersJson !== 'function') return '';
        var j = B3.usersJson();
        if (typeof j !== 'string' || !j) return '';
        var o = JSON.parse(j);
        if (!o || !o.users || !o.current) return '';
        for (var i = 0; i < o.users.length; i++) {
          if (o.users[i] && o.users[i].uid === o.current) {
            return String(o.users[i].name || '');
          }
        }
        return '';
      } catch (e) { return ''; }
    }
  })();

  window[EARLY_TAG] = true;
})();
// —— 早期段到此结束（上面这个 })(); 必须存在，否则切分后早期段语法不完整）——

// ============ 以下为 UI 段（被 Java 插在 </body> 前，此时 body 已存在） ============
/*__XJ_EARLY_SPLIT__*/

/* ------------------------------------------------------------------------------------------
 * 【UI 段】独立 IIFE —— 会被 Java 插到 </body> 之前，此时 DOM 已经就绪。
 * 这里重新起一个函数作用域，所以下面的 `return` / 变量都不会漏到全局，
 * 也不会和早期段共用作用域（两段本来就是两次独立执行）。
 * ------------------------------------------------------------------------------------------ */
;(function () {
  'use strict';

  if (window.__xjInjected) return;
  window.__xjInjected = true;

  var BRIDGE = window.Launcher || null;

  /** 调用原生桥，任何桥不存在 / 抛异常都不能拖垮整个页面。 */
  function call(name, arg) {
    try {
      if (!BRIDGE || typeof BRIDGE[name] !== 'function') return null;
      return arg === undefined ? BRIDGE[name]() : BRIDGE[name](arg);
    } catch (e) {
      return null;
    }
  }

  /** 双参数桥调用（pushUserData 等）。 */
  function call2(name, a, b) {
    try {
      if (!BRIDGE || typeof BRIDGE[name] !== 'function') return null;
      return BRIDGE[name](a, b);
    } catch (e) {
      return null;
    }
  }

  /** localStorage 兜底（原生桥不可用时仍能记住选择）。 */
  var LS = {
    get: function (k, d) {
      try {
        var v = window.localStorage.getItem('xj.' + k);
        return v == null ? d : v;
      } catch (e) { return d; }
    },
    set: function (k, v) {
      try { window.localStorage.setItem('xj.' + k, String(v)); } catch (e) { /* ignore */ }
    }
  };

  // ======================================================================================
  // 2. 联网模式（局域网 / 外部连接）
  // ======================================================================================
  var netMode = null;   // 'lan' | 'wan'
  var frpHost = '';     // 内网穿透网址，樱花给的是裸地址，如 frp-xxx.com:12345（协议由 normalizeHost 补 http）
  var lanHost = '';     // 本机局域网地址，如 192.168.1.23:3000

  try {
    var m = call('getNet');
    if (m === 'lan' || m === 'wan') netMode = m;
  } catch (e) { /* ignore */ }
  if (!netMode) {
    var lm = LS.get('netmode', 'lan');
    netMode = lm === 'wan' ? 'wan' : 'lan';
  }

  try {
    var h = call('getHost');
    if (typeof h === 'string') frpHost = h;
  } catch (e) { /* ignore */ }
  if (!frpHost) frpHost = LS.get('frphost', '') || '';

  // 游戏服务器自己的端口。用它区分「明文直连」和「穿透隧道」：
  // 3000 → http（本机/局域网直连）；其它端口 → https（樱花隧道，禁明文）。
  var GAME_PORT = 3000;

  /**
   * 把用户填的地址变成可直接打开的 URL。**只在「进入」那一刻调用，不回写输入框。**
   *
   * 协议补全规则（唯一依据：**端口**）：
   *   · 端口 = 3000（游戏服务器自己的端口）→ **http**
   *       —— 本机 127.0.0.1:3000、局域网 192.168.x.x:3000，都是明文直连，没有 TLS。
   *   · 其它端口（樱花分配的隧道端口，如 64755）→ **https**
   *       —— 樱花国内节点**禁止明文 HTTP 访问**（会回 501），必须走 HTTPS；
   *          开了「自动 HTTPS」后该端口上才真的有 TLS，此时 https 才连得通。
   *
   * 用户显式写了协议 → 一律尊重，不做任何改动。
   * ⚠️ 用户明确要求：**不要回写输入框**。这里的结果只用于跳转。
   */
  function normalizeHost(raw) {
    var s = String(raw || '').trim();
    if (!s) return '';
    // 用户没写协议 → 按端口猜：3000 用 http，其它用 https
    if (!/^[a-z][a-z0-9+.-]*:\/\//i.test(s)) {
      var port = '';
      var m = /:(\d+)(?:\/|$)/.exec(s.replace(/^\/+/, ''));
      if (m) port = m[1];
      s = (port === String(GAME_PORT) ? 'http://' : 'https://') + s;
    }
    try {
      var u = new URL(s);
      if (!u.hostname) return '';
      // 去掉末尾斜杠，链接拼接时统一由我们补 '/'
      var out = u.origin + u.pathname.replace(/\/+$/, '');
      return out;
    } catch (e) {
      return '';
    }
  }

  /**
   * 邀请链接要用的 host 前缀。
   *   局域网模式 → 本机局域网地址（从启动器元服务 /lan 拿到真实 IP，好友同 WiFi 才连得上）
   *   外部连接   → 用户填的内网穿透网址
   * 拿不到就返回 ''，调用方保留游戏原本的 location.origin。
   */
  function inviteBase() {
    if (netMode === 'wan') {
      var n = normalizeHost(frpHost);
      return n || '';
    }
    return lanHost ? 'http://' + lanHost : '';
  }

  /** 拉一次本机局域网地址（启动器元服务在 3002，仅本机可问）。 */
  function loadLanHost() {
    try {
      var xhr = new XMLHttpRequest();
      xhr.open('GET', 'http://127.0.0.1:3002/lan', true);
      xhr.timeout = 3000;
      xhr.onload = function () {
        try {
          var o = JSON.parse(xhr.responseText);
          if (o && o.ip) lanHost = String(o.ip) + ':' + (o.port || 3000);
        } catch (e) { /* ignore */ }
        syncPanel();
      };
      xhr.onerror = xhr.ontimeout = function () { /* 拿不到就留空，复制链接保持原样 */ };
      xhr.send();
    } catch (e) { /* ignore */ }
  }

  // ======================================================================================
  // 3. 启动器面板（只挂在大厅「加入同盟」下方）
  // ======================================================================================

  /** 悬停式提示条（游戏自己的 toast 是模块私有的，这里自备一个轻量的）。 */
  var toastEl = null;
  var toastTimer = 0;
  function toast(msg) {
    if (!toastEl) {
      toastEl = document.createElement('div');
      toastEl.className = 'xj-toast';
      document.body.appendChild(toastEl);
    }
    toastEl.textContent = msg;
    toastEl.classList.add('is-on');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { toastEl.classList.remove('is-on'); }, 1800);
  }

  var panel = null;

  /** 造一行「左标题 + 右控件」。 */
  function makeRow(labelText, microText) {
    var row = document.createElement('div');
    row.className = 'xj-row';
    var lab = document.createElement('span');
    lab.className = 'xj-row__label';
    lab.appendChild(document.createTextNode(labelText));
    if (microText) {
      var m = document.createElement('span');
      m.className = 'xj-row__micro';
      m.textContent = microText;
      lab.appendChild(m);
    }
    row.appendChild(lab);
    var ctl = document.createElement('span');
    ctl.className = 'xj-row__ctl';
    row.appendChild(ctl);
    return { row: row, ctl: ctl };
  }

  /** 造一个两段/多段切换开关。 */
  function makeSeg(ariaLabel, items, dataAttr, onPick) {
    var seg = document.createElement('div');
    seg.className = 'xj-seg';
    seg.setAttribute('role', 'radiogroup');
    seg.setAttribute('aria-label', ariaLabel);
    items.forEach(function (it) {
      var b = document.createElement('button');
      b.type = 'button';
      b.className = 'xj-seg__opt';
      b.setAttribute('role', 'radio');
      b.setAttribute(dataAttr, it.value);
      b.textContent = it.label;
      b.addEventListener('click', function () { onPick(it); });
      seg.appendChild(b);
    });
    return seg;
  }

  function buildPanel() {
    var el = document.createElement('div');
    el.className = 'xj-netmode brackets';
    el.setAttribute('data-mode', netMode);

    // ---- 行 1：联机方式 [局域网 | 外部连接] ----
    var r1 = makeRow('联机方式', 'CONNECTION');
    r1.ctl.appendChild(makeSeg(
      '联机方式',
      [{ value: 'lan', label: '局域网' }, { value: 'wan', label: '外部连接' }],
      'data-net',
      function (it) { pickMode(it.value); }
    ));
    el.appendChild(r1.row);

    // ---- 外部连接才出现的网址行 ----
    var host = document.createElement('div');
    host.className = 'xj-host';

    var box = document.createElement('div');
    box.className = 'xj-host__box';
    var input = document.createElement('input');
    input.className = 'xj-host__input';
    input.type = 'text';
    input.setAttribute('inputmode', 'url');
    input.setAttribute('autocapitalize', 'off');
    input.setAttribute('autocorrect', 'off');
    input.setAttribute('spellcheck', 'false');
    input.placeholder = '粘贴内网穿透网址，如 xxxx.sakurafrp.com:12345';
    input.value = frpHost;

    // 「进入」按钮：网址为空时置灰，不为空时可以直接点进去。
    var enter = document.createElement('button');
    enter.type = 'button';
    enter.className = 'xj-host__save xj-host__enter';
    enter.textContent = '进入';

    /** 按当前输入内容决定「进入」是否可用（幂等，防死循环）。 */
    var syncEnter = function () {
      var ok = !!String(input.value || '').trim();
      if (enter.__xjDisabled !== !ok) {
        enter.disabled = !ok;
        enter.__xjDisabled = !ok;
      }
    };
    var doEnter = function () {
      // 原文照存；能不能连得通交给 openRemote 内部判断（那里才补协议、且不回写输入框）。
      saveHost(input.value);
      openRemote(input.value);
    };

    enter.addEventListener('click', doEnter);
    input.addEventListener('input', syncEnter);
    input.addEventListener('change', function () { saveHost(input.value); syncEnter(); });
    input.addEventListener('blur', function () { saveHost(input.value); syncEnter(); });
    input.addEventListener('keydown', function (ev) {
      if (ev.key === 'Enter') { ev.preventDefault(); doEnter(); }
    });

    box.appendChild(input);
    box.appendChild(enter);
    syncEnter();                 // 初始态：按已保存的网址决定灰不灰

    host.appendChild(box);
    el.appendChild(host);

    // ---- 行 3：检查更新（GitHub Releases，弹原生对话框显示当前/上游版本）----
    var rUp = makeRow('检查更新', 'UPDATE');
    var upBtn = document.createElement('button');
    upBtn.type = 'button';
    upBtn.className = 'xj-seg__opt';
    upBtn.textContent = '立即检查';
    upBtn.addEventListener('click', function () {
      if (call('checkUpdate') !== null) toast('正在检查更新…');
      else toast('检查更新不可用');
    });
    rUp.ctl.appendChild(upBtn);
    el.appendChild(rUp.row);

    // ---- 提示（单行，换模式时刷新内容） ----
    var hint = document.createElement('p');
    hint.className = 'xj-hint';
    el.appendChild(hint);

    panel = el;
    syncPanel();
    return el;
  }

  /**
   * 保存用户填的网址。
   *
   * ⚠️ 关键原则（用户明确要求）：**输入框里是什么，就存什么，原样保存。**
   *   以前这里会先 normalizeHost（补协议）再存，再被 syncPanel 回写到输入框，
   *   结果是「用户填进去的地址总被自动加 http://」——用户根本没法按自己想的填。
   *   规范化只在「点击进入」那一刻做（openRemote 内部），且**不回写输入框**。
   */
  function saveHost(raw) {
    var s = String(raw || '').trim();
    frpHost = s;
    call('setHost', s);
    LS.set('frphost', s);
    // 注意：这里**不调 syncPanel**，避免它把输入框内容覆盖成加工后的值。
  }

  /**
   * 直接进入一个远程地址（「进入」按钮 / 大厅粘贴邀请链接都走这里）。
   * 交给原生侧 Launcher.go() 跳 WebView —— 只有原生能跨 origin 切。
   *
   * 协议全权交给 normalizeHost 处理（裸地址一律补 http://，只有显式写 https:// 才用 https）。
   * 这里不再自己补协议 —— 以前在两处各补一次，规则分叉过，踩过 SSL / 501 的坑。
   */
  function openRemote(hostOrUrl) {
    var n = normalizeHost(hostOrUrl);
    if (!n) { toast('网址看起来不对'); return false; }
    if (call('go', n) !== null) {
      toast('正在进入远程同盟…');
      return true;
    }
    toast('进入失败');
    return false;
  }

  function pickMode(mode) {
    if (mode !== 'lan' && mode !== 'wan') return;
    if (mode === 'wan' && !normalizeHost(frpHost)) {
      // 切到外部连接但还没填网址：先让用户填，模式仍然切过去
      netMode = 'wan';
    } else {
      netMode = mode;
    }
    call('setNet', netMode);
    LS.set('netmode', netMode);
    syncPanel();
  }

  /** 把当前状态刷到面板上（模式高亮、输入框、提示文字）。 */
  function syncPanel() {
    if (!panel) return;
    panel.setAttribute('data-mode', netMode);
    var opts = panel.querySelectorAll('.xj-seg__opt[data-net]');
    for (var i = 0; i < opts.length; i++) {
      var on = opts[i].getAttribute('data-net') === netMode;
      opts[i].classList.toggle('is-on', on);
      opts[i].setAttribute('aria-checked', on ? 'true' : 'false');
    }

    // 输入框回写策略（用户要求：别动我填的东西）：
    //   · 正在聚焦编辑 → 绝对不碰；
    //   · 框里已经有内容 → 也不碰（用户填的就是他要的，即使是半截）；
    //   · 只有框是空的（首次挂载 / 被清空）才把已保存的值灌进去。
    var input = panel.querySelector('.xj-host__input');
    if (input && document.activeElement !== input && !input.value) input.value = frpHost;

    var hint = panel.querySelector('.xj-hint');
    if (!hint) return;
    // ⚠️ 绝不用 innerHTML 拼 frpHost —— 那是用户自己填的字符串，
    //     `<img src=x onerror=...>` 会在这里变成可执行脚本（自欺自贻，也是真洞）。
    //     改成「固定文案 + 一个只装主机名的 <b>」，用 textContent 赋值。
    hint.textContent = '';
    if (netMode === 'lan') {
      if (lanHost) {
        hint.appendChild(document.createTextNode('好友用 '));
        var b1 = document.createElement('b');
        b1.textContent = lanHost;
        hint.appendChild(b1);
        hint.appendChild(document.createTextNode(' 加入（同 WiFi）'));
      } else {
        hint.textContent = '正在获取本机局域网地址…';
      }
    } else {
      if (frpHost) {
        hint.appendChild(document.createTextNode('好友通过 '));
        var b2 = document.createElement('b');
        b2.textContent = frpHost;
        hint.appendChild(b2);
        hint.appendChild(document.createTextNode(' 加入'));
      } else {
        hint.textContent = '先启动穿透隧道，把';
        var b3 = document.createElement('b');
        b3.textContent = '网址';
        hint.appendChild(b3);
        hint.appendChild(document.createTextNode('填到上面'));
      }
    }
  }

  /** 找到大厅「加入同盟」栏，把面板插到它下面（只在那一处）。 */
  function mountPanel() {
    // 联机方式开关 + 穿透网址输入框已迁移到原生顶部菜单（MainActivity 的 menuWanRow）；
    // 大厅「加入同盟」下方不再挂载启动器面板，原位置保持不出现。
    return;
    var lobby = document.querySelector('.screen.lobby-screen');
    if (!lobby) return;                        // 不在大厅 → 面板不该存在
    var joinPanel = lobby.querySelector('.join-panel');
    if (!joinPanel) return;
    var anchor = joinPanel.parentNode;         // .lobby-left
    if (!anchor) return;
    if (panel && panel.parentNode === anchor && panel.previousSibling === joinPanel) return; // 已在位

    if (!panel) panel = buildPanel();
    if (joinPanel.nextSibling) anchor.insertBefore(panel, joinPanel.nextSibling);
    else anchor.appendChild(panel);
    syncPanel();
  }

  /** 离开大厅 / 进了房间 → 面板必须消失（用户明确要求：别处一律不许出现）。 */
  function unmountPanel() {
    if (panel && panel.parentNode) panel.parentNode.removeChild(panel);
  }

  // ======================================================================================
  // 4. 外链直达 + 复制链接改写（靠事件捕获，不需要模块引用）
  // ======================================================================================

  /**
   * 大厅密钥框里粘贴了完整网址 → 直接让 WebView 跳过去加入远程房。
   * 用捕获阶段监听，抢在游戏自己的 input 处理之前把网址拦下来。
   */
  document.addEventListener('input', function (ev) {
    var t = ev.target;
    if (!t || !t.closest) return;
    if (!t.closest('.join-panel')) return;                  // 只认大厅加入栏
    var v = String(t.value || '').trim();
    if (!/^https?:\/\/[^\s]+\.[^\s]+/i.test(v)) return;      // 必须是完整网址
    // 是邀请链接且带 room= 才跳（避免误判普通文本）
    if (!/[?&]room=/i.test(v)) return;
    if (call('go', v) !== null) {
      toast('正在进入远程同盟…');
      t.value = '';
      t.dispatchEvent(new Event('input', { bubbles: true }));
    }
  }, true);

  /**
   * 房内「复制链接」：把 location.origin 换成我们算出来的地址。
   * 手法：临时替换 document.createElement('textarea') 的 value 观察结果太脏，
   * 改用拦截 navigator.clipboard.writeText —— 游戏用 secure context 时走这条。
   * 非 secure context 时游戏走 execCommand，那就靠 execCommand 包装。
   */
  function rewriteInvite(text) {
    var base = inviteBase();
    if (!base || typeof text !== 'string') return text;
    // 文本形如: "http://127.0.0.1:3000/?room=ABCD 「XX邀请你加入…」"
    return text.replace(/https?:\/\/[^\s/]+\/(\?room=|index\.html\?room=)?/i, function (m, q) {
      return base + '/' + (q || '');
    });
  }

  // 4a. navigator.clipboard.writeText 包装
  try {
    if (window.navigator && navigator.clipboard && typeof navigator.clipboard.writeText === 'function') {
      var origWrite = navigator.clipboard.writeText.bind(navigator.clipboard);
      navigator.clipboard.writeText = function (text) {
        return origWrite(rewriteInvite(text));
      };
    }
  } catch (e) { /* 只读属性等，忽略 */ }

  // 4b. document.execCommand('copy') 包装（非 secure context 兜底）
  try {
    var origExec = document.execCommand.bind(document);
    document.execCommand = function (cmd) {
      if (String(cmd).toLowerCase() === 'copy') {
        var ae = document.activeElement;
        if (ae && (ae.tagName === 'TEXTAREA' || ae.tagName === 'INPUT')) {
          var rw = rewriteInvite(ae.value);
          if (rw !== ae.value) {
            // ⚠️ 原来这里改了 value 从不恢复 —— 用户在「外部连接」框里点复制，
            //    框里的字就被悄悄换成改写后的值，正是用户明令
            //    「别动我填的东西」要避免的事。
            //    现在：改 → 复制 → **立刻恢复**，用户看到的永远是原文。
            var old = ae.value;
            var selStart = 0, selEnd = 0;
            try { selStart = ae.selectionStart; selEnd = ae.selectionEnd; } catch (e3) { }
            ae.value = rw;
            if (typeof ae.setSelectionRange === 'function') {
              try { ae.setSelectionRange(0, rw.length); } catch (e2) { }
            }
            var copied = origExec.apply(document, Array.prototype.slice.call(arguments));
            ae.value = old;
            if (typeof ae.setSelectionRange === 'function') {
              try { ae.setSelectionRange(selStart, selEnd); } catch (e2) { }
            }
            return copied;
          }
        }
      }
      return origExec.apply(document, Array.prototype.slice.call(arguments));
    };
  } catch (e) { /* ignore */ }

  // ======================================================================================
  // 4.5 用户账号（标题页「开始」右边多一个「用户」按钮 + 用户列表弹窗）
  //
  //  为什么需要这套：localStorage 按 origin 隔离 —— 单机(127.0.0.1) 和
  //  联机(<主机IP>) 是两套互不相干的数据。原生侧持有「用户目录」，
  //  这里只负责在标题页把用户暴露出来（建/切/改名/删），并在两端铺取数据。
  //
  //  手法：游戏标题页是 preact 渲染的 .title-login，我们往里插自己的节点。
  //       已实测：插入后即使 preact 重渲染也不会被抹掉（与大厅联网面板同一套路）。
  // ======================================================================================

  var userBtn = null;      // 「用户」按钮
  var userModal = null;    // 弹窗根节点
  var nameInput = null;    // 游戏自己的代号输入框
  var nameWatcher = null;  // 输入框监听解绑函数
  var lastName = '';       // 上次同步过的名字（避免循环写）

  // ---------------- 数据快照：把「用户数据」在 origin 之间隔离 ----------------
  //
  //  游戏把玩家数据放在 localStorage 的 sp.* 键里。localStorage 按 origin 隔离，
  //  所以单机(127.0.0.1) 和 联机(<主机IP>) 天然是两套；但同一 origin 下所有用户
  //  共用同一份 —— 这就是「切了用户数据还共通」的原因。
  //  解法：切用户 / 进出 origin 时，把这一份 sp.* 打包存进原生侧（按 uid + originTag 分开保管），
  //        再把目标用户的那一份铺回来。

  /** 当前 origin 的标签：单机 = local，联机 = remote:<host>（按主机分开保管）。 */
  function originTag() {
    try {
      var h = String(location.hostname || '');
      if (h === '127.0.0.1' || h === 'localhost' || h === '::1') return 'local';
      return 'remote:' + h;
    } catch (e) { return 'local'; }
  }

  /**
   * 属于「用户数据」的键。
   *  - sp.name           玩家代号
   *  - sp.pref.*         全部偏好（设置/战绩/最近房间/配装/收藏…）
   * 明确排除：
   *  - sp.token / sp.tokens  会话令牌，与 origin 绑定，跨用户搬运会导致串号
   *  - sp.matches            联机房间的短期状态，不属于个人档案
   *  - xj.*                  我们自己的键（网络模式/FRP 等设备级设置）
   */
  function isUserKey(k) {
    if (k === 'sp.name') return true;
    if (k.indexOf('sp.pref.') === 0) return true;
    return false;
  }

  /** 打包当前 origin 下所有用户数据。 */
  function snapshotUserData() {
    var out = {};
    try {
      for (var i = 0; i < localStorage.length; i++) {
        var k = localStorage.key(i);
        if (isUserKey(k)) out[k] = localStorage.getItem(k);
      }
    } catch (e) { /* ignore */ }
    return out;
  }

  /** 用快照覆盖当前 origin 的用户数据（多余的同类键先清掉，避免残留）。 */
  function restoreUserData(obj) {
    if (!obj || typeof obj !== 'object') return;
    try {
      var victims = [];
      for (var i = 0; i < localStorage.length; i++) {
        var k = localStorage.key(i);
        if (isUserKey(k)) victims.push(k);
      }
      for (var j = 0; j < victims.length; j++) localStorage.removeItem(victims[j]);
      for (var key in obj) {
        if (Object.prototype.hasOwnProperty.call(obj, key) && isUserKey(key) && obj[key] != null) {
          localStorage.setItem(key, String(obj[key]));
        }
      }
    } catch (e) { /* ignore */ }
  }

  /**
   * 把当前 origin 的数据存回「原生侧当前用户」的存档。
   * 注意：原生侧的「当前用户」必须先切到目标 uid，再调这里。
   */
  function pushUserDataNow() {
    try {
      var json = JSON.stringify(snapshotUserData());
      return call2('pushUserData', originTag(), json) !== null;
    } catch (e) { return false; }
  }

  /** 取出「原生侧当前用户」在当前 origin 的存档。 */
  function pullUserDataNow() {
    try {
      var raw = call('pullUserData', originTag());
      if (typeof raw !== 'string' || !raw) return null;
      var o = JSON.parse(raw);
      if (!o || typeof o !== 'object') return null;
      return o;
    } catch (e) { return null; }
  }

  /** 当前用户 uid（原生侧）。 */
  function currentUid() {
    try {
      var u = call('currentUser');
      return (typeof u === 'string' && u) ? u : '';
    } catch (e) { return ''; }
  }

  /**
   * 切换到指定用户，并把这一端的数据一起搬过去。
   * 返回 true = 成功（原生侧已切到 newUid，localStorage 已铺成新用户的数据）。
   */
  function switchUserWithData(newUid) {
    if (!newUid) return false;
    var oldUid = currentUid();
    if (oldUid === newUid) return true;

    // ① 回收旧用户：把当前 localStorage 存进旧用户的存档
    if (oldUid) {
      // 原生侧当前仍是 oldUid，直接存
      pushUserDataNow();
    }
    // ② 切到新用户
    if (call('switchUser', newUid) === null) return false;
    if (currentUid() !== newUid) return false;
    // ③ 铺开新用户的存档（没有则清空，绝不残留上一个用户的数据）
    var snap = pullUserDataNow();
    restoreUserData(snap && Object.keys(snap).length ? snap : {});
    invalidateUsers();
    lastName = '';          // 让输入框重新跟随新用户
    return true;
  }

  /** 数据变了（改设置/打完一局）就悄悄存一份，防意外退出丢档。 */
  var autosaveTimer = null;
  function startAutosave() {
    if (autosaveTimer) return;
    autosaveTimer = setInterval(function () {
      if (!currentUid()) return;
      // 只在标题页/大厅静默保存，避免打断游戏内操作
      var busy = document.querySelector('.screen.game-screen, .screen.battle-screen, .screen.loading-screen');
      if (busy) return;
      pushUserDataNow();
    }, 30000);
  }

  /** 页面重载（切档后必须重载，游戏才会重新读 localStorage）。 */
  function reloadSoon() {
    // 注意：不要在这里再 pushUserDataNow()。此刻原生侧已是新用户，
    // 会把刚铺进去的新档原样写回（无害但多余），真正的回收在 switchUserWithData 里已完成。
    setTimeout(function () {
      try { location.reload(); } catch (e) { /* ignore */ }
    }, 260);
  }

  var usersCache = null;      // 用户摘要缓存（tick 每帧都读，别每帧过 JNI）
  var usersCacheAt = 0;

  /** 桥：读用户摘要。失败返回 null（原生桥缺失/异常都不能拖垮页面）。 */
  function readUsers() {
    try {
      var raw = call('usersJson');
      if (typeof raw !== 'string' || !raw) return null;
      var o = JSON.parse(raw);
      if (!o || !Array.isArray(o.users)) return null;
      return o;
    } catch (e) { return null; }
  }

  /** 带缓存读用户摘要：同一帧/短时间内复用，避免 tick 反复走同步 JNI。 */
  function readUsersCached() {
    var now = Date.now();
    if (usersCache && (now - usersCacheAt) < 400) return usersCache;
    var fresh = readUsers();
    usersCache = fresh;
    usersCacheAt = now;
    return fresh;
  }

  /** 任何写操作后调用，让下一次读取拿到最新值。 */
  function invalidateUsers() { usersCache = null; usersCacheAt = 0; }

  /** 当前用户对象（无则 null）。走缓存版，供 tick/refreshUserBtn 热路径调用。 */
  function currentUserObj() {
    var s = readUsersCached();
    if (!s || !s.current) return null;
    for (var i = 0; i < s.users.length; i++) {
      if (s.users[i].uid === s.current) return s.users[i];
    }
    return null;
  }

  /** 把当前用户名写进游戏的代号输入框（并同步进游戏自己的存储）。 */
  function applyNameToInput(name) {
    if (!nameInput || !name) return;
    if (nameInput.value === name) return;   // 已是目标值 → 不写、不派发事件
    try {
      var proto = window.HTMLInputElement && window.HTMLInputElement.prototype;
      var desc = proto && Object.getOwnPropertyDescriptor(proto, 'value');
      if (desc && desc.set) desc.set.call(nameInput, name);   // 绕过 preact 的受控绑定
      else nameInput.value = name;
      lastName = name;
      // 派发 input 让 preact 侧状态跟上（我们自己的 onInput 会因 v===lastName 直接返回）
      nameInput.dispatchEvent(new Event('input', { bubbles: true }));
    } catch (e) { /* ignore */ }
  }

  /** 读当前输入框里的名字首选项（供 fallback）。 */
  function readInputName() {
    return nameInput ? String(nameInput.value || '').trim() : '';
  }

  // ---------------- 弹窗 ----------------

  function closeUserModal() {
    // 先把焦点从弹窗内的输入框挪走，否则输入法会一直挂在屏幕上不走
    try {
      var a = document.activeElement;
      if (a && a.blur && userModal && userModal.contains(a)) a.blur();
    } catch (e) { /* ignore */ }
    if (userModal && userModal.parentNode) userModal.parentNode.removeChild(userModal);
    userModal = null;
  }

  function buildUserModal() {
    var users = readUsers();
    var list = users ? users.users : [];
    var current = users ? users.current : '';
    var full = users ? !!users.full : false;
    var max = users ? (users.max || 10) : 10;

    var mask = document.createElement('div');
    mask.className = 'xj-umask';
    mask.addEventListener('click', function (ev) { if (ev.target === mask) closeUserModal(); });

    var box = document.createElement('div');
    box.className = 'xj-umodal brackets';
    box.setAttribute('role', 'dialog');
    box.setAttribute('aria-label', '用户');

    // 标题
    var head = document.createElement('div');
    head.className = 'xj-umodal__head';
    var h = document.createElement('span');
    h.className = 'xj-umodal__title';
    h.textContent = '用户';
    var micro = document.createElement('span');
    micro.className = 'xj-umodal__micro';
    micro.textContent = list.length + ' / ' + max;
    head.appendChild(h);
    head.appendChild(micro);
    var x = document.createElement('button');
    x.type = 'button';
    x.className = 'xj-umodal__x';
    x.setAttribute('aria-label', '关闭');
    x.textContent = '×';
    x.addEventListener('click', closeUserModal);
    head.appendChild(x);
    box.appendChild(head);

    // 列表
    var ul = document.createElement('div');
    ul.className = 'xj-ulist';
    if (!list.length) {
      var empty = document.createElement('p');
      empty.className = 'xj-uhint';
      empty.textContent = '还没有用户 —— 在下面输入名字创建一个。';
      ul.appendChild(empty);
    } else {
      list.forEach(function (u) {
        var row = document.createElement('div');
        row.className = 'xj-urow' + (u.uid === current ? ' is-on' : '');
        row.setAttribute('role', 'button');
        row.setAttribute('tabindex', '0');

        var dot = document.createElement('span');
        dot.className = 'xj-urow__dot';
        row.appendChild(dot);

        var nm = document.createElement('span');
        nm.className = 'xj-urow__name';
        nm.textContent = u.name || '(未命名)';
        row.appendChild(nm);

        if (u.uid === current) {
          var cur = document.createElement('span');
          cur.className = 'xj-urow__cur';
          cur.textContent = '当前';
          row.appendChild(cur);
        }

        var pick = function () {
          if (u.uid === current) { closeUserModal(); return; }
          // 切换 = 切档 + 搬数据。搬数据必须显式指定新旧 uid，
          // 因为游戏把 sp.* 读进了内存，换完得让页面重载才会生效。
          if (switchUserWithData(u.uid)) {
            toast('已切换到 ' + (u.name || '') + '，正在重载…');
            closeUserModal();
            reloadSoon();
          } else {
            toast('切换失败');
          }
        };
        row.addEventListener('click', pick);
        row.addEventListener('keydown', function (ev) {
          if (ev.key === 'Enter' || ev.key === ' ') { ev.preventDefault(); pick(); }
        });

        // 改名 / 删除
        var acts = document.createElement('span');
        acts.className = 'xj-urow__acts';

        var ren = document.createElement('button');
        ren.type = 'button';
        ren.className = 'xj-uact';
        ren.textContent = '改名';
        ren.addEventListener('click', function (ev) {
          ev.stopPropagation();
          var nn = window.prompt('新的名字', u.name || '');
          if (nn == null) return;
          // 改名必须三处对齐（目录 + 本 origin 的 sp.name + 快照里的 sp.name），
          // 否则重进会被旧快照覆盖回去。renameUserSync 已含后两处的原生侧刷新。
          var applied = call2('renameUserSync', u.uid, nn);
          if (typeof applied === 'string' && applied) {
            invalidateUsers();
            if (u.uid === current) {
              try { localStorage.setItem('sp.name', applied); } catch (e) { }
              pushUserDataNow();
              lastName = applied;
              if (nameInput && readInputName() !== applied) applyNameToInput(applied);
            }
            toast('已改名');
            closeUserModal(); openUserModal();
          } else {
            toast('名字不合法');
          }
        });
        acts.appendChild(ren);

        var del = document.createElement('button');
        del.type = 'button';
        del.className = 'xj-uact xj-uact--danger';
        del.textContent = '删除';
        del.addEventListener('click', function (ev) {
          ev.stopPropagation();
          if (!window.confirm('删除用户「' + (u.name || '') + '」？\n该用户的数据会一并删除，无法恢复。')) return;
          var wasCurrent = (u.uid === current);
          if (call('deleteUser', u.uid)) {
            invalidateUsers();
            var left = currentUid();
            if (wasCurrent) {
              // 删掉的是当前用户 → 原生侧会清空 current，需要重载页面
              restoreUserData({});
              toast('已删除，正在重载…');
              closeUserModal();
              reloadSoon();
              return;
            }
            if (left) {
              // 删的是别的用户，当前用户不变，但要保证本地数据属于当前用户
              var snap2 = pullUserDataNow();
              if (snap2 && Object.keys(snap2).length) restoreUserData(snap2);
            }
            toast('已删除');
            closeUserModal(); openUserModal();
          } else {
            toast('删除失败');
          }
        });
        acts.appendChild(del);
        row.appendChild(acts);

        ul.appendChild(row);
      });
    }
    box.appendChild(ul);

    // 创建新用户
    var create = document.createElement('div');
    create.className = 'xj-ucreate';
    var inp = document.createElement('input');
    inp.className = 'xj-ucreate__input';
    inp.type = 'text';
    inp.maxLength = 12;
    inp.setAttribute('autocapitalize', 'off');
    inp.setAttribute('autocorrect', 'off');
    inp.setAttribute('spellcheck', 'false');
    inp.placeholder = full ? '已达上限（' + max + ' 个）' : '输入名字创建新用户';
    inp.disabled = full;
    create.appendChild(inp);

    var add = document.createElement('button');
    add.type = 'button';
    add.className = 'xj-ucreate__btn';
    add.textContent = '创建';
    add.disabled = full;
    var doCreate = function () {
      var nm = String(inp.value || '').trim();
      if (!nm) { toast('请输入名字'); return; }
      var uid = call('createUser', nm);
      if (typeof uid === 'string' && uid) {
        // 先把当前数据收回旧用户，再切到新用户（新用户=空档，页面会是全新的）
        switchUserWithData(uid);
        toast('已创建 ' + nm + '，正在重载…');
        closeUserModal();
        reloadSoon();
      } else {
        toast(full ? '用户已达上限' : '名字不合法');
      }
    };
    add.addEventListener('click', doCreate);
    inp.addEventListener('keydown', function (ev) { if (ev.key === 'Enter') { ev.preventDefault(); doCreate(); } });
    create.appendChild(add);
    box.appendChild(create);

    // ---- 检查更新（标题页也能用：点「用户」弹窗底部）----
    var upd = document.createElement('button');
    upd.type = 'button';
    upd.className = 'xj-ucreate__btn';
    upd.style.marginTop = '10px';
    upd.textContent = '检查更新';
    upd.addEventListener('click', function () {
      if (call('checkUpdate') !== null) toast('正在检查更新…');
      else toast('检查更新不可用');
    });
    box.appendChild(upd);

    mask.appendChild(box);
    return mask;
  }

  function openUserModal() {
    closeUserModal();
    userModal = buildUserModal();
    document.body.appendChild(userModal);
    // 不自动聚焦创建框：一打开就弹输入法会挡住整个列表，用户明确要求改掉。
    // 用户想创建时自己点输入框即可。
  }

  // ---------------- 标题页挂载 ----------------

  /** 找标题页的 .title-login，把「用户」按钮插到「开始」后面。 */
  function mountTitleUi() {
    var login = document.querySelector('.title-login');
    if (!login) return;

    // 输入框（可能被 preact 重建，每次都要重新绑定）
    var inp = login.querySelector('input');
    if (inp !== nameInput) {
      if (nameWatcher) { try { nameWatcher(); } catch (e) { } nameWatcher = null; }
      nameInput = inp || null;
      if (nameInput && !nameInput.__xjBound) {
        nameInput.__xjBound = true;

        /**
         * 用户在「博士代号」框里改了名 → 必须一次改齐三处，否则重进就被重置：
         *   ① 用户目录 name     —— renameUserSync（原生侧，顺带刷新所有快照里的 sp.name）
         *   ② 当前 origin 的 sp.name —— 直接写 localStorage（游戏靠它恢复输入框）
         *   ③ 本地快照            —— pushUserDataNow（把 ② 连同其它 sp.* 一起存回去）
         *
         * 之前只做了 ①，所以「临时改能生效，退出重进又变回原名」。
         */
        var commitRename = function () {
          var v = readInputName();
          if (!v || v === lastName) return;
          var cur = currentUserObj();
          if (!cur) return;

          var applied = call2('renameUserSync', cur.uid, v);
          var finalName = (typeof applied === 'string' && applied) ? applied : '';
          if (!finalName) return;              // 名字非法 / 改名失败 → 不动

          // ② 写回本 origin 的 sp.name（游戏下次启动就读这个）
          try { localStorage.setItem('sp.name', finalName); } catch (e) { }

          // ③ 连同其它 sp.* 一起存进当前用户的快照
          pushUserDataNow();

          lastName = finalName;
          invalidateUsers();                   // 让「用户」按钮/列表立刻显示新名字
          if (userBtn) refreshUserBtn(userBtn);
        };

        // input 事件太频繁（每敲一个字都触发），会不停跨桥 + 写 localStorage。
        // 用短防抖：停手 600ms 或失焦/回车时才真正落盘。
        var renameTimer = null;
        var scheduleRename = function () {
          if (renameTimer) clearTimeout(renameTimer);
          renameTimer = setTimeout(commitRename, 600);
        };
        var flushRename = function () {
          if (renameTimer) { clearTimeout(renameTimer); renameTimer = null; }
          commitRename();
        };

        nameInput.addEventListener('input', scheduleRename);
        nameInput.addEventListener('change', flushRename);
        nameInput.addEventListener('blur', flushRename);
        nameInput.addEventListener('keydown', function (ev) {
          if (ev.key === 'Enter') flushRename();
        });

        nameWatcher = function () {
          try {
            if (renameTimer) { clearTimeout(renameTimer); renameTimer = null; }
            nameInput.removeEventListener('input', scheduleRename);
            nameInput.removeEventListener('change', flushRename);
            nameInput.removeEventListener('blur', flushRename);
            nameInput.__xjBound = false;
          } catch (e) { }
        };
      }
    }

    // 按钮
    var btn = login.querySelector('.xj-ubtn');
    if (btn) {
      // 已在位：只刷新标签
      refreshUserBtn(btn);
      return;
    }
    var startBtn = login.querySelector('button.btn--primary');
    if (!startBtn || !startBtn.parentNode) return;

    // ---- 首次「输入名字 → 点开始」= 自动建用户 ----
    // 用户要求：第一次进来不该逼他先去「用户」弹窗里建账号，
    // 在代号框里打上名字点「开始」就应该自动创建同名用户。
    // 手法：在捕获阶段先于游戏的 onClick 执行，把用户建好并切过去；
    //      因为这时候还没有任何用户，游戏的 enterSession 会把名字用起来。
    if (!startBtn.__xjStartBound) {
      startBtn.__xjStartBound = true;
      startBtn.addEventListener('click', function (ev) {
        try {
          var typed = readInputName();
          if (!typed) return;                   // 没填名字 → 交给游戏自己提示
          var cur = currentUserObj();
          if (cur) {
            // 已经有用户：只是同步一下名字，不新建
            if (cur.name !== typed) {
              var applied = call2('renameUserSync', cur.uid, typed);
              if (typeof applied === 'string' && applied) {
                try { localStorage.setItem('sp.name', applied); } catch (e) { }
                lastName = applied;
                invalidateUsers();
                if (userBtn) refreshUserBtn(userBtn);
              }
            }
            return;
          }
          // 还没有任何用户 → 用输入的名字建一个，并设为当前用户
          var uid = call('createUser', typed);
          if (typeof uid === 'string' && uid) {
            // ⚠️ 原来没判返回值。switchUser 失败（原生侧 uid 已被删/写入失败）时，
            //    currentUser() 仍是空 → 后续 pullUserData 拿不到档，
            //    现象是「建了用户但数据不保留」。现在失败就当没建成。
            if (call('switchUser', uid) === false) {
              invalidateUsers();
              if (userBtn) refreshUserBtn(userBtn);
              toast('切换用户失败，请重试');
              return;
            }
            invalidateUsers();
            try { localStorage.setItem('sp.name', typed); } catch (e) { }
            lastName = typed;
            if (userBtn) refreshUserBtn(userBtn);
            // 数据快照落一份，重进才有档
            setTimeout(function () { try { pushUserDataNow(); } catch (e) { } }, 0);
            toast('已创建用户 ' + typed);
          }
        } catch (e) { /* 绝不打断游戏的开始流程 */ }
      }, true);   // ← 捕获：必须早于游戏自己的 handler
    }

    btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'xj-ubtn';
    btn.addEventListener('click', function (ev) { ev.preventDefault(); openUserModal(); });
    refreshUserBtn(btn);

    // 「开始」被 preact 包在按钮里，直接插到它后面
    if (startBtn.nextSibling) startBtn.parentNode.insertBefore(btn, startBtn.nextSibling);
    else startBtn.parentNode.appendChild(btn);
    userBtn = btn;

    // 首次挂载：若当前用户有名字，灌进输入框
    var cur2 = currentUserObj();
    if (cur2 && (!readInputName() || readInputName() !== cur2.name)) {
      setTimeout(function () { applyNameToInput(cur2.name); }, 0);
    }
  }

  function refreshUserBtn(btn) {
    var cur = currentUserObj();
    var text = cur ? (cur.name || '用户') : '用户';
    var title = cur ? '当前用户：' + (cur.name || '') : '还没有用户，点击创建';
    var empty = !cur;
    // ⚠️ 必须幂等：MutationObserver 监听整个 body，任何 DOM 写入都会再次触发 tick()。
    //    无脑赋值 textContent / setAttribute 会造成「写 → observer → tick → 再写」死循环，
    //    直接把 WebView 主线程打满（表现为卡在启动页，所有点击无响应）。
    if (btn.__xjText !== text) { btn.textContent = text; btn.__xjText = text; }
    if (btn.__xjTitle !== title) { btn.setAttribute('title', title); btn.__xjTitle = title; }
    if (btn.__xjEmpty !== empty) {
      btn.classList.toggle('is-empty', empty);
      btn.__xjEmpty = empty;
    }
  }

  function unmountTitleUi() {
    if (nameWatcher) { try { nameWatcher(); } catch (e) { } nameWatcher = null; }
    nameInput = null;
    var btn = document.querySelector('.xj-ubtn');
    if (btn && btn.parentNode) btn.parentNode.removeChild(btn);
    userBtn = null;
    closeUserModal();
  }

  // ======================================================================================
  // 5. 生命周期：DOM 一变就检查面板该不该在
  // ======================================================================================
  var ticking = false;   // 重入锁：tick 内部会改 DOM，防止 MutationObserver 递归
  var tickQueued = false;

  function tick() {
    if (ticking) return;         // 已在执行中 → 直接丢弃，等下一轮
    ticking = true;
    try {
      if (document.querySelector('.screen.lobby-screen')) {
        mountPanel();
      } else {
        unmountPanel();
      }
      if (document.querySelector('.screen.title-screen')) {
        mountTitleUi();
      } else {
        unmountTitleUi();
      }
      // 战绩表格（弹窗里）—— 贴上主/客标签
      if (document.querySelector('.st-table--history')) {
        decorateStatsRows();
      }
    } catch (e) {
      if (window.console && console.warn) console.warn('[xj] tick failed', e);
    } finally {
      ticking = false;
    }
  }

  /** 去抖调度：rAF 合并同一帧内的多次 DOM 变更，避免观察器风暴。 */
  function queueTick() {
    if (tickQueued) return;
    tickQueued = true;
    var run = function () { tickQueued = false; tick(); };
    if (window.requestAnimationFrame) window.requestAnimationFrame(run);
    else setTimeout(run, 16);
  }

  /**
   * 名字守护：游戏的 net.js `onWelcome` 会把服务器记住的名字写回 sp.name
   * （见 game/public/js/net.js: `identity.saveName(name)`），
   * 这会在联机时把本地的改名悄悄覆盖掉。
   *
   * 处理策略：**以本地用户名为准**。一旦发现 sp.name 和当前用户的名字不一致，
   * 就把本地名字写回去，并把用户名同步进用户目录。
   * 轮询而不是监听 storage 事件 —— 同页面内的 localStorage 写入不触发 storage 事件。
   */
  function startNameGuard() {
    if (nameGuardTimer) return;
    nameGuardTimer = setInterval(function () {
      try {
        if (!currentUid()) return;
        var cur = currentUserObj();
        if (!cur || !cur.name) return;
        var stored = '';
        try { stored = String(localStorage.getItem('sp.name') || ''); } catch (e) { return; }
        if (stored === cur.name) return;
        // 只有「两边都不为空且真的不同」才纠正，避免首启把空值也当成不一致
        if (!stored) {
          try { localStorage.setItem('sp.name', cur.name); } catch (e) { }
          return;
        }
        // 服务器灌回旧名 → 用用户名盖回去（用户名是玩家在 launcher 里显式设置的，优先）
        try { localStorage.setItem('sp.name', cur.name); } catch (e) { }
        if (nameInput && readInputName() !== cur.name) applyNameToInput(cur.name);
      } catch (e) { /* ignore */ }
    }, 3000);
  }
  var nameGuardTimer = null;

  // ======================================================================================
  // 战绩主 / 客标签
  //
  // 为什么不能改数据、只能改 DOM：
  //   游戏的 ui/stats.js `normalizeRecord()` 是**严格 schema**，未知字段一律丢弃
  //   （见该文件注释「unknown ones are dropped」）。往记录里塞 side 字段是白费功夫。
  //   所以：记录本身不动，主客关系存在**原生侧**（UserStore xj.matchside.<recId>），
  //        渲染时由本模块读出来，贴到战绩表格那一行上。
  //
  // 主客怎么判：记录里 `players[].seat` 就是座位号，**主机坐 0 号位**。
  //   对着 selfId 找到自己那行，seat === 0 → 主，否则 → 客。
  //   只给 roomMode === 'coop'（同盟对局）打标 —— 单机没有主客之分。
  //
  // 写入时机：游戏调 `localStorage.setItem('sp.pref.stats', ...)` 存新记录的那一刻。
  //   同页面内 localStorage 写入**不触发 storage 事件**，所以直接劫持 setItem —— 这是唯一
  //   能在「记录刚生成」这个瞬间拿到它、且不与游戏模块耦合的办法。
  // ======================================================================================

  var STATS_KEY = 'sp.pref.stats';
  var sidesCache = null;          // recId → 'host' | 'guest'
  var sidesCacheAt = 0;

  /** 读全部主客映射（带 400ms 缓存，避免每帧跨桥）。 */
  function readSidesCached() {
    var now = Date.now();
    if (sidesCache && (now - sidesCacheAt) < 400) return sidesCache;
    var o = {};
    try {
      var raw = call('allSidesJson');
      if (typeof raw === 'string' && raw) {
        var p = JSON.parse(raw);
        if (p && typeof p === 'object') o = p;
      }
    } catch (e) { /* ignore */ }
    sidesCache = o; sidesCacheAt = now;
    return o;
  }
  function invalidateSides() { sidesCache = null; sidesCacheAt = 0; }

  /**
   * 从一条战绩记录推断本机是主还是客。
   * @returns {'host'|'guest'|null} null = 单机 / 信息不足（不打标）
   */
  function sideOfRecord(rec) {
    try {
      if (!rec || rec.roomMode !== 'coop') return null;   // 单机没有主客
      var selfId = rec.selfId;
      if (!selfId || !Array.isArray(rec.players)) return null;
      for (var i = 0; i < rec.players.length; i++) {
        var p = rec.players[i];
        if (p && p.playerId === selfId) {
          return (Number(p.seat) === 0) ? 'host' : 'guest';
        }
      }
      return null;
    } catch (e) { return null; }
  }

  /** 把新记录的主客存进原生侧（只补没有的，已存在的不覆盖）；顺带刷新行索引。 */
  function captureSidesFromStats(json) {
    try {
      var env = JSON.parse(json);
      var recs = env && env.records;
      if (!Array.isArray(recs) || !recs.length) return;

      // 行索引：游戏按「时间降序」渲染 records，表格行序与之**一一对应**
      // （stats.js 的 History: records.slice(0, shown) 顺序直出）。
      var idx = [];
      for (var k = 0; k < recs.length; k++) {
        if (recs[k] && recs[k].id) idx.push({ id: recs[k].id, mode: recs[k].roomMode || '' });
      }
      recIndex = idx;

      var known = readSidesCached();
      var added = 0;
      for (var i = 0; i < idx.length; i++) {
        if (known[idx[i].id]) continue;          // 已记过
        var s = sideOfRecord(recs[i]);
        if (!s) continue;
        // markMatchSide 是 void 桥方法（JS 侧返回 undefined）；桥不在时返回 null。
        // 所以判据是「没返回 null」= 调用成功，而不是判断返回真值。
        if (call2('markMatchSide', idx[i].id, s) === null) continue;
        added++;
      }
      if (added) invalidateSides();
    } catch (e) { /* ignore */ }
  }

  /**
   * 劫持 localStorage.setItem —— 只为捕获 sp.pref.stats 的写入，行为完全不变。
   *
   * ⚠️ 风险与对策：这里改的是 **window.localStorage 原型链上的方法**，是全局行为。
   *    · 整个替换在 try/catch 里 —— 某些 WebView 的 Storage 是只读属性，
   *      getPrototypeOf / 赋值会抛，那时 hook 装不上，只是战绩主客标不显示，
   *      游戏本身完全不受影响（绝不抛出去打断游戏）。
   *    · 包装函数**永远调用 orig.apply** 并原样返回其结果，
   *      自己这套逻辑只做旁路观察 + setTimeout 延后，不改写入内容、不改返回值。
   *    · 用 window.__xjStatsHooked 保证只装一次（重复装会层层嵌套，每写一次
   *      localStorage 就多绕一层，最终把写入路径拖垮）。
   *    如果哪天要动这里，先确认上面三条还成立。
   */
  function installStatsHook() {
    if (window.__xjStatsHooked) return;
    window.__xjStatsHooked = true;
    try {
      var proto = Object.getPrototypeOf(window.localStorage);
      var orig = proto.setItem;
      proto.setItem = function (k, v) {
        var r = orig.apply(this, arguments);
        try {
          if (k === STATS_KEY && typeof v === 'string' && v.length > 2) {
            // 延后一拍，别阻塞游戏的写入路径
            setTimeout(function () { captureSidesFromStats(v); }, 0);
          }
        } catch (e) { /* ignore */ }
        return r;
      };
    } catch (e) { /* ignore */ }
  }

  /**
   * 给战绩表格的每一行贴上主/客标记。
   * DOM：table.st-table--history > tbody > tr，第 3 列（「模式」）后面插一个小标。
   * 幂等：已贴过的行看 data-xj-side，值没变就不动 —— 否则会触发 MutationObserver 死循环。
   */
  function decorateStatsRows() {
    var table = document.querySelector('.st-table--history');
    if (!table) return;
    var tbody = table.tBodies && table.tBodies[0];
    if (!tbody) return;

    // 找到表头里「模式」那一列的索引（不硬编码，避免上游改列序）
    // ⚠️ rows 可能为空数组（空表头/表格刚挂载）—— rows[0] 取不到 cells 会抛，
    //    而这里没有 try/catch，一抛整段 UI 段就中断，战绩标签全都不贴。
    var modeIdx = -1;
    var ths = (table.tHead && table.tHead.rows && table.tHead.rows.length > 0)
      ? table.tHead.rows[0].cells
      : null;
    if (ths) {
      for (var i = 0; i < ths.length; i++) {
        var tx = String(ths[i].textContent || '').trim();
        if (tx === '模式') { modeIdx = i; break; }
      }
    }
    if (modeIdx < 0) modeIdx = 2;   // 兜底：当前版本「模式」在第 3 列

    var known = readSidesCached();
    var rows = tbody.rows;
    for (var j = 0; j < rows.length; j++) {
      var tr = rows[j];
      var recId = resolveRowRecId(j);
      var side = recId ? (known[recId] || '') : '';
      applySideBadge(tr, modeIdx, side);
    }
  }

  /**
   * 表格第 j 行对应哪条记录。
   * 依据：游戏的 History 组件就是 `records.slice(0, shown).map(...)` 顺序直出，
   * 行序与记录序**严格一一对应**（records 已在数据层按时间降序排好）。
   * 所以按下标取即可 —— 不需要、也不该去做文本匹配（那是脆的）。
   */
  var recIndex = null;   // [{id, mode}]，顺序与表格行一致

  function buildRecIndex(json) {
    try {
      var env = JSON.parse(json);
      var recs = env && env.records;
      if (!Array.isArray(recs)) return;
      var out = [];
      for (var i = 0; i < recs.length; i++) {
        var r = recs[i];
        if (!r || !r.id) continue;
        out.push({ id: r.id, mode: r.roomMode || '' });
      }
      recIndex = out;
    } catch (e) { /* ignore */ }
  }

  function resolveRowRecId(rowIdx) {
    try {
      if (!recIndex || !recIndex.length) {
        var raw = localStorage.getItem(STATS_KEY);
        if (raw) buildRecIndex(raw);
      }
      if (!recIndex || rowIdx >= recIndex.length) return '';
      return recIndex[rowIdx].id;
    } catch (e) { return ''; }
  }

  /** 把徽章贴到该行的「模式」单元格里（幂等）。 */
  function applySideBadge(tr, modeIdx, side) {
    var cells = tr.cells;
    if (!cells || modeIdx >= cells.length) return;
    var td = cells[modeIdx];
    if (!td) return;

    var cur = td.getAttribute('data-xj-side') || '';
    if (cur === side) return;              // 已一致 → 绝不写 DOM（防死循环）

    var badge = td.querySelector('.xj-side');
    if (!side) {
      if (badge && badge.parentNode) badge.parentNode.removeChild(badge);
      td.removeAttribute('data-xj-side');
      return;
    }
    if (!badge) {
      badge = document.createElement('i');
      badge.className = 'xj-side';
      td.appendChild(badge);
    }
    badge.textContent = side === 'host' ? '主' : '客';
    badge.setAttribute('title', side === 'host' ? '本机是主机（房主）' : '本机是客机（加入方）');
    badge.setAttribute('data-side', side);
    td.setAttribute('data-xj-side', side);
  }

  function start() {
    try {
      document.documentElement.classList.add('xj-touch');
      loadLanHost();
      tick();
      // ⚠️ 这两句以前漏了：autosave 定义了却没启动 → 打完一局/改完设置不会自动存，
      //    强杀进程就丢档（用户报的「退出重进被重置」有一部分是它造成的）。
      startAutosave();
      startNameGuard();
      installStatsHook();
      // 已经在页面上（刷新后重进）的战绩：先把索引建起来
      try {
        var rawStats = localStorage.getItem(STATS_KEY);
        if (rawStats) { buildRecIndex(rawStats); captureSidesFromStats(rawStats); }
      } catch (e) { /* ignore */ }
      // 只观察元素增删（childList），不观察属性/文本 —— 我们的样式都挂在 class 上，
      // 而属性变化会被自己的写入再次触发，是死循环的第二个来源。
      var mo = new MutationObserver(function () { queueTick(); });
      mo.observe(document.body, { childList: true, subtree: true });
      // 首屏渲染完成后再补一次（MutationObserver 有时赶不上 preact 的首帧）
      setTimeout(tick, 300);
      setTimeout(tick, 1200);
    } catch (e) {
      if (window.console && console.warn) console.warn('[xj] inject failed', e);
    }
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', start);
  } else {
    start();
  }

  /**
   * 离开远程房：把这一端的数据先存好，再让原生侧导航回本机首页。
   *
   * 为什么不去「关 socket」：游戏的 WebSocket 实例关在 net.js 的闭包里，注入层拿不到，
   * 硬抓会踩到内部实现。而且**离开这个 origin 本身就会断开连接**（页面一卸载 socket 就没了），
   * 所以这里只做「保住数据」这件真正重要的事，断开交给导航。
   *
   * @returns {boolean} 是否成功发起了返回
   */
  function leaveRemote() {
    try {
      // ① 把联机期间的数据收回当前用户的 remote 档（否则直接导航会丢）
      if (currentUid()) pushUserDataNow();
    } catch (e) { /* ignore */ }
    // ② 让原生跳回本机首页 —— Launcher.home() 是 Java 侧实现的
    if (call('home') !== null) return true;
    // ③ 桥不可用时退而求其次：直接改 location（WebView 会跟着走）
    try { location.href = 'http://127.0.0.1:3000/'; return true; } catch (e) { return false; }
  }

  // 暴露给原生 / 调试用
  window.__xj = {
    setMode: pickMode,
    mode: function () { return netMode; },
    openUsers: openUserModal,
    user: currentUserObj,
    leaveRemote: leaveRemote,
    openRemote: openRemote
  };
})();
