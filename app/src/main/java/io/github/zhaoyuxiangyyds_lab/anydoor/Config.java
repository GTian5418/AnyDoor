package io.github.zhaoyuxiangyyds_lab.anydoor;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.util.Iterator;
import java.util.Map;
import java.util.Random;

/** App-side access to the world-readable "config" prefs (read by hooks) and the private "app" prefs. */
public final class Config {
    private static final String TAG = "AnyDoor";
    private static boolean worldReadable = true;

    private Config() {}

    @SuppressWarnings("deprecation")
    public static SharedPreferences config(Context c) {
        try {
            SharedPreferences p = c.getSharedPreferences(Keys.CONFIG, Context.MODE_WORLD_READABLE);
            worldReadable = true;
            return p;
        } catch (SecurityException e) {
            worldReadable = false;
            Log.w(TAG, "MODE_WORLD_READABLE refused – framework inactive?");
            return c.getSharedPreferences(Keys.CONFIG, Context.MODE_PRIVATE);
        }
    }

    public static boolean isWorldReadable() {
        return worldReadable;
    }

    public static SharedPreferences app(Context c) {
        return c.getSharedPreferences(Keys.APP, Context.MODE_PRIVATE);
    }

    public static void ensureDefaults(Context c) {
        SharedPreferences p = config(c);
        SharedPreferences.Editor e = p.edit();
        if (!p.contains(Keys.SEED)) e.putLong(Keys.SEED, new Random().nextLong());
        if (!p.contains(Keys.LAT)) e.putString(Keys.LAT, "39.908722").putString(Keys.LNG, "116.397499");
        if (!p.contains(Keys.ALT)) e.putString(Keys.ALT, "50");
        if (!p.contains(Keys.ACC)) e.putString(Keys.ACC, "8");
        if (!p.contains(Keys.SPEED)) e.putString(Keys.SPEED, "0");
        if (!p.contains(Keys.BEARING)) e.putString(Keys.BEARING, "0");
        if (!p.contains(Keys.JITTER)) e.putString(Keys.JITTER, "3");
        if (!p.contains(Keys.INTERVAL)) e.putInt(Keys.INTERVAL, Keys.DEFAULT_INTERVAL);
        if (!p.contains(Keys.WIFI_BLOCK)) e.putBoolean(Keys.WIFI_BLOCK, true);
        if (!p.contains(Keys.CELL_BLOCK)) e.putBoolean(Keys.CELL_BLOCK, true);
        if (!p.contains(Keys.GNSS_BLOCK)) e.putBoolean(Keys.GNSS_BLOCK, true);
        if (!p.contains(Keys.MOCK_DRIVER)) e.putBoolean(Keys.MOCK_DRIVER, true);
        if (!p.contains(Keys.MOCK_NETWORK)) e.putBoolean(Keys.MOCK_NETWORK, true);
        if (!p.contains(Keys.APP_HOOK)) e.putBoolean(Keys.APP_HOOK, true);
        if (!p.contains(Keys.EXEMPT)) e.putString(Keys.EXEMPT, "");
        if (!p.contains(Keys.STEPS)) e.putString(Keys.STEPS, "0");
        if (!p.contains(Keys.STRIDE)) e.putString(Keys.STRIDE, "0.7");
        if (!p.contains(Keys.STEP_FAKE)) e.putBoolean(Keys.STEP_FAKE, false);
        if (!p.contains(Keys.PRIVACY)) e.putBoolean(Keys.PRIVACY, false);
        if (!p.contains(Keys.ID_SPOOF)) e.putBoolean(Keys.ID_SPOOF, true);
        if (!p.contains(Keys.BT_BLOCK)) e.putBoolean(Keys.BT_BLOCK, true);
        if (!p.contains(Keys.SENSOR_BLOCK)) e.putBoolean(Keys.SENSOR_BLOCK, true);
        e.commit();
        ensureIdentity(c, false);
    }

