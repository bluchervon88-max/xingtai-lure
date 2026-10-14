package com.xtlure.app.util;

import android.app.Activity;
import android.content.Context;
import android.util.DisplayMetrics;
import android.view.View;

/**
 * 屏幕适配层。
 *
 * ★ 为什么必须单独做这一层
 *   之前所有尺寸都写成固定 dp（如"标签栏 64dp"），在这台 vivo 上全部算错：
 *     · 系统设置了 density override（实际 600dpi，物理 640dpi），dp→px 的换算与直觉不符
 *     · 应用窗口高度 3168px 比屏幕可用高度 3026px 多出 142px，底部内容被挤出屏幕
 *   结果是"设了 64dp 实际只有 39dp""标签栏只剩一条边"这类问题反复出现。
 *
 *   这一层的做法：**先量，再算**。开机时把真实可用区域量出来，
 *   所有关键尺寸都由「可见高度 / 密度 / 屏幕宽高比」推导，而不是写死。
 *
 * 使用方式（每个 Activity 的 onCreate 最先调用）：
 *   ScreenFit.get().init(this);
 *   int h = ScreenFit.get().tabBar();      // 标签栏高度，已保证触摸区达标
 */
public final class ScreenFit {

    private static final ScreenFit INSTANCE = new ScreenFit();

    public static ScreenFit get() {
        return INSTANCE;
    }

    private ScreenFit() {
    }

    // ---- 实测环境 ----
    /** 屏幕的物理尺寸（px），含系统栏 */
    public int screenW, screenH;
    /** 应用内容区的实际可用范围（px） */
    public int contentTop, contentBottom;
    /** 密度 */
    public float density;
    public int densityDpi;
    /** 是否为非常规密度（系统改过 DPI 或用了显示缩放） */
    public boolean densityOverridden;

    /** 可用高度（px）：内容区实际能看到的部分 */
    public int usableH;
    /** 可用高度换算成 dp */
    public float usableHdp;
    /** 屏幕高宽比（长边/短边） */
    public float aspect;

    private boolean inited;

    /** 已按基准设计过的尺寸缓存，避免重复计算 */
    private int tabBarPx = -1;
    private int headerPadPx = -1;

    // ================================================================ 初始化

    /**
     * 采集真实屏幕参数。必须在 Activity 可见之后调用才能拿到准确的窗口范围，
     * 因此这里同时做两步：onCreate 时取物理值，布局完成后再校正可用区域。
     */
    public void init(Activity a) {
        DisplayMetrics dm = a.getResources().getDisplayMetrics();
        density = dm.density > 0 ? dm.density : 1f;
        densityDpi = dm.densityDpi;
        screenW = dm.widthPixels;

        // widthPixels/heightPixels 在部分 ROM 上给的是"窗口可用尺寸"而非物理尺寸，
        // 用真实显示信息覆盖，确保拿到的是物理屏幕
        try {
            android.graphics.Point p = new android.graphics.Point();
            a.getWindowManager().getDefaultDisplay().getRealSize(p);
            if (p.x > 0 && p.y > 0) {
                screenW = p.x;
                screenH = p.y;
            } else {
                screenH = dm.heightPixels;
            }
        } catch (Throwable e) {
            screenH = dm.heightPixels;
        }

        // 密度是否被系统改过：以常见基准 160 的整数倍来判断
        int physicalDpi = 0;
        try {
            android.util.DisplayMetrics real = new android.util.DisplayMetrics();
            a.getWindowManager().getDefaultDisplay().getRealMetrics(real);
            physicalDpi = real.densityDpi;
        } catch (Throwable ignored) {
        }
        // 系统显示缩放会让逻辑密度低于物理密度
        densityOverridden = physicalDpi > 0 && Math.abs(physicalDpi - densityDpi) > 20;

        aspect = screenH > 0 ? (float) Math.max(screenW, screenH) / Math.min(screenW, screenH) : 2f;

        // 可用高度先给个保守估算，等布局完成再由 refreshUsable() 校正
        usableH = (int) (screenH * 0.92f);
        usableHdp = usableH / density;
        inited = true;
    }

