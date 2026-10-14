package com.tosasitill.az100

import android.app.Activity
import android.content.SharedPreferences
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The module's settings page.
 *
 * LSPosed opens this from the module list, and it is also the module's launcher
 * activity. Built in code rather than from a layout resource, without an
 * appcompat theme. The visual pieces come from [Ui], which the Melody detail page
 * shares, so the two entry points stay identical.
 *
 * Every switch writes through [Prefs] to private storage and framework remote
 * preferences. Hooks receive updates without a restart; connection behavior is
 * applied on the next connect.
 */
class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var palette: Ui.Palette
    private var statusPill: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        palette = Ui.Palette.of(this)

        prefs = try {
            Prefs.open(this)
        } catch (t: Throwable) {
            setContentView(
                Ui.errorScreen(
                    this, palette, "无法读取配置",
                    "配置未能打开，请重新启动模块设置。", t
                )
            )
            return
        }

        val page = Ui.page(this, palette)
        val root = page.column

        addHeader(root)

        root.addView(Ui.sectionLabel(this, palette, "LE Audio"), spaced(22, 8))
        val lea = Ui.card(this, palette)
        addSwitch(
            lea, Prefs.KEY_AUTO_LEA, Prefs.DEFAULT_AUTO_LEA,
            "自动切换到 LE Audio",
            "重连时把耳机升级到 LEA。关掉后只做连接，制式交给系统。"
        )
        lea.addView(Ui.divider(this, palette))
        addSwitch(
            lea, Prefs.KEY_TWIN, Prefs.DEFAULT_TWIN,
            "自动连接另一只耳塞",
            "一只连上后把同组另一只也连上，双耳才能组成立体声组。"
        )
        root.addView(lea)

        root.addView(
            Ui.paragraph(
                this, palette,
                "系统详情页的 LE Audio 开关仍然有效。这里的开关只决定模块要不要主动升级。", 0
            ).apply { setPadding(0, Ui.dp(this@MainActivity, 10f), 0, 0) }
        )

        root.addView(Ui.sectionLabel(this, palette, "音量"), spaced(22, 8))
        val volume = Ui.card(this, palette)
        addSwitch(
            volume, Prefs.KEY_VOLUME_FIX, Prefs.DEFAULT_VOLUME_FIX,
            "音量修复",
            "抬高低音量下限，并丢弃重复的绝对音量写入。"
        )
        root.addView(volume)

        root.addView(Ui.sectionLabel(this, palette, "诊断"), spaced(22, 8))
        val diagnose = Ui.card(this, palette)
        addSwitch(
            diagnose, Prefs.KEY_DIAGNOSE, Prefs.DEFAULT_DIAGNOSE,
            "详细日志",
            "记录 UUID 流水线与音量收发细节。仅影响日志，不影响行为。"
        )
        root.addView(diagnose)

        root.addView(
            Ui.paragraph(
                this, palette,
                "/data/misc/bluetooth/az100.log\n"
                        + "/sdcard/Android/data/com.oplus.melody/files/az100.log", 0
            ).apply {
                typeface = Typeface.MONOSPACE
                textSize = 11f
                setPadding(0, Ui.dp(this@MainActivity, 10f), 0, 0)
            }
        )

        root.addView(
            Ui.paragraph(this, palette, "改动会在下次连接时生效，不需要重启蓝牙或重启手机。", 0),
            spaced(24, 0)
        )

        setContentView(page.scroll)
    }

    /* ------------------------------------------------------------------ */

    private fun addHeader(root: LinearLayout) {
        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL

        val titles = LinearLayout(this)
        titles.orientation = LinearLayout.VERTICAL
        titles.addView(Ui.display(this, palette, Az100Hook.AZ100_NAME))
        titles.addView(
            Ui.caption(this, palette, "ColorOS 音量面板控制 · v" + versionName()).apply {
                setPadding(0, Ui.dp(this@MainActivity, 3f), 0, 0)
            }
        )
        header.addView(
            titles,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        statusPill = Ui.pill(this, palette, "服务未连接").also {
            val connected = Prefs.frameworkConnected
            Ui.setPill(it, palette, connected, if (connected) "服务已连接" else "服务未连接")
            header.addView(it)
        }
        root.addView(header)
    }

    /**
     * One settings row.  Writes through on change and reports the result in the
     * header chip, so a failed write is never silent.
     */
    private fun addSwitch(
        card: LinearLayout,
        key: String,
        fallback: Boolean,
        label: String,
        summary: String
    ) {
        val toggle = Ui.Toggle(this, palette)
        toggle.setChecked(prefs.getBoolean(key, fallback))
        toggle.setOnChange { checked ->
            val ok = Prefs.save(this, key, checked)
            statusPill?.let { Ui.setPill(it, palette, ok, if (ok) "已保存" else "保存失败") }
        }
        card.addView(Ui.row(this, palette, label, summary, toggle))
    }

    private fun spaced(top: Int, bottom: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = Ui.dp(this@MainActivity, top.toFloat())
            bottomMargin = Ui.dp(this@MainActivity, bottom.toFloat())
        }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (t: Throwable) {
        "?"
    }
}
