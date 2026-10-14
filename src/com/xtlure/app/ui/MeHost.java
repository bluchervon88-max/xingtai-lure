package com.xtlure.app.ui;

/**
 * 「我的」页需要的宿主能力：文件导入导出。
 *
 * 独立文件，避免 MeFragment 与 MainActivity 之间的编译期循环引用。
 */
public interface MeHost {

    /** 通过系统文件选择器保存完整 JSON 备份 */
    void exportBackupViaPicker();

    /** 导出 Markdown 钓点清单 */
    void exportChecklist();

    /** 从备份文件导入 */
    void importFromPicker();
}
