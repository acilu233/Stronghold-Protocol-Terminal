package com.stronghold.launcher;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 空壳启动器：APK 里只内置安卓入口 android-main.mjs，游戏本体由 GameUpdater 从上游下载。
 * 这里只负责把入口文件铺到 filesDir/nodejs-project（缺失或长度不符才写，绝不覆盖游戏文件）。
 */
public final class ProjectInstaller {

    private static final String TAG = "SHL-Install";
    private static final String ASSET_ROOT = "nodejs-project";
    private static final String ENTRY = "android-main.mjs";

    public interface Progress {
        void onProgress(String message, float fraction);
    }

    private ProjectInstaller() { }

    /** 确保安卓入口 android-main.mjs 在目标目录里。阻塞方法，请在后台线程调用。 */
    public static void ensureEntry(Context ctx, File targetDir, Progress cb) throws IOException {
        if (!targetDir.isDirectory() && !targetDir.mkdirs()) {
            throw new IOException("cannot create " + targetDir);
        }
        byte[] data;
        try (InputStream in = ctx.getAssets().open(ASSET_ROOT + "/" + ENTRY)) {
            data = readAll(in);
        }
        File out = new File(targetDir, ENTRY);
        // 逐字节比对（长度相同但内容变了也要重写）
        boolean same = false;
        if (out.isFile() && out.length() == data.length) {
            try {
                same = java.util.Arrays.equals(readAll(new java.io.FileInputStream(out)), data);
            } catch (Throwable ignored) {
                same = false;
            }
        }
        if (!same) {
            try (OutputStream os = new FileOutputStream(out)) {
                os.write(data);
            }
            Log.i(TAG, "wrote " + ENTRY + " (" + data.length + " bytes)");
        } else {
            Log.i(TAG, ENTRY + " up to date");
        }
        if (cb != null) cb.onProgress("本地文件就绪", 1f);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(64 * 1024);
        byte[] buf = new byte[16 * 1024];
        int r;
        while ((r = in.read(buf)) > 0) bos.write(buf, 0, r);
        return bos.toByteArray();
    }
}
