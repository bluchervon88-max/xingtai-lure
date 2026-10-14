package com.xtlure.app.ui;

/**
 * 可以响应"重新加载"的页面。
 *
 * 单独成一个文件而不是嵌在 MainActivity 里，是为了避免循环继承：
 * 各 Fragment 实现本接口，而 MainActivity 又引用这些 Fragment，
 * 若接口嵌在 MainActivity 中就会形成编译期循环。
 */
public interface Refreshable {
    void refresh();
}
