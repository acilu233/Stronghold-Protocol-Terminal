package com.stronghold.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.Context;
import android.content.ClipboardManager;
import android.content.ClipData;
import android.net.Uri;
import java.net.NetworkInterface;
import java.net.InetAddress;
import java.util.Enumeration;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.view.animation.LinearInterpolator;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.EditText;
import android.widget.Toast;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.widget.Button;
import android.widget.ProgressBar;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 启动器主界面。
 *
 * 启动顺序（和 Windows 版 Electron 启动器一一对应）：
 *   1. 显示启动画面                          ← splash.html
 *   2. 校验 / 展开本地文件（自检）            ← selfcheck.mjs
 *   3. 拉起 Node 服务（前台服务 + libnode）
 *   4. 轮询 /healthz 直到服务真的活了          ← 比 Windows 版更严格：
 *                                              Windows 版「端口开了」就算好，
 *                                              这里是「HTTP 200」才算好
 *   5. WebView 载入 http://127.0.0.1:3000     ← BrowserWindow
 *
 * 不调用系统浏览器 —— WebView 内置在 APK 里。
 */
public class MainActivity extends Activity {

    private static final String TAG = "SHL";
    public static final int PORT = 3000;
    public static final String BASE = "http://127.0.0.1:" + PORT;

    // 下载阶段可能要几分钟（约 550MB），健康检查超时放宽到 30 分钟
    private static final int DOWNLOAD_TIMEOUT_MS = 30 * 60 * 1000;

