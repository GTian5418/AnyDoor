package com.zyx.anydoor;

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
        e.commit();
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
