package tr.yerel.pilanaliz;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "dumpsys batterystats --charged" çıktısını ayrıştırır.
 * Sadece pil üzerindeyken (şarjda değilken) harcanan enerjiyi hesaplar.
 */
final class StatsParser {

    static final class AppUsage {
        String uidLabel;      // "u0a510", "1000" ...
        int uid;
        double screenOn;      // pil üzerinde, ekran açık (mAh)
        double screenOff;     // pil üzerinde, ekran kapalı/doze (mAh)
        double fg, fgs, bg, cached; // pil üzerinde, süreç durumuna göre (mAh)
        Map<String, Double> components = new HashMap<>(); // cpu, wifi, gnss... (pil üzerinde)
        double total() { return screenOn + screenOff; }
        double background() { return bg + cached; }
    }

    static final class Result {
        boolean onBattery;
        String startClock = "?";
        String timeOnBattery = "?";
        String timeOnBatteryScreenOff = "?";
        String screenOnTime = "?";
        double dischargeMah = -1, screenOnDischargeMah = -1, screenOffDischargeMah = -1;
        double computedDrain = -1;
        int capacity = -1, learnedCapacity = -1;
        List<AppUsage> apps = new ArrayList<>();
    }

    private static final Pattern UID_LINE =
            Pattern.compile("^\\s+UID (\\S+): [0-9.eE+-]+");
    private static final Pattern STATE_LINE =
            Pattern.compile("^\\s+\\((on|not on) battery, (screen on|screen off/doze)\\)(.*)$");
    private static final Pattern COMPONENT =
            Pattern.compile("([A-Za-z_]+)(?::([a-z]+))?=([0-9.]+(?:[eE][+-]?[0-9]+)?)");
    private static final Pattern MAH = Pattern.compile("([0-9.]+) mAh");

    static Result parse(List<String> lines) {
        Result r = new Result();
        boolean inPower = false;
        AppUsage cur = null;

        for (String line : lines) {
            String t = line.trim();
            if (!inPower) {
                if (t.startsWith("System starts:")) {
                    r.onBattery = t.contains("currently on battery: true");
                } else if (t.startsWith("Start clock time:")) {
                    r.startClock = t.substring("Start clock time:".length()).trim();
                } else if (t.startsWith("Time on battery screen off:")) {
                    r.timeOnBatteryScreenOff = firstDuration(t.substring("Time on battery screen off:".length()));
                } else if (t.startsWith("Time on battery:")) {
                    r.timeOnBattery = firstDuration(t.substring("Time on battery:".length()));
                } else if (t.startsWith("Screen on:") && "?".equals(r.screenOnTime)) {
                    r.screenOnTime = firstDuration(t.substring("Screen on:".length()));
                } else if (t.startsWith("Discharge:")) {
                    r.dischargeMah = mah(t);
                } else if (t.startsWith("Screen on discharge:")) {
                    r.screenOnDischargeMah = mah(t);
                } else if (t.startsWith("Screen off discharge:")) {
                    r.screenOffDischargeMah = mah(t);
                } else if (t.startsWith("Last learned battery capacity:")) {
                    r.learnedCapacity = (int) mah(t);
                } else if (t.startsWith("Estimated power use (mAh):")) {
                    inPower = true;
                }
                continue;
            }

            // "Estimated power use" bölümü
            if (t.startsWith("Capacity:")) {
                Matcher m = Pattern.compile("Capacity: ([0-9]+).*Computed drain: ([0-9.]+)").matcher(t);
                if (m.find()) {
                    r.capacity = Integer.parseInt(m.group(1));
                    r.computedDrain = Double.parseDouble(m.group(2));
                }
                continue;
            }
            Matcher um = UID_LINE.matcher(line);
            if (um.find()) {
                cur = new AppUsage();
                cur.uidLabel = um.group(1);
                cur.uid = toUid(cur.uidLabel);
                r.apps.add(cur);
                continue;
            }
            if (cur == null) continue;

            Matcher sm = STATE_LINE.matcher(line);
            if (sm.find()) {
                if (!"on".equals(sm.group(1))) continue; // şarjdayken harcanan sayılmaz
                boolean screenOn = "screen on".equals(sm.group(2));
                Matcher cm = COMPONENT.matcher(sm.group(3));
                while (cm.find()) {
                    String comp = cm.group(1);
                    String state = cm.group(2);
                    double v = Double.parseDouble(cm.group(3));
                    if (state == null) {
                        if (screenOn) cur.screenOn += v; else cur.screenOff += v;
                        Double prev = cur.components.get(comp);
                        cur.components.put(comp, (prev == null ? 0 : prev) + v);
                    } else {
                        switch (state) {
                            case "fg": cur.fg += v; break;
                            case "fgs": cur.fgs += v; break;
                            case "bg": cur.bg += v; break;
                            case "cached": cur.cached += v; break;
                            default: break;
                        }
                    }
                }
            } else if (!t.startsWith("(") && !t.startsWith("cpu") && !t.isEmpty()
                    && !Character.isLowerCase(t.charAt(0))) {
                // UID bloğu bitti (yeni başlık)
                cur = null;
            }
        }

        // Pil üzerinde hiç harcamamış olanları at, büyükten küçüğe sırala
        List<AppUsage> filtered = new ArrayList<>();
        for (AppUsage a : r.apps) if (a.total() > 0.0005) filtered.add(a);
        Collections.sort(filtered, new Comparator<AppUsage>() {
            @Override public int compare(AppUsage a, AppUsage b) {
                return Double.compare(b.total(), a.total());
            }
        });
        r.apps = filtered;
        return r;
    }

    private static double mah(String t) {
        Matcher m = MAH.matcher(t);
        return m.find() ? Double.parseDouble(m.group(1)) : -1;
    }

    /** "6m 56s 365ms (26,0%) realtime, ..." -> "6dk 56sn" */
    private static String firstDuration(String s) {
        String d = s.split("\\(")[0].split(",")[0].trim();
        return prettyDuration(d);
    }

    static String prettyDuration(String d) {
        long ms = 0;
        Matcher m = Pattern.compile("([0-9]+)(d|h|ms|m|s)").matcher(d);
        while (m.find()) {
            long v = Long.parseLong(m.group(1));
            switch (m.group(2)) {
                case "d": ms += v * 86400000L; break;
                case "h": ms += v * 3600000L; break;
                case "m": ms += v * 60000L; break;
                case "s": ms += v * 1000L; break;
                default: ms += v;
            }
        }
        long s = ms / 1000, dd = s / 86400, h = (s % 86400) / 3600, mi = (s % 3600) / 60, se = s % 60;
        StringBuilder b = new StringBuilder();
        if (dd > 0) b.append(dd).append("g ");
        if (h > 0) b.append(h).append("sa ");
        if (mi > 0) b.append(mi).append("dk ");
        if (dd == 0 && h == 0) b.append(se).append("sn");
        return b.toString().trim();
    }

    /** "u0a510" -> 10510, "1000" -> 1000 */
    static int toUid(String label) {
        Matcher m = Pattern.compile("^u(\\d+)a(\\d+)$").matcher(label);
        if (m.find()) return Integer.parseInt(m.group(1)) * 100000 + 10000 + Integer.parseInt(m.group(2));
        m = Pattern.compile("^u(\\d+)i(\\d+)$").matcher(label);
        if (m.find()) return Integer.parseInt(m.group(1)) * 100000 + 99000 + Integer.parseInt(m.group(2));
        try { return Integer.parseInt(label); } catch (NumberFormatException e) { return -1; }
    }
}
