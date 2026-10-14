package com.stronghold.launcher;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.io.File;

/**
 * 启动器托管更新的「下载」阶段（前台服务）。
 *
 * 下载上游「更新包」(-update.zip，约几 MB 增量) 到 cache，写 pending 标记，然后重启应用；
 * 真正的「应用」由 NodeService 在下次启动 Node 前完成（GameUpdater.applyUpdate）。
 * 下载走多镜像并发测速（UpdateRepo.downloadFast），选最快镜像。
 */
public class UpdateService extends Service {
    private static final String TAG = "SHL-Update";
    public static final String CHANNEL_ID = "stronghold-update";
    private static final int NOTIF_ID = 0x53504D;

    /** 进度广播：UpdateService → MainActivity 对话框（同一进程内，普通广播即可）。 */
    public static final String ACTION_PROGRESS = "com.stronghold.launcher.UPDATE_PROGRESS";
    public static final String EXTRA_PROGRESS = "progress";   // int，<0 表示未知总量
    public static final String EXTRA_PHASE = "phase";         // downloading|done|error
    public static final String EXTRA_MESSAGE = "message";
    public static final String EXTRA_SILENT = "silent";       // boolean：静默模式不强制重启
    /** 是否正在下载（防止手动与静默重复触发）。 */
    public static volatile boolean downloading = false;

    private NotificationManager nm;

    @Override
    public void onCreate() {
        super.onCreate();
        nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String tag = intent != null ? intent.getStringExtra("tag") : null;
        boolean silent = intent != null && intent.getBooleanExtra(EXTRA_SILENT, false);
        if (tag == null || tag.isEmpty()) { stopSelf(); return START_NOT_STICKY; }
        startForeground(NOTIF_ID, buildNotification("准备更新…"));
        new Thread(() -> runDownload(tag, silent), "shl-update").start();
        return START_NOT_STICKY;
    }

    private void runDownload(String tag, boolean silent) {
        downloading = true;
        File zip = new File(getCacheDir(), "sp-update.zip");
        try {
            sendProgress(0, "downloading", "正在下载更新包… 0%");
            notify("正在下载更新包…");
            UpdateRepo.downloadFast(UpdateRepo.updateUrl(tag), zip, (got, total, frac) -> {
                int pct = total > 0 ? (int) (frac * 100) : -1;
                String msg = total > 0
                        ? "正在下载更新包… " + pct + "%"
                        : "正在下载更新包… " + (got / 1048576) + " MB";
                sendProgress(pct, "downloading", msg);
                notify(msg);
            });
            GameUpdater.setPending(this, zip, tag, "update");
            sendProgress(100, "done", "下载完成");
            if (silent) {
                // 静默模式：不强制重启，pending 在下次启动由 NodeService 应用
                notify("更新已下载，下次启动自动生效");
                try { Thread.sleep(1500); } catch (InterruptedException ignored) { }
                downloading = false;
                stopSelf();
            } else {
                notify("下载完成，正在重启以应用…");
                try { Thread.sleep(400); } catch (InterruptedException ignored) { }
                restartApp();   // 杀掉进程，下次启动应用 pending
            }
        } catch (Throwable t) {
            Log.e(TAG, "download failed", t);
            sendProgress(0, "error", "下载失败：" + t.getMessage());
            notify("下载失败：" + t.getMessage());
            try { Thread.sleep(2500); } catch (InterruptedException ignored) { }
            downloading = false;
            stopSelf();
        }
    }

    private void sendProgress(int pct, String phase, String msg) {
        Intent i = new Intent(ACTION_PROGRESS);
        i.putExtra(EXTRA_PROGRESS, pct);
        i.putExtra(EXTRA_PHASE, phase);
        i.putExtra(EXTRA_MESSAGE, msg);
        sendBroadcast(i);
    }

    public static boolean isDownloading() { return downloading; }

    private void restartApp() {
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 0, i, flags);
        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (am != null) {
            long at = System.currentTimeMillis() + 800;
            boolean set = false;
            // ⚠️ Android 12+ 精确闹钟需要 SCHEDULE_EXACT_ALARM 特殊权限，否则 setExactAndAllowWhileIdle
            //    直接抛 SecurityException（真机踩到）。优先精确；不可用就退回无需权限的 set()。
            if (Build.VERSION.SDK_INT < 31) {
                try { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi); set = true; }
                catch (Throwable ignored) { }
            } else {
                try {
                    if (am.canScheduleExactAlarms()) {
                        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
                        set = true;
                    }
                } catch (Throwable ignored) { }
            }
            if (!set) {
                try { am.set(AlarmManager.RTC_WAKEUP, at, pi); } catch (Throwable ignored) { }
            }
        }
        // 兜底：即使闹钟没按时拉起，前台通知「点此重新打开应用」也能让用户手动回到新版本。
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26 || nm == null) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "卫戍协议：更新", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        ch.setSound(null, null);
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) piFlags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, piFlags);
        Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return b.setContentTitle("卫戍协议：更新")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void notify(String text) {
        if (nm != null) nm.notify(NOTIF_ID, buildNotification(text));
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
