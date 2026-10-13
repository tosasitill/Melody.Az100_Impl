package com.tosasitill.az100;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
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
    private static final int DANGER = 0xFFE5484D;

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
     *
     * <p>Laid out with {@link Ui}, the same pieces the module's own settings
     * page uses, so the two entry points read as one product.
     */
    private static void installAz100Page(Activity activity) {
        View decor = activity.getWindow().getDecorView();
        if (decor.getTag(PAGE_TAG) != null) return;

        Ui.Palette palette = Ui.Palette.of(activity);
        Ui.Page page = Ui.page(activity, palette);
        LinearLayout column = page.column;

        TextView connection = addHeader(activity, palette, column);
        addBatteryCard(activity, palette, column, connection);
        addSettingsCard(activity, palette, column);

        activity.setTitle(Az100Hook.AZ100_NAME);

        int containerId = activity.getResources().getIdentifier(
                "melody_ui_fragment_container", "id", activity.getPackageName());
        View containerView = containerId == 0 ? null : activity.findViewById(containerId);
        if (containerView instanceof ViewGroup) {
            ViewGroup container = (ViewGroup) containerView;
            container.removeAllViews();
            container.addView(page.scroll, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            // Older Melody builds may use a different container id.  This
            // fallback still gives the user a working page, at the cost of
            // losing the stock toolbar on those builds.
            activity.setContentView(page.scroll);
        }

        decor.setTag(PAGE_TAG, Boolean.TRUE);
        Logs.trace("AZ100 detail page installed");
    }

    /**
     * Product name plus a connection chip that tracks the battery refresh.
     *
     * @return the chip, which the battery card updates when it refreshes
     */
    private static TextView addHeader(final Activity activity, Ui.Palette palette,
                                      LinearLayout column) {
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titles = new LinearLayout(activity);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(Ui.display(activity, palette, "Technics EAH-AZ100"));
        TextView subtitle = Ui.caption(activity, palette, "耳机详情");
        subtitle.setPadding(0, Ui.dp(activity, 3), 0, 0);
        titles.addView(subtitle);
        header.addView(titles, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView connection = Ui.pill(activity, palette, "检查中");
        header.addView(connection);
        column.addView(header);

        boolean connected = DirectAirohaController.reachable(activity, Az100Hook.AZ100_MAC);
        Ui.setPill(connection, palette, connected, connected ? "已连接" : "未连接");
        return connection;
    }

    /** Three battery rows and the refresh button, in one card. */
    private static void addBatteryCard(final Activity activity, Ui.Palette palette,
                                       LinearLayout column, final TextView connection) {
        column.addView(Ui.sectionLabel(activity, palette, "电量"), spaced(activity, 26, 8));

        LinearLayout card = Ui.card(activity, palette);
        final TextView[] values = new TextView[3];
        final Ui.Meter[] meters = new Ui.Meter[3];
        String[] labels = {"左耳", "右耳", "充电盒"};
        for (int i = 0; i < labels.length; i++) {
            if (i > 0) card.addView(Ui.divider(activity, palette));
            addBatteryRow(activity, palette, card, labels[i], values, meters, i);
        }
        column.addView(card);

        final TextView refresh = Ui.button(activity, palette, "立即刷新电量");
        final TextView status = Ui.caption(activity, palette, "");

        refresh.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                requestRefresh(activity, view, values, meters, connection, refresh, status);
            }
        });

        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.addView(refresh);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        statusParams.leftMargin = Ui.dp(activity, 12);
        actions.addView(status, statusParams);
        column.addView(actions, spaced(activity, 14, 0));

        updateBatteryValues(DirectAirohaController.cachedBattery(Az100Hook.AZ100_MAC),
                values, meters);
    }

    private static void addBatteryRow(Activity activity, Ui.Palette palette, LinearLayout card,
                                      String label, TextView[] values, Ui.Meter[] meters,
                                      int index) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(Ui.dp(activity, 16), Ui.dp(activity, 13),
                Ui.dp(activity, 16), Ui.dp(activity, 13));

        LinearLayout line = new LinearLayout(activity);
        line.setGravity(Gravity.CENTER_VERTICAL);

        TextView name = Ui.body(activity, palette, label);
        line.addView(name, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView value = Ui.body(activity, palette, "--");
        value.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        line.addView(value);
        row.addView(line);

        Ui.Meter meter = new Ui.Meter(activity, palette);
        LinearLayout.LayoutParams meterParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(activity, 5));
        meterParams.topMargin = Ui.dp(activity, 9);
        row.addView(meter, meterParams);

        card.addView(row);
        values[index] = value;
        meters[index] = meter;
    }

    private static LinearLayout.LayoutParams spaced(Activity activity, int top, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = Ui.dp(activity, top);
        params.bottomMargin = Ui.dp(activity, bottom);
        return params;
    }

    /**
     * The module's switches, rendered inside the Melody detail page.
     *
     * <p>They live here rather than only in the LSPosed module page because this
     * is where the user already is: the volume tile's 降噪 button opens this
     * activity, so the switch that decides whether a reconnect becomes LE Audio
     * is one tap away from the tile instead of buried in the module list.
     *
     * <p>Melody cannot write the module's prefs file -- different UID, and the
     * directory is {@code 0700} -- so every change goes through
     * {@link Az100ConfigProvider}, which runs in this module's own process.  A
     * failed round trip is surfaced in the status line rather than swallowed:
     * a switch that moves but does not save is worse than no switch.
     */
    private static void addSettingsCard(final Activity activity, Ui.Palette palette,
                                        LinearLayout column) {
        column.addView(Ui.sectionLabel(activity, palette, "模块设置"), spaced(activity, 26, 8));

        final TextView status = Ui.caption(activity, palette, "");
        LinearLayout card = Ui.card(activity, palette);
        Bundle current = readSettings(activity);

        addSettingSwitch(activity, palette, card, status, Prefs.KEY_AUTO_LEA,
                Prefs.DEFAULT_AUTO_LEA, "自动切换到 LE Audio",
                "关闭后重连不再升级到 LEA，系统开关仍然有效。", current);
        card.addView(Ui.divider(activity, palette));
        addSettingSwitch(activity, palette, card, status, Prefs.KEY_TWIN, Prefs.DEFAULT_TWIN,
                "自动连接另一只耳塞",
                "一只连上后，把同组另一只也连上。", current);
        card.addView(Ui.divider(activity, palette));
        addSettingSwitch(activity, palette, card, status, Prefs.KEY_VOLUME_FIX,
                Prefs.DEFAULT_VOLUME_FIX, "音量修复",
                "抬高低音量下限，并丢弃重复的绝对音量写入。", current);
        card.addView(Ui.divider(activity, palette));
        addSettingSwitch(activity, palette, card, status, Prefs.KEY_DIAGNOSE,
                Prefs.DEFAULT_DIAGNOSE, "详细日志",
                "记录 UUID 流水线与音量收发细节，仅影响日志。", current);
        column.addView(card);

        column.addView(status, spaced(activity, 10, 0));
    }

    /** One labelled switch, wired to the config provider. */
    private static void addSettingSwitch(final Activity activity, Ui.Palette palette,
                                         LinearLayout card, final TextView status,
                                         final String key, boolean fallback, String label,
                                         String summary, Bundle current) {
        final Ui.Toggle toggle = new Ui.Toggle(activity, palette);
        toggle.setChecked(current == null ? fallback : current.getBoolean(key, fallback));
        toggle.setOnChange(new Ui.OnChange() {
            @Override public void onChanged(boolean checked) {
                boolean ok = writeSetting(activity, key, checked);
                status.setText(ok ? "已保存" : "保存失败 —— 模块未安装或已被卸载");
                status.setTextColor(ok ? palette.fgSecondary : DANGER);
            }
        });
        card.addView(Ui.row(activity, palette, label, summary, toggle));
    }

    /** Every switch in one round trip, or {@code null} when the module is gone. */
    private static Bundle readSettings(Activity activity) {
        try {
            return activity.getContentResolver().call(
                    Az100ConfigProvider.URI, Az100ConfigProvider.METHOD_GET_ALL, null, null);
        } catch (Throwable t) {
            Logs.trace("detail: settings read failed - " + t);
            return null;
        }
    }

    private static boolean writeSetting(Activity activity, String key, boolean value) {
        Bundle extras = new Bundle();
        extras.putString(Az100ConfigProvider.EXTRA_KEY, key);
        extras.putBoolean(Az100ConfigProvider.EXTRA_VALUE, value);
        try {
            Bundle result = activity.getContentResolver().call(
                    Az100ConfigProvider.URI, Az100ConfigProvider.METHOD_SET, null, extras);
            return result != null && result.getBoolean("ok");
        } catch (Throwable t) {
            Logs.trace("detail: settings write failed - " + t);
            return false;
        }
    }

    private static void updateBatteryValues(int[] values, TextView[] views, Ui.Meter[] meters) {
        if (views == null) return;
        for (int i = 0; i < views.length; i++) {
            if (views[i] == null) continue;
            String text = percent(values, i);
            views[i].setText(text);
            if (meters != null && meters[i] != null) {
                meters[i].setFraction(values == null || i >= values.length
                        || values[i] < 0 || values[i] > 100 ? 0f : values[i] / 100f);
            }
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
        requestRefresh(activity, item, null, null, null, null, null);
    }

    private static void requestRefresh(Activity activity, Object control, TextView[] batteryValues,
                                       Ui.Meter[] meters, TextView connection,
                                       TextView refresh, TextView status) {
        if (control instanceof View) ((View) control).setEnabled(false);
        if (control instanceof TextView && control == refresh) {
            Ui.setButtonEnabled((TextView) control, false);
            ((TextView) control).setText("正在读取…");
        }
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
                            if (button instanceof TextView) {
                                Ui.setButtonEnabled((TextView) button, true);
                                ((TextView) button).setText("立即刷新电量");
                            }
                        }
                        MenuItem action = itemRef == null ? null : itemRef.get();
                        if (action != null) {
                            action.setEnabled(true);
                            action.setTitle("刷新电量");
                        }
                        if (host.isFinishing() || host.isDestroyed()) return;
                        updateConnection(host, connection);
                        updateBatteryValues(values, batteryValues, meters);
                        if (status != null) {
                            status.setText(resultText(values, receivedFreshSample));
                        }
                        Toast.makeText(host, resultText(values, receivedFreshSample),
                                Toast.LENGTH_LONG).show();
                    });
                });
    }

    private static void updateConnection(Activity activity, TextView view) {
        if (view == null) return;
        boolean connected = DirectAirohaController.reachable(activity, Az100Hook.AZ100_MAC);
        Ui.setPill(view, Ui.Palette.of(activity), connected, connected ? "已连接" : "未连接");
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
