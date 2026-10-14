package com.xtlure.app.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.DialogFragment;
import android.app.TimePickerDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;

import com.xtlure.app.data.Db;
import com.xtlure.app.model.CatchRecord;
import com.xtlure.app.model.FishingSpot;
import com.xtlure.app.util.CrashLog;
import com.xtlure.app.util.Prefs;
import com.xtlure.app.util.Ui;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * 记一次作钓 / 编辑记录。
 *
 * 排版：滚动表单，每行"标签 + 输入控件"，尽量让常见字段一次点选完成，
 * 真正做到"钓完鱼站岸边 30 秒记完"。
 */
public class CatchDialog extends DialogFragment {

    public static final String ARG_CATCH_ID = "catch_id";
    public static final String ARG_SPOT_ID = "spot_id";
    public static final String ARG_SPOT_NAME = "spot_name";

    private static final String[] FISH_PRESETS = {
            "翘嘴", "鲈鱼", "黑鱼", "鳜鱼", "马口", "白条",
            "鲶鱼", "鳡鱼", "狗鱼", "鲫鱼", "鲤鱼", "草鱼", "青鱼", "黄颡鱼"
    };
    private static final String[] LURE_PRESETS = {
            "亮片", "米诺", "VIB", "软虫", "雷蛙", "铅笔", "波爬",
            "德州钓组", "倒钓", "铅头钩", "复合亮片", "铁板"
    };
    private static final String[] METHOD_PRESETS = {
            "远投搜索", "贴边慢收", "跳底", "抽停", "雷强重障", "拖钓"
    };
    private static final String[] WEATHER_PRESETS = {
            "晴", "多云", "阴", "小雨", "中雨", "雨后", "大风", "雾"
    };
    private static final String[] WIND_PRESETS = {
            "无风", "微风", "东南风", "西北风", "西南风", "东北风", "大风"
    };
    private static final String[] WATER_PRESETS = {
            "水清", "微浑", "浑浊", "涨水", "落水", "静水", "走水"
    };

    /** 气压区间：路亚很吃气压，给几个典型区间让用户一键填 */
    private static final String[] PRESSURE_PRESETS = {
            "1020以上 高压(鱼口好)", "1010-1020 偏高", "1000-1010 正常",
            "990-1000 偏低(鱼口差)", "990以下 低压(基本不开口)"
    };
    private static final String[] AIRTEMP_PRESETS = {
            "0℃以下", "0-5℃", "5-10℃", "10-15℃", "15-20℃",
            "20-25℃", "25-30℃", "30℃以上"
    };
    private static final String[] WATERTEMP_PRESETS = {
            "5℃以下", "5-10℃", "10-15℃", "15-20℃", "20-25℃", "25℃以上"
    };

    private long catchId = -1;
    private long spotId = -1;
    private String spotName = "";
    private long time;
    private int rating;

    private EditText etTarget, etCaught, etCount, etSize, etLure, etMethod,
            etWeather, etWind, etWater, etNote, etPressure, etAirTemp, etWaterTemp;
    private TextView tvTime, tvSpot, tvRating;

    private Activity activity;

    // ------------------------------------------------------------ 入口

    /** 新建记录。spotId/spotName 传 null 表示不预选钓点 */
    public static void show(Activity a, Long spotId, String spotName) {
        CatchDialog d = new CatchDialog();
        Bundle b = new Bundle();
        b.putLong(ARG_CATCH_ID, -1);
        b.putLong(ARG_SPOT_ID, spotId == null ? -1 : spotId);
        b.putString(ARG_SPOT_NAME, spotName == null ? "" : spotName);
        d.setArguments(b);
        d.show(a.getFragmentManager(), "catch_dialog");
    }

    /** 编辑已有记录 */
    public static void showForEdit(Activity a, long catchId) {
        CatchDialog d = new CatchDialog();
        Bundle b = new Bundle();
        b.putLong(ARG_CATCH_ID, catchId);
        d.setArguments(b);
        d.show(a.getFragmentManager(), "catch_dialog_edit");
    }

    // ------------------------------------------------------------ 生命周期

