package com.xtlure.app.ui;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.xtlure.app.model.FishingSpot;
import com.xtlure.app.util.ThemeHelper;
import com.xtlure.app.util.Ui;

/** 关于页：版本、数据说明、坐标系说明、免责声明。 */
public class AboutActivity extends Activity {

    /** 从 PackageManager 读取真实版本号，保证和已安装的 APK 一致 */
    private String appVersion() {
        try {
            android.content.pm.PackageInfo pi = getPackageManager()
                    .getPackageInfo(getPackageName(), 0);
            String name = pi.versionName == null ? "?" : pi.versionName;
            return name + "（versionCode " + pi.versionCode + "）";
        } catch (Exception e) {
            return "未知";
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeHelper.prepare(this);

        LinearLayout col = Ui.column(this);
        col.setBackgroundColor(Ui.BG);

        // 顶栏
        LinearLayout bar = Ui.row(this);
        bar.setBackgroundColor(Ui.SURFACE);
        bar.setPadding(Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        TextView back = Ui.text(this, "‹", 26, Ui.TEXT);
        back.setGravity(Gravity.CENTER);
        back.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        bar.addView(back);
        TextView title = Ui.text(this, "关于", 17, Ui.TEXT);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        bar.addView(title);
        col.addView(bar, Ui.lpMatch());

        ScrollView scroll = new ScrollView(this);
        LinearLayout body = Ui.column(this);
        body.setPadding(Ui.dp(this, 16), Ui.dp(this, 10), Ui.dp(this, 16), Ui.dp(this, 40));
        scroll.addView(body);
        col.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(col);

        // 头部
        TextView app = Ui.text(this, "🎣  邢台路亚", 24, Ui.TEXT);
        app.setTypeface(app.getTypeface(), android.graphics.Typeface.BOLD);
        body.addView(app);

        TextView ver = Ui.text(this, "版本 " + appVersion() + "　·　离线优先的本地钓点本",
                12, Ui.TEXT_FAINT);
        ver.setPadding(0, Ui.dp(this, 6), 0, 0);
        body.addView(ver);

        addCard(body, "这个 App 做什么",
                "记录河北省邢台市范围内的路亚钓点与自己的作钓流水。\n\n"
                        + "核心是三件事：\n"
                        + "1. 把钓点记住 —— 名称、坐标、对象鱼、有效拟饵、到达方式；\n"
                        + "2. 把每次出钓记住 —— 时间、用饵、钓法、渔获、天气气压水温；\n"
                        + "3. 把这些数据随时带走 —— 导出 JSON 备份与 Markdown 清单。\n\n"
                        + "在「我的」页可以切换夜钓（深色）与日间（浅色）两套配色。");

        addCard(body, "地图为什么是离线的",
                "钓点分布图用的是**编译进 APK 的真实地理数据**，不是联网地图。\n\n"
                        + "数据来源：OpenStreetMap（© OpenStreetMap 贡献者，ODbL 许可）。\n"
                        + "内容包括邢台及周边的水系轮廓（166 个水体）、区县行政边界、"
                        + "主要河道中心线，总计约 5.3 万个顶点，压缩后 208 KB。\n\n"
                        + "为什么不用高德 / 百度 / 腾讯的地图 SDK：\n"
                        + "· 它们都需要申请开发者账号并绑定包名的 API Key，我这边无法代你申请；\n"
                        + "· 都需要 INTERNET 权限，一张瓦片都下载不下来就没法显示；\n"
                        + "· 都需要联网，而水库边经常没信号 —— 恰恰是最需要看地图的时候。\n\n"
                        + "本 App 的定位是「离线优先、不申请网络权限」，接了 SDK 就把这一点推翻了。\n"
                        + "所以要商用地图时，用钓点的「导航」功能跳转到手机上的高德或百度地图，\n"
                        + "由对方 App 去联网取图，本 App 依旧不需要任何权限。\n\n"
                        + "矢量绘制的好处：存的是顶点而不是位图，放到多大都不会糊，"
                        + "体积还比瓦片包小两个数量级。");

        addCard(body, "为什么要手填气压",
                "路亚对气压非常敏感，但加压天气接口基本都要申请 key，\n"
                        + "而本 App 刻意不申请网络权限（为了在水库边没信号也能用）。\n\n"
                        + "所以气压做成手填快捷项：手机上任何一个天气 App 都能看到气压值，\n"
                        + "记渔获时顺手填一下，几次之后你就能看出——\n"
                        + "同一个钓点，气压 1020 和 995 的鱼口差别到底有多大。\n\n"
                        + "这是别人给不了你的数据，因为它只存在于你自己的记录里。");

        addCard(body, "关于坐标：为什么有三种坐标系",
                "这是国内做地图相关 App 最容易踩的坑，说明白很重要。\n\n"
                        + "· GPS 原始坐标（WGS-84）：手机定位芯片给出的真实经纬度，国际标准。\n"
                        + "· 火星坐标（GCJ-02）：国内法规要求的地图加密坐标，高德、腾讯、\n"
                        + "  以及绝大多数国内地图使用它。与 WGS-84 相差约 300～600 米。\n"
                        + "· 百度坐标（BD-09）：百度在地图 GCJ-02 基础上又加了一层偏移。\n\n"
                        + "本 App 数据库里**只存 WGS-84**，展示或导航时实时换算。\n"
                        + "这样做的好处：以后换地图、换手机、导出到别的工具，都不会产生累积偏移。\n\n"
                        + "在钓点详情页点「复制坐标」，会一次性给出三种坐标系和一条高德链接，\n"
                        + "直接粘到微信里发给钓友也不会跑偏。");

        addCard(body, "预置数据的可信度",
                "App 内置了一批邢台境内的水域作为起点，但请注意：\n\n"
                        + "· 这些是**公开资料整理**的结果，未经过实地核对；\n"
                        + "· 每个钓点都标了「资料可信度」，未核实的会明确标出来；\n"
                        + "· 坐标如果是推算的，会标注为近似值。\n\n"
                        + "正确用法：把它当作一份**待验证的线索清单**，\n"
                        + "你亲自去过的钓点，在编辑页把可信度改成「本人实地确认」，\n"
                        + "再用页面上的「取坐标」按钮用 GPS 校正一次坐标，它就真正属于你了。\n\n"
                        + "水域情况会变（水位、鱼情、管理政策），任何一条信息都请以现场为准。");

        addCard(body, "⚠ 合规与安全",
                "· 出钓前请确认禁渔期与水域管理规定。邢台市禁渔期为 "
                        + FishingSpot.CLOSED_SEASON_TEXT + "；\n"
                        + "· 通告原文的禁止类型是「除钓具之外的所有作业方式」——"
                        + "即钓具垂钓被豁免，但严打「以休闲垂钓为幌子」的一人多杆、"
                        + "多钩线钓、锚鱼行为。路亚三本钩硬饵在禁渔期内有被按"
                        + "「多钩线钓」认定的风险，稳妥做法是期内改单钩软虫或亮片；\n"
                        + "· 网上流传的「一人一杆一线一钩」并未找到官方条文出处，"
                        + "本 App 不作为规定引用；\n"
                        + "· 饮用水源地、水产种质资源保护区、南水北调干渠等区域禁止垂钓，"
                        + "不要抱侥幸心理。南水北调干渠沿线全程围网、有监控，"
                        + "且邻近高压线，碳素竿导电；\n"
                        + "· 禁渔期内使用网具可能构成刑事犯罪。2022 年南和区有人用撒网"
                        + "捕 4 条白鲢，被以《刑法》第 340 条非法捕捞水产品罪处理；\n"
                        + "· 发现违法捕捞可拨打市级举报电话 0319-3681055 / 0319-3160198（24 小时），"
                        + "各县举报电话见「我的 → 合规须知」；\n"
                        + "· 水库、河道水下地形复杂，独自夜钓与涉水都有风险，穿救生衣；\n"
                        + "· 本 App 只是个人记录工具，不构成任何作钓许可或安全保证。");

        addCard(body, "隐私与数据",
                "· 所有数据存在本机 SQLite 数据库，App 本身不申请网络权限；\n"
                        + "· 定位只在你自己点击「定位 / 取坐标」时触发，坐标只写在本机；\n"
                        + "· 只有你主动点「导航」时，才会把坐标交给地图 App；\n"
                        + "· 卸载 App 会一起删掉数据，重要数据请先导出备份；\n"
                        + "· 自己实地用 GPS 校正过的钓点会被标记为「本人实地确认」，"
                        + "以后升级再也不会被预置资料覆盖。");

        addCard(body, "技术说明",
                "· 纯 Java + 系统 framework 实现，不依赖任何第三方库，APK 只有几十 KB；\n"
                        + "· 坐标转换与日出日落、月相为本地算法，无网络也能用；\n"
                        + "· 数据库只增不改结构，后续版本升级不会丢数据。");

        TextView tail = Ui.text(this, "钓鱼是为了放松，别为了鱼获较劲。\n祝不空军。",
                12, Ui.TEXT_FAINT);
        tail.setGravity(Gravity.CENTER);
        tail.setPadding(0, Ui.dp(this, 24), 0, 0);
        tail.setLineSpacing(Ui.dp(this, 3), 1f);
        body.addView(tail);
    }

    private void addCard(LinearLayout parent, String title, String text) {
        LinearLayout card = Ui.card(this);
        LinearLayout.LayoutParams lp = Ui.lpMatch();
        lp.topMargin = Ui.dp(this, 12);
        card.setLayoutParams(lp);

        TextView t = Ui.text(this, title, 14, Ui.ACCENT);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        card.addView(t);

        TextView b = Ui.text(this, text, 13, Ui.TEXT_DIM);
        LinearLayout.LayoutParams blp = Ui.lpMatch();
        blp.topMargin = Ui.dp(this, 8);
        b.setLayoutParams(blp);
        b.setLineSpacing(Ui.dp(this, 4), 1f);
        card.addView(b);

        parent.addView(card);
    }
}
