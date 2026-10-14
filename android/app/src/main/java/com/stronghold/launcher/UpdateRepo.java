package com.stronghold.launcher;

import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 上游仓库（GitHub Releases）访问层：
 *   · 版本检查：拉 releases.atom（不受 api.github.com 匿名限额影响），解析最新 tag / 名称 / 更新日志
 *   · 三种包的下载地址：完整包 / 精简包 / 更新包
 *   · 多镜像并发测速下载器：同时探测「直连 + 各第三方镜像」的下载速度，选最快的下载，失败自动回退
 */
public final class UpdateRepo {
    private static final String TAG = "SHL-Repo";
    public static final String REPO = "sganggs/Stronghold-Protocol";
    private static final String ATOM = "https://github.com/" + REPO + "/releases.atom";

    /** 第三方 GitHub 加速镜像前缀（与游戏自带下载器同源）；直连在最后兜底。 */
    private static final String[] MIRROR_PREFIXES = {
            "https://gh-proxy.com/",
            "https://ghfast.top/",
            "https://ghproxy.net/",
            "https://gh.monlor.com/",
            "https://slink.ltd/",
            "https://gh.llkk.cc/",
    };

    /** 下载进度回调：got/total 字节；frac<0 表示总量未知。 */
    public interface Progress {
        void onProgress(long got, long total, float frac);
    }

    private UpdateRepo() { }

    public static final class Latest {
        public String tag;      // v0.2.6
        public String name;     // 上游 release 标题（取自 GitHub Releases）
        public String notes;    // 更新日志（已去 HTML，纯文本）
        public String pageUrl;  // 发布页
        public String err;
    }