    @Override
    public android.app.Dialog onCreateDialog(Bundle savedInstanceState) {
        activity = getActivity();
        Context ctx = activity;

        // ★ 诊断：真机上表单只显示出「标签」和「选择」按钮，中间本该有的
        //   输入框和取值控件在 uiautomator dump 里完全不存在（0 个 EditText、
        //   0 个带 hint 的节点），而同一个 App 主页的搜索框却能正常出现在 dump 里。
        //   这里给每行做一次记账，并在布局完成后输出真实的子控件尺寸，
        //   用实测数据判断是"没创建"还是"创建了但被量成 0 宽"。
        final java.util.List<String> diag = new ArrayList<>();
        CrashLog.step(ctx, "CatchDialog.onCreateDialog 开始");

        try {
            return buildDialog(ctx, savedInstanceState, diag);
        } catch (Throwable e) {
            CrashLog.record(ctx, "CatchDialog.onCreateDialog", e);
            diag.add("异常: " + e);
            // 兜底：给一个能看的最小对话框，避免直接崩
            AlertDialog.Builder fb = new AlertDialog.Builder(ctx,
                    android.R.style.Theme_Material_Dialog_Alert);
            fb.setTitle("记录表单构建失败");
            fb.setMessage(android.util.Log.getStackTraceString(e));
            fb.setPositiveButton("关闭", null);
            return fb.create();
        }
    }

