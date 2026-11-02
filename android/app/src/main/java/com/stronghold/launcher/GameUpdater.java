package com.stronghold.launcher;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 游戏本体的「下载后落位 / 更新」工具（空壳启动器）。
 *
 * 启动器不再内置游戏：首次启动下载上游「完整包」（含全部素材）解压落位；之后更新下载「更新包」（增量）覆盖。
 * 两者都只是「把上游 zip 解压进 filesDir/nodejs-project（剥掉顶层 Stronghold-Protocol/ 目录）」，
 * 不再有内置包，也就没有降级风险。
 */
public final class GameUpdater {
    static final String PREFS = "xj_launcher";
    private static final String KEY_ZIP = "pending_update_zip";
    private static final String KEY_TAG = "pending_update_tag";
    private static final String KEY_KIND = "pending_update_kind";
    /** 已安装版本戳文件（内容是上游 tag，如 v0.2.6）。 */
    private static final String VERSION = ".launcher-version";

    private GameUpdater() { }

    static File nodejsProject(Context ctx) {
        return new File(ctx.getFilesDir(), "nodejs-project");
    }

    /** 游戏本体是否已就位（代码齐全 + 有版本戳）。 */
    static boolean hasGame(Context ctx) {
        File root = nodejsProject(ctx);
        return new File(root, "package.json").isFile()
                && new File(root, "server/index.js").isFile()
                && installedVersion(ctx) != null;
    }

