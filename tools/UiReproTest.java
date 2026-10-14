import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 用真实 Android framework（android-all）在 JVM 里跑 MainActivity.onCreate，
 * 目的是拿到手机上拿不到的异常堆栈。
 *
 * 背景：vivo ROM 会把应用 logcat 整个过滤掉，crash 缓冲区也查不到，
 * 而真机现象是"顶栏只渲染出标题，标签栏与列表全无"，说明 onCreate 中途抛异常。
 * 这里把同一个 Activity 在本地跑起来，异常会带着完整堆栈抛出。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class UiReproTest {

    @Test
    public void mainActivityOnCreate() throws Exception {
        System.out.println("=== 尝试创建 MainActivity ===");
        Activity a = null;
        try {
            a = Robolectric.buildActivity(
                    Class.forName("com.xtlure.app.ui.MainActivity").asSubclass(Activity.class))
                    .create().get();
            System.out.println("  onCreate 完成，无异常");
        } catch (Throwable t) {
            System.out.println("  ✗ onCreate 抛异常！");
            dump(t);
        }
        if (a == null) {
            return;
        }

        // 打印视图树，看有哪些子视图真的建出来了
        System.out.println("\n=== 视图树 ===");
        View root = a.getWindow().getDecorView();
        printTree(root, 0);

        // 触发 onResume（真机上顶栏是 onResume 里重建的）
        System.out.println("\n=== 触发 onResume ===");
        try {
            Method m = Activity.class.getDeclaredMethod("onResume");
            m.setAccessible(true);
            m.invoke(a);
            System.out.println("  onResume 完成，无异常");
        } catch (Throwable t) {
            System.out.println("  ✗ onResume 抛异常！");
            dump(t);
        }
    }

    private void dump(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        System.out.println("  根本原因: " + cause.getClass().getName() + ": " + cause.getMessage());
        System.out.println("  堆栈:");
        StackTraceElement[] st = cause.getStackTrace();
        int shown = 0;
        for (StackTraceElement e : st) {
            if (e.getClassName().startsWith("com.xtlure")) {
                System.out.println("    at " + e);
                shown++;
            }
        }
        if (shown == 0) {
            for (int i = 0; i < Math.min(st.length, 20); i++) {
                System.out.println("    at " + st[i]);
            }
        }
    }

    private void printTree(View v, int depth) {
        if (v == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            sb.append("  ");
        }
        sb.append(v.getClass().getSimpleName());
        if (v instanceof android.widget.TextView) {
            CharSequence t = ((android.widget.TextView) v).getText();
            if (t != null && t.length() > 0) {
                sb.append(" 「").append(t).append("」");
            }
        }
        System.out.println(sb);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                printTree(g.getChildAt(i), depth + 1);
            }
        }
    }
}
