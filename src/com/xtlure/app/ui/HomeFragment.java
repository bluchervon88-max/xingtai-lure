package com.xtlure.app.ui;

import android.app.AlertDialog;
import android.app.Fragment;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.xtlure.app.core.CoordinateTransform;
import com.xtlure.app.data.Db;
import com.xtlure.app.model.FishingSpot;
import com.xtlure.app.util.MapLauncher;
import com.xtlure.app.util.ScreenFit;
import com.xtlure.app.util.Ui;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 钓点页（主页）。
 *
 * ★ 版面：整个页面只有一个滚动容器，从上到下依次是
 *     ① 状态行（结果计数 + 当前筛选 + 排序 + 坐标覆盖）
 *     ② 钓点卡片列表（一次性全部构建，不用视图回收）
 *   两者同属一个滚动体系，滚动时一起移动。
 *
 * ★ 地图入口已从主页移除（2026-10 改版）
 *   原来这里有一个 56dp 的「钓点分布图」入口卡片 + 四周留白，实测占掉纵向
 *   352px（约 94dp，占内容区 33%），把首页切成上下两块 —— 用户连续两轮反馈
 *   "割裂"。而且第一张钓点卡片因此被推到 y=1266（屏幕 43% 处），首屏大半屏
 *   看不到钓点。
 *
 *   地图本身是"看整体分布"的工具，属于独立页面；主页专注"找具体钓点"。
 *   入口改到顶栏标题右侧的小按钮，与标题同一行，不额外占纵向空间。
 *
 * 尺寸全部走 ScreenFit 推导，不再写死 dp —— 这是适配不同 dpi 屏幕的关键。
 */
public class HomeFragment extends Fragment implements Refreshable {

    private SpotListHost host;

    private TextView countLabel, emptyView;
    private SpotListView listView;

    private String keyword = "";
    private String districtFilter = "";
    private String waterTypeFilter = "";
    private String sortMode = "recent";
    private boolean onlyFavorite = false;

