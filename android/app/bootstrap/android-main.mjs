// android-main.mjs — 安卓启动器的 Node 入口
//
// 这一份文件是安卓专用的「编排脚本」，不参与游戏本体更新（游戏更新只动 game/ 目录）。
// 它只做三件事，顺序执行在同一Node进程里（libnode 单实例，没有第二个 node 可执行文件）：
//
//   1) 按 data/assets.json 抽样自检 public/assets 是否完整
//   2) 缺素材 → 触发 tools/fetch-assets.mjs 的主流程下载（走 GitHub 镜像，不设任何代理环境变量）
//      下载进度通过独立的 127.0.0.1:3001 接口暴露给 Java 层（不依赖 stdout 转发）
//   3) 素材齐了 → startServer({host:'127.0.0.1'}) 起游戏服务（端口 3000）
//
// 为什么用「伪造 process.argv[1] 触发 fetch-assets 顶层 main()」而不是改游戏源码：
//   fetch-assets.mjs 末尾 `if (invoked === import.meta.url && !restartForEnvProxy()) main()` 只在
//   被当作脚本直接运行时执行。把 argv[1] 指到它自己，import 时那段判断就成立，main() 自动跑——
//   完整复用其下载/Spine后处理/字体/manifest 重建逻辑，零修改游戏代码。
//
// ⚠️ 安卓专用：多镜像实时测速 + 全局 fetch 重写（见底部 MIRROR 段）
//   游戏原生的 fetch-assets 在「镜像连续失败 3 次」后会熔断、退回直连 github.com，而在国行手机上
//   直连 github 是黑洞（connect 成功但 body 永远不来），每个文件卡满 2 分钟×3 重试 → 进度假死。
//   所以在 import fetch-assets 之前，装一层 fetch 重写：① 启动时并发探测所有镜像、按延迟排序取最快；
//   ② 任何 github 主机请求一律改走排序后的镜像、绝不留直连；③ 每个请求硬超时 45s，杜绝无限挂起。

import http from 'node:http';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const ROOT = path.dirname(__filename);                 // nodejs-project 根
const ASSETS_DIR = path.join(ROOT, 'public', 'assets');
const ASSETS_JSON = path.join(ROOT, 'data', 'assets.json');
const PROGRESS_PORT = 3001;
const META_PORT = 3002;          // 常驻：给房主 UI 提供本机 LAN IP（拼局域网邀请链接用）
const SERVER_PORT = 3000;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const log = (...a) => console.log('[android]', ...a);

// ============================================================
//  崩溃兜底（第一层修复，2026-10-12）
// ============================================================
// 游戏自己本来有这对 handler，装在 server/http/boot.js:83-84 的 runMain() 里。
// 但 runMain() 只在 isProcessEntry() 为真时执行，而它比的是
//   fs.realpathSync(process.argv[1]) === fs.realpathSync(fileURLToPath(import.meta.url))
// 安卓上 process.argv[1] 是**本文件**（NodeService 传的 entry），不是 server/index.js
//   → isProcessEntry() 恒为 false → runMain() 从未执行 → 那对 handler 从来没装上。
//
// 后果：Windows/macOS 上服务器某处抛个未捕获异常，只是打行日志、游戏继续跑；
//       安卓上同一个异常直接杀死 Node → 服务变僵尸（通知还在、端口没人听）→ 游戏卡死。
//
// 这里在 import server **之前**补上，把安卓拉回和桌面端同等的抗崩溃水平。
// 语义与游戏保持一致：只记录、不退出（退出交给上面的启动重试逻辑）。
process.on('unhandledRejection', (e) => console.error('[android] unhandled rejection', e));
process.on('uncaughtException', (e) => console.error('[android] uncaught exception', e));

// ============================================================
//  多镜像实时测速 + 全局 fetch 重写（安卓专用，零侵入游戏代码）
// ============================================================
const MIRROR_CANDIDATES = [
  'https://gh-proxy.com/',
  'https://ghfast.top/',
  'https://ghproxy.net/',
  'https://gh.monlor.com/',
  'https://slink.ltd/',
  'https://gh.llkk.cc/',
];
// 用一份确定的、约 100KB 的文件测速，延迟 + 吞吐都能反映出来
const PROBE_URL = 'https://github.com/sganggs/Stronghold-Protocol/raw/v0.2.4/data/assets.json';

