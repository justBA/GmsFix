package com.anb.gmsfix;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/** Dialog chọn nhiều app từ danh sách app đã cài. */
final class AppPicker {

    private static final class App {
        final String pkg;
        final String label;
        final Drawable icon;
        final boolean system;
        final AppGroups.Group group;

        App(String pkg, String label, Drawable icon, boolean system, AppGroups.Group group) {
            this.pkg = pkg;
            this.label = label;
            this.icon = icon;
            this.system = system;
            this.group = group;
        }
    }

    private AppPicker() {}

    /** Xiaomi/HyperOS: quyền runtime "Đọc danh sách ứng dụng đã cài" (MIUI security center). */
    static final String MIUI_PERM = "com.android.permission.GET_INSTALLED_APPS";
    static final int PERM_REQ = 1003;
    private static Runnable pending;

    /** ROM có quyền riêng của Xiaomi mà GMS Fix chưa được cho phép. */
    static boolean needsMiuiPerm(android.content.Context ctx) {
        try {
            android.content.pm.PermissionInfo pi = ctx.getPackageManager().getPermissionInfo(MIUI_PERM, 0);
            if (pi == null || !"com.lbe.security.miui".equals(pi.packageName)) return false;
        } catch (Throwable t) {
            return false; // ROM không có quyền này (không phải Xiaomi)
        }
        return ctx.checkSelfPermission(MIUI_PERM) != PackageManager.PERMISSION_GRANTED;
    }

    /** Gọi từ onRequestPermissionsResult của Activity: mở lại danh sách sau khi người dùng trả lời. */
    static void onPermissionResult(int requestCode) {
        if (requestCode != PERM_REQ || pending == null) return;
        Runnable r = pending;
        pending = null;
        r.run();
    }

    static void show(Activity activity, Set<String> current, Consumer<Set<String>> onDone) {
        if (pending == null && needsMiuiPerm(activity)) {
            pending = () -> showNow(activity, current, onDone);
            try {
                activity.requestPermissions(new String[]{MIUI_PERM}, PERM_REQ);
                return;
            } catch (Throwable t) {
                pending = null;
            }
        }
        pending = null;
        showNow(activity, current, onDone);
    }

    private static void showNow(Activity activity, Set<String> current, Consumer<Set<String>> onDone) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        Set<String> selected = new LinkedHashSet<>(current);
        List<App> all = new ArrayList<>();
        /** App hoặc AppGroups.Group (dòng tiêu đề nhóm). */
        List<Object> shown = new ArrayList<>();

        View view = LayoutInflater.from(activity).inflate(R.layout.dialog_app_picker, null);
        EditText search = view.findViewById(R.id.search);
        MaterialSwitch showSystem = view.findViewById(R.id.showSystem);
        RecyclerView list = view.findViewById(R.id.list);
        View loading = view.findViewById(R.id.loading);
        TextView note = view.findViewById(R.id.pickerNote);

        RecyclerView.Adapter<RecyclerView.ViewHolder> adapter = new RecyclerView.Adapter<>() {
            @Override
            public int getItemViewType(int pos) {
                return shown.get(pos) instanceof App ? 0 : 1;
            }

            @NonNull
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
                View v = LayoutInflater.from(parent.getContext()).inflate(
                        type == 0 ? R.layout.item_app : R.layout.item_picker_header, parent, false);
                return new RecyclerView.ViewHolder(v) {};
            }