    /** 已安装版本（上游 tag）；未安装/读不到返回 null。 */
    static String installedVersion(Context ctx) {
        File f = new File(nodejsProject(ctx), VERSION);
        if (!f.isFile()) return null;
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line = r.readLine();
            return (line == null || line.trim().isEmpty()) ? null : line.trim();
        } catch (Throwable t) {
            return null;
        }
    }

    private static void writeVersion(Context ctx, String tag) throws IOException {
        writeFile(new File(nodejsProject(ctx), VERSION), tag == null ? "unknown" : tag);
    }

    // ── 待应用的更新包（增量包，下载完先存 cache，起 Node 前应用）──────────────

    static File pendingZip(Context ctx) {
        String p = prefs(ctx).getString(KEY_ZIP, null);
        if (p == null) return null;
        File f = new File(p);
        return f.isFile() ? f : null;
    }

    static String pendingTag(Context ctx) { return prefs(ctx).getString(KEY_TAG, null); }

    static void setPending(Context ctx, File zip, String tag, String kind) {
        prefs(ctx).edit()
                .putString(KEY_ZIP, zip.getAbsolutePath())
                .putString(KEY_TAG, tag)
                .putString(KEY_KIND, kind)
                .apply();
    }

    static void clearPending(Context ctx) {
        prefs(ctx).edit().remove(KEY_ZIP).remove(KEY_TAG).remove(KEY_KIND).apply();
    }

    static boolean hasPending(Context ctx) { return pendingZip(ctx) != null; }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ── 首装 / 更新 ────────────────────────────────────────────────────────

    /** 首装：拉上游最新 tag 的「完整包」（含全部素材，约 556MB）下载并解压落位。阻塞，后台线程调用。 */
    static void installFirst(Context ctx, UpdateRepo.Progress cb) throws IOException {
        UpdateRepo.Latest latest = UpdateRepo.fetchLatest();
        if (latest.err != null) throw new IOException("无法获取上游版本：" + latest.err);
        if (latest.tag == null || latest.tag.isEmpty()) throw new IOException("上游没有可用版本");
        File zip = new File(ctx.getCacheDir(), "sp-full.zip");
        UpdateRepo.downloadFast(UpdateRepo.fullUrl(latest.tag), zip, cb);
        extractAll(zip, nodejsProject(ctx));
        zip.delete();
        writeVersion(ctx, latest.tag);
    }

    /** 应用已下载的更新包（增量包或完整包，由 UpdateService 选好）。阻塞，起 Node 前调用。 */
    static void applyUpdate(Context ctx, UpdateRepo.Progress cb) throws IOException {
        File zip = pendingZip(ctx);
        if (zip == null) return;
        String tag = pendingTag(ctx);
        extractAll(zip, nodejsProject(ctx));
        zip.delete();
        if (tag != null) writeVersion(ctx, tag);
        clearPending(ctx);
    }

    // ── 增量包适用性判定 ────────────────────────────────────────────────────

    /**
     * 增量包是否适用于「当前已装版本」。
     *
     * 上游的 -update.zip 里带 UPDATE.json（format 1），其中
     * <pre>"from": ["0.3.2"]</pre>
     * 列出它能从哪些版本升上来。**跨版本应用会得到「半新半旧」的安装**
     * （2026-11-02 真机事故：0.2.6 直接吃 0.3.3 的增量包，只覆盖了 21 个文件，
     *  server/ 等仍是旧代码 → Node 起不来，启动器卡在启动页）。
     *
     * 所以：from 含当前版本才允许走增量；读不到 / 解析失败 / 不含 → 一律走完整包。
     */
    static boolean updateAppliesTo(File zip, String installedTag) {
        String cur = normVer(installedTag);
        if (cur == null) return false;
        String json = readZipText(zip, "UPDATE.json");
        if (json == null) return false;
        int k = json.indexOf("\"from\"");
        if (k < 0) return false;
        int lb = json.indexOf('[', k);
        int rb = lb < 0 ? -1 : json.indexOf(']', lb);
        if (lb < 0 || rb < 0) return false;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"([^\"]{1,32})\"").matcher(json.substring(lb + 1, rb));
        while (m.find()) {
            if (cur.equals(normVer(m.group(1)))) return true;
        }
        return false;
    }

    /** 是否为「完整安装」：有官方本地素材（public/assets/local）。精简安装不能吃增量包。 */
    static boolean isFullInstall(Context ctx) {
        File local = new File(nodejsProject(ctx), "public/assets/local");
        String[] list = local.list();
        return local.isDirectory() && list != null && list.length > 0;
    }

    /** 版本号规范化：去掉前导 v / V、去空白。 */
    private static String normVer(String v) {
        if (v == null) return null;
        String s = v.trim();
        if (!s.isEmpty() && (s.charAt(0) == 'v' || s.charAt(0) == 'V')) s = s.substring(1);
        return s.isEmpty() ? null : s;
    }

    /** 从 zip 中读一个文本条目（允许前面带一层目录，如 Stronghold-Protocol/）。 */
    private static String readZipText(File zip, String name) {
        try (ZipFile zf = new ZipFile(zip)) {
            Enumeration<? extends ZipEntry> e = zf.entries();
            while (e.hasMoreElements()) {
                ZipEntry ze = e.nextElement();
                if (ze.isDirectory()) continue;
                String n = ze.getName();
                if (!n.equals(name) && !n.endsWith("/" + name)) continue;
                try (InputStream in = zf.getInputStream(ze)) {
                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                    copy(in, bos);
                    return new String(bos.toByteArray(), StandardCharsets.UTF_8);
                }
            }
        } catch (Throwable ignored) { }
        return null;
    }

    /** 解压 zip 到 target：剥掉公共顶层目录（Stronghold-Protocol/）、覆盖同名、保留既有文件。 */
    static void extractAll(File zip, File target) throws IOException {
        if (!target.isDirectory() && !target.mkdirs()) throw new IOException("cannot create " + target);
        List<String> names = new ArrayList<>();
        try (ZipFile zf = new ZipFile(zip)) {
            Enumeration<? extends ZipEntry> e = zf.entries();
            while (e.hasMoreElements()) names.add(e.nextElement().getName());
        }
        String prefix = commonTopPrefix(names);
        try (ZipFile zf = new ZipFile(zip)) {
            Enumeration<? extends ZipEntry> e = zf.entries();
            while (e.hasMoreElements()) {
                ZipEntry ze = e.nextElement();
                String name = ze.getName();
                if (prefix != null && name.startsWith(prefix)) name = name.substring(prefix.length());
                if (name.isEmpty()) continue;
                File out = new File(target, name);
                if (ze.isDirectory()) { out.mkdirs(); continue; }
                File parent = out.getParentFile();
                if (parent != null) parent.mkdirs();
                try (InputStream in = zf.getInputStream(ze); OutputStream os = new FileOutputStream(out)) {
                    copy(in, os);
                }
            }
        }
    }

    /** 若 zip 内所有条目都以同一个顶层目录开头（如 Stronghold-Protocol/），剥离该前缀。 */
    private static String commonTopPrefix(List<String> names) {
        if (names.isEmpty()) return null;
        String first = names.get(0);
        int slash = first.indexOf('/');
        if (slash < 0) return null;
        String cand = first.substring(0, slash + 1);
        for (String n : names) if (!n.startsWith(cand)) return null;
        return cand;
    }

    private static void writeFile(File f, String c) throws IOException {
        File p = f.getParentFile();
        if (p != null) p.mkdirs();
        try (FileOutputStream os = new FileOutputStream(f)) {
            os.write(c.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[64 * 1024];
        int r;
        while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
    }
}