    private android.app.Dialog buildDialog(final Context ctx, Bundle savedInstanceState,
                                           final java.util.List<String> diag) {

        Bundle args = getArguments();
        if (args != null) {
            catchId = args.getLong(ARG_CATCH_ID, -1);
            spotId = args.getLong(ARG_SPOT_ID, -1);
            spotName = args.getString(ARG_SPOT_NAME, "");
        }

        CatchRecord existing = null;
        if (catchId > 0) {
            existing = findCatch(catchId);
            if (existing != null) {
                spotId = existing.getSpotId();
                spotName = existing.getSpotName();
                time = existing.getTime();
                rating = existing.getRating();
            }
        }
        if (time == 0) {
            time = System.currentTimeMillis();
        }

        // ---------------- 表单主体
        LinearLayout form = Ui.column(ctx);
        int pad = Ui.dp(ctx, 18);
        form.setPadding(pad, pad, pad, pad);

        form.addView(sectionTitle("基本信息"));

        tvTime = addPickerRow(form, "时间", "");
        tvTime.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickDateTime();
            }
        });

        tvSpot = addPickerRow(form, "钓点", "");
        tvSpot.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickSpot();
            }
        });

        etTarget = addInput(form, "目标鱼", "想钓什么，例如 翘嘴", false);

        form.addView(sectionTitle("渔获"));

        LinearLayout fishRow = Ui.row(ctx);
        fishRow.setPadding(0, Ui.dp(ctx, 4), 0, Ui.dp(ctx, 4));
        TextView lf = Ui.text(ctx, "钓获鱼种", Ui.TEXT_SMALL, Ui.TEXT_FAINT);
        lf.setMinWidth(Ui.dp(ctx, 68));
        fishRow.addView(lf);
        etCaught = input("例如 翘嘴（没钓到就留空）", false);
        fishRow.addView(etCaught, Ui.weighted(1f));
        TextView pickFish = chipButton("选择");
        pickFish.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                multiPick("钓获鱼种", FISH_PRESETS, etCaught);
            }
        });
        fishRow.addView(pickFish);
        form.addView(fishRow);

        LinearLayout numRow = Ui.row(ctx);
        numRow.setPadding(0, Ui.dp(ctx, 4), 0, Ui.dp(ctx, 4));
        TextView ln = Ui.text(ctx, "尾数", Ui.TEXT_SMALL, Ui.TEXT_FAINT);
        ln.setMinWidth(Ui.dp(ctx, 68));
        numRow.addView(ln);
        etCount = input("0", true);
        numRow.addView(etCount, Ui.weighted(1f));
        etSize = input("体长/重量，例如 32cm", false);
        LinearLayout.LayoutParams sizeLp = Ui.weighted(1f);
        sizeLp.leftMargin = Ui.dp(ctx, 6);
        etSize.setLayoutParams(sizeLp);
        numRow.addView(etSize);
        form.addView(numRow);

        form.addView(sectionTitle("用饵与钓法"));

        LinearLayout lureRow = Ui.row(ctx);
        lureRow.setPadding(0, Ui.dp(ctx, 4), 0, Ui.dp(ctx, 4));
        TextView ll = Ui.text(ctx, "用饵", Ui.TEXT_SMALL, Ui.TEXT_FAINT);
        ll.setMinWidth(Ui.dp(ctx, 68));
        lureRow.addView(ll);
        etLure = input("例如 亮片 7g", false);
        lureRow.addView(etLure, Ui.weighted(1f));
        TextView pickLure = chipButton("选择");
        pickLure.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                singlePick("常用拟饵", LURE_PRESETS, etLure, true);
            }
        });
        lureRow.addView(pickLure);
        form.addView(lureRow);

        LinearLayout methodRow = Ui.row(ctx);
        methodRow.setPadding(0, Ui.dp(ctx, 4), 0, Ui.dp(ctx, 4));
        TextView lm = Ui.text(ctx, "钓法", Ui.TEXT_SMALL, Ui.TEXT_FAINT);
        lm.setMinWidth(Ui.dp(ctx, 68));
        methodRow.addView(lm);
        etMethod = input("例如 远投搜索", false);
        methodRow.addView(etMethod, Ui.weighted(1f));
        TextView pickMethod = chipButton("选择");
        pickMethod.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                singlePick("钓法", METHOD_PRESETS, etMethod, true);
            }
        });
        methodRow.addView(pickMethod);
        form.addView(methodRow);

        form.addView(sectionTitle("环境"));

        etWeather = addInputWithPick(form, "天气", "例如 晴 / 雨后", WEATHER_PRESETS);
        etPressure = addInputWithPick(form, "气压", "例如 1013 hPa（路亚关键项）",
                PRESSURE_PRESETS);
        etWind = addInputWithPick(form, "风向风力", "例如 东南风 2 级", WIND_PRESETS);
        etAirTemp = addInputWithPick(form, "气温", "例如 18℃", AIRTEMP_PRESETS);
        etWaterTemp = addInputWithPick(form, "水温", "例如 15℃", WATERTEMP_PRESETS);
        etWater = addInputWithPick(form, "水情", "例如 微浑 / 涨水", WATER_PRESETS);
        etNote = addInput(form, "备注", "标点、咬口时间、遇到的鱼情…", false);
        etNote.setMinLines(2);

        form.addView(sectionTitle("这次体验"));

        LinearLayout rateRow = Ui.row(ctx);
        rateRow.setPadding(0, Ui.dp(ctx, 6), 0, Ui.dp(ctx, 6));
        TextView lr = Ui.text(ctx, "评分", Ui.TEXT_SMALL, Ui.TEXT_FAINT);
        lr.setMinWidth(Ui.dp(ctx, 68));
        rateRow.addView(lr);
        tvRating = Ui.text(ctx, "", 17, Ui.FAV);
        // 触摸高度 44dp：原先整行只有 21dp，点星很难点中
        tvRating.setGravity(Gravity.CENTER_VERTICAL);
        tvRating.setLayoutParams(new LinearLayout.LayoutParams(
                0, Ui.dp(ctx, 44), 1f));
        rateRow.addView(tvRating);
        tvRating.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                rating = (rating >= 5) ? 0 : rating + 1;
                renderRating();
            }
        });
        form.addView(rateRow);

        // 填充已有数据
        if (existing != null) {
            etTarget.setText(existing.getTargetFish());
            etCaught.setText(existing.getCaughtFish());
            etCount.setText(existing.getCount() > 0 ? String.valueOf(existing.getCount()) : "");
            etSize.setText(existing.getSizeNote());
            etLure.setText(existing.getLureUsed());
            etMethod.setText(existing.getMethod());
            etWeather.setText(existing.getWeather());
            etWind.setText(existing.getWind());
            etWater.setText(existing.getWaterNote());
            etPressure.setText(existing.getPressure());
            etAirTemp.setText(existing.getAirTemp());
            etWaterTemp.setText(existing.getWaterTemp());
            etNote.setText(existing.getNote());
        }

        renderTime();
        renderSpot();
        renderRating();

        ScrollView scroll = new ScrollView(ctx);
        scroll.addView(form);

        AlertDialog.Builder b = new AlertDialog.Builder(ctx,
                android.R.style.Theme_Material_Dialog_Alert);
        b.setTitle(catchId > 0 ? "编辑作钓记录" : "记一次作钓");
        b.setView(scroll);
        b.setPositiveButton(catchId > 0 ? "保存" : "记录", null);
        b.setNegativeButton("取消", null);
        if (catchId > 0) {
            b.setNeutralButton("删除", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    Db.get(activity).deleteCatch(catchId);
                    Toast.makeText(activity, "已删除", Toast.LENGTH_SHORT).show();
                }
            });
        }

        final AlertDialog dialog = b.create();
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface d) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                        .setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                if (save()) {
                                    dialog.dismiss();
                                }
                            }
                        });

                // ★ 布局完成后输出每行的真实结构。
                //   核心要回答的问题：输入框到底有没有被子控件撑开、宽度量成了多少。
                //   用 post 保证测量已完成，拿到的是最终值而不是 0。
                form.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            StringBuilder sb = new StringBuilder();
                            sb.append("CatchDialog 布局实测: 表单直接子控件 ")
                              .append(form.getChildCount()).append(" 个\n");
                            for (int i = 0; i < form.getChildCount(); i++) {
                                View child = form.getChildAt(i);
                                sb.append("  [").append(i).append("] ")
                                  .append(child.getClass().getSimpleName())
                                  .append(" ").append(child.getWidth())
                                  .append("x").append(child.getHeight())
                                  .append(" vis=").append(child.getVisibility());
                                if (child instanceof android.view.ViewGroup) {
                                    android.view.ViewGroup g = (android.view.ViewGroup) child;
                                    sb.append(" 子=").append(g.getChildCount());
                                    for (int j = 0; j < g.getChildCount(); j++) {
                                        View gc = g.getChildAt(j);
                                        sb.append("\n      - ")
                                          .append(gc.getClass().getSimpleName())
                                          .append(" ").append(gc.getWidth())
                                          .append("x").append(gc.getHeight())
                                          .append(" vis=").append(gc.getVisibility());
                                        if (gc instanceof android.widget.TextView) {
                                            CharSequence t =
                                                    ((android.widget.TextView) gc).getText();
                                            sb.append(" text='")
                                              .append(t == null ? "" : t.toString())
                                              .append("'");
                                        }
                                        android.view.ViewGroup.LayoutParams lp =
                                                gc.getLayoutParams();
                                        if (lp instanceof LinearLayout.LayoutParams) {
                                            LinearLayout.LayoutParams llp =
                                                    (LinearLayout.LayoutParams) lp;
                                            sb.append(" w=").append(llp.width)
                                              .append(" weight=").append(llp.weight);
                                        }
                                    }
                                }
                                sb.append("\n");
                            }
                            for (String s : diag) {
                                sb.append("  diag: ").append(s).append("\n");
                            }
                            CrashLog.appendDiag(ctx, sb.toString());
                        } catch (Throwable e) {
                            CrashLog.record(ctx, "CatchDialog.post 诊断", e);
                        }
                    }
                });
            }
        });

        Window w = dialog.getWindow();
        if (w != null) {
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        return dialog;
    }

    @Override
    public void onStart() {
        super.onStart();
        android.app.Dialog d = getDialog();
        if (d != null && d.getWindow() != null) {
            d.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    // ------------------------------------------------------------ 数据

    private CatchRecord findCatch(long id) {
        return Db.get(activity).getCatch(id);
    }

    private boolean save() {
        String caught = etCaught.getText().toString().trim();
        String countStr = etCount.getText().toString().trim();
        int count = 0;
        if (!countStr.isEmpty()) {
            try {
                count = Integer.parseInt(countStr);
            } catch (NumberFormatException e) {
                Toast.makeText(activity, "尾数请填数字", Toast.LENGTH_SHORT).show();
                return false;
            }
        }

        CatchRecord r;
        boolean isNew = catchId <= 0;
        if (isNew) {
            r = new CatchRecord();
            r.setCreatedAt(System.currentTimeMillis());
        } else {
            r = findCatch(catchId);
            if (r == null) {
                return false;
            }
        }

        r.setSpotId(spotId);
        r.setSpotName(spotName);
        r.setTime(time);
        r.setTargetFish(etTarget.getText().toString().trim());
        r.setCaughtFish(caught);
        r.setCount(count);
        r.setSizeNote(etSize.getText().toString().trim());
        r.setLureUsed(etLure.getText().toString().trim());
        r.setMethod(etMethod.getText().toString().trim());
        r.setWeather(etWeather.getText().toString().trim());
        r.setWind(etWind.getText().toString().trim());
        r.setWaterNote(etWater.getText().toString().trim());
        r.setPressure(etPressure.getText().toString().trim());
        r.setAirTemp(etAirTemp.getText().toString().trim());
        r.setWaterTemp(etWaterTemp.getText().toString().trim());
        r.setNote(etNote.getText().toString().trim());
        r.setRating(rating);

        // 带上当前定位，方便以后回看当时站在哪个标点
        double lat = Prefs.getDouble(activity, Prefs.K_LAST_LAT, Double.NaN);
        double lon = Prefs.getDouble(activity, Prefs.K_LAST_LON, Double.NaN);
        if (!Double.isNaN(lat) && !Double.isNaN(lon)) {
            r.setLat(lat);
            r.setLon(lon);
            r.setHasCoord(true);
        }

        if (isNew) {
            Db.get(activity).insertCatch(r);
        } else {
            Db.get(activity).updateCatch(r);
        }
        Toast.makeText(activity,
                isNew ? (count > 0 ? "已记录 " + count + " 尾 👍" : "已记录本次作钓")
                        : "已保存",
                Toast.LENGTH_SHORT).show();
        return true;
    }

    // ------------------------------------------------------------ 子选择器

    private void pickDateTime() {
        final Calendar c = Calendar.getInstance();
        c.setTimeInMillis(time);
        new DatePickerDialog(activity, new DatePickerDialog.OnDateSetListener() {
            @Override
            public void onDateSet(DatePicker view, int year, int month, int day) {
                c.set(Calendar.YEAR, year);
                c.set(Calendar.MONTH, month);
                c.set(Calendar.DAY_OF_MONTH, day);
                new TimePickerDialog(activity, new TimePickerDialog.OnTimeSetListener() {
                    @Override
                    public void onTimeSet(TimePicker view, int hour, int minute) {
                        c.set(Calendar.HOUR_OF_DAY, hour);
                        c.set(Calendar.MINUTE, minute);
                        time = c.getTimeInMillis();
                        renderTime();
                    }
                }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true).show();
            }
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void pickSpot() {
        final List<FishingSpot> spots = Db.get(activity).listSpots();
        final List<String> names = new ArrayList<>();
        names.add("（不关联钓点）");
        for (FishingSpot s : spots) {
            names.add(s.getName() + (s.getDistrict().isEmpty() ? "" : " · " + s.getDistrict()));
        }
        new AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("选择钓点")
                .setItems(names.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            spotId = -1;
                            spotName = "";
                        } else {
                            FishingSpot s = spots.get(which - 1);
                            spotId = s.getId();
                            spotName = s.getName();
                        }
                        renderSpot();
                    }
                })
                .show();
    }

    private void multiPick(String title, final String[] presets, final EditText target) {
        final List<String> selected = new ArrayList<>();
        String cur = target.getText().toString();
        for (String p : presets) {
            if (cur.contains(p)) {
                selected.add(p);
            }
        }
        final boolean[] checked = new boolean[presets.length];
        for (int i = 0; i < presets.length; i++) {
            checked[i] = selected.contains(presets[i]);
        }
        new AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(title)
                .setMultiChoiceItems(presets, checked,
                        new DialogInterface.OnMultiChoiceClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which, boolean isChecked) {
                                checked[which] = isChecked;
                            }
                        })
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < presets.length; i++) {
                            if (checked[i]) {
                                if (sb.length() > 0) {
                                    sb.append("、");
                                }
                                sb.append(presets[i]);
                            }
                        }
                        target.setText(sb.toString());
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void singlePick(String title, final String[] presets, final EditText target,
                            final boolean append) {
        new AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(title)
                .setItems(presets, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (append && target.getText().length() > 0) {
                            target.setText(target.getText().toString() + " " + presets[which]);
                        } else {
                            target.setText(presets[which]);
                        }
                    }
                })
                .show();
    }

    // ------------------------------------------------------------ 渲染

    private void renderTime() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA);
        tvTime.setText(f.format(new java.util.Date(time)));
        tvTime.setTextColor(Ui.TEXT);
    }

    private void renderSpot() {
        tvSpot.setText(spotName == null || spotName.isEmpty() ? "点这里选择" : spotName);
        tvSpot.setTextColor(spotName == null || spotName.isEmpty() ? Ui.TEXT_FAINT : Ui.TEXT);
    }

    private void renderRating() {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 5; i++) {
            sb.append(i <= rating ? "★" : "☆");
        }
        tvRating.setText(sb.toString() + (rating == 0 ? "   点一下评分" : ""));
        if (rating == 0) {
            tvRating.setTextColor(Ui.TEXT_FAINT);
        } else {
            tvRating.setTextColor(Ui.FAV);
        }
    }

    // ------------------------------------------------------------ 小控件

    private TextView sectionTitle(String s) {
        TextView t = Ui.text(activity, s, 12, Ui.ACCENT);
        t.setPadding(0, Ui.dp(activity, 14), 0, Ui.dp(activity, 2));
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        return t;
    }

    private TextView addPickerRow(LinearLayout parent, String label, String value) {
        LinearLayout r = Ui.row(activity);
        r.setPadding(0, Ui.dp(activity, 4), 0, Ui.dp(activity, 4));

        TextView l = Ui.text(activity, label, Ui.TEXT_SMALL, Ui.TEXT_FAINT);
        l.setMinWidth(Ui.dp(activity, 68));
        r.addView(l);

        TextView v = Ui.text(activity, value, 14, Ui.TEXT);
        v.setBackground(Ui.round(Ui.SURFACE_2, 8, Ui.BORDER, 1f));
        v.setPadding(Ui.dp(activity, 10), Ui.dp(activity, 7),
                Ui.dp(activity, 10), Ui.dp(activity, 7));
        r.addView(v, Ui.weighted(1f));

        parent.addView(r);
        return v;
    }

    private EditText addInput(LinearLayout parent, String label, String hint, boolean number) {
        LinearLayout r = Ui.row(activity);
        r.setPadding(0, Ui.dp(activity, 4), 0, Ui.dp(activity, 4));

        TextView l = Ui.text(activity, label, Ui.TEXT_SMALL, Ui.TEXT_FAINT);
        l.setMinWidth(Ui.dp(activity, 68));
        r.addView(l);

        EditText e = input(hint, number);
        r.addView(e, Ui.weighted(1f));

        parent.addView(r);
        return e;
    }

    private EditText addInputWithPick(LinearLayout parent, String label, String hint,
                                      final String[] presets) {
        LinearLayout r = Ui.row(activity);
        r.setPadding(0, Ui.dp(activity, 4), 0, Ui.dp(activity, 4));

        TextView l = Ui.text(activity, label, Ui.TEXT_SMALL, Ui.TEXT_FAINT);
        l.setMinWidth(Ui.dp(activity, 68));
        r.addView(l);

        final EditText e = input(hint, false);
        r.addView(e, Ui.weighted(1f));

        TextView pick = chipButton("选择");
        pick.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                singlePick("选择", presets, e, false);
            }
        });
        r.addView(pick);

        parent.addView(r);
        return e;
    }

    private EditText input(String hint, boolean number) {
        EditText e = new EditText(activity);
        e.setHint(hint);
        e.setHintTextColor(Ui.TEXT_FAINT);
        e.setTextColor(Ui.TEXT);
        e.setTextSize(14);
        e.setBackground(Ui.round(Ui.SURFACE_2, 8, Ui.BORDER, 1f));
        e.setPadding(Ui.dp(activity, 10), Ui.dp(activity, 7),
                Ui.dp(activity, 10), Ui.dp(activity, 7));
        if (number) {
            e.setInputType(InputType.TYPE_CLASS_NUMBER);
        }
        return e;
    }

    /**
     * 表单里的「选择」小按钮。
     *
     * 高度固定 44dp：之前是 WRAP_CONTENT + 上下各 8dp 内边距，实测只有 30dp，
     * 在手机上很难点准 —— 这是"误触频繁"的一部分来源。
     */
    private TextView chipButton(String s) {
        TextView t = Ui.text(activity, s, 12, Ui.ACCENT);
        t.setGravity(Gravity.CENTER);
        t.setPadding(Ui.dp(activity, 12), 0, Ui.dp(activity, 12), 0);
        t.setBackground(Ui.ripple(Ui.SURFACE_3, 8, Ui.RIPPLE));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                Ui.dp(activity, 64), Ui.dp(activity, 44));
        lp.leftMargin = Ui.dp(activity, 6);
        t.setLayoutParams(lp);
        return t;
    }
}
