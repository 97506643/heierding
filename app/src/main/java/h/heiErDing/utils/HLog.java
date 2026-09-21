package h.heiErDing.utils;

import de.robv.android.xposed.XposedBridge;

/**
 * 统一日志工具。
 *
 * 说明：LSPosed 将 Throwable 形式的日志视为 error 级别，
 * 因此普通信息请使用 i()/d()，仅真实失败使用 e()。
 */
public final class HLog {

    private static final String TAG = "heiErDing";

    private HLog() {
    }

    /** 普通信息日志。 */
    public static void i(String message) {
        XposedBridge.log("[" + TAG + "] " + message);
    }

    /** 调试日志。 */
    public static void d(String message) {
        XposedBridge.log("[" + TAG + "][D] " + message);
    }

    /** 警告日志。 */
    public static void w(String message) {
        XposedBridge.log("[" + TAG + "][W] " + message);
    }

    /** 错误日志（Throwable 形式，LSPosed 会归为 error）。 */
    public static void e(String message) {
        XposedBridge.log(new RuntimeException(message));
    }

    /** 错误日志（带异常）。 */
    public static void e(String message, Throwable throwable) {
        if (throwable == null) {
            e(message);
            return;
        }
        XposedBridge.log(new RuntimeException(message, throwable));
    }
}
