package com.miku.snow;

import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuProvider;

/**
 * Shizuku 桥接：借 Shizuku（由 ADB/root 启动的 shell 进程）拿到 uid=2000 的
 * 系统权限，执行 `appops set ... SYSTEM_ALERT_WINDOW allow`，从而让本应用
 * 不用手点系统开关也能挂上悬浮层。
 *
 * 所有命令都会把原始输出保留下来，授权失败时可在界面上看到原因。
 */
public final class ShizukuHelper {

    public interface Listener {
        void onShizukuChanged();
    }

    private static final List<Listener> listeners = new ArrayList<>();
    private static boolean initialized = false;
    private static String lastError = "";
    private static String lastLog = "";

    private ShizukuHelper() {
    }

    public static void init(Context context) {
        if (initialized) return;
        try {
            ShizukuProvider.enableMultiProcessSupport(false);
            Shizuku.addBinderReceivedListenerSticky(new Shizuku.OnBinderReceivedListener() {
                @Override
                public void onBinderReceived() {
                    notifyChanged();
                }
            });
            Shizuku.addBinderDeadListener(new Shizuku.OnBinderDeadListener() {
                @Override
                public void onBinderDead() {
                    notifyChanged();
                }
            });
            Shizuku.addRequestPermissionResultListener(new Shizuku.OnRequestPermissionResultListener() {
                @Override
                public void onRequestPermissionResult(int requestCode, int grantResult) {
                    notifyChanged();
                }
            });
            initialized = true;
        } catch (Throwable t) {
            lastError = t.toString();
        }
    }

    public static void addListener(Listener l) {
        if (l != null && !listeners.contains(l)) listeners.add(l);
    }

    public static void removeListener(Listener l) {
        listeners.remove(l);
    }

    private static void notifyChanged() {
        for (Listener l : new ArrayList<>(listeners)) {
            try {
                l.onShizukuChanged();
            } catch (Throwable ignored) {
            }
        }
    }

