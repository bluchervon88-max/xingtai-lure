package com.xtlure.app.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.xtlure.app.util.ScreenFit;
import com.xtlure.app.util.Ui;

/**
 * 成就徽章面板。
 *
 * ★ 只统计**用户自己的行为**，不把 App 预置数据算成成果。
 *   上一版把内置的 73 个钓点当成"用户添加的钓点"，导致新装用户一进「我的」页
 *   就白拿三枚成就（勘探者 / 测绘员 / 走遍邢台），成就因此完全没有意义。
 *   现在所有计数都来自 Db 的 countUser* / count* 系列，预置钓点（seed_version>=1）
 *   一律不计入。
 *
 * ★ 版面：横向可滑动的奖章条。
 *   一屏放得下 3 枚半，提示用户"右边还有"；每枚都是完整奖章
 *   （环形进度弧 + 中心图标 + 名称 + 进度），比原来一行行文字厚实得多。
 *
 * 用"进度型"而不是"荣誉型"：每枚都显示离达成还差多少，
 * 它才是能驱动使用的提示，而不是没有意义的装饰。
 */
public final class BadgeWall {

    private BadgeWall() {
    }

    /** 用户的真实行为统计 */
    public static final class Stats {
        /** 用户自己添加的钓点数（不含预置） */
        public int userSpots;
        /** 用户自己添加且带坐标的钓点数 */
        public int userSpotsWithCoord;
        /** 用户去过的不同钓点数 */
        public int visitedSpots;
        /** 作钓记录数 */
        public int catches;
        /** 累计渔获尾数 */
        public int totalFish;
        /** 有渔获的记录数 */
        public int successfulCatches;
        /** 出现过的鱼种数 */
        public int distinctSpecies;
        /** 用过的拟饵数 */
        public int distinctLures;
        /** 出钓天数 */
        public int fishingDays;
    }

    /** 一枚徽章的定义 */
    private static final class Badge {
        final String icon, name, desc;
        final int current, target;

        Badge(String icon, String name, String desc, int current, int target) {
            this.icon = icon;
            this.name = name;
            this.desc = desc;
            this.current = current;
            this.target = target;
        }

        boolean done() {
            return current >= target;
        }

        float ratio() {
            return target <= 0 ? 1f : Math.min(1f, (float) current / target);
        }
    }

