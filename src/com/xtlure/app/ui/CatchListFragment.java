package com.xtlure.app.ui;

import android.app.AlertDialog;
import android.app.Fragment;
import android.content.DialogInterface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.xtlure.app.data.Db;
import com.xtlure.app.model.CatchRecord;
import com.xtlure.app.util.Ui;

import java.util.List;

/**
 * 记录页：作钓/渔获流水。点卡片编辑，长按出菜单。
 */
public class CatchListFragment extends Fragment implements Refreshable {

    private EditText search;
    private TextView countLabel, emptyView;
    private ListView list;
    private CatchAdapter adapter;

    private String keyword = "";
    private final Handler debounce = new Handler(Looper.getMainLooper());
    private Runnable pending;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        adapter = new CatchAdapter(getActivity());
    }

    @Override
    public View onCreateView(android.view.LayoutInflater inf, ViewGroup parent,
                             Bundle savedInstanceState) {
        final android.content.Context ctx = getActivity();

        LinearLayout root = Ui.column(ctx);
        root.setBackgroundColor(Ui.BG);

        LinearLayout searchBox = Ui.row(ctx);
        searchBox.setBackground(Ui.round(Ui.SURFACE_2, Ui.RADIUS_BTN, Ui.BORDER, 1f));
        searchBox.setPadding(Ui.dp(ctx, 10), 0, Ui.dp(ctx, 10), 0);
        LinearLayout.LayoutParams sbLp = Ui.lpMatch(Ui.dp(ctx, 42));
        sbLp.leftMargin = Ui.dp(ctx, 12);
        sbLp.rightMargin = Ui.dp(ctx, 12);
        sbLp.topMargin = Ui.dp(ctx, 0);
        searchBox.setLayoutParams(sbLp);

        searchBox.addView(Ui.text(ctx, "🔍", 14, Ui.TEXT_FAINT));

        search = new EditText(ctx);
        search.setHint("搜钓点 / 鱼种 / 用饵");
        search.setHintTextColor(Ui.TEXT_FAINT);
        search.setTextColor(Ui.TEXT);
        search.setTextSize(14);
        search.setSingleLine(true);
        search.setBackground(null);
        search.setPadding(Ui.dp(ctx, 8), 0, 0, 0);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                keyword = s.toString();
                if (pending != null) {
                    debounce.removeCallbacks(pending);
                }
                pending = new Runnable() {
                    @Override
                    public void run() {
                        reload();
                    }
                };
                debounce.postDelayed(pending, 220);
            }
        });
        searchBox.addView(search, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        root.addView(searchBox);

        countLabel = Ui.text(ctx, "", Ui.TEXT_TINY, Ui.TEXT_FAINT);
        LinearLayout.LayoutParams clLp = Ui.lpMatch();
        clLp.topMargin = Ui.dp(ctx, 8);
        clLp.leftMargin = Ui.dp(ctx, 14);
        countLabel.setLayoutParams(clLp);
        root.addView(countLabel);

        list = new ListView(ctx);
        list.setAdapter(adapter);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setSelector(new android.graphics.drawable.ColorDrawable(0));
        list.setCacheColorHint(0);
        // ★ 底部留白让开悬浮按钮。
        //   按钮是浮在列表之上的，不留白的话滑到底时它会压住最后一条记录，
        //   而那块区域点不到条目 —— 与钓点页同一问题（用户反馈"最下方用不了"）。
        //   clipToPadding=false 让留白区域仍能滚动到，视觉上也更自然。
        list.setClipToPadding(false);
        list.setPadding(0, 0, 0, Ui.dp(ctx, 56 + 14 + 12));
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                CatchRecord r = adapter.getItem(position);
                CatchDialog.showForEdit(getActivity(), r.getId());
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view,
                                           int position, long id) {
                showMenu(adapter.getItem(position));
                return true;
            }
        });

        emptyView = Ui.text(ctx, "", 14, Ui.TEXT_FAINT);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(Ui.dp(ctx, 24), Ui.dp(ctx, 60), Ui.dp(ctx, 24), 0);

        android.widget.FrameLayout box = new android.widget.FrameLayout(ctx);
        box.addView(list, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        box.addView(emptyView, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(box, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

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

    private void reload() {
        if (getActivity() == null || adapter == null) {
            return;
        }
        Db db = Db.get(getActivity());
        List<CatchRecord> listData = db.queryCatches(keyword, -1);
        adapter.setData(listData);

        int total = 0;
        for (CatchRecord r : listData) {
            total += r.getCount();
        }
        if (listData.isEmpty()) {
            countLabel.setText("");
            list.setVisibility(View.GONE);
            emptyView.setVisibility(View.VISIBLE);
            emptyView.setText(keyword.isEmpty()
                    ? "还没有作钓记录\n\n点右下角 ＋ 记一次\n或进钓点详情页点「记录一次作钓」"
                    : "没有匹配的记录");
        } else {
            countLabel.setText("共 " + listData.size() + " 次作钓 · 累计 " + total + " 尾");
            list.setVisibility(View.VISIBLE);
            emptyView.setVisibility(View.GONE);
        }
    }

    private void showMenu(final CatchRecord r) {
        final String[] items = {"编辑这条记录", "再记一次（同钓点）", "删除"};
        new AlertDialog.Builder(getActivity(), android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(r.getSpotName().isEmpty() ? "作钓记录" : r.getSpotName())
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            CatchDialog.showForEdit(getActivity(), r.getId());
                        } else if (which == 1) {
                            CatchDialog.show(getActivity(), r.getSpotId(), r.getSpotName());
                        } else {
                            confirmDelete(r);
                        }
                    }
                })
                .show();
    }

    private void confirmDelete(final CatchRecord r) {
        new AlertDialog.Builder(getActivity(), android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("删除记录")
                .setMessage("确定删除这条作钓记录吗？删除后无法恢复。")
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Db.get(getActivity()).deleteCatch(r.getId());
                        Toast.makeText(getActivity(), "已删除", Toast.LENGTH_SHORT).show();
                        reload();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    @Override
    public void onDestroyView() {
        if (pending != null) {
            debounce.removeCallbacks(pending);
        }
        super.onDestroyView();
    }
}