    /** 拉取上游最新版本信息（网络方法，勿在主线程调用）。 */
    public static Latest fetchLatest() {
        Latest r = new Latest();
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(ATOM).openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setRequestProperty("Accept", "application/atom+xml");
            c.setRequestProperty("User-Agent", "StrongholdLauncher");
            int code = c.getResponseCode();
            if (code == 200) {
                StringBuilder sb = new StringBuilder();
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                }
                String xml = sb.toString();
                int e0 = xml.indexOf("<entry>");
                if (e0 < 0) {
                    r.err = "该仓库还没有发布 Release";
                } else {
                    int e1 = xml.indexOf("</entry>", e0);
                    String entry = e1 > e0 ? xml.substring(e0, e1) : xml.substring(e0);
                    Matcher tm = Pattern.compile("<title>([^<]*)</title>").matcher(entry);
                    if (tm.find()) r.name = tm.group(1);
                    Matcher lm = Pattern.compile("<link[^>]*href=\"([^\"]*)\"").matcher(entry);
                    if (lm.find()) {
                        r.pageUrl = lm.group(1);
                        Matcher t2 = Pattern.compile("releases/tag/([^\"/]+)").matcher(r.pageUrl);
                        if (t2.find()) r.tag = t2.group(1);
                    }
                    Matcher cm = Pattern.compile("<content[^>]*>(.*?)</content>", Pattern.DOTALL).matcher(entry);
                    if (cm.find()) r.notes = htmlToText(unescape(cm.group(1)));
                }
            } else if (code == 404) {
                r.err = "该仓库还没有发布 Release";
            } else {
                r.err = "上游返回 HTTP " + code;
            }
            c.disconnect();
        } catch (Throwable t) {
            r.err = "网络错误：" + t.getMessage();
        }
        return r;
    }

    public static String fullUrl(String tag)   { return fileUrl(tag, ""); }
    public static String liteUrl(String tag)   { return fileUrl(tag, "-lite"); }
    public static String updateUrl(String tag) { return fileUrl(tag, "-update"); }

    private static String fileUrl(String tag, String suffix) {
        return "https://github.com/" + REPO + "/releases/download/" + tag
                + "/Stronghold-Protocol-" + tag + suffix + ".zip";
    }

    // ── 多镜像并发测速下载 ─────────────────────────────────────────────────

    private static final class Cand {
        String url;
        long ms = Long.MAX_VALUE;
        boolean ok = false;
    }

    private static List<String> candidates(String fileUrl) {
        List<String> out = new ArrayList<>();
        for (String p : MIRROR_PREFIXES) out.add(p + fileUrl);
        out.add(fileUrl);   // 直连兜底
        return out;
    }

    /** 并发探测各候选的首段速度，返回按「快到慢」排序的 URL 列表。 */
    private static List<String> ranked(String fileUrl) {
        final int PROBE = 128 * 1024;
        final List<String> cands = candidates(fileUrl);
        ExecutorService pool = Executors.newFixedThreadPool(cands.size());
        List<Future<Cand>> fs = new ArrayList<>();
        for (final String u : cands) {
            fs.add(pool.submit(new Callable<Cand>() {
                @Override
                public Cand call() {
                    Cand c = new Cand();
                    c.url = u;
                    long t0 = System.currentTimeMillis();
                    HttpURLConnection hc = null;
                    try {
                        hc = (HttpURLConnection) new URL(u).openConnection();
                        hc.setConnectTimeout(6000);
                        hc.setReadTimeout(6000);
                        hc.setRequestProperty("Range", "bytes=0-" + (PROBE - 1));
                        hc.setRequestProperty("User-Agent", "StrongholdLauncher");
                        int code = hc.getResponseCode();
                        if (code != 200 && code != 206) return c;
                        long got = 0;
                        boolean zip = false;
                        try (InputStream in = hc.getInputStream()) {
                            byte[] buf = new byte[16 * 1024];
                            int rd;
                            while (got < PROBE && (rd = in.read(buf)) > 0) {
                                if (got == 0 && rd >= 4) zip = isZipMagic(buf, 0);
                                got += rd;
                            }
                        }
                        c.ms = Math.max(1, System.currentTimeMillis() - t0);
                        // 只认真正返回 zip 的镜像（有的镜像对 release 资产返回错误页）
                        c.ok = got > 0 && zip;
                    } catch (Throwable ignored) {
                        // 该镜像不可用
                    } finally {
                        if (hc != null) hc.disconnect();
                    }
                    return c;
                }
            }));
        }
        pool.shutdown();
        List<Cand> good = new ArrayList<>();
        for (Future<Cand> f : fs) {
            try {
                Cand c = f.get(20, TimeUnit.SECONDS);
                if (c != null && c.ok) good.add(c);
            } catch (Throwable ignored) { }
        }
        // 探测数据量相同，耗时越短越快
        Collections.sort(good, (a, b) -> Long.compare(a.ms, b.ms));
        List<String> ordered = new ArrayList<>();
        for (Cand c : good) ordered.add(c.url);
        for (String u : cands) if (!ordered.contains(u)) ordered.add(u);   // 未成功的也列在后面兜底
        return ordered;
    }

    /** 多镜像测速选最快并下载到 out；失败依次回退。返回实际使用的 URL。 */
    public static String downloadFast(String fileUrl, File out, Progress cb) throws IOException {
        List<String> order = ranked(fileUrl);
        Exception last = null;
        for (String url : order) {
            try {
                downloadOne(url, out, cb);
                Log.i(TAG, "downloaded via " + url);
                return url;
            } catch (Exception e) {
                last = e;
                Log.w(TAG, "download failed via " + url + " : " + e.getMessage());
            }
        }
        throw new IOException("所有镜像均失败" + (last != null ? "：" + last.getMessage() : ""));
    }

    private static void downloadOne(String url, File out, Progress cb) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("GET");
        c.setConnectTimeout(15000);
        c.setReadTimeout(0);
        c.setRequestProperty("User-Agent", "StrongholdLauncher");
        int code = c.getResponseCode();
        if (code != 200) { c.disconnect(); throw new IOException("HTTP " + code); }
        long total = c.getContentLength();
        File tmp = new File(out.getParentFile(), out.getName() + ".tmp");
        try (InputStream in = c.getInputStream(); OutputStream os = new FileOutputStream(tmp)) {
            byte[] buf = new byte[64 * 1024];
            long got = 0;
            int rd, tick = 0;
            while ((rd = in.read(buf)) > 0) {
                os.write(buf, 0, rd);
                got += rd;
                if (++tick % 16 == 0 && cb != null) {
                    cb.onProgress(got, total, total > 0 ? (float) got / total : -1f);
                }
            }
        } finally {
            c.disconnect();
        }
        if (out.exists()) out.delete();
        if (!tmp.renameTo(out)) tmp.renameTo(out);
        if (out.length() == 0) throw new IOException("空文件");
        // 校验：必须是有效 zip，且（已知总量时）大小一致 —— 防止把镜像的错误页当成功
        if (!isZip(out)) throw new IOException("不是有效的 zip（可能被镜像返回了错误页）");
        if (total > 0 && out.length() != total) {
            throw new IOException("下载不完整 " + out.length() + "/" + total);
        }
    }

    private static boolean isZipMagic(byte[] b, int off) {
        return b.length >= off + 4 && b[off] == 'P' && b[off + 1] == 'K'
                && b[off + 2] == 3 && b[off + 3] == 4;
    }

    private static boolean isZip(File f) {
        try (InputStream in = new java.io.FileInputStream(f)) {
            byte[] b = new byte[4];
            int n = 0, r;
            while (n < 4 && (r = in.read(b, n, 4 - n)) > 0) n += r;
            return n == 4 && isZipMagic(b, 0);
        } catch (Throwable t) {
            return false;
        }
    }

    // ── 文本处理 ──────────────────────────────────────────────────────────

    static String unescape(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&apos;", "'").replace("&amp;", "&");
    }

    static String htmlToText(String html) {
        String t = html
                .replaceAll("(?is)<br\\s*/?>", "\n")
                .replaceAll("(?is)</p>", "\n")
                .replaceAll("(?is)<li[^>]*>", "\n· ")
                .replaceAll("(?is)<[^>]+>", "");
        return t.replaceAll("\n{3,}", "\n\n").trim();
    }
}