function mirrorOf(u) {
  for (const m of MIRROR_CANDIDATES) if (u.startsWith(m)) return u.slice(m.length);
  return u;
}
function isGitHub(u) {
  let h;
  try { h = new URL(u).hostname; } catch { return false; }
  return h === 'github.com' || h.endsWith('.githubusercontent.com');
}

async function probeOne(prefix) {
  const url = prefix + PROBE_URL;
  const t0 = Date.now();
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), 8000);
  try {
    const res = await fetch(url, { signal: ctrl.signal, headers: { 'user-agent': 'sh-probe' } });
    if (!res.ok) return { prefix, ok: false, ms: 99999 };
    const buf = await res.arrayBuffer();
    const ms = Date.now() - t0;
    const kbps = buf.byteLength / 1024 / (ms / 1000);
    return { prefix, ok: true, ms, kbps };
  } catch {
    return { prefix, ok: false, ms: 99999 };
  } finally {
    clearTimeout(timer);
  }
}

let RANKED = MIRROR_CANDIDATES.slice();
let mi = 0;

async function rankMirrors() {
  log('[mirror] probing ' + MIRROR_CANDIDATES.length + ' mirrors…');
  const results = await Promise.all(MIRROR_CANDIDATES.map(probeOne));
  for (const r of results) log(`[mirror]   ${r.prefix} ${r.ok ? (r.ms + 'ms / ' + r.kbps.toFixed(0) + 'KB/s') : 'FAIL'}`);
  const ok = results.filter((r) => r.ok).sort((a, b) => b.kbps - a.kbps);
  if (ok.length) {
    RANKED = ok.map((r) => r.prefix);
    log('[mirror] using fastest first: ' + RANKED.join(' '));
  } else {
    log('[mirror] all probes failed; will still try each in turn (no direct fallback)');
  }
}

function installFetchShim() {
  const orig = globalThis.fetch;
  globalThis.fetch = async (input, init = {}) => {
    let url = typeof input === 'string' ? input : (input && input.url) || String(input);
    const bare = mirrorOf(url);
    if (isGitHub(bare)) {
      const m = RANKED[mi++ % RANKED.length];
      url = m + bare;
    }
    const TO = 45000; // 硬超时：任何请求 45s 必断，杜绝直连黑洞式无限挂起
    const signal = (init && init.signal)
      ? globalThis.AbortSignal.any([init.signal, globalThis.AbortSignal.timeout(TO)])
      : globalThis.AbortSignal.timeout(TO);
    return orig(url, { ...init, signal });
  };
  log('[mirror] global fetch shim installed');
}

function readJson(p) {
  try { return JSON.parse(fs.readFileSync(p, 'utf8')); } catch { return null; }
}

/** manifest 里登记的总文件数（进度分母）。 */
function totalFiles() {
  const m = readJson(ASSETS_JSON);
  return m && m.stats && typeof m.stats.files === 'number' ? m.stats.files : 0;
}

/** 递归统计某目录下文件数（下载进度分子）。 */
function countFiles(dir) {
  let n = 0;
  const walk = (d) => {
    let ents;
    try { ents = fs.readdirSync(d, { withFileTypes: true }); } catch { return; }
    for (const e of ents) {
      const p = path.join(d, e.name);
      if (e.isDirectory()) walk(p);
      else if (e.isFile()) n++;
    }
  };
  walk(dir);
  return n;
}

/**
 * 下载进度分子：public/assets 下的文件数，**排除清单（assets.json）不含的 local/**。
 *
 * local/ 是从官方客户端本地提取的素材（官方 3D 棋盘、部分图标、召唤物模型），
 * 既不在 assets.json 清单里、也不由 fetch-assets 下载；老版本曾把它内置进 APK，
 * 残留会让「已下载」数超过清单总数（12570 vs 10956）。这里排除后分子与分母口径一致。
 */
function countProgressFiles() {
  const n = countFiles(ASSETS_DIR) - countFiles(path.join(ASSETS_DIR, 'local'));
  return n > 0 ? n : 0;
}

/**
 * 抽样自检：public/assets 目录存在、且文件数达到清单的 90% 视为完整。
 * 素材下载是幂等的，缺一两个不致命（客户端有 fallback），所以 90% 阈值足够稳。
 */
