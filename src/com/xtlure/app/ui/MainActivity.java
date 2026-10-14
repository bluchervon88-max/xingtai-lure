package com.xtlure.app.ui;

import android.app.Activity;
import android.app.Fragment;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.xtlure.app.core.SunMoon;
import com.xtlure.app.data.Db;
import com.xtlure.app.model.CatchRecord;
import com.xtlure.app.model.FishingSpot;
import com.xtlure.app.util.CrashLog;
import com.xtlure.app.util.Exporter;
import com.xtlure.app.util.GpsTracker;
import com.xtlure.app.util.Json;
import com.xtlure.app.util.Prefs;
import com.xtlure.app.util.ScreenFit;
import com.xtlure.app.util.ThemeHelper;
import com.xtlure.app.util.Ui;

import java.io.File;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 主界面。
 *
 * ★ 版面设计原则（这一版重做的重点）
 *   顶部是一整块**连续的头部区**，不再拆成几个各自带边框的卡片。
 *   上一版把"标题块 / 搜索块 / 分布图块"各做成带描边的独立卡片，
 *   视觉上像三块不相干的积木堆着，这就是"割裂感"的来源。
 *   现在头部共用同一底色、统一左右边距，内部只用间距和分隔线组织层级。
 *
 * ┌────────────────────────────────┐
 * │ 邢台路亚                [📍已定位] │ 标题行
 * │ 10月7日 周三 · ✅非禁渔期  日出… ▸ │ 简报行（整行可点，弹出详情）
 * ├────────────────────────────────┤
 * │ 🔍 搜钓点 / 区县 / 鱼种 / 拟饵    │ 搜索（48dp）
 * │ (区县)(类型)(★收藏)(排序)(清除)   │ 筛选（横向可滚，48dp 触摸）
 * ├────────────────────────────────┤
 * │ 内容区 SimplePager              │ 各页自己滚动
 * │  页0 钓点（分布图 + 卡片列表）    │
 * │  页1 记录  页2 我的              │
 * │                      (＋) 悬浮   │
 * ├────────────────────────────────┤
 * │  🎣钓点    📒记录    👤我的     │ 标签栏（56dp，已避让导航区）
 * └────────────────────────────────┘
 *
 * 两条真机上踩过的坑，这里都有防护：
 *   1. 水平 LinearLayout 里带 weight 的空隙 View 用 WRAP_CONTENT 高度，会被按
 *      容器可用高度测量而撑满整行（表现为"只有标题、列表消失"）。全工程统一高度 0。
 *   2. 不做系统栏 Insets 处理，底部标签栏会被手势导航区挤压到只剩十几 dp。
 */
public class MainActivity extends Activity implements SpotListHost, MeHost {

    private static final int REQ_EXPORT_BACKUP = 3001;
    private static final int REQ_EXPORT_CHECKLIST = 3002;
    private static final int REQ_IMPORT_BACKUP = 3003;

    /**
     * 标签栏与筛选标签的高度不再写死，统一由 ScreenFit 按实测屏幕推导。
     * 之前写死 64dp，在 density override 的机器上被压到 39dp，触摸区不达标。
     */
    private int tabBarHeightPx() {
        return ScreenFit.get().tabBar();
    }

    private int chipHeightPx() {
        return ScreenFit.get().flex(40f, 38f, 46f);
    }

    private LinearLayout rootColumn;
    private LinearLayout headerBox;
    private View searchBar;
    private View filterWrap;
    private LinearLayout filterRow;
    private SimplePager pager;
    private LinearLayout tabBar;
    private View fab;

    private final TextView[] tabIcons = new TextView[3];
    private final TextView[] tabLabels = new TextView[3];

    private GpsTracker gps;
    private boolean buildingHeader;
    private boolean uiProbed;

    // 筛选状态由 Activity 持有：切页导致 Fragment 视图重建时不会丢失
    private String keyword = "";
    private String districtFilter = "";
    private String waterTypeFilter = "";
    private String sortMode = "recent";
    private boolean onlyFavorite = false;

