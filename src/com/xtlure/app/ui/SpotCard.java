package com.xtlure.app.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.xtlure.app.core.CoordinateTransform;
import com.xtlure.app.model.FishingSpot;
import com.xtlure.app.util.Ui;

/**
 * 单张钓点卡片。
 *
 * 全部用代码构建，且**每次都是全新对象**（不参与视图回收复用），
 * 所以不存在"属性残留"的问题 —— 这是把列表错乱问题从根上消掉的关键。
 *
 * ★ 版面压缩（2026-10 改版）
 *   原来纵向排 5 行（名称 / 胶囊行 / 鱼种 / 统计 / 隐式间距），实测 110.9dp，
 *   一屏只能看到 3.7 张，浏览效率低。
 *   现在压到 3 行（名称 / 胶囊行+鱼种 / 一行状态），目标 ~92dp，
 *   一屏可见 5~6 张。做法：
 *     · 鱼种从独立一行改成接在距离后面（同为次要信息，共用一行）
 *     · 内边距 12dp -> 9dp，行间距 8/7/6 -> 6/5/4
 *   卡片本身仍是一个整块点击区，没有把信息拆到多个可点元素上。
 *
 * 版面：
 *   ┃ 🏞 朱庄水库·主坝区                    ★
 *   ┃ [水库] 沙河市 [限钓]   21 km · 翘嘴、青鲈
 *   ┃ 最近 10-03 · 累计 12 尾 · 坐标未核实
 *   ┗ 左侧竖条用类型色，快速滑动时也能分辨
 */
final class SpotCard {

    private SpotCard() {
    }

    static View build(Context c, final FishingSpot s, double userLat, double userLon,
                      String statLine,
                      View.OnClickListener onClick, View.OnLongClickListener onLongClick) {

        final int typeColor = Ui.waterTypeColor(s.getWaterType());

        LinearLayout card = Ui.row(c);
        card.setBackground(Ui.cardRipple(c));

        // 左侧类型色竖条
        View bar = new View(c);
        bar.setBackground(Ui.round(typeColor, 0));
        card.addView(bar, new LinearLayout.LayoutParams(
                Ui.dp(c, 4), ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout body = Ui.column(c);
        body.setPadding(Ui.dp(c, 13), Ui.dp(c, 9), Ui.dp(c, 13), Ui.dp(c, 9));
        card.addView(body, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // ---- 第一行：类型图标 + 名称 + 收藏星
        LinearLayout r1 = Ui.row(c);
        TextView name = Ui.text(c, FishingSpot.waterTypeIcon(s.getWaterType()) + " " + s.getName(),
                16, Ui.TEXT);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        r1.addView(name, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (s.isFavorite()) {
            TextView fav = Ui.text(c, "★", 16, Ui.FAV);
            fav.setPadding(Ui.dp(c, 6), 0, 0, 0);
            r1.addView(fav);
        }
        body.addView(r1);

        // ---- 第二行：类型胶囊 + 区县 + 状态胶囊 + 距离 · 鱼种
        LinearLayout r2 = Ui.row(c);
        LinearLayout.LayoutParams r2lp = Ui.lpMatch();
        r2lp.topMargin = Ui.dp(c, 6);
        r2.setLayoutParams(r2lp);

        r2.addView(chip(c, FishingSpot.waterTypeName(s.getWaterType()), typeColor));

        boolean hasDistrict = !s.getDistrict().isEmpty();
        TextView district = Ui.text(c, hasDistrict ? s.getDistrict() : "未标区县",
                Ui.TEXT_SMALL, hasDistrict ? Ui.TEXT_DIM : Ui.TEXT_FAINT);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.leftMargin = Ui.dp(c, 8);
        district.setLayoutParams(dlp);
        r2.addView(district);

        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.leftMargin = Ui.dp(c, 6);
        TextView status = chip(c, FishingSpot.statusName(s.getStatus()),
                Ui.statusColor(s.getStatus()));
        status.setLayoutParams(slp);
        r2.addView(status);

        r2.addView(new View(c), new LinearLayout.LayoutParams(0, 0, 1f));

        // 距离 + 鱼种合并到同一行右侧。
        // 距离用主色（这是最常看的一个数），鱼种跟在后面用弱色 ——
        // 原来鱼种独占一行，是卡片过高的一大来源。
        // 距离是首页唯一的"决策数字"（决定去不去），给它更大字号 + 粗体。
        // 14sp 仍小于名称的 16sp，不会抢标题；但明显高于同行的 12sp 次要文字。
        TextView dist = Ui.text(c, "", 14, Ui.TEXT_FAINT);
        dist.setTypeface(dist.getTypeface(), Typeface.BOLD);
        boolean hasFish = !s.getFishSpecies().isEmpty();
        StringBuilder tail = new StringBuilder();
        if (s.isHasCoord() && !Double.isNaN(userLat)) {
            tail.append(CoordinateTransform.formatDistance(
                    CoordinateTransform.distanceMeters(userLat, userLon, s.getLat(), s.getLon())));
            dist.setTextColor(Ui.ACCENT);
        } else if (!s.isHasCoord()) {
            tail.append("无坐标");
        }
        if (hasFish) {
            if (tail.length() > 0) {
                tail.append(" · ");
            }
            tail.append(s.getFishSpecies());
        }
        dist.setText(tail.toString());
        dist.setMaxLines(1);
        dist.setEllipsize(android.text.TextUtils.TruncateAt.END);
        // 给左侧留出空间，避免长区县名把它挤没
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.leftMargin = Ui.dp(c, 8);
        dist.setLayoutParams(tlp);
        r2.addView(dist);
        body.addView(r2);

        // ---- 第三行：作钓记录 / 禁渔期 / 坐标可信度（原本分散在两行）
        StringBuilder sb = new StringBuilder(statLine == null ? "" : statLine);
        final boolean closedNow = s.isClosedNow();
        if (closedNow) {
            sb.append("　⛔ 禁渔期");
        } else if (s.getConfidence() == FishingSpot.CONF_UNVERIFIED && s.isHasCoord()) {
            sb.append("　· 坐标未核实");
        }
        if (sb.length() > 0) {
            TextView stat = Ui.text(c, sb.toString(), Ui.TEXT_TINY,
                    closedNow ? Ui.WARN : Ui.TEXT_FAINT);
            LinearLayout.LayoutParams stlp = Ui.lpMatch();
            stlp.topMargin = Ui.dp(c, 4);
            stat.setLayoutParams(stlp);
            stat.setMaxLines(1);
            stat.setEllipsize(android.text.TextUtils.TruncateAt.END);
            body.addView(stat);
        }

        card.setOnClickListener(onClick);
        card.setOnLongClickListener(onLongClick);
        return card;
    }

    /** 小胶囊：文字用主色，底色是同色系淡底 + 淡描边 */
    private static TextView chip(Context c, String text, int color) {
        TextView t = Ui.text(c, text, Ui.TEXT_TINY, color);
        t.setGravity(Gravity.CENTER);
        t.setPadding(Ui.dp(c, 8), Ui.dp(c, 3), Ui.dp(c, 8), Ui.dp(c, 3));
        t.setBackground(Ui.round(Ui.blend(color, Ui.SURFACE, 0.80f), Ui.RADIUS_CHIP,
                Ui.blend(color, Ui.SURFACE, 0.55f), 1f));
        return t;
    }
}
