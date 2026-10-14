package com.xtlure.app.util;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * ★★ 全局配色与组件工厂 —— UI 改版集中在这个文件 ★★
 *
 * 颜色字段**不是常量而是静态变量**，由 Theme 在启动时和切换主题时通过
 * apply() 写入。这样全工程各处引用 Ui.ACCENT / Ui.SURFACE 的写法完全不用改，
 * 换肤只需要改 Theme.buildDark() / buildLight() 两个方法。
 *
 * 想调版式：改下面的 RADIUS_* / GAP_* / TEXT_* 尺寸常量。
 * 想调配色：改 Theme.java。
 */
public final class Ui {

    /**
     * 允许 Theme 构造调色板实例。
     * 普通代码不需要 new Ui()，直接用下面的静态字段即可。
     */
    public Ui() {
    }

    /** 是否深色主题（某些地方需要按明暗微调，例如半透明叠加色） */
    public boolean isDark = true;

    // ================================================================ 配色字段

    public int pBg = 0xFF0E1116;
    public int pSurface = 0xFF171C24;
    public int pSurface2 = 0xFF1F2630;
    public int pSurface3 = 0xFF2A323D;
    public int pBorder = 0xFF2C3542;

    public int pText = 0xFFE9EEF5;
    public int pTextDim = 0xFF9AA6B5;
    public int pTextFaint = 0xFF6B7684;

    public int pAccent = 0xFF2FB6A8;
    public int pAccentDark = 0xFF1E8C81;
    public int pOnAccent = 0xFF04201D;

    public int pWarn = 0xFFE8A33D;
    public int pDanger = 0xFFE05A5A;
    public int pOk = 0xFF4FBF6B;
    public int pInfo = 0xFF5A9BE0;
    public int pFav = 0xFFF0B429;

    /** 点击水波纹颜色（半透明，深浅主题不同） */
    public int pRipple = 0x33FFFFFF;
    /** 输入框底色（浅色主题下是白色而非灰） */
    public int pInputBg = 0xFF1F2630;
    public int pDialogBg = 0xFF171C24;
    public int pStatusBar = 0xFF0E1116;

    // ================================================================ 全局静态视图
    // 由 Theme.apply() 同步，供全工程直接引用

    public static int BG;
    public static int SURFACE;
    public static int SURFACE_2;
    public static int SURFACE_3;
    public static int BORDER;

    public static int TEXT;
    public static int TEXT_DIM;
    public static int TEXT_FAINT;

    public static int ACCENT;
    public static int ACCENT_DARK;
    public static int ON_ACCENT;

    public static int WARN;
    public static int DANGER;
    public static int OK;
    public static int INFO;
    public static int FAV;

    public static int RIPPLE = 0x33FFFFFF;
    public static int INPUT_BG = 0xFF1F2630;
    public static int DIALOG_BG = 0xFF171C24;
    public static int STATUS_BAR = 0xFF0E1116;

    public static boolean IS_DARK = true;

    /** 把一套配色同步到静态字段。启动时与切换主题时各调一次。 */
    public static void apply(Ui p) {
        if (p == null) {
            return;
        }
        BG = p.pBg;
        SURFACE = p.pSurface;
        SURFACE_2 = p.pSurface2;
        SURFACE_3 = p.pSurface3;
        BORDER = p.pBorder;

        TEXT = p.pText;
        TEXT_DIM = p.pTextDim;
        TEXT_FAINT = p.pTextFaint;

        ACCENT = p.pAccent;
        ACCENT_DARK = p.pAccentDark;
        ON_ACCENT = p.pOnAccent;

        WARN = p.pWarn;
        DANGER = p.pDanger;
        OK = p.pOk;
        INFO = p.pInfo;
        FAV = p.pFav;

        RIPPLE = p.pRipple;
        INPUT_BG = p.pInputBg;
        DIALOG_BG = p.pDialogBg;
        STATUS_BAR = p.pStatusBar;
        IS_DARK = p.isDark;
    }

    // ================================================================ 尺寸
    // 与主题无关，改这里可以整体调版式松紧

    public static final int RADIUS_CARD = 14;
    public static final int RADIUS_CHIP = 999;
    public static final int RADIUS_BTN = 12;