    // ── 检查更新：统一更新管线（手动弹窗进度 + 启动静默检查，共用 UpdateService/GameUpdater）──
    private static final int STATE_CHECKING = 0, STATE_UPTODATE = 1, STATE_AVAILABLE = 2,
            STATE_DOWNLOADING = 3, STATE_DONE = 4, STATE_ERROR = 5;
    private AlertDialog updateDialog;
    private TextView updateMsgTv;
    private ProgressBar updateProgressPb;
    private boolean updateDownloading = false;
    private String updateTag, updateName;
    private final BroadcastReceiver updateProgressReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent i) {
            if (i == null || !UpdateService.ACTION_PROGRESS.equals(i.getAction())) return;
            if (updateDialog == null || !updateDialog.isShowing() || !updateDownloading) return;
            int p = i.getIntExtra(UpdateService.EXTRA_PROGRESS, 0);
            String phase = i.getStringExtra(UpdateService.EXTRA_PHASE);
            String msg = i.getStringExtra(UpdateService.EXTRA_MESSAGE);
            if ("error".equals(phase)) {
                updateDialogState(STATE_ERROR, msg != null ? msg : "下载失败", 0);
            } else if ("done".equals(phase)) {
                updateDialogState(STATE_DONE, "下载完成，正在应用并重启…", 100);
            } else {
                String text = (msg != null && !msg.isEmpty()) ? msg : ("正在下载更新包… " + p + "%");
                updateDialogState(STATE_DOWNLOADING, text, p);
            }
        }
    };

    private WebView web;
    private FrameLayout splash;
    private TextView splashText;
    private TextView splashSub;
    private SplashProgress splashBar;
    private HexView splashHex;
    private TextView menuArrow;          // 顶部小箭头触发（常驻，可左右拖动）
    private LinearLayout menuPanel;       // 折叠菜单面板
    private boolean menuOpen = false;
    private boolean arrowDragging = false;
    private TextView menuReturn;          // 「返回本地」项（仅远程连接可用）
    private TextView menuNetLabel;        // 「联机方式」项（显示当前模式）
    private LinearLayout menuWanRow;       // 外部连接穿透网址输入框（默认隐藏）
    private EditText menuWanInput;         // 穿透网址输入框
    private TextView menuWanJoin;          // 「加入」按钮
    private final LauncherBridge bridge = new LauncherBridge();

    // —— 二次返回退出（防手滑）——
    private long lastBackAt = 0;
    private static final long BACK_EXIT_WINDOW_MS = 2000;

    // ── 检查更新：上游版本来源（GitHub Releases）──
    // 比对的是**游戏（game/）上游版本**，不是启动器自身版本；启动器版本仅作展示。
    // 上游仓库即本游戏同人复刻的官方源（game/ 来自此仓库），其 Releases 即游戏更新。
    private static final String UPDATE_REPO = "sganggs/Stronghold-Protocol";
    private static final String UPDATE_API_URL =
            "https://github.com/" + UPDATE_REPO + "/releases.atom";

    // ---------------- 启动器注入（只改启动器，game/ 一个字节都不动） ----------------
    // 思路：shouldInterceptRequest 拦下 index.html 的响应，把启动器自己的
    // inject.js / inject.css 插进去。上游若覆盖 game/ 目录，注入的这两个文件在 assets 里，
    // 不受影响，功能照样生效。
    //
    // 注入分两段（见 fetchAndInject）：
    //   ① 早期段 → <head>，抢在游戏读 localStorage 之前铺用户存档；
    //   ② UI 段   → </body> 前，此时 body 已存在，可以插按钮/弹窗。
    private static final String INJECT_JS_PATH = "inject/inject.js";
    private static final String INJECT_CSS_PATH = "inject/inject.css";
    /** inject.js 里必须出现这个标记：之前是早期段，之后是 UI 段。 */
    private static final String XJ_EARLY_SPLIT = "/*__XJ_EARLY_SPLIT__*/";
    private volatile String injectJs;    // 懒加载 + 缓存
    private volatile String injectCss;
    private volatile String injectUiJs;  // inject.js 的 UI 段（兜底注入用）

    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean pageShown = false;
    // 远程加入成功弹窗：go(远程地址) 时置位，onPageFinished 消费一次
    private volatile boolean pendingRemoteJoin = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        buildUi();
        // 必须在 setContentView 之后：在那之前 DecorView 还不存在，
        // getInsetsController() 会直接 NPE（真机 LineageOS 22.2 上实测踩到过）
        enterFullScreen();

        if (Build.VERSION.SDK_INT >= 33) {
            try {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 101);
            } catch (Throwable t) {
                Log.w(TAG, "notification permission request failed", t);
            }
        }

        // ── 前台服务必须从「前台 Activity 的主线程」启动（2026-10-13 跨设备实测破案）──
        // 之前 startNodeService() 在 boot() 的后台线程里、等本地文件展开完（数秒~数十秒）
        // 才调，部分 ROM（Android 12+，如 Odin2 Mini / Android 13）此时把 App 判成后台
        // （uidState=CAC），直接拒绝 startForegroundService
        //   "Background started FGS: Disallowed … startForegroundService() not allowed
        //    due to mAllowStartForeground false"
        // → 服务起不来、进程被系统杀、游戏打不开（表现就是「贴图没了/黑屏」）。
        // 改在 onCreate 主线程（App 正前台拉起）就拉起服务；本地文件展开已挪到
        // NodeService.runNode 的服务线程里做，展开完再起 Node，互不阻塞主线程。
        NodeService.installProgress = (msg, frac) -> ui.post(() -> {
            setStatus(msg, "");
            setProgress(frac);
        });
        startNodeService();

        // 启动静默检查：注册进度广播；首启（游戏在下载素材）跳过，之后每次启动按节流查一次
        // ⚠️ Android 13+（targetSdk 34）注册非系统广播必须显式声明导出标志，否则
        //    SecurityException 直接崩在 onCreate（API 33 前没有该常量，需分支）。
        IntentFilter progressFilter = new IntentFilter(UpdateService.ACTION_PROGRESS);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(updateProgressReceiver, progressFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(updateProgressReceiver, progressFilter);
        }
        boolean firstLaunch = !getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("launchedBefore", false);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("launchedBefore", true).apply();
        if (!firstLaunch) maybeSilentUpdateCheck();

        Thread t = new Thread(this::boot, "boot");
        t.setDaemon(true);
        t.start();
    }

    private void enterFullScreen() {
        try {
            // ① 关键一步：让窗口铺进挖孔区。
            //    必须**直接给 Window 属性赋值**，不能依赖主题里那条
            //    windowLayoutInDisplayCutoutMode —— K70 实测主题值没进 mAttrs，
            //    窗口左边界被推到 x=104（挖孔宽），左边一条黑带。
            if (Build.VERSION.SDK_INT >= 28) {
                try {
                    WindowManager.LayoutParams lp = getWindow().getAttributes();
                    lp.getClass().getField("layoutInDisplayCutoutMode").setInt(lp, 1); // SHORT_EDGES
                    getWindow().setAttributes(lp);
                } catch (Throwable t) {
                    Log.w(TAG, "cutout mode set failed", t);
                }
            }

            if (Build.VERSION.SDK_INT >= 30) {
                WindowInsetsController c = getWindow().getInsetsController();
                if (c != null) {
                    c.hide(WindowInsets.Type.systemBars());
                    c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
                // ② 窗口不按系统栏/挖孔缩进 —— 铺满整个物理屏幕
                getWindow().setDecorFitsSystemWindows(false);
            } else {
                getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            }
        } catch (Throwable t) {
            // 全屏没做成顶多难看一点，不能让它连累整个 Activity
            Log.w(TAG, "enterFullScreen failed", t);
        }
    }

    // ------------------------------------------------------------------ UI

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#0E141B"));
        // ⚠ 决定性结论：**一个 inset 都不能加**，padding 必须恒为 0，才是真全屏。
        //
        // 踩过的两个坑（都有真机实测数据）：
        //   坑 1 — padding(systemBars())：状态栏/导航栏 hide() 之后仍占位，白留一条。
        //   坑 2 — padding(displayCutout())：本以为是"只避让真挖孔"，结果在挖孔机上
        //          等于**主动把挖孔那条留白**。K70 实测：screenW=915 而 innerW=874，
        //          差 41 CSS px × dpr 2.625 = 108 物理px ≈ 挖孔宽 104px，左边一整条黑带。
        //
        // 正确做法：不注册任何 inset 监听器。配合 enterFullScreen() 里的
        //   setDecorFitsSystemWindows(false) + layoutInDisplayCutoutMode=shortEdges，
        //   窗口铺到 [0,0]-[2400,1080]，WebView 视口即为整屏。
        // 代价：挖孔区会被物理遮挡一小块内容 —— 这是全屏游戏的通行取舍。本游戏 UI
        //   自带安全边距（顶栏左右各有留白），实测无关键元素落在挖孔区。

        // ---------- WebView ----------
        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0E141B"));

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        // 音频自动播放的关键：没有这一行，WebView 会拦掉所有「非用户手势」的发声
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);          // 让 <meta viewport> 生效，移动端页面按设备宽度正确排版
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        if (Build.VERSION.SDK_INT >= 26) s.setSafeBrowsingEnabled(false);

        // ---------- 性能/流畅度（全部在启动器侧，不碰游戏逻辑）----------
        //
        // 1) 硬件层：WebView 自己合成到独立硬件层，省掉每帧和 Activity 背景的重复混合。
        //    横屏 + 棋盘是 <canvas> 的场景收益最明显（canvas 每帧都在重绘）。
        web.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        // 2) 离屏光栅化阈值：滚动画布时提前把稍远处的内容也栅格化，减少滚动时的白块/掉帧。
        //    （API 23+ 才有；本项目 minSdk 30，恒可用。）
        if (Build.VERSION.SDK_INT >= 23) {
            try { s.setOffscreenPreRaster(true); } catch (Throwable ignored) { }
        }

        // 3) 关闭 Safe Browsing 已做（上面），它每次加载都要做一次后台校验，纯拖慢启动。

        // 4) 不让 WebView 弹「选择应用」之类的窗口，也避免多一帧的窗口动画开销。
        s.setSupportMultipleWindows(false);

        // 5) 文本缩放锁定：系统字体大小变化会让游戏 UI 错位，
        //    同时每次变化都触发一次完整重排。锁 100% = 布局稳定 + 少一次重排。
        s.setTextZoom(100);
        // chrome://inspect 远程调试 —— **只在 debug 包开**。
        // release 包开着等于把「注入脚本 + 原生桥」暴露给任何能连上 devtools 的人，
        // 而且用户手机上也没这个必要。
        //
        // 判据用 ApplicationInfo.FLAG_DEBUGGABLE 而不是 BuildConfig.DEBUG：
        // AGP 8.x 默认不生成 BuildConfig 类（要 buildFeatures { buildConfig true } 才生成），
        // 不改构建配置去迁就一行代码。FLAG_DEBUGGABLE 是系统按 APK 实际签名/属性打的标，
        // debug 包必然带、release 包必然不带，语义完全等价且更可靠。
        boolean debuggable = (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        if (debuggable) {
            WebView.setWebContentsDebuggingEnabled(true);
        }
        web.addJavascriptInterface(bridge, "Launcher"); // 游戏内可调 Launcher.go()/home() 导航 WebView

        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView v, String url, android.graphics.Bitmap favicon) {
                // 只在「非本机的游戏服务器」上显示「返回本地」浮球。
                // ⚠️ 以前判据是 !url.startsWith(BASE) —— 太宽了：任何一次错误跳转（比如
                //    加载失败的 about:blank、被打断的重定向）都会把浮球亮出来，
                //    而且用户报的「进联机就被弹回主页」有一部分就是浮球被误触发。
                //    改成：只有在【别的机器的 3000 端口游戏页】上才显示。
                boolean remote = url != null && !isBaseUrl(url) && isGameServerUrl(url);
                // 菜单常驻顶部；这里只控制「返回本地」项：远程连接时才可点，本地时灰显。
                if (menuReturn != null) {
                    menuReturn.setEnabled(remote);
                    menuReturn.setAlpha(remote ? 1f : 0.4f);
                }
                Log.i(TAG, "onPageStarted: " + url + "  remote=" + remote);
            }

            /**
             * 启动器注入点 + **本地资源代理**。
             *
             * 两件事：
             * A) 主文档（本机服务器的 HTML）→ 插入注入脚本。
             * B)【方案二】联机页面的纯资源请求 → 改从本机 127.0.0.1 取。
             *
             * ── B 的原理 ──
             * 联机时 WebView 连的是主机 http://&lt;hostIP&gt;:3000，于是页面发出的
             * `/assets/**`、`/data/assets.json` 等资源请求全都要从主机拉（656MB / 12570 个文件）。
             * 但这些美术素材**本机有一份完全一样的**（游戏自己也带 local-assets 本地素材机制）。
             * 把「纯资源」这几类路径改成本机取：
             *   · 房间状态 / 联网逻辑（/api、/socket、/data/*.json 里的对局数据）→ **仍然走主机**
             *   · 美术素材（/assets/**、/fonts/**、静态的 /data/assets.json、/data/local-assets.json）
             *     → **改走本机**，省掉主机的带宽和等待
             * 这样「自己有的东西就调自己的，联机相关再和主机交互」。
             *
             * 安全设计：任何一步失败都 return null（放行走原始请求），绝不会让资源加载不出来。
             */
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest req) {
                try {
                    if (req == null) return null;
                    String url = req.getUrl() == null ? null : req.getUrl().toString();
                    if (url == null) return null;

                    // ── B) 联机页面：把纯资源请求改回本机 ──
                    String localUrl = toLocalResourceUrl(url);
                    if (localUrl != null) {
                        WebResourceResponse r = proxyFromLocal(localUrl, req);
                        if (r != null) return r;   // 拿不到就落到下面走原请求
                    }

                    // ── A) 注入：游戏服务器的 HTML 文档 ──
                    // ⚠️ 以前这里写的是「只拦本机 BASE」，结果是**进远程房（<主机IP>:3000）时
                    //    整个注入脚本都不加载** —— 早期段不跑（代号框空白、要重输名字）、
                    //    UI 段不跑（用户按钮/联机面板/战绩标签全没有）。
                    //    这是用户报的「进联机还要重新输名字」的根因。
                    //    改成：任何主机、只要**是游戏服务器**（端口 3000）的主文档就注入。
                    if (!req.isForMainFrame()) return null;
                    if (!isGameServerDoc(req, url)) return null;

                    String injected = fetchAndInject(url);
                    if (injected == null) return null;
                    Map<String, String> respHeaders = new HashMap<>();
                    respHeaders.put("Cache-Control", "no-store");
                    return new WebResourceResponse("text/html", "UTF-8",
                            new ByteArrayInputStream(injected.getBytes(StandardCharsets.UTF_8)));
                } catch (Throwable t) {
                    Log.w(TAG, "shouldInterceptRequest injection skipped", t);
                    return null;   // 注入失败就退化成原始请求，绝不让页面打不开
                }
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                Log.i(TAG, "page finished: " + url);
                pageShown = true;
                ui.post(MainActivity.this::hideSplash);
                // 兜底：万一 shouldInterceptRequest 那条路没生效（某些 WebView 版本对
                // 主文档的拦截时机会变），再用 evaluateJavascript 补一次。
                //
                // ⚠️ 这里**只补 UI 段**，不能补早期段：
                //    早期段必须在游戏模块读 localStorage **之前**执行，而 onPageFinished
                //    时游戏早跑完了 —— 补早期段不但没用，还会把已经铺好的数据又覆盖一遍。
                //    早期段只能靠 shouldInterceptRequest 在文档响应阶段插进 <head>。
                // ⚠️ 而且必须**按标记切分**：整段 inject.js 里有 `return` 语句
                //    （早期段的 IIFE 之外还有顶层 return），evaluateJavascript 会报
                //    "Illegal return statement" —— 之前日志里那两条语法错误就是这个。
                if (isGameServerUrl(url)) {
                    String uiJs = loadInjectUiJs();
                    if (uiJs != null && !uiJs.isEmpty()) v.evaluateJavascript(uiJs, null);
                }

                // 远程加入成功 → 弹「成功进入联机」（只在由 go(远程地址) 触发的那次加载完成时）
                if (isBaseUrl(url)) {
                    pendingRemoteJoin = false;            // 回到本机，不算远程加入
                } else if (pendingRemoteJoin && isGameServerUrl(url)) {
                    pendingRemoteJoin = false;
                    showToast("成功进入联机");
                }
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest req, WebResourceError err) {
                if (req != null && req.isForMainFrame()) {
                    Log.e(TAG, "main frame error: " + err.getDescription());
                    ui.post(() -> setStatus("页面加载失败：" + err.getDescription(), ""));
                }
            }

            /**
             * 证书校验 —— 只对「自己的游戏服务器」放行，别的站点照常拦。
             *
             * ── 为什么必须放行 ──
             * 樱花内网穿透的「自动 HTTPS」是 frpc 用**它自己签发的证书**在隧道端口上做 TLS，
             * 脱壳后再用明文 HTTP 转发给本机 3000。这张证书不在安卓系统根证书列表里，
             * WebView 默认直接拒绝 → 表现为 `net_error -202 (ERR_CERT_AUTHORITY_INVALID)`，
             * 页面**静默加载失败**（连 onPageStarted 都不触发），用户看到的就是"点了没反应"。
             * curl/浏览器会提示"继续访问"，但 WebView 没有这个 UI，必须由宿主自己决定。
             *
             * ── 安全边界（重要，别放宽）──
             * 放行判据是 isOwnServerUrl()：
             *   ① 本机 127.0.0.1 / localhost（PORT 端口）；
             *   ② 任何主机但端口 == PORT（3000，局域网直连主机的情况）；
             *   ③ **用户当前填在「外部连接」里的那个穿透地址**（端口是樱花分配的任意端口，
             *      例如 frp-gap.com:64755 —— 这是用户自己指定的、他要连的那台服务器）。
             * 其它任何 https 站点（含外链、第三方页面）一律拒绝 ——
             * 不把 App 变成"无视证书"的任意代理。
             */
            @Override
            public void onReceivedSslError(WebView v, android.webkit.SslErrorHandler handler,
                                           android.net.http.SslError error) {
                String url = error == null ? null : error.getUrl();
                if (isOwnServerUrl(url)) {
                    Log.w(TAG, "SSL 放行（自己的游戏服务器）：" + url
                            + "  primaryError=" + (error == null ? "?" : error.getPrimaryError()));
                    handler.proceed();
                } else {
                    Log.e(TAG, "SSL 拒绝（非游戏服务器）：" + url);
                    handler.cancel();
                }
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage m) {
                Log.i(TAG, "web: " + m.message() + "  @" + m.sourceId() + ":" + m.lineNumber());
                return true;
            }
        });

        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ---------- 顶部矩形小箭头折叠菜单（常驻顶部居中 · 游戏 HUD 风）----------
        // 折叠态：只看到紧贴顶部的一个小矩形箭头（▾）。点一下坠出半透明菜单面板，
        // 里面放常用功能（返回本地 / 检查更新 / 复制联机地址 / 刷新 / 联机方式 / 关于）。
        menuArrow = new TextView(this);
        menuArrow.setText("▾");                                      // 折叠态向下箭头
        menuArrow.setTextColor(Color.parseColor("#59F4CA"));         // --mint-400
        menuArrow.setTypeface(Typeface.DEFAULT_BOLD);
        menuArrow.setTextSize(16f);
        menuArrow.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable arrowBg = new android.graphics.drawable.GradientDrawable();
        arrowBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        arrowBg.setColor(Color.parseColor("#FF101412"));             // 完全不透明，无论点前点后都清晰
        arrowBg.setStroke(dp(2), Color.parseColor("#B34ED8AF"));     // 薄荷描边
        arrowBg.setCornerRadius(dp(6));
        menuArrow.setBackground(arrowBg);
        FrameLayout.LayoutParams arrowLp = new FrameLayout.LayoutParams(dp(40), dp(32));
        arrowLp.gravity = Gravity.TOP | Gravity.START;               // 改 START：靠 leftMargin 定位，可左右拖动
        arrowLp.topMargin = 0;                                        // 紧贴顶部
        int arrowX = getSharedPreferences(PREFS, MODE_PRIVATE).getInt("arrowX", -1);
        int screenW0 = getResources().getDisplayMetrics().widthPixels;
        arrowLp.leftMargin = arrowX >= 0 ? arrowX : Math.max(0, (screenW0 - dp(40)) / 2);  // 默认居中
        menuArrow.setLayoutParams(arrowLp);
        menuArrow.setOnClickListener(v -> {
            if (!arrowDragging) toggleMenu();                        // 拖动后不触发展开
            arrowDragging = false;
        });
        // 水平拖动：避开游戏顶部中央的遮挡物（如自带徽标）；上下不动，始终贴顶
        final int arrowSlop = dp(6);
        menuArrow.setOnTouchListener(new View.OnTouchListener() {
            float downX; int startL;
            @Override
            public boolean onTouch(View v, android.view.MotionEvent e) {
                int w = getResources().getDisplayMetrics().widthPixels;
                int maxL = Math.max(0, w - dp(40));
                switch (e.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        startL = arrowLp.leftMargin;
                        arrowDragging = false;
                        return false;                                // 放行，便于 onClick 判定点击
                    case android.view.MotionEvent.ACTION_MOVE: {
                        if (!arrowDragging && Math.abs(e.getRawX() - downX) > arrowSlop) arrowDragging = true;
                        if (arrowDragging) {
                            int nl = (int) Math.min(maxL, Math.max(0, startL + (e.getRawX() - downX)));
                            arrowLp.leftMargin = nl;
                            v.setLayoutParams(arrowLp);
                        }
                        return arrowDragging;                        // 拖动时消费，避免误触
                    }
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL:
                        if (arrowDragging) {
                            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                                    .putInt("arrowX", arrowLp.leftMargin).apply();
                            return true;                             // 消费，阻止误触展开
                        }
                        return false;
                    default:
                        return false;
                }
            }
        });
        root.addView(menuArrow, arrowLp);

        // 折叠菜单面板（默认隐藏，点箭头展开）
        menuPanel = new LinearLayout(this);
        menuPanel.setOrientation(LinearLayout.VERTICAL);
        menuPanel.setPadding(dp(6), dp(6), dp(6), dp(6));
        android.graphics.drawable.GradientDrawable panelBg = new android.graphics.drawable.GradientDrawable();
        panelBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        panelBg.setColor(Color.parseColor("#F2101412"));              // 高不透明玻璃（展开后看得清，不再半透明）
        panelBg.setStroke(dp(2), Color.parseColor("#B34ED8AF"));
        panelBg.setCornerRadius(dp(10));
        menuPanel.setBackground(panelBg);
        menuPanel.setVisibility(View.GONE);
        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(dp(260), ViewGroup.LayoutParams.WRAP_CONTENT);
        panelLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        panelLp.topMargin = dp(34);                                   // 紧贴在箭头下方
        menuPanel.setLayoutParams(panelLp);
        root.addView(menuPanel, panelLp);

        // 6 个菜单项（单行紧凑式：横屏可视高度仅约 392dp，两行式会超出屏幕被裁掉；
        // addMenuItem 会在项之间自动加分隔线；keepOpen=true 的项点完不收起菜单）
        menuReturn   = addMenuItem(menuPanel, "返回本地", this::goHomeAndDisconnect);
        addMenuItem(menuPanel, "检查更新", this::doCheckUpdate);
        addMenuItem(menuPanel, "复制联机地址", this::copyLanAddress);
        addMenuItem(menuPanel, "刷新页面", () -> { if (web != null) web.reload(); });
        menuNetLabel = addMenuItem(menuPanel, "联机方式：局域网", this::toggleNetMode, true); // 切换不收起
        // 外部连接才出现的穿透网址输入框（紧跟「联机方式」，默认隐藏；切到外部连接时显示）
        menuWanRow = new LinearLayout(this);
        menuWanRow.setOrientation(LinearLayout.HORIZONTAL);
        menuWanRow.setGravity(Gravity.CENTER_VERTICAL);
        menuWanRow.setPadding(dp(10), dp(6), dp(10), dp(6));
        menuWanRow.setVisibility("wan".equals(bridge.getNet()) ? View.VISIBLE : View.GONE);
        menuWanInput = new EditText(this);
        menuWanInput.setHint("粘贴内网穿透网址");
        menuWanInput.setHintTextColor(Color.parseColor("#6E8A82"));
        menuWanInput.setTextColor(Color.parseColor("#CFEFE3"));
        menuWanInput.setTextSize(12f);
        menuWanInput.setSingleLine(true);
        menuWanInput.setBackground(null);
        menuWanInput.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        menuWanJoin = new TextView(this);
        menuWanJoin.setText("加入");
        menuWanJoin.setTextColor(Color.parseColor("#59F4CA"));
        menuWanJoin.setTypeface(Typeface.DEFAULT_BOLD);
        menuWanJoin.setTextSize(13f);
        menuWanJoin.setPadding(dp(12), dp(6), dp(12), dp(6));
        menuWanJoin.setOnClickListener(v -> wanJoin());
        menuWanRow.addView(menuWanInput);
        menuWanRow.addView(menuWanJoin);
        menuPanel.addView(menuWanRow);
        addMenuItem(menuPanel, "关于", this::showAbout);

        // ---------- 启动画面（游戏美术风格：近黑墨绿灰底 + 薄荷辉光 + 网格 + 扫描线 + 四角括号框 + 旋转六边形）----------
        // 全部用代码绘制，不依赖任何图片资源，分辨率自适应；配色取自游戏 theme.css（--bg-0 / --mint-* / --gold）。
        splash = new FrameLayout(this);
        splash.setBackgroundColor(Color.parseColor("#0C0F0E"));   // --bg-0

        // ① 背景层：等效游戏 .app-bg（竖直渐变 + 底部薄荷辉光 + 中心绿辉光 + 网格 + 暗角 + 扫描线）
        View splashBg = new View(this);
        splashBg.setBackground(new SplashBackground());
        splash.addView(splashBg, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ② 内容层：居中竖排
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(dp(40), dp(56), dp(40), dp(56));

        // 旋转六边形（对应游戏 .boot__hex 的 boot-spin）
        splashHex = new HexView(this);
        LinearLayout.LayoutParams hexLp = new LinearLayout.LayoutParams(dp(76), dp(76));
        hexLp.gravity = Gravity.CENTER_HORIZONTAL;
        hexLp.bottomMargin = dp(22);
        content.addView(splashHex, hexLp);

        TextView title = new TextView(this);
        title.setText("卫戍协议：终端");
        title.setTextColor(Color.parseColor("#59F4CA"));      // --mint-400
        title.setTextSize(30f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setLetterSpacing(0.04f);
        title.setGravity(Gravity.CENTER);

        TextView subtitle = new TextView(this);
        subtitle.setText("STRONGHOLD PROTOCOL");
        subtitle.setTextColor(Color.parseColor("#2A9E7F"));     // --mint-700
        subtitle.setTextSize(12f);
        subtitle.setTypeface(Typeface.MONOSPACE);
        subtitle.setLetterSpacing(0.40f);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, 12, 0, 30);

        // 进度条（机械风薄荷条：确定态填充 + 不确定态流光）
        splashBar = new SplashProgress(this);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(dp(360), dp(14));
        barLp.gravity = Gravity.CENTER_HORIZONTAL;
        barLp.bottomMargin = dp(8);

        splashText = new TextView(this);
        splashText.setTextColor(Color.parseColor("#C3CBC7"));   // --text-md
        splashText.setTextSize(15f);
        splashText.setGravity(Gravity.CENTER);
        splashText.setPadding(0, 22, 0, 6);

        splashSub = new TextView(this);
        splashSub.setTextColor(Color.parseColor("#8A948F"));    // --text-lo
        splashSub.setTextSize(12f);
        splashSub.setGravity(Gravity.CENTER);

        TextView verLabel = new TextView(this);
        verLabel.setText("安卓启动器 · v" + launcherVersion());
        verLabel.setTextColor(Color.parseColor("#5D6863"));     // --text-dim
        verLabel.setTextSize(11f);
        verLabel.setTypeface(Typeface.MONOSPACE);
        verLabel.setLetterSpacing(0.10f);

        // 版本号右边的小彩蛋（普通字体，避免等宽 + 字距把颜文字拉散）
        TextView cialloLabel = new TextView(this);
        cialloLabel.setText("Ciallo～ (∠・ω< )⌒★");
        cialloLabel.setTextColor(Color.parseColor("#5D6863"));  // --text-dim
        cialloLabel.setTextSize(11f);
        cialloLabel.setPadding(dp(8), 0, 0, 0);

        LinearLayout verRow = new LinearLayout(this);
        verRow.setOrientation(LinearLayout.HORIZONTAL);
        verRow.setGravity(Gravity.CENTER);
        verRow.setPadding(0, 16, 0, 0);
        verRow.addView(verLabel);
        verRow.addView(cialloLabel);

        content.addView(title);
        content.addView(subtitle);
        content.addView(splashBar, barLp);
        content.addView(splashText);
        content.addView(splashSub);
        content.addView(verRow);

        splash.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        // ③ 四角括号框 + 十字准星（机械 HUD 感，盖在最上层，不挡交互）
        View frame = new View(this);
        frame.setBackground(new SplashFrame());
        splash.addView(frame, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        root.addView(splash, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);
        setStatus("正在启动…", "");
    }

    private void setStatus(final String main, final String sub) {
        splashText.setText(main);
        splashSub.setText(sub);
    }

    private void setProgress(final float f) {
        if (splashBar != null) splashBar.setFraction(f);
    }

    private void hideSplash() {
        if (splashHex != null) splashHex.stopSpin();
        if (splashBar != null) splashBar.stop();
        splash.setVisibility(View.GONE);
    }

    /** 启动页版本号（读 versionName，失败回退 0.1）。 */
    private String launcherVersion() {
        try {
            android.content.pm.PackageInfo pi =
                    getPackageManager().getPackageInfo(getPackageName(), 0);
            if (pi.versionName != null && !pi.versionName.isEmpty()) return pi.versionName;
        } catch (Throwable t) { /* 拿不到就回退 */ }
        return "0.1";
    }

    /** 启动页背景：等效游戏 .app-bg（竖直渐变 + 底部薄荷辉光 + 中心绿辉光 + 网格 + 暗角 + 扫描线）。 */
    private static final class SplashBackground extends Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        @Override public void draw(Canvas c) {
            Rect b = getBounds();
            int w = b.width(), h = b.height();
            if (w <= 0 || h <= 0) return;
            // 1) 基础竖直渐变
            p.setShader(new LinearGradient(0, 0, 0, h,
                    new int[]{0xFF0B0E0D, 0xFF0E1311, 0xFF0A0D0C},
                    new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
            c.drawRect(b.left, b.top, b.right, b.bottom, p);
            // 2) 底部薄荷辉光
            p.setShader(new RadialGradient(w * 0.5f, h * 1.12f, Math.max(w, h) * 0.62f,
                    new int[]{0x1A17F9B7, 0x00000000}, new float[]{0f, 1f}, Shader.TileMode.CLAMP));
            c.drawRect(b.left, b.top, b.right, b.bottom, p);
            // 3) 中心绿辉光
            p.setShader(new RadialGradient(w * 0.5f, h * 0.4f, Math.max(w, h) * 0.5f,
                    new int[]{0x59283C34, 0x00000000}, new float[]{0f, 1f}, Shader.TileMode.CLAMP));
            c.drawRect(b.left, b.top, b.right, b.bottom, p);
            // 4) 网格（薄荷细线，低透明，步长随屏宽自适应）
            p.setShader(null);
            p.setStrokeWidth(1f);
            p.setColor(0x0E4ED8AF);
            int step = Math.max(40, w / 26);
            for (int x = b.left; x <= b.right; x += step) c.drawLine(x, b.top, x, b.bottom, p);
            for (int y = b.top; y <= b.bottom; y += step) c.drawLine(b.left, y, b.right, y, p);
            // 5) 暗角
            p.setShader(new RadialGradient(w * 0.5f, h * 0.5f, Math.max(w, h) * 0.62f,
                    new int[]{0x00000000, 0xA6000000}, new float[]{0.55f, 1f}, Shader.TileMode.CLAMP));
            c.drawRect(b.left, b.top, b.right, b.bottom, p);
            // 6) 扫描线
            p.setShader(null);
            p.setColor(0x06FFFFFF);
            for (int y = b.top; y <= b.bottom; y += 3) c.drawLine(b.left, y, b.right, y, p);
            p.setShader(null);
        }
        @Override public void setAlpha(int a) { p.setAlpha(a); }
        @Override public void setColorFilter(ColorFilter cf) { p.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** 启动页边框：四角 L 形机械括号 + 四边中点十字准星（等效游戏 .brackets / .cross）。 */
    private static final class SplashFrame extends Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int m = 26, len = 30;
        @Override public void draw(Canvas c) {
            Rect b = getBounds();
            int w = b.width(), h = b.height();
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2f);
            p.setColor(0xCC4ED8AF);                       // 薄荷 ~80%
            bracket(c, b.left + m, b.top + m, 1, 1);
            bracket(c, b.right - m, b.top + m, -1, 1);
            bracket(c, b.left + m, b.bottom - m, 1, -1);
            bracket(c, b.right - m, b.bottom - m, -1, -1);
            // 四边中点十字准星
            p.setStrokeWidth(1f);
            p.setColor(0x802A9E7F);
            int cx = w / 2, cy = h / 2;
            cross(c, cx, m);
            cross(c, cx, h - m);
            cross(c, m, cy);
            cross(c, w - m, cy);
        }
        private void bracket(Canvas c, float x, float y, int sx, int sy) {
            c.drawLine(x, y, x + sx * len, y, p);
            c.drawLine(x, y, x, y + sy * len, p);
        }
        private void cross(Canvas c, float x, float y) {
            int s = 6;
            c.drawLine(x - s, y, x + s, y, p);
            c.drawLine(x, y - s, x, y + s, p);
        }
        @Override public void setAlpha(int a) { p.setAlpha(a); }
        @Override public void setColorFilter(ColorFilter cf) { p.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** 旋转六边形（等效游戏 .boot__hex 的 boot-spin）。 */
    private static final class HexView extends View {
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private ObjectAnimator spin;
        public HexView(Context ctx) {
            super(ctx);
            float d = ctx.getResources().getDisplayMetrics().density;
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setColor(0xFF4ED8AF);
            stroke.setStrokeWidth(2f * d);
        }
        public void startSpin() {
            if (spin != null) return;
            spin = ObjectAnimator.ofFloat(this, "rotation", 0f, 360f);
            spin.setDuration(1400);
            spin.setRepeatCount(ValueAnimator.INFINITE);
            spin.setInterpolator(new LinearInterpolator());
            spin.start();
        }
        public void stopSpin() {
            if (spin != null) { spin.cancel(); spin = null; }
        }
        @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); startSpin(); }
        @Override protected void onDetachedFromWindow() { stopSpin(); super.onDetachedFromWindow(); }
        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float cx = w / 2f, cy = h / 2f;
            float r = Math.min(w, h) * 0.46f;            // 外接圆半径（正六边形）
            final double S = Math.sin(Math.toRadians(60)); // √3/2 ≈ 0.866
            // 平顶正六边形（flat-top，六个顶点距中心均为 r），顺时针
            float[] px = {
                    cx + r,            cy,
                    cx + r / 2f,       cy - (float) (r * S),
                    cx - r / 2f,       cy - (float) (r * S),
                    cx - r,            cy,
                    cx - r / 2f,       cy + (float) (r * S),
                    cx + r / 2f,       cy + (float) (r * S)
            };
            Path path = new Path();
            path.moveTo(px[0], px[1]);
            for (int i = 2; i < px.length; i += 2) path.lineTo(px[i], px[i + 1]);
            path.close();
            // 135° 对角流光（透明→薄荷 35%→透明）
            fill.setShader(new LinearGradient(cx - r, cy - r, cx + r, cy + r,
                    new int[]{0x004ED8AF, 0x594ED8AF, 0x004ED8AF},
                    new float[]{0.42f, 0.5f, 0.58f}, Shader.TileMode.CLAMP));
            c.save();
            c.clipPath(path);
            c.drawRect(0, 0, w, h, fill);
            c.restore();
            c.drawPath(path, stroke);
        }
    }

    /** 机械风进度条：确定态（0..1 薄荷填充 + 前缘亮线）/ 不确定态（流光扫过）。 */
    private static final class SplashProgress extends View {
        private float frac = -1f;
        private float sweep = 0f;
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint prog = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final ValueAnimator sweepAnim;
        public SplashProgress(Context ctx) {
            super(ctx);
            float d = ctx.getResources().getDisplayMetrics().density;
            track.setColor(0xFF252C29);
            edge.setColor(0xFF59F4CA);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(2f * d);
            sweepAnim = ValueAnimator.ofFloat(0f, 1f);
            sweepAnim.setDuration(1100);
            sweepAnim.setRepeatCount(ValueAnimator.INFINITE);
            sweepAnim.setInterpolator(new LinearInterpolator());
            sweepAnim.addUpdateListener(a -> { sweep = (Float) a.getAnimatedValue(); invalidate(); });
            sweepAnim.start();
        }
        public void setFraction(float f) { frac = f; invalidate(); }
        public void stop() { if (sweepAnim.isRunning()) sweepAnim.cancel(); }
        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float d = getResources().getDisplayMetrics().density;
            float pad = 2f * d;
            float left = pad, top = pad, right = w - pad, bottom = h - pad;
            float ry = (bottom - top) / 2f;
            track.setStyle(Paint.Style.FILL);
            c.drawRoundRect(left, top, right, bottom, ry, ry, track);
            if (frac >= 0) {
                float fw = left + (right - left) * Math.min(1f, Math.max(0f, frac));
                prog.setStyle(Paint.Style.FILL);
                prog.setShader(new LinearGradient(left, 0, fw, 0,
                        new int[]{0xFF145443, 0xFF4ED8AF}, null, Shader.TileMode.CLAMP));
                c.drawRoundRect(left, top, fw, bottom, ry, ry, prog);
                c.drawLine(fw, top + 1f, fw, bottom - 1f, edge);   // 前缘亮线
            } else {
                float seg = (right - left) * 0.28f;
                float x0 = left + sweep * (right - left + seg) - seg;
                float x1 = x0 + seg;
                prog.setStyle(Paint.Style.FILL);
                prog.setShader(new LinearGradient(x0, 0, x1, 0,
                        new int[]{0x004ED8AF, 0x994ED8AF, 0x004ED8AF},
                        new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
                c.drawRoundRect(left, top, right, bottom, ry, ry, prog);
            }
        }
        @Override protected void onDetachedFromWindow() { stop(); super.onDetachedFromWindow(); }
    }

    // ----------------------------------------------------------------- boot

    private void boot() {
        try {
            // 0) 先把注入脚本读进缓存（放在这里是因为 boot() 本身就跑在后台线程，
            //    且此刻还在等 Node 起来，正好把这段 IO 藏进等待里，不占用户感知的启动时间）
            warmInjectCache();

            // 注：本地文件展开（ProjectInstaller.ensure）已挪到 NodeService.runNode 里执行——
            // 服务在 onCreate 主线程被前台拉起后，由它自己的线程完成展开，再起 Node。
            // 这样避免「在后台线程里启动前台服务」被部分 ROM 拒绝（见 onCreate 注释）。

            // 1) 等服务真正可用（服务线程在展开文件 + 起 Node；下载阶段先经 3001 暴露进度）
            if (!waitReady(DOWNLOAD_TIMEOUT_MS)) {
                ui.post(() -> setStatus("本地服务启动超时",
                        "请查看 node-service.log，或点按屏幕重试"));
                return;
            }

            // 2) 载入游戏
            ui.post(() -> {
                setStatus("正在载入界面…", "");
                setProgress(-1);
                loadUrlSafe(BASE + "/");
            });
        } catch (Throwable t) {
            Log.e(TAG, "boot failed", t);
            ui.post(() -> setStatus("启动失败：" + t.getMessage(), ""));
        }
    }

    /** 外层 Toast（原来只有 LauncherBridge 内部类里有，onBackPressed 用不到）。 */
    private void showToast(final String msg) {
        if (msg == null) return;
        ui.post(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show());
    }

    /**
     * 统一的安全导航入口。
     *
     * ⚠️ 必须判空：调用点几乎都是 ui.post / postDelayed 排到主线程队列里的
     *    （go()、home()、goHomeAndDisconnect 的 120ms 延迟、boot() 的载入），
     *    而 onDestroy 会把 web 置 null。用户「点返回本地 → 立刻划掉应用」
     *    就是典型时序 —— 不判空就是 NPE 崩在主线程，
     *    而且崩在 onDestroy 之后，堆栈完全看不出是哪来的。
     */
    private void loadUrlSafe(String url) {
        try {
            if (web == null) return;
            web.loadUrl(url);
        } catch (Throwable t) {
            Log.w(TAG, "loadUrl failed: " + url, t);
        }
    }

    /**
     * 预热注入脚本缓存。
     *
     * ⚠️ 为什么需要：loadInjectJs() 会**同步读 assets 里的 64KB 文本**，
     *    而它的调用点 shouldInterceptRequest 和 onPageFinished **都跑在主线程**。
     *    原来每个页面首次加载都要在主线程上解压+读一次 assets，
     *    表现为「每次进页面/切页面都卡一下」。
     *    这里在启动流程的后台线程里先读一遍填好 volatile 缓存，
     *    之后主线程的调用全部命中缓存，零 IO。
     *
     * 失败无所谓（缓存为 null，后续懒加载照旧工作），所以整个方法吞掉异常。
     */
    private void warmInjectCache() {
        try {
            loadInjectJs();
            loadInjectCss();
            loadInjectUiJs();
            Log.i(TAG, "inject cache warmed: js=" + (injectJs != null ? injectJs.length() : 0)
                    + " css=" + (injectCss != null ? injectCss.length() : 0)
                    + " ui=" + (injectUiJs != null ? injectUiJs.length() : 0));
        } catch (Throwable t) {
            Log.w(TAG, "warm inject cache failed (will lazy-load later)", t);
        }
    }

    private void startNodeService() {
        try {
            Intent i = new Intent(this, NodeService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
        } catch (Throwable t) {
            Log.e(TAG, "cannot start NodeService", t);
        }
    }

    /**
     * 等服务真正可用：
     *   - 下载阶段：android-main.mjs 在 3001 暴露进度，这里轮询并刷新下载进度条
     *   - 下载完：android-main.mjs 起 3000，/healthz 返回 200 即放行载入 WebView
     * 只有真的返回 200 才算服务可用（比 Windows 版「端口开了」更严格）。
     */
    private boolean waitReady(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        boolean downloading = false;
        while (System.currentTimeMillis() < deadline) {
            if (healthOk()) {
                Log.i(TAG, "server is healthy");
                return true;
            }
            ProgressState p = pollProgress();
            if (p != null) {
                downloading = true;
                final float frac = p.pct / 100f;
                final String sub = p.pct + "%  (" + p.done + "/" + (p.total > 0 ? p.total : "?") + ")";
                ui.post(() -> {
                    setStatus("正在下载游戏素材…", sub);
                    setProgress(frac);
                });
            } else if (!downloading && !NodeService.installing) {
                // 首装下载 / 应用更新期间由 NodeService.installProgress 独占状态栏，
                // 这里不要写「正在启动本地服务…」，否则会和下载进度互相覆盖（抢画面）。
                ui.post(() -> {
                    setStatus("正在启动游戏服务…", "端口 " + PORT);
                    setProgress(-1);
                });
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                return false;
            }
        }
        return false;
    }

    private static final class ProgressState {
        int pct;
        long done;
        long total;
    }

    /** GET 127.0.0.1:3001/progress；不通（未下载 / 已下载完）返回 null。 */
    private ProgressState pollProgress() {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL("http://127.0.0.1:3001/progress").openConnection();
            c.setConnectTimeout(600);
            c.setReadTimeout(1500);
            c.setRequestMethod("GET");
            if (c.getResponseCode() != 200) return null;
            try (java.io.InputStream in = c.getInputStream()) {
                byte[] buf = new byte[512];
                int n = in.read(buf);
                if (n <= 0) return null;
                String s = new String(buf, 0, n, "UTF-8");
                org.json.JSONObject o = new org.json.JSONObject(s);
                ProgressState p = new ProgressState();
                p.pct = o.optInt("pct", 0);
                p.done = o.optLong("done", 0);
                p.total = o.optLong("total", 0);
                return p;
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private boolean healthOk() {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(BASE + "/healthz").openConnection();
            c.setConnectTimeout(800);
            c.setReadTimeout(1500);
            c.setRequestMethod("GET");
            int code = c.getResponseCode();
            if (code == 200) {
                try (java.io.InputStream in = c.getInputStream()) {
                    byte[] buf = new byte[512];
                    int n = in.read(buf);
                    if (n > 0) Log.i(TAG, "healthz: " + new String(buf, 0, n, "UTF-8").trim());
                }
            }
            return code == 200;
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /**
     * 是不是「本机服务器」的 URL —— 用**主机名 + 端口精确比较**，绝不用 startsWith。
     *
     * ⚠️ 为什么不能写 url.startsWith(BASE)：
     *    `http://127.0.0.1:3000@evil.com/` 这种地址里，`@` 前面只是 userinfo，
     *    startsWith("http://127.0.0.1:3000") **也为真** → 被当成本机服务器，
     *    于是 SSL 放行 / 脚本注入 / 本地资源代理三条判据同时被绕过。
     *    换成 host+port 精确比较后，evil.com:80 ≠ 127.0.0.1:3000，直接否掉。
     */
    private boolean isBaseUrl(String url) {
        try {
            if (url == null) return false;
            android.net.Uri u = android.net.Uri.parse(url);
            String host = u.getHost();
            if (host == null) return false;
            int port = u.getPort() > 0 ? u.getPort() : ("https".equals(u.getScheme()) ? 443 : 80);
            return ("127.0.0.1".equals(host) || "localhost".equals(host) || "::1".equals(host))
                    && port == PORT;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 这个 URL 的 **origin** 是不是「我们自己的游戏服务器」。
     *
     * 与 isGameServerUrl 的区别：那个是「要不要当**文档**处理」（额外要求路径是 / 或 .html，
     * 因为要往 HTML 里插脚本）；这个只管「是不是我们的服务器」，
     * **对 /assets/xxx.png 这类带扩展名的资源请求也必须为真** ——
     * 本地资源代理（toLocalResourceUrl）和 SSL 放行都靠它。
     *
     * ⚠️ 曾经把 isGameServerUrl 直接用在资源代理上，结果它里面的
     *    「带扩展名且不是 .html 就否掉」把全部美术素材挡在代理之外，
     *    联机时素材全从主机拉 —— 功能退化但不报错，极难发现。
     */
    private boolean isGameServerOrigin(String url) {
        try {
            if (url == null) return false;
            android.net.Uri u = android.net.Uri.parse(url);
            String scheme = u.getScheme();
            if (!"http".equals(scheme) && !"https".equals(scheme)) return false;
            String host = u.getHost();
            if (host == null || host.isEmpty()) return false;
            // ① 本机服务器
            if (isBaseUrl(url)) return true;
            // ② 端口就是游戏服务器端口
            if (u.getPort() == PORT) return true;
            // ③ 用户填的穿透地址（樱花那边端口是随机的，如 64755）
            return sameAsSavedHost(u, scheme);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * URL 版判据（onPageFinished 拿不到 WebResourceRequest，只有 url 字符串）。
     *
     * = isGameServerOrigin + 「是文档」的路径约束。
     */
    private boolean isGameServerUrl(String url) {
        try {
            if (url == null) return false;
            if (!isGameServerOrigin(url)) return false;
            android.net.Uri u = android.net.Uri.parse(url);
            String path = u.getPath();
            // 只认文档类请求（带扩展名但不是 .html 的走开）
            if (path != null && path.indexOf('.') > 0 && !path.endsWith(".html")) return false;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 该 URL 是否与「用户保存的穿透地址」同源（host + port 完全一致）。
     *
     * 抽出来给三个判据共用（isGameServerUrl / isOwnServerUrl / isGameServerDoc），
     * 免得三处各写一遍、改一处漏两处。
     *
     * 说明：保存的值可能是「frp-gap.com:64755」这种裸地址（用户要求原样存），
     * 所以解析时要按 scheme 补一个默认端口；比较时端口取不到就用协议默认口。
     */
    private boolean sameAsSavedHost(android.net.Uri u, String scheme) {
        String saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString("host", "");
        if (saved == null || saved.isEmpty()) return false;
        String s2 = saved.trim();
        if (!s2.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) s2 = "http://" + s2;  // 裸地址只为取 host/port
        android.net.Uri su = android.net.Uri.parse(s2);
        String sHost = su.getHost();
        if (sHost == null || sHost.isEmpty()) return false;
        if (!sHost.equalsIgnoreCase(u.getHost())) return false;

        // ⚠️ 默认端口必须**按 host 判断**，不能按 scheme：
        //    用户存的是裸域名 `frp-gap.com`（樱花自动 HTTPS 的典型地址，没有端口），
        //    按 scheme 补会得到 http:80，而实际访问走 https:443 → 永远匹配不上。
        //    规则：域名没写端口 → http(80) 与 https(443) 视为同一个服务，对等；
        //          host:port 形式（本地/IP/局域网）→ 按字面端口精确匹配。
        boolean sBareDomain = su.getPort() <= 0 && !sHost.matches("^[0-9.]+$")
                && !"127.0.0.1".equals(sHost) && !"localhost".equalsIgnoreCase(sHost);
        if (sBareDomain) return true;
        int sPort = su.getPort() > 0 ? su.getPort() : ("https".equals(su.getScheme()) ? 443 : 80);
        int uPort = u.getPort() > 0 ? u.getPort() : ("https".equals(scheme) ? 443 : 80);
        return sPort == uPort;
    }

    /**
     * 这个 URL 是不是「我们自己的游戏服务器」—— 用于 SSL 证书放行的**安全边界**。
     *
     * 与 isGameServerUrl 的区别：那个是「要不要注入脚本」的判据（严格限定 3000 端口，
     * 避免把启动器 UI 注到无关站点）；这个是「证书给不给过」的判据，必须**也认穿透地址**
     * —— 因为樱花把隧道开在它分配的任意端口上（如 frp-gap.com:64755），
     * 端口根本不是 3000，但那是用户自己指定的、他要连的那台服务器。
     *
     * 判据（满足任一即放行）：
     *   ① 部署目标就是本机服务器（BASE 同源，含端口）；
     *   ② 端口 == PORT（局域网直连另一台手机/电脑上跑的游戏服务）；
     *   ③ **与用户保存在「外部连接」里的穿透地址同源**（host + port 完全一致）。
     *
     * ⚠️ 判据③必须精确匹配用户填的地址，绝不能"凡是 https 都放行"。
     */
    private boolean isOwnServerUrl(String url) {
        try {
            if (url == null) return false;
            android.net.Uri u = android.net.Uri.parse(url);
            String scheme = u.getScheme();
            if (scheme == null) return false;
            scheme = scheme.toLowerCase(java.util.Locale.ROOT);
            // ⚠️ 必须包含 ws/wss：游戏的联机通道是 wss://<host>:<port>/ws，
            //    如果这里只放 http/https，就会看到「页面放行了、但 WebSocket 被拒」——
            //    用户现象就是「进了房但连不上」。
            boolean httpLike = "http".equals(scheme) || "https".equals(scheme);
            boolean wsLike = "ws".equals(scheme) || "wss".equals(scheme);
            if (!httpLike && !wsLike) return false;
            String host = u.getHost();
            if (host == null || host.isEmpty()) return false;

            // ① 本机服务器（127.0.0.1:3000 / localhost:3000）
            if (isBaseUrl(url)) return true;

            // ② 局域网直连：任何主机，只要端口是游戏服务器的 3000
            if (u.getPort() == PORT) return true;

            // ③ 用户填的穿透地址：host + port 与原样保存的值一致
            //    （ws/wss 走同一分支 —— 网页和 WebSocket 用的是同一个隧道的同一个端口）
            return sameAsSavedHost(u, scheme);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 只取 inject.js 的 **UI 段**（分隔标记之后的部分）。
     * 用于 onPageFinished 的兜底注入 —— 早期段绝不能在这时候补（见那里的注释）。
     *
     * ⚠️ 标记找不到时的行为：**返回空串，绝不回退成整段**。
     *    整段 inject.js 顶层有 `return`，evaluateJavascript 会抛
     *    "Illegal return statement"（这个错历史上真的出现过一整轮）。
     *    宁可漏一次兜底注入，也不能把早期段塞进 onPageFinished。
     *    同时打 ERROR 日志 —— 正常打包一定有标记，没有就是 assets 被改坏了。
     */
    private String loadInjectUiJs() {
        String s = injectUiJs;
        if (s != null) return s;
        synchronized (this) {
            if (injectUiJs == null) {
                String all = loadInjectJs();
                if (all == null) { injectUiJs = ""; return injectUiJs; }
                int at = all.indexOf(XJ_EARLY_SPLIT);
                if (at < 0) {
                    Log.e(TAG, "inject.js 缺少 " + XJ_EARLY_SPLIT + " 标记，UI 段兜底注入已禁用"
                            + "（请检查 assets/inject/inject.js 是否被完整打包）");
                    injectUiJs = "";
                } else {
                    injectUiJs = all.substring(at + XJ_EARLY_SPLIT.length());
                }
            }
            return injectUiJs;
        }
    }

    // ------------------------------------------------------- 顶部折叠菜单
    //
    // 常驻顶部居中的小矩形箭头（▾），点开坠出半透明面板：
    //   · 返回本地 / 检查更新 / 复制联机地址 / 刷新 / 联机方式 / 关于
    // 菜单项的构建与展开/收起见 addMenuItem / toggleMenu；dp() 在下方。

    /** dp → px。 */
    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /**
     * 菜单「返回本地」项 → 断连并回首页（仅远程连接时可点，本地时灰显）。
     * 先用注入脚本主动断开 socket（干净地离房），再让 WebView 回本机首页；
     * 两条都不依赖对方成功 —— 即使 JS 断连失败，导航到本机也会自然断开旧连接。
     */
    private void goHomeAndDisconnect() {
        if (web == null) return;
        try {
            web.evaluateJavascript(
                    "(function(){try{window.__xj&&window.__xj.leaveRemote&&window.__xj.leaveRemote()}catch(e){}})();",
                    null);
        } catch (Throwable ignored) { }
        // 给断连一点时间，然后回首页（回首页本身也会让旧连接失效）
        ui.postDelayed(() -> loadUrlSafe(BASE + "/"), 120);
    }

    // ===================== 顶部折叠菜单：交互与各项动作 =====================

    /** 展开/收起顶部菜单面板。 */
    private void toggleMenu() {
        menuOpen = !menuOpen;
        menuPanel.setVisibility(menuOpen ? View.VISIBLE : View.GONE);
        menuArrow.setText(menuOpen ? "▴" : "▾");   // 展开朝上、折叠朝下
        if (menuOpen) refreshNetLabel();            // 打开时同步联机方式显示
    }

    /** 往菜单面板加一行（单行标题）；keepOpen 项点完不收起菜单。返回该行 TextView 便于后续改字。 */
    private TextView addMenuItem(LinearLayout parent, String title, Runnable action) {
        return addMenuItem(parent, title, action, false);
    }
    private TextView addMenuItem(LinearLayout parent, String title, Runnable action, boolean keepOpen) {
        TextView item = new TextView(this);
        item.setText(title);
        item.setTextColor(Color.parseColor("#CFEFE3"));                  // 亮薄荷，保证可读性
        item.setTextSize(12f);                                           // 字体小一点，紧凑
        item.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        item.setPadding(dp(16), dp(6), dp(16), dp(6));                    // 上下再收窄，不臃肿
        item.setMinHeight(dp(34));                                       // 仍好点，但不占太多
        // 按下高亮（StateListDrawable）
        android.graphics.drawable.StateListDrawable sd = new android.graphics.drawable.StateListDrawable();
        android.graphics.drawable.GradientDrawable normalBg = new android.graphics.drawable.GradientDrawable();
        normalBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        normalBg.setColor(Color.parseColor("#00000000"));
        normalBg.setCornerRadius(dp(6));
        android.graphics.drawable.GradientDrawable pressBg = new android.graphics.drawable.GradientDrawable();
        pressBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        pressBg.setColor(Color.parseColor("#1A4ED8AF"));                 // 薄荷半透明按下态
        pressBg.setCornerRadius(dp(6));
        sd.addState(new int[]{android.R.attr.state_pressed}, pressBg);
        sd.addState(new int[]{}, normalBg);
        item.setBackground(sd);
        item.setOnClickListener(v -> {
            if (item.isEnabled() && action != null) action.run();
            if (!keepOpen) toggleMenu();                                // 点完收起（keepOpen 项不收）
        });
        // 除第一项外，每项上方加一条细分隔线（项与项之间、最后一项之后不加）
        if (parent.getChildCount() > 0) {
            View div = new View(this);
            div.setBackgroundColor(Color.parseColor("#334ED8AF"));
            parent.addView(div, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        }
        parent.addView(item);
        return item;
    }

    /** 打开菜单时刷新「联机方式」项的文字。 */
    private void refreshNetLabel() {
        if (menuNetLabel == null) return;
        String net = bridge.getNet();
        menuNetLabel.setText("联机方式：" + ("wan".equals(net) ? "外部连接" : "局域网"));
        // 外部连接时才显示穿透网址输入框；切回局域网则藏起并清空，避免误用
        if (menuWanRow != null) {
            boolean wan = "wan".equals(net);
            menuWanRow.setVisibility(wan ? View.VISIBLE : View.GONE);
            if (!wan && menuWanInput != null) menuWanInput.setText("");
        }
    }

    /** 菜单里「加入」：外部连接模式下，用穿透网址进入远程房。 */
    private void wanJoin() {
        if (menuWanInput == null) return;
        String h = String.valueOf(menuWanInput.getText()).trim();
        if (h.isEmpty()) { showToast("请先填写穿透网址"); return; }
        bridge.setHost(h);                                  // 持久化（同注入侧 setHost）
        if (web != null) {
            final String js = "window.__xj && window.__xj.openRemote && window.__xj.openRemote('"
                    + h.replace("\\", "\\\\").replace("'", "\\'") + "')";
            web.evaluateJavascript(js, null);
        }
        toggleMenu();                                        // 进入了，收起菜单
    }

    /** 复制本机局域网联机地址到剪贴板。 */
    private void copyLanAddress() {
        String addr = getLanAddress();
        if (addr == null) {
            showToast("没找到局域网地址");
            return;
        }
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("联机地址", addr));
                showToast("已复制：" + addr);
            } else {
                showToast("复制失败");
            }
        } catch (Throwable t) {
            Log.w(TAG, "copyLanAddress failed", t);
            showToast("复制失败");
        }
    }

    /** 取本机局域网 IPv4（跳过回环/虚拟网卡），拼上游戏端口 3000。 */
    private String getLanAddress() {
        try {
            Enumeration<NetworkInterface> nets = NetworkInterface.getNetworkInterfaces();
            while (nets != null && nets.hasMoreElements()) {
                NetworkInterface nif = nets.nextElement();
                if (nif.isLoopback() || !nif.isUp()) continue;
                Enumeration<InetAddress> addrs = nif.getInetAddresses();
                while (addrs != null && addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof java.net.Inet4Address && !a.isLoopbackAddress()) {
                        String h = a.getHostAddress();
                        if (h != null && !h.contains(":")) return "http://" + h + ":3000";
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "getLanAddress failed", t);
        }
        return null;
    }

    /** 切换联机方式（局域网 ⇄ 外部连接），同步注入脚本与显示。 */
    private void toggleNetMode() {
        String cur = bridge.getNet();
        String next = "wan".equals(cur) ? "lan" : "wan";
        bridge.setNet(next);
        if (web != null) {
            final String m = next;
            web.evaluateJavascript(
                    "window.__xj && window.__xj.setMode && window.__xj.setMode('" + m + "')", null);
        }
        refreshNetLabel();
        showToast("联机方式已切换为：" + ("wan".equals(next) ? "外部连接" : "局域网"));
    }

    /** 关于弹窗（复用主题化样式）。 */
    private void showAbout() {
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("关于 · 卫戍协议：终端")
                .setMessage("安卓启动器  v" + launcherVersion() + "\n包名  " + getPackageName()
                        + "\n联机方式  " + ("wan".equals(bridge.getNet()) ? "外部连接" : "局域网"))
                .setPositiveButton("知道了", null)
                .create();
        d.show();
        themeDialog(d);
    }

    /**
     * 这个请求是不是「游戏服务器的 HTML 主文档」——决定要不要注入启动器脚本。
     *
     * 原来只认 127.0.0.1（BASE），导致进远程房时注入整个失效。现在放宽为：
     *   协议 http/https
     *   + 端口是本机服务器端口（3000）← **关键约束**：游戏服务器固定这个端口，
     *     用它区分「游戏页面」和「随便一个网站」，避免把脚本注入到无关站点
     *   + 是文档请求（Accept: text/html，且路径是 / 或 .html）
     *
     * 注意：内网穿透（frp）会把远程游戏映射到**别的端口**（樱花分配的是随机口，如 64755），
     * 所以这里不能只认 3000 —— 否则用户从穿透地址进房时，早期段和 UI 段全都不注入，
     * 表现就是「进了联机页还是要重输名字、用户按钮不见了」。放宽为：
     *   本机 3000  或  用户保存的穿透地址同源。
     */
    private boolean isGameServerDoc(WebResourceRequest req, String url) {
        try {
            if (!isGameServerOrigin(url)) return false;   // 与其它判据同一套 origin 逻辑

            // 文档请求判定
            Map<String, String> h = req.getRequestHeaders();
            String accept = h == null ? null : h.get("Accept");
            if (accept != null && !accept.contains("text/html")) return false;
            android.net.Uri u = android.net.Uri.parse(url);
            String path = u.getPath();
            if (path != null && path.indexOf('.') > 0 && !path.endsWith(".html")) return false;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------- 方案二：本地资源代理
    //
    // 联机时页面在 <hostIP>:3000，于是美术素材也要从主机拉。但这些素材本机有一份一样的，
    // 改从 127.0.0.1:3000 取能省掉主机带宽和等待 —— 「自己有的就调自己的，联机相关再和主机交互」。
    //
    // 只代理**纯静态资源**这几类，联网逻辑一律不碰：
    //   /assets/**              美术素材（图片/音频/spine/字体图标）
    //   /fonts/**               字体
    //   /data/assets.json       素材清单（各版本一致，与房间状态无关）
    //   /data/local-assets.json 本地素材清单（本机自己生成的那份才准）
    //   /media/bgm/**           背景音乐
    // 明确**不代理**：/api/**、/socket、/data/*.json 里其它数据（对局/房间/玩家状态），
    // 以及 HTML 文档本身 —— 这些必须来自主机。

    /** 纯资源路径白名单（前缀）。顺序无关，命中即代理。 */
    private static final String[] LOCAL_RES_PREFIXES = {
            "/assets/", "/fonts/", "/media/bgm/",
    };
    /** 纯资源路径白名单（精确匹配）。 */
    private static final String[] LOCAL_RES_EXACT = {
            "/data/assets.json", "/data/local-assets.json",
    };

    /**
     * 该 URL 是否属于「可改走本机」的纯资源。
     * 是 → 返回本机对应 URL；否 → null（按原样请求）。
     *
     * ⚠️ 只对**我们自己的游戏服务器**做代理。原来没有这一步来源校验：
     *    WebView 里任何一个页面（哪怕是误加载的无关站点）请求 `/assets/**`，
     *    都会被悄悄换成本机 127.0.0.1:3000 的同名文件 —— 轻则资源错配，
     *    重则把本机服务器的文件内容吐给无关页面。
     *    加一道 isGameServerUrl 后，只有游戏页面能享受这个加速。
     */
    private String toLocalResourceUrl(String url) {
        try {
            if (url == null) return null;
            if (!isGameServerOrigin(url)) return null;   // ← 来源校验：只代理游戏服务器的资源
                                                          // （必须用 origin 判据，不能用 isGameServerUrl，
                                                          //  后者会把 /assets/xxx.png 这类资源路径否掉）
            android.net.Uri u = android.net.Uri.parse(url);
            String scheme = u.getScheme();
            if (scheme == null) return null;
            if (!"http".equals(scheme) && !"https".equals(scheme)) return null;

            String host = u.getHost();
            if (host == null || host.isEmpty()) return null;
            // 本来就是本机 → 不用代理
            if ("127.0.0.1".equals(host) || "localhost".equals(host) || "::1".equals(host)) return null;

            String path = u.getPath();
            if (path == null || path.isEmpty()) return null;

            boolean hit = false;
            for (String p : LOCAL_RES_EXACT) {
                if (path.equals(p)) { hit = true; break; }
            }
            if (!hit) {
                for (String p : LOCAL_RES_PREFIXES) {
                    if (path.startsWith(p)) { hit = true; break; }
                }
            }
            if (!hit) return null;

            // 带上原始 query（素材 URL 可能有 ?v= 之类），指回本机
            String q = u.getEncodedQuery();
            return BASE + path + (q == null || q.isEmpty() ? "" : ("?" + q));
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 从本机 127.0.0.1 取资源并原样返回（含 Content-Type）。
     * 任何失败 → null，调用方会放行走原始请求（主机），不会让资源加载不出来。
     */
    private WebResourceResponse proxyFromLocal(String localUrl, WebResourceRequest req) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(localUrl).openConnection();
            c.setConnectTimeout(4000);   // 本机连不上就迅速放弃，别拖慢加载
            c.setReadTimeout(20000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("Accept-Encoding", "identity");
            if (req != null) {
                Map<String, String> h = req.getRequestHeaders();
                if (h != null && h.get("Range") != null) c.setRequestProperty("Range", h.get("Range"));
            }
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) {
                // 非 2xx：把 errorStream 读掉再断开，连接才能回到池子里复用。
                // （成功路径**不能** disconnect —— 响应流要交给 WebView 读。）
                try (java.io.InputStream es = c.getErrorStream()) {
                    if (es != null) { byte[] b = new byte[1024]; while (es.read(b) > 0) { } }
                } catch (Throwable ignored) { }
                c.disconnect();
                return null;
            }

            String ctype = c.getContentType();
            if (ctype == null || ctype.isEmpty()) ctype = guessMime(localUrl);
            String enc = "binary";
            int semi = ctype.indexOf(';');
            String mime = semi >= 0 ? ctype.substring(0, semi).trim() : ctype;
            if (mime.startsWith("text/") || mime.contains("json") || mime.contains("javascript") || mime.contains("xml")) {
                enc = "UTF-8";
            }

            Map<String, String> rh = new HashMap<>();
            String cc = c.getHeaderField("Cache-Control");
            rh.put("Cache-Control", (cc == null || cc.isEmpty()) ? "max-age=86400" : cc);
            String etag = c.getHeaderField("ETag");
            if (etag != null) rh.put("ETag", etag);
            // 告诉页面这份资源其实来自本机（便于 CDP/日志排查；不影响渲染）
            rh.put("X-XJ-Local-Asset", "1");

            int status = code;
            String reason = c.getResponseMessage();
            return new WebResourceResponse(mime, enc, status,
                    reason == null ? "OK" : reason, rh, c.getInputStream());
        } catch (Throwable t) {
            Log.w(TAG, "proxyFromLocal failed: " + localUrl + " (" + t.getMessage() + ")");
            if (c != null) try { c.disconnect(); } catch (Throwable ignored) { }
            return null;
        }
    }

    /** 兜底 MIME（服务器没给 Content-Type 时按扩展名猜）。 */
    private static String guessMime(String url) {
        String p = url == null ? "" : url.toLowerCase();
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return "image/jpeg";
        if (p.endsWith(".webp")) return "image/webp";
        if (p.endsWith(".gif")) return "image/gif";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".json")) return "application/json";
        if (p.endsWith(".js")) return "application/javascript";
        if (p.endsWith(".css")) return "text/css";
        if (p.endsWith(".mp3")) return "audio/mpeg";
        if (p.endsWith(".ogg")) return "audio/ogg";
        if (p.endsWith(".woff2")) return "font/woff2";
        if (p.endsWith(".woff")) return "font/woff";
        if (p.endsWith(".ttf")) return "font/ttf";
        if (p.endsWith(".atlas") || p.endsWith(".skel")) return "application/octet-stream";
        return "application/octet-stream";
    }

    // ------------------------------------------------------- 启动器注入（assets → 页面）

    /** 懒加载 assets/inject/inject.js（读一次就缓存，之后零 IO）。 */
    private String loadInjectJs() {
        String s = injectJs;
        if (s != null) return s;
        synchronized (this) {
            if (injectJs == null) injectJs = readAsset(INJECT_JS_PATH);
            return injectJs;
        }
    }

    private String loadInjectCss() {
        String s = injectCss;
        if (s != null) return s;
        synchronized (this) {
            if (injectCss == null) injectCss = readAsset(INJECT_CSS_PATH);
            return injectCss;
        }
    }

    private String readAsset(String path) {
        try (InputStream in = getAssets().open(path)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(16 * 1024);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            Log.w(TAG, "cannot read asset " + path, t);
            return "";
        }
    }

    /**
     * 【只给「自己的服务器」用】的 SSL 工厂 —— 信任任何证书。
     *
     * ⚠️ 安全性：这个工厂**绝不会**被用到无关站点上。调用点前置了 isOwnServerUrl(url) 判据：
     *    · 本机 127.0.0.1:3000 / localhost:3000
     *    · 端口 == 3000 的局域网直连
     *    · 与用户保存的穿透地址 **host+port 完全一致**（如 frp-gap.com:64755）
     *    换言之，只有「用户在启动器里亲手填的、他要去连的那台游戏服务器」才走这条路。
     *
     * 为什么必须这么干：樱花自动 HTTPS 用的是它自己的证书，不在 Android 信任库里，
     * Java 侧连接会抛 "Trust anchor for certification path not found"。
     */
    private javax.net.ssl.SSLSocketFactory ownServerSslFactory;

    private synchronized javax.net.ssl.SSLSocketFactory buildOwnServerSslFactory() {
        if (ownServerSslFactory != null) return ownServerSslFactory;
        try {
            javax.net.ssl.TrustManager tm = new javax.net.ssl.X509TrustManager() {
                @Override public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {}
                @Override public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {}
                @Override public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                    return new java.security.cert.X509Certificate[0];
                }
            };
            javax.net.ssl.SSLContext ctx = javax.net.ssl.SSLContext.getInstance("TLS");
            ctx.init(null, new javax.net.ssl.TrustManager[]{ tm }, new java.security.SecureRandom());
            ownServerSslFactory = ctx.getSocketFactory();
            return ownServerSslFactory;
        } catch (Exception e) {
            Log.e(TAG, "buildOwnServerSslFactory failed", e);
            return (javax.net.ssl.SSLSocketFactory) javax.net.ssl.SSLSocketFactory.getDefault();
        }
    }

    /**
     * 取回本机服务器的页面 HTML，把启动器的 <style>/<script> 插进去。
     *
     * 注入分两段：
     *   ① 「早期段」（inject.js 里 XJ_EARLY_SPLIT 标记之前）→ 插到 <head> 内，
     *      必须在游戏的模块脚本之前跑，否则游戏已经把 sp.* 读进内存，切档不生效。
     *   ② 「UI 段」（标记之后）→ 插到 </body> 前，此时 body 已存在，可以操作 DOM。
     *
     * 取不到 / 没有 </body> 时返回 null（调用方退化到原始请求）。
     */
    private String fetchAndInject(String url) throws IOException {
        String js = loadInjectJs();
        String css = loadInjectCss();
        if ((js == null || js.isEmpty()) && (css == null || css.isEmpty())) return null;

        HttpURLConnection c = null;
        String html;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            // ⚠️ 关键：这是 **Java 侧的 HttpURLConnection**，走系统信任库，
            //    和 WebView 的 onReceivedSslError 完全是两套东西 ——
            //    樱花自签证书在这里会直接抛 CertificateException，
            //    导致「页面能显示（WebView 放行了）但注入不生效（这里拉不到 HTML）」
            //    的用户现象：进房还是要重输名字、用户按钮不见了。
            //    所以这里必须给 HttpsURLConnection 挂一个**只对自己服务器放行**的信任管理器。
            if (c instanceof javax.net.ssl.HttpsURLConnection && isOwnServerUrl(url)) {
                javax.net.ssl.HttpsURLConnection hc = (javax.net.ssl.HttpsURLConnection) c;
                hc.setSSLSocketFactory(buildOwnServerSslFactory());
                hc.setHostnameVerifier(new javax.net.ssl.HostnameVerifier() {
                    @Override
                    public boolean verify(String hostname, javax.net.ssl.SSLSession session) {
                        return true;   // 只对「自己的服务器」生效，见上面的 isOwnServerUrl 判据
                    }
                });
            }
            c.setConnectTimeout(4000);
            c.setReadTimeout(8000);
            c.setRequestMethod("GET");
            c.setInstanceFollowRedirects(true);
            int code = c.getResponseCode();
            if (code != 200) return null;
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream(64 * 1024);
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                html = new String(bos.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            if (c != null) c.disconnect();
        }

        // ---- 把 inject.js 按标记切成「早期段 / UI 段」 ----
        // 标记缺失 → 早期段留空、UI 段留空，**绝不把整段当早期段插进 <head>**
        // （整段顶层有 return，插进去整个 <script> 语法错误，等于注入全废）
        String earlyJs = "";
        String uiJs = "";
        if (js != null && !js.isEmpty()) {
            int split = js.indexOf(XJ_EARLY_SPLIT);
            if (split >= 0) {
                earlyJs = js.substring(0, split);
                uiJs = js.substring(split + XJ_EARLY_SPLIT.length());
            } else {
                Log.e(TAG, "inject.js 缺少 " + XJ_EARLY_SPLIT + " 标记，本次注入已跳过"
                        + "（请检查 assets/inject/inject.js 是否被完整打包）");
                return null;
            }
        }

        // ---- 早期段：插到 <head> 之后（越早越好） ----
        if (!earlyJs.isEmpty()) {
            StringBuilder headTag = new StringBuilder(earlyJs.length() + 64);
            headTag.append("<script id=\"xj-inject-early\">\n").append(earlyJs).append("\n</script>\n");
            int hi = html.indexOf("<head>");
            if (hi >= 0) {
                int insertAt = hi + "<head>".length();
                html = html.substring(0, insertAt) + headTag + html.substring(insertAt);
            } else {
                // 没有 <head>（极不可能）→ 插到最前面
                html = headTag + html;
            }
        }

        // ---- UI 段 + 样式：插到 </body> 前 ----
        StringBuilder tag = new StringBuilder(64 * 1024);
        if (css != null && !css.isEmpty()) {
            tag.append("<style id=\"xj-inject-css\">\n").append(css).append("\n</style>\n");
        }
        if (!uiJs.isEmpty()) {
            tag.append("<script id=\"xj-inject-js\">\n").append(uiJs).append("\n</script>\n");
        }

        int at = html.lastIndexOf("</body>");
        if (at < 0) at = html.lastIndexOf("</html>");
        if (at < 0) {
            // 没有闭合标签（极不可能）→ 直接追加，浏览器也能解析
            return html + tag;
        }
        return html.substring(0, at) + tag + html.substring(at);
    }

    // -------------------------------------------------------------- 生命周期

    @Override
    public void onBackPressed() {
        // 游戏内页面自己处理返回；这里只兜底不直接退出，避免误触关掉服务
        if (web != null && web.canGoBack()) {
            web.goBack();
            return;
        }

        // —— 二次返回才退出（防手滑，2026-10-12 用户要求）——
        // 原来这里是 moveTaskToBack(true)：按一下返回就退到后台，想彻底退出只能去
        // 多任务界面划掉，而划掉对带前台服务的 App 各厂商行为不一（有的杀进程、
        // 有的只杀 Activity 留下服务），用户没法预期。
        // 现在：第一次返回只提示；2 秒内再按一次才真退出。
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastBackAt < BACK_EXIT_WINDOW_MS) {
            lastBackAt = 0;
            exitApp();
        } else {
            lastBackAt = now;
            showToast("再按一次返回退出");
        }
    }

    /**
     * 干净退出：先让页面断开联机（房里的其他人看到你正常离房，而不是掉线），
     * 再停前台服务、结束进程。
     *
     * ⚠️ 最后必须 killProcess：NodeService 的 nodeThread 是 setDaemon(false) 的，
     *    只 stopService + finish 的话 Node 线程继续跑、进程不退出，
     *    会变成「通知没了但 3000 端口还在监听」的僵尸。实测确认过这一点。
     */
    private void exitApp() {
        try {
            if (web != null) {
                web.evaluateJavascript(
                        "(function(){try{window.__xj&&window.__xj.leaveRemote&&window.__xj.leaveRemote()}catch(e){}})();",
                        null);
            }
        } catch (Throwable ignored) { }
        try { stopService(new Intent(this, NodeService.class)); } catch (Throwable ignored) { }
        try { finish(); } catch (Throwable ignored) { }
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    @Override
    protected void onResume() {
        super.onResume();
        enterFullScreen();
    }

    @Override
    protected void onDestroy() {
        // 先把排队的导航/延迟任务全部撤掉。goHomeAndDisconnect 那个 120ms 的
        // postDelayed 如果不撤，会在 web 置 null 之后才跑（现在有判空不会崩，
        // 但会让一个已销毁的 Activity 白跑一遍逻辑）。
        try { unregisterReceiver(updateProgressReceiver); } catch (Throwable ignored) { }
        ui.removeCallbacksAndMessages(null);
        try {
            if (web != null) {
                web.stopLoading();
                web.loadUrl("about:blank");
                web.destroy();
                web = null;
            }
        } catch (Throwable ignored) { }
        super.onDestroy();
    }

    /**
     * JS↔原生桥（网页内通过 window.Launcher 调用）。
     *   go(url)      : 跳到对方服务器网址（如粘贴的 frp 邀请链接），WebView 直接导航过去加入远程房；
     *   home()       : 回到本机 127.0.0.1:3000 服务器；
     *   setNet/getNet     : 联机方式 lan|wan（持久化）；
     *   setHost/getHost   : 内网穿透网址（持久化）；
     *   toast(msg)        : 原生 Toast，给注入脚本反馈用。
     *   checkUpdate()     : 检查更新，弹原生对话框显示当前版本 + 上游最新版本（GitHub Releases）。
     *
     * 用户账号（UserStore，见该类注释：解决单机/联机两套存储割裂）：
     *   usersJson()                     → {users:[{uid,name,createdAt}], current, max, full}
     *   createUser(name)                → 新 uid，失败 ""
     *   renameUser(uid, name)           → 是否成功（uid 不变）
     *   deleteUser(uid)                 → 是否成功
     *   currentUser() / switchUser(uid) → 当前用户
     *   pullUserData(tag) / pushUserData(tag, json) → 当前用户在某 origin 的快照
     *   markMatchSide(recId, side) / matchSide(recId) / allSidesJson() → 战绩主客标签
     *   isMigrated() / setMigrated()    → 老数据迁移标记
     * 只接受 http/https 跳转，避免任意 scheme。
     */
    private static final String PREFS = "xj_launcher";

    // ======================================================================================
    // 检查更新（GitHub Releases）：原生侧拉取上游最新 tag，弹 AlertDialog 显示
    // 「当前版本 / 上游最新版本」。游戏目录一个字节都不动，全部逻辑在启动器侧。
    // ======================================================================================

    /** 当前版本文案：启动器 versionName + 游戏 stamp（install-stamp.txt）。 */
    private String currentVersionText() {
        String launcher = "0.1";
        try {
            android.content.pm.PackageInfo pi =
                    getPackageManager().getPackageInfo(getPackageName(), 0);
            if (pi.versionName != null && !pi.versionName.isEmpty()) launcher = pi.versionName;
        } catch (Throwable t) { /* 拿不到就回退 1.0 */ }
        return "启动器 " + launcher + " / 游戏 " + readGameStamp();
    }

    /** 读取游戏版本：空壳启动器的已安装版本戳（filesDir/nodejs-project/.launcher-version，内容为上游 tag）。 */
    private String readGameStamp() {
        String v = GameUpdater.installedVersion(this);
        return (v == null || v.isEmpty()) ? "（未安装）" : v;
    }

    private static String readFileFirstLine(File f) {
        if (!f.isFile()) return null;
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new java.io.FileInputStream(f), StandardCharsets.UTF_8))) {
            String line = r.readLine();
            return (line != null && !line.trim().isEmpty()) ? line.trim() : null;
        } catch (Throwable t) { return null; }
    }

    /** 从字符串里抠出版号数字段（如 "game-0.2.4-android5" → "0.2.4"，"v1.2.3" → "1.2.3"）。 */
    private static String versionDigits(String s) {
        if (s == null) return "";
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("\\d+(?:\\.\\d+)*").matcher(s);
        return m.find() ? m.group(0) : "";
    }

    /** 注入脚本点「检查更新」时走这里：弹原生对话框，检查中即显示进度，发现更新后原地显示下载进度。 */
    private void doCheckUpdate() {
        final String cur = currentVersionText();
        showUpdateChecking(cur);
        new Thread(() -> {
            UpdateRepo.Latest r = UpdateRepo.fetchLatest();
            ui.post(() -> presentCheckResult(cur, r));
        }).start();
    }

    /** 结果对话框：检查中→(已最新|发现新版本)；发现新版本点「立即更新」原地走下载进度条。 */
    private void presentCheckResult(String cur, UpdateRepo.Latest r) {
        if (r.err != null) {
            updateDialogState(STATE_UPTODATE,
                    "当前版本：\n" + cur + "\n\n上游最新：未能获取（" + r.err + "）\n（上游：" + UpdateRepo.REPO + "）", 0);
            return;
        }
        if (r.tag == null || r.tag.isEmpty()) {
            updateDialogState(STATE_UPTODATE, "当前版本：\n" + cur + "\n\n上游最新：未能获取", 0);
            return;
        }
        String curD = versionDigits(GameUpdater.installedVersion(this));   // 比对游戏版本，而非启动器版本
        String latD = versionDigits(r.tag);
        boolean has = curD.isEmpty() || (!latD.isEmpty() && !latD.equals(curD));
        String tagLine = (r.name != null && !r.name.isEmpty()) ? r.name : r.tag;
        if (has) {
            updateTag = r.tag;
            updateName = tagLine;
            String notes = (r.notes != null && !r.notes.isEmpty()) ? ("\n\n更新内容：\n" + r.notes) : "";
            updateDialogState(STATE_AVAILABLE,
                    "当前版本：\n" + cur + "\n\n上游最新：\n" + tagLine + notes
                            + "\n\n点「立即更新」本机自动拉取增量包并应用。", 0);
        } else {
            updateDialogState(STATE_UPTODATE,
                    "当前版本：\n" + cur + "\n\n上游最新：\n" + tagLine + "\n\n已是最新。", 0);
        }
    }

    /** 打开「检查中」状态的对话框（不确定进度）。 */
    private void showUpdateChecking(String cur) {
        updateDownloading = false;
        if (updateDialog != null && updateDialog.isShowing()) updateDialog.dismiss();
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("检查更新");
        TextView tv = new TextView(this);
        tv.setText("正在检查更新…\n\n当前版本：\n" + cur);
        tv.setTextColor(Color.parseColor("#C3CBC7"));
        tv.setTextSize(14f);
        ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pb.setIndeterminate(true);
        pb.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)));
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(8));
        box.addView(tv);
        box.addView(pb);
        // 更新日志可能很长 → 放进可滚动容器，弹窗高度受屏幕约束而不是无限撑开
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(box);
        b.setView(sv);
        b.setPositiveButton("立即更新", (d, w) -> onUpdatePositive());
        b.setNegativeButton("稍后", (d, w) -> d.dismiss());
        AlertDialog d = b.show();
        themeDialog(d);
        // ⚠️ AlertDialog 默认「点按钮即 dismiss」——这里覆盖掉，否则点「立即更新」弹窗会直接
        //    关闭，无法「原地」显示下载进度条。正/负按钮都不自动关闭，由各自逻辑控制。
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> onUpdatePositive());
        d.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> d.dismiss());
        d.getButton(AlertDialog.BUTTON_POSITIVE).setVisibility(View.GONE);
        d.getButton(AlertDialog.BUTTON_NEGATIVE).setVisibility(View.GONE);
        updateDialog = d;
        updateMsgTv = tv;
        updateProgressPb = pb;
    }

    /** 切换对话框到某个状态（文本/进度条/按钮）。 */
    private void updateDialogState(int state, String msg, int progress) {
        if (updateDialog == null) return;
        if (updateMsgTv != null) updateMsgTv.setText(msg != null ? msg : "");
        if (updateProgressPb != null) {
            if (state == STATE_CHECKING || state == STATE_DOWNLOADING) {
                updateProgressPb.setVisibility(View.VISIBLE);
                if (state == STATE_CHECKING || progress < 0) {
                    updateProgressPb.setIndeterminate(true);
                } else {
                    updateProgressPb.setIndeterminate(false);
                    updateProgressPb.setMax(100);
                    updateProgressPb.setProgress(progress);
                }
            } else {
                updateProgressPb.setVisibility(View.GONE);
            }
        }
        Button pos = updateDialog.getButton(AlertDialog.BUTTON_POSITIVE);
        Button neg = updateDialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        if (pos != null) {
            if (state == STATE_AVAILABLE) { pos.setVisibility(View.VISIBLE); pos.setText("立即更新"); }
            else pos.setVisibility(View.GONE);
        }
        if (neg != null) {
            if (state == STATE_CHECKING || state == STATE_DOWNLOADING || state == STATE_DONE) {
                neg.setVisibility(View.GONE);
            } else {
                neg.setVisibility(View.VISIBLE);
                neg.setText(state == STATE_AVAILABLE ? "稍后" : "知道了");
            }
        }
    }

    private void onUpdatePositive() {
        if (updateTag != null && !updateTag.isEmpty()) startLauncherUpdate(updateTag, updateName, false);
    }

    /** 启动器托管更新：下载上游「更新包」（增量，约几 MB）到 cache 并记 pending，重启后由 NodeService 应用。
     *  silent=true 时不弹窗、不强制重启，下载完下次启动自动生效（启动静默检查走这条）。 */
    private void startLauncherUpdate(String tag, String name, boolean silent) {
        if (tag == null || tag.isEmpty()) { showToast("缺少版本标识，无法自动更新"); return; }
        if (UpdateService.isDownloading()) {
            showToast("更新正在下载中…");
            if (!silent) { updateDownloading = true; updateDialogState(STATE_DOWNLOADING, "正在下载更新包…", 0); }
            return;
        }
        Intent i = new Intent(this, UpdateService.class);
        i.putExtra("tag", tag);
        i.putExtra("name", name);
        i.putExtra(UpdateService.EXTRA_SILENT, silent);
        try {
            if (silent) showToast("发现新版本，正在后台下载…");
            else { updateDownloading = true; updateDialogState(STATE_DOWNLOADING, "正在下载更新包… 0%", 0); }
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
        } catch (Throwable t) {
            showToast("无法启动更新服务");
        }
    }

    /** 启动静默检查：节流 + 首启（游戏在下载素材）跳过 + 已 pending/下载中则跳过。 */
    private void maybeSilentUpdateCheck() {
        long now = System.currentTimeMillis();
        android.content.SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (now - sp.getLong("lastAutoCheck", 0) < 6L * 3600 * 1000) return;
        sp.edit().putLong("lastAutoCheck", now).apply();
        if (GameUpdater.hasPending(this)) return;
        if (UpdateService.isDownloading()) return;
        new Thread(() -> {
            UpdateRepo.Latest r = UpdateRepo.fetchLatest();
            if (r.err != null || r.tag == null || r.tag.isEmpty()) return;
            String curD = versionDigits(GameUpdater.installedVersion(this));
            String latD = versionDigits(r.tag);
            if (latD.isEmpty() || latD.equals(curD)) return;
            ui.post(() -> startLauncherUpdate(r.tag, r.name, true));
        }).start();
    }


    /** 把原生 AlertDialog 也套上游戏的暗色玻璃 + 薄荷风格（首屏之外的启动器 UI 保持一致）。 */
    private void themeDialog(AlertDialog d) {
        if (d.getWindow() != null) {
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
            bg.setColor(0xF2101412);                                  // 近黑墨绿灰，95% 不透明
            bg.setStroke(dp(2), 0xCC4ED8AF);                      // 薄荷描边
            bg.setCornerRadius(dp(14));
            d.getWindow().setBackgroundDrawable(bg);
        }
        TextView title = d.findViewById(android.R.id.title);
        if (title != null) {
            title.setTextColor(Color.parseColor("#59F4CA"));         // --mint-400
            title.setTypeface(Typeface.DEFAULT_BOLD);
        }
        TextView msg = d.findViewById(android.R.id.message);
        if (msg != null) {
            msg.setTextColor(Color.parseColor("#C3CBC7"));           // --text-md
            msg.setTextSize(14f);
        }
        for (int which : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL}) {
            android.widget.Button btn = d.getButton(which);
            if (btn != null) btn.setTextColor(Color.parseColor("#59F4CA"));
        }
    }

    private final class LauncherBridge {
        @android.webkit.JavascriptInterface
        public void go(String url) {
            if (url == null) return;
            String u = url.trim();
            if (!u.startsWith("http://") && !u.startsWith("https://")) return;
            final String target = u;
            // 标记「远程加入」：地址加入且页面成功加载后，弹「成功进入联机」
            if (!isBaseUrl(target) && isGameServerUrl(target)) pendingRemoteJoin = true;
            ui.post(() -> loadUrlSafe(target));
        }

        @android.webkit.JavascriptInterface
        public void home() {
            ui.post(() -> loadUrlSafe(BASE + "/"));
        }

        @android.webkit.JavascriptInterface
        public void setNet(String v) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("net", v).apply();
        }

        @android.webkit.JavascriptInterface
        public String getNet() {
            return getSharedPreferences(PREFS, MODE_PRIVATE).getString("net", "lan");
        }

        @android.webkit.JavascriptInterface
        public void setHost(String v) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("host", v).apply();
        }

        @android.webkit.JavascriptInterface
        public String getHost() {
            return getSharedPreferences(PREFS, MODE_PRIVATE).getString("host", "");
        }

        @android.webkit.JavascriptInterface
        public void toast(final String msg) {
            if (msg == null) return;
            ui.post(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show());
        }

        /** 检查更新：拉 GitHub Releases 最新 tag，弹原生对话框显示当前/上游版本。 */
        @android.webkit.JavascriptInterface
        public void checkUpdate() {
            ui.post(() -> doCheckUpdate());
        }

        // ---------------- 用户账号（UserStore）----------------
        // 说明：这些方法都在 JS 侧被 try/catch 包着调用，返回空串/否 = 失败，不抛异常穿透桥。

        /** 用户列表摘要：{users:[{uid,name,createdAt}], current:"u_x", max:10, full:bool} */
        @android.webkit.JavascriptInterface
        public String usersJson() {
            try { return UserStore.summaryJson(MainActivity.this); }
            catch (Throwable t) { Log.w(TAG, "usersJson failed", t); return "{}"; }
        }

        /** 建用户，返回新 uid；名字非法或已达上限返回 ""。 */
        @android.webkit.JavascriptInterface
        public String createUser(String name) {
            try { return UserStore.create(MainActivity.this, name); }
            catch (Throwable t) { Log.w(TAG, "createUser failed", t); return ""; }
        }

        /** 改名（uid 不变）。 */
        @android.webkit.JavascriptInterface
        public boolean renameUser(String uid, String name) {
            try { return UserStore.rename(MainActivity.this, uid, name); }
            catch (Throwable t) { Log.w(TAG, "renameUser failed", t); return false; }
        }

        /**
         * 改名并返回**清洗后真正落盘的名字**（"" = 失败）。
         * JS 侧拿它去回写 sp.name / 输入框，避免两边名字不一致。
         */
        @android.webkit.JavascriptInterface
        public String renameUserSync(String uid, String name) {
            try {
                if (!UserStore.rename(MainActivity.this, uid, name)) return "";
                JSONObject u = UserStore.byUid(MainActivity.this, uid);
                return u == null ? "" : u.optString("name", "");
            } catch (Throwable t) { Log.w(TAG, "renameUserSync failed", t); return ""; }
        }

        /** 该用户在指定 tag 下是否有自己的档（无 = 需要继承 local 基线）。 */
        @android.webkit.JavascriptInterface
        public boolean hasOwnData(String uid, String tag) {
            try { return UserStore.hasOwnSnapshot(MainActivity.this, uid, tag); }
            catch (Throwable t) { return false; }
        }

        /** 把 local 档播种到 remote 档（进联机房时调一次）。 */
        @android.webkit.JavascriptInterface
        public boolean seedRemoteData(String tag) {
            try {
                String uid = UserStore.currentUid(MainActivity.this);
                if (uid.isEmpty()) return false;
                return UserStore.seedRemoteFromLocal(MainActivity.this, uid, tag);
            } catch (Throwable t) { Log.w(TAG, "seedRemoteData failed", t); return false; }
        }

        /** 删用户（含其快照）。 */
        @android.webkit.JavascriptInterface
        public boolean deleteUser(String uid) {
            try { return UserStore.delete(MainActivity.this, uid); }
            catch (Throwable t) { Log.w(TAG, "deleteUser failed", t); return false; }
        }

        /** 当前用户 uid（无则 ""）。 */
        @android.webkit.JavascriptInterface
        public String currentUser() {
            try { return UserStore.currentUid(MainActivity.this); }
            catch (Throwable t) { return ""; }
        }

        /** 切换当前用户。 */
        @android.webkit.JavascriptInterface
        public boolean switchUser(String uid) {
            try {
                if (UserStore.byUid(MainActivity.this, uid) == null) return false;
                UserStore.setCurrentUid(MainActivity.this, uid);
                return true;
            } catch (Throwable t) { Log.w(TAG, "switchUser failed", t); return false; }
        }

        /**
         * 取当前用户在某 origin 的数据快照（tag: "local" 或 "remote:<host>"）。
         * 远程 tag 下若自己没有档，会自动继承 local（单机）档当基线 —— 见 UserStore.pullMerged。
         */
        @android.webkit.JavascriptInterface
        public String pullUserData(String tag) {
            try {
                String uid = UserStore.currentUid(MainActivity.this);
                if (uid.isEmpty()) return "{}";
                return UserStore.pullMerged(MainActivity.this, uid, tag);
            } catch (Throwable t) { Log.w(TAG, "pullUserData failed", t); return "{}"; }
        }

        /** 存当前用户在某 origin 的数据快照。 */
        @android.webkit.JavascriptInterface
        public boolean pushUserData(String tag, String json) {
            try {
                String uid = UserStore.currentUid(MainActivity.this);
                if (uid.isEmpty()) return false;
                return UserStore.push(MainActivity.this, uid, tag, json);
            } catch (Throwable t) { Log.w(TAG, "pushUserData failed", t); return false; }
        }

        /** 标记某局的主/客（战绩标签用）。 */
        @android.webkit.JavascriptInterface
        public void markMatchSide(String recId, String side) {
            try { UserStore.markSide(MainActivity.this, recId, side); }
            catch (Throwable t) { Log.w(TAG, "markMatchSide failed", t); }
        }

        /** 查某局的主/客："host" | "guest" | ""。 */
        @android.webkit.JavascriptInterface
        public String matchSide(String recId) {
            try { return UserStore.side(MainActivity.this, recId); }
            catch (Throwable t) { return ""; }
        }

        /** 全部主客映射（一次取完，避免逐行跨桥）。 */
        @android.webkit.JavascriptInterface
        public String allSidesJson() {
            try { return UserStore.allSidesJson(MainActivity.this); }
            catch (Throwable t) { return "{}"; }
        }

        /** 老数据迁移是否已做（决定 5：只保留 Master）。 */
        @android.webkit.JavascriptInterface
        public boolean isMigrated() {
            try { return UserStore.migrated(MainActivity.this); }
            catch (Throwable t) { return true; }
        }

        @android.webkit.JavascriptInterface
        public void setMigrated() {
            try { UserStore.setMigrated(MainActivity.this); }
            catch (Throwable t) { Log.w(TAG, "setMigrated failed", t); }
        }
    }
}