    public static boolean available() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean authorized() {
        try {
            if (!Shizuku.pingBinder()) return false;
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    public static void requestPermission() {
        try {
            Shizuku.requestPermission(100);
        } catch (Throwable t) {
            lastError = t.toString();
        }
    }

    public static String lastErrorText() {
        return lastError == null ? "" : lastError;
    }

    /** 打开 Shizuku 管理器，让用户手动给本应用授权 */
    public static boolean openManager(Context c) {
        try {
            String pkg = managerPackage(c);
            if (pkg == null) return false;
            android.content.Intent i = c.getPackageManager().getLaunchIntentForPackage(pkg);
            if (i == null) return false;
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            return true;
        } catch (Throwable t) {
            lastError = t.toString();
            return false;
        }
    }

    /** Shizuku 管理器的真实包名是 moe.shizuku.privileged.api，老代码写成了 moe.shizuku.manager，所以永远“没安装”。 */
    public static String managerPackage(Context c) {
        try {
            for (String p : new String[]{"moe.shizuku.privileged.api", "moe.shizuku.manager"}) {
                c.getPackageManager().getPackageInfo(p, 0);
                return p;
            }
        } catch (Throwable ignored) {
        }
        try {
            java.util.List<android.content.pm.PackageInfo> list =
                    c.getPackageManager().getInstalledPackages(0);
            for (android.content.pm.PackageInfo pi : list) {
                String n = pi.packageName;
                if (n != null && n.contains("shizuku") && !n.equals(c.getPackageName())) return n;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 主动向管理器要一次 binder（单进程应用也安全） */
    public static void requestBinder(Context c) {
        try {
            ShizukuProvider.requestBinderForNonProviderProcess(c);
        } catch (Throwable t) {
            lastError = t.toString();
        }
    }

    /**
     * 管理器是在它自己启动时把 binder 发给已安装的客户端应用的；
     * 应用装得比管理器晚，管理器就不会主动发。重启一次管理器即可。
     * 需要 root：am force-stop + monkey 拉起。
     */
    /**
     * Shizuku 的服务端在「进程进入前台」时把 binder 投给客户端，但每 uid 只投一次：
     * 首次启动时若投递失败，该 uid 会被记住，之后再也不投。
     * 所以重连的正确做法是杀掉本应用进程再拉起来，让 uid 重新注册一次。
     */
    public static String reconnect(Context c) {
        String self = c.getPackageName();
        String cmd = "am force-stop " + self + "; sleep 1; am start -n " + self + "/.MainActivity";
        String out = RootHelper.exec(cmd);
        return out == null ? "" : out;
    }

    /** API_V23 是 dangerous 权限，必须在运行时申请，否则管理器不会把 binder 发过来。 */
    public static boolean apiPermissionGranted(Context c) {
        try {
            return c.checkSelfPermission("moe.shizuku.manager.permission.API_V23")
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    public static int shellUid() {
        try {
            return Shizuku.getUid();
        } catch (Throwable t) {
            return -1;
        }
    }

    public static String describe() {
        if (!available()) return "未连接：Shizuku 未安装或服务未启动";
        if (!authorized()) return "已连接，未授权给本应用";
        int uid = shellUid();
        return "已授权 · shell uid=" + uid;
    }

    /**
     * 通过 Shizuku 执行一条 shell 命令，返回 stdout+stderr；失败返回 null。
     * Shizuku 13.x 的 newProcess 是私有方法，用反射拿；不同版本签名可能不同，这里逐个尝试。
     */
    public static String exec(String cmd) {
        if (!authorized()) {
            lastError = "尚未获得 Shizuku 授权";
            return null;
        }
        Throwable first = null;
        for (Method m : Shizuku.class.getDeclaredMethods()) {
            if (!"newProcess".equals(m.getName())) continue;
            try {
                m.setAccessible(true);
                Object[] args = buildArgs(m.getParameterTypes(), cmd);
                Process p = (Process) m.invoke(null, args);
                if (p == null) continue;
                StringBuilder sb = new StringBuilder();
                BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line;
                while ((line = br.readLine()) != null) sb.append(line).append('\n');
                BufferedReader er = new BufferedReader(new InputStreamReader(p.getErrorStream()));
                while ((line = er.readLine()) != null) sb.append(line).append('\n');
                p.waitFor();
                return sb.toString().trim();
            } catch (Throwable t) {
                if (first == null) first = t;
            }
        }
        lastError = first == null ? "找不到 newProcess 方法" : first.toString();
        return null;
    }

    /** 按参数类型拼参数：只支持 String / String[] / null。 */
    private static Object[] buildArgs(Class<?>[] types, String cmd) {
        Object[] args = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            Class<?> t = types[i];
            if (t == String[].class) {
                args[i] = i == 0 ? new String[]{"sh", "-c", cmd} : null;
            } else if (t == String.class) {
                args[i] = null;
            } else {
                args[i] = null;
            }
        }
        return args;
    }

    /**
     * 用 shell 身份给本应用打开 SYSTEM_ALERT_WINDOW。
     * 依次尝试多条命令（不同 ROM 对 appops 语法支持不同），最后用两种方式校验。
     */
    public static boolean grantOverlay(Context context) {
        String pkg = context.getPackageName();
        StringBuilder log = new StringBuilder();
        String[] cmds = {
                "appops set " + pkg + " SYSTEM_ALERT_WINDOW allow",
                "appops set --uid " + pkg + " SYSTEM_ALERT_WINDOW allow",
                "cmd appops set " + pkg + " SYSTEM_ALERT_WINDOW allow",
        };
        boolean anyOk = false;
        for (String c : cmds) {
            String out = exec(c);
            log.append("$ ").append(c).append('\n');
            if (out == null) {
                log.append("  (执行失败: ").append(lastError).append(")\n");
                continue;
            }
            log.append(out.length() == 0 ? "  (无输出)" : "  " + out).append('\n');
            anyOk = true;
        }

        String check = exec("appops get " + pkg + " SYSTEM_ALERT_WINDOW");
        log.append("$ appops get ").append(pkg).append(" SYSTEM_ALERT_WINDOW\n");
        log.append(check == null ? "  (读取失败)\n" : "  " + check + "\n");

        boolean can = false;
        try {
            can = Settings.canDrawOverlays(context);
        } catch (Throwable ignored) {
        }
        log.append("Settings.canDrawOverlays = ").append(can).append('\n');
        lastLog = log.toString();

        if (!can && check != null && check.toLowerCase().contains("allow")) {
            // appop 已经是 allow，但当前进程可能读到旧的缓存，下一帧/重进页面会生效
            can = true;
        }
        return anyOk && can;
    }

    /** 诊断信息：给用户看“到底卡在哪一步”。 */
    public static String diagnostics(Context context) {
        StringBuilder sb = new StringBuilder();
        sb.append("接口权限: ").append(apiPermissionGranted(context) ? "已授予" : "未授予").append('\n');
        sb.append("管理器包: ").append(String.valueOf(managerPackage(context))).append('\n');
        sb.append("Shizuku: ").append(available() ? "已连接" : "未连接").append('\n');
        sb.append("授权状态: ").append(authorized() ? "已授权" : "未授权").append('\n');
        if (available()) {
            try {
                sb.append("服务版本: ").append(Shizuku.getVersion()).append('\n');
            } catch (Throwable ignored) {
            }
            try {
                sb.append("shell uid: ").append(Shizuku.getUid()).append('\n');
            } catch (Throwable ignored) {
            }
        }
        sb.append("悬浮窗权限: ")
                .append(Settings.canDrawOverlays(context) ? "已开启" : "未开启").append('\n');
        if (lastError != null && lastError.length() > 0) {
            sb.append("最近错误: ").append(lastError).append('\n');
        }
        if (lastLog != null && lastLog.length() > 0) {
            sb.append('\n').append(lastLog);
        }
        return sb.toString();
    }

    /**
     * SH 之所以「ADB 也能隐藏」，是因为它整段程序是跑在 shell（uid 2000）上的；
     * 普通应用 uid 调 SurfaceControl.Transaction#setSecure 会被非 SDK 接口拦截。
     * 这里用手里任意一条 shell 通道把 hidden_api_policy 打开，反射才能过去。
     */
    public static String liftHiddenApi(Context c) {
        String cmds = "settings put global hidden_api_policy 1; "
                + "settings put global hidden_api_policy_p_apps 1; "
                + "settings put global hidden_api_policy_pre_p_apps 1; "
                + "settings get global hidden_api_policy";
        StringBuilder log = new StringBuilder();
        String out = null;
        try {
            out = exec(cmds);
        } catch (Throwable ignored) {
        }
        if (out != null && !out.contains("Exception") && !out.contains("Permission denial")) {
            log.append("Shizuku: ").append(out.trim()).append('\n');
            return log.toString();
        }
        log.append("Shizuku 不可用：").append(lastError()).append('\n');
        out = RishHelper.exec(c, cmds);
        if (out != null && !out.contains("Exception") && !out.contains("Permission denial")) {
            log.append("rish: ").append(out.trim()).append('\n');
            return log.toString();
        }
        log.append("rish 不可用：").append(RishHelper.lastLog()).append('\n');
        out = RootHelper.exec(cmds);
        if (out != null && !out.contains("Exception")) {
            log.append("Root: ").append(out.trim()).append('\n');
            return log.toString();
        }
        log.append("Root 也不可用：").append(RootHelper.lastLog());
        return log.toString();
    }

    /** 归档：之前那条「重启本应用让 Shizuku 重新投 binder」的入口还在，这里保留别名 */
    public static String lastError() {
        return lastError;
    }
}