    public static final int GAP_XS = 4;
    public static final int GAP_S = 8;
    public static final int GAP_M = 12;
    public static final int GAP_L = 16;
    public static final int GAP_XL = 24;

    public static final int TEXT_TITLE = 17;
    public static final int TEXT_BODY = 14;
    public static final int TEXT_SMALL = 12;
    public static final int TEXT_TINY = 11;

    // ================================================================ 转换

    public static int dp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics());
    }

    /** "10月7日 周三" 形式的日期文本 */
    public static String dateText(java.util.Calendar c) {
        String[] week = {"", "周日", "周一", "周二", "周三", "周四", "周五", "周六"};
        int w = c.get(java.util.Calendar.DAY_OF_WEEK);
        return (c.get(java.util.Calendar.MONTH) + 1) + "月"
                + c.get(java.util.Calendar.DAY_OF_MONTH) + "日 "
                + (w >= 1 && w <= 7 ? week[w] : "");
    }

    // ================================================================ Drawable

    /** 圆角实心背景，可选描边 */
    public static GradientDrawable round(int fill, float radiusDp, Integer strokeColor,
                                         float strokeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(fill);
        d.setCornerRadius(radiusDp);
        if (strokeColor != null) {
            d.setStroke(Math.max(1, (int) strokeDp), strokeColor);
        }
        return d;
    }

    public static GradientDrawable round(int fill, float radiusDp) {
        return round(fill, radiusDp, null, 0);
    }

    /** 带水波纹的圆角背景（API21+），波纹颜色跟随主题 */
    public static RippleDrawable ripple(int fill, float radiusDp, int rippleColor) {
        return new RippleDrawable(ColorStateList.valueOf(rippleColor),
                round(fill, radiusDp), null);
    }

    /**
     * 带水波纹 + 描边的圆角背景。
     *
     * 用于"强调色胶囊按钮"：底色是同色系淡底、描边与文字同色。
     * 之前只有纯色涟漪版本，带描边就得自己拼 RippleDrawable —— 与其在各处重复，
     * 不如在这里提供一次（顶栏的「🗺 分布」入口就是这种按钮）。
     */
    public static RippleDrawable ripple(int fill, float radiusDp, int rippleColor,
                                        Integer strokeColor, float strokeDp) {
        return new RippleDrawable(ColorStateList.valueOf(rippleColor),
                round(fill, radiusDp, strokeColor, strokeDp), null);
    }

    /** 用主题默认波纹色 */
    public static RippleDrawable ripple(int fill, float radiusDp) {
        return ripple(fill, radiusDp, RIPPLE);
    }

    /**
     * 强调色胶囊的统一样式：淡底 + 同色描边 + 涟漪。
     *
     * 用于「当前处于激活状态」的元素（顶栏的分布入口、选中的筛选 chip）。
     * 底色混合比 0.30 是调过的：0.22 在深色底上几乎看不出激活，
     * 而更高会让整屏出现多个强色块，破坏"一屏一个强调色"。
     */
    public static RippleDrawable accentChip(int accentColor) {
        return ripple(blend(accentColor, SURFACE, 0.70f), RADIUS_CHIP, RIPPLE,
                blend(accentColor, SURFACE, 0.45f), 1f);
    }

    public static RippleDrawable cardRipple(Context c) {
        return ripple(SURFACE, RADIUS_CARD, RIPPLE);
    }

    /** 圆形纯色底 */
    public static GradientDrawable circle(int fill) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(fill);
        return d;
    }

    /** 作钓状态对应的颜色 */
    public static int statusColor(int status) {
        switch (status) {
            case 1: return OK;
            case 2: return DANGER;
            case 3: return INFO;
            case 4: return WARN;
            default: return TEXT_FAINT;
        }
    }

    /** 水域类型对应的颜色 */
    public static int waterTypeColor(int type) {
        switch (type) {
            case 1: return 0xFF3E8EDE; // 水库 蓝
            case 2: return 0xFF2FB6A8; // 河流 青
            case 3: return 0xFF4FA3E3; // 湖泊
            case 4: return 0xFF6FBF73; // 池塘
            case 5: return 0xFFE8A33D; // 黑坑 橙
            case 6: return 0xFF8E7CE0; // 渠道 紫
            case 7: return 0xFF9C8B6B; // 沙坑 土
            default:  return TEXT_FAINT;
        }
    }

    // ================================================================ 文字

    public static TextView text(Context c, String s, float sizeSp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sizeSp);
        t.setTextColor(color);
        return t;
    }

    public static TextView title(Context c, String s) {
        TextView t = text(c, s, TEXT_TITLE, TEXT);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        return t;
    }

    public static TextView dim(Context c, String s) {
        return text(c, s, TEXT_SMALL, TEXT_DIM);
    }

    public static TextView label(Context c, String s) {
        return text(c, s, TEXT_SMALL, TEXT_FAINT);
    }

    /** 小胶囊标签：文字用主色，底色是同色系淡底 + 淡描边 */
    public static TextView chip(Context c, String s, int color) {
        TextView t = text(c, s, TEXT_TINY, color);
        t.setPadding(dp(c, 8), dp(c, 3), dp(c, 8), dp(c, 3));
        t.setBackground(round(blend(color, SURFACE, 0.80f), RADIUS_CHIP,
                blend(color, SURFACE, 0.55f), 1f));
        return t;
    }

    /** 把 color 以 alpha 混到 base 上，得到柔和底色。深色主题下会自动加大对比。 */
    public static int blend(int color, int base, float alphaOfColor) {
        float a = Math.max(0f, Math.min(1f, alphaOfColor));
        int r = (int) (Color.red(color) * a + Color.red(base) * (1 - a));
        int g = (int) (Color.green(color) * a + Color.green(base) * (1 - a));
        int b = (int) (Color.blue(color) * a + Color.blue(base) * (1 - a));
        return Color.rgb(r, g, b);
    }

    // ================================================================ 布局

    public static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    /** 一行「标签 : 值」 */
    public static View kv(Context c, String k, String v) {
        LinearLayout r = row(c);
        r.setPadding(0, dp(c, 5), 0, dp(c, 5));

        TextView kt = text(c, k, TEXT_SMALL, TEXT_FAINT);
        kt.setMinWidth(dp(c, 68));
        r.addView(kt);

        TextView vt = text(c, v == null || v.isEmpty() ? "—" : v, TEXT_BODY, TEXT);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        vt.setLayoutParams(lp);
        r.addView(vt);
        return r;
    }

    /** 分区标题 */
    public static TextView section(Context c, String s) {
        TextView t = text(c, s, TEXT_SMALL, ACCENT);
        t.setPadding(0, dp(c, GAP_M), 0, dp(c, GAP_XS));
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        return t;
    }

    /** 卡片容器 */
    public static LinearLayout card(Context c) {
        LinearLayout l = column(c);
        l.setPadding(dp(c, GAP_M), dp(c, GAP_M), dp(c, GAP_M), dp(c, GAP_M));
        l.setBackground(round(SURFACE, RADIUS_CARD, BORDER, 1f));
        return l;
    }

    public static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    public static LinearLayout.LayoutParams lpMatch() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    public static LinearLayout.LayoutParams lpMatch(int height) {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height);
    }

    /**
     * 「按权重占满剩余空间」的布局参数。
     *
     * ★ 为什么必须有这个方法（踩过的坑）
     *
     * 原来各处都写 `new LinearLayout.LayoutParams(0, 0, 1f)`，以为
     * 「宽 0 + 权重 1」就等于「占满剩余宽度」。在**纵向** LinearLayout 里碰巧没暴露，
     * 但在**横向** LinearLayout 里这是个严重 bug：
     *
     *   weight 只分配**主轴**方向的尺寸（横向布局里就是宽度），
     *   高度是交叉轴，必须由 width/height 自己给 —— 传 0 就永远是 0。
     *
     *   后果是控件宽 931px、高 0px，**完全不可见**，而且因为不可见，
     *   uiautomator dump 里也不会出现，排查时极难定位。
     *   实测「记一次作钓」表单 20 个控件全部隐身，只剩左边的标签和右边的按钮。
     *
     * 正确做法：高度传 WRAP_CONTENT，让它按内容自然撑开。
     * 无论是横向还是纵向父容器，这么写都正确。
     */
    public static LinearLayout.LayoutParams weighted(float weight) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
    }

    /** 权重占位（真正需要 0 高度的弹性空隙，例如把后面的元素推到右边） */
    public static LinearLayout.LayoutParams weightedSpacer() {
        return new LinearLayout.LayoutParams(0, 0, 1f);
    }
}
