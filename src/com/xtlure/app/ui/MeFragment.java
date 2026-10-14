package com.xtlure.app.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Fragment;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.xtlure.app.data.Db;
import com.xtlure.app.model.CatchRecord;
import com.xtlure.app.model.FishingSpot;
import com.xtlure.app.util.MapLauncher;
import com.xtlure.app.util.Prefs;
import com.xtlure.app.util.Theme;
import com.xtlure.app.util.ThemeHelper;
import com.xtlure.app.util.Ui;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 我的页：数据统计、导出/导入、地图偏好、关于。
 * 这里是"数据在你手里"的体现：能导出、能备份、能一键清空。
 */
public class MeFragment extends Fragment implements Refreshable {

    private LinearLayout root;
    private MeHost host;
    private Activity act;

    @Override
    public View onCreateView(android.view.LayoutInflater inf, ViewGroup parent,
                             Bundle savedInstanceState) {
        act = getActivity();
        host = (MeHost) act;

        ScrollView scroll = new ScrollView(act);
        root = Ui.column(act);
        root.setPadding(Ui.dp(act, 14), Ui.dp(act, 8), Ui.dp(act, 14), Ui.dp(act, 40));
        scroll.addView(root);
        return scroll;
    }

    @Override
    public void onResume() {
        super.onResume();
        render();
    }

    @Override
    public void refresh() {
        render();
    }

    // ================================================================ 渲染

