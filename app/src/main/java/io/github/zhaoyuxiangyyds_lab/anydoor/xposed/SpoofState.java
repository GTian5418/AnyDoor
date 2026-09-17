package io.github.zhaoyuxiangyyds_lab.anydoor.xposed;

import android.location.Location;
import android.os.SystemClock;

import io.github.zhaoyuxiangyyds_lab.anydoor.GeoMath;
import io.github.zhaoyuxiangyyds_lab.anydoor.Keys;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;

/** Hook-side view of the world-readable config. Reloads at most every 300 ms. */
final class SpoofState {
    private static final Set<String> ALWAYS_EXEMPT = new HashSet<>(Arrays.asList(
            Keys.PKG, "com.android.location.fused"));

    private final XSharedPreferences prefs;
    private long lastReload;
    private boolean available;

    SpoofState() {
        XSharedPreferences p = null;
        try {
            p = new XSharedPreferences(Keys.PKG, Keys.CONFIG);
            p.makeWorldReadable();
            available = p.getFile() != null && p.getFile().canRead();
        } catch (Throwable t) {
            XposedBridge.log("AnyDoor: cannot open prefs: " + t);
        }
        prefs = p;
    }

    synchronized void refresh() {
        if (prefs == null) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastReload < 300) return;
        lastReload = now;
        try {
            prefs.reload();
            if (!available) available = prefs.getFile() != null && prefs.getFile().canRead();
        } catch (Throwable ignored) {
        }
    }

    boolean started() {
        refresh();
        return prefs != null && prefs.getBoolean(Keys.STARTED, false);
    }

    boolean bool(String k, boolean def) {
        refresh();
        return prefs == null ? def : prefs.getBoolean(k, def);
    }

    double num(String k, double def) {
        refresh();
        if (prefs == null) return def;
        String s = prefs.getString(k, null);
        if (s == null) return def;
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    int interval() {
        refresh();
        int v = prefs == null ? Keys.DEFAULT_INTERVAL : prefs.getInt(Keys.INTERVAL, Keys.DEFAULT_INTERVAL);
        return Math.max(200, v);
    }

    boolean debug() {
        return bool(Keys.DEBUG_LOG, false);
    }

    String str(String k, String def) {
        refresh();
        if (prefs == null) return def;
        String s = prefs.getString(k, def);
        return s == null || s.isEmpty() ? def : s;
    }

    /** Privacy hardening master switch – independent of whether location spoofing is started. */
    boolean privacy() {
        return bool(Keys.PRIVACY, false);
    }

    boolean idSpoof() {
        return privacy() && bool(Keys.ID_SPOOF, true);
    }

    boolean stepFake() {
        return bool(Keys.STEP_FAKE, false);
    }

    /** Which fake identifier a telephony/identity getter should return, by method name; null = leave alone. */
    String fakeIdFor(String method) {
        if (method.startsWith("getDeviceId") || method.startsWith("getImei")) return str(Keys.FAKE_IMEI, null);
        if (method.startsWith("getMeid")) return str(Keys.FAKE_MEID, null);
        if (method.startsWith("getSubscriberId")) return str(Keys.FAKE_IMSI, null);
        if (method.startsWith("getIccSerialNumber") || method.startsWith("getSimSerialNumber")) return str(Keys.FAKE_ICCID, null);
        if (method.startsWith("getLine1Number")) return str(Keys.FAKE_PHONE, null);
        if (method.startsWith("getSerial")) return str(Keys.FAKE_SERIAL, null);
        return null;
    }

    boolean isExempt(String pkg) {
        if (pkg == null) return false;
        if (ALWAYS_EXEMPT.contains(pkg)) return true;
        refresh();
        if (prefs == null) return false;
        String ex = prefs.getString(Keys.EXEMPT, "");
        if (ex == null || ex.isEmpty()) return false;
        for (String s : ex.split(",")) {
            if (pkg.equals(s.trim())) return true;
        }
        return false;
    }

    /** Current spoofed fix (after jitter). */
    Fix current() {
        refresh();
        double lat = num(Keys.LAT, 39.9042), lng = num(Keys.LNG, 116.4074);
        double jitter = num(Keys.JITTER, 0);
        if (jitter > 0) {
            long seed = prefs == null ? 0 : prefs.getLong(Keys.SEED, 0);
            long bucket = System.currentTimeMillis() / interval();
            double[] j = GeoMath.jitter(lat, lng, jitter, seed, bucket);
            lat = j[0];
            lng = j[1];
        }
        Fix f = new Fix();
        f.lat = lat;
        f.lng = lng;
        f.alt = num(Keys.ALT, 50);
        f.acc = (float) num(Keys.ACC, 8);
        f.speed = (float) num(Keys.SPEED, 0);
        f.bearing = (float) num(Keys.BEARING, 0);
        return f;
    }

    /** Build a fresh, non-mock Location. */
    Location build(String provider, Location orig) {
        Fix f = current();
        Location l = new Location(provider == null ? "gps" : provider);
        l.setLatitude(f.lat);
        l.setLongitude(f.lng);
        l.setAltitude(f.alt);
        l.setAccuracy(f.acc);
        l.setSpeed(f.speed);
        l.setBearing(f.bearing);
        l.setTime(System.currentTimeMillis());
        l.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        try {
            l.setVerticalAccuracyMeters(orig != null && orig.hasVerticalAccuracy() ? orig.getVerticalAccuracyMeters() : 3f);
            l.setSpeedAccuracyMetersPerSecond(0.5f);
            l.setBearingAccuracyDegrees(f.speed > 0.3f ? 10f : 90f);
        } catch (Throwable ignored) {
        }
        return l;
    }

    static final class Fix {
        double lat, lng, alt;
        float acc, speed, bearing;
    }
}
