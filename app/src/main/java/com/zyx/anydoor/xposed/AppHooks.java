package com.zyx.anydoor.xposed;

import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;

import com.zyx.anydoor.Keys;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * In-process hooks for apps that are explicitly added to the module scope ("strong mode").
 * The system_server hooks already cover every app; these add a second layer for apps that
 * cache/verify Location objects, check isFromMockProvider, or read WiFi/cell data directly.
 */
final class AppHooks {
    private AppHooks() {}

    private static final WeakHashMap<Object, Runnable> PUMPS = new WeakHashMap<>();

    static void install(final XC_LoadPackage.LoadPackageParam lp, final SpoofState st) {
        final String pkg = lp.packageName;
        if (st.isExempt(pkg)) return;

        // ---- android.location.Location getters ----
        Class<?> loc = Location.class;
        hookGetter(loc, "getLatitude", st, pkg, 0);
        hookGetter(loc, "getLongitude", st, pkg, 1);
        hookGetter(loc, "getAltitude", st, pkg, 2);
        hookGetter(loc, "getAccuracy", st, pkg, 3);
        hookGetter(loc, "getSpeed", st, pkg, 4);
        hookGetter(loc, "getBearing", st, pkg, 5);
        XC_MethodHook notMock = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                if (st.started()) p.setResult(false);
            }
        };
        HookUtil.hookAll(loc, "isFromMockProvider", notMock);
        if (android.os.Build.VERSION.SDK_INT >= 31) HookUtil.hookAll(loc, "isMock", notMock);
        HookUtil.hookAll(loc, "hasAltitude", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                if (st.started()) p.setResult(true);
            }
        });

        // ---- LocationManager ----
        Class<?> lm = LocationManager.class;
        HookUtil.hookAll(lm, "getLastKnownLocation", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                if (!st.started()) return;
                String provider = p.args.length > 0 && p.args[0] instanceof String ? (String) p.args[0] : "gps";
                Object res = p.getResult();
                p.setResult(st.build(provider, res instanceof Location ? (Location) res : null));
            }
        });
        XC_MethodHook pump = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                if (!st.started()) return;
                LocationListener listener = null;
                Looper looper = null;
                String provider = null;
                for (Object a : p.args) {
                    if (a instanceof LocationListener) listener = (LocationListener) a;
                    else if (a instanceof Looper) looper = (Looper) a;
                    else if (a instanceof String && provider == null) provider = (String) a;
                }
                if (listener == null) return;
                startPump(st, listener, looper, provider == null ? "gps" : provider);
            }
        };
        HookUtil.hookAll(lm, "requestLocationUpdates", pump);
        HookUtil.hookAll(lm, "requestSingleUpdate", pump);
        HookUtil.hookAll(lm, "removeUpdates", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                if (p.args.length > 0 && p.args[0] instanceof LocationListener) stopPump(p.args[0]);
            }
        });
        HookUtil.hookAll(lm, "isProviderEnabled", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                if (!st.started()) return;
                if (p.args.length > 0 && ("gps".equals(p.args[0]) || "network".equals(p.args[0]))) p.setResult(true);
            }
        });
        HookUtil.hookAll(lm, "addNmeaListener", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                if (st.started() && st.bool(Keys.GNSS_BLOCK, true)) p.setResult(false);
            }
        });

        // ---- WiFi / cell (in-process) ----
        XC_MethodHook emptyList = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                if (st.started() && st.bool(Keys.WIFI_BLOCK, true)) p.setResult(new ArrayList<>());
            }
        };
        HookUtil.hookAll(android.net.wifi.WifiManager.class, "getScanResults", emptyList);
        HookUtil.hookAll(android.net.wifi.WifiInfo.class, "getBSSID", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                if (st.started() && st.bool(Keys.WIFI_BLOCK, true) && p.getResult() != null) p.setResult("02:00:00:00:00:00");
            }
        });
        XC_MethodHook cell = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                if (!st.started() || !st.bool(Keys.CELL_BLOCK, true)) return;
                Class<?> rt = ((Method) p.method).getReturnType();
                if (List.class.isAssignableFrom(rt)) p.setResult(new ArrayList<>());
                else p.setResult(null);
            }
        };
        Class<?> tm = android.telephony.TelephonyManager.class;
        HookUtil.hookAll(tm, "getAllCellInfo", cell);
        HookUtil.hookAll(tm, "getCellLocation", cell);
        HookUtil.hookAll(tm, "getNeighboringCellInfo", cell);
        HookUtil.hookAll(tm, "listen", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                if (!st.started() || !st.bool(Keys.CELL_BLOCK, true)) return;
                for (int i = 0; i < p.args.length; i++) {
                    if (p.args[i] instanceof Integer) p.args[i] = ((Integer) p.args[i]) & ~(0x10 | 0x400);
                }
            }
        });
        HookEntry.log("app hooks installed in " + pkg);
    }

    private static void hookGetter(Class<?> cls, String name, final SpoofState st, final String pkg, final int what) {
        HookUtil.hookAll(cls, name, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                if (!st.started()) return;
                SpoofState.Fix f = st.current();
                switch (what) {
                    case 0: p.setResult(f.lat); break;
                    case 1: p.setResult(f.lng); break;
                    case 2: p.setResult(f.alt); break;
                    case 3: p.setResult(f.acc); break;
                    case 4: p.setResult(f.speed); break;
                    default: p.setResult(f.bearing); break;
                }
            }
        });
    }

    /** Periodically feed a listener with fresh fixes so apps get updates even without a real GPS fix. */
    private static synchronized void startPump(final SpoofState st, final LocationListener l, Looper looper, final String provider) {
        stopPump(l);
        final Handler h = new Handler(looper != null ? looper : Looper.getMainLooper());
        Runnable r = new Runnable() {
            @Override
            public void run() {
                synchronized (AppHooks.class) {
                    if (PUMPS.get(l) != this) return;
                }
                if (st.started()) {
                    try {
                        l.onLocationChanged(st.build(provider, null));
                    } catch (Throwable t) {
                        HookEntry.log("pump: " + t);
                    }
                }
                h.postDelayed(this, st.interval());
            }
        };
        PUMPS.put(l, r);
        h.postDelayed(r, 300);
    }

    private static synchronized void stopPump(Object l) {
        PUMPS.remove(l);
    }
}
