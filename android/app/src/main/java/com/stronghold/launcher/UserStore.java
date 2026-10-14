package com.stronghold.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 本地用户账号目录（launcher 侧，不碰 game/）。
 *
 * 为什么需要它：
 *   游戏的 localStorage 按 origin 隔离 —— 单机是 127.0.0.1:3000，联机是 <主机IP>:3000，
 *   于是变成两套互不相干的数据（实测：名字 Master / 你好，战绩各一份）。
 *   网页 JS 受同源策略限制读不到别的 origin，**只有原生侧能同时持有两边数据**。
 *   所以这里做「用户目录 + 每用户每 origin 的快照」，由注入脚本在两端铺取，实现数据归拢。
 *
 * 存储（SharedPreferences "xj_launcher"）：
 *   xj.users                  JSON 数组 [ {uid, name, createdAt} ]，上限 10 个
 *   xj.user.cur               当前用户 uid
 *   xj.userdata.<uid>.<tag>   该用户在该 origin 的数据快照（JSON 字符串）
 *   xj.matchside.<recId>      该局本机是主还是客（"host" | "guest"）
 *   xj.migrated.v1            老数据迁移是否已做（只保留 Master）
 *
 * 注：uid 一旦生成永不变（改名只动 name），这是用户明确要求的「识别 id 不变」。
 */
final class UserStore {

    private static final String TAG = "SHL-UserStore";

    /** 与 MainActivity 共用同一个 SharedPreferences，避免多份存储。 */
    static final String PREFS = "xj_launcher";

    private static final String K_USERS = "xj.users";
    private static final String K_CUR = "xj.user.cur";
    private static final String K_DATA_PREFIX = "xj.userdata.";
    private static final String K_SIDE_PREFIX = "xj.matchside.";
    private static final String K_MIGRATED = "xj.migrated.v1";

    // 只能进快照、不能跨 origin 搬的键（本 tab 会话令牌，跟 origin 绑定）
    private UserStore() { }

    // ==================================================================================
    //  基础读写
    // ==================================================================================

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 用户上限（用户明确要求：10 个）。 */
    static final int MAX_USERS = 10;

    // ==================================================================================
    //  用户列表
    // ==================================================================================