    /**
     * 构建奖章条。
     *
     * 六枚奖章刻意覆盖不同玩法：标点、精确坐标、出勤、渔获、鱼种。
     * 目标值都定得不高（1~30），让新用户很快能点亮第一枚 ——
     * 一开始全是灰的会让人以为这功能坏了。
     */
    public static View build(Context c, Stats st) {
        final ScreenFit fit = ScreenFit.get();

        LinearLayout box = Ui.column(c);

        final Badge[] badges = {
                new Badge("📌", "初次标点", "添加第 1 个钓点", st.userSpots, 1),
                new Badge("🥾", "勘探者", "自己添加 5 个钓点", st.userSpots, 5),
                new Badge("📍", "测绘员", "10 个自建钓点带坐标", st.userSpotsWithCoord, 10),
                new Badge("🎣", "开竿", "记录第 1 次作钓", st.catches, 1),
                new Badge("🗓", "出勤", "出钓 5 天", st.fishingDays, 5),
                new Badge("🐟", "开张", "累计 5 尾渔获", st.totalFish, 5),
                new Badge("🏆", "老手", "累计 30 尾渔获", st.totalFish, 30),
                new Badge("🎨", "拟饵库", "用过 8 种拟饵", st.distinctLures, 8),
                new Badge("🔬", "认鱼", "钓获 5 个鱼种", st.distinctSpecies, 5),
                new Badge("📚", "档案员", "30 次作钓记录", st.catches, 30),
        };

        int unlocked = 0;
        for (Badge b : badges) {
            if (b.done()) {
                unlocked++;
            }
        }

        // ---- 总览：已解锁数量 + 总进度条
        LinearLayout summaryRow = Ui.row(c);
        summaryRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView summary = Ui.text(c, unlocked + " / " + badges.length, 20,
                unlocked > 0 ? Ui.FAV : Ui.TEXT_DIM);
        summary.setTypeface(summary.getTypeface(), android.graphics.Typeface.BOLD);
        summaryRow.addView(summary);

        TextView summaryLabel = Ui.text(c, " 枚已解锁", 12, Ui.TEXT_DIM);
        LinearLayout.LayoutParams sllp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sllp.leftMargin = fit.dp(4);
        summaryLabel.setLayoutParams(sllp);
        summaryRow.addView(summaryLabel);

        summaryRow.addView(new View(c), new LinearLayout.LayoutParams(0, 0, 1f));

        // 提示下一枚离达成最近的奖章，给出明确的下一步
        Badge next = null;
        float bestRatio = -1f;
        for (Badge b : badges) {
            if (!b.done() && b.ratio() > bestRatio) {
                bestRatio = b.ratio();
                next = b;
            }
        }
        String tip = unlocked == 0
                ? "先记一个钓点试试"
                : next == null ? "全部达成 🎉"
                : "最快：「" + next.name + "」还差 " + (next.target - next.current);
        summaryRow.addView(Ui.text(c, tip, 11, Ui.TEXT_FAINT));
        box.addView(summaryRow);

        View total = new ProgressBar(c, (float) unlocked / badges.length, Ui.FAV);
        LinearLayout.LayoutParams tlp = Ui.lpMatch(fit.dp(6));
        tlp.topMargin = fit.dp(8);
        total.setLayoutParams(tlp);
        box.addView(total);

        // ---- 奖章条（横向可滑动）
        HorizontalScrollView scroll = new HorizontalScrollView(c);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipToPadding(false);

        LinearLayout row = Ui.row(c);
        row.setPadding(fit.dp(2), 0, fit.dp(8), 0);
        int badgeW = fit.flex(84f, 74f, 96f);
        for (Badge b : badges) {
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    badgeW, ViewGroup.LayoutParams.WRAP_CONTENT);
            blp.rightMargin = fit.dp(6);
            row.addView(badgeView(c, b), blp);
        }
        scroll.addView(row, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams slp = Ui.lpMatch(fit.flex(130f, 116f, 146f));
        slp.topMargin = fit.dp(16);
        scroll.setLayoutParams(slp);
        box.addView(scroll);

        // 滑动提示
        TextView swipe = Ui.text(c, "← 左右滑动看全部 " + badges.length + " 枚 →",
                10, Ui.TEXT_FAINT);
        swipe.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams swlp = Ui.lpMatch();
        swlp.topMargin = fit.dp(6);
        swipe.setLayoutParams(swlp);
        box.addView(swipe);

        return box;
    }

