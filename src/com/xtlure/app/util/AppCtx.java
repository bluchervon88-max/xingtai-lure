package com.xtlure.app.util;

import android.content.Context;

/**
 * 全局 ApplicationContext 持有者。
 *
 * 为什么需要：主题要在任何界面读颜色之前就确定，而工具类（Db 等）拿不到 Context。
 * 在这里存一份 application context，启动时初始化一次即可。
 * 只存 application context，不会泄漏 Activity。
 */
public final class AppCtx {

    private static Context appContext;

    private AppCtx() {
    }

    public static void init(Context ctx) {
        if (appContext == null && ctx != null) {
            appContext = ctx.getApplicationContext();
        }
    }

    public static Context get() {
        return appContext;
    }
}
