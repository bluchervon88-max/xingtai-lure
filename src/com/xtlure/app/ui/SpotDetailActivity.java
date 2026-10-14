package com.xtlure.app.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.xtlure.app.core.CoordinateTransform;
import com.xtlure.app.core.SunMoon;
import com.xtlure.app.data.Db;
import com.xtlure.app.model.CatchRecord;
import com.xtlure.app.model.FishingSpot;
import com.xtlure.app.util.GpsTracker;
import com.xtlure.app.util.MapLauncher;
import com.xtlure.app.util.Prefs;
import com.xtlure.app.util.ThemeHelper;
import com.xtlure.app.util.Ui;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 钓点详情页。
 *
 * 版面顺序按"到了水边最想知道什么"排：
 *   1. 四个大按钮：导航 / 复制坐标 / 记一次 / 编辑
 *   2. 坐标卡（三种坐标系 + 日出日落 + 距离）
 *   3. 作钓信息：对象鱼、推荐拟饵、最佳季节
 *   4. 到达方式与风险提示
 *   5. 这个钓点的历史作钓记录
 */
public class SpotDetailActivity extends Activity {

    public static final String EXTRA_SPOT_ID = "spot_id";

    private long spotId;
    private FishingSpot spot;
    private LinearLayout content;
    private GpsTracker gps;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeHelper.prepare(this);
        spotId = getIntent().getLongExtra(EXTRA_SPOT_ID, -1);
        spot = Db.get(this).getSpot(spotId);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);

        LinearLayout col = Ui.column(this);
        col.addView(buildTopBar(), Ui.lpMatch());

        ScrollView scroll = new ScrollView(this);
        content = Ui.column(this);
        content.setPadding(Ui.dp(this, 14), Ui.dp(this, 6), Ui.dp(this, 14), Ui.dp(this, 30));
        scroll.addView(content);
        col.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(col);
        setContentView(root);

        if (spot == null) {
            Toast.makeText(this, "钓点不存在或已被删除", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从编辑页返回时重新读取
        FishingSpot fresh = Db.get(this).getSpot(spotId);
        if (fresh != null) {
            spot = fresh;
            render();
        }
    }

    // ================================================================ 顶栏

    private View buildTopBar() {
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

        TextView title = Ui.text(this, spot == null ? "钓点详情" : spot.getName(), 17, Ui.TEXT);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setMaxLines(1);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        bar.addView(title, Ui.weighted(1f));

        final TextView fav = Ui.text(this, spot != null && spot.isFavorite() ? "★" : "☆",
                22, Ui.FAV);
        fav.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 4), 0);
        fav.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (spot == null) {
                    return;
                }
                spot.setFavorite(!spot.isFavorite());
                Db.get(SpotDetailActivity.this).updateSpot(spot);
                fav.setText(spot.isFavorite() ? "★" : "☆");
            }
        });
        bar.addView(fav);

        return bar;
    }

    // ================================================================ 主体

    private void render() {
        content.removeAllViews();

        content.addView(actionBar());

        // ---------------- 禁渔期卡（合规信息，放最前面）
        if (!spot.getClosedSeason().isEmpty()) {
            boolean closedNow = spot.isClosedNow();
            boolean exempt = !spot.subjectToClosedSeason();
            int color = exempt ? Ui.OK : (closedNow ? Ui.WARN : Ui.TEXT_DIM);

            LinearLayout seasonCard = Ui.card(this);
            LinearLayout.LayoutParams scLp = Ui.lpMatch();
            scLp.topMargin = Ui.dp(this, 12);
            seasonCard.setLayoutParams(scLp);

            LinearLayout head = Ui.row(this);
            TextView title = Ui.text(this, "⚖ 禁渔期规定", 12, Ui.ACCENT);
            title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
            head.addView(title);
            View sp2 = new View(this);
            // 弹性占位：只用来把后面的徽标推到右边，高度必须为 0
            // （weight 在横向布局里只分配宽度，高度给 WRAP_CONTENT 会让行高失控）
            head.addView(sp2, Ui.weightedSpacer());

            String badgeText;
            if (exempt) {
                badgeText = "不受禁渔期约束";
            } else if (closedNow) {
                badgeText = "⛔ 当前处于禁渔期";
            } else {
                badgeText = "✅ 当前不在禁渔期";
            }
            TextView badge = Ui.text(this, badgeText, 11, color);
            badge.setTypeface(badge.getTypeface(), android.graphics.Typeface.BOLD);
            badge.setPadding(Ui.dp(this, 8), Ui.dp(this, 3), Ui.dp(this, 8), Ui.dp(this, 3));
            badge.setBackground(Ui.round(Ui.blend(color, Ui.SURFACE, 0.18f), Ui.RADIUS_CHIP,
                    Ui.blend(color, Ui.SURFACE, 0.45f), 1f));
            head.addView(badge);
            seasonCard.addView(head);

            TextView body = Ui.text(this, spot.getClosedSeason(), 13,
                    closedNow && !exempt ? Ui.WARN : Ui.TEXT_DIM);
            body.setLineSpacing(Ui.dp(this, 4), 1f);
            LinearLayout.LayoutParams blp = Ui.lpMatch();
            blp.topMargin = Ui.dp(this, 8);
            body.setLayoutParams(blp);
            seasonCard.addView(body);

            if (!exempt) {
                TextView rule = Ui.text(this,
                        "河北省海河流域禁渔期：" + FishingSpot.CLOSED_SEASON_TEXT + "。\n"
                                + FishingSpot.CLOSED_SEASON_RULE + "\n"
                                + "提示：路亚竿属钓具、文本上被豁免，但三本钩硬饵在禁渔期内"
                                + "有被按「多钩线钓」认定的风险（推断，未核实）；"
                                + "稳妥做法是期内改单钩软虫或亮片。",
                        11, Ui.TEXT_FAINT);
                rule.setLineSpacing(Ui.dp(this, 3), 1f);
                LinearLayout.LayoutParams rlp = Ui.lpMatch();
                rlp.topMargin = Ui.dp(this, 7);
                rule.setLayoutParams(rlp);
                seasonCard.addView(rule);
            }

            content.addView(seasonCard);
        }

        // ---------------- 坐标卡
        LinearLayout coordCard = Ui.card(this);
        LinearLayout.LayoutParams ccLp = Ui.lpMatch();
        ccLp.topMargin = Ui.dp(this, 12);
        coordCard.setLayoutParams(ccLp);

        if (spot.isHasCoord()) {
            double[] gcj = CoordinateTransform.wgs84ToGcj02(spot.getLat(), spot.getLon());
            double[] bd = CoordinateTransform.wgs84ToBd09(spot.getLat(), spot.getLon());

            coordCard.addView(Ui.section(this, "坐标"));
            coordCard.addView(Ui.kv(this, "GPS 原始",
                    String.format(Locale.CHINA, "%.5f, %.5f", spot.getLat(), spot.getLon())));
            coordCard.addView(Ui.kv(this, "高德/腾讯",
                    String.format(Locale.CHINA, "%.5f, %.5f", gcj[0], gcj[1])));
            coordCard.addView(Ui.kv(this, "百度",
                    String.format(Locale.CHINA, "%.5f, %.5f", bd[0], bd[1])));
            coordCard.addView(Ui.kv(this, "度分秒",
                    CoordinateTransform.toDms(spot.getLat(), true) + "  "
                            + CoordinateTransform.toDms(spot.getLon(), false)));

            // 坐标精度必须让用户看见：估算值不能当实测值用
            TextView prec = Ui.text(this, "📐 " + spot.coordPrecisionText(), 11,
                    spot.isCoordExact() ? Ui.OK : Ui.WARN);
            prec.setLineSpacing(Ui.dp(this, 3), 1f);
            LinearLayout.LayoutParams plp = Ui.lpMatch();
            plp.topMargin = Ui.dp(this, 6);
            prec.setLayoutParams(plp);
            coordCard.addView(prec);

            double ulat = Prefs.getDouble(this, Prefs.K_LAST_LAT, Double.NaN);
            double ulon = Prefs.getDouble(this, Prefs.K_LAST_LON, Double.NaN);
            if (!Double.isNaN(ulat)) {
                double d = CoordinateTransform.distanceMeters(ulat, ulon,
                        spot.getLat(), spot.getLon());
                coordCard.addView(Ui.kv(this, "直线距离",
                        CoordinateTransform.formatDistance(d) + "（从上次定位点算）"));
            }

            // 天文信息：用钓点自身坐标算，比用手机位置更准
            Calendar now = Calendar.getInstance();
            double[] ss = SunMoon.sunriseSunset(now.get(Calendar.YEAR),
                    now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH),
                    spot.getLat(), spot.getLon(), 8);
            coordCard.addView(Ui.section(this, "今日光照"));
            coordCard.addView(Ui.kv(this, "日出", SunMoon.hourToTime(ss[0])));
            coordCard.addView(Ui.kv(this, "日落", SunMoon.hourToTime(ss[1])));
            coordCard.addView(Ui.kv(this, "黄金窗口",
                    SunMoon.hourToTime(ss[0]) + " 前后 2 小时  /  "
                            + SunMoon.hourToTime(ss[1] - 2) + "–" + SunMoon.hourToTime(ss[1])));
            double moonAge = SunMoon.moonAgeToday();
            coordCard.addView(Ui.kv(this, "月相",
                    SunMoon.moonPhaseEmoji(moonAge) + " " + SunMoon.moonPhaseName(moonAge)
                            + "（月龄 " + String.format(Locale.CHINA, "%.1f", moonAge) + " 天）"));
            coordCard.addView(Ui.kv(this, "鱼口参考",
                    SunMoon.fishActivityScore(moonAge) + " 分 · 仅供参考"));
        } else {
            coordCard.addView(Ui.section(this, "坐标"));
            TextView t = Ui.text(this, "还没有记录坐标。\n到钓点后点上面的「重新取坐标」即可用手机 GPS 定位。",
                    14, Ui.TEXT_DIM);
            t.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
            coordCard.addView(t);
        }
        content.addView(coordCard);

        // ---------------- 作钓信息
        LinearLayout infoCard = Ui.card(this);
        LinearLayout.LayoutParams icLp = Ui.lpMatch();
        icLp.topMargin = Ui.dp(this, 12);
        infoCard.setLayoutParams(icLp);

        infoCard.addView(Ui.section(this, "基本信息"));
        infoCard.addView(Ui.kv(this, "水域类型", FishingSpot.waterTypeName(spot.getWaterType())));
        infoCard.addView(Ui.kv(this, "所在区县", spot.getDistrict()));
        infoCard.addView(Ui.kv(this, "位置描述", spot.getAddress()));
        infoCard.addView(Ui.kv(this, "作钓状态", FishingSpot.statusName(spot.getStatus())));
        infoCard.addView(Ui.kv(this, "资料可信度", FishingSpot.confidenceName(spot.getConfidence())));

        // 费用情况：调研里常有"官方标免费但有人乱收费"这类冲突信息，原样呈现
        if (!spot.getFeeNote().isEmpty()) {
            infoCard.addView(Ui.section(this, "费用情况"));
            TextView fee = body(spot.getFeeNote());
            fee.setTextColor(spot.getStatus() == FishingSpot.STATUS_FEE
                    ? Ui.INFO : Ui.TEXT_DIM);
            infoCard.addView(fee);
        }

        infoCard.addView(Ui.section(this, "作钓信息"));
        infoCard.addView(Ui.kv(this, "对象鱼", spot.getFishSpecies()));
        infoCard.addView(Ui.kv(this, "推荐拟饵", spot.getLureTypes()));
        infoCard.addView(Ui.kv(this, "最佳季节", spot.getBestSeason()));
        infoCard.addView(Ui.kv(this, "到达方式", spot.getAccessNote()));

        if (!spot.getNote().isEmpty()) {
            infoCard.addView(Ui.section(this, "备注"));
            infoCard.addView(body(spot.getNote()));
        }

        if (!spot.getRiskNote().isEmpty()) {
            infoCard.addView(Ui.section(this, "⚠ 风险与合规提示"));
            TextView t = body(spot.getRiskNote());
            t.setTextColor(Ui.WARN);
            infoCard.addView(t);
        }

        if (!spot.getSourceUrl().isEmpty()) {
            infoCard.addView(Ui.section(this, "资料来源"));
            TextView link = Ui.text(this, spot.getSourceUrl(), 12, Ui.INFO);
            link.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
            link.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openUrl(spot.getSourceUrl());
                }
            });
            infoCard.addView(link);

            // 备选来源（竖线分隔）
            String[] alts = spot.getSourceAlt().split("\\|");
            for (final String alt : alts) {
                final String u = alt.trim();
                if (u.isEmpty()) {
                    continue;
                }
                TextView a = Ui.text(this, u, 11, Ui.TEXT_FAINT);
                a.setPadding(0, Ui.dp(this, 3), 0, Ui.dp(this, 3));
                a.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openUrl(u);
                    }
                });
                infoCard.addView(a);
            }
        }

        content.addView(infoCard);

        // ---------------- 调研依据（说明"为什么这么判断"，含反向证据）
        if (!spot.getEvidence().isEmpty()) {
            LinearLayout evCard = Ui.card(this);
            LinearLayout.LayoutParams ecLp = Ui.lpMatch();
            ecLp.topMargin = Ui.dp(this, 12);
            evCard.setLayoutParams(ecLp);

            evCard.addView(Ui.section(this, "调研依据"));
            TextView ev = body(spot.getEvidence());
            evCard.addView(ev);

            TextView note = Ui.text(this,
                    "资料整理自公开来源，未实地逐点核实。鱼情与收费随时会变，以现场为准。",
                    11, Ui.TEXT_FAINT);
            note.setLineSpacing(Ui.dp(this, 3), 1f);
            LinearLayout.LayoutParams nlp = Ui.lpMatch();
            nlp.topMargin = Ui.dp(this, 8);
            note.setLayoutParams(nlp);
            evCard.addView(note);

            content.addView(evCard);
        }

        // ---------------- 历史记录
        List<CatchRecord> records = Db.get(this).queryCatches(null, spotId);
        LinearLayout hisCard = Ui.card(this);
        LinearLayout.LayoutParams hcLp = Ui.lpMatch();
        hcLp.topMargin = Ui.dp(this, 12);
        hisCard.setLayoutParams(hcLp);

        int total = 0;
        for (CatchRecord r : records) {
            total += r.getCount();
        }
        hisCard.addView(Ui.section(this, "我的作钓记录（" + records.size() + " 次 · 累计 "
                + total + " 尾）"));

        if (records.isEmpty()) {
            hisCard.addView(body("这里还没有你的作钓记录。\n钓完点上面的「记一次」就能记下来。"));
        } else {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA);
            int show = Math.min(records.size(), 8);
            for (int i = 0; i < show; i++) {
                CatchRecord r = records.get(i);
                LinearLayout row = Ui.row(this);
                row.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));

                String head = r.isBlank() ? "空军"
                        : (r.getCaughtFish().isEmpty() ? "有渔获" : r.getCaughtFish())
                        + (r.getCount() > 0 ? " ×" + r.getCount() : "");
                TextView t1 = Ui.text(this, f.format(new Date(r.getTime())), 12, Ui.TEXT_FAINT);
                t1.setMinWidth(Ui.dp(this, 112));
                row.addView(t1);

                TextView t2 = Ui.text(this, head, 13, r.isBlank() ? Ui.TEXT_FAINT : Ui.ACCENT);
                row.addView(t2, Ui.weighted(1f));

                TextView t3 = Ui.text(this, r.getLureUsed(), 12, Ui.TEXT_DIM);
                t3.setMaxLines(1);
                t3.setEllipsize(android.text.TextUtils.TruncateAt.END);
                row.addView(t3);
                hisCard.addView(row);
            }
            if (records.size() > show) {
                hisCard.addView(Ui.text(this, "…… 还有 " + (records.size() - show) + " 条，去「记录」页查看",
                        11, Ui.TEXT_FAINT));
            }
        }
        content.addView(hisCard);
    }

    private TextView body(String s) {
        TextView t = Ui.text(this, s, 14, Ui.TEXT_DIM);
        t.setLineSpacing(Ui.dp(this, 3), 1f);
        t.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
        return t;
    }

    /** 用系统浏览器打开链接，失败给提示 */
    private void openUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            return;
        }
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url.trim()));
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "没有可用的浏览器", Toast.LENGTH_SHORT).show();
        }
    }

    // ================================================================ 操作按钮

    private View actionBar() {
        LinearLayout row = Ui.row(this);
        row.setBackground(Ui.round(Ui.SURFACE, Ui.RADIUS_CARD, Ui.BORDER, 1f));
        row.setPadding(Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 10));

        row.addView(actionBtn("🧭", "导航", Ui.ACCENT, new Runnable() {
            @Override
            public void run() {
                if (!spot.isHasCoord()) {
                    Toast.makeText(SpotDetailActivity.this,
                            "还没有坐标，先点「取坐标」", Toast.LENGTH_SHORT).show();
                    return;
                }
                String used = MapLauncher.smartOpen(SpotDetailActivity.this, spot.getName(),
                        spot.getLat(), spot.getLon());
                Toast.makeText(SpotDetailActivity.this,
                        used == null ? "没找到可用的地图 App" : "已用「" + used + "」打开",
                        Toast.LENGTH_SHORT).show();
            }
        }));

        row.addView(actionBtn("📋", "复制坐标", Ui.INFO, new Runnable() {
            @Override
            public void run() {
                if (!spot.isHasCoord()) {
                    Toast.makeText(SpotDetailActivity.this, "还没有坐标",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                MapLauncher.copyCoord(SpotDetailActivity.this, spot.getName(),
                        spot.getLat(), spot.getLon());
            }
        }));

        row.addView(actionBtn("📝", "记一次", Ui.WARN, new Runnable() {
            @Override
            public void run() {
                CatchDialog.show(SpotDetailActivity.this, spot.getId(), spot.getName());
            }
        }));

        row.addView(actionBtn("✏️", "编辑", Ui.TEXT_DIM, new Runnable() {
            @Override
            public void run() {
                Intent i = new Intent(SpotDetailActivity.this, EditSpotActivity.class);
                i.putExtra(EditSpotActivity.EXTRA_SPOT_ID, spot.getId());
                startActivity(i);
            }
        }));

        row.addView(actionBtn("📍", "取坐标", Ui.OK, new Runnable() {
            @Override
            public void run() {
                locateSpot();
            }
        }));

        return row;
    }

    /** 站在钓点上用手机 GPS 补录坐标 */
    private void locateSpot() {
        if (gps == null) {
            gps = new GpsTracker(this);
        }
        if (!GpsTracker.hasPermission(this)) {
            GpsTracker.requestPermission(this);
            return;
        }
        Toast.makeText(this, "正在定位…请保持手机在开阔处", Toast.LENGTH_SHORT).show();
        gps.requestOnce(new GpsTracker.Callback() {
            @Override
            public void onLocation(double lat, double lon, float acc, String provider) {
                spot.setLat(lat);
                spot.setLon(lon);
                spot.setHasCoord(true);
                Db.get(SpotDetailActivity.this).updateSpot(spot);
                Prefs.setDouble(SpotDetailActivity.this, Prefs.K_LAST_LAT, lat);
                Prefs.setDouble(SpotDetailActivity.this, Prefs.K_LAST_LON, lon);
                Toast.makeText(SpotDetailActivity.this,
                        "已记录坐标，精度约 " + (int) acc + " 米", Toast.LENGTH_SHORT).show();
                render();
            }

            @Override
            public void onFail(String reason) {
                Toast.makeText(SpotDetailActivity.this,
                        "定位失败：" + reason, Toast.LENGTH_LONG).show();
            }
        }, 30000);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == GpsTracker.REQ_LOCATION) {
            boolean ok = false;
            for (int r : results) {
                if (r == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    ok = true;
                    break;
                }
            }
            if (ok) {
                locateSpot();
            } else {
                Toast.makeText(this, "没有定位权限，可以进编辑页手动输入坐标",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (gps != null) {
            gps.cancel();
        }
        super.onDestroy();
    }

    private View actionBtn(String icon, String label, int color, final Runnable onClick) {
        LinearLayout v = Ui.column(this);
        v.setGravity(Gravity.CENTER);
        v.setPadding(Ui.dp(this, 4), Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 6));
        v.setBackground(Ui.ripple(0x00000000, Ui.RADIUS_BTN, Ui.RIPPLE));

        TextView ic = Ui.text(this, icon, 20, color);
        ic.setGravity(Gravity.CENTER);
        v.addView(ic);

        TextView lb = Ui.text(this, label, 11, Ui.TEXT_DIM);
        lb.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = Ui.lpMatch();
        lp.topMargin = Ui.dp(this, 3);
        lb.setLayoutParams(lp);
        v.addView(lb);

        v.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                onClick.run();
            }
        });
        v.setLayoutParams(Ui.weighted(1f));
        return v;
    }
}