    /** 单枚奖章：圆环 + 图标 + 名称 + 进度 */
    private static View badgeView(Context c, final Badge b) {
        final ScreenFit fit = ScreenFit.get();
        final boolean done = b.done();

        LinearLayout box = Ui.column(c);
        box.setGravity(Gravity.CENTER_HORIZONTAL);

        int diameter = fit.flex(68f, 58f, 78f);
        BadgeRing ring = new BadgeRing(c, b.icon, b.ratio(), done);
        box.addView(ring, new LinearLayout.LayoutParams(diameter, diameter));

        TextView name = Ui.text(c, b.name, 11, done ? Ui.TEXT : Ui.TEXT_DIM);
        name.setGravity(Gravity.CENTER);
        name.setTypeface(name.getTypeface(), android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams nlp = Ui.lpMatch();
        nlp.topMargin = fit.dp(6);
        name.setLayoutParams(nlp);
        box.addView(name);

        TextView prog = Ui.text(c, done ? "已达成"
                        : Math.min(b.current, b.target) + "/" + b.target,
                10, done ? Ui.OK : Ui.TEXT_FAINT);
        prog.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams plp = Ui.lpMatch();
        plp.topMargin = fit.dp(2);
        prog.setLayoutParams(plp);
        box.addView(prog);

        box.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new android.app.AlertDialog.Builder(c,
                        android.R.style.Theme_Material_Dialog_Alert)
                        .setTitle(b.icon + " " + b.name)
                        .setMessage(b.desc + "\n\n当前进度：" + Math.min(b.current, b.target)
                                + " / " + b.target + (done ? "\n\n✅ 已达成" : ""))
                        .setPositiveButton("知道了", null)
                        .show();
            }
        });
        return box;
    }

    /** 奖章圆环：底环 + 进度弧 + 中心图标 + 达成时的金色外描边 */
    private static final class BadgeRing extends View {
        private final Paint ringBg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ringFg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint disc = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint gold = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final String icon;
        private final float ratio;
        private final boolean done;

        BadgeRing(Context c, String icon, float ratio, boolean done) {
            super(c);
            this.icon = icon;
            this.ratio = Math.max(0f, Math.min(1f, ratio));
            this.done = done;

            ringBg.setStyle(Paint.Style.STROKE);
            ringBg.setStrokeWidth(Ui.dp(c, 4));
            ringBg.setColor(Ui.SURFACE_3);

            ringFg.setStyle(Paint.Style.STROKE);
            ringFg.setStrokeWidth(Ui.dp(c, 4));
            ringFg.setStrokeCap(Paint.Cap.ROUND);
            ringFg.setColor(done ? Ui.OK : Ui.ACCENT);

            // 勋章底盘：达成时更亮，形成实体奖章的观感
            disc.setStyle(Paint.Style.FILL);
            disc.setColor(done ? Ui.blend(Ui.OK, Ui.SURFACE, 0.20f)
                    : Ui.blend(Ui.ACCENT, Ui.SURFACE, 0.07f));

            gold.setStyle(Paint.Style.STROKE);
            gold.setStrokeWidth(Ui.dp(c, 1.5f));
            gold.setColor(Ui.FAV);

            iconPaint.setTextAlign(Paint.Align.CENTER);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final float cx = getWidth() / 2f;
            final float cy = getHeight() / 2f;
            final float r = Math.min(getWidth(), getHeight()) / 2f - Ui.dp(getContext(), 5);
            if (r <= 2) {
                return;
            }

            // 底盘
            canvas.drawCircle(cx, cy, r - Ui.dp(getContext(), 2), disc);

            // 底环 + 进度弧（从 12 点方向顺时针）
            canvas.drawCircle(cx, cy, r, ringBg);
            RectF oval = new RectF(cx - r, cy - r, cx + r, cy + r);
            canvas.drawArc(oval, -90f, 360f * ratio, false, ringFg);

            // 图标：未达成按进度提升透明度，形成"待点亮"观感
            iconPaint.setTextSize(r * 0.92f);
            iconPaint.setAlpha(done ? 255 : (int) (100 + 155 * ratio));
            Paint.FontMetrics fm = iconPaint.getFontMetrics();
            canvas.drawText(icon, cx, cy - (fm.ascent + fm.descent) / 2f, iconPaint);

            if (done) {
                canvas.drawCircle(cx, cy, r + Ui.dp(getContext(), 3), gold);
            }
        }
    }

    /** 细进度条 */
    private static final class ProgressBar extends View {
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float ratio;

        ProgressBar(Context c, float ratio, int color) {
            super(c);
            this.ratio = Math.max(0f, Math.min(1f, ratio));
            bg.setColor(Ui.SURFACE_3);
            fg.setColor(color);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final float h = getHeight();
            final float r = h / 2f;
            canvas.drawRoundRect(new RectF(0, 0, getWidth(), h), r, r, bg);
            if (ratio > 0.001f) {
                canvas.drawRoundRect(new RectF(0, 0, Math.max(h, getWidth() * ratio), h),
                        r, r, fg);
            }
        }
    }
}
