package com.xtlure.app.ui;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.xtlure.app.data.Db;
import com.xtlure.app.model.FishingSpot;
import com.xtlure.app.util.GpsTracker;
import com.xtlure.app.util.MapLauncher;
import com.xtlure.app.util.Prefs;
import com.xtlure.app.util.ThemeHelper;
import com.xtlure.app.util.Ui;

import java.util.Locale;

/**
 * 编辑/新建钓点。
 *
 * 关键交互是顶部那颗「用 GPS 取当前坐标」大按钮：
 * 站在钓点上点一下，坐标自动填好，其余字段能省就省。
 */
public class EditSpotActivity extends Activity {

    public static final String EXTRA_SPOT_ID = "spot_id";

    /** 邢台市行政区划 */
    private static final String[] DISTRICTS = {
            "襄都区", "信都区", "任泽区", "南和区", "沙河市", "南宫市",
            "临城县", "内丘县", "柏乡县", "隆尧县", "宁晋县", "巨鹿县",
            "平乡县", "新河县", "广宗县", "威县", "清河县", "临西县",
            "跨区/未定"
    };

    private static final String[] WATER_TYPES = {
            "水库", "河流", "湖泊", "池塘", "黑坑", "渠道", "沙坑", "未分类"
    };
    private static final int[] WATER_TYPE_VALUES = {
            FishingSpot.TYPE_RESERVOIR, FishingSpot.TYPE_RIVER, FishingSpot.TYPE_LAKE,
            FishingSpot.TYPE_POND, FishingSpot.TYPE_PAYPOND, FishingSpot.TYPE_CANAL,
            FishingSpot.TYPE_PIT, FishingSpot.TYPE_UNKNOWN
    };

    private static final String[] STATUSES = {"可钓", "禁钓", "收费", "限钓", "待确认"};
    private static final int[] STATUS_VALUES = {
            FishingSpot.STATUS_OPEN, FishingSpot.STATUS_BANNED, FishingSpot.STATUS_FEE,
            FishingSpot.STATUS_RESTRICTED, FishingSpot.STATUS_UNKNOWN
    };

    private static final String[] FISH_PRESETS = {
            "翘嘴", "鲈鱼", "黑鱼", "鳜鱼", "马口", "白条", "鲶鱼",
            "鳡鱼", "狗鱼", "鲫鱼", "鲤鱼", "草鱼", "青鱼", "黄颡鱼"
    };
    private static final String[] LURE_PRESETS = {
            "亮片", "米诺", "VIB", "软虫", "雷蛙", "铅笔", "波爬",
            "德州钓组", "倒钓", "铅头钩", "复合亮片", "铁板"
    };

    private long spotId = -1;
    private FishingSpot spot;
    private boolean isNew;

    private EditText etName, etAddress, etFish, etLure, etSeason, etAccess, etNote, etRisk, etSource;
    private TextView tvCoord, tvDistrict, tvWaterType, tvStatus, tvConfidence;
    private TextView btnGps;
    private GpsTracker gps;

    private double lat, lon;
    private boolean hasCoord;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeHelper.prepare(this);
        spotId = getIntent().getLongExtra(EXTRA_SPOT_ID, -1);
        isNew = spotId <= 0;

        if (isNew) {
            spot = new FishingSpot();
        } else {
            spot = Db.get(this).getSpot(spotId);
            if (spot == null) {
                Toast.makeText(this, "钓点不存在", Toast.LENGTH_SHORT).show();
                finish();
                return;
            }
        }

        lat = spot.getLat();
        lon = spot.getLon();
        hasCoord = spot.isHasCoord();

        gps = new GpsTracker(this);

        LinearLayout col = Ui.column(this);
        col.setBackgroundColor(Ui.BG);

        col.addView(buildTopBar(), Ui.lpMatch());

