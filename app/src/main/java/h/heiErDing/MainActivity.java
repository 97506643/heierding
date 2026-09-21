package h.heiErDing;

import android.app.Activity;
import android.os.Bundle;

import h.heiErDing.ui.SettingsUI;
import h.heiErDing.utils.HLog;

/**
 * 黑耳钉模块的桌面入口 Activity。
 *
 * <p>点击桌面图标后直接在当前 Activity 的 decorView 上渲染模块设置页，
 * 设置页以纯 Android View 实现（{@code h.heiErDing.ui.miuix.MiuixSettingsPage}），
 * 不依赖 Compose / miuix 运行时。</p>
 */
public final class MainActivity extends Activity {

    private static final String TAG = "[heiErDing:MainActivity]";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            SettingsUI.show(this);
        } catch (Throwable t) {
            HLog.e(TAG + " 打开设置页失败: " + t.getMessage(), t);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从其它页面返回时重新渲染，保证开关状态与当前配置一致。
        try {
            SettingsUI.show(this);
        } catch (Throwable t) {
            HLog.e(TAG + " 恢复设置页失败: " + t.getMessage(), t);
        }
    }
}