function assetsComplete() {
  if (!fs.existsSync(ASSETS_DIR)) return false;
  const total = totalFiles();
  const have = countFiles(ASSETS_DIR);
  if (total > 0) return have >= Math.floor(total * 0.9);
  return have > 50; // 没有清单时，有足量文件就算完整
}

/** 下载阶段进度接口：GET 127.0.0.1:3001/progress → {pct, done, total, phase} */
function startProgressServer(total) {
  const state = {
    phase: 'download',
    pct: 0,
    done: 0,
    total,
    msg: '首次启动需下载游戏素材（约 550MB，请保持 WiFi）',
  };
  (async () => {
    while (true) {
      try {
        const raw = countProgressFiles();
        // 分母 = assets.json 清单登记的 stats.files（下载项）。即使排除了 local/，仍可能有
        // 别的清单外文件，这里再把分子夹到分母以内，保证显示恒为 已下载 ≤ 总数。
        const done = total ? Math.min(raw, total) : raw;
        state.done = done;
        state.pct = total ? Math.min(99, Math.round((done / total) * 100)) : 0;
      } catch { /* ignore */ }
      await sleep(1000);
    }
  })();
  const srv = http.createServer((req, res) => {
    res.setHeader('Access-Control-Allow-Origin', '*');
    res.setHeader('Content-Type', 'application/json');
    res.end(JSON.stringify(state));
  });
  srv.listen(PROGRESS_PORT, '127.0.0.1', () => log('progress server on', PROGRESS_PORT));
  return srv;
}

/**
 * 常驻的启动器 meta 服务（127.0.0.1:3002）：给房主 UI 暴露本机 LAN IP，
 * 让「局域网」模式下拼出的邀请链接 host 是手机真实网卡 IP（不是 127.0.0.1），
 * 同 WiFi 的朋友才连得到。带 CORS，WebView 内同源跨端口也能取。
 * 这个服务独立于游戏服务(3000)与下载进度服务(3001)，不干扰它们。
 */
function detectLanIp() {
  try {
    const ifaces = os.networkInterfaces();
    for (const name of Object.keys(ifaces)) {
      for (const ni of ifaces[name] || []) {
        if (ni.family === 'IPv4' && !ni.internal) return ni.address;
      }
    }
  } catch { /* ignore */ }
  return null;
}
function startMetaServer() {
  const lanIp = detectLanIp();
  const srv = http.createServer((req, res) => {
    res.setHeader('Access-Control-Allow-Origin', '*');
    res.setHeader('Content-Type', 'application/json');
    const url = (req.url || '').split('?')[0];
    if (url === '/lan') {
      res.end(JSON.stringify({ ip: lanIp, port: SERVER_PORT }));
      return;
    }
    res.statusCode = 404;
    res.end(JSON.stringify({ error: 'not found' }));
  });
  srv.listen(META_PORT, '127.0.0.1', () => log('meta server on', META_PORT, 'lanIp=', lanIp));
  return srv;
}

/**
 * 触发 fetch-assets 的主流程（不修改游戏源码）：
 * 把 process.argv[1] 指到 fetch-assets.mjs 本身，再 import 它——顶层那段
 * `if (invoked === import.meta.url && !restartForEnvProxy()) main()` 就会成立并执行。
 */
async function runFetchAssets() {
  const faPath = path.join(ROOT, 'tools', 'fetch-assets.mjs');
  const savedArgv1 = process.argv[1];
  process.argv[1] = faPath;

  // 走 GitHub 镜像（连续失败会自动熔断回退 direct 源——但我们已用 fetch 重写层
  // 把 github 主机强制改走测速选出的镜像，直连分支形同虚设，熔断也无副作用）。
  process.env.SP_ASSET_SOURCE = process.env.SP_ASSET_SOURCE || 'mirror';
  process.env.SP_GITHUB_PROXY = process.env.SP_GITHUB_PROXY || 'https://gh-proxy.com/';
  // 绝不设 HTTP_PROXY/HTTPS_PROXY：否则 tools/assets/env-proxy.mjs 会用 spawn 重启自己，
  // 而安卓没有独立 node 二进制，spawn 必然失败、下载中断。node_bridge.cpp 已经 unset 过一次，这里再保险。
  delete process.env.HTTP_PROXY; delete process.env.HTTPS_PROXY;
  delete process.env.http_proxy; delete process.env.https_proxy;

  // ★ 安卓关键：先测速选优，再装 fetch 重写层（必须在 import fetch-assets 之前）
  await rankMirrors();
  installFetchShim();

  log('triggering fetch-assets (source=' + process.env.SP_ASSET_SOURCE +
      ' proxy=' + process.env.SP_GITHUB_PROXY + ')');
  await import(pathToFileURL(faPath).href); // 顶层 if 块执行 main()
  process.argv[1] = savedArgv1;

  // 等 main() 跑完（它末尾设 process.exitCode；不调用 process.exit，不会杀进程）
  const deadline = Date.now() + 45 * 60 * 1000;
  while (process.exitCode === undefined && Date.now() < deadline) await sleep(500);
  log('fetch-assets finished (exitCode=' + process.exitCode + ')');
}

