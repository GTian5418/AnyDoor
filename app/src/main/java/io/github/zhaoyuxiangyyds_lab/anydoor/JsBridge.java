package io.github.zhaoyuxiangyyds_lab.anydoor;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** JavaScript ↔ Java bridge exposed as window.Native. Async results arrive via window.__cb(id, json). */
public class JsBridge {
    private final MainActivity act;
    private final WebView web;
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final ArrayDeque<String> logs = new ArrayDeque<>();

    JsBridge(MainActivity a, WebView w) {
        act = a;
        web = w;
    }

    void log(String s) {
        synchronized (logs) {
            logs.addLast(java.text.DateFormat.getTimeInstance().format(new java.util.Date()) + " " + s);
            while (logs.size() > 300) logs.removeFirst();
        }
    }

    private void cb(final int id, final JSONObject json) {
        web.post(new Runnable() {
            @Override
            public void run() {
                web.evaluateJavascript("window.__cb && window.__cb(" + id + "," + json.toString() + ")", null);
            }
        });
    }

    private JSONObject err(String msg) {
        try {
            return new JSONObject().put("ok", false).put("error", msg);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    // ------------------------------------------------------------------ state

    @JavascriptInterface
    public String getState() {
        JSONObject o = new JSONObject();
        try {
            SharedPreferences c = Config.config(act);
            o.put("config", Config.toJson(c));
            o.put("app", Config.toJson(Config.app(act)));
            SpoofService s = SpoofService.instance;
            o.put("service", s != null ? s.status() : new JSONObject().put("running", false));
            o.put("started", c.getBoolean(Keys.STARTED, false));
            o.put("night", act.isNight());
            o.put("version", BuildInfo.VERSION);
        } catch (Exception ignored) {
        }
        return o.toString();
    }

    @JavascriptInterface
    public String checkEnv() {
        JSONObject o = new JSONObject();
        try {
            o.put("moduleActive", ModuleStatus.isModuleActive());
            Config.config(act);
            o.put("prefsWorldReadable", Config.isWorldReadable());
            o.put("root", RootShell.available());
            RootShell.Result ap = RootShell.run("appops get " + Keys.PKG + " android:mock_location");
            o.put("mockAllowed", ap.out.contains("allow"));
            o.put("overlay", Settings.canDrawOverlays(act));
            Bundle probe = probeSystemHook();
            o.put("systemHook", probe != null);
            if (probe != null) {
                o.put("sysPrefs", probe.getBoolean("prefs", false));
                o.put("sysStarted", probe.getBoolean("started", false));
                o.put("sysSdk", probe.getInt("sdk", 0));
                o.put("sysHooks", probe.getString("hooks", ""));
            }
            o.put("started", Config.config(act).getBoolean(Keys.STARTED, false));
            RootShell.Result cli = RootShell.run("ls /data/adb/lspd/cli >/dev/null 2>&1 && echo yes");
            o.put("vectorCli", cli.out.contains("yes"));
            if (cli.out.contains("yes")) {
                RootShell.Result sc = RootShell.run("sh /data/adb/lspd/cli scope ls " + Keys.PKG + " 2>&1");
                o.put("scope", sc.out);
                RootShell.Result mods = RootShell.run("sh /data/adb/lspd/cli modules ls 2>&1 | grep " + Keys.PKG);
                o.put("moduleEnabled", mods.out.contains("enabled"));
            }
            o.put("sdk", Build.VERSION.SDK_INT);
            o.put("device", Build.MANUFACTURER + " " + Build.MODEL + " / Android " + Build.VERSION.RELEASE);
            o.put("mockError", Config.app(act).getString(Keys.MOCK_ERROR, ""));
        } catch (Exception e) {
            try {
                o.put("error", String.valueOf(e));
            } catch (Exception ignored) {
            }
        }
        return o.toString();
    }

    /**
     * Ask the system for a provider that only exists inside our system_server hook. Returns the
     * diagnostics bundle the hook attaches (never null when the hook answered), or null.
     */
    private Bundle probeSystemHook() {
        try {
            LocationManager lm = (LocationManager) act.getSystemService(Context.LOCATION_SERVICE);
            Location l = lm.getLastKnownLocation(Keys.PROBE_PROVIDER);
            if (l == null || !Keys.PROBE_PROVIDER.equals(l.getProvider())) return null;
            return l.getExtras() != null ? l.getExtras() : new Bundle();
        } catch (Throwable t) {
            log("probe: " + t);
            return null;
        }
    }

    @JavascriptInterface
    public String rootSetup() {
        StringBuilder sb = new StringBuilder();
        RootShell.Result r = RootShell.run("appops set " + Keys.PKG + " android:mock_location allow && echo mock_ok");
        sb.append(r.out.contains("mock_ok") ? "✓ 已授予模拟位置权限\n" : "✗ 模拟位置权限失败: " + r.err + "\n");
        r = RootShell.run("appops set " + Keys.PKG + " SYSTEM_ALERT_WINDOW allow && echo ov_ok");
        sb.append(r.out.contains("ov_ok") ? "✓ 已授予悬浮窗权限\n" : "✗ 悬浮窗权限失败: " + r.err + "\n");
        r = RootShell.run("ls /data/adb/lspd/cli >/dev/null 2>&1 && echo yes");
        if (r.out.contains("yes")) {
            r = RootShell.run("sh /data/adb/lspd/cli modules enable " + Keys.PKG + " 2>&1; "
                    + "sh /data/adb/lspd/cli scope set " + Keys.PKG + " android/0 com.android.phone/0 com.android.bluetooth/0 " + Keys.PKG + "/0 2>&1");
            sb.append("Vector: ").append(r.out.isEmpty() ? r.err : r.out).append("\n");
            sb.append("✓ 已启用模块并设置作用域 (system / phone / bluetooth)，重启手机后生效\n");
        } else {
            sb.append("· 未检测到 Vector CLI，请在 LSPosed 管理器里手动启用模块并勾选「系统框架」「电话」「蓝牙」\n");
        }
        return sb.toString().trim();
    }

    @JavascriptInterface
    public void reboot() {
        RootShell.run("svc power reboot || reboot");
    }

    // ------------------------------------------------------------------ spoof control

    @JavascriptInterface
    public void setTarget(double lat, double lng) {
        Config.config(act).edit().putString(Keys.LAT, String.valueOf(lat)).putString(Keys.LNG, String.valueOf(lng)).commit();
        SpoofService s = SpoofService.instance;
        if (s != null && SpoofService.isRunning()) {
            s.setBase(lat, lng);
            s.refreshNotification();
        }
    }

    @JavascriptInterface
    public void setStarted(boolean on) {
        Intent i = new Intent(act, SpoofService.class).setAction(on ? SpoofService.ACTION_START : SpoofService.ACTION_STOP);
        if (on) {
            if (Build.VERSION.SDK_INT >= 26) act.startForegroundService(i);
            else act.startService(i);
        } else {
            Config.config(act).edit().putBoolean(Keys.STARTED, false).commit();
            act.startService(i);
        }
    }

    @JavascriptInterface
    public void setConfig(String json) {
        try {
            Config.applyJson(act, new JSONObject(json));
        } catch (Exception e) {
            log("setConfig: " + e);
        }
    }

    @JavascriptInterface
    public String getApp(String key) {
        return Config.app(act).getString(key, null);
    }

    @JavascriptInterface
    public void setApp(String key, String value) {
        if (value == null) Config.app(act).edit().remove(key).apply();
        else Config.app(act).edit().putString(key, value).apply();
        if (Keys.THEME.equals(key)) act.onThemeChanged();
    }

    @JavascriptInterface
    public boolean startRoute(String json) {
        SpoofService s = SpoofService.instance;
        if (s == null) {
            Intent i = new Intent(act, SpoofService.class).setAction(SpoofService.ACTION_START);
            if (Build.VERSION.SDK_INT >= 26) act.startForegroundService(i);
            else act.startService(i);
            final String j = json;
            pool.submit(new Runnable() {
                @Override
                public void run() {
                    for (int i = 0; i < 20 && SpoofService.instance == null; i++) {
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException ignored) {
                        }
                    }
                    SpoofService s2 = SpoofService.instance;
                    if (s2 != null) s2.startRoute(j);
                }
            });
            return true;
        }
        return s.startRoute(json);
    }

    @JavascriptInterface
    public void stopRoute() {
        SpoofService s = SpoofService.instance;
        if (s != null) s.stopRoute();
    }

    /** Road-following route through WGS-84 points. json: {"mode":"walking|running|bicycling|driving","points":[{lat,lng}]} */
    @JavascriptInterface
    public void planRoute(final int id, final String json) {
        pool.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject o = new JSONObject(json);
                    cb(id, Geocoder.route(o.optString("mode", "walking"), o.getJSONArray("points"),
                            Config.app(act).getString(Keys.AMAP_KEY, "")));
                } catch (Exception e) {
                    cb(id, err(String.valueOf(e)));
                }
            }
        });
    }

    // ------------------------------------------------------------------ pedometer

    private void withService(final Runnable r) {
        SpoofService s = SpoofService.instance;
        if (s != null) {
            r.run();
            return;
        }
        Intent i = new Intent(act, SpoofService.class).setAction(SpoofService.ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) act.startForegroundService(i);
        else act.startService(i);
        pool.submit(new Runnable() {
            @Override
            public void run() {
                for (int n = 0; n < 20 && SpoofService.instance == null; n++) {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ignored) {
                    }
                }
                if (SpoofService.instance != null) r.run();
            }
        });
    }

    @JavascriptInterface
    public void stepBurst(final double n, final double perMinute) {
        withService(new Runnable() {
            @Override
            public void run() {
                SpoofService s = SpoofService.instance;
                if (s != null) s.stepBurst(n, perMinute);
            }
        });
    }

    @JavascriptInterface
    public void stopSteps() {
        SpoofService s = SpoofService.instance;
        if (s != null) s.stopSteps();
    }

    @JavascriptInterface
    public void setSteps(double n) {
        SpoofService s = SpoofService.instance;
        if (s != null) s.setSteps(n);
        else Config.config(act).edit().putString(Keys.STEPS, String.valueOf(Math.floor(Math.max(0, n)))).commit();
    }

    // ------------------------------------------------------------------ privacy

    @JavascriptInterface
    public String getIdentity() {
        return Config.ensureIdentity(act, false).toString();
    }

    @JavascriptInterface
    public String regenIdentity() {
        return Config.ensureIdentity(act, true).toString();
    }

    /** One-click privacy preset: turn every shield on (or just the master switch off). */
    @JavascriptInterface
    public void privacyPreset(boolean on) {
        SharedPreferences.Editor e = Config.config(act).edit().putBoolean(Keys.PRIVACY, on);
        if (on) {
            Config.ensureIdentity(act, false);
            e.putBoolean(Keys.WIFI_BLOCK, true).putBoolean(Keys.CELL_BLOCK, true).putBoolean(Keys.GNSS_BLOCK, true)
                    .putBoolean(Keys.ID_SPOOF, true).putBoolean(Keys.BT_BLOCK, true).putBoolean(Keys.SENSOR_BLOCK, true);
            if (Config.num(Config.config(act), Keys.JITTER, 0) < 2) e.putString(Keys.JITTER, "3");
        }
        e.commit();
    }

    @JavascriptInterface
    public void toggleJoystick() {
        Intent i = new Intent(act, SpoofService.class).setAction(SpoofService.ACTION_JOY);
        if (Build.VERSION.SDK_INT >= 26) act.startForegroundService(i);
        else act.startService(i);
    }

    // ------------------------------------------------------------------ geocoding (async)

    @JavascriptInterface
    public void search(final int id, final String query, final double nearLat, final double nearLng) {
        pool.submit(new Runnable() {
            @Override
            public void run() {
                SharedPreferences a = Config.app(act);
                JSONObject r = Geocoder.search(query, a.getString(Keys.SEARCH_SRC, "auto"),
                        a.getString(Keys.AMAP_KEY, ""), a.getString(Keys.NOMINATIM, ""), nearLat, nearLng);
                cb(id, r);
            }
        });
    }

    @JavascriptInterface
    public void reverse(final int id, final double lat, final double lng) {
        pool.submit(new Runnable() {
            @Override
            public void run() {
                SharedPreferences a = Config.app(act);
                cb(id, Geocoder.reverse(lat, lng, a.getString(Keys.SEARCH_SRC, "auto"),
                        a.getString(Keys.AMAP_KEY, ""), a.getString(Keys.NOMINATIM, "")));
            }
        });
    }

    /** Real device location (only meaningful while spoofing is off; our package is exempt from hooks). */
    @JavascriptInterface
    public void locate(final int id) {
        pool.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    LocationManager lm = (LocationManager) act.getSystemService(Context.LOCATION_SERVICE);
                    Location best = null;
                    for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER}) {
                        try {
                            Location l = lm.getLastKnownLocation(p);
                            if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
                        } catch (Throwable ignored) {
                        }
                    }
                    if (best == null) {
                        cb(id, err("暂无定位，请到室外或打开 WiFi 后再试"));
                        return;
                    }
                    cb(id, new JSONObject().put("ok", true).put("lat", best.getLatitude()).put("lng", best.getLongitude())
                            .put("acc", best.getAccuracy()).put("provider", best.getProvider()).put("age", System.currentTimeMillis() - best.getTime()));
                } catch (Throwable t) {
                    cb(id, err(String.valueOf(t)));
                }
            }
        });
    }

    // ------------------------------------------------------------------ misc

    @JavascriptInterface
    public void toast(final String msg) {
        act.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(act, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    @JavascriptInterface
    public void copy(String text) {
        ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("AnyDoor", text));
    }

    @JavascriptInterface
    public String paste() {
        try {
            ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData d = cm.getPrimaryClip();
            if (d == null || d.getItemCount() == 0) return "";
            CharSequence t = d.getItemAt(0).coerceToText(act);
            return t == null ? "" : t.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    @JavascriptInterface
    public void openUrl(String url) {
        try {
            act.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Throwable ignored) {
        }
    }

    @JavascriptInterface
    public void share(String text) {
        try {
            Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text);
            act.startActivity(Intent.createChooser(i, "分享"));
        } catch (Throwable ignored) {
        }
    }

    @JavascriptInterface
    public void vibrate(int ms) {
        try {
            Vibrator v = (Vibrator) act.getSystemService(Context.VIBRATOR_SERVICE);
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
            else v.vibrate(ms);
        } catch (Throwable ignored) {
        }
    }

    @JavascriptInterface
    public String getLogs() {
        JSONArray a = new JSONArray();
        synchronized (logs) {
            for (String s : logs) a.put(s);
        }
        return a.toString();
    }

    @JavascriptInterface
    public String frameworkLog() {
        RootShell.Result r = RootShell.run("f=$(ls -t /data/adb/lspd/log/modules_*.log 2>/dev/null | head -1); "
                + "[ -n \"$f\" ] && grep -a AnyDoor \"$f\" | tail -60");
        return r.out.isEmpty() ? r.err : r.out;
    }
}
