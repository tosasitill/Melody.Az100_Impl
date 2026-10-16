package com.tosasitill.az100

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.highcapable.kavaref.KavaRef.Companion.resolve
import java.lang.ref.WeakReference

/**
 * The AZ100 page inside Melody's own detail activity.
 *
 * Melody's catalog page cannot render a product which is absent from its database,
 * so [install] replaces the catalog fragment with a small, deterministic status
 * page and answers the module's own switches in the same place the user already is.
 */
object MelodyDetailBatteryHook {

    private const val DETAIL_ACTIVITY = "com.oplus.melody.ui.component.detail.DetailMainActivity"
    private const val MENU_ITEM_ID = 0x415a1001
    private const val PAGE_TAG = 0x415a1002
    private const val DANGER = 0xFFE5484D.toInt()

    fun install(param: HookRuntime) {
        val loader = param.appClassLoader ?: return
        try {
            val activity = Reflect.findClass(DETAIL_ACTIVITY, loader)
            with(param) {
                activity.resolve().optional(silent = true).firstMethod {
                    name = "A"
                    emptyParameters()
                }
                    .hook {
                    before {
                        // DetailMainActivity.A() starts Melody's normal repository/model
                        // download.  AZ100 is not a Melody catalog product, so that
                        // request has no terminal result and leaves the stock page in its
                        // endless loading state.  The module-owned page below has its own
                        // finite data source.
                        if (isAz100Activity(instanceOrNull)) {
                            result = null
                            Logs.trace("detail stock load skipped for AZ100")
                        }
                    }
                }
                activity.resolve().optional(silent = true).firstMethod {
                    name = "onCreate"
                    parameters(Bundle::class.java)
                }.hook {
                    after {
                        val host = instanceOrNull as? Activity ?: return@after
                        if (isAz100Activity(host)) {
                            installAz100Page(host)
                            addRefreshAction(host)
                        }
                    }
                }
            }
            Logs.trace("detail battery action hooked")
        } catch (t: Throwable) {
            Logs.e("detail battery action hook failed", t)
        }
    }

    private fun isAz100Activity(any: Any?): Boolean {
        val activity = any as? Activity ?: return false
        val extras = activity.intent?.extras ?: return false
        var address = extras.getString("device_mac_info")
        if (!Az100Hook.isAz100Mac(address)) address = extras.getString("device_id")
        return Az100Hook.isAz100Mac(address)
    }

