package com.miku.snow;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 检查更新：读开源页的 releases，取「发布时间最新」的那个版本里的 APK。
 * 版本命名换过方案（7.14 -> 7.6.35），纯按版本号大小比会把老版本当成新版，所以以发布时间为准。
 */
public class UpdateChecker {

    public static class Result {
        public boolean hasUpdate;
        public boolean testBuild;      // 本地版本比发布版还高
        public String message = "";
        public String url = "";
    }

    public static Result check(String repoWebUrl, String currentVersion) {
        Result r = new Result();
        String owner = "";
        String repo = "";
        try {
            String path = repoWebUrl.replace("https://github.com/", "").replace("http://github.com/", "");
            String[] parts = path.split("/");
            owner = parts[0];
            repo = parts[1];
        } catch (Throwable t) {
            r.message = "开源页地址不正确";
            return r;
        }

        HttpURLConnection conn = null;
        try {
            URL u = new URL("https://api.github.com/repos/" + owner + "/" + repo + "/releases");
            conn = (HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("User-Agent", "Miku-Android");

            int code = conn.getResponseCode();
            if (code == 404) {
                r.message = "开源页还没发布版本";
                return r;
            }
            if (code != 200) {
                r.message = "读取失败（HTTP " + code + "）";
                return r;
            }

            String body = readAll(conn.getInputStream());
            JSONArray arr = new JSONArray(body);

            String best = null;
            String bestPub = "";
            String bestUrl = "";
            String bestName = "";
            for (int i = 0; i < arr.length(); i++) {
                JSONObject rel = arr.optJSONObject(i);
                if (rel == null) continue;
                JSONArray assets = rel.optJSONArray("assets");
                if (assets == null) continue;
                for (int j = 0; j < assets.length(); j++) {
                    JSONObject a = assets.optJSONObject(j);
                    if (a == null) continue;
                    String name = a.optString("name", "");
                    if (!name.toLowerCase().endsWith(".apk")) continue;
                    String v = versionFromName(name);
                    if (v == null) continue;
                    String pub = rel.optString("published_at", rel.optString("created_at", ""));
                    // 先比发布时间；时间相同再按版本号段比较
                    boolean better = best == null
                            || pub.compareTo(bestPub) > 0
                            || (pub.equals(bestPub) && compare(v, best) > 0);
                    if (better) {
                        best = v;
                        bestPub = pub;
                        bestName = name;
                        bestUrl = a.optString("browser_download_url", "");
                    }
                }
            }

            if (best == null) {
                r.message = "开源页还没有可下载的安装包";
                return r;
            }

            // 只是名字不同就是新版（版本方案变更后没法再靠比大小判断）
            if (!best.equals(currentVersion.trim())) {
                r.hasUpdate = true;
                r.url = bestUrl;
                r.message = "发现新版本 " + best + "（当前 " + currentVersion + "）：" + bestName;
            } else {
                r.message = "已是最新版本（" + currentVersion + "）";
            }
            return r;
        } catch (Throwable t) {
            r.message = "检查失败：" + t.getClass().getSimpleName();
            return r;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 从 Miku-4.6.apk / Miku-4.6-debug.apk 里取出版本后缀 */
    private static String versionFromName(String name) {
        String n = name;
        int i = n.lastIndexOf("Miku-");
        if (i < 0) i = n.lastIndexOf("miku-");
        if (i < 0) return null;
        String rest = n.substring(i + 5);
        int end = rest.length();
        for (int k = 0; k < rest.length(); k++) {
            char c = rest.charAt(k);
            if (!(Character.isDigit(c) || c == '.')) {
                end = k;
                break;
            }
        }
        String v = rest.substring(0, end);
        while (v.endsWith(".")) v = v.substring(0, v.length() - 1);
        return v.isEmpty() ? null : v;
    }

    /** 逐段比数字，4.10 > 4.9 */
    public static int compare(String a, String b) {
        String[] x = a.replaceAll("\\.+$", "").split("\\.");
        String[] y = b.replaceAll("\\.+$", "").split("\\.");
        int n = Math.max(x.length, y.length);
        for (int i = 0; i < n; i++) {
            int xi = i < x.length ? parseIntSafe(x[i]) : 0;
            int yi = i < y.length ? parseIntSafe(y[i]) : 0;
            if (xi != yi) return xi > yi ? 1 : -1;
        }
        return 0;
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return 0;
        }
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return new String(bos.toByteArray(), "UTF-8");
    }
}