    /**
     * 用内容视图的实测位置校正可用区域。
     * 这个方法解决的就是"窗口比屏幕高、底部被挤出屏幕"的问题：
     * 不再相信任何窗口声明的高度，直接看内容区实际落在屏幕的哪一段。
     */
    public void refreshUsable(View content) {
        if (content == null) {
            return;
        }
        try {
            int[] loc = new int[2];
            content.getLocationOnScreen(loc);
            contentTop = loc[1];
            int bottom = loc[1] + content.getHeight();
            // 内容若超出屏幕下沿，只算到屏幕为止 —— 超出的部分用户看不到
            contentBottom = Math.min(bottom, screenH);
            usableH = Math.max(1, contentBottom - contentTop);
            usableHdp = usableH / density;
            // 尺寸需要重算
            tabBarPx = -1;
            headerPadPx = -1;
        } catch (Throwable ignored) {
        }
    }

    public boolean isInited() {
        return inited;
    }

    // ================================================================ 尺寸推导

    /** px 换算（带下限保护） */
    public int dp(float v) {
        return Math.max(0, Math.round(v * density));
    }

    /**
     * 按可用高度推导一个"弹性尺寸"。
     *
     * 思路：把设计稿的基准当作 812dp 高的屏幕，元素高度在
     * [minDp, maxDp] 之间按实际屏高比例缩放，并夹紧到区间内。
     * 这样小屏不会被顶满、大屏也不会留出大片空白。
     *
     * @param designDp 在 812dp 基准屏上的期望高度
     * @param minDp    最小高度（触摸区下限之类）
     * @param maxDp    最大高度
     */
    public int flex(float designDp, float minDp, float maxDp) {
        final float baseH = 812f;
        float h = usableHdp > 0 ? usableHdp : baseH;
        // 只在 0.85 ~ 1.25 之间做比例缩放，避免极端屏幕把元素拉爆
        float scale = h / baseH;
        scale = Math.max(0.85f, Math.min(1.25f, scale));
        float v = designDp * scale;
        v = Math.max(minDp, Math.min(maxDp, v));
        return dp(v);
    }

    /**
     * 底部标签栏高度。
     *
     * 保证：触摸区 >= 48dp，且在矮屏上不会把内容挤没。
     * 之前写死 64dp 时被窗口压缩到 39dp，这里直接按实测可用高度算并由调用方校验。
     */
    public int tabBar() {
        if (tabBarPx > 0) {
            return tabBarPx;
        }
        // 基准 56dp；可用高度紧张时不低于 52dp（含内边距后触摸区仍 >= 48dp）
        tabBarPx = flex(56f, 52f, 68f);
        return tabBarPx;
    }

    /** 触摸目标下限（px）：48dp，但不低于 40dp 以免矮屏上元素过大 */
    public int touchMin() {
        return dp(48f);
    }

    /** 顶栏内容区的上下内边距：矮屏收紧、高屏放宽 */
    public int headerPad() {
        if (headerPadPx > 0) {
            return headerPadPx;
        }
        headerPadPx = flex(10f, 6f, 16f);
        return headerPadPx;
    }

    /**
     * 列表卡片之间的间距。
     *
     * 实测这台设备上 flex(6f) 得到 5.9dp，比 Material 列表的 8dp 略紧，
     * 卡片挨得太近会让"一张卡到哪里结束"变得模糊 —— 深色底上尤其明显。
     * 改成以 8dp 为基准，矮屏仍可收紧到 4dp。
     */
    public int cardGap() {
        return flex(8f, 4f, 12f);
    }

    /**
     * 状态栏高度（px）。用于给顶栏留出安全距离。
     */
    public int statusBar(Context c) {
        int id = c.getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (id > 0) {
            return c.getResources().getDimensionPixelSize(id);
        }
        return dp(24f);
    }

    /** 诊断用的一句话描述 */
    public String describe() {
        return "屏 " + screenW + "x" + screenH + "px"
                + " 密度 " + densityDpi + "dpi(x" + String.format(java.util.Locale.CHINA, "%.2f", density) + ")"
                + (densityOverridden ? " [系统改过密度]" : "")
                + " 可用 " + usableH + "px(" + Math.round(usableHdp) + "dp)"
                + " 内容区 y=" + contentTop + ".." + contentBottom;
    }
}
