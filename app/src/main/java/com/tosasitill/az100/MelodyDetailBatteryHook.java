package com.tosasitill.az100;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.ref.WeakReference;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

final class MelodyDetailBatteryHook {

    private static final String DETAIL_ACTIVITY =
            "com.oplus.melody.ui.component.detail.DetailMainActivity";
    private static final int MENU_ITEM_ID = 0x415a1001;
    private static final int PAGE_TAG = 0x415a1002;

    private MelodyDetailBatteryHook() {
    }

    static void install(ClassLoader loader) {
        try {
            Class<?> activity = XposedHelpers.findClass(DETAIL_ACTIVITY, loader);
            XposedHelpers.findAndHookMethod(activity, "A",
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            // DetailMainActivity.A() starts Melody's normal
                            // repository/model download.  AZ100 is not a
                            // Melody catalog product, so that request has no
                            // terminal result and leaves the stock page in its
                            // endless loading state.  The module-owned page
                            // below has its own finite data source.
                            if (isAz100Activity(param.thisObject)) {
                                param.setResult(null);
                                Logs.trace("detail stock load skipped for AZ100");
                            }
                        }
                    });
            XposedHelpers.findAndHookMethod(activity, "onCreate", Bundle.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (param.thisObject instanceof Activity) {
                                Activity host = (Activity) param.thisObject;
                                if (isAz100Activity(host)) {
                                    installAz100Page(host);
                                    addRefreshAction(host);
                                }
                            }
                        }
                    });
            Logs.trace("detail battery action hooked");
        } catch (Throwable t) {
            Logs.e("detail battery action hook failed", t);
        }
    }

    private static boolean isAz100Activity(Object object) {
        if (!(object instanceof Activity)) return false;
        Activity activity = (Activity) object;
        Bundle extras = activity.getIntent() == null ? null : activity.getIntent().getExtras();
        if (extras == null) return false;
        String address = extras.getString("device_mac_info");
        if (!Az100Hook.isAz100Mac(address)) {
            address = extras.getString("device_id");
        }
        return Az100Hook.isAz100Mac(address);
    }

    /**
     * Melody's catalog page cannot render a product which is absent from its
     * database.  Keep the normal Activity/toolbar shell, but replace the
     * catalog fragment with a small, deterministic AZ100 status page.  This
     * also means a missing cloud model file can never block the page itself.
     */
    private static void installAz100Page(Activity activity) {
        View decor = activity.getWindow().getDecorView();
        if (decor.getTag(PAGE_TAG) != null) return;

        boolean night = (activity.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        int foreground = night ? Color.WHITE : Color.rgb(32, 33, 36);
        int secondary = night ? Color.rgb(190, 190, 190) : Color.rgb(95, 99, 104);
        int background = night ? Color.rgb(18, 18, 18) : Color.WHITE;
        int spacing = dp(activity, 16);

        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(background);
        LinearLayout page = new LinearLayout(activity);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(spacing, dp(activity, 24), spacing, dp(activity, 32));
        scroll.addView(page, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView heading = text(activity, "Technics EAH-AZ100", 24, foreground);
        heading.setTypeface(heading.getTypeface(), android.graphics.Typeface.BOLD);
        page.addView(heading, matchWrap(0));

        TextView description = text(activity, "耳机详情", 15, secondary);
        LinearLayout.LayoutParams descriptionParams = matchWrap(dp(activity, 8));
        page.addView(description, descriptionParams);

        TextView connection = text(activity, "连接状态：检查中", 16, foreground);
        page.addView(connection, matchWrap(dp(activity, 28)));

        TextView[] batteryValues = new TextView[3];
        page.addView(sectionTitle(activity, "电量", foreground), matchWrap(dp(activity, 8)));
        addBatteryRow(activity, page, "左耳", batteryValues, 0, foreground, secondary);
        addBatteryRow(activity, page, "右耳", batteryValues, 1, foreground, secondary);
        addBatteryRow(activity, page, "充电盒", batteryValues, 2, foreground, secondary);

        Button refresh = new Button(activity);
        refresh.setText("立即刷新电量");
        refresh.setAllCaps(false);
        LinearLayout.LayoutParams buttonParams = matchWrap(dp(activity, 24));
        buttonParams.gravity = Gravity.START;
        page.addView(refresh, buttonParams);

        TextView status = text(activity, "", 14, secondary);
        page.addView(status, matchWrap(dp(activity, 12)));

        updateConnection(activity, connection);
        updateBatteryValues(DirectAirohaController.cachedBattery(Az100Hook.AZ100_MAC), batteryValues);
        refresh.setOnClickListener(view -> requestRefresh(activity, refresh, batteryValues,
                connection, status));

        activity.setTitle(Az100Hook.AZ100_NAME);
        int containerId = activity.getResources().getIdentifier(
                "melody_ui_fragment_container", "id", activity.getPackageName());
        View containerView = containerId == 0 ? null : activity.findViewById(containerId);
        if (containerView instanceof ViewGroup) {
            ViewGroup container = (ViewGroup) containerView;
            container.removeAllViews();
            container.addView(scroll, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            // Older Melody builds may use a different container id.  This
            // fallback still gives the user a working page, at the cost of
            // losing the stock toolbar on those builds.
            activity.setContentView(scroll);
        }
        decor.setTag(PAGE_TAG, Boolean.TRUE);
        Logs.trace("AZ100 detail page installed");
    }

    private static TextView sectionTitle(Activity activity, String value, int color) {
        TextView view = text(activity, value, 18, color);
        view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        return view;
    }

    private static void addBatteryRow(Activity activity, LinearLayout page, String label,
                                      TextView[] values, int index, int foreground, int secondary) {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text(activity, label, 16, foreground);
        TextView value = text(activity, "--", 16, secondary);
        value.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        row.addView(name, new LinearLayout.LayoutParams(0, dp(activity, 52), 1));
        row.addView(value, new LinearLayout.LayoutParams(dp(activity, 100),
                dp(activity, 52)));
        page.addView(row, matchWrap(0));
        values[index] = value;
    }

    private static TextView text(Activity activity, String value, float size, int color) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private static LinearLayout.LayoutParams matchWrap(int topMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = topMargin;
        return params;
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private static void updateConnection(Activity activity, TextView view) {
        if (view == null) return;
        view.setText("连接状态：" + (DirectAirohaController.reachable(
                activity, Az100Hook.AZ100_MAC) ? "已连接" : "未连接"));
    }

    private static void updateBatteryValues(int[] values, TextView[] views) {
        if (views == null) return;
        for (int i = 0; i < views.length; i++) {
            if (views[i] == null) continue;
            views[i].setText(percent(values, i));
        }
    }

    private static String percent(int[] values, int index) {
        if (values == null || index >= values.length || values[index] < 0
                || values[index] > 100) return "--";
        return values[index] + "%";
    }

    private static void addRefreshAction(Activity activity) {
        int toolbarId = activity.getResources().getIdentifier(
                "toolbar", "id", activity.getPackageName());
        if (toolbarId == 0) return;
        View toolbar = activity.findViewById(toolbarId);
        if (toolbar == null) return;

        try {
            Object menuObject = XposedHelpers.callMethod(toolbar, "getMenu");
            if (!(menuObject instanceof Menu)) return;
            Menu menu = (Menu) menuObject;
            if (menu.findItem(MENU_ITEM_ID) != null) return;

            MenuItem item = menu.add(Menu.NONE, MENU_ITEM_ID, Menu.NONE, "刷新电量");
            item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
            item.setContentDescription("立即刷新 AZ100 电量");
            item.setOnMenuItemClickListener(clicked -> {
                requestRefresh(activity, clicked);
                return true;
            });
        } catch (Throwable t) {
            Logs.e("detail battery action add failed", t);
        }
    }

    private static void requestRefresh(Activity activity, MenuItem item) {
        requestRefresh(activity, item, null, null, null);
    }

    private static void requestRefresh(Activity activity, Object control, TextView[] batteryValues,
                                       TextView connection, TextView status) {
        if (control instanceof View) ((View) control).setEnabled(false);
        if (control instanceof Button) ((Button) control).setText("正在读取…");
        if (control instanceof MenuItem) ((MenuItem) control).setTitle("正在读取");

        WeakReference<Activity> activityRef = new WeakReference<>(activity);
        WeakReference<View> controlRef = control instanceof View
                ? new WeakReference<>((View) control) : null;
        MenuItem menuItem = control instanceof MenuItem ? (MenuItem) control : null;
        WeakReference<MenuItem> itemRef = menuItem == null ? null : new WeakReference<>(menuItem);
        DirectAirohaController.refreshBattery(activity, Az100Hook.AZ100_MAC,
                (values, receivedFreshSample) -> {
                    Activity host = activityRef.get();
                    if (host == null) return;
                    host.runOnUiThread(() -> {
                        View button = controlRef == null ? null : controlRef.get();
                        if (button != null) {
                            button.setEnabled(true);
                            if (button instanceof Button) ((Button) button).setText("立即刷新电量");
                        }
                        MenuItem action = itemRef == null ? null : itemRef.get();
                        if (action != null) {
                            action.setEnabled(true);
                            action.setTitle("刷新电量");
                        }
                        if (host.isFinishing() || host.isDestroyed()) return;
                        updateConnection(host, connection);
                        updateBatteryValues(values, batteryValues);
                        if (status != null) {
                            status.setText(resultText(values, receivedFreshSample));
                        }
                        Toast.makeText(host, resultText(values, receivedFreshSample),
                                Toast.LENGTH_LONG).show();
                    });
                });
    }

    private static String resultText(int[] values, boolean receivedFreshSample) {
        if (!receivedFreshSample || values == null) {
            return "没有收到 AZ100 的新电量，请确认耳机已连接后重试";
        }
        return "AZ100 电量：左耳 " + percent(values, 0)
                + "，右耳 " + percent(values, 1)
                + "，充电盒 " + percent(values, 2);
    }

}
