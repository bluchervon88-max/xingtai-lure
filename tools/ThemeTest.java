import com.xtlure.app.util.Theme;
import com.xtlure.app.util.Ui;

/**
 * 两套配色的可读性与一致性自检（纯 JVM）。
 *
 * 为什么值得测：配色算错是肉眼最不容易发现、但在户外最要命的问题 ——
 * 浅色主题下文字对比度不足，太阳底下一片糊。这里用 WCAG 对比度公式
 * 把"能不能看清"变成可计算的数字。
 *
 * WCAG 2.0 对比度要求：
 *   正文    >= 4.5  （AA）
 *   大字/次要 >= 3.0
 */
public class ThemeTest {

    static int pass = 0, fail = 0;

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + name + "   " + detail);
        if (ok) pass++; else fail++;
    }

    /** 相对亮度（WCAG 定义） */
    static double luminance(int color) {
        double r = channel((color >> 16) & 0xFF);
        double g = channel((color >> 8) & 0xFF);
        double b = channel(color & 0xFF);
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    static double channel(int v) {
        double s = v / 255.0;
        return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    }

    /** 对比度 1.0 ~ 21.0 */
    static double contrast(int a, int b) {
        double la = luminance(a);
        double lb = luminance(b);
        double hi = Math.max(la, lb);
        double lo = Math.min(la, lb);
        return (hi + 0.05) / (lo + 0.05);
    }

    static String hex(int c) {
        return String.format("#%06X", c & 0xFFFFFF);
    }

    static String fmt(double d) {
        return String.format("%.2f", d);
    }

    /** 对一套配色做全部检查 */
    static void audit(String label, Ui p, boolean expectDark) {
        System.out.println("\n=== " + label + " ===");
        System.out.println("  底色 " + hex(p.pBg) + "  卡片 " + hex(p.pSurface)
                + "  主色 " + hex(p.pAccent) + "  正文 " + hex(p.pText));

        check(label + " isDark 标记正确", p.isDark == expectDark,
                "isDark=" + p.isDark);

        double cText = contrast(p.pText, p.pSurface);
        double cTextBg = contrast(p.pText, p.pBg);
        double cDim = contrast(p.pTextDim, p.pSurface);
        double cFaint = contrast(p.pTextFaint, p.pSurface);
        double cAccent = contrast(p.pAccent, p.pSurface);
        double cOnAccent = contrast(p.pOnAccent, p.pAccent);
        double cBorder = contrast(p.pBorder, p.pSurface);

        System.out.println("  正文/卡片 " + fmt(cText)
                + "   正文/底色 " + fmt(cTextBg)
                + "   次要 " + fmt(cDim)
                + "   最弱 " + fmt(cFaint));
        System.out.println("  主色/卡片 " + fmt(cAccent)
                + "   主色上的字 " + fmt(cOnAccent)
                + "   描边/卡片 " + fmt(cBorder));

        check(label + " 正文对比度 >= 4.5（AA 正文）", cText >= 4.5, fmt(cText));
        check(label + " 正文在页面底色上 >= 4.5", cTextBg >= 4.5, fmt(cTextBg));
        check(label + " 次要文字 >= 3.0（能看清）", cDim >= 3.0, fmt(cDim));
        check(label + " 最弱文字 >= 2.2（可接受的下限）", cFaint >= 2.2, fmt(cFaint));
        check(label + " 主色文字 >= 3.0", cAccent >= 3.0, fmt(cAccent));
        check(label + " 主色按钮上的字 >= 3.0（可点按）", cOnAccent >= 3.0, fmt(cOnAccent));
        check(label + " 卡片描边可见 >= 1.05", cBorder >= 1.05, fmt(cBorder));

        // 状态色也要能看清
        double cWarn = contrast(p.pWarn, p.pSurface);
        double cDanger = contrast(p.pDanger, p.pSurface);
        double cOk = contrast(p.pOk, p.pSurface);
        double cInfo = contrast(p.pInfo, p.pSurface);
        double cFav = contrast(p.pFav, p.pSurface);
        System.out.println("  警示 " + fmt(cWarn) + "  危险 " + fmt(cDanger)
                + "  正常 " + fmt(cOk) + "  信息 " + fmt(cInfo) + "  收藏 " + fmt(cFav));
        check(label + " 警示色 >= 3.0", cWarn >= 3.0, fmt(cWarn));
        check(label + " 危险色 >= 3.0", cDanger >= 3.0, fmt(cDanger));
        check(label + " 正常色 >= 3.0", cOk >= 3.0, fmt(cOk));
        check(label + " 信息色 >= 3.0", cInfo >= 3.0, fmt(cInfo));
        check(label + " 收藏星 >= 3.0", cFav >= 3.0, fmt(cFav));

        // 层与层之间要有区分度，否则卡片和底会糊成一片
        double cSurfaceBg = contrast(p.pSurface, p.pBg);
        double cSurface2 = contrast(p.pSurface2, p.pSurface);
        double cSurface3 = contrast(p.pSurface3, p.pSurface2);
        System.out.println("  卡片/底 " + fmt(cSurfaceBg) + "  输入框/卡片 " + fmt(cSurface2)
                + "  三级/二级 " + fmt(cSurface3));
        check(label + " 卡片与底色有区分（>=1.05）", cSurfaceBg >= 1.05, fmt(cSurfaceBg));
        check(label + " 输入框与卡片有区分（>=1.03）", cSurface2 >= 1.03, fmt(cSurface2));

        // 水波纹颜色必须是半透明的，否则会盖住内容
        int rippleAlpha = (p.pRipple >>> 24) & 0xFF;
        check(label + " 水波纹是半透明（alpha 20~120）",
                rippleAlpha >= 20 && rippleAlpha <= 120, "alpha=" + rippleAlpha);
    }

    public static void main(String[] args) {
        System.out.println("=== 配色可读性审计（WCAG 对比度）===");

        Ui dark = Theme.buildDark();
        Ui light = Theme.buildLight();

        audit("夜钓（深色）", dark, true);
        audit("日间（浅色）", light, false);

        // ---------------- 两套主题必须真的不同
        System.out.println("\n=== 两套主题的差异 ===");
        check("底色不同", dark.pBg != light.pBg, hex(dark.pBg) + " vs " + hex(light.pBg));
        check("卡片色不同", dark.pSurface != light.pSurface,
                hex(dark.pSurface) + " vs " + hex(light.pSurface));
        check("文字色不同", dark.pText != light.pText,
                hex(dark.pText) + " vs " + hex(light.pText));
        check("深色主题确实更暗",
                luminance(dark.pBg) < luminance(light.pBg),
                fmt(luminance(dark.pBg)) + " < " + fmt(luminance(light.pBg)));
        check("深色主题卡片比文字暗（深色主题特征）",
                luminance(dark.pSurface) < luminance(dark.pText), "");
        check("浅色主题卡片比文字亮（浅色主题特征）",
                luminance(light.pSurface) > luminance(light.pText), "");

        // ---------------- apply 机制
        System.out.println("\n=== Ui.apply 同步机制 ===");
        Ui.apply(dark);
        int accentAfterDark = Ui.ACCENT;
        int bgAfterDark = Ui.BG;
        boolean isDarkAfterDark = Ui.IS_DARK;
        check("apply(深色) 后 Ui.ACCENT 生效",
                accentAfterDark == dark.pAccent, hex(accentAfterDark));
        check("apply(深色) 后 Ui.BG 生效", bgAfterDark == dark.pBg, hex(bgAfterDark));
        check("apply(深色) 后 Ui.IS_DARK = true", isDarkAfterDark, "");

        Ui.apply(light);
        check("apply(浅色) 后 Ui.ACCENT 变成浅色主色",
                Ui.ACCENT == light.pAccent, hex(Ui.ACCENT));
        check("apply(浅色) 后 Ui.BG 变成浅色底", Ui.BG == light.pBg, hex(Ui.BG));
        check("apply(浅色) 后 Ui.IS_DARK = false", !Ui.IS_DARK, "");
        check("切换后静态字段确实变了", accentAfterDark != Ui.ACCENT,
                hex(accentAfterDark) + " -> " + hex(Ui.ACCENT));

        // 每个字段都要同步，漏一个就会在某个界面露出错误颜色
        System.out.println("\n=== 逐字段同步完整性 ===");
        Ui.apply(dark);
        int[] d = {Ui.BG, Ui.SURFACE, Ui.SURFACE_2, Ui.SURFACE_3, Ui.BORDER,
                Ui.TEXT, Ui.TEXT_DIM, Ui.TEXT_FAINT, Ui.ACCENT, Ui.ACCENT_DARK,
                Ui.ON_ACCENT, Ui.WARN, Ui.DANGER, Ui.OK, Ui.INFO, Ui.FAV,
                Ui.RIPPLE, Ui.INPUT_BG, Ui.DIALOG_BG, Ui.STATUS_BAR};
        int[] l = {light.pBg, light.pSurface, light.pSurface2, light.pSurface3, light.pBorder,
                light.pText, light.pTextDim, light.pTextFaint, light.pAccent, light.pAccentDark,
                light.pOnAccent, light.pWarn, light.pDanger, light.pOk, light.pInfo, light.pFav,
                light.pRipple, light.pInputBg, light.pDialogBg, light.pStatusBar};
        Ui.apply(light);
        int[] got = {Ui.BG, Ui.SURFACE, Ui.SURFACE_2, Ui.SURFACE_3, Ui.BORDER,
                Ui.TEXT, Ui.TEXT_DIM, Ui.TEXT_FAINT, Ui.ACCENT, Ui.ACCENT_DARK,
                Ui.ON_ACCENT, Ui.WARN, Ui.DANGER, Ui.OK, Ui.INFO, Ui.FAV,
                Ui.RIPPLE, Ui.INPUT_BG, Ui.DIALOG_BG, Ui.STATUS_BAR};
        String[] names = {"BG", "SURFACE", "SURFACE_2", "SURFACE_3", "BORDER",
                "TEXT", "TEXT_DIM", "TEXT_FAINT", "ACCENT", "ACCENT_DARK",
                "ON_ACCENT", "WARN", "DANGER", "OK", "INFO", "FAV",
                "RIPPLE", "INPUT_BG", "DIALOG_BG", "STATUS_BAR"};
        int mismatch = 0;
        for (int i = 0; i < got.length; i++) {
            if (got[i] != l[i]) {
                System.out.println("    ✗ " + names[i] + " 未同步: " + hex(got[i])
                        + " 应为 " + hex(l[i]));
                mismatch++;
            }
        }
        check("20 个颜色字段全部随主题同步", mismatch == 0, "不同步 " + mismatch + " 个");

        // apply(null) 不应崩
        System.out.println("\n=== 健壮性 ===");
        int before = Ui.ACCENT;
        try {
            Ui.apply(null);
            check("apply(null) 不崩溃且不改变颜色", Ui.ACCENT == before, hex(Ui.ACCENT));
        } catch (Exception e) {
            check("apply(null) 不崩溃", false, e.toString());
        }

        // 说明：Theme.currentName()/displayName() 依赖 Preferences（安卓），
        // 在纯 JVM 下无法测试，那部分逻辑很薄，靠代码审查保证。

        System.out.println("\n================================");
        System.out.println("PASS=" + pass + "  FAIL=" + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }
}
