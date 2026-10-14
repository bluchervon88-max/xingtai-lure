package com.xtlure.app.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import com.xtlure.app.model.FishingSpot;
import com.xtlure.app.util.ScreenFit;
import com.xtlure.app.util.Ui;

import java.util.ArrayList;
import java.util.List;

/**
 * 钓点列表容器。
 *
 * ★ 为什么不用 ListView
 *   ListView 靠"回收 + 重新绑定"复用行视图，任何一条属性忘了重置，
 *   就会把上一个钓点的内容残留在新行上 —— 真机上表现为
 *   "同一个位置显示了两个不同的钓点"，而且时好时坏、极难复现。
 *
 *   本 App 的钓点数只有几十条，一次性把卡片全部构建出来的代价很小
 *   （每张卡 8 个 TextView，73 张约 600 个视图，现代手机毫无压力），
 *   换来的是**结果完全确定**：不存在回收，也就不可能存在残留。
 *
 *   同时这样一整块都能跟着手指滚动，上半部分（分布入口、筛选）与
 *   下半部分（列表）成为同一个滚动体系，不再有"两块拼起来"的割裂感。
 */
public class SpotListView extends ScrollView {

    /** 卡片点击 */
    public interface OnSpotClick {
        void onClick(FishingSpot spot);

        void onLongClick(FishingSpot spot);
    }

    private final LinearLayout container;
    private OnSpotClick listener;
    private final List<View> cardViews = new ArrayList<>();

    /** 悬浮按钮尺寸与它距底部的间距（与 MainActivity.buildFab 保持一致） */
    private static final int FAB_DP = 56;
    private static final int FAB_BOTTOM_GAP_DP = 14;

    public SpotListView(Context c) {
        super(c);
        setFillViewport(true);
        setVerticalScrollBarEnabled(false);
        container = Ui.column(c);
        // ★ 底部留白必须让开悬浮按钮。
        //
        //   悬浮按钮是浮在列表之上的（FrameLayout 里后加的子视图），
        //   原来只留 12dp，滑到底时按钮仍然压住最后一张卡片的右侧内容
        //   （实测遮住"距离 · 鱼种"那一段），而且那块区域点不到卡片 ——
        //   用户反馈的"最下方用不了"就是这个。
        //
        //   留出「按钮高度 + 按钮底距 + 一点余量」之后，最后一张卡片可以
        //   完整滑到按钮上方。
        int fabClearance = Ui.dp(c, FAB_DP + FAB_BOTTOM_GAP_DP) + Ui.dp(c, 12);
        container.setPadding(0, 0, 0, fabClearance);
        addView(container, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    public void setOnSpotClick(OnSpotClick l) {
        this.listener = l;
    }

    /** 在列表顶部插入一个固定视图（页头），它随列表一起滚动 */
    public void setHeader(View v) {
        if (container.getChildCount() > 0) {
            container.removeViewAt(0);
        }
        if (v != null) {
            container.addView(v, 0, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    /** 在列表底部追加一个固定视图（页脚） */
    public void addFooter(View v) {
        if (v != null) {
            container.addView(v, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    /**
     * 重建全部卡片。
     *
     * 每次都重新构建，不做复用 —— 正确性优先。
     * 数据量增长到几百条以上时应改用 RecyclerView 并严格实现 onViewRecycled。
     */
    public void setSpots(List<FishingSpot> spots, double userLat, double userLon,
                         List<String> statLines) {
        // 先移除所有卡片视图（保留页头/页脚由调用方管理，这里只清卡片）
        for (View v : cardViews) {
            container.removeView(v);
        }
        cardViews.clear();

        if (spots == null || spots.isEmpty()) {
            return;
        }

        final Context c = getContext();
        final int gap = ScreenFit.get().cardGap();

        for (int i = 0; i < spots.size(); i++) {
            final FishingSpot s = spots.get(i);
            String stat = (statLines != null && i < statLines.size()) ? statLines.get(i) : "";

            View card = SpotCard.build(c, s, userLat, userLon, stat,
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            if (listener != null) {
                                listener.onClick(s);
                            }
                        }
                    },
                    new View.OnLongClickListener() {
                        @Override
                        public boolean onLongClick(View v) {
                            if (listener != null) {
                                listener.onLongClick(s);
                            }
                            return true;
                        }
                    });

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = gap;
            lp.rightMargin = gap;
            lp.topMargin = gap;
            container.addView(card, lp);
            cardViews.add(card);
        }
    }

    /** 当前卡片数量，供自检使用 */
    public int cardCount() {
        return cardViews.size();
    }

    public void scrollToTop() {
        scrollTo(0, 0);
    }
}
