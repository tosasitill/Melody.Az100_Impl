package com.tosasitill.az100;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The module's settings page.
 *
 * <p>LSPosed opens this from the module list, and it is also the module's
 * launcher activity.  Built in code rather than from a layout resource: the
 * module's only compile-time dependency is the Xposed API, so there is no
 * appcompat theme to inflate against.  The visual pieces come from {@link Ui},
 * which the Melody detail page shares, so the two entry points stay identical.
 *
 * <p>Every switch writes straight through to {@link Prefs}.  The hooks read that
 * file on each connection, so a change takes effect on the next connect with no
 * restart; nothing is cached across processes.
 */
public class MainActivity extends Activity {

    private SharedPreferences prefs;
    private Ui.Palette palette;
    private TextView statusPill;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        palette = Ui.Palette.of(this);

        try {
            prefs = Prefs.open(this);
        } catch (Throwable t) {
            // MODE_WORLD_READABLE refused by the ROM.  Say so instead of showing
            // switches that cannot work.
            setContentView(Ui.errorScreen(this, palette, "无法写入配置",
                    "系统拒绝了 MODE_WORLD_READABLE。开关无法保存，模块也不会读到它们。",
                    t));
            return;
        }

        Ui.Page page = Ui.page(this, palette);
        LinearLayout root = page.column;

        addHeader(root);

        root.addView(Ui.sectionLabel(this, palette, "LE Audio"), spaced(22, 8));
        LinearLayout lea = Ui.card(this, palette);
        addSwitch(lea, Prefs.KEY_AUTO_LEA, Prefs.DEFAULT_AUTO_LEA,
                "自动切换到 LE Audio",
                "重连时把耳机升级到 LEA。关掉后只做连接，制式交给系统。");
        lea.addView(Ui.divider(this, palette));
        addSwitch(lea, Prefs.KEY_TWIN, Prefs.DEFAULT_TWIN,
                "自动连接另一只耳塞",
                "一只连上后把同组另一只也连上，双耳才能组成立体声组。");
        root.addView(lea);

        TextView leaNote = Ui.paragraph(this, palette,
                "系统详情页的 LE Audio 开关仍然有效。这里的开关只决定模块要不要主动升级。",
                0);
        leaNote.setPadding(0, Ui.dp(this, 10), 0, 0);
        root.addView(leaNote);

        root.addView(Ui.sectionLabel(this, palette, "音量"), spaced(22, 8));
        LinearLayout volume = Ui.card(this, palette);
        addSwitch(volume, Prefs.KEY_VOLUME_FIX, Prefs.DEFAULT_VOLUME_FIX,
                "音量修复",
                "抬高低音量下限，并丢弃重复的绝对音量写入。");
        root.addView(volume);

        root.addView(Ui.sectionLabel(this, palette, "诊断"), spaced(22, 8));
        LinearLayout diagnose = Ui.card(this, palette);
        addSwitch(diagnose, Prefs.KEY_DIAGNOSE, Prefs.DEFAULT_DIAGNOSE,
                "详细日志",
                "记录 UUID 流水线与音量收发细节。仅影响日志，不影响行为。");
        root.addView(diagnose);

        TextView logPaths = Ui.paragraph(this, palette,
                "/data/misc/bluetooth/az100.log\n"
                        + "/sdcard/Android/data/com.oplus.melody/files/az100.log",
                0);
        logPaths.setTypeface(android.graphics.Typeface.MONOSPACE);
        logPaths.setTextSize(11f);
        logPaths.setPadding(0, Ui.dp(this, 10), 0, 0);
        root.addView(logPaths);

        root.addView(Ui.paragraph(this, palette,
                "改动会在下次连接时生效，不需要重启蓝牙或重启手机。", 0),
                spaced(24, 0));

        setContentView(page.scroll);
    }

    /* ------------------------------------------------------------------ */

    private void addHeader(LinearLayout root) {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(Ui.display(this, palette, Az100Hook.AZ100_NAME));
        TextView subtitle = Ui.caption(this, palette, "ColorOS 音量面板控制 · v" + versionName());
        subtitle.setPadding(0, Ui.dp(this, 3), 0, 0);
        titles.addView(subtitle);
        header.addView(titles, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        statusPill = Ui.pill(this, palette, "已加载");
        Ui.setPill(statusPill, palette, true, "已加载");
        header.addView(statusPill);
        root.addView(header);
    }

    /**
     * One settings row.  Writes through on change and reports the result in the
     * header chip, so a failed write is never silent.
     */
    private void addSwitch(LinearLayout card, final String key, boolean fallback,
                           String label, String summary) {
        Ui.Toggle toggle = new Ui.Toggle(this, palette);
        toggle.setChecked(prefs.getBoolean(key, fallback));
        toggle.setOnChange(new Ui.OnChange() {
            @Override public void onChanged(boolean checked) {
                boolean ok = prefs.edit().putBoolean(key, checked).commit();
                Ui.setPill(statusPill, palette, ok, ok ? "已保存" : "保存失败");
            }
        });
        card.addView(Ui.row(this, palette, label, summary, toggle));
    }

    private LinearLayout.LayoutParams spaced(int top, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = Ui.dp(this, top);
        params.bottomMargin = Ui.dp(this, bottom);
        return params;
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "?";
        }
    }
}