            @Override
            public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int pos) {
                Object o = shown.get(pos);
                if (o instanceof AppGroups.Group) {
                    ((TextView) h.itemView).setText(((AppGroups.Group) o).title);
                    return;
                }
                App a = (App) o;
                View v = h.itemView;
                ((ImageView) v.findViewById(R.id.icon)).setImageDrawable(a.icon);
                ((TextView) v.findViewById(R.id.label)).setText(a.label);
                ((TextView) v.findViewById(R.id.pkg)).setText(a.pkg);
                CheckBox check = v.findViewById(R.id.check);
                check.setChecked(selected.contains(a.pkg));
                v.setOnClickListener(x -> {
                    if (!selected.remove(a.pkg)) selected.add(a.pkg);
                    check.setChecked(selected.contains(a.pkg));
                });
            }

            @Override
            public int getItemCount() {
                return shown.size();
            }
        };
        list.setLayoutManager(new LinearLayoutManager(activity));
        list.setAdapter(adapter);

        Runnable filter = () -> {
            String q = search.getText() == null ? "" : search.getText().toString().trim().toLowerCase(Locale.ROOT);
            shown.clear();
            AppGroups.Group last = null;
            for (App a : all) {
                if (a.system && !showSystem.isChecked() && !selected.contains(a.pkg)) continue;
                if (!q.isEmpty() && !a.label.toLowerCase(Locale.ROOT).contains(q) && !a.pkg.contains(q)) continue;
                if (a.group != last) {
                    shown.add(a.group);
                    last = a.group;
                }
                shown.add(a);
            }
            adapter.notifyDataSetChanged();
        };
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { filter.run(); }
        });
        showSystem.setOnCheckedChangeListener((b, c) -> filter.run());

        new MaterialAlertDialogBuilder(activity)
                .setTitle(I18n.t("Chọn ứng dụng"))
                .setView(view)
                .setPositiveButton("Xong", (d, w) -> onDone.accept(selected))
                .setNegativeButton(I18n.t("Hủy"), null)
                .show();

        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            PackageManager pm = activity.getPackageManager();
            List<App> apps = new ArrayList<>();
            String err = null;
            int user = 0;
            try {
                for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
                    if (ai.packageName.equals(activity.getPackageName())) continue;
                    try { // 1 app lỗi (label/icon hỏng) không được làm hỏng cả danh sách
                        boolean sys = (ai.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
                                && pm.getLaunchIntentForPackage(ai.packageName) == null;
                        if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) == 0) user++;
                        String label = String.valueOf(ai.loadLabel(pm));
                        Drawable icon;
                        try {
                            icon = ai.loadIcon(pm);
                        } catch (Throwable t) {
                            icon = pm.getDefaultActivityIcon();
                        }
                        apps.add(new App(ai.packageName, label, icon, sys,
                                AppGroups.of(ai.packageName, label, ai)));
                    } catch (Throwable ignored) {
                    }
                }
                // Theo nhóm; trong nhóm app đang chọn lên đầu, còn lại theo tên
                apps.sort((a, b) -> {
                    int g = a.group.compareTo(b.group);
                    if (g != 0) return g;
                    int s = Boolean.compare(!current.contains(a.pkg), !current.contains(b.pkg));
                    return s != 0 ? s : a.label.compareToIgnoreCase(b.label);
                });
            } catch (Throwable t) {
                err = I18n.t("Không đọc được danh sách ứng dụng: ") + t.getClass().getSimpleName() + ": " + t.getMessage();
            }
            String msg = err;
            if (msg == null && needsMiuiPerm(activity)) {
                msg = I18n.t("GMS Fix chưa được phép đọc danh sách ứng dụng nên chỉ thấy app hệ thống. Vào Cài đặt → Ứng dụng → ")
                        + I18n.t("GMS Fix → Quyền ứng dụng (Quyền khác) → bật \"Đọc danh sách ứng dụng đã cài\", rồi mở lại.");
            } else if (msg == null && user == 0) {
                msg = I18n.t("Không thấy app nào do bạn cài — ROM đang ẩn danh sách ứng dụng với GMS Fix. Kiểm tra quyền ")
                        + I18n.t("\"Đọc danh sách ứng dụng đã cài\" trong Cài đặt → Ứng dụng → GMS Fix.");
            }
            String fmsg = msg;
            main.post(() -> {
                all.addAll(apps);
                loading.setVisibility(View.GONE);
                if (fmsg != null) {
                    note.setText(fmsg);
                    note.setVisibility(View.VISIBLE);
                }
                filter.run();
            });
        }).start();
    }
}