    private void render() {
        if (root == null || act == null) {
            return;
        }
        root.removeAllViews();

        Db db = Db.get(act);
        List<FishingSpot> spots = db.listSpots();
        List<CatchRecord> catches = db.listCatches();

        // ---------------- 统计卡
        LinearLayout stat = Ui.card(act);
        stat.addView(Ui.section(act, "数据统计"));

        int withCoord = 0;
        int favorites = 0;
        int banned = 0;
        java.util.LinkedHashSet<String> districtSet = new java.util.LinkedHashSet<>();
        Map<String, Integer> byType = new LinkedHashMap<>();
        for (FishingSpot s : spots) {
            if (s.isHasCoord()) {
                withCoord++;
            }
            if (s.isFavorite()) {
                favorites++;
            }
            if (s.getStatus() == FishingSpot.STATUS_BANNED) {
                banned++;
            }
            if (!s.getDistrict().isEmpty()) {
                districtSet.add(s.getDistrict());
            }
            String t = FishingSpot.waterTypeName(s.getWaterType());
            Integer c = byType.get(t);
            byType.put(t, c == null ? 1 : c + 1);
        }

        int totalFish = 0;
        int blankCount = 0;
        long earliest = Long.MAX_VALUE;
        for (CatchRecord r : catches) {
            totalFish += r.getCount();
            if (r.isBlank()) {
                blankCount++;
            }
            if (r.getTime() < earliest) {
                earliest = r.getTime();
            }
        }

        stat.addView(bigStat(spots.size() + " 个钓点", withCoord + " 个有坐标 · "
                + favorites + " 个收藏" + (banned > 0 ? " · " + banned + " 个禁钓" : "")));

        stat.addView(bigStat(catches.size() + " 次作钓", "累计 " + totalFish + " 尾 · 空军 "
                + blankCount + " 次" + (earliest == Long.MAX_VALUE ? ""
                : " · 最早 " + new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
                .format(new Date(earliest)))));

        StringBuilder typeText = new StringBuilder();
        for (Map.Entry<String, Integer> e : byType.entrySet()) {
            if (typeText.length() > 0) {
                typeText.append("　");
            }
            typeText.append(e.getKey()).append(' ').append(e.getValue());
        }
        if (typeText.length() > 0) {
            stat.addView(Ui.section(act, "按水域类型"));
            TextView t = Ui.text(act, typeText.toString(), 13, Ui.TEXT_DIM);
            t.setLineSpacing(Ui.dp(act, 3), 1f);
            stat.addView(t);
        }

        root.addView(stat);

        // ---------------- 成就徽章（把"用得多"变成看得见的进度）
        LinearLayout badgeCard = Ui.card(act);
        LinearLayout.LayoutParams bcLp = Ui.lpMatch();
        bcLp.topMargin = Ui.dp(act, 12);
        badgeCard.setLayoutParams(bcLp);
        badgeCard.addView(Ui.section(act, "成就"));
        // 成就只统计用户自己的行为：预置钓点（seed_version>=1）不计入，
        // 否则新装用户一进来就白拿好几枚，成就失去意义。
        BadgeWall.Stats bs = new BadgeWall.Stats();
        Db sdb = Db.get(act);
        bs.userSpots = sdb.countUserSpots();
        bs.userSpotsWithCoord = sdb.countUserSpotsWithCoord();
        bs.visitedSpots = sdb.countVisitedSpots();
        bs.catches = sdb.countCatches();
        bs.successfulCatches = sdb.countSuccessfulCatches();
        bs.distinctSpecies = sdb.countDistinctSpecies();
        bs.distinctLures = sdb.countDistinctLures();
        bs.fishingDays = sdb.countFishingDays();
        for (CatchRecord r : catches) {
            bs.totalFish += r.getCount();
        }
        badgeCard.addView(BadgeWall.build(act, bs));
        root.addView(badgeCard);

        // ---------------- 统计洞察
        LinearLayout insight = Ui.card(act);
        LinearLayout.LayoutParams inLp = Ui.lpMatch();
        inLp.topMargin = Ui.dp(act, 12);
        insight.setLayoutParams(inLp);
        insight.addView(Ui.section(act, "作钓洞察"));
        if (!addInsights(insight, catches, db)) {
            insight.addView(Ui.text(act,
                    "多记几次作钓，这里会自动算出哪种拟饵、哪个月份、哪个钓点对你最管用。",
                    13, Ui.TEXT_DIM));
        }
        root.addView(insight);

        // ---------------- 合规须知（来自预置数据里的官方通告原文引用）
        LinearLayout compCard = Ui.card(act);
        LinearLayout.LayoutParams ccLp = Ui.lpMatch();
        ccLp.topMargin = Ui.dp(act, 12);
        compCard.setLayoutParams(ccLp);

        compCard.addView(Ui.section(act, "⚖ 合规须知"));
        boolean hasComp = addComplianceNotes(compCard);
        if (!hasComp) {
            compCard.addView(Ui.text(act, "暂无合规信息。出钓前请自行确认当地禁渔期规定。",
                    13, Ui.TEXT_DIM));
        }
        root.addView(compCard);

        // ---------------- 数据管理
        LinearLayout dataCard = Ui.card(act);
        LinearLayout.LayoutParams dcLp = Ui.lpMatch();
        dcLp.topMargin = Ui.dp(act, 12);
        dataCard.setLayoutParams(dcLp);

        dataCard.addView(Ui.section(act, "数据备份与导出"));
        dataCard.addView(menuRow("💾", "保存完整备份到手机", "生成 JSON 文件，含全部钓点与记录",
                new Runnable() {
                    @Override
                    public void run() {
                        host.exportBackupViaPicker();
                    }
                }));
        dataCard.addView(divider());
        dataCard.addView(menuRow("📄", "导出钓点清单（Markdown）",
                "人可读的表格文本，可发微信/打印/贴笔记", new Runnable() {
                    @Override
                    public void run() {
                        host.exportChecklist();
                    }
                }));
        dataCard.addView(divider());
        dataCard.addView(menuRow("📥", "从备份文件恢复", "选择之前导出的 JSON 文件导入",
                new Runnable() {
                    @Override
                    public void run() {
                        host.importFromPicker();
                    }
                }));

        dataCard.addView(Ui.section(act, "危险操作"));
        dataCard.addView(menuRow("🗑", "清空全部数据", "删除所有钓点和记录，不可恢复",
                new Runnable() {
                    @Override
                    public void run() {
                        confirmWipe();
                    }
                }));

        root.addView(dataCard);

        // ---------------- 外观
        LinearLayout themeCard = Ui.card(act);
        LinearLayout.LayoutParams tcLp = Ui.lpMatch();
        tcLp.topMargin = Ui.dp(act, 12);
        themeCard.setLayoutParams(tcLp);

        themeCard.addView(Ui.section(act, "外观"));
        themeCard.addView(menuRow("🌗", "配色主题", Theme.displayName()
                + "（点此切换，切换后界面会重载）", new Runnable() {
            @Override
            public void run() {
                pickTheme();
            }
        }));

        root.addView(themeCard);

        // ---------------- 地图
        LinearLayout mapCard = Ui.card(act);
        LinearLayout.LayoutParams mcLp = Ui.lpMatch();
        mcLp.topMargin = Ui.dp(act, 12);
        mapCard.setLayoutParams(mcLp);

        mapCard.addView(Ui.section(act, "地图偏好"));
        String cur = Prefs.getString(act, Prefs.K_MAP_PROVIDER, Prefs.MAP_AUTO);
        mapCard.addView(menuRow("🧭", "默认导航方式", mapProviderName(cur), new Runnable() {
            @Override
            public void run() {
                pickMapProvider();
            }
        }));

        StringBuilder installed = new StringBuilder();
        installed.append(MapLauncher.isAmapInstalled(act) ? "高德 ✓" : "高德 ✗").append("　");
        installed.append(MapLauncher.isBaiduInstalled(act) ? "百度 ✓" : "百度 ✗").append("　");
        installed.append(MapLauncher.isTencentInstalled(act) ? "腾讯 ✓" : "腾讯 ✗");
        TextView inst = Ui.text(act, "检测到已安装：" + installed, 11, Ui.TEXT_FAINT);
        inst.setPadding(0, Ui.dp(act, 6), 0, 0);
        mapCard.addView(inst);

        root.addView(mapCard);

        // ---------------- 关于
        LinearLayout aboutCard = Ui.card(act);
        LinearLayout.LayoutParams acLp = Ui.lpMatch();
        acLp.topMargin = Ui.dp(act, 12);
        aboutCard.setLayoutParams(acLp);

        aboutCard.addView(Ui.section(act, "关于"));
        aboutCard.addView(menuRow("ℹ️", "关于邢台路亚", "版本、数据说明、坐标系与免责声明",
                new Runnable() {
                    @Override
                    public void run() {
                        startActivity(new Intent(act, AboutActivity.class));
                    }
                }));

        root.addView(aboutCard);

        TextView footer = Ui.text(act, "所有数据只存在这台手机上，App 不联网、不上传。\n"
                + "出钓前请自行确认当地水域管理规定与禁渔期。", 11, Ui.TEXT_FAINT);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, Ui.dp(act, 18), 0, 0);
        footer.setLineSpacing(Ui.dp(act, 2), 1f);
        root.addView(footer);
    }

    // ================================================================ 小控件

    private View bigStat(String title, String sub) {
        LinearLayout v = Ui.column(act);
        v.setPadding(0, Ui.dp(act, 6), 0, Ui.dp(act, 6));

        TextView t = Ui.text(act, title, 18, Ui.TEXT);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        v.addView(t);

        TextView s = Ui.text(act, sub, 12, Ui.TEXT_DIM);
        s.setPadding(0, Ui.dp(act, 3), 0, 0);
        v.addView(s);
        return v;
    }

    private View menuRow(String icon, String title, String sub, final Runnable onClick) {
        LinearLayout r = Ui.row(act);
        r.setPadding(0, Ui.dp(act, 11), 0, Ui.dp(act, 11));
        r.setBackground(Ui.ripple(0x00000000, 8, Ui.RIPPLE));

        TextView ic = Ui.text(act, icon, 17, Ui.TEXT_DIM);
        ic.setMinWidth(Ui.dp(act, 32));
        r.addView(ic);

        LinearLayout txt = Ui.column(act);
        TextView t = Ui.text(act, title, 14, Ui.TEXT);
        txt.addView(t);
        if (sub != null && !sub.isEmpty()) {
            TextView s = Ui.text(act, sub, 11, Ui.TEXT_FAINT);
            s.setPadding(0, Ui.dp(act, 3), 0, 0);
            txt.addView(s);
        }
        r.addView(txt, Ui.weighted(1f));

        TextView arrow = Ui.text(act, "›", 18, Ui.TEXT_FAINT);
        r.addView(arrow);

        r.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onClick.run();
            }
        });
        return r;
    }

    private View divider() {
        View v = new View(act);
        v.setBackgroundColor(Ui.BORDER);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1));
        return v;
    }

    // ================================================================ 合规须知

    /**
     * 从预置数据里读顶层 complianceNotes 并展示。
     *
     * 这些是从各级政府通告原文里摘出来的，比我自己总结可靠 ——
     * 特别是"一人一杆一线一钩"这种网上流传但不一定有条文出处的说法，
     * 这里明确标注"未找到官方出处"，避免把猜测当法规用。
     */
    private boolean addComplianceNotes(LinearLayout parent) {
        try {
            String text = com.xtlure.app.ui.Seed.readAsset(act, "spots_seed.json");
            if (text == null) {
                return false;
            }
            java.util.Map<String, Object> root =
                    com.xtlure.app.util.Json.obj(com.xtlure.app.util.Json.parse(text));
            if (root == null) {
                return false;
            }
            java.util.List<Object> notes =
                    com.xtlure.app.util.Json.arr(root.get("complianceNotes"));
            if (notes == null || notes.isEmpty()) {
                return false;
            }
            for (Object o : notes) {
                if (o == null) {
                    continue;
                }
                String line = String.valueOf(o);

                // 抽取【标题】作为小标题，剩下作为正文
                String title = "";
                String bodyText = line;
                if (line.startsWith("【")) {
                    int end = line.indexOf('】');
                    if (end > 0) {
                        title = line.substring(1, end);
                        bodyText = line.substring(end + 1).trim();
                    }
                }
                // 把"来源 http..."单独拎出来，做成可点的链接
                String sourceUrl = "";
                int si = bodyText.lastIndexOf("来源 ");
                if (si >= 0) {
                    String tail = bodyText.substring(si + 3).trim();
                    if (tail.startsWith("http")) {
                        sourceUrl = tail.split("\\s+")[0];
                        bodyText = bodyText.substring(0, si).trim();
                    }
                }

                if (!title.isEmpty()) {
                    TextView t = Ui.text(act, "· " + title, 13, Ui.ACCENT);
                    t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
                    t.setPadding(0, Ui.dp(act, 10), 0, 0);
                    parent.addView(t);
                }

                TextView b = Ui.text(act, bodyText, 12, Ui.TEXT_DIM);
                b.setLineSpacing(Ui.dp(act, 3), 1f);
                b.setPadding(0, Ui.dp(act, 4), 0, 0);
                parent.addView(b);

                if (!sourceUrl.isEmpty()) {
                    final String u = sourceUrl;
                    TextView l = Ui.text(act, "查看原文 →", 11, Ui.INFO);
                    l.setPadding(0, Ui.dp(act, 4), 0, 0);
                    l.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            try {
                                startActivity(new Intent(Intent.ACTION_VIEW,
                                        android.net.Uri.parse(u)));
                            } catch (Exception e) {
                                Toast.makeText(act, "没有可用的浏览器",
                                        Toast.LENGTH_SHORT).show();
                            }
                        }
                    });
                    parent.addView(l);
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ================================================================ 洞察

    /**
     * 从记录里算出有用的结论。这是这个 App 最有价值的部分 ——
     * 数据是你自己攒的，结论别人给不了。
     */
    private boolean addInsights(LinearLayout parent, List<CatchRecord> catches, Db db) {
        if (catches.isEmpty()) {
            return false;
        }

        // 1) 最管用的拟饵（按累计尾数）
        java.util.LinkedHashMap<String, Integer> lureCount = new java.util.LinkedHashMap<>();
        java.util.LinkedHashMap<String, Integer> lureUses = new java.util.LinkedHashMap<>();
        // 2) 最出鱼的钓点
        java.util.LinkedHashMap<String, Integer> spotCount = new java.util.LinkedHashMap<>();
        java.util.LinkedHashMap<String, Integer> spotVisits = new java.util.LinkedHashMap<>();
        // 3) 按月分布
        java.util.LinkedHashMap<Integer, Integer> byMonth = new java.util.LinkedHashMap<>();
        // 4) 最常钓到的鱼
        java.util.LinkedHashMap<String, Integer> fishCount = new java.util.LinkedHashMap<>();
        // 5) 气压与鱼口的关系
        int lowPressureTrips = 0, lowPressureFish = 0;
        int highPressureTrips = 0, highPressureFish = 0;

        SimpleDateFormat monthFmt = new SimpleDateFormat("M", Locale.CHINA);

        for (CatchRecord r : catches) {
            int n = r.getCount();
            if (!r.getLureUsed().isEmpty()) {
                String key = r.getLureUsed();
                lureUses.put(key, (lureUses.containsKey(key) ? lureUses.get(key) : 0) + 1);
                lureCount.put(key, (lureCount.containsKey(key) ? lureCount.get(key) : 0) + n);
            }
            if (!r.getSpotName().isEmpty()) {
                String key = r.getSpotName();
                spotVisits.put(key, (spotVisits.containsKey(key) ? spotVisits.get(key) : 0) + 1);
                spotCount.put(key, (spotCount.containsKey(key) ? spotCount.get(key) : 0) + n);
            }
            if (!r.getCaughtFish().isEmpty() && n > 0) {
                String key = r.getCaughtFish();
                fishCount.put(key, (fishCount.containsKey(key) ? fishCount.get(key) : 0) + n);
            }
            try {
                int m = Integer.parseInt(monthFmt.format(new Date(r.getTime())));
                byMonth.put(m, (byMonth.containsKey(m) ? byMonth.get(m) : 0) + n);
            } catch (Exception ignored) {
            }
            // 气压：把数字抽出来判断高低压
            String p = r.getPressure().replaceAll("[^0-9]", "");
            if (!p.isEmpty() && p.length() >= 3) {
                try {
                    int hpa = Integer.parseInt(p.substring(0, 4));
                    if (hpa >= 950 && hpa <= 1060) {
                        if (hpa < 1005) {
                            lowPressureTrips++;
                            lowPressureFish += n;
                        } else if (hpa >= 1015) {
                            highPressureTrips++;
                            highPressureFish += n;
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }

        boolean any = false;

        String bestLure = topKey(lureCount);
        if (bestLure != null) {
            int total = lureCount.get(bestLure);
            int uses = lureUses.containsKey(bestLure) ? lureUses.get(bestLure) : 0;
            double avg = uses > 0 ? (double) total / uses : 0;
            any = true;
            parent.addView(insightRow("🥇", "最出鱼的拟饵", bestLure,
                    "累计 " + total + " 尾，用了 " + uses + " 次，平均每次 "
                            + String.format(Locale.CHINA, "%.1f", avg) + " 尾", Ui.FAV));
        }

        String bestSpot = topKey(spotCount);
        if (bestSpot != null) {
            int total = spotCount.get(bestSpot);
            int visits = spotVisits.containsKey(bestSpot) ? spotVisits.get(bestSpot) : 0;
            any = true;
            parent.addView(insightRow("🏞", "最出鱼的钓点", bestSpot,
                    "去了 " + visits + " 次，累计 " + total + " 尾", Ui.ACCENT));
        }

        String topFish = topKey(fishCount);
        if (topFish != null) {
            any = true;
            parent.addView(insightRow("🐟", "最常钓到", topFish,
                    "累计 " + fishCount.get(topFish) + " 尾", Ui.INFO));
        }

        Integer bestMonth = topKeyInt(byMonth);
        if (bestMonth != null) {
            any = true;
            parent.addView(insightRow("📅", "你的高产地月份", bestMonth + " 月",
                    "这个月累计 " + byMonth.get(bestMonth) + " 尾（仅供参考，样本还少）",
                    Ui.OK));
        }

        // 气压对比：只有两边都有样本才给结论，避免误导
        if (lowPressureTrips >= 2 && highPressureTrips >= 2) {
            double lowAvg = (double) lowPressureFish / lowPressureTrips;
            double highAvg = (double) highPressureFish / highPressureTrips;
            String verdict;
            if (highAvg > lowAvg * 1.3) {
                verdict = "高压天明显更好（平均 " + String.format(Locale.CHINA, "%.1f", highAvg)
                        + " 尾 vs 低压 " + String.format(Locale.CHINA, "%.1f", lowAvg) + " 尾）";
            } else if (lowAvg > highAvg * 1.3) {
                verdict = "低压天反而更好（平均 " + String.format(Locale.CHINA, "%.1f", lowAvg)
                        + " 尾 vs 高压 " + String.format(Locale.CHINA, "%.1f", highAvg) + " 尾）";
            } else {
                verdict = "目前看不出气压与鱼口的明显关系";
            }
            any = true;
            parent.addView(insightRow("🌡", "气压与鱼口", "你的实测样本",
                    verdict + "\n（低压 " + lowPressureTrips + " 次、高压 "
                            + highPressureTrips + " 次，样本少时结论不可靠）", Ui.WARN));
        } else if (!any) {
            return false;
        }

        return any;
    }

    private View insightRow(String icon, String label, String value, String sub, int color) {
        LinearLayout r = Ui.row(act);
        r.setPadding(0, Ui.dp(act, 8), 0, Ui.dp(act, 8));
        r.setGravity(android.view.Gravity.TOP);

        TextView ic = Ui.text(act, icon, 16, color);
        ic.setMinWidth(Ui.dp(act, 28));
        r.addView(ic);

        LinearLayout txt = Ui.column(act);
        txt.addView(Ui.text(act, label, 11, Ui.TEXT_FAINT));

        TextView v = Ui.text(act, value, 15, Ui.TEXT);
        v.setTypeface(v.getTypeface(), android.graphics.Typeface.BOLD);
        v.setPadding(0, Ui.dp(act, 2), 0, 0);
        txt.addView(v);

        if (sub != null && !sub.isEmpty()) {
            TextView s = Ui.text(act, sub, 11, Ui.TEXT_DIM);
            s.setLineSpacing(Ui.dp(act, 2), 1f);
            s.setPadding(0, Ui.dp(act, 3), 0, 0);
            txt.addView(s);
        }
        r.addView(txt, Ui.weighted(1f));
        return r;
    }

    private String topKey(java.util.LinkedHashMap<String, Integer> map) {
        String best = null;
        int bestVal = 0;
        for (java.util.Map.Entry<String, Integer> e : map.entrySet()) {
            if (e.getValue() > bestVal) {
                bestVal = e.getValue();
                best = e.getKey();
            }
        }
        return bestVal > 0 ? best : null;
    }

    private Integer topKeyInt(java.util.LinkedHashMap<Integer, Integer> map) {
        Integer best = null;
        int bestVal = 0;
        for (java.util.Map.Entry<Integer, Integer> e : map.entrySet()) {
            if (e.getValue() > bestVal) {
                bestVal = e.getValue();
                best = e.getKey();
            }
        }
        return bestVal > 0 ? best : null;
    }

    private String mapProviderName(String key) {
        if ("amap".equals(key)) return "高德地图";
        if ("baidu".equals(key)) return "百度地图";
        if ("tencent".equals(key)) return "腾讯地图";
        if ("web".equals(key)) return "网页地图（无需装 App）";
        return "自动选择（推荐）";
    }

    private void pickTheme() {
        final String[] labels = {"夜钓（深色）—— 夜间不刺眼，推荐",
                "日间（浅色）—— 白天强光下更清楚"};
        final String[] values = {Theme.DARK, Theme.LIGHT};
        String cur = Theme.currentName();
        int checked = Theme.LIGHT.equals(cur) ? 1 : 0;

        new AlertDialog.Builder(act, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("配色主题")
                .setSingleChoiceItems(labels, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        d.dismiss();
                        String target = values[which];
                        if (target.equals(Theme.currentName())) {
                            return;
                        }
                        Theme.set(act, target);
                        // 配色是全局静态字段，重建整个任务才能保证所有页面都刷新
                        ThemeHelper.restartApp(act);
                    }
                })
                .show();
    }

    private void pickMapProvider() {
        final String[] labels = {"自动选择（推荐）", "高德地图", "百度地图", "腾讯地图",
                "网页地图（无需装 App）"};
        final String[] values = {Prefs.MAP_AUTO, "amap", "baidu", "tencent", "web"};
        String cur = Prefs.getString(act, Prefs.K_MAP_PROVIDER, Prefs.MAP_AUTO);
        int checked = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(cur)) {
                checked = i;
            }
        }
        new AlertDialog.Builder(act, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("默认导航方式")
                .setSingleChoiceItems(labels, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        Prefs.setString(act, Prefs.K_MAP_PROVIDER, values[which]);
                        d.dismiss();
                        render();
                    }
                })
                .show();
    }

    private void confirmWipe() {
        new AlertDialog.Builder(act, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("清空全部数据")
                .setMessage("这会删除全部钓点和作钓记录，且无法恢复。\n\n"
                        + "建议先做一次「保存完整备份到手机」。确定要清空吗？")
                .setPositiveButton("我确定，清空", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        Db.get(act).wipeAll();
                        Toast.makeText(act, "已清空全部数据", Toast.LENGTH_SHORT).show();
                        render();
                    }
                })
                .setNegativeButton("先不删", null)
                .show();
    }
}
