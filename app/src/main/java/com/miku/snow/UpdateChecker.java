package com.miku.snow;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 检查更新：读开源页的 releases，按 APK 文件名里的版本后缀比较（Miku-4.6.apk -> 4.6）。
 * 只看名字，不看发布日期，谁的后缀大就用谁。
 */
public class UpdateChecker {

    public static class Result {
        public boolean hasUpdate;
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
                    if (best == null || compare(v, best) > 0) {
                        best = v;
                        bestName = name;
                        bestUrl = a.optString("browser_download_url", "");
                    }
                }
            }

            if (best == null) {
                r.message = "开源页还没有可下载的安装包";
                return r;
            }

            if (compare(best, currentVersion) > 0) {
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
        return v.isEmpty() ? null : v;
    }

    /** 逐段比数字，4.10 > 4.9 */
    public static int compare(String a, String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
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
