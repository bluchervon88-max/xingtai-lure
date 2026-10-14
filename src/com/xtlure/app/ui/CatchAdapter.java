package com.xtlure.app.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.xtlure.app.model.CatchRecord;
import com.xtlure.app.util.Ui;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 作钓记录列表。每张卡片：
 *   第一行  鱼种 + 尾数（空军单独用灰色显示） / 日期时间
 *   第二行  钓点名
 *   第三行  用饵 · 钓法
 *   第四行  天气水情备注
 */
public class CatchAdapter extends BaseAdapter {

    private final Context ctx;
    private final List<CatchRecord> data = new ArrayList<>();

    private final SimpleDateFormat dateFmt = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA);
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", Locale.CHINA);

    public CatchAdapter(Context ctx) {
        this.ctx = ctx;
    }

    public void setData(List<CatchRecord> list) {
        data.clear();
        if (list != null) {
            data.addAll(list);
        }
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return data.size();
    }

    @Override
    public CatchRecord getItem(int position) {
        return data.get(position);
    }

    @Override
    public long getItemId(int position) {
        return data.get(position).getId();
    }

    private TextView tvTitle, tvTime, tvSpot, tvLure, tvMeta;

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        LinearLayout card;
        if (convertView instanceof LinearLayout) {
            card = (LinearLayout) convertView;
        } else {
            card = build();
        }
        bind(getItem(position));
        return card;
    }

    private LinearLayout build() {
        LinearLayout card = Ui.column(ctx);
        card.setPadding(Ui.dp(ctx, 14), Ui.dp(ctx, 12), Ui.dp(ctx, 14), Ui.dp(ctx, 12));
        card.setBackground(Ui.cardRipple(ctx));

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        int m = Ui.dp(ctx, 7);
        cardLp.setMargins(m, m, m, m);
        card.setLayoutParams(cardLp);

        LinearLayout r1 = Ui.row(ctx);
        tvTitle = Ui.text(ctx, "", 16, Ui.TEXT);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tvTitle.setMaxLines(1);
        tvTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        r1.addView(tvTitle, Ui.weighted(1f));

        tvTime = Ui.text(ctx, "", Ui.TEXT_TINY, Ui.TEXT_FAINT);
        r1.addView(tvTime);
        card.addView(r1);

        tvSpot = Ui.text(ctx, "", Ui.TEXT_SMALL, Ui.ACCENT);
        LinearLayout.LayoutParams slp = Ui.lpMatch();
        slp.topMargin = Ui.dp(ctx, 5);
        tvSpot.setLayoutParams(slp);
        tvSpot.setMaxLines(1);
        tvSpot.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(tvSpot);

        tvLure = Ui.text(ctx, "", Ui.TEXT_SMALL, Ui.TEXT_DIM);
        LinearLayout.LayoutParams llp = Ui.lpMatch();
        llp.topMargin = Ui.dp(ctx, 5);
        tvLure.setLayoutParams(llp);
        tvLure.setMaxLines(1);
        tvLure.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(tvLure);

        tvMeta = Ui.text(ctx, "", Ui.TEXT_TINY, Ui.TEXT_FAINT);
        LinearLayout.LayoutParams mlp = Ui.lpMatch();
        mlp.topMargin = Ui.dp(ctx, 5);
        tvMeta.setLayoutParams(mlp);
        tvMeta.setMaxLines(2);
        tvMeta.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(tvMeta);

        return card;
    }

    private void bind(CatchRecord r) {
        boolean blank = r.isBlank();

        if (blank) {
            tvTitle.setText("空军");
            tvTitle.setTextColor(Ui.TEXT_FAINT);
        } else {
            String fish = r.getCaughtFish().isEmpty() ? "渔获" : r.getCaughtFish();
            String count = r.getCount() > 0 ? " × " + r.getCount() : "";
            String size = r.getSizeNote().isEmpty() ? "" : "  " + r.getSizeNote();
            tvTitle.setText(fish + count + size);
            tvTitle.setTextColor(Ui.ACCENT);
        }

        Date d = new Date(r.getTime());
        tvTime.setText(dateFmt.format(d) + " " + timeFmt.format(d));

        tvSpot.setText(r.getSpotName().isEmpty() ? "（未记录钓点）" : "📍 " + r.getSpotName());

        StringBuilder lure = new StringBuilder();
        if (!r.getLureUsed().isEmpty()) {
            lure.append("🎣 ").append(r.getLureUsed());
        }
        if (!r.getMethod().isEmpty()) {
            if (lure.length() > 0) {
                lure.append("   ");
            }
            lure.append("⚙ ").append(r.getMethod());
        }
        if (r.getTargetFish().length() > 0 && !blank) {
            if (lure.length() > 0) {
                lure.append("   ");
            }
            lure.append("目标 ").append(r.getTargetFish());
        }
        tvLure.setText(lure.length() == 0 ? "未记录用饵" : lure.toString());
        tvLure.setTextColor(lure.length() == 0 ? Ui.TEXT_FAINT : Ui.TEXT_DIM);

        StringBuilder meta = new StringBuilder();
        String env = r.environmentLine();
        if (!env.isEmpty()) {
            meta.append(env);
        }
        if (!r.getNote().isEmpty()) {
            if (meta.length() > 0) {
                meta.append(" · ");
            }
            meta.append(r.getNote());
        }
        tvMeta.setText(meta.toString());
        tvMeta.setVisibility(meta.length() == 0 ? View.GONE : View.VISIBLE);
    }
}
