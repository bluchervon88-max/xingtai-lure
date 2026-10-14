package com.xtlure.app.util;

import android.content.Context;

/**
 * 主题系统。
 *
 * 设计：把两套配色写成两个 Ui 实例，Ui.apply() 把颜色同步到 Ui 的**静态字段**上，
 * 全工程各处引用 Ui.ACCENT / Ui.SURFACE 的写法因此完全不用改。
 * 换肤只需要改本文件的两个 build 方法。
 *
 * 注意：Ui 里的调色板字段带 p 前缀（pBg/pAccent...），
 * 与全局静态字段（BG/ACCENT...）区分开，避免 Java 允许同名隐藏带来的坑。
 *
 * 两套主题：
 *   dark  夜钓（默认）—— 深蓝灰底 + 水青主色。夜间出钓不刺眼。
 *   light 日间        —— 浅灰白底 + 深青主色。白天强光下看得更清。
 */
public final class Theme {

    public static final String DARK = "dark";
    public static final String LIGHT = "light";

    private static final String KEY = "theme_name";

    /** 当前主题调色板 */
    private static Ui current = buildDark();

    private Theme() {
    }

    /** 从偏好读取并应用主题。必须在任何界面取色之前调用。 */
    public static void applyFromPrefs(Context ctx) {
        String name = ctx == null ? DARK : Prefs.getString(ctx, KEY, DARK);
        current = LIGHT.equals(name) ? buildLight() : buildDark();
        Ui.apply(current);
    }

    /** 切换主题并立即生效（当前界面需要重建才能看到效果） */
    public static void set(Context ctx, String name) {
        Prefs.setString(ctx, KEY, name);
        current = LIGHT.equals(name) ? buildLight() : buildDark();
        Ui.apply(current);
    }

    public static String currentName() {
        return current.isDark ? DARK : LIGHT;
    }

    public static boolean isDark() {
        return current.isDark;
    }

    public static String displayName() {
        return current.isDark ? "夜钓（深色）" : "日间（浅色）";
    }

    // ================================================================ 调色板构建
    // 公开出来是为了让离线测试能直接构造两套配色做对比度校验（见 tools/ThemeTest.java）

    /** 夜钓（深色）调色板 */
    public static Ui buildDark() {
        Ui u = new Ui();
        u.isDark = true;

        // ★ 表面层次的调整依据（2026-10）
        //
        // 深色模式不能用阴影表达层级（阴影在深底上不可见），唯一手段是
        // **让每一层比下一层更亮一点**。Material Design 3 对最低一层的要求是
        // 相对背景 ≥ +5%，而原来 pSurface 只有 +4.51%（#171C24 对 #0E1116），
        // 压线不足 —— 结果是 73 张卡片看起来像贴在同一个大黑底上，
        // 用户反馈的"单调"主要来自这里，而不是强调色用得少。
        //
        // 调整后：
        //   pBg       #0E1116   —
        //   pSurface  #1B2230  +7.65%   （原 +4.51%）
        //   pSurface2 #242C3A  +11.37%  （原 +8.43%）
        //   pSurface3 #2C3545  +15.10%
        //   pBorder   #3A4557  +21.37%  （描边要更明确才看得出卡片边界）
        //
        // 核对过对比度没有掉到不合格：正文 #E9EEF5 在新卡片底上是 13.67:1（AAA），
        // 次要文字 #9AA6B5 是 6.45:1（AA），主色 #2FB6A8 是 6.36:1。
        // 这些数字由工具算过，并且 ThemeTest 的 WCAG 审计会持续兜住。
        u.pBg = 0xFF0E1116;
        u.pSurface = 0xFF1B2230;
        u.pSurface2 = 0xFF242C3A;
        u.pSurface3 = 0xFF2C3545;
        u.pBorder = 0xFF3A4557;

        u.pText = 0xFFE9EEF5;
        u.pTextDim = 0xFF9AA6B5;
        u.pTextFaint = 0xFF6B7684;

        u.pAccent = 0xFF2FB6A8;
        u.pAccentDark = 0xFF1E8C81;
        u.pOnAccent = 0xFF04201D;

        u.pWarn = 0xFFE8A33D;
        u.pDanger = 0xFFE05A5A;
        u.pOk = 0xFF4FBF6B;
        u.pInfo = 0xFF5A9BE0;
        u.pFav = 0xFFF0B429;

        u.pRipple = 0x33FFFFFF;
        u.pInputBg = 0xFF1F2630;
        u.pDialogBg = 0xFF171C24;
        u.pStatusBar = 0xFF0E1116;
        return u;
    }

    /** 日间（浅色）调色板 */
    public static Ui buildLight() {
        Ui u = new Ui();
        u.isDark = false;

        u.pBg = 0xFFF2F4F7;
        u.pSurface = 0xFFFFFFFF;
        u.pSurface2 = 0xFFF0F2F5;
        u.pSurface3 = 0xFFE3E7EC;
        u.pBorder = 0xFFDCE1E7;

        u.pText = 0xFF14181D;
        u.pTextDim = 0xFF5A6472;
        u.pTextFaint = 0xFF8B95A3;

        u.pAccent = 0xFF12897D;
        u.pAccentDark = 0xFF0B6157;
        u.pOnAccent = 0xFFFFFFFF;

        u.pWarn = 0xFFB4720E;
        u.pDanger = 0xFFCC3B3B;
        u.pOk = 0xFF2E8B48;
        u.pInfo = 0xFF2C6FBF;
        // 浅色底上金色要压暗：原来用 #D99A00 对比度只有 2.45，太阳底下看不见星星。
        // 这个值由 tools/ThemeTest.java 的 WCAG 对比度审计倒推出来（>= 3.0）。
        u.pFav = 0xFFA8730A;

        u.pRipple = 0x22000000;
        u.pInputBg = 0xFFFFFFFF;
        u.pDialogBg = 0xFFFFFFFF;
        u.pStatusBar = 0xFFF2F4F7;
        return u;
    }
}
