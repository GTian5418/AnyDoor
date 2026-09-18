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
    private final RealLocationRequest realLocation;
    private volatile boolean destroyed;
    private final ArrayDeque<String> logs = new ArrayDeque<>();

    JsBridge(MainActivity a, WebView w) {
        act = a;
        web = w;
        realLocation = new RealLocationRequest((LocationManager) a.getSystemService(Context.LOCATION_SERVICE),
                new android.os.Handler(android.os.Looper.getMainLooper()));
    }

    /** Called on the UI thread before the WebView is destroyed. */
    void destroy() {
        destroyed = true;
        realLocation.close();
        pool.shutdownNow();
    }

    void log(String s) {
        synchronized (logs) {
            logs.addLast(java.text.DateFormat.getTimeInstance().format(new java.util.Date()) + " " + s);
            while (logs.size() > 300) logs.removeFirst();
        }
    }

    private void cb(final int id, final JSONObject json) {
        if (destroyed) return;
        web.post(new Runnable() {
            @Override
            public void run() {
                if (destroyed) return;
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
            o.put("version", BuildInfo.VERSION);
            o.put("protocol", ConfigSnapshot.PROTOCOL);
            o.put("configRevision", Config.revision());
            o.put("mirrorRevision", Config.mirrorRevision());
            o.put("mirrorError", Config.mirrorError());
            o.put("root", RootShell.available());
            RootShell.Result ap = RootShell.run("appops get " + Keys.PKG + " android:mock_location");
            o.put("mockAllowed", ap.out.contains("allow"));
            o.put("overlay", Settings.canDrawOverlays(act));
            long revisionAtProbeStart = Config.revision();
            Bundle probe = probeSystemHook();
            o.put("probeRevisionStart", revisionAtProbeStart);
            o.put("configRevision", Config.revision());
            o.put("systemHook", probe != null);
            if (probe != null) {
                o.put("sysProtocol", probe.getInt("protocol", 0));
                o.put("sysVersion", probe.getString("version", "unknown"));
                o.put("sysRevision", probe.getLong("revision", 0));
                o.put("sysError", probe.getString("error", ""));
                o.put("sysLease", probe.getBoolean("lease", false));
                o.put("liveHook", probe.getBoolean("liveHook", false));
                o.put("deliveries", probe.getLong("deliveries", 0));
                o.put("lastDelivery", probe.getLong("lastDelivery", 0));
                o.put("sysPrefs", probe.getBoolean("prefs", false));
                o.put("sysChannel", probe.getString("channel", ""));
                o.put("sysStarted", probe.getBoolean("started", false));
                o.put("mockGrant", probe.getBoolean("mockGrant", false));
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
            SpoofService service = SpoofService.instance;
            o.put("service", service == null ? new JSONObject().put("running", false) : service.status());
            o.put("mockError", service == null ? Config.app(act).getString(Keys.MOCK_ERROR, "") : service.status().optString("mockError", ""));
        } catch (Exception e) {
            try {
                o.put("error", String.valueOf(e));
            } catch (Exception ignored) {
            }
        }
        return o.toString();
    }

    @JavascriptInterface
    public String diagnosticReport() {
        try {
            JSONObject o = new JSONObject(checkEnv());
            JSONObject full = o.optJSONObject("service"), safe = new JSONObject();
            if (full != null) for (String k : new String[]{"running", "providers", "gpsReady", "networkReady", "providerNote", "mockError", "lastPush"}) safe.put(k, full.opt(k));
            o.put("service", safe);
            o.put("generatedAt", System.currentTimeMillis());
            return o.toString(2);
        } catch (Exception e) { return "诊断导出失败: " + e; }
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
            // Do not replace the user's entire scope list (which may contain WeChat/DingTalk).
            r = RootShell.run("sh /data/adb/lspd/cli modules enable " + Keys.PKG + " 2>&1");
            sb.append(r.ok() ? "✓ 已请求启用模块\n" : "✗ 启用模块失败: " + r.out + " " + r.err + "\n");
            sb.append("· 请在框架管理器确认系统框架、电话、蓝牙作用域；保留已有应用勾选。升级后需要重启手机。\n");
        } else {
            sb.append("· 未检测到 Vector CLI，请在 LSPosed 管理器里手动启用模块并勾选「系统框架」「电话」「蓝牙」\n");
        }
        String syncError = Config.syncNow(act);
        sb.append(syncError.isEmpty() ? "✓ 配置快照已写入，刷新环境检查确认系统是否读到\n" : "✗ " + syncError + "\n");
        return sb.toString().trim();
    }

    @JavascriptInterface
    public void reboot() {
        RootShell.run("svc power reboot || reboot");
    }

    // ------------------------------------------------------------------ spoof control

    @JavascriptInterface
    public void setTarget(double lat, double lng) {
        if (!Double.isFinite(lat) || !Double.isFinite(lng) || Math.abs(lat) > 90 || Math.abs(lng) > 180) return;
        Config.commit(Config.config(act).edit().putString(Keys.LAT, String.valueOf(lat)).putString(Keys.LNG, String.valueOf(lng)));
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
            act.runOnUiThread(() -> { if (!destroyed) realLocation.cancel(); });
            if (Build.VERSION.SDK_INT >= 26) act.startForegroundService(i);
            else act.startService(i);
        } else {
            Config.commit(Config.config(act).edit().putBoolean(Keys.STARTED, false));
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
        else Config.commit(Config.config(act).edit().putString(Keys.STEPS, String.valueOf(Math.floor(Math.max(0, n)))));
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
        Config.commit(e);
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

    /** Independent of the network pool: slow reverse geocoding must never queue real GPS requests. */
    @JavascriptInterface
    public void locate(final int id) {
        act.runOnUiThread(() -> {
            if (destroyed) return;
            if (SpoofService.isRunning()) { cb(id, err("请先停止模拟，再获取真实位置")); return; }
            realLocation.request((location, error) -> {
                if (destroyed) return;
                if (SpoofService.isRunning()) { cb(id, err("请先停止模拟，再获取真实位置")); return; }
                if (location == null) { cb(id, err(error)); return; }
                try {
                    cb(id, new JSONObject().put("ok", true).put("lat", location.getLatitude()).put("lng", location.getLongitude())
                            .put("acc", location.getAccuracy()).put("provider", location.getProvider())
                            .put("age", RealLocationRequest.ageMillis(location)));
                } catch (Exception e) { cb(id, err(String.valueOf(e))); }
            });
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