    /**
     * Melody's catalog page cannot render a product which is absent from its
     * database.  Keep the normal Activity/toolbar shell, but replace the catalog
     * fragment with a small, deterministic AZ100 status page.  This also means a
     * missing cloud model file can never block the page itself.
     *
     * Laid out with [Ui], the same pieces the module's own settings page uses, so
     * the two entry points read as one product.
     */
    private fun installAz100Page(activity: Activity) {
        val decor = activity.window.decorView
        if (decor.getTag(PAGE_TAG) != null) return

        val palette = Ui.Palette.of(activity)
        val page = Ui.page(activity, palette)
        val column = page.column

        val connection = addHeader(activity, palette, column)
        addBatteryCard(activity, palette, column, connection)
        addSettingsCard(activity, palette, column)

        activity.title = Az100Hook.AZ100_NAME

        val containerId = activity.resources.getIdentifier(
            "melody_ui_fragment_container", "id", activity.packageName
        )
        val containerView = if (containerId == 0) null else activity.findViewById<View>(containerId)
        if (containerView is ViewGroup) {
            containerView.removeAllViews()
            containerView.addView(
                page.scroll,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        } else {
            // Older Melody builds may use a different container id.  This fallback
            // still gives the user a working page, at the cost of losing the stock
            // toolbar on those builds.
            activity.setContentView(page.scroll)
        }

        decor.setTag(PAGE_TAG, true)
        Logs.trace("AZ100 detail page installed")
    }

    /**
     * Product name plus a connection chip that tracks the battery refresh.
     *
     * @return the chip, which the battery card updates when it refreshes
     */
    private fun addHeader(
        activity: Activity,
        palette: Ui.Palette,
        column: LinearLayout
    ): TextView {
        val header = LinearLayout(activity)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL

        val titles = LinearLayout(activity)
        titles.orientation = LinearLayout.VERTICAL
        titles.addView(Ui.display(activity, palette, "Technics EAH-AZ100"))
        titles.addView(
            Ui.caption(activity, palette, "耳机详情").apply {
                setPadding(0, Ui.dp(activity, 3f), 0, 0)
            }
        )
        header.addView(
            titles,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        val connection = Ui.pill(activity, palette, "检查中")
        header.addView(connection)
        column.addView(header)

        val connected = DirectAirohaController.reachable(activity, Az100Hook.AZ100_MAC)
        Ui.setPill(connection, palette, connected, if (connected) "已连接" else "未连接")
        return connection
    }

    /** Three battery rows and the refresh button, in one card. */
    private fun addBatteryCard(
        activity: Activity,
        palette: Ui.Palette,
        column: LinearLayout,
        connection: TextView
    ) {
        column.addView(Ui.sectionLabel(activity, palette, "电量"), spaced(activity, 26, 8))

        val card = Ui.card(activity, palette)
        val values = arrayOfNulls<TextView>(3)
        val meters = arrayOfNulls<Ui.Meter>(3)
        val labels = arrayOf("左耳", "右耳", "充电盒")
        for (i in labels.indices) {
            if (i > 0) card.addView(Ui.divider(activity, palette))
            addBatteryRow(activity, palette, card, labels[i], values, meters, i)
        }
        column.addView(card)

        val refresh = Ui.button(activity, palette, "立即刷新电量")
        val status = Ui.caption(activity, palette, "")

        refresh.setOnClickListener {
            requestRefresh(activity, it, values, meters, connection, refresh, status)
        }

        val actions = LinearLayout(activity)
        actions.orientation = LinearLayout.HORIZONTAL
        actions.gravity = Gravity.CENTER_VERTICAL
        actions.addView(refresh)
        actions.addView(
            status,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = Ui.dp(activity, 12f)
            }
        )
        column.addView(actions, spaced(activity, 14, 0))

        updateBatteryValues(
            DirectAirohaController.cachedBattery(Az100Hook.AZ100_MAC), values, meters
        )
    }

    private fun addBatteryRow(
        activity: Activity,
        palette: Ui.Palette,
        card: LinearLayout,
        label: String,
        values: Array<TextView?>,
        meters: Array<Ui.Meter?>,
        index: Int
    ) {
        val row = LinearLayout(activity)
        row.orientation = LinearLayout.VERTICAL
        row.setPadding(
            Ui.dp(activity, 16f), Ui.dp(activity, 13f),
            Ui.dp(activity, 16f), Ui.dp(activity, 13f)
        )

        val line = LinearLayout(activity)
        line.gravity = Gravity.CENTER_VERTICAL

        line.addView(
            Ui.body(activity, palette, label),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        val value = Ui.body(activity, palette, "--")
        value.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        line.addView(value)
        row.addView(line)

        val meter = Ui.Meter(activity, palette)
        row.addView(
            meter,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(activity, 5f)
            ).apply { topMargin = Ui.dp(activity, 9f) }
        )

        card.addView(row)
        values[index] = value
        meters[index] = meter
    }

    private fun spaced(activity: Activity, top: Int, bottom: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = Ui.dp(activity, top.toFloat())
            bottomMargin = Ui.dp(activity, bottom.toFloat())
        }

    /**
     * The module's switches, rendered inside the Melody detail page.
     *
     * They live here rather than only in the LSPosed module page because this is
     * where the user already is: the volume tile's 降噪 button opens this activity,
     * so the switch that decides whether a reconnect becomes LE Audio is one tap
     * away from the tile instead of buried in the module list.
     *
     * Melody cannot write the module's prefs file -- different UID, and the
     * directory is `0700` -- so every change goes through [Az100ConfigProvider],
     * which runs in this module's own process.  A failed round trip is surfaced in
     * the status line rather than swallowed: a switch that moves but does not save is
     * worse than no switch.
     */
    private fun addSettingsCard(
        activity: Activity,
        palette: Ui.Palette,
        column: LinearLayout
    ) {
        column.addView(Ui.sectionLabel(activity, palette, "模块设置"), spaced(activity, 26, 8))

        val status = Ui.caption(activity, palette, "")
        val card = Ui.card(activity, palette)
        val current = readSettings(activity)

        addSettingSwitch(
            activity, palette, card, status, Prefs.KEY_AUTO_LEA, Prefs.DEFAULT_AUTO_LEA,
            "自动切换到 LE Audio",
            "关闭后重连不再升级到 LEA，系统开关仍然有效。", current
        )
        card.addView(Ui.divider(activity, palette))
        addSettingSwitch(
            activity, palette, card, status, Prefs.KEY_TWIN, Prefs.DEFAULT_TWIN,
            "自动连接另一只耳塞",
            "一只连上后，把同组另一只也连上。", current
        )
        card.addView(Ui.divider(activity, palette))
        addSettingSwitch(
            activity, palette, card, status, Prefs.KEY_VOLUME_FIX, Prefs.DEFAULT_VOLUME_FIX,
            "音量修复",
            "抬高低音量下限，并丢弃重复的绝对音量写入。", current
        )
        card.addView(Ui.divider(activity, palette))
        addSettingSwitch(
            activity, palette, card, status, Prefs.KEY_DIAGNOSE, Prefs.DEFAULT_DIAGNOSE,
            "详细日志",
            "记录 UUID 流水线与音量收发细节，仅影响日志。", current
        )
        column.addView(card)

        column.addView(status, spaced(activity, 10, 0))
    }

    /** One labelled switch, wired to the config provider. */
    private fun addSettingSwitch(
        activity: Activity,
        palette: Ui.Palette,
        card: LinearLayout,
        status: TextView,
        key: String,
        fallback: Boolean,
        label: String,
        summary: String,
        current: Bundle?
    ) {
        val toggle = Ui.Toggle(activity, palette)
        toggle.setChecked(current?.getBoolean(key, fallback) ?: fallback)
        toggle.setOnChange { checked ->
            val ok = writeSetting(activity, key, checked)
            status.text = if (ok) "已保存" else "保存失败 —— 模块未安装或已被卸载"
            status.setTextColor(if (ok) palette.fgSecondary else DANGER)
        }
        card.addView(Ui.row(activity, palette, label, summary, toggle))
    }

    /** Every switch in one round trip, or null when the module is gone. */
    private fun readSettings(activity: Activity): Bundle? = try {
        activity.contentResolver.call(
            Az100ConfigProvider.URI, Az100ConfigProvider.METHOD_GET_ALL, null, null
        )
    } catch (t: Throwable) {
        Logs.trace("detail: settings read failed - $t")
        null
    }

    private fun writeSetting(activity: Activity, key: String, value: Boolean): Boolean {
        val extras = Bundle().apply {
            putString(Az100ConfigProvider.EXTRA_KEY, key)
            putBoolean(Az100ConfigProvider.EXTRA_VALUE, value)
        }
        return try {
            val result = activity.contentResolver.call(
                Az100ConfigProvider.URI, Az100ConfigProvider.METHOD_SET, null, extras
            )
            result != null && result.getBoolean("ok")
        } catch (t: Throwable) {
            Logs.trace("detail: settings write failed - $t")
            false
        }
    }

    private fun updateBatteryValues(
        values: IntArray?,
        views: Array<TextView?>?,
        meters: Array<Ui.Meter?>?
    ) {
        if (views == null) return
        for (i in views.indices) {
            val view = views[i] ?: continue
            view.text = percent(values, i)
            meters?.get(i)?.setFraction(
                if (values == null || i >= values.size || values[i] < 0 || values[i] > 100) {
                    0f
                } else {
                    values[i] / 100f
                }
            )
        }
    }

    private fun percent(values: IntArray?, index: Int): String {
        if (values == null || index >= values.size || values[index] < 0 || values[index] > 100) {
            return "--"
        }
        return "${values[index]}%"
    }

    private fun addRefreshAction(activity: Activity) {
        val toolbarId = activity.resources.getIdentifier("toolbar", "id", activity.packageName)
        if (toolbarId == 0) return
        val toolbar = activity.findViewById<View>(toolbarId) ?: return

        try {
            val menu = Reflect.callMethod(toolbar, "getMenu") as? Menu ?: return
            if (menu.findItem(MENU_ITEM_ID) != null) return

            menu.add(Menu.NONE, MENU_ITEM_ID, Menu.NONE, "刷新电量").apply {
                setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                contentDescription = "立即刷新 AZ100 电量"
                setOnMenuItemClickListener {
                    requestRefresh(activity, it)
                    true
                }
            }
        } catch (t: Throwable) {
            Logs.e("detail battery action add failed", t)
        }
    }

    private fun requestRefresh(activity: Activity, item: MenuItem) =
        requestRefresh(activity, item, null, null, null, null, null)

    private fun requestRefresh(
        activity: Activity,
        control: Any?,
        batteryValues: Array<TextView?>?,
        meters: Array<Ui.Meter?>?,
        connection: TextView?,
        refresh: TextView?,
        status: TextView?
    ) {
        (control as? View)?.isEnabled = false
        if (control is TextView && control === refresh) {
            Ui.setButtonEnabled(control, false)
            control.text = "正在读取…"
        }
        (control as? MenuItem)?.title = "正在读取"

        // Every view this callback touches is held weakly.  The read is bounded
        // (one RFCOMM round trip with a 6 s ceiling per stage) so the leak was
        // never permanent, but the detail activity can be closed mid-read and a
        // strong reference here kept its whole view tree alive until the socket
        // answered -- the same reason `activityRef` was already weak.
        val activityRef = WeakReference(activity)
        val controlRef = (control as? View)?.let { WeakReference(it) }
        val itemRef = (control as? MenuItem)?.let { WeakReference(it) }
        val connectionRef = connection?.let { WeakReference(it) }
        val statusRef = status?.let { WeakReference(it) }
        DirectAirohaController.refreshBattery(activity, Az100Hook.AZ100_MAC) { values, fresh ->
            val host = activityRef.get() ?: return@refreshBattery
            host.runOnUiThread {
                controlRef?.get()?.let { button ->
                    button.isEnabled = true
                    if (button is TextView) {
                        Ui.setButtonEnabled(button, true)
                        button.text = "立即刷新电量"
                    }
                }
                itemRef?.get()?.let { action ->
                    action.isEnabled = true
                    action.title = "刷新电量"
                }
                if (host.isFinishing || host.isDestroyed) return@runOnUiThread
                updateConnection(host, connectionRef?.get())
                updateBatteryValues(values, batteryValues, meters)
                val text = resultText(values, fresh)
                statusRef?.get()?.text = text
                Toast.makeText(host, text, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun updateConnection(activity: Activity, view: TextView?) {
        if (view == null) return
        val connected = DirectAirohaController.reachable(activity, Az100Hook.AZ100_MAC)
        Ui.setPill(view, Ui.Palette.of(activity), connected, if (connected) "已连接" else "未连接")
    }

    private fun resultText(values: IntArray?, receivedFreshSample: Boolean): String {
        if (!receivedFreshSample || values == null) {
            return "没有收到 AZ100 的新电量，请确认耳机已连接后重试"
        }
        return "AZ100 电量：左耳 ${percent(values, 0)}" +
                "，右耳 ${percent(values, 1)}" +
                "，充电盒 ${percent(values, 2)}"
    }
}
