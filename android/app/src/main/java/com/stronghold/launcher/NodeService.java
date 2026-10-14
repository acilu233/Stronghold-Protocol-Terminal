package com.stronghold.launcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 跑 Node 服务的前台服务。
 *
 * 为什么用前台服务：
 *  - Node 是常驻进程，Activity 退到后台后不能被系统回收，否则 WebSocket 断线、对局丢失
 *  - 前台通知同时也让用户能一键回到游戏
 *
 * 进程模型：libnode 和 WebView 在**同一个进程**里，但各自跑自己的线程。
 * 两边只通过 HTTP / WebSocket（127.0.0.1:3000）通信，没有别的耦合。
 */
public class NodeService extends Service {

    private static final String TAG = "SHL-Node";
    public static final String CHANNEL_ID = "stronghold-node";
    private static final int NOTIF_ID = 0x53504C;

    private Thread nodeThread;
    private PowerManager.WakeLock wakeLock;
    private volatile boolean running = false;

    /**
     * 本地文件展开进度回调：由前台 Activity 在 onCreate 注册，服务线程展开时把进度
     * 回传到启动画面。展开这一步现在在服务的线程里跑（见 runNode），不在 Activity 后台线程，
     * 这样启动前台服务就不会触发「后台启动被拒」。
     */
    public static volatile InstallProgressListener installProgress;
    public interface InstallProgressListener {
        void onProgress(String msg, float frac);
    }

    /**
     * 是否正在「首装下载 / 应用更新」。
     * 用于让启动画面把状态栏交给下载进度：否则 MainActivity 的 waitReady 每 500ms 刷一次
     * 「正在启动本地服务…」，会和下载进度互相覆盖（抢画面）。
     */
    public static volatile boolean installing = false;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIF_ID, buildNotification());
        acquireWakeLock();

        if (!running || nodeThread == null || !nodeThread.isAlive()) {
            running = true;
            // V8 需要大栈：Android 线程默认只有 ~1 MB，Node 启动时会直接爆栈。
            // 16 MB 是 nodejs-mobile 系实践的常用值。
            nodeThread = new Thread(null, this::runNode, "node-main", 16L * 1024 * 1024);
            nodeThread.setDaemon(false);
            nodeThread.start();
        }
        return START_STICKY;
    }

    private void runNode() {
        File root = new File(getFilesDir(), "nodejs-project");

        // 空壳启动器：游戏本体不在 APK 里。
        //  · 首次启动（文件缺失）：下载上游「完整包」（含全部素材）解压落位；
        //  · 之后更新：应用已下载的「更新包」（增量）。
        // 都趁 Node 还没起时做，避免改动正在运行的游戏文件。进度回传前台启动画面。
        final UpdateRepo.Progress pcb = (got, total, frac) -> {
            if (installProgress == null) return;
            String msg = total > 0
                    ? "正在下载游戏 " + (got / 1048576) + "/" + (total / 1048576) + " MB（" + (int) (frac * 100) + "%）"
                    : "正在下载游戏 " + (got / 1048576) + " MB";
            installProgress.onProgress(msg, Math.max(0f, Math.min(0.9f, frac * 0.9f)));
        };
        installing = true;
        try {
            if (!GameUpdater.hasGame(this)) {
                if (installProgress != null) installProgress.onProgress("首次启动：正在获取游戏…", 0.01f);
                GameUpdater.installFirst(this, pcb);
            } else if (GameUpdater.hasPending(this)) {
                if (installProgress != null) installProgress.onProgress("正在应用更新…", 0.9f);
                GameUpdater.applyUpdate(this, pcb);
            }
            // 确保安卓入口 android-main.mjs 在位（从 APK assets 铺，不覆盖游戏文件）
            ProjectInstaller.ensureEntry(this, root, (msg, frac) -> {
                if (installProgress != null) installProgress.onProgress(msg, frac);
            });
        } catch (Throwable t) {
            writeLog("install/update failed:\n" + t);
            Log.e(TAG, "install/update failed", t);
        } finally {
            installing = false;   // 交还给 boot()，之后才显示「正在启动游戏服务…」
        }

        // ② 起 Node（安卓专用编排入口：缺素材先下载、再起游戏服务，见 bootstrap/android-main.mjs）
        String entry = new File(root, "android-main.mjs").getAbsolutePath();

        if (!new File(entry).isFile()) {
            writeLog("FATAL: entry not found: " + entry);
            return;
        }

        // Android 上 HTTP_PROXY 之类的环境变量一般不存在，但真存在就会触发
        // tools/assets/env-proxy.mjs 的「重启自己」逻辑 —— 安卓没有 spawn，会当场死。
        // 在 native 侧统一 unsetenv（见 node_bridge.cpp），这里只做记录。
        String[] args = {"node", "--enable-source-maps", entry};
        writeLog("starting node: " + String.join(" ", args) + "\ncwd=" + root.getAbsolutePath());

        try {
            NodeRunner.startNode(args, root.getAbsolutePath());
            writeLog("node returned normally");
        } catch (Throwable t) {
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            writeLog("node threw:\n" + sw);
            Log.e(TAG, "node crashed", t);
        } finally {
            running = false;
        }
    }

    /** 把 Node 的异常写到 filesDir/node-service.log，方便 adb / 文件管理器取。 */
    private void writeLog(String msg) {
        Log.i(TAG, msg);
        try {
            File f = new File(getFilesDir(), "node-service.log");
            if (f.length() > 256 * 1024) f.delete();
            String stamp = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date());
            try (java.io.FileWriter w = new java.io.FileWriter(f, true)) {
                w.write("[" + stamp + "] " + msg + "\n");
            }
        } catch (Throwable ignored) { }
    }

    /**
     * 取 WakeLock。
     *
     * ⚠️ 原来这里**每次 onStartCommand 都无条件 acquire**：服务被 START_STICKY 重启、
     *    或用户反复从通知栏回来，就会重复 acquire。虽然 setReferenceCounted(false)
     *    让引用计数不涨，但 acquire 一个已持有的锁会直接抛
     *    "WakeLock under-locked" → 整个 wakeLock 句柄作废，后面 release 也救不回来，
     *    表现为「服务活着但 CPU 会被休眠」。
     *    现在先判 isHeld()，只在没持有时取；失败时把 wakeLock 置 null，
     *    避免 onDestroy 拿着一个废句柄调 release 再抛一次。
     */
    private void acquireWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) return;   // 已经持有了，别重复取
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm == null) return;
            PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "stronghold:node");
            wl.setReferenceCounted(false);
            wl.acquire();
            wakeLock = wl;
        } catch (Throwable t) {
            Log.w(TAG, "wakelock unavailable", t);
            wakeLock = null;
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        ch.setSound(null, null);
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) piFlags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, piFlags);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        return b.setContentTitle(getString(R.string.notif_title))
                .setContentText(getString(R.string.notif_text))
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    @Override
    public void onDestroy() {
        running = false;
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Throwable ignored) { }
        wakeLock = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
