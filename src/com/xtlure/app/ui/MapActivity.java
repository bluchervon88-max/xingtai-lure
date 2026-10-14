package com.xtlure.app.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.xtlure.app.data.Db;
import com.xtlure.app.model.FishingSpot;
import com.xtlure.app.util.CrashLog;
import com.xtlure.app.util.Prefs;
import com.xtlure.app.util.ScreenFit;
import com.xtlure.app.util.ThemeHelper;
import com.xtlure.app.util.Ui;

import java.util.ArrayList;
import java.util.List;

/**
 * 钓点分布图（二级页面）。
 *
 * 从主页顶部的「分布图」入口进入，全屏展示、可缩放拖动。
 *
 * 为什么要独立成页：
 *   原来把地图塞在主页上半部分，占掉 240dp 高度，把列表挤成一小条，
 *   上下两块像拼接的两个界面 —— 这正是"割裂感"的主要来源。
 *   地图本质是"看整体分布"的工具，适合独立空间；主页则专注"找具体钓点"。
 *
 * 支持：双指缩放、拖动平移、按类型筛选、点击圆点进详情。
 */
public class MapActivity extends Activity implements SpotMapView.OnSpotTap,
        SpotMapView.OnMapTap {

    private SpotMapView mapView;
    private TextView statLabel;
    private LinearLayout filterRow;
    private TextView emptyHint;
    private Basemap basemap;

    /** 当前类型筛选，空串表示全部 */
    private String typeFilter = "";
    private List<FishingSpot> allSpots = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeHelper.prepare(this);
        CrashLog.install(this);
        ScreenFit.get().init(this);

        LinearLayout col = Ui.column(this);
        col.setBackgroundColor(Ui.BG);

        // ---- 顶栏
        LinearLayout bar = Ui.row(this);
        bar.setBackgroundColor(Ui.SURFACE);
        int pad = ScreenFit.get().headerPad();
        bar.setPadding(Ui.dp(this, 6), pad, Ui.dp(this, 12), pad);

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

        TextView title = Ui.text(this, "钓点分布图", 18, Ui.TEXT);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        bar.addView(title);
        bar.addView(new View(this), new LinearLayout.LayoutParams(0, 0, 1f));

        // 底图开关：可以关掉只留钓点，方便在密集区看清点位
        final TextView baseToggle = Ui.text(this, "底图", 12, Ui.ACCENT);
        baseToggle.setGravity(Gravity.CENTER);
        baseToggle.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), 0);
        baseToggle.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ScreenFit.get().touchMin()));
        baseToggle.setBackground(Ui.ripple(
                Ui.blend(Ui.ACCENT, Ui.SURFACE, 0.22f), Ui.RADIUS_CHIP, Ui.RIPPLE));
        baseToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean now = !mapView.isShowBasemap();
                mapView.setShowBasemap(now);
                baseToggle.setTextColor(now ? Ui.ACCENT : Ui.TEXT_DIM);
                baseToggle.setBackground(Ui.ripple(now
                                ? Ui.blend(Ui.ACCENT, Ui.SURFACE, 0.22f)
                                : Ui.SURFACE_2,
                        Ui.RADIUS_CHIP, Ui.RIPPLE));
            }
        });
        bar.addView(baseToggle);

        TextView reset = Ui.text(this, "复位", 12, Ui.ACCENT);
        reset.setGravity(Gravity.CENTER);
        reset.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), 0);
        reset.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ScreenFit.get().touchMin()));
        reset.setBackground(Ui.ripple(Ui.SURFACE_2, Ui.RADIUS_CHIP, Ui.RIPPLE));
        reset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mapView != null) {
                    mapView.resetView();
                }
            }
        });
        bar.addView(reset);
        col.addView(bar, Ui.lpMatch());

        // ---- 统计与图例
        LinearLayout info = Ui.column(this);
        info.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 6));
        statLabel = Ui.text(this, "", 12, Ui.TEXT_DIM);
        info.addView(statLabel);

        LinearLayout legend = Ui.row(this);
        LinearLayout.LayoutParams lgLp = Ui.lpMatch();
        lgLp.topMargin = Ui.dp(this, 8);
        legend.setLayoutParams(lgLp);
        addLegend(legend, FishingSpot.TYPE_RESERVOIR);
        addLegend(legend, FishingSpot.TYPE_RIVER);
        addLegend(legend, FishingSpot.TYPE_LAKE);
        addLegend(legend, FishingSpot.TYPE_PAYPOND);
        addLegend(legend, FishingSpot.TYPE_CANAL);
        legend.addView(new View(this), new LinearLayout.LayoutParams(0, 0, 1f));
        legend.addView(Ui.text(this, "圆内数字 = 该处并合了几个点", 10, Ui.TEXT_FAINT));
        info.addView(legend);
        col.addView(info, Ui.lpMatch());

        // ---- 类型筛选（触摸高度 >= 48dp）
        filterRow = Ui.row(this);
        filterRow.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 10), Ui.dp(this, 4));
        col.addView(filterRow, Ui.lpMatch());
        buildFilterRow();

        // ---- 地图本体 + 悬浮缩放按钮
        FrameLayout mapBox = new FrameLayout(this);
        mapView = new SpotMapView(this);
        mapView.setOnSpotTap(this);
        mapView.setOnMapTap(this);

        // 载入离线矢量底图（水系 / 区县界 / 河道），失败则只画钓点
        basemap = new Basemap();
        basemap.load(this);

        // 底图必须先挂上，地图才能按真实数据范围校正初始视野
        mapView.setBasemap(basemap);
        // 视野变化时刷新统计行（缩放倍数会变）
        mapView.setOnViewChanged(new Runnable() {
            @Override
            public void run() {
                updateStat();
            }
        });

        mapBox.addView(mapView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 缩放按钮：捏合缩放之外再给一套显式按钮，单手也能操作
        LinearLayout zoomCol = Ui.column(this);
        LinearLayout.LayoutParams zcLp = new LinearLayout.LayoutParams(
                Ui.dp(this, 44), ViewGroup.LayoutParams.WRAP_CONTENT);
        zcLp.gravity = Gravity.TOP | Gravity.END;
        zcLp.topMargin = Ui.dp(this, 12);
        zcLp.rightMargin = Ui.dp(this, 12);
        zoomCol.setLayoutParams(zcLp);
        zoomCol.addView(zoomButton("＋", new Runnable() {
            @Override
            public void run() {
                mapView.zoomBy(2f);
            }
        }), new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));
        View gap = new View(this);
        zoomCol.addView(gap, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 6)));
        zoomCol.addView(zoomButton("－", new Runnable() {
            @Override
            public void run() {
                mapView.zoomBy(0.5f);   // 1x -> 0.5x -> 0.25x（全览）
            }
        }), new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));
        mapBox.addView(zoomCol);

        emptyHint = Ui.text(this, "还没有带坐标的钓点\n\n在钓点编辑页用「取坐标」记录位置",
                13, Ui.TEXT_FAINT);
        emptyHint.setGravity(Gravity.CENTER);
        emptyHint.setVisibility(View.GONE);
        mapBox.addView(emptyHint, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
        col.addView(mapBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(col);

        // 交互提示 + 数据来源署名（ODbL 要求署名）
        TextView hint = Ui.text(this, "双指缩放 · 拖动平移 · 点圆点看钓点　|　底图数据 © OpenStreetMap",
                10, Ui.TEXT_FAINT);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 8));
        col.addView(hint, Ui.lpMatch());

        load();
    }

    /** 悬浮的方形缩放按钮 */
    private View zoomButton(String label, final Runnable action) {
        TextView t = Ui.text(this, label, 20, Ui.TEXT);
        t.setGravity(Gravity.CENTER);
        t.setBackground(Ui.ripple(Ui.SURFACE, Ui.RADIUS_BTN, Ui.RIPPLE));
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                action.run();
            }
        });
        return t;
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    private void load() {
        allSpots = Db.get(this).listSpots();
        int withCoord = 0;
        for (FishingSpot s : allSpots) {
            if (s.isHasCoord()) {
                withCoord++;
            }
        }
        emptyHint.setVisibility(withCoord == 0 ? View.VISIBLE : View.GONE);
        applyFilter();
    }

    private void applyFilter() {
        List<FishingSpot> shown = new ArrayList<>();
        for (FishingSpot s : allSpots) {
            if (typeFilter.isEmpty()
                    || typeFilter.equals(FishingSpot.waterTypeName(s.getWaterType()))) {
                shown.add(s);
            }
        }
        mapView.setUserLocation(Prefs.getDouble(this, Prefs.K_LAST_LAT, Double.NaN),
                Prefs.getDouble(this, Prefs.K_LAST_LON, Double.NaN));
        mapView.setData(shown);
        updateStat();
    }

    /**
     * 统计行。
     *
     * 显示缩放倍数与当前视野的经纬范围 —— 出问题时一眼就能看出是比例不对还是范围不对，
     * 不用去猜（之前"地图比例不对"就是靠这行定位的）。
     */
    private void updateStat() {
        if (statLabel == null || mapView == null) {
            return;
        }
        int total = 0, coord = 0;
        for (FishingSpot s : allSpots) {
            if (typeFilter.isEmpty()
                    || typeFilter.equals(FishingSpot.waterTypeName(s.getWaterType()))) {
                total++;
                if (s.isHasCoord()) {
                    coord++;
                }
            }
        }
        statLabel.setText((typeFilter.isEmpty() ? "全部" : typeFilter) + "："
                + total + " 个钓点，" + coord + " 个有坐标 · 视野内 "
                + mapView.visibleSpotCount() + "/" + mapView.totalSpotCount()
                + " 点聚成 " + mapView.clusterCount() + " 处 · 缩放 "
                + String.format(java.util.Locale.CHINA, "%.1f×", mapView.currentZoom())
                + " · " + mapView.viewportText());
    }

    // ================================================================ 类型筛选

    private void buildFilterRow() {
        filterRow.removeAllViews();
        String[] labels = {"全部", "水库", "河流", "湖泊", "黑坑", "渠道"};
        for (final String label : labels) {
            boolean active = (label.equals("全部") && typeFilter.isEmpty())
                    || label.equals(typeFilter);
            TextView t = Ui.text(this, label, 12, active ? Ui.ACCENT : Ui.TEXT_DIM);
            t.setGravity(Gravity.CENTER);
            t.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), 0);
            t.setBackground(active
                    ? Ui.ripple(Ui.blend(Ui.ACCENT, Ui.SURFACE, 0.22f), Ui.RADIUS_CHIP, Ui.RIPPLE)
                    : Ui.ripple(Ui.SURFACE_2, Ui.RADIUS_CHIP, Ui.RIPPLE));
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    typeFilter = label.equals("全部") ? "" : label;
                    buildFilterRow();
                    applyFilter();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 40));
            lp.rightMargin = Ui.dp(this, 8);
            lp.topMargin = Ui.dp(this, 4);
            lp.bottomMargin = Ui.dp(this, 4);
            t.setLayoutParams(lp);
            filterRow.addView(t);
        }
    }

    private void addLegend(LinearLayout parent, int waterType) {
        LinearLayout item = Ui.row(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Ui.dp(this, 10);
        item.setLayoutParams(lp);

        View dot = new View(this);
        dot.setBackground(Ui.circle(Ui.waterTypeColor(waterType)));
        item.addView(dot, new LinearLayout.LayoutParams(Ui.dp(this, 8), Ui.dp(this, 8)));

        TextView t = Ui.text(this, FishingSpot.waterTypeName(waterType), 10, Ui.TEXT_DIM);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.leftMargin = Ui.dp(this, 4);
        t.setLayoutParams(tlp);
        item.addView(t);
        parent.addView(item);
    }

    // ================================================================ 交互

    @Override
    public void onTap(FishingSpot spot) {
        Intent i = new Intent(this, SpotDetailActivity.class);
        i.putExtra(SpotDetailActivity.EXTRA_SPOT_ID, spot.getId());
        startActivity(i);
    }

    /** 点空白处：提示可以缩放拖动，避免用户以为图是死的 */
    @Override
    public void onMapTap() {
        Toast.makeText(this, "双指缩放、拖动平移；点圆点查看钓点", Toast.LENGTH_SHORT).show();
    }
}