    /** 读用户列表；损坏或为空返回空数组（绝不抛）。 */
    static List<JSONObject> list(Context ctx) {
        List<JSONObject> out = new ArrayList<>();
        String raw = prefs(ctx).getString(K_USERS, null);
        if (raw == null || raw.isEmpty()) return out;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String uid = o.optString("uid", "");
                if (uid.isEmpty()) continue;
                out.add(o);
            }
        } catch (JSONException e) {
            Log.w(TAG, "xj.users 损坏，按空处理", e);
        }
        return out;
    }

    private static void save(Context ctx, List<JSONObject> users) {
        JSONArray arr = new JSONArray();
        for (JSONObject u : users) arr.put(u);
        prefs(ctx).edit().putString(K_USERS, arr.toString()).apply();
    }

    /** 用户数量是否已达上限。 */
    static boolean isFull(Context ctx) {
        return list(ctx).size() >= MAX_USERS;
    }

    static JSONObject byUid(Context ctx, String uid) {
        if (uid == null || uid.isEmpty()) return null;
        for (JSONObject u : list(ctx)) {
            if (uid.equals(u.optString("uid", ""))) return u;
        }
        return null;
    }

    // ==================================================================================
    //  当前用户
    // ==================================================================================

    static String currentUid(Context ctx) {
        String uid = prefs(ctx).getString(K_CUR, "");
        if (uid == null || uid.isEmpty()) return "";
        // 指向一个已不存在的用户时清掉（用户被删过）
        if (byUid(ctx, uid) == null) {
            prefs(ctx).edit().remove(K_CUR).apply();
            return "";
        }
        return uid;
    }

    static void setCurrentUid(Context ctx, String uid) {
        prefs(ctx).edit().putString(K_CUR, uid == null ? "" : uid).apply();
    }

    // ==================================================================================
    //  增删改
    // ==================================================================================

    /** 新的稳定 uid：u_ + 16 位 hex。改名不会变，这是身份锚点。 */
    private static String newUid(Context ctx) {
        SecureRandom rnd = new SecureRandom();
        for (int tries = 0; tries < 32; tries++) {
            String uid = "u_" + String.format("%016x", new java.math.BigInteger(64, rnd).abs());
            if (byUid(ctx, uid) == null) return uid;
        }
        // 极端兜底（几乎不可能走到）
        return "u_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /**
     * 建用户。返回新 uid；名字非法或已满 10 个返回 ""。
     * 名字允许重复（用户可自辨），但会 trim + 限长。
     */
    static String create(Context ctx, String name) {
        String n = sanitizeName(name);
        if (n.isEmpty()) return "";
        if (isFull(ctx)) return "";
        List<JSONObject> users = list(ctx);
        String uid = newUid(ctx);
        JSONObject u = new JSONObject();
        try {
            u.put("uid", uid);
            u.put("name", n);
            u.put("createdAt", System.currentTimeMillis());
        } catch (JSONException e) {
            return "";
        }
        users.add(u);
        save(ctx, users);
        return uid;
    }

    /**
     * 改名（uid 不变）。返回是否成功。
     *
     * ⚠️ 连带把该用户所有 origin 快照里的 sp.name 一起改掉。
     *    只改目录不改快照的话，重进会被旧快照覆盖回去（用户实测到的「改名又变回来」）。
     */
    static boolean rename(Context ctx, String uid, String name) {
        String n = sanitizeName(name);
        if (n.isEmpty()) return false;
        List<JSONObject> users = list(ctx);
        boolean hit = false;
        for (JSONObject u : users) {
            if (uid.equals(u.optString("uid", ""))) {
                try { u.put("name", n); hit = true; } catch (JSONException ignored) { }
                break;
            }
        }
        if (!hit) return false;
        save(ctx, users);
        renameInSnapshots(ctx, uid, n);   // 三处副本一起对齐
        return true;
    }

    /** 删用户 + 其全部 origin 快照 + 主客映射。返回是否成功。 */
    static boolean delete(Context ctx, String uid) {
        if (uid == null || uid.isEmpty()) return false;
        List<JSONObject> users = list(ctx);
        List<JSONObject> keep = new ArrayList<>();
        boolean hit = false;
        for (JSONObject u : users) {
            if (uid.equals(u.optString("uid", ""))) { hit = true; continue; }
            keep.add(u);
        }
        if (!hit) return false;
        save(ctx, keep);

        // 清掉该用户的所有 origin 快照（键前缀匹配）
        SharedPreferences p = prefs(ctx);
        SharedPreferences.Editor ed = p.edit();
        for (String k : p.getAll().keySet()) {
            if (k.startsWith(K_DATA_PREFIX + uid + ".")) ed.remove(k);
        }
        if (uid.equals(p.getString(K_CUR, ""))) ed.remove(K_CUR);
        ed.apply();
        return true;
    }

    /** 名字清洗：去首尾空白、去控制字符、限 12 字（与游戏 MAX 一致）。 */
    static String sanitizeName(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder();
        String s = raw.trim();
        for (int i = 0; i < s.length() && sb.length() < 12; i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7F) continue;   // 控制字符
            sb.append(c);
        }
        return sb.toString().trim();
    }

    // ==================================================================================
    //  每用户 · 每 origin 的快照
    // ==================================================================================

    /** originTag：单机固定 "local"；联机用 "remote:<主机>"，不同主机各存一份。 */
    private static String dataKey(String uid, String tag) {
        return K_DATA_PREFIX + uid + "." + (tag == null || tag.isEmpty() ? "local" : tag);
    }

    /** 取快照 JSON（无则返回 "{}"）。 */
    static String pull(Context ctx, String uid, String tag) {
        if (uid == null || uid.isEmpty()) return "{}";
        return prefs(ctx).getString(dataKey(uid, tag), "{}");
    }

    /**
     * 取快照，**带「本地基线继承」**。
     *
     * 为什么需要它（用户报的现象：进了远程服务器就读不到本地用户数据）：
     *   localStorage 按 origin 隔离 —— 单机是 127.0.0.1:3000（tag=local），
     *   联机是 <主机IP>:3000（tag=remote:x.x.x.x）。两者在玩家看来是「同一个账号」，
     *   但实际上各自一份快照。联机时如果 remote 档是空的，
     *   早期段就只能把 localStorage 清空 → 玩家看到的是「改名/设置/配装全没了」，
     *   很像「进远程就读不到本地用户」。
     *
     * 规则（合并策略 = 本地优先做基线，remote 档补充）：
     *   - remote 档为空  → 返回 local 档（把单机的档案带进联机房；纯读，不落盘）
     *   - remote 档非空  → 以 remote 档为准（联机期间产生的进度不回灌单机，
     *                      避免两套进度互相打架；单机档保持干净）
     *   - tag 本来就是 local → 直接返回
     *
     * 注意：这里**只读不写**。真正的落盘发生在注入层的 pushUserData，
     * 所以「远程继承」不会污染 local 档。
     */
    static String pullMerged(Context ctx, String uid, String tag) {
        String own = pull(ctx, uid, tag);
        if (tag == null || tag.isEmpty() || "local".equals(tag)) return own;
        if (!isEmptySnapshot(own)) return own;          // remote 档有内容 → 用它
        String base = pull(ctx, uid, "local");          // 否则继承单机基线
        return isEmptySnapshot(base) ? own : base;
    }

    private static boolean isEmptySnapshot(String json) {
        if (json == null) return true;
        String s = json.trim();
        return s.isEmpty() || "{}".equals(s) || "null".equals(s);
    }

    /** 存快照。传入非对象 JSON 时拒绝，避免把脏数据写进去。 */
    static boolean push(Context ctx, String uid, String tag, String json) {
        if (uid == null || uid.isEmpty() || json == null) return false;
        try {
            new JSONObject(json);   // 校验是合法 JSON 对象
        } catch (JSONException e) {
            Log.w(TAG, "push 收到非法 JSON，已拒绝");
            return false;
        }
        prefs(ctx).edit().putString(dataKey(uid, tag), json).apply();
        return true;
    }

    /**
     * 把 local 档「播种」到某个 remote 档（仅当 remote 档为空时）。
     * 注入层进联机房时调一次，让继承来的基线真正落盘 —— 之后联机期间的
     * 改动才有落脚点，也不会和单机档互相覆盖。
     *
     * @return true = 播种成功（或无需播种时 remote 已有内容）
     */
    static boolean seedRemoteFromLocal(Context ctx, String uid, String tag) {
        if (uid == null || uid.isEmpty()) return false;
        if (tag == null || tag.isEmpty() || "local".equals(tag)) return false;
        String own = pull(ctx, uid, tag);
        if (!isEmptySnapshot(own)) return true;         // 已有内容，不覆盖
        String base = pull(ctx, uid, "local");
        if (isEmptySnapshot(base)) return false;        // 单机也没档，无从播种
        return push(ctx, uid, tag, base);
    }

    /** 当前用户在某 tag 下是否真的有档（用于注入层判断要不要继承）。 */
    static boolean hasOwnSnapshot(Context ctx, String uid, String tag) {
        if (uid == null || uid.isEmpty()) return false;
        return !isEmptySnapshot(pull(ctx, uid, tag));
    }

    /**
     * 改名后，把该用户**所有 origin 快照**里的 sp.name 一起改掉。
     *
     * 为什么必须做（用户报的现象：临时改能生效，退出重进又变回原名字）：
     *   名字有三处副本 —— ① 用户目录 name；② sp.name（localStorage，按 origin 各一份）；
     *   ③ 每个 origin 的快照 xj.userdata.&lt;uid&gt;.&lt;tag&gt;。
     *   只改 ① 的话，重进时早期段会用 ③ 里的旧 sp.name 把 localStorage 覆盖回去，
     *   游戏再读 sp.name 就把输入框/联机名恢复成旧名 —— 看起来就是「改名没保存」。
     *   把 ③ 全部对齐，重进才能拿到新名字。
     *
     * @return 被改动的快照个数
     */
    static int renameInSnapshots(Context ctx, String uid, String name) {
        String n = sanitizeName(name);
        if (uid == null || uid.isEmpty() || n.isEmpty()) return 0;
        String prefix = K_DATA_PREFIX + uid + ".";
        SharedPreferences p = prefs(ctx);
        SharedPreferences.Editor ed = p.edit();
        int touched = 0;

        // 先收集键，避免在遍历 getAll 时改 map
        List<String> keys = new ArrayList<>(p.getAll().keySet());
        for (String k : keys) {
            if (!k.startsWith(prefix)) continue;
            String raw = p.getString(k, null);
            if (raw == null || raw.isEmpty()) continue;
            try {
                JSONObject o = new JSONObject(raw);
                if (!o.has("sp.name")) continue;
                if (n.equals(o.optString("sp.name", ""))) continue;
                o.put("sp.name", n);
                ed.putString(k, o.toString());
                touched++;
            } catch (JSONException ignored) {
                // 快照不是合法对象 → 跳过，不影响其它档
            }
        }
        if (touched > 0) ed.apply();
        return touched;
    }

    // ==================================================================================
    //  主客标签（战绩用；游戏 schema 严格，只能外部存映射）
    // ==================================================================================

    static void markSide(Context ctx, String recId, String side) {
        if (recId == null || recId.isEmpty()) return;
        String v = "guest".equals(side) ? "guest" : "host";
        prefs(ctx).edit().putString(K_SIDE_PREFIX + recId, v).apply();
    }

    /** 返回 "host" | "guest" | ""（未知）。 */
    static String side(Context ctx, String recId) {
        if (recId == null || recId.isEmpty()) return "";
        return prefs(ctx).getString(K_SIDE_PREFIX + recId, "");
    }

    /** 一次性取全部映射（注入层渲染战绩列表时一次拿完，避免逐行跨桥调用）。 */
    static String allSidesJson(Context ctx) {
        JSONObject out = new JSONObject();
        SharedPreferences p = prefs(ctx);
        for (String k : p.getAll().keySet()) {
            if (!k.startsWith(K_SIDE_PREFIX)) continue;
            try {
                out.put(k.substring(K_SIDE_PREFIX.length()), p.getString(k, ""));
            } catch (JSONException ignored) { }
        }
        return out.toString();
    }

    // ==================================================================================
    //  老数据迁移标记
    // ==================================================================================

    static boolean migrated(Context ctx) {
        return prefs(ctx).getBoolean(K_MIGRATED, false);
    }

    static void setMigrated(Context ctx) {
        prefs(ctx).edit().putBoolean(K_MIGRATED, true).apply();
    }

    // ==================================================================================
    //  给注入层的 JSON 摘要
    // ==================================================================================

    /** { users: [...], current: "u_xxx", full: bool, max: 10 } */
    static String summaryJson(Context ctx) {
        JSONObject out = new JSONObject();
        try {
            JSONArray arr = new JSONArray();
            for (JSONObject u : list(ctx)) arr.put(u);
            out.put("users", arr);
            out.put("current", currentUid(ctx));
            out.put("max", MAX_USERS);
            out.put("full", isFull(ctx));
        } catch (JSONException ignored) { }
        return out.toString();
    }
}
