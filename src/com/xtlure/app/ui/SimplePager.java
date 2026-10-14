package com.xtlure.app.ui;

import android.app.Fragment;
import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * 极简三页容器，用来替代 ViewPager。
 *
 * 为什么要自己写：ViewPager 属于 AndroidX / support 库，而本工程刻意不引入任何
 * 外部依赖（没有 Gradle 依赖解析）。这里只需要"三页来回切 + 记住页面状态"，
 * 用 Fragment show/hide 就够了，比 ViewPager 更省内存也更简单。
 *
 * 用 show/hide 而不是 replace：切页时列表滚动位置、搜索框内容都保留。
 * 对配置变更（旋转屏幕）做了防护：已存在的 fragment 不会被重复 add。
 */
public class SimplePager extends FrameLayout {

    /** 页面切换监听 */
    public interface OnPageChangeListener {
        void onPageSelected(int position);
    }

    /** 页面工厂：每次需要时按页号创建 Fragment */
    public interface PageFactory {
        Fragment create(int position);
    }

    private final List<Fragment> pages = new ArrayList<>();
    private PageFactory factory;
    private OnPageChangeListener listener;
    private FragmentManager fm;
    private int current = 0;
    private final int containerId = 0x7f0f0002;
    private boolean committed = false;

    public SimplePager(Context context) {
        super(context);
        setId(containerId);
    }

    public SimplePager(Context context, AttributeSet attrs) {
        super(context, attrs);
        setId(containerId);
    }

    public void setup(FragmentManager fm, PageFactory factory, int pageCount) {
        this.fm = fm;
        this.factory = factory;
        pages.clear();
        for (int i = 0; i < pageCount; i++) {
            pages.add(null);
        }
    }

    public int getPageCount() {
        return pages.size();
    }

    public int getCurrentItem() {
        return current;
    }

    public void setOnPageChangeListener(OnPageChangeListener l) {
        this.listener = l;
    }

    /**
     * 附着到窗口后调用：确保所有页面都已 add，非当前页隐藏。
     * 已在 FragmentManager 中恢复的页面会被复用，不会重复添加。
     */
    public void commit() {
        if (committed || fm == null || pages.isEmpty()) {
            return;
        }
        committed = true;

        FragmentTransaction tx = fm.beginTransaction();
        for (int i = 0; i < pages.size(); i++) {
            String tag = tagFor(i);
            Fragment existing = fm.findFragmentByTag(tag);
            Fragment f = existing != null ? existing : factory.create(i);
            pages.set(i, f);
            if (!f.isAdded() && existing == null) {
                tx.add(containerId, f, tag);
                if (i != current) {
                    tx.hide(f);
                }
            }
        }
        tx.commitAllowingStateLoss();

        // 提交后统一校正可见性
        applyVisibility();
    }

    private String tagFor(int index) {
        return "page:" + index;
    }

    public void setCurrentItem(int index) {
        setCurrentItem(index, true);
    }

    public void setCurrentItem(int index, boolean notify) {
        if (index < 0 || index >= pages.size()) {
            return;
        }
        if (index != current) {
            current = index;
            applyVisibility();
        }
        if (notify && listener != null) {
            listener.onPageSelected(current);
        }
    }

    private void applyVisibility() {
        if (fm == null) {
            return;
        }
        FragmentTransaction tx = fm.beginTransaction();
        boolean any = false;
        for (int i = 0; i < pages.size(); i++) {
            Fragment f = pages.get(i);
            if (f == null || !f.isAdded()) {
                continue;
            }
            if (i == current) {
                tx.show(f);
            } else {
                tx.hide(f);
            }
            any = true;
        }
        if (any) {
            tx.commitAllowingStateLoss();
        }
    }

    /** 当前页 Fragment */
    public Fragment currentFragment() {
        if (current < 0 || current >= pages.size()) {
            return null;
        }
        Fragment f = pages.get(current);
        if (f != null) {
            return f;
        }
        return fm == null ? null : fm.findFragmentByTag(tagFor(current));
    }

    /** 指定页 Fragment */
    public Fragment fragmentAt(int index) {
        if (index < 0 || index >= pages.size()) {
            return null;
        }
        Fragment f = pages.get(index);
        if (f != null) {
            return f;
        }
        return fm == null ? null : fm.findFragmentByTag(tagFor(index));
    }
}