// ------------------------------------------------------------
//  启动编排（第二层修复，2026-10-12）
// ------------------------------------------------------------
// 原来的写法是 main().catch → process.exit(1)：下载抖一下、或 startServer 抛个错，
// 整个 Node 进程当场自杀，服务变成「通知还在但端口没人听」的僵尸，用户只能杀应用。
//
// 现在拆成三段，各自有不同的失败策略：
//   ① 素材下载 —— 失败**不致命**。assetsComplete() 本来就是 90% 阈值，客户端也有
//      fallback（缺图退程序生成、缺模型退方块），所以下载挂了记一笔、继续起服务，
//      让用户至少能进游戏，下次启动会再试。
//   ② 起游戏服务 —— **带退避重试**。这是最值得重试的一段：端口 TIME_WAIT、瞬时
//      资源竞争都可能让第一次 listen 失败，等几秒就好了。
//   ③ 元服务(3002) —— 全局只起一次，重试时复用，否则第二次 listen 必然 EADDRINUSE。
const MAX_SERVER_ATTEMPTS = 3;
const SERVER_BACKOFF_MS = [5000, 15000];

let metaServer = null;
function ensureMetaServer() {
  if (metaServer) return metaServer;
  metaServer = startMetaServer();
  return metaServer;
}

/** 起一次游戏服务；失败抛给调用方重试。 */
async function startGameServer() {
  ensureMetaServer();
  const { startServer } = await import(pathToFileURL(path.join(ROOT, 'server', 'index.js')).href);
  const srv = await startServer({ host: '0.0.0.0' });
  log('server ready: ' + srv.url);

  const stop = () => { try { srv.close(); } catch { /* ignore */ } process.exit(0); };
  process.on('SIGINT', stop);
  process.on('SIGTERM', stop);
  return srv;
}

async function main() {
  const total = totalFiles();
  if (assetsComplete()) {
    log('assets present, skip download');
  } else {
    log('assets missing, downloading…');
    const prog = startProgressServer(total);
    try {
      await runFetchAssets();
    } catch (e) {
      // 下载失败不当场自杀：能起服务就先让用户玩，缺的素材下次启动再补。
      console.error('[android] asset download failed (will continue anyway)', e);
    } finally {
      try { prog.close(); } catch { /* ignore */ }
    }
  }

  // 起游戏服务，带退避重试。host 0.0.0.0：绑全网卡，同 WiFi 朋友 + 手机上的樱花 frpc
  // （连 127.0.0.1:3000）都能达；WebView 仍走 127.0.0.1:3000，单机/AI 对战不受影响。
  for (let attempt = 1; ; attempt++) {
    try {
      await startGameServer();
      return;
    } catch (e) {
      const inUse = e && e.code === 'EADDRINUSE';
      console.error(`[android] startServer attempt ${attempt} failed` +
          `${inUse ? ' (port in use)' : ''}`, e);
      if (attempt > MAX_SERVER_ATTEMPTS) {
        console.error('[android] FATAL: giving up after ' + attempt + ' attempts');
        throw e;
      }
      const wait = SERVER_BACKOFF_MS[Math.min(attempt - 1, SERVER_BACKOFF_MS.length - 1)];
      log(`retrying startServer in ${wait}ms (${attempt}/${MAX_SERVER_ATTEMPTS})…`);
      await sleep(wait);
    }
  }
}

// 只有「重试全部失败」才会走到这里 —— 那确实是起不来了，退出让 Java 层看到。
main().catch((e) => {
  console.error('[android] FATAL', e);
  process.exit(1);
});
