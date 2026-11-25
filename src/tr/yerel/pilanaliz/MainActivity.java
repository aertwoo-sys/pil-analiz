package tr.yerel.pilanaliz;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Tek ekranlı uygulama. Sadece açıkken, "Yenile"ye basınca dumpsys batterystats okur.
 * Arka planda hiçbir şey çalışmaz; ağ izni yoktur.
 */
public class MainActivity extends Activity {

    private static final int SORT_TOTAL = 0, SORT_SCREEN_OFF = 1, SORT_BG = 2;

    private int bg, card, text, sub, accent, warn, barBg, chipBg;
    private LinearLayout content;
    private TextView status;
    private StatsParser.Result last;
    private int sortMode = SORT_TOTAL;
    private final List<Button> sortButtons = new ArrayList<>();
    private final Map<Integer, String[]> labelCache = new HashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupColors();
        getWindow().getDecorView().setBackgroundColor(bg);
        if (!isNight()) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }

        final ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(bg);
        scroll.setClipToPadding(false);
        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        final int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        scroll.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override public WindowInsets onApplyWindowInsets(View v, WindowInsets in) {
                root.setPadding(pad + in.getSystemWindowInsetLeft(), pad + in.getSystemWindowInsetTop(),
                        pad + in.getSystemWindowInsetRight(), pad + in.getSystemWindowInsetBottom());
                return in;
            }
        });

        TextView title = tv("Pil Analizi", 26, text, true);
        root.addView(title);
        TextView subtitle = tv("Son şarjdan beri · Android'in ham kayıtlarından · çevrimdışı", 13, sub, false);
        subtitle.setPadding(0, dp(2), 0, dp(12));
        root.addView(subtitle);

        Button refresh = button("Yenile", true);
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { load(); }
        });
        root.addView(refresh, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));

        status = tv("", 13, sub, false);
        status.setPadding(0, dp(8), 0, 0);
        root.addView(status);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        root.addView(content);

        setContentView(scroll);
        scroll.requestApplyInsets();
        load();
    }

    // ---------------------------------------------------------------- veri

    private void load() {
        status.setText("Okunuyor…");
        new Thread(new Runnable() {
            @Override public void run() {
                final List<String> lines = new ArrayList<>();
                String error = null;
                try {
                    Process p = Runtime.getRuntime().exec(new String[]{"dumpsys", "batterystats", "--charged"});
                    BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
                    String l;
                    while ((l = r.readLine()) != null) lines.add(l);
                    p.waitFor();
                } catch (Exception e) {
                    error = e.toString();
                }
                final String err = error;
                StatsParser.Result res = null;
                boolean denied = false;
                for (int i = 0; i < Math.min(lines.size(), 5); i++) {
                    if (lines.get(i).contains("Permission Denial")) denied = true;
                }
                if (err == null && !denied) res = StatsParser.parse(lines);
                final StatsParser.Result fres = res;
                final boolean fdenied = denied || (res != null && res.apps.isEmpty() && lines.size() < 5);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (err != null || fdenied || fres == null) {
                            showPermissionHelp(err);
                        } else {
                            last = fres;
                            render();
                        }
                    }
                });
            }
        }).start();
    }

    // ---------------------------------------------------------------- ekran

    private void showPermissionHelp(String err) {
        status.setText("");
        content.removeAllViews();
        LinearLayout c = card();
        c.addView(tv("İzin gerekli", 17, warn, true));
        TextView t = tv("Uygulamanın pil kayıtlarını okuyabilmesi için bilgisayardan bir kez şu komutları çalıştırın:\n\n"
                + "adb shell pm grant tr.yerel.pilanaliz android.permission.DUMP\n"
                + "adb shell pm grant tr.yerel.pilanaliz android.permission.BATTERY_STATS"
                + (err != null ? "\n\nHata: " + err : ""), 14, text, false);
        t.setTextIsSelectable(true);
        t.setPadding(0, dp(8), 0, 0);
        c.addView(t);
        content.addView(c);
    }

    private void render() {
        StatsParser.Result r = last;
        content.removeAllViews();
        status.setText("Güncellendi · kayıt başlangıcı: " + r.startClock);

        if (!r.onBattery) {
            LinearLayout w = card();
            w.addView(tv("Telefon şu an şarjda", 15, warn, true));
            TextView wt = tv("Sadece pil üzerinde geçen süre sayılıyor. Şarjdan çıkardıktan sonra Android "
                    + "kayıtları sıfırlayabilir; en doğru sonuç için şarja takmadan hemen önce bakın.", 13, sub, false);
            wt.setPadding(0, dp(4), 0, 0);
            w.addView(wt);
            content.addView(w);
        }

        // Özet
        LinearLayout s = card();
        s.addView(tv("Özet", 17, text, true));
        int cap = r.learnedCapacity > 0 ? r.learnedCapacity : r.capacity;
        addRow(s, "Pilde geçen süre", r.timeOnBattery);
        addRow(s, "Ekran açık", r.screenOnTime);
        addRow(s, "Ekran kapalı", r.timeOnBatteryScreenOff);
        addRow(s, "Gerçek boşalma (pil sensörü)", mah(r.dischargeMah)
                + (cap > 0 && r.dischargeMah >= 0 ? String.format(Locale.US, "  (%%%.1f)", 100.0 * r.dischargeMah / cap) : ""));
        addRow(s, "  · ekran açıkken", mah(r.screenOnDischargeMah));
        addRow(s, "  · ekran kapalıyken", mah(r.screenOffDischargeMah));
        double apps = 0, appsOff = 0;
        for (StatsParser.AppUsage a : r.apps) { apps += a.total(); appsOff += a.screenOff; }
        addRow(s, "Uygulamalara dağıtılan", mah(apps));
        if (cap > 0) addRow(s, "Öğrenilmiş pil kapasitesi", cap + " mAh");
        content.addView(s);

        // Sıralama
        LinearLayout seg = new LinearLayout(this);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        seg.setPadding(0, dp(12), 0, dp(4));
        sortButtons.clear();
        String[] names = {"Toplam", "Ekran kapalı", "Arka plan"};
        for (int i = 0; i < names.length; i++) {
            final int mode = i;
            Button b = button(names[i], sortMode == i);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { sortMode = mode; render(); }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(40), 1);
            if (i > 0) lp.leftMargin = dp(6);
            seg.addView(b, lp);
            sortButtons.add(b);
        }
        content.addView(seg);

        List<StatsParser.AppUsage> list = new ArrayList<>(r.apps);
        Collections.sort(list, new Comparator<StatsParser.AppUsage>() {
            @Override public int compare(StatsParser.AppUsage a, StatsParser.AppUsage b) {
                return Double.compare(key(b), key(a));
            }
        });
        double max = 0;
        for (StatsParser.AppUsage a : list) max = Math.max(max, key(a));

        int shown = 0;
        for (StatsParser.AppUsage a : list) {
            if (key(a) < 0.01 && shown >= 10) break;
            content.addView(appRow(a, max, apps));
            if (++shown >= 60) break;
        }

        TextView foot = tv("Kaynak: dumpsys batterystats (Android'in kendi enerji modeli). Uygulama başına değerler, "
                + "cihazın güç profiline göre hesaplanmış tahminlerdir. \"Gerçek boşalma\" doğrudan pil sensöründen gelir. "
                + "Bu uygulamanın internet izni yoktur ve arka planda çalışmaz.", 12, sub, false);
        foot.setPadding(0, dp(16), 0, dp(8));
        content.addView(foot);
    }

    private double key(StatsParser.AppUsage a) {
        switch (sortMode) {
            case SORT_SCREEN_OFF: return a.screenOff;
            case SORT_BG: return a.background();
            default: return a.total();
        }
    }

    private View appRow(StatsParser.AppUsage a, double max, double appsTotal) {
        LinearLayout c = card();
        String[] lab = label(a.uid, a.uidLabel);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        TextView n = tv(lab[0], 15, text, true);
        n.setSingleLine(true);
        names.addView(n);
        TextView pk = tv(lab[1], 11, sub, false);
        pk.setSingleLine(true);
        names.addView(pk);
        top.addView(names, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        String pct = appsTotal > 0 ? String.format(Locale.US, "%%%.1f", 100 * a.total() / appsTotal) : "";
        TextView v = tv(mah(key(a)) + "\n" + pct, 14, text, true);
        v.setGravity(Gravity.END);
        top.addView(v);
        c.addView(top);

        // çubuk
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable bbg = new GradientDrawable();
        bbg.setColor(barBg);
        bbg.setCornerRadius(dp(3));
        bar.setBackground(bbg);
        View fill = new View(this);
        GradientDrawable fd = new GradientDrawable();
        boolean suspicious = a.screenOff > 1.0 && a.screenOff > a.screenOn;
        fd.setColor(suspicious ? warn : accent);
        fd.setCornerRadius(dp(3));
        fill.setBackground(fd);
        float w = max > 0 ? (float) (key(a) / max) : 0;
        bar.addView(fill, new LinearLayout.LayoutParams(0, dp(6), Math.max(w, 0.005f)));
        bar.addView(new View(this), new LinearLayout.LayoutParams(0, dp(6), Math.max(1 - w, 0.0001f)));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(6));
        blp.topMargin = dp(8);
        blp.bottomMargin = dp(8);
        c.addView(bar, blp);

        // detay
        StringBuilder d = new StringBuilder();
        d.append("Ekran açık ").append(mah(a.screenOn)).append(" · ekran kapalı ").append(mah(a.screenOff));
        d.append("\nÖn plan ").append(mah(a.fg + a.fgs))
                .append(" · arka plan ").append(mah(a.bg))
                .append(" · önbellekte ").append(mah(a.cached));
        String comps = topComponents(a);
        if (!comps.isEmpty()) d.append("\n").append(comps);
        if (suspicious) d.append("\n⚠ Enerjisinin çoğunu ekran kapalıyken harcamış");
        c.addView(tv(d.toString(), 12, sub, false));
        return c;
    }

    private String topComponents(StatsParser.AppUsage a) {
        List<Map.Entry<String, Double>> e = new ArrayList<>(a.components.entrySet());
        Collections.sort(e, new Comparator<Map.Entry<String, Double>>() {
            @Override public int compare(Map.Entry<String, Double> x, Map.Entry<String, Double> y) {
                return Double.compare(y.getValue(), x.getValue());
            }
        });
        StringBuilder b = new StringBuilder();
        int n = 0;
        for (Map.Entry<String, Double> en : e) {
            if (en.getValue() < 0.005 || n >= 4) break;
            if (b.length() > 0) b.append(" · ");
            b.append(componentName(en.getKey())).append(" ").append(mah(en.getValue()));
            n++;
        }
        return b.toString();
    }

    private static String componentName(String c) {
        switch (c) {
            case "cpu": return "İşlemci";
            case "screen": return "Ekran";
            case "wifi": return "Wi-Fi";
            case "mobile_radio": return "Mobil veri";
            case "gnss": return "GPS";
            case "sensors": return "Sensörler";
            case "audio": return "Ses";
            case "video": return "Video";
            case "camera": return "Kamera";
            case "bluetooth": return "Bluetooth";
            case "wakelock": return "Uyanık tutma";
            case "flashlight": return "Fener";
            case "phone": return "Arama";
            default: return c;
        }
    }

    // ---------------------------------------------------------------- etiketler

    private String[] label(int uid, String uidLabel) {
        String[] cached = labelCache.get(uid);
        if (cached != null) return cached;
        String[] out;
        switch (uid) {
            case 0: out = new String[]{"Çekirdek (root)", "uid 0"}; break;
            case 1000: out = new String[]{"Android Sistemi", "uid 1000 · system_server ve sistem uygulamaları"}; break;
            case 1001: out = new String[]{"Telefon / Hücresel radyo", "uid 1001"}; break;
            case 1002: out = new String[]{"Bluetooth", "uid 1002"}; break;
            case 1010: out = new String[]{"Wi-Fi", "uid 1010"}; break;
            case 1013: out = new String[]{"Medya sunucusu", "uid 1013"}; break;
            case 1041: out = new String[]{"Ses sunucusu", "uid 1041"}; break;
            case 1047: out = new String[]{"Kamera sunucusu", "uid 1047"}; break;
            case 1073: out = new String[]{"Ağ yığını", "uid 1073"}; break;
            default: out = null;
        }
        if (out == null) {
            PackageManager pm = getPackageManager();
            String[] pkgs = null;
            try { pkgs = pm.getPackagesForUid(uid); } catch (Exception ignored) { }
            if (pkgs != null && pkgs.length > 0) {
                String name = pkgs[0];
                try {
                    ApplicationInfo ai = pm.getApplicationInfo(pkgs[0], 0);
                    name = pm.getApplicationLabel(ai).toString();
                } catch (Exception ignored) { }
                String pk = pkgs[0] + (pkgs.length > 1 ? "  +" + (pkgs.length - 1) + " paket" : "");
                out = new String[]{name, pk};
            } else {
                out = new String[]{uidLabel, "uid " + uid};
            }
        }
        labelCache.put(uid, out);
        return out;
    }

    // ---------------------------------------------------------------- yardımcılar

    private static String mah(double v) {
        if (v < 0) return "?";
        if (v < 10) return String.format(Locale.US, "%.2f mAh", v);
        return String.format(Locale.US, "%.0f mAh", v);
    }

    private boolean isNight() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    private void setupColors() {
        if (isNight()) {
            bg = Color.rgb(0x12, 0x13, 0x16); card = Color.rgb(0x1D, 0x1F, 0x24);
            text = Color.rgb(0xEC, 0xEC, 0xEE); sub = Color.rgb(0x9A, 0x9C, 0xA3);
            accent = Color.rgb(0x5B, 0xA8, 0xF5); warn = Color.rgb(0xF2, 0x9B, 0x4B);
            barBg = Color.rgb(0x2C, 0x2F, 0x36); chipBg = Color.rgb(0x2C, 0x2F, 0x36);
        } else {
            bg = Color.rgb(0xF4, 0xF5, 0xF7); card = Color.WHITE;
            text = Color.rgb(0x17, 0x18, 0x1C); sub = Color.rgb(0x5F, 0x63, 0x6B);
            accent = Color.rgb(0x1F, 0x6F, 0xD1); warn = Color.rgb(0xC5, 0x62, 0x0B);
            barBg = Color.rgb(0xE6, 0xE8, 0xEC); chipBg = Color.rgb(0xE6, 0xE8, 0xEC);
        }
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable d = new GradientDrawable();
        d.setColor(card);
        d.setCornerRadius(dp(14));
        c.setBackground(d);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        c.setLayoutParams(lp);
        return c;
    }

    private void addRow(LinearLayout parent, String k, String v) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(5), 0, 0);
        row.addView(tv(k, 14, sub, false), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView tv = tv(v, 14, text, true);
        tv.setGravity(Gravity.END);
        row.addView(tv);
        parent.addView(row);
    }

    private Button button(String label, boolean primary) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setStateListAnimator(null);
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(12));
        d.setColor(primary ? accent : chipBg);
        b.setBackground(d);
        b.setTextColor(primary ? Color.WHITE : text);
        b.setPadding(dp(8), 0, dp(8), 0);
        return b;
    }

    private TextView tv(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