        ScrollView scroll = new ScrollView(this);
        LinearLayout form = Ui.column(this);
        form.setPadding(Ui.dp(this, 16), Ui.dp(this, 6), Ui.dp(this, 16), Ui.dp(this, 40));
        scroll.addView(form);
        col.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(col);

        // ---------------- 坐标区（最重要，放最上面）
        btnGps = Ui.text(this, "📍  用 GPS 取当前坐标", 15, Ui.ON_ACCENT);
        btnGps.setGravity(Gravity.CENTER);
        btnGps.setPadding(0, Ui.dp(this, 15), 0, Ui.dp(this, 15));
        btnGps.setBackground(Ui.ripple(Ui.ACCENT, Ui.RADIUS_BTN, Ui.RIPPLE));
        btnGps.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                grabGps();
            }
        });
        form.addView(btnGps);

        tvCoord = Ui.text(this, "", 13, Ui.TEXT);
        tvCoord.setBackground(Ui.round(Ui.SURFACE_2, 8, Ui.BORDER, 1f));
        tvCoord.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        LinearLayout.LayoutParams clp = Ui.lpMatch();
        clp.topMargin = Ui.dp(this, 8);
        tvCoord.setLayoutParams(clp);
        tvCoord.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (hasCoord) {
                    MapLauncher.copyCoord(EditSpotActivity.this,
                            etName.getText().toString(), lat, lon);
                } else {
                    manualCoord();
                }
            }
        });
        tvCoord.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                manualCoord();
                return true;
            }
        });
        form.addView(tvCoord);

        TextView coordHint = Ui.text(this,
                "没有 GPS 信号也可以长按上面卡片手动输入坐标　·　点一下可复制三种坐标系",
                11, Ui.TEXT_FAINT);
        coordHint.setPadding(0, Ui.dp(this, 5), 0, 0);
        form.addView(coordHint);
        renderCoord();

        // ---------------- 基本信息
        form.addView(sectionTitle("基本信息"));
        etName = addInput(form, "钓点名称", "例如 朱庄水库·大坝东侧", false);
        tvDistrict = addPickRow(form, "所在区县", new Runnable() {
            @Override
            public void run() {
                pickSingle("所在区县", DISTRICTS, new OnPick() {
                    @Override
                    public void onPick(int index) {
                        spot.setDistrict(DISTRICTS[index]);
                        renderDistrict();
                    }
                });
            }
        });
        tvWaterType = addPickRow(form, "水域类型", new Runnable() {
            @Override
            public void run() {
                pickSingle("水域类型", WATER_TYPES, new OnPick() {
                    @Override
                    public void onPick(int index) {
                        spot.setWaterType(WATER_TYPE_VALUES[index]);
                        renderWaterType();
                    }
                });
            }
        });
        tvStatus = addPickRow(form, "作钓状态", new Runnable() {
            @Override
            public void run() {
                pickSingle("作钓状态", STATUSES, new OnPick() {
                    @Override
                    public void onPick(int index) {
                        spot.setStatus(STATUS_VALUES[index]);
                        renderStatus();
                    }
                });
            }
        });
        etAddress = addInput(form, "位置描述", "例如 邢台市信都区，水库东北角入水口", false);

        // ---------------- 作钓信息
        form.addView(sectionTitle("作钓信息"));
        etFish = addInputWithPick(form, "对象鱼", "顿号分隔，例如 翘嘴、鲈鱼", FISH_PRESETS, true);
        etLure = addInputWithPick(form, "推荐拟饵", "例如 亮片、VIB", LURE_PRESETS, true);
        etSeason = addInput(form, "最佳季节", "例如 4-6月、9-11月", false);
        etAccess = addInput(form, "到达方式", "停车、步行距离、是否好走", false);

        // ---------------- 备注
        form.addView(sectionTitle("备注与风险"));
        etNote = addInput(form, "备注", "标点特征、鱼情、当地规矩…", false);
        etNote.setMinLines(3);
        etRisk = addInput(form, "风险提示", "禁渔期、饮用水源保护、涉水危险等", false);
        etRisk.setMinLines(2);

        tvConfidence = addPickRow(form, "资料可信度", new Runnable() {
            @Override
            public void run() {
                final String[] opts = {"未核实", "网络资料", "本人实地确认"};
                pickSingle("资料可信度", opts, new OnPick() {
                    @Override
                    public void onPick(int index) {
                        spot.setConfidence(index);
                        renderConfidence();
                    }
                });
            }
        });
        etSource = addInput(form, "资料来源", "网址或出处，便于以后回查", false);

        // ---------------- 填充
        etName.setText(spot.getName());
        etAddress.setText(spot.getAddress());
        etFish.setText(spot.getFishSpecies());
        etLure.setText(spot.getLureTypes());
        etSeason.setText(spot.getBestSeason());
        etAccess.setText(spot.getAccessNote());
        etNote.setText(spot.getNote());
        etRisk.setText(spot.getRiskNote());
        etSource.setText(spot.getSourceUrl());

        renderDistrict();
        renderWaterType();
        renderStatus();
        renderConfidence();

        // ---------------- 底部按钮
        LinearLayout btnRow = Ui.row(this);
        LinearLayout.LayoutParams brLp = Ui.lpMatch();
        brLp.topMargin = Ui.dp(this, 22);
        btnRow.setLayoutParams(brLp);

        TextView save = Ui.text(this, isNew ? "保存钓点" : "保存修改", 15, Ui.ON_ACCENT);
        save.setGravity(Gravity.CENTER);
        save.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 14));
        save.setBackground(Ui.ripple(Ui.ACCENT, Ui.RADIUS_BTN, Ui.RIPPLE));
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
            }
        });
        btnRow.addView(save, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 2f));

        if (!isNew) {
            TextView del = Ui.text(this, "删除", 15, Ui.DANGER);
            del.setGravity(Gravity.CENTER);
            del.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 14));
            del.setBackground(Ui.ripple(Ui.blend(Ui.DANGER, Ui.BG, 0.22f),
                    Ui.RADIUS_BTN, Ui.RIPPLE));
            LinearLayout.LayoutParams dlp = Ui.weighted(1f);
            dlp.leftMargin = Ui.dp(this, 8);
            del.setLayoutParams(dlp);
            del.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    confirmDelete();
                }
            });
            btnRow.addView(del);
        }
        form.addView(btnRow);
    }

    @Override
    protected void onDestroy() {
        if (gps != null) {
            gps.cancel();
        }
        super.onDestroy();
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

        TextView t = Ui.text(this, isNew ? "新建钓点" : "编辑钓点", 17, Ui.TEXT);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        bar.addView(t, Ui.weighted(1f));
        return bar;
    }

    // ================================================================ GPS

    private void grabGps() {
        if (!GpsTracker.hasPermission(this)) {
            GpsTracker.requestPermission(this);
            return;
        }
        btnGps.setText("⏳  正在定位，请稍候…");
        btnGps.setBackground(Ui.round(Ui.SURFACE_3, Ui.RADIUS_BTN));

        gps.requestOnce(new GpsTracker.Callback() {
            @Override
            public void onLocation(double la, double lo, float acc, String provider) {
                lat = la;
                lon = lo;
                hasCoord = true;
                btnGps.setText("📍  重新取坐标");
                btnGps.setBackground(Ui.ripple(Ui.ACCENT, Ui.RADIUS_BTN, Ui.RIPPLE));
                renderCoord();
                Prefs.setDouble(EditSpotActivity.this, Prefs.K_LAST_LAT, la);
                Prefs.setDouble(EditSpotActivity.this, Prefs.K_LAST_LON, lo);
                Toast.makeText(EditSpotActivity.this,
                        "已取到坐标，精度约 " + (int) acc + " 米", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onFail(String reason) {
                btnGps.setText("📍  用 GPS 取当前坐标");
                btnGps.setBackground(Ui.ripple(Ui.ACCENT, Ui.RADIUS_BTN, Ui.RIPPLE));
                Toast.makeText(EditSpotActivity.this,
                        "定位失败：" + reason + "\n可以长按坐标卡片手动输入",
                        Toast.LENGTH_LONG).show();
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
                grabGps();
            } else {
                Toast.makeText(this, "没有定位权限，可长按坐标卡片手动输入", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void manualCoord() {
        LinearLayout box = Ui.column(this);
        box.setPadding(Ui.dp(this, 18), Ui.dp(this, 10), Ui.dp(this, 18), 0);

        final EditText eLat = new EditText(this);
        eLat.setHint("纬度，例如 37.0750");
        eLat.setHintTextColor(Ui.TEXT_FAINT);
        eLat.setTextColor(Ui.TEXT);
        eLat.setInputType(InputType.TYPE_CLASS_NUMBER
                | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        eLat.setBackground(Ui.round(Ui.SURFACE_2, 8, Ui.BORDER, 1f));
        eLat.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8));
        if (hasCoord) {
            eLat.setText(String.format(Locale.CHINA, "%.6f", lat));
        }
        box.addView(eLat);

        final EditText eLon = new EditText(this);
        eLon.setHint("经度，例如 114.4900");
        eLon.setHintTextColor(Ui.TEXT_FAINT);
        eLon.setTextColor(Ui.TEXT);
        eLon.setInputType(InputType.TYPE_CLASS_NUMBER
                | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        eLon.setBackground(Ui.round(Ui.SURFACE_2, 8, Ui.BORDER, 1f));
        eLon.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8));
        LinearLayout.LayoutParams lp = Ui.lpMatch();
        lp.topMargin = Ui.dp(this, 8);
        eLon.setLayoutParams(lp);
        if (hasCoord) {
            eLon.setText(String.format(Locale.CHINA, "%.6f", lon));
        }
        box.addView(eLon);

        TextView hint = Ui.text(this,
                "填入 WGS-84（GPS 原始）坐标。\n若你手头是高德/腾讯坐标，请先减掉约 300-600 米偏移，"
                        + "或直接用 App 里的 GPS 取点更准。",
                11, Ui.TEXT_FAINT);
        hint.setPadding(0, Ui.dp(this, 8), 0, 0);
        box.addView(hint);

        new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("手动输入坐标")
                .setView(box)
                .setPositiveButton("确定", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        try {
                            double la = Double.parseDouble(eLat.getText().toString().trim());
                            double lo = Double.parseDouble(eLon.getText().toString().trim());
                            if (la < -90 || la > 90 || lo < -180 || lo > 180) {
                                Toast.makeText(EditSpotActivity.this, "坐标超出合法范围",
                                        Toast.LENGTH_SHORT).show();
                                return;
                            }
                            lat = la;
                            lon = lo;
                            hasCoord = true;
                            renderCoord();
                        } catch (Exception e) {
                            Toast.makeText(EditSpotActivity.this, "请输入合法的经纬度数字",
                                    Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ================================================================ 渲染

    private void renderCoord() {
        if (hasCoord) {
            double[] gcj = com.xtlure.app.core.CoordinateTransform.wgs84ToGcj02(lat, lon);
            tvCoord.setText("✅ 已取到坐标\n"
                    + "GPS 原始:  " + String.format(Locale.CHINA, "%.5f, %.5f", lat, lon) + "\n"
                    + "高德/腾讯: " + String.format(Locale.CHINA, "%.5f, %.5f", gcj[0], gcj[1])
                    + "\n\n点一下复制 · 长按手动改");
            tvCoord.setTextColor(Ui.OK);
        } else {
            tvCoord.setText("还没有坐标\n点上面的按钮用 GPS 取，或长按这里手动输入");
            tvCoord.setTextColor(Ui.TEXT_FAINT);
        }
    }

    private void renderDistrict() {
        String v = spot.getDistrict();
        tvDistrict.setText(v.isEmpty() ? "点这里选择" : v);
        tvDistrict.setTextColor(v.isEmpty() ? Ui.TEXT_FAINT : Ui.TEXT);
    }

    private void renderWaterType() {
        tvWaterType.setText(FishingSpot.waterTypeName(spot.getWaterType()));
        tvWaterType.setTextColor(Ui.TEXT);
    }

    private void renderStatus() {
        tvStatus.setText(FishingSpot.statusName(spot.getStatus()));
        tvStatus.setTextColor(Ui.statusColor(spot.getStatus()));
    }

    private void renderConfidence() {
        tvConfidence.setText(FishingSpot.confidenceName(spot.getConfidence()));
        tvConfidence.setTextColor(spot.getConfidence() == FishingSpot.CONF_FIELD
                ? Ui.OK : Ui.TEXT);
    }

    // ================================================================ 保存

    private void save() {
        String name = etName.getText().toString().trim();
        if (name.isEmpty()) {
            Toast.makeText(this, "请填钓点名称", Toast.LENGTH_SHORT).show();
            etName.requestFocus();
            return;
        }
        if (Db.get(this).spotNameExists(name, isNew ? -1 : spotId)) {
            Toast.makeText(this, "已经有同名钓点了，换个名字以免混淆", Toast.LENGTH_LONG).show();
            return;
        }

        spot.setName(name);
        spot.setLat(lat);
        spot.setLon(lon);
        spot.setHasCoord(hasCoord);
        spot.setAddress(etAddress.getText().toString().trim());
        spot.setFishSpecies(etFish.getText().toString().trim());
        spot.setLureTypes(etLure.getText().toString().trim());
        spot.setBestSeason(etSeason.getText().toString().trim());
        spot.setAccessNote(etAccess.getText().toString().trim());
        spot.setNote(etNote.getText().toString().trim());
        spot.setRiskNote(etRisk.getText().toString().trim());
        spot.setSourceUrl(etSource.getText().toString().trim());

        Db db = Db.get(this);
        if (isNew) {
            db.insertSpot(spot);
            Toast.makeText(this, "已添加「" + name + "」", Toast.LENGTH_SHORT).show();
        } else {
            db.updateSpot(spot);
            Toast.makeText(this, "已保存修改", Toast.LENGTH_SHORT).show();
        }
        finish();
    }

    private void confirmDelete() {
        int n = Db.get(this).countCatchesForSpot(spotId);
        String msg = "确定删除「" + spot.getName() + "」吗？";
        if (n > 0) {
            msg += "\n\n该钓点有 " + n + " 条作钓记录，记录会保留但不再关联。";
        }
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("删除钓点")
                .setMessage(msg)
                .setPositiveButton("删除", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        Db.get(EditSpotActivity.this).deleteSpot(spotId);
                        Toast.makeText(EditSpotActivity.this, "已删除", Toast.LENGTH_SHORT).show();
                        finish();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ================================================================ 小控件

    private interface OnPick {
        void onPick(int index);
    }

    private void pickSingle(String title, final String[] options, final OnPick cb) {
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(title)
                .setItems(options, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        cb.onPick(which);
                    }
                })
                .show();
    }

    private void pickMulti(String title, final String[] options, final EditText target) {
        String cur = target.getText().toString();
        final boolean[] checked = new boolean[options.length];
        for (int i = 0; i < options.length; i++) {
            checked[i] = cur.contains(options[i]);
        }
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(title)
                .setMultiChoiceItems(options, checked,
                        new android.content.DialogInterface.OnMultiChoiceClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface d, int which,
                                                boolean isChecked) {
                                checked[which] = isChecked;
                            }
                        })
                .setPositiveButton("确定", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < options.length; i++) {
                            if (checked[i]) {
                                if (sb.length() > 0) {
                                    sb.append('、');
                                }
                                sb.append(options[i]);
                            }
                        }
                        target.setText(sb.toString());
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private TextView sectionTitle(String s) {
        TextView t = Ui.text(this, s, 12, Ui.ACCENT);
        t.setPadding(0, Ui.dp(this, 18), 0, Ui.dp(this, 4));
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        return t;
    }

    private EditText addInput(LinearLayout parent, String label, String hint, boolean number) {
        LinearLayout r = Ui.row(this);
        r.setPadding(0, Ui.dp(this, 5), 0, Ui.dp(this, 5));

        TextView l = Ui.text(this, label, 12, Ui.TEXT_FAINT);
        l.setMinWidth(Ui.dp(this, 72));
        r.addView(l);

        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(Ui.TEXT_FAINT);
        e.setTextColor(Ui.TEXT);
        e.setTextSize(14);
        e.setBackground(Ui.round(Ui.SURFACE_2, 8, Ui.BORDER, 1f));
        e.setPadding(Ui.dp(this, 10), Ui.dp(this, 9), Ui.dp(this, 10), Ui.dp(this, 9));
        r.addView(e, Ui.weighted(1f));

        parent.addView(r);
        return e;
    }

    private EditText addInputWithPick(LinearLayout parent, String label, String hint,
                                      final String[] presets, final boolean multi) {
        LinearLayout r = Ui.row(this);
        r.setPadding(0, Ui.dp(this, 5), 0, Ui.dp(this, 5));

        TextView l = Ui.text(this, label, 12, Ui.TEXT_FAINT);
        l.setMinWidth(Ui.dp(this, 72));
        r.addView(l);

        final EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(Ui.TEXT_FAINT);
        e.setTextColor(Ui.TEXT);
        e.setTextSize(14);
        e.setBackground(Ui.round(Ui.SURFACE_2, 8, Ui.BORDER, 1f));
        e.setPadding(Ui.dp(this, 10), Ui.dp(this, 9), Ui.dp(this, 10), Ui.dp(this, 9));
        r.addView(e, Ui.weighted(1f));

        // 「选择」按钮：高度 44dp（原先只有 30dp，手机上很难点准）
        TextView pick = Ui.text(this, "选择", 12, Ui.ACCENT);
        pick.setGravity(Gravity.CENTER);
        pick.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        pick.setBackground(Ui.ripple(Ui.SURFACE_3, 8, Ui.RIPPLE));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                Ui.dp(this, 64), Ui.dp(this, 44));
        plp.leftMargin = Ui.dp(this, 6);
        pick.setLayoutParams(plp);
        pick.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (multi) {
                    pickMulti("选择", presets, e);
                } else {
                    pickSingle("选择", presets, new OnPick() {
                        @Override
                        public void onPick(int index) {
                            e.setText(presets[index]);
                        }
                    });
                }
            }
        });
        r.addView(pick);

        parent.addView(r);
        return e;
    }

    private TextView addPickRow(LinearLayout parent, String label, final Runnable onClick) {
        LinearLayout r = Ui.row(this);
        r.setPadding(0, Ui.dp(this, 5), 0, Ui.dp(this, 5));

        TextView l = Ui.text(this, label, 12, Ui.TEXT_FAINT);
        l.setMinWidth(Ui.dp(this, 72));
        r.addView(l);

        TextView v = Ui.text(this, "", 14, Ui.TEXT);
        v.setBackground(Ui.round(Ui.SURFACE_2, 8, Ui.BORDER, 1f));
        v.setPadding(Ui.dp(this, 10), Ui.dp(this, 9), Ui.dp(this, 10), Ui.dp(this, 9));
        r.addView(v, Ui.weighted(1f));

        v.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                onClick.run();
            }
        });

        parent.addView(r);
        return v;
    }
}