    @Override
    public View onCreateView(android.view.LayoutInflater inf, ViewGroup parent,
                             Bundle savedInstanceState) {
        final android.content.Context ctx = getActivity();
        host = (SpotListHost) ctx;
        final ScreenFit fit = ScreenFit.get();

        LinearLayout root = Ui.column(ctx);
        root.setBackgroundColor(Ui.BG);

        // ============================================================ 整页一个滚动容器
        listView = new SpotListView(ctx);
        listView.setOnSpotClick(new SpotListView.OnSpotClick() {
            @Override
            public void onClick(FishingSpot spot) {
                host.openSpotDetail(spot.getId());
            }

            @Override
            public void onLongClick(FishingSpot spot) {
                showQuickMenu(spot);
            }
        });

        // ---- 页头：随列表一起滚动，让上下成为一体
        //
        // 只有一个状态行。之前这里还有地图入口卡片，撤掉后页头几乎不占纵向空间，
        // 滚动时第一张卡片紧跟筛选行出现。
        LinearLayout header = Ui.column(ctx);
        header.setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 8), Ui.dp(ctx, 16), Ui.dp(ctx, 4));

        countLabel = Ui.text(ctx, "", Ui.TEXT_SMALL, Ui.TEXT_DIM);
        countLabel.setMaxLines(1);
        countLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
        header.addView(countLabel, Ui.lpMatch());

        listView.setHeader(header);

        // ---- 空状态（与列表并排放在同一容器里，列表为空时显示）
        root.addView(listView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        emptyView = Ui.text(ctx, "", 14, Ui.TEXT_FAINT);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setVisibility(View.GONE);
        root.addView(emptyView, Ui.lpMatch());

        return root;
    }

    @Override
    public void onResume() {
        super.onResume();
        reload();
    }

    @Override
    public void refresh() {
        reload();
    }

    // ================================================================ 由宿主驱动

    public void setKeyword(String kw) {
        this.keyword = kw == null ? "" : kw;
        reload();
    }

    public void applyFilters(String keyword, String district, String waterType,
                             String sortMode, boolean onlyFavorite) {
        this.keyword = keyword == null ? "" : keyword;
        this.districtFilter = district == null ? "" : district;
        this.waterTypeFilter = waterType == null ? "" : waterType;
        this.sortMode = sortMode == null ? "recent" : sortMode;
        this.onlyFavorite = onlyFavorite;
        reload();
    }

    // ================================================================ 数据

    private void reload() {
        if (getActivity() == null || listView == null) {
            return;
        }
        final ScreenFit fit = ScreenFit.get();
        Db db = Db.get(getActivity());
        List<FishingSpot> allSpots = db.querySpots(null, null);

        // 坐标覆盖统计：原来显示在地图入口卡片上，卡片撤掉后并入状态行
        int withCoord = 0;
        java.util.LinkedHashSet<String> districts = new java.util.LinkedHashSet<>();
        for (FishingSpot s : allSpots) {
            if (s.isHasCoord()) {
                withCoord++;
            }
            if (!s.getDistrict().isEmpty()) {
                districts.add(s.getDistrict());
            }
        }

        List<FishingSpot> spots = keyword.isEmpty() ? allSpots : db.querySpots(keyword, null);

        if (!waterTypeFilter.isEmpty()) {
            List<FishingSpot> f = new ArrayList<>();
            for (FishingSpot s : spots) {
                if (waterTypeFilter.equals(FishingSpot.waterTypeName(s.getWaterType()))) {
                    f.add(s);
                }
            }
            spots = f;
        }
        if (!districtFilter.isEmpty()) {
            List<FishingSpot> f = new ArrayList<>();
            for (FishingSpot s : spots) {
                if (districtFilter.equals(s.getDistrict())) {
                    f.add(s);
                }
            }
            spots = f;
        }
        if (onlyFavorite) {
            List<FishingSpot> f = new ArrayList<>();
            for (FishingSpot s : spots) {
                if (s.isFavorite()) {
                    f.add(s);
                }
            }
            spots = f;
        }

        if ("name".equals(sortMode)) {
            Collections.sort(spots, new Comparator<FishingSpot>() {
                @Override
                public int compare(FishingSpot a, FishingSpot b) {
                    return a.getName().compareTo(b.getName());
                }
            });
        } else if ("distance".equals(sortMode) && !Double.isNaN(host.userLat())) {
            final double ulat = host.userLat();
            final double ulon = host.userLon();
            Collections.sort(spots, new Comparator<FishingSpot>() {
                @Override
                public int compare(FishingSpot a, FishingSpot b) {
                    double da = a.isHasCoord()
                            ? CoordinateTransform.distanceMeters(ulat, ulon, a.getLat(), a.getLon())
                            : Double.MAX_VALUE;
                    double dbb = b.isHasCoord()
                            ? CoordinateTransform.distanceMeters(ulat, ulon, b.getLat(), b.getLon())
                            : Double.MAX_VALUE;
                    return Double.compare(da, dbb);
                }
            });
        }

        // 统计行：每个钓点的最近记录与累计尾数
        SimpleDateFormat dayFmt = new SimpleDateFormat("MM-dd", Locale.CHINA);
        List<String> statLines = new ArrayList<>();
        for (FishingSpot s : spots) {
            long last = db.lastCatchTime(s.getId());
            int total = db.totalFishForSpot(s.getId());
            if (last > 0) {
                statLines.add("最近 " + dayFmt.format(new Date(last)) + " · 累计 " + total + " 尾");
            } else {
                statLines.add("还没有作钓记录");
            }
        }

        listView.setSpots(spots, host.userLat(), host.userLon(), statLines);

        boolean filtered = !districtFilter.isEmpty() || !waterTypeFilter.isEmpty()
                || onlyFavorite || !keyword.isEmpty();

        // 状态行：一行内交代"有多少、筛了什么、按什么排、地图上能看到多少"。
        // 之前这些信息分散在计数行和地图入口卡片上，撤掉卡片后合并到这里，
        // 既省了一整块纵向空间，又让信息更集中。
        StringBuilder status = new StringBuilder();
        status.append(filtered ? "筛选出 " + spots.size() + " 个" : "共 " + spots.size() + " 个钓点");
        if (!waterTypeFilter.isEmpty()) {
            status.append(" · ").append(waterTypeFilter);
        }
        if (!districtFilter.isEmpty()) {
            status.append(" · ").append(districtFilter);
        }
        if (onlyFavorite) {
            status.append(" · 收藏");
        }
        if (!"recent".equals(sortMode)) {
            status.append(" · ").append("name".equals(sortMode) ? "按名称" : "按距离");
        }
        if (!filtered) {
            status.append(" · ").append(withCoord).append(" 个可定位");
        }
        countLabel.setText(status.toString());

        if (spots.isEmpty()) {
            listView.setVisibility(View.GONE);
            emptyView.setVisibility(View.VISIBLE);
            emptyView.setText(filtered
                    ? "没有匹配的钓点\n\n点顶部筛选行的「✕ 清除」或换个关键词"
                    : "还没有钓点\n\n点右下角 ＋ 添加第一个");
        } else {
            listView.setVisibility(View.VISIBLE);
            emptyView.setVisibility(View.GONE);
        }
    }

    // ================================================================ 快捷菜单

    private void showQuickMenu(final FishingSpot s) {
        final List<String> items = new ArrayList<>();
        final List<Integer> actions = new ArrayList<>();

        if (s.isHasCoord()) {
            items.add("🧭  导航到这个钓点");
            actions.add(1);
            items.add("📋  复制三种坐标系坐标");
            actions.add(2);
        }
        items.add(s.isFavorite() ? "☆  取消收藏" : "★  加入收藏");
        actions.add(3);
        items.add("📝  记录一次作钓");
        actions.add(4);
        items.add("✏️  编辑");
        actions.add(5);
        items.add("🗑  删除");
        actions.add(6);

        new AlertDialog.Builder(getActivity(), android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(s.getName())
                .setItems(items.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        handleAction(actions.get(which), s);
                    }
                })
                .show();
    }

    private void handleAction(int action, FishingSpot s) {
        Db db = Db.get(getActivity());
        switch (action) {
            case 1: {
                String used = MapLauncher.smartOpen(getActivity(), s.getName(),
                        s.getLat(), s.getLon());
                if (used == null) {
                    Toast.makeText(getActivity(), "没找到可用的地图 App", Toast.LENGTH_SHORT).show();
                }
                break;
            }
            case 2:
                MapLauncher.copyCoord(getActivity(), s.getName(), s.getLat(), s.getLon());
                break;
            case 3:
                s.setFavorite(!s.isFavorite());
                db.updateSpot(s);
                reload();
                break;
            case 4:
                CatchDialog.show(getActivity(), s.getId(), s.getName());
                break;
            case 5: {
                Intent i = new Intent(getActivity(), EditSpotActivity.class);
                i.putExtra(EditSpotActivity.EXTRA_SPOT_ID, s.getId());
                startActivity(i);
                break;
            }
            case 6:
                confirmDelete(s);
                break;
        }
    }

    private void confirmDelete(final FishingSpot s) {
        int n = Db.get(getActivity()).countCatchesForSpot(s.getId());
        String msg = "确定删除「" + s.getName() + "」吗？";
        if (n > 0) {
            msg += "\n\n该钓点下还有 " + n + " 条作钓记录，记录会保留但不再关联到这个钓点。";
        }
        new AlertDialog.Builder(getActivity(), android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("删除钓点")
                .setMessage(msg)
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Db.get(getActivity()).deleteSpot(s.getId());
                        Toast.makeText(getActivity(), "已删除", Toast.LENGTH_SHORT).show();
                        reload();
                        host.onSpotsChanged();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }
}
