package io.github.zhaoyuxiangyyds_lab.anydoor.xposed;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Binder;
import android.os.Process;

import io.github.zhaoyuxiangyyds_lab.anydoor.Keys;

import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Hooks inside system_server: every app on the device receives the spoofed location, and
 * (optionally) WiFi scan results / cell registrations are hidden from non-system apps so that
 * network-positioning SDKs (Amap/Tencent/Baidu) cannot derive the real position.
 */
final class SystemHooks {
    private SystemHooks() {}

    private static final int LISTEN_CELL_LOCATION = 0x10;
    private static final int LISTEN_CELL_INFO = 0x400;
    private static final int EVENT_CELL_LOCATION_CHANGED = 5;
    private static final int EVENT_CELL_INFO_CHANGED = 11;

    static void install(XC_LoadPackage.LoadPackageParam lp, final SpoofState st) {
        ClassLoader cl = lp.classLoader;

        // ---------- LocationManagerService ----------
        Class<?> lms = XposedHelpers.findClassIfExists("com.android.server.LocationManagerService", cl);
        if (lms == null) lms = XposedHelpers.findClassIfExists("com.android.server.location.LocationManagerService", cl);
        if (lms == null) {
            HookEntry.log("LocationManagerService not found!");
        } else {
            List<Class<?>> targets = new ArrayList<>();
            targets.add(lms);
            for (String n : new String[]{
                    "com.android.server.HwLocationManagerService",
                    "com.android.server.location.HwLocationManagerService"}) {
                Class<?> c = XposedHelpers.findClassIfExists(n, cl);
                if (c != null && !targets.contains(c)) targets.add(c);
            }
            int n = 0;
            for (Class<?> c : targets) n += HookUtil.hookAll(c, "getLastLocation", new LastLocationHook(st));
            HookEntry.log("getLastLocation hooks: " + n + " on " + targets.size() + " classes");

            // Android 8.1 - 11: per-receiver delivery
            Class<?> recv = XposedHelpers.findClassIfExists(lms.getName() + "$Receiver", cl);
            if (recv != null) {
                HookEntry.log("Receiver.callLocationChangedLocked hooks: "
                        + HookUtil.hookAll(recv, "callLocationChangedLocked", new DeliverHook(st)));
            }
            // Android 12+: provider report path
            Class<?> lpm = XposedHelpers.findClassIfExists("com.android.server.location.provider.LocationProviderManager", cl);
            if (lpm != null) {
                HookEntry.log("LocationProviderManager.onReportLocation hooks: "
                        + HookUtil.hookAll(lpm, "onReportLocation", new ReportHook(st)));
            }
            // raw GNSS data would reveal the real position
            XC_MethodHook deny = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (!st.started() || !st.bool(Keys.GNSS_BLOCK, true)) return;
                    if (HookUtil.isSystemUid(Binder.getCallingUid())) return;
                    Class<?> rt = ((java.lang.reflect.Method) p.method).getReturnType();
                    if (rt == boolean.class || rt == Boolean.class) p.setResult(false);
                    else if (rt == void.class) p.setResult(null);
                }
            };
            for (String m : new String[]{"addGnssMeasurementsListener", "addGnssNavigationMessageListener",
                    "addGnssBatchingCallback", "startGnssBatch", "addGnssAntennaInfoListener"}) {
                HookUtil.hookAll(lms, m, deny);
            }
        }

        // ---------- WiFi ----------
        Class<?> wifi = XposedHelpers.findClassIfExists("com.android.server.wifi.WifiServiceImpl", cl);
        if (wifi != null) {
            HookUtil.hookAll(wifi, "getScanResults", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    if (!shouldBlock(st, p.thisObject, p.args, Keys.WIFI_BLOCK)) return;
                    Object res = p.getResult();
                    if (res == null) return;
                    try {
                        if (res instanceof List) p.setResult(new ArrayList<>());
                        else p.setResult(XposedHelpers.newInstance(res.getClass(), new ArrayList<>()));
                    } catch (Throwable t) {
                        HookEntry.log("getScanResults replace failed: " + t);
                    }
                }
            });
            HookUtil.hookAll(wifi, "getConnectionInfo", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    if (!shouldBlock(st, p.thisObject, p.args, Keys.WIFI_BLOCK)) return;
                    Object w = p.getResult();
                    if (w == null) return;
                    try {
                        Object copy = XposedHelpers.newInstance(w.getClass(), w);
                        XposedHelpers.callMethod(copy, "setBSSID", "02:00:00:00:00:00");
                        try {
                            XposedHelpers.callMethod(copy, "setMacAddress", "02:00:00:00:00:00");
                        } catch (Throwable ignored) {
                        }
                        p.setResult(copy);
                    } catch (Throwable t) {
                        HookEntry.log("getConnectionInfo mask failed: " + t);
                    }
                }
            });
        } else {
            HookEntry.log("WifiServiceImpl not in system_server (separate wifi process?)");
        }

        // ---------- TelephonyRegistry: strip cell events from PhoneStateListener registrations ----------
        Class<?> tr = XposedHelpers.findClassIfExists("com.android.server.TelephonyRegistry", cl);
        if (tr != null) {
            XC_MethodHook listen = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (!shouldBlock(st, p.thisObject, p.args, Keys.CELL_BLOCK)) return;
                    int idx = p.args.length - 2;
                    if (idx < 0) return;
                    Object ev = p.args[idx];
                    if (ev instanceof Integer) {
                        p.args[idx] = ((Integer) ev) & ~(LISTEN_CELL_LOCATION | LISTEN_CELL_INFO);
                    } else if (ev instanceof int[]) {
                        int[] in = (int[]) ev;
                        List<Integer> out = new ArrayList<>();
                        for (int e : in) if (e != EVENT_CELL_LOCATION_CHANGED && e != EVENT_CELL_INFO_CHANGED) out.add(e);
                        int[] arr = new int[out.size()];
                        for (int i = 0; i < arr.length; i++) arr[i] = out.get(i);
                        p.args[idx] = arr;
                    }
                }
            };
            for (String m : new String[]{"listen", "listenForSubscriber", "listenWithEventList"}) HookUtil.hookAll(tr, m, listen);
        }
        HookEntry.log("system hooks installed (sdk " + android.os.Build.VERSION.SDK_INT + ")");
    }

    /** Common gate for WiFi/cell blocking: started, feature on, caller is a normal app that is not exempt. */
    static boolean shouldBlock(SpoofState st, Object service, Object[] args, String featureKey) {
        if (!st.started() || !st.bool(featureKey, true)) return false;
        int uid = Binder.getCallingUid();
        if (HookUtil.isSystemUid(uid)) return false;
        String pkg = HookUtil.callerPackage(service, args, uid);
        return !HookUtil.isInfraPackage(pkg) && !st.isExempt(pkg);
    }

    /** getLastLocation(...) → spoofed Location for non-exempt callers. */
    static final class LastLocationHook extends XC_MethodHook {
        private final SpoofState st;

        LastLocationHook(SpoofState st) {
            this.st = st;
        }

        @Override
        protected void afterHookedMethod(MethodHookParam p) {
            String provider = requestedProvider(p.args);
            // liveness probe from our own app, works even when spoofing is off
            if (Keys.PROBE_PROVIDER.equals(provider)) {
                p.setResult(st.build(Keys.PROBE_PROVIDER, null));
                return;
            }
            if (!st.started()) return;
            int uid = Binder.getCallingUid();
            String pkg = HookUtil.callerPackage(p.thisObject, p.args, uid);
            if (st.isExempt(pkg)) return;
            Object res = p.getResult();
            if (res == null && !callerHasLocationPermission(p.thisObject)) return;
            Location orig = res instanceof Location ? (Location) res : null;
            if (provider == null && orig != null) provider = orig.getProvider();
            if (provider == null) provider = "gps";
            if (st.debug()) HookEntry.log("getLastLocation → spoof for " + pkg + " (" + provider + ")");
            p.setResult(st.build(provider, orig));
        }

        private static String requestedProvider(Object[] args) {
            if (args == null) return null;
            if (args.length > 0 && args[0] instanceof String) return (String) args[0];
            for (Object a : args) {
                if (a != null && a.getClass().getName().equals("android.location.LocationRequest")) {
                    try {
                        Object pr = XposedHelpers.callMethod(a, "getProvider");
                        if (pr instanceof String) return (String) pr;
                    } catch (Throwable ignored) {
                    }
                }
            }
            return null;
        }

        private static boolean callerHasLocationPermission(Object service) {
            if (Binder.getCallingPid() == Process.myPid()) return true;
            Context ctx = HookUtil.context(service);
            if (ctx == null) return true;
            return ctx.checkCallingPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    || ctx.checkCallingPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        }
    }

    /** Receiver.callLocationChangedLocked(Location) → replace the delivered Location. */
    static final class DeliverHook extends XC_MethodHook {
        private final SpoofState st;

        DeliverHook(SpoofState st) {
            this.st = st;
        }

        @Override
        protected void beforeHookedMethod(MethodHookParam p) {
            if (!st.started() || p.args.length == 0) return;
            String pkg = receiverPackage(p.thisObject);
            if (st.isExempt(pkg)) return;
            Location o = p.args[0] instanceof Location ? (Location) p.args[0] : null;
            p.args[0] = st.build(o != null ? o.getProvider() : "gps", o);
            if (st.debug()) HookEntry.log("deliver → spoof for " + pkg);
        }

        private static String receiverPackage(Object receiver) {
            try {
                Object id = XposedHelpers.getObjectField(receiver, "mIdentity");
                return (String) XposedHelpers.getObjectField(id, "mPackageName");
            } catch (Throwable ignored) {
            }
            try {
                Object id = XposedHelpers.getObjectField(receiver, "mCallerIdentity");
                try {
                    return (String) XposedHelpers.getObjectField(id, "packageName");
                } catch (Throwable ignored) {
                    return (String) XposedHelpers.getObjectField(id, "mPackageName");
                }
            } catch (Throwable ignored) {
            }
            try {
                return (String) XposedHelpers.getObjectField(receiver, "mPackageName");
            } catch (Throwable ignored) {
            }
            return null;
        }
    }

    /** Android 12+: LocationProviderManager.onReportLocation(LocationResult) → replace the whole result. */
    static final class ReportHook extends XC_MethodHook {
        private final SpoofState st;

        ReportHook(SpoofState st) {
            this.st = st;
        }

        @Override
        protected void beforeHookedMethod(MethodHookParam p) {
            if (!st.started() || p.args.length == 0 || p.args[0] == null) return;
            Object lr = p.args[0];
            try {
                Location first = null;
                try {
                    first = (Location) XposedHelpers.callMethod(lr, "getLastLocation");
                } catch (Throwable ignored) {
                }
                String provider = first != null ? first.getProvider() : null;
                if (provider == null) {
                    try {
                        provider = (String) XposedHelpers.getObjectField(p.thisObject, "mName");
                    } catch (Throwable ignored) {
                    }
                }
                Location fake = st.build(provider == null ? "gps" : provider, first);
                Object replaced;
                try {
                    replaced = XposedHelpers.callStaticMethod(lr.getClass(), "wrap", (Object) new Location[]{fake});
                } catch (Throwable t) {
                    List<Location> l = new ArrayList<>();
                    l.add(fake);
                    replaced = XposedHelpers.callStaticMethod(lr.getClass(), "create", l);
                }
                p.args[0] = replaced;
            } catch (Throwable t) {
                HookEntry.log("onReportLocation replace failed: " + t);
            }
        }
    }
}
