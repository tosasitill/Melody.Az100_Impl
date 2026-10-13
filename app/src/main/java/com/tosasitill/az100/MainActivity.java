package com.tosasitill.az100;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.io.File;

/**
 * The module's settings page.
 *
 * <p>LSPosed opens this from the module list, and it is also the module's
 * launcher activity.  It is built in code rather than from a layout resource so
 * the whole page is one file -- there is no design here worth a resource.
 *
 * <p>Every switch writes straight through to {@link Prefs}.  The hooks read that
 * file on each connection, so a change takes effect on the next connect with no
 * restart; nothing is cached across processes.
 */
public class MainActivity extends Activity {

    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            prefs = Prefs.open(this);
        } catch (Throwable t) {
            // MODE_WORLD_READABLE refused by the ROM.  Say so instead of showing
            // switches that cannot work.
            setContentView(message(
                    "无法写入配置\n\n"
                            + "系统拒绝了 MODE_WORLD_READABLE。开关无法保存，"
                            + "模块也不会读到它们。\n\n" + t));
            return;
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);

        root.addView(title("Technics EAH-AZ100"));
        root.addView(subtitle("ColorOS 音量面板控制 · 版本 " + versionName()));

        root.addView(section("LE Audio"));

        addSwitch(root, Prefs.KEY_AUTO_LEA, Prefs.DEFAULT_AUTO_LEA,
                "自动切换到 LE Audio",
                "开启后，模块在耳机重新连接时会把它升级到 LE Audio（LEA）。\n\n"
                        + "关闭后只做连接，音频制式交给系统决定 —— 系统自带的 LE Audio "
                        + "开关仍然有效，本开关只控制模块要不要主动升级。\n\n"
                        + "如果你用系统详情页的 LE Audio 开关关掉了 LEA，又不想让它被"
                        + "自动打开，关掉这里。");

        addSwitch(root, Prefs.KEY_TWIN, Prefs.DEFAULT_TWIN,
                "自动连接另一只耳塞",
                "一只耳塞连上后，模块会尝试把同组另一只也连上，"
                        + "这样双耳才能组成立体声组。\n\n"
                        + "关闭后只连当前这一只。");

        root.addView(section("音量"));

        addSwitch(root, Prefs.KEY_VOLUME_FIX, Prefs.DEFAULT_VOLUME_FIX,
                "音量修复",
                "修掉 LE Audio 下的两个音量问题：把最低非零音量从 0 抬到 1，"
                        + "以及丢弃重复的绝对音量写入。\n\n"
                        + "关闭后耳机音量行为与原生一致。");

        root.addView(section("诊断"));

        addSwitch(root, Prefs.KEY_DIAGNOSE, Prefs.DEFAULT_DIAGNOSE,
                "详细日志",
                "把 UUID 流水线、音量收发等细节写入日志。\n\n"
                        + "日志位置：\n"
                        + "  /data/misc/bluetooth/az100.log\n"
                        + "  /sdcard/Android/data/com.oplus.melody/files/az100.log\n\n"
                        + "注意：音量修复本身不受此开关影响，关掉只少日志。");

        root.addView(section("状态"));

        root.addView(note(prefsFileNote()));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    /* ------------------------------------------------------------------ */

    private void addSwitch(LinearLayout root, final String key, boolean fallback,
                           String label, String summary) {
        Switch toggle = new Switch(this);
        toggle.setText(label);
        toggle.setTextSize(16f);
        toggle.setChecked(prefs.getBoolean(key, fallback));
        toggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton button, boolean checked) {
                prefs.edit().putBoolean(key, checked).apply();
            }
        });

        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(0, dp(12), 0, dp(12));
        block.addView(toggle);
        block.addView(subtitle(summary));
        root.addView(block);
    }

    private TextView title(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(22f);
        return view;
    }

    private TextView section(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(13f);
        view.setPadding(0, dp(20), 0, dp(4));
        return view;
    }

    private TextView subtitle(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(12f);
        view.setPadding(0, dp(4), 0, 0);
        return view;
    }

    private TextView note(String text) {
        return subtitle(text);
    }

    private TextView message(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(14f);
        int pad = dp(24);
        view.setPadding(pad, pad, pad, pad);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "?";
        }
    }

    /**
     * Where the config actually lives.
     *
     * <p>Not {@code getDataDir()/shared_prefs}: LSPosed redirects module prefs
     * into {@code /data/misc/apexdata/<uuid>/prefs/<pkg>/} so that a world- and
     * group-readable file can exist on a ROM that would otherwise refuse
     * {@code MODE_WORLD_READABLE}.  Measured on device -- the file is there and
     * mode {@code 0664}, while the app's own data dir has no {@code shared_prefs}
     * at all.  Showing the real path matters because the whole point of the page
     * is to make a silently-unreadable config visible.
     */
    private String prefsFileNote() {
        try {
            File file = new File("/data/misc/apexdata");
            File[] uuids = file.listFiles();
            if (uuids != null) {
                for (File uuid : uuids) {
                    File candidate = new File(uuid,
                            "prefs/" + getPackageName() + "/" + Prefs.FILE + ".xml");
                    if (candidate.isFile()) return describe(candidate);
                }
            }
            return "配置文件尚未生成 —— 改动任一开关后会出现";
        } catch (Throwable t) {
            return "无法定位配置文件：" + t;
        }
    }

    private String describe(File file) {
        return "配置文件：" + file.getAbsolutePath() + "\n"
                + (file.canRead() ? "可读，模块能读取" : "不可读 —— 模块读不到开关");
    }
}
