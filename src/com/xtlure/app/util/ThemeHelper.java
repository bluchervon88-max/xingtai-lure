package com.xtlure.app.util;

import android.app.Activity;
import android.view.View;
import android.view.Window;

/**
 * 主题在 Activity 上的落地。
 *
 * 因为配色是运行时可变的（见 Ui/Theme），XML 主题里写死的颜色只作为
 * 首次启动时的兜底，真正的颜色在这里按当前主题设置到窗口上，
 * 避免"深色主题闪一下白屏"或状态栏颜色对不上。
 */
public final class ThemeHelper {

    private ThemeHelper() {
    }

    /** 所有 Activity 的 onCreate 第一件事调用它 */
    public static void prepare(Activity a) {
        AppCtx.init(a);
        Theme.applyFromPrefs(a);
        applyToWindow(a);
    }

    private static void applyToWindow(Activity a) {
        try {
            Window w = a.getWindow();
            if (w == null) {
                return;
            }
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Ui.BG));
            w.setStatusBarColor(Ui.STATUS_BAR);
            w.setNavigationBarColor(Ui.STATUS_BAR);
        } catch (Exception ignored) {
        }
    }

    /** 切换主题后重建整个任务，确保所有已打开的页面都换上新配色 */
    public static void restartApp(Activity a) {
        try {
            android.content.Intent i = new android.content.Intent(a, Class.forName(
                    "com.xtlure.app.ui.MainActivity"));
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    | android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK);
            a.startActivity(i);
            a.finish();
        } catch (Exception e) {
            try {
                a.recreate();
            } catch (Exception ignored) {
            }
        }
    }

    /** 给任意 View 铺上当前主题的页面底色 */
    public static void paintBackground(View v) {
        if (v != null) {
            v.setBackgroundColor(Ui.BG);
        }
    }
}
