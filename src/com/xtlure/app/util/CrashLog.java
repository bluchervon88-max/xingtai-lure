package com.xtlure.app.util;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 崩溃记录器。
 *
 * 为什么需要：部分国产 ROM（实测 vivo）会把应用的 logcat 整个过滤掉，
 * crash 缓冲区里也查不到，导致线上崩溃完全无法定位。
 * 这里把未捕获异常的堆栈直接写到应用外部目录的文件里，
 * 用
 *   adb shell run-as com.xtlure.app cat files/crash.txt
 * （或 adb pull 应用外部目录）就能读到，不依赖 logcat。
 *
 * 文件只保留最近几次崩溃，不会无限增长。
 */
public final class CrashLog {

    private static final String FILE_NAME = "crash.txt";
    private static final int MAX_ENTRIES = 5;
    private static final String SEP = "\n=====================\n";

    private CrashLog() {
    }

    /** 在 Application 或每个 Activity 的 onCreate 最早处调用一次即可 */
    public static void install(final Context ctx) {
        final Thread.UncaughtExceptionHandler previous =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    write(ctx, t, e);
                } catch (Throwable ignored) {
                    // 记录失败绝不能掩盖原始崩溃
                }
                if (previous != null) {
                    previous.uncaughtException(t, e);
                }
            }
        });
    }

    private static void write(Context ctx, Thread t, Throwable e) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        pw.println("时间: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
                .format(new Date()));
        pw.println("线程: " + t.getName());
        pw.println("设备: " + Build.MANUFACTURER + " " + Build.MODEL
                + " / Android " + Build.VERSION.RELEASE
                + " (API " + Build.VERSION.SDK_INT + ")");
        pw.println("异常: " + e.getClass().getName() + ": " + e.getMessage());
        pw.println("堆栈:");
        e.printStackTrace(pw);
        pw.flush();

        String entry = sw.toString();

        try {
            append(ctx, entry);
        } catch (Throwable ignored) {
            // 记录失败绝不能掩盖原始崩溃
        }
    }

    /** 崩溃日志文件路径，供界面展示"反馈"用 */
    public static File file(Context ctx) {
        File dir = ctx.getExternalFilesDir(null);
        if (dir == null) {
            dir = ctx.getFilesDir();
        }
        return new File(dir, FILE_NAME);
    }

    /** 是否为调试构建。诊断信息只在调试构建下写入，避免正式包留下调试痕迹。 */
    public static boolean isDebugBuild(Context ctx) {
        try {
            return (ctx.getApplicationInfo().flags
                    & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * 记录一个流程节点（启动诊断用）。
     *
     * 真机排查时最有用的信息往往不是"崩在哪"，而是"走到哪一步为止"——
     * 尤其当 ROM 把日志吞掉、又拿不到堆栈的时候。
     *
     * 仅在调试构建下写入，正式包不产生任何文件。
     */
    public static void step(Context ctx, String note) {
        if (!isDebugBuild(ctx)) {
            return;
        }
        try {
            append(ctx, "[" + new SimpleDateFormat("HH:mm:ss.SSS", Locale.CHINA)
                    .format(new Date()) + "] 步骤: " + note + "\n");
        } catch (Throwable ignored) {
        }
    }

    /**
     * 追加一段诊断文本（**不受调试构建限制**）。
     *
     * 与 step() 的区别：step() 只在 debuggable 构建写入，因为它记录的是启动流程，
     * 正式包不该留调试痕迹。而这里是"用户报告了界面异常、需要拿到实测尺寸"的场景，
     * 恰恰要用**和用户同一份构建**才准 —— 所以无条件写入。
     * 调用点必须是低频的（只在用户主动打开某个界面时触发），不能放进绘制或滚动回调。
     */
    public static void appendDiag(Context ctx, String note) {
        try {
            append(ctx, "\n==== 界面诊断 " + new SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date()) + " ====\n"
                    + note + "\n");
        } catch (Throwable ignored) {
        }
    }

    /**
     * 手动记录一次异常（用于 catch 住但需要留证的场景，例如某块界面构建失败）。
     * 与全局崩溃处理写同一个文件，方便一次取回全部线索。
     *
     * @param where 出错位置，例如 "MainActivity.refreshHeader"
     */
    public static void record(Context ctx, String where, Throwable e) {
        try {
            java.io.StringWriter sw = new java.io.StringWriter();
            java.io.PrintWriter pw = new java.io.PrintWriter(sw);
            pw.println("时间: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
                    .format(new Date()));
            pw.println("位置: " + where + "   （非崩溃，已被 catch）");
            pw.println("设备: " + Build.MANUFACTURER + " " + Build.MODEL
                    + " / Android " + Build.VERSION.RELEASE
                    + " (API " + Build.VERSION.SDK_INT + ")");
            pw.println("异常: " + e.getClass().getName() + ": " + e.getMessage());
            pw.println("堆栈:");
            e.printStackTrace(pw);
            pw.flush();
            append(ctx, sw.toString());
        } catch (Throwable ignored) {
        }
    }

    /** 追加一条记录（保留最近 MAX_ENTRIES 条），同时写私有目录和公共下载目录 */
    private static synchronized void append(Context ctx, String entry) throws Exception {
        String content = null;
        try {
            File f = file(ctx);
            String old = "";
            if (f.exists()) {
                byte[] buf = new byte[(int) Math.min(f.length(), 60000)];
                java.io.FileInputStream in = new java.io.FileInputStream(f);
                int n = in.read(buf);
                in.close();
                if (n > 0) {
                    old = new String(buf, 0, n, "UTF-8");
                }
            }
            String[] parts = old.split(java.util.regex.Pattern.quote(SEP));
            StringBuilder sb = new StringBuilder();
            sb.append(entry).append(SEP);
            int kept = 0;
            for (String p : parts) {
                if (p.trim().isEmpty()) {
                    continue;
                }
                if (kept >= MAX_ENTRIES - 1) {
                    break;
                }
                sb.append(p).append(SEP);
                kept++;
            }
            content = sb.toString();

            FileOutputStream out = new FileOutputStream(f, false);
            out.write(content.getBytes("UTF-8"));
            out.flush();
            out.close();
        } catch (Throwable ignored) {
        }

        // 再往公共下载目录写一份：那里 adb / 文件管理器都能直接读，
        // 不需要应用开启 debuggable，排查真机问题时省事得多。
        // 写失败不影响主流程（各版本存储权限策略不同，尽力而为）。
        try {
            File downloads = android.os.Environment
                    .getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS);
            if (downloads != null) {
                if (!downloads.exists()) {
                    downloads.mkdirs();
                }
                if (downloads.isDirectory() && downloads.canWrite()) {
                    File pub = new File(downloads, "xtlure-crash.txt");
                    FileOutputStream out2 = new FileOutputStream(pub, false);
                    out2.write((content == null ? entry : content).getBytes("UTF-8"));
                    out2.flush();
                    out2.close();
                }
            }
        } catch (Throwable ignored) {
        }
    }
}