    /** Generate the fake device identity used by privacy mode (once, or again when regen is true). */
    public static JSONObject ensureIdentity(Context c, boolean regen) {
        SharedPreferences p = config(c);
        if (regen || !p.contains(Keys.FAKE_IMEI)) {
            Random r = new Random();
            String tac = "86" + digits(r, 6);                       // TAC of a mainland-market phone
            String imei14 = tac + digits(r, 6);
            String[] mnc = {"00", "01", "11", "07", "03"};
            String imsi = "460" + mnc[r.nextInt(mnc.length)] + digits(r, 10);
            String iccid19 = "8986" + mnc[r.nextInt(mnc.length)] + digits(r, 13);
            String[] prefix = {"133", "135", "136", "137", "138", "139", "150", "151", "152", "158", "159",
                    "176", "177", "180", "181", "182", "185", "186", "187", "188", "189"};
            p.edit()
                    .putString(Keys.FAKE_IMEI, imei14 + luhn(imei14))
                    .putString(Keys.FAKE_MEID, "A0" + hex(r, 12).toUpperCase())
                    .putString(Keys.FAKE_IMSI, imsi)
                    .putString(Keys.FAKE_ICCID, iccid19 + luhn(iccid19))
                    .putString(Keys.FAKE_ANDROID_ID, hex(r, 16))
                    .putString(Keys.FAKE_SERIAL, alnum(r, 16))
                    .putString(Keys.FAKE_PHONE, prefix[r.nextInt(prefix.length)] + digits(r, 8))
                    .commit();
        }
        return identity(c);
    }

    public static JSONObject identity(Context c) {
        SharedPreferences p = config(c);
        JSONObject o = new JSONObject();
        try {
            for (String k : new String[]{Keys.FAKE_IMEI, Keys.FAKE_MEID, Keys.FAKE_IMSI, Keys.FAKE_ICCID,
                    Keys.FAKE_ANDROID_ID, Keys.FAKE_SERIAL, Keys.FAKE_PHONE}) o.put(k, p.getString(k, ""));
        } catch (Exception ignored) {
        }
        return o;
    }

    private static String digits(Random r, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(r.nextInt(10));
        return sb.toString();
    }

    private static String hex(Random r, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(Character.forDigit(r.nextInt(16), 16));
        return sb.toString();
    }

    private static String alnum(Random r, int n) {
        String cs = "ABCDEFGHJKLMNPQRSTUVWXYZ0123456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(cs.charAt(r.nextInt(cs.length())));
        return sb.toString();
    }

    /** Luhn check digit for a numeric string. */
    private static int luhn(String s) {
        int sum = 0;
        boolean dbl = true;
        for (int i = s.length() - 1; i >= 0; i--) {
            int d = s.charAt(i) - '0';
            if (dbl) {
                d *= 2;
                if (d > 9) d -= 9;
            }
            sum += d;
            dbl = !dbl;
        }
        return (10 - sum % 10) % 10;
    }

    public static double num(SharedPreferences p, String k, double def) {
        String s = p.getString(k, null);
        if (s == null) return def;
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** Apply a JSON object of key → value onto the config prefs, using the right type per key. */
    public static void applyJson(Context c, JSONObject o) {
        SharedPreferences.Editor e = config(c).edit();
        Iterator<String> it = o.keys();
        while (it.hasNext()) {
            String k = it.next();
            Object v = o.opt(k);
            if (v == null || v == JSONObject.NULL) {
                e.remove(k);
            } else if (Keys.INTERVAL.equals(k)) {
                e.putInt(k, o.optInt(k, Keys.DEFAULT_INTERVAL));
            } else if (v instanceof Boolean) {
                e.putBoolean(k, (Boolean) v);
            } else if (v instanceof Number) {
                e.putString(k, String.valueOf(((Number) v).doubleValue()));
            } else {
                e.putString(k, String.valueOf(v));
            }
        }
        e.commit();
    }

    public static JSONObject toJson(SharedPreferences p) {
        JSONObject o = new JSONObject();
        try {
            for (Map.Entry<String, ?> en : p.getAll().entrySet()) o.put(en.getKey(), en.getValue());
        } catch (Exception ignored) {
        }
        return o;
    }
}
