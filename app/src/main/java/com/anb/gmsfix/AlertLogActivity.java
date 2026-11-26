package com.anb.gmsfix;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

/** Trang log cảnh báo: cuộn, lọc theo loại, xoá. */
public class AlertLogActivity extends AppCompatActivity {

    private final List<String[]> all = new ArrayList<>();   // {time, type, text}
    private final List<String[]> shown = new ArrayList<>();
    private String filter; // null = tất cả
    private RecyclerView.Adapter<RecyclerView.ViewHolder> adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(savedInstanceState);
        I18n.sync(this);
        setContentView(R.layout.activity_alert_log);
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        RecyclerView list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.addItemDecoration(new DividerItemDecoration(this, DividerItemDecoration.VERTICAL));
        adapter = new RecyclerView.Adapter<>() {
            @NonNull
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup p, int t) {
                return new RecyclerView.ViewHolder(LayoutInflater.from(p.getContext())
                        .inflate(R.layout.item_alert_log, p, false)) {};
            }

            @Override
            public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int pos) {
                String[] e = shown.get(pos);
                ((TextView) h.itemView.findViewById(R.id.meta)).setText(e[0] + "  •  " + e[1]);
                ((TextView) h.itemView.findViewById(R.id.text)).setText(format(e[2]));
            }

            @Override
            public int getItemCount() {
                return shown.size();
            }
        };
        list.setAdapter(adapter);

        ChipGroup chips = findViewById(R.id.filters);
        addChip(chips, I18n.t("Tất cả"), null, true);
        for (AlertCenter.Type t : AlertCenter.Type.values()) addChip(chips, t.title, t.title, false);

        findViewById(R.id.btnClear).setOnClickListener(v -> new MaterialAlertDialogBuilder(this)
                .setTitle(I18n.t("Xoá toàn bộ lịch sử cảnh báo?"))
                .setPositiveButton(I18n.t("Xoá"), (d, w) -> {
                    AlertCenter.clearHistory(this);
                    load();
                })
                .setNegativeButton(I18n.t("Hủy"), null)
                .show());
        load();
    }

    /** "Apps — chi tiết ⏐ chi tiết" → tên app in đậm + chi tiết, bỏ dòng chỉ lặp lại tên app. */
    private static CharSequence format(String raw) {
        int d = raw.indexOf(" — ");
        String apps = d > 0 ? raw.substring(0, d) : "";
        String rest = d > 0 ? raw.substring(d + 3) : raw;
        java.util.Set<String> names = new java.util.HashSet<>(java.util.Arrays.asList(apps.split(", ")));
        StringBuilder body = new StringBuilder();
        for (String line : rest.split(" ⏐ ")) {
            String t = line.trim();
            if (t.isEmpty() || (t.startsWith("• ") && names.contains(t.substring(2)))) continue;
            if (body.length() > 0) body.append('\n');
            body.append(t);
        }
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        if (!apps.isEmpty()) {
            sb.append(apps, new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0);
            if (body.length() > 0) sb.append('\n');
        }
        return sb.append(body);
    }

    private void addChip(ChipGroup g, String label, String value, boolean checked) {
        Chip c = new Chip(this);
        c.setText(label);
        c.setCheckable(true);
        c.setChecked(checked);
        c.setOnCheckedChangeListener((b, on) -> {
            if (!on) return;
            filter = value;
            apply();
        });
        g.addView(c);
    }

    private void load() {
        all.clear();
        for (String line : AlertCenter.historyList(this)) {
            String time = "", type = "", text = line;
            int a = line.indexOf(" • ");
            if (a > 0) {
                time = line.substring(0, a);
                String rest = line.substring(a + 3);
                int b = rest.indexOf(": ");
                if (b > 0) {
                    type = rest.substring(0, b);
                    text = rest.substring(b + 2);
                } else {
                    text = rest;
                }
            }
            all.add(new String[]{time, type, text});
        }
        apply();
    }

    private void apply() {
        shown.clear();
        for (String[] e : all) if (filter == null || e[1].startsWith(filter.split(" ")[0] + " " + filter.split(" ")[1])) shown.add(e);
        adapter.notifyDataSetChanged();
        ((TextView) findViewById(R.id.count)).setText(shown.size() + " / " + all.size() + I18n.t(" mục"));
        findViewById(R.id.list).setVisibility(shown.isEmpty() ? View.GONE : View.VISIBLE);
        findViewById(R.id.empty).setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
    }
}