    // ================================================================ 生命周期

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CrashLog.install(this);
        // 适配层必须先初始化：后面所有尺寸都要用它推导
        ScreenFit.get().init(this);
        try {
            buildUi(savedInstanceState);
        } catch (Throwable e) {
            CrashLog.record(this, "MainActivity.onCreate 构建界面", e);
            throw new RuntimeException("onCreate 构建界面失败", e);
        }
    }

    private void buildUi(Bundle savedInstanceState) {
        ThemeHelper.prepare(this);
        gps = new GpsTracker(this);
        Seed.loadIfNeeded(this);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);

        rootColumn = Ui.column(this);
        rootColumn.setBackgroundColor(Ui.BG);
        rootColumn.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ---- 头部区（连续的一块，内部不再拆成独立卡片）
        headerBox = Ui.column(this);
        // 顶栏内边距收紧（原来是 10dp 上、0 下）：顶栏合并成一行后不需要那么大的呼吸空间，
        // 省下的每一 dp 都直接让列表提前出现。
        headerBox.setPadding(Ui.dp(this, 16), Ui.dp(this, 6), Ui.dp(this, 16), Ui.dp(this, 4));
        rootColumn.addView(headerBox, Ui.lpMatch());

        rootColumn.addView(buildHeaderControls(), Ui.lpMatch());

        pager = new SimplePager(this);
        pager.setup(getFragmentManager(), new SimplePager.PageFactory() {
            @Override
            public Fragment create(int position) {
                switch (position) {
                    case 0:  return new HomeFragment();
                    case 1:  return new CatchListFragment();
                    default: return new MeFragment();
                }
            }
        }, 3);
        rootColumn.addView(pager, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        tabBar = buildTabBar();
        rootColumn.addView(tabBar, Ui.lpMatch());

        root.addView(rootColumn);

        fab = buildFab();
        FrameLayout.LayoutParams fabLp = new FrameLayout.LayoutParams(
                Ui.dp(this, 56), Ui.dp(this, 56));
        fabLp.gravity = Gravity.BOTTOM | Gravity.END;
        fabLp.rightMargin = Ui.dp(this, 18);
        fabLp.bottomMargin = tabBarHeightPx() + Ui.dp(this, 14);
        root.addView(fab, fabLp);

        setContentView(root);
        ensureContentFitsScreen(root);

        pager.setOnPageChangeListener(new SimplePager.OnPageChangeListener() {
            @Override
            public void onPageSelected(int position) {
                updateTabs(position);
                updateFab(position);
                boolean onSpots = position == 0;
                if (searchBar != null) {
                    searchBar.setVisibility(onSpots ? View.VISIBLE : View.GONE);
                }
                if (filterWrap != null) {
                    filterWrap.setVisibility(onSpots ? View.VISIBLE : View.GONE);
                }
                if (onSpots) {
                    pushFiltersToFragment();
                }
                notifyRefresh();
            }
        });

        root.getViewTreeObserver().addOnGlobalLayoutListener(
                new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        if (uiProbed) {
                            return;
                        }
                        uiProbed = true;
                        // 布局完成，用内容区实测位置校正可用高度（这是适配层的核心一步）
                        ScreenFit.get().refreshUsable(rootColumn);
                        // 校正后标签栏高度可能变化，重新应用一次
                        applyAdaptiveSizes();
                        probeLayout();
                    }
                });

        pager.post(new Runnable() {
            @Override
            public void run() {
                try {
                    pager.commit();
                    updateTabs(pager.getCurrentItem());
                    updateFab(pager.getCurrentItem());
                    buildFilterRow();
                    pushFiltersToFragment();
                } catch (Throwable e) {
                    CrashLog.record(MainActivity.this, "MainActivity.pager.post", e);
                }
            }
        });
    }


    /**
     * 处理系统栏 Insets。
     *
     * ★ 这里有个反直觉的坑：**不能无条件补导航栏内边距**。
     *   如果窗口本身已经被系统让出了导航栏位置（本机就是这种情况），
     *   再给根视图加一份导航栏高度的 padding，就会把底部标签栏整个顶出屏幕，
     *   真机上表现为"看不到下面的 UI、标签栏只剩一条边"。
     *   所以先判断窗口底边是否真的压在导航栏上，只有压上了才补内边距。
     */
    /**
     * 确保整个界面落在屏幕可见范围内。
     *
     * ★ 真机上遇到的问题：窗口高度（3168px）比屏幕可用高度（3026px）多出 142px，
     *   布局是按窗口高度算的，于是底部标签栏的下半截被推到屏幕外面，
     *   表现为"看不到下面的 UI、标签栏只剩一条边、点不准"。
     *
     *   这里在每次布局后比一次：根视图底边若超出屏幕，就补上差额的内边距把它顶回来。
     *   比手动算 Insets 可靠 —— 不依赖任何窗口装饰的假设，只看实测结果。
     */
    private void ensureContentFitsScreen(final View root) {
        root.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b,
                                       int ol, int ot, int or, int ob) {
                try {
                    if (adjusting) {
                        return;
                    }
                    int screenH = getResources().getDisplayMetrics().heightPixels;
                    int[] loc = new int[2];
                    v.getLocationOnScreen(loc);
                    int bottomOnScreen = loc[1] + v.getHeight();
                    int overflow = bottomOnScreen - screenH;

                    if (overflow > 0) {
                        adjusting = true;
                        v.setPadding(v.getPaddingLeft(), v.getPaddingTop(),
                                v.getPaddingRight(), v.getPaddingBottom() + overflow);
                        // 记录一次，便于事后核对（只在调试构建写文件）
                        CrashLog.step(MainActivity.this, "修正底部溢出 " + overflow
                                + "px（根视图底=" + bottomOnScreen + " 屏幕=" + screenH + "）");
                    }
                } catch (Throwable ignored) {
                }
            }
        });
    }

    /** 防止 setPadding 触发的再次布局造成递归 */
    private boolean adjusting;

    private int getNavigationBarHeight() {
        int id = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        if (id > 0) {
            return getResources().getDimensionPixelSize(id);
        }
        return Ui.dp(this, 48);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshHeader();
        buildFilterRow();
        notifyRefresh();
    }

    @Override
    protected void onDestroy() {
        if (gps != null) {
            gps.cancel();
        }
        super.onDestroy();
    }

    // ================================================================ 头部控件区

    /**
     * 搜索框 + 筛选行。与 headerBox 共用同一底色与左右边距，
     * 视觉上属于同一整块，不再各自带卡片边框。
     */
    private View buildHeaderControls() {
        LinearLayout box = Ui.column(this);
        box.setBackgroundColor(Ui.BG);

        searchBar = buildSearchBar();
        box.addView(searchBar, Ui.lpMatch());

        filterWrap = Ui.column(this);
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setFillViewport(false);
        scroll.setLayoutParams(Ui.lpMatch(ScreenFit.get().touchMin()));

        filterRow = Ui.row(this);
        filterRow.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        scroll.addView(filterRow, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ((LinearLayout) filterWrap).addView(scroll, Ui.lpMatch(ScreenFit.get().touchMin()));

        box.addView(filterWrap, Ui.lpMatch());
        return box;
    }

    private View buildSearchBar() {
        LinearLayout wrap = Ui.column(this);
        wrap.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), Ui.dp(this, 2));

        LinearLayout searchBox = Ui.row(this);
        searchBox.setBackground(Ui.round(Ui.SURFACE_2, Ui.RADIUS_BTN, Ui.BORDER, 1f));
        searchBox.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        searchBox.setLayoutParams(Ui.lpMatch(ScreenFit.get().touchMin()));

        searchBox.addView(Ui.text(this, "🔍", 14, Ui.TEXT_FAINT));

        final android.widget.EditText search = new android.widget.EditText(this);
        search.setHint("搜钓点 / 区县 / 鱼种 / 拟饵");
        search.setHintTextColor(Ui.TEXT_FAINT);
        search.setTextColor(Ui.TEXT);
        search.setTextSize(14);
        search.setSingleLine(true);
        search.setBackground(null);
        search.setPadding(Ui.dp(this, 8), 0, 0, 0);
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
                keyword = s.toString();
                HomeFragment f = spotFragment();
                if (f != null) {
                    f.setKeyword(keyword);
                }
            }
        });
        searchBox.addView(search, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        wrap.addView(searchBox);
        return wrap;
    }

    private HomeFragment spotFragment() {
        if (pager == null) {
            return null;
        }
        Fragment f = pager.fragmentAt(0);
        return (f instanceof HomeFragment) ? (HomeFragment) f : null;
    }

    /** 把 Activity 持有的筛选状态同步给钓点页 */
    private void pushFiltersToFragment() {
        HomeFragment f = spotFragment();
        if (f != null) {
            f.applyFilters(keyword, districtFilter, waterTypeFilter, sortMode, onlyFavorite);
        }
    }

    // ================================================================ 筛选行

    private void buildFilterRow() {
        if (filterRow == null) {
            return;
        }
        filterRow.removeAllViews();
        chip(districtFilter.isEmpty() ? "区县" : districtFilter, !districtFilter.isEmpty(),
                new Runnable() {
                    @Override
                    public void run() {
                        showDistrictPicker();
                    }
                });
        chip(waterTypeFilter.isEmpty() ? "类型" : waterTypeFilter, !waterTypeFilter.isEmpty(),
                new Runnable() {
                    @Override
                    public void run() {
                        showWaterTypePicker();
                    }
                });
        chip("★ 收藏", onlyFavorite, new Runnable() {
            @Override
            public void run() {
                onlyFavorite = !onlyFavorite;
                buildFilterRow();
                pushFiltersToFragment();
            }
        });

        String sortText;
        if ("name".equals(sortMode)) {
            sortText = "按名称";
        } else if ("distance".equals(sortMode)) {
            sortText = "按距离";
        } else {
            sortText = "按更新";
        }
        chip(sortText, !"recent".equals(sortMode), new Runnable() {
            @Override
            public void run() {
                showSortPicker();
            }
        });

        if (!districtFilter.isEmpty() || !waterTypeFilter.isEmpty() || onlyFavorite
                || !"recent".equals(sortMode) || !keyword.isEmpty()) {
            chip("✕ 清除", false, new Runnable() {
                @Override
                public void run() {
                    districtFilter = "";
                    waterTypeFilter = "";
                    onlyFavorite = false;
                    sortMode = "recent";
                    buildFilterRow();
                    pushFiltersToFragment();
                }
            });
        }
    }

    /** 高度 40dp + 上下各 4dp 外边距 = 48dp 可点区域 */
    private void chip(String label, final boolean active, final Runnable onClick) {
        final TextView t = Ui.text(this, label, 12, active ? Ui.ACCENT : Ui.TEXT_DIM);
        t.setGravity(Gravity.CENTER);
        t.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), 0);
        // 选中态原来只有 0.22 的底色混合、没有描边，在深色底上几乎看不出
        // "当前在用哪个筛选"。现在加描边并把混合比提到 0.30 —— 筛选状态是
        // 用户最需要一眼确认的信息（决定了他看到的是不是全部钓点）。
        t.setBackground(active
                ? Ui.accentChip(Ui.ACCENT)
                : Ui.ripple(Ui.SURFACE_2, Ui.RADIUS_CHIP, Ui.RIPPLE));
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onClick.run();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, chipHeightPx());
        lp.rightMargin = Ui.dp(this, 8);
        lp.topMargin = Ui.dp(this, 4);
        lp.bottomMargin = Ui.dp(this, 4);
        t.setLayoutParams(lp);
        filterRow.addView(t);
    }

    private void showDistrictPicker() {
        final List<String> options = new ArrayList<>();
        options.add("全部区县");
        for (FishingSpot s : Db.get(this).listSpots()) {
            String d = s.getDistrict();
            if (!d.isEmpty() && !options.contains(d)) {
                options.add(d);
            }
        }
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("按区县筛选")
                .setItems(options.toArray(new String[0]),
                        new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface d, int which) {
                                districtFilter = which == 0 ? "" : options.get(which);
                                buildFilterRow();
                                pushFiltersToFragment();
                            }
                        })
                .show();
    }

    private void showWaterTypePicker() {
        final String[] labels = {"全部类型", "水库", "河流", "湖泊", "黑坑", "渠道", "池塘", "沙坑"};
        int checked = 0;
        for (int i = 0; i < labels.length; i++) {
            if (labels[i].equals(waterTypeFilter)) {
                checked = i;
            }
        }
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("按水域类型筛选")
                .setSingleChoiceItems(labels, checked,
                        new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface d, int which) {
                                waterTypeFilter = which == 0 ? "" : labels[which];
                                d.dismiss();
                                buildFilterRow();
                                pushFiltersToFragment();
                            }
                        })
                .show();
    }

    private void showSortPicker() {
        final String[] labels = {"按最近更新", "按名称", "按距离（需先定位）"};
        final String[] values = {"recent", "name", "distance"};
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("排序方式")
                .setItems(labels, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        sortMode = values[which];
                        buildFilterRow();
                        pushFiltersToFragment();
                    }
                })
                .show();
    }

    // ================================================================ 标题与简报

    private void refreshHeader() {
        if (headerBox == null) {
            return;
        }
        headerBox.removeAllViews();
        if (buildingHeader) {
            return;
        }
        buildingHeader = true;
        try {
            buildHeader();
        } catch (Throwable e) {
            CrashLog.record(this, "MainActivity.refreshHeader", e);
            headerBox.removeAllViews();
            TextView err = Ui.text(this, "顶栏加载出错（已记录到下载目录 xtlure-crash.txt）：\n"
                    + e.getClass().getSimpleName() + ": " + e.getMessage(), 11, Ui.WARN);
            err.setPadding(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4));
            headerBox.addView(err);
        } finally {
            buildingHeader = false;
        }
    }

    /**
     * 标题行 + 简报行。
     * 简报压成一行，想细看再点开 —— 上一版把四格指标 + 月相 + 统计全铺开占掉 240dp，
     * 把内容挤得没地方，是"割裂"的另一半原因。
     */
    private void buildHeader() {
        Calendar now = Calendar.getInstance();
        double refLat = hasLocation() ? Prefs.getDouble(this, Prefs.K_LAST_LAT, 37.0708) : 37.0708;
        double refLon = hasLocation() ? Prefs.getDouble(this, Prefs.K_LAST_LON, 114.5048) : 114.5048;
        double[] ss = SunMoon.sunriseSunset(now.get(Calendar.YEAR),
                now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH), refLat, refLon, 8);
        double moonAge = SunMoon.moonAgeToday();
        int score = SunMoon.fishActivityScore(moonAge);
        final boolean closedNow = FishingSpot.inClosedSeasonNow();

        // ============================================================ 单一顶栏行
        //
        // ★ 原来这里是两行（标题行 44dp + 日期行 44dp = 88dp），加上外边距后
        //   顶栏实测占 99.7dp，是固定区里最大的一块。
        //
        //   合并成一行后：左边标题，右边"分布"入口 + 定位胶囊。
        //   日期 / 禁渔状态 / 日出月相这些信息**没有丢**，仍然是整行可点，
        //   点开看当日详情（原来只有日期那一行可点）。
        //   省下的 46dp 直接让列表提前出现。
        LinearLayout r1 = Ui.row(this);
        TextView title = Ui.title(this, "邢台路亚");
        title.setTextSize(19);
        r1.addView(title);

        // 禁渔期用一个小圆点提示，不再单独占行
        if (closedNow) {
            TextView dot = Ui.text(this, "⛔", 11, Ui.WARN);
            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dotLp.leftMargin = Ui.dp(this, 6);
            dot.setLayoutParams(dotLp);
            r1.addView(dot);
        }

        r1.addView(spacer());

        // 地图入口：与标题同一行，不额外占纵向空间。
        // 用 ACCENT 描边 + ACCENT 文字 —— 它是这一行里唯一显眼的元素，
        // 顺带解决"强调色几乎不用、界面显得单调"的问题。
        TextView mapBtn = Ui.text(this, "🗺 分布", 12, Ui.ACCENT);
        mapBtn.setGravity(Gravity.CENTER);
        mapBtn.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        mapBtn.setBackground(Ui.accentChip(Ui.ACCENT));
        LinearLayout.LayoutParams mbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 34));
        mbLp.rightMargin = Ui.dp(this, 8);
        mapBtn.setLayoutParams(mbLp);
        mapBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, MapActivity.class));
            }
        });
        r1.addView(mapBtn);

        final TextView locate = Ui.text(this, hasLocation() ? "📍 已定位" : "📍 定位", 12,
                hasLocation() ? Ui.ACCENT : Ui.TEXT_DIM);
        locate.setGravity(Gravity.CENTER);
        locate.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        locate.setBackground(Ui.ripple(Ui.SURFACE_2, Ui.RADIUS_CHIP, Ui.RIPPLE));
        locate.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 34)));
        locate.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestMyLocation();
            }
        });
        r1.addView(locate);

        // 整行可点 -> 当日详情（日期、禁渔状态、日出日落、月相都在里面）
        r1.setBackground(Ui.ripple(0x00000000, Ui.RADIUS_BTN, Ui.RIPPLE));
        r1.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showDayDetail();
            }
        });
        LinearLayout.LayoutParams r1Lp = Ui.lpMatch(Ui.dp(this, 46));
        r1.setLayoutParams(r1Lp);
        headerBox.addView(r1);
        // 一行次要信息（日期 / 日出 / 月相）。11sp 小字，高度自适应，
        // 不放按钮 —— 与顶栏行一起构成"一块"，而不是两块各自为政。
        LinearLayout briefRow = Ui.row(this);
        briefRow.addView(Ui.text(this, Ui.dateText(now) + " · "
                + (closedNow ? "禁渔期" : "可钓"), 11, closedNow ? Ui.WARN : Ui.OK));
        briefRow.addView(spacer());
        briefRow.addView(Ui.text(this, "日出 " + SunMoon.hourToTime(ss[0]) + " · "
                + SunMoon.moonPhaseEmoji(moonAge) + " " + score + "分 ▸", 11, Ui.TEXT_FAINT));
        briefRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showDayDetail();
            }
        });
        LinearLayout.LayoutParams brLp = Ui.lpMatch();
        brLp.topMargin = Ui.dp(this, 2);
        briefRow.setLayoutParams(brLp);
        headerBox.addView(briefRow);

        if (closedNow) {
            TextView banner = Ui.text(this, "⛔ 禁渔期 " + FishingSpot.CLOSED_SEASON_TEXT
                    + "：通告允许钓具垂钓，禁一人多杆、多钩线钓、锚鱼", 10, Ui.WARN);
            banner.setPadding(Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6));
            banner.setBackground(Ui.round(Ui.blend(Ui.WARN, Ui.SURFACE, 0.16f),
                    Ui.RADIUS_BTN, Ui.blend(Ui.WARN, Ui.SURFACE, 0.45f), 1f));
            LinearLayout.LayoutParams blp = Ui.lpMatch();
            blp.topMargin = Ui.dp(this, 6);
            banner.setLayoutParams(blp);
            banner.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showDayDetail();
                }
            });
            headerBox.addView(banner);
        }
    }

    /** 弹性空隙：高度必须给 0。给 WRAP_CONTENT 会被按容器可用高度测量而撑满整行 */
    private View spacer() {
        // ★ 这里必须是「高度 0 的弹性占位」：它只负责把后面的元素推到右边。
        //   横向布局里高度是交叉轴、不会被 weight 撑开，所以 0 高度没问题。
        //   （曾经误把它改成 WRAP_CONTENT，顶栏随即出现异常，
        //     真机上表现为"整个屏幕只剩标题和一个悬浮按钮"。）
        View v = new View(this);
        v.setLayoutParams(Ui.weightedSpacer());
        return v;
    }

    private void showDayDetail() {
        try {
            Db db = Db.get(this);
            Calendar now = Calendar.getInstance();
            double refLat = hasLocation() ? Prefs.getDouble(this, Prefs.K_LAST_LAT, 37.0708) : 37.0708;
            double refLon = hasLocation() ? Prefs.getDouble(this, Prefs.K_LAST_LON, 114.5048) : 114.5048;
            double[] ss = SunMoon.sunriseSunset(now.get(Calendar.YEAR),
                    now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH), refLat, refLon, 8);
            double moonAge = SunMoon.moonAgeToday();
            int score = SunMoon.fishActivityScore(moonAge);
            boolean closedNow = FishingSpot.inClosedSeasonNow();

            StringBuilder sb = new StringBuilder();
            sb.append(Ui.dateText(now)).append("\n\n");
            sb.append("日出　").append(SunMoon.hourToTime(ss[0])).append('\n');
            sb.append("日落　").append(SunMoon.hourToTime(ss[1])).append('\n');
            sb.append("黄金窗口　").append(SunMoon.hourToTime(ss[0])).append("–")
                    .append(SunMoon.hourToTime(ss[0] + 2)).append("　与　")
                    .append(SunMoon.hourToTime(ss[1] - 2)).append("–")
                    .append(SunMoon.hourToTime(ss[1])).append('\n');
            sb.append("月相　").append(SunMoon.moonPhaseEmoji(moonAge)).append(' ')
                    .append(SunMoon.moonPhaseName(moonAge))
                    .append("（月龄 ").append(String.format(Locale.CHINA, "%.1f", moonAge))
                    .append(" 天）").append('\n');
            sb.append("鱼口参考　").append(score).append(" 分（启发式，仅供参考）\n\n");
            sb.append("禁渔期：").append(closedNow ? "⛔ 当前处于禁渔期内" : "✅ 当前不在禁渔期")
                    .append('\n');
            sb.append(FishingSpot.CLOSED_SEASON_RULE).append("\n\n");
            sb.append("水库 ").append(countByType(db, FishingSpot.TYPE_RESERVOIR))
                    .append(" · 河流 ").append(countByType(db, FishingSpot.TYPE_RIVER))
                    .append(" · 湖泊 ").append(countByType(db, FishingSpot.TYPE_LAKE))
                    .append(" · 收费塘 ").append(countByType(db, FishingSpot.TYPE_PAYPOND))
                    .append('\n');
            sb.append("合计 ").append(db.countSpots()).append(" 个钓点，你的 ")
                    .append(db.countCatches()).append(" 次作钓记录");

            new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("今日钓讯")
                    .setMessage(sb.toString())
                    .setPositiveButton("知道了", null)
                    .show();
        } catch (Throwable e) {
            CrashLog.record(this, "MainActivity.showDayDetail", e);
        }
    }

    private int countByType(Db db, int type) {
        int n = 0;
        for (FishingSpot s : db.listSpots()) {
            if (s.getWaterType() == type) {
                n++;
            }
        }
        return n;
    }

    // ================================================================ 标签栏

    /**
     * 底部标签栏。
     *
     * ★ 高度不用固定值，改成内容自适应 + 上下内边距。
     *   原因：本机窗口高度（3168px）比屏幕可用高度（3026px）多出 142px，
     *   固定高度在这个被压缩的空间里会被挤小 —— 实测请求 240px 只拿到 147px。
     *   改用 WRAP_CONTENT 后，高度由「图标 + 文字 + 上下各 12dp 内边距」自然决定
     *   （约 61dp），每个标签再撑满整条高度，触摸区稳定 >= 48dp。
     */
    private LinearLayout buildTabBar() {
        LinearLayout bar = Ui.row(this);
        bar.setBackgroundColor(Ui.SURFACE);

        // 上下内边距 = (标签栏总高 - 触摸区)/2，保证标签项始终是 48dp 触摸高度、
        // 且在矮屏上不会把内容挤没。这个式子对不同 dpi 都成立，无需换算式地调参。
        final int touch = ScreenFit.get().touchMin();
        int barH = tabBarHeightPx();
        if (barH < touch) {
            barH = touch;
        }
        int vPad = Math.max(Ui.dp(this, 3), (barH - touch) / 2);
        bar.setPadding(0, vPad, 0, vPad);
        bar.setLayoutParams(Ui.lpMatch());

        String[] icons = {"🎣", "📒", "👤"};
        String[] labels = {"钓点", "记录", "我的"};

        for (int i = 0; i < 3; i++) {
            final int idx = i;
            LinearLayout item = Ui.column(this);
            item.setGravity(Gravity.CENTER);
            item.setBackground(Ui.ripple(0x00000000, 0, Ui.RIPPLE));

            TextView ic = Ui.text(this, icons[i], 20, Ui.TEXT_FAINT);
            ic.setGravity(Gravity.CENTER);
            item.addView(ic);

            TextView lb = Ui.text(this, labels[i], 11, Ui.TEXT_FAINT);
            lb.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lbLp = Ui.lpMatch();
            lbLp.topMargin = Ui.dp(this, 2);
            lb.setLayoutParams(lbLp);
            item.addView(lb);

            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (pager.getCurrentItem() == idx) {
                        notifyRefresh();
                    } else {
                        pager.setCurrentItem(idx, true);
                    }
                }
            });

            tabIcons[i] = ic;
            tabLabels[i] = lb;
            LinearLayout.LayoutParams itemLp = new LinearLayout.LayoutParams(0,
                    touch, 1f);
            bar.addView(item, itemLp);
        }
        return bar;
    }

    private void updateTabs(int selected) {
        for (int i = 0; i < 3; i++) {
            boolean on = (i == selected);
            tabIcons[i].setTextColor(on ? Ui.ACCENT : Ui.TEXT_FAINT);
            tabLabels[i].setTextColor(on ? Ui.ACCENT : Ui.TEXT_FAINT);
            tabIcons[i].setAlpha(on ? 1f : 0.55f);
            tabLabels[i].setAlpha(on ? 1f : 0.75f);
        }
    }

    // ================================================================ 悬浮按钮

    private View buildFab() {
        TextView f = Ui.text(this, "＋", 26, Ui.ON_ACCENT);
        f.setGravity(Gravity.CENTER);
        f.setBackground(Ui.circle(Ui.ACCENT));
        f.setElevation(Ui.dp(this, 6));
        f.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int page = pager.getCurrentItem();
                if (page == 0) {
                    startActivity(new Intent(MainActivity.this, EditSpotActivity.class));
                } else if (page == 1) {
                    CatchDialog.show(MainActivity.this, null, null);
                }
            }
        });
        return f;
    }

    private void updateFab(int page) {
        if (fab != null) {
            fab.setVisibility(page == 2 ? View.INVISIBLE : View.VISIBLE);
        }
    }

    // ================================================================ 定位

    public boolean hasLocation() {
        return !Double.isNaN(Prefs.getDouble(this, Prefs.K_LAST_LAT, Double.NaN));
    }

    private void requestMyLocation() {
        if (!GpsTracker.hasPermission(this)) {
            GpsTracker.requestPermission(this);
            return;
        }
        Toast.makeText(this, "正在定位…", Toast.LENGTH_SHORT).show();
        gps.requestOnce(new GpsTracker.Callback() {
            @Override
            public void onLocation(double lat, double lon, float acc, String provider) {
                Prefs.setDouble(MainActivity.this, Prefs.K_LAST_LAT, lat);
                Prefs.setDouble(MainActivity.this, Prefs.K_LAST_LON, lon);
                Toast.makeText(MainActivity.this,
                        "定位成功，精度 " + (int) acc + " 米（" + provider + "）",
                        Toast.LENGTH_SHORT).show();
                refreshHeader();
                notifyRefresh();
            }

            @Override
            public void onFail(String reason) {
                Toast.makeText(MainActivity.this, "定位失败：" + reason, Toast.LENGTH_LONG).show();
            }
        }, 25000);
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
                requestMyLocation();
            } else {
                Toast.makeText(this, "未授予定位权限，仍可手动记录钓点", Toast.LENGTH_LONG).show();
            }
        }
    }

    // ================================================================ 供 Fragment 调用

    @Override
    public double userLat() {
        return Prefs.getDouble(this, Prefs.K_LAST_LAT, Double.NaN);
    }

    @Override
    public double userLon() {
        return Prefs.getDouble(this, Prefs.K_LAST_LON, Double.NaN);
    }

    private void notifyRefresh() {
        try {
            Fragment f = pager == null ? null : pager.currentFragment();
            if (f instanceof Refreshable) {
                ((Refreshable) f).refresh();
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onSpotsChanged() {
        refreshHeader();
        notifyRefresh();
    }

    @Override
    public void openSpotDetail(long spotId) {
        Intent i = new Intent(this, SpotDetailActivity.class);
        i.putExtra(SpotDetailActivity.EXTRA_SPOT_ID, spotId);
        startActivity(i);
    }

    // ================================================================ 布局缺陷自检

    private void probeLayout() {
        try {
            int screenH = getResources().getDisplayMetrics().heightPixels;
            List<String> problems = new ArrayList<>();

            // 记录真实尺寸，用于定位"内容被推出窗口"这类问题
            // （屏幕高度 ≠ 应用可用高度，两者混淆会得出错误结论）
            StringBuilder dim = new StringBuilder("测量: 屏幕=").append(screenH)
                    .append("px/").append(getResources().getDisplayMetrics().densityDpi).append("dpi");
            View decor = getWindow() == null ? null : getWindow().getDecorView();
            if (decor != null) {
                dim.append(" decor高=").append(decor.getHeight());
            }
            View content = findViewById(android.R.id.content);
            if (content != null) {
                int[] cl = new int[2];
                content.getLocationOnScreen(cl);
                dim.append(" content=").append(cl[1]).append("..")
                        .append(cl[1] + content.getHeight());
            }
            if (tabBar != null) {
                int[] tl = new int[2];
                tabBar.getLocationOnScreen(tl);
                dim.append(" tabBar=").append(tl[1]).append("..").append(tl[1] + tabBar.getHeight())
                        .append("(h=").append(tabBar.getHeight())
                        .append(" 期望").append(tabBarHeightPx()).append(")");
            }
            // 尺寸换算属于开发期诊断，只在调试构建写文件（正式包每次启动都刷一条太吵）
            CrashLog.step(this, dim.toString());

            if (tabBar == null || tabBar.getHeight() < Ui.dp(this, 36)) {
                problems.add("标签栏高度异常: "
                        + (tabBar == null ? "null" : tabBar.getHeight() + "px")
                        + "（期望 " + tabBarHeightPx() + "px）");
            }
            if (pager == null) {
                problems.add("内容区 pager 为 null");
            } else if (pager.getParent() == null) {
                problems.add("内容区 pager 未加入视图树");
            } else if (pager.getHeight() < Ui.dp(this, 120)) {
                problems.add("内容区高度异常: " + pager.getHeight() + "px（应 >= 120dp）");
            }
            if (headerBox != null && headerBox.getHeight() > screenH * 0.45) {
                problems.add("顶栏占屏过高: " + headerBox.getHeight() + "px / 屏幕 " + screenH + "px");
            }
            if (tabBar != null && tabBar.getBottom() > screenH) {
                problems.add("标签栏超出屏幕下沿: bottom=" + tabBar.getBottom() + " > " + screenH);
            }

            if (!problems.isEmpty()) {
                StringBuilder sb = new StringBuilder("布局异常（这些缺陷会让界面看起来是空的）:\n");
                for (String p : problems) {
                    sb.append("  ⚠ ").append(p).append('\n');
                }
                sb.append("  屏幕高=").append(screenH).append('\n');
                sb.append("  根视图底部padding=")
                        .append(rootColumn == null ? -1
                                : rootColumn.getRootView().getPaddingBottom())
                        .append('\n');
                if (headerBox != null) {
                    sb.append("  headerBox ").append(describe(headerBox)).append('\n');
                }
                if (tabBar != null) {
                    sb.append("  tabBar    ").append(describe(tabBar)).append('\n');
                }
                if (pager != null) {
                    sb.append("  pager     ").append(describe(pager)).append('\n');
                }
                CrashLog.record(this, "MainActivity.probeLayout",
                        new IllegalStateException(sb.toString()));
            }
        } catch (Throwable ignored) {
        }
    }

    /** 布局完成后按实测可用高度重新应用一次关键尺寸 */
    private void applyAdaptiveSizes() {
        try {
            if (tabBar != null) {
                final int touch = ScreenFit.get().touchMin();
                int barH = tabBarHeightPx();
                if (barH < touch) {
                    barH = touch;
                }
                int vPad = Math.max(Ui.dp(this, 3), (barH - touch) / 2);
                tabBar.setPadding(0, vPad, 0, vPad);
                for (int i = 0; i < tabBar.getChildCount(); i++) {
                    View child = tabBar.getChildAt(i);
                    ViewGroup.LayoutParams lp = child.getLayoutParams();
                    if (lp instanceof LinearLayout.LayoutParams) {
                        lp.height = touch;
                        child.setLayoutParams(lp);
                    }
                }
            }
            if (fab != null) {
                ViewGroup.LayoutParams lp = fab.getLayoutParams();
                if (lp instanceof FrameLayout.LayoutParams) {
                    ((FrameLayout.LayoutParams) lp).bottomMargin =
                            tabBarHeightPx() + Ui.dp(this, 14);
                    fab.setLayoutParams(lp);
                }
            }
        } catch (Throwable e) {
            CrashLog.record(this, "MainActivity.applyAdaptiveSizes", e);
        }
    }

    private String describe(View v) {
        if (v == null) {
            return "null";
        }
        return "h=" + v.getHeight() + " w=" + v.getWidth()
                + " vis=" + (v.getVisibility() == View.VISIBLE ? "V"
                : v.getVisibility() == View.INVISIBLE ? "I" : "G")
                + " @y=" + v.getTop() + ".." + v.getBottom();
    }

    // ================================================================ 导出 / 导入

    private String exportFileName(String prefix, String ext) {
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmm", Locale.CHINA)
                .format(new Date());
        return prefix + "-" + stamp + "." + ext;
    }

    @Override
    public void exportBackupViaPicker() {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/json");
        i.putExtra(Intent.EXTRA_TITLE, exportFileName("邢台路亚-备份", "json"));
        try {
            startActivityForResult(i, REQ_EXPORT_BACKUP);
        } catch (Exception e) {
            exportToAppDir(true);
        }
    }

    @Override
    public void exportChecklist() {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/markdown");
        i.putExtra(Intent.EXTRA_TITLE, exportFileName("邢台路亚-钓点清单", "md"));
        try {
            startActivityForResult(i, REQ_EXPORT_CHECKLIST);
        } catch (Exception e) {
            exportToAppDir(false);
        }
    }

    @Override
    public void importFromPicker() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, REQ_IMPORT_BACKUP);
        } catch (Exception e) {
            Toast.makeText(this, "打不开文件选择器", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        android.net.Uri uri = data.getData();
        if (requestCode == REQ_EXPORT_BACKUP) {
            writeToUri(uri, buildBackupJson());
        } else if (requestCode == REQ_EXPORT_CHECKLIST) {
            writeToUri(uri, Exporter.toMarkdown(Db.get(this).listSpots()));
        } else if (requestCode == REQ_IMPORT_BACKUP) {
            doImport(uri);
        }
    }

    private String buildBackupJson() {
        Db db = Db.get(this);
        return Exporter.toBackupJson(db.listSpots(), db.listCatches());
    }

    private void exportToAppDir(boolean backup) {
        try {
            File dir = getExternalFilesDir(null);
            if (dir == null) {
                dir = getFilesDir();
            }
            File f = new File(dir, exportFileName(backup ? "邢台路亚-备份" : "邢台路亚-钓点清单",
                    backup ? "json" : "md"));
            java.io.FileOutputStream out = new java.io.FileOutputStream(f);
            out.write((backup ? buildBackupJson()
                    : Exporter.toMarkdown(Db.get(this).listSpots())).getBytes("UTF-8"));
            out.close();
            Toast.makeText(this, "已导出到：\n" + f.getAbsolutePath(), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "导出失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void writeToUri(android.net.Uri uri, String text) {
        try {
            OutputStream out = getContentResolver().openOutputStream(uri, "wt");
            if (out == null) {
                throw new java.io.IOException("无法打开目标文件");
            }
            out.write(text.getBytes("UTF-8"));
            out.flush();
            out.close();
            Toast.makeText(this, "导出成功", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "导出失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void doImport(android.net.Uri uri) {
        try {
            java.io.InputStream in = getContentResolver().openInputStream(uri);
            if (in == null) {
                throw new java.io.IOException("无法读取文件");
            }
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            in.close();
            String text = new String(bos.toByteArray(), "UTF-8");
            text = text.replace("\"timeText\"", "\"time\"");

            Map<String, Object> obj = Json.obj(Json.parse(text));
            if (obj == null) {
                Toast.makeText(this, "文件格式不对，应该是本 App 导出的 JSON 备份",
                        Toast.LENGTH_LONG).show();
                return;
            }

            Db db = Db.get(this);
            int addedSpots = 0, addedCatches = 0, skipped = 0;

            List<Object> spotArr = Json.arr(obj.get("spots"));
            if (spotArr != null) {
                for (Object o : spotArr) {
                    Map<String, Object> m = Json.obj(o);
                    if (m == null) {
                        continue;
                    }
                    FishingSpot s = Seed.fromMap(m);
                    if (s.getName().isEmpty()) {
                        continue;
                    }
                    if (db.spotNameExists(s.getName(), -1)) {
                        skipped++;
                        continue;
                    }
                    db.insertSpot(s);
                    addedSpots++;
                }
            }

            List<Object> catchArr = Json.arr(obj.get("catches"));
            if (catchArr != null) {
                for (Object o : catchArr) {
                    Map<String, Object> m = Json.obj(o);
                    if (m == null) {
                        continue;
                    }
                    CatchRecord r = catchFromMap(m, db);
                    db.insertCatch(r);
                    addedCatches++;
                }
            }

            StringBuilder msg = new StringBuilder("导入完成：");
            msg.append(addedSpots).append(" 个钓点、").append(addedCatches).append(" 条记录");
            if (skipped > 0) {
                msg.append("\n（").append(skipped).append(" 个同名钓点已跳过，不会重复）");
            }
            Toast.makeText(this, msg.toString(), Toast.LENGTH_LONG).show();

            refreshHeader();
            notifyRefresh();
        } catch (Exception e) {
            Toast.makeText(this, "导入失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private CatchRecord catchFromMap(Map<String, Object> m, Db db) {
        CatchRecord r = new CatchRecord();
        r.setSpotName(Json.str(m, "spotName"));
        r.setTargetFish(Json.str(m, "targetFish"));
        r.setCaughtFish(Json.str(m, "caughtFish"));
        r.setCount(Json.intVal(m, "count", 0));
        r.setSizeNote(Json.str(m, "sizeNote"));
        r.setLureUsed(Json.str(m, "lureUsed"));
        r.setMethod(Json.str(m, "method"));
        r.setWeather(Json.str(m, "weather"));
        r.setWind(Json.str(m, "wind"));
        r.setWaterNote(Json.str(m, "waterNote"));
        r.setPressure(Json.str(m, "pressure"));
        r.setAirTemp(Json.str(m, "airTemp"));
        r.setWaterTemp(Json.str(m, "waterTemp"));
        r.setNote(Json.str(m, "note"));
        r.setRating(Json.intVal(m, "rating", 0));
        r.setCreatedAt(System.currentTimeMillis());

        long t = parseTime(Json.str(m, "time"));
        r.setTime(t == 0 ? System.currentTimeMillis() : t);

        long sid = (long) Json.num(m, "spotId", -1);
        if (sid <= 0 && !r.getSpotName().isEmpty()) {
            for (FishingSpot s : db.listSpots()) {
                if (s.getName().equals(r.getSpotName())) {
                    sid = s.getId();
                    break;
                }
            }
        }
        r.setSpotId(sid);
        return r;
    }

    private long parseTime(String s) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        try {
            return (long) Double.parseDouble(s.trim());
        } catch (NumberFormatException ignored) {
        }
        String[] patterns = {"yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd"};
        for (String p : patterns) {
            try {
                Date d = new SimpleDateFormat(p, Locale.CHINA).parse(s.trim());
                if (d != null) {
                    return d.getTime();
                }
            } catch (Exception ignored) {
            }
        }
        return 0;
    }
}
