package io.github.zhaoyuxiangyyds_lab.anydoor.xposed;

import android.app.AndroidAppHelper;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.SystemClock;

import io.github.zhaoyuxiangyyds_lab.anydoor.Keys;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

final class HookUtil {
    private HookUtil() {}

    private static final Map<Integer, String[]> UID_CACHE = new HashMap<>();
    private static long uidCacheTime;

    /** Hook every declared method whose name starts with one of the prefixes and returns String. */
    static int hookByPrefix(Class<?> cls, String[] prefixes, XC_MethodHook cb) {
        if (cls == null) return 0;
        int n = 0;
        try {
            for (java.lang.reflect.Method m : cls.getDeclaredMethods()) {
                if (m.getReturnType() != String.class) continue;
                if (java.lang.reflect.Modifier.isAbstract(m.getModifiers())) continue;
                for (String pre : prefixes) {
                    if (m.getName().startsWith(pre)) {
                        XposedBridge.hookMethod(m, cb);
                        n++;
                        break;
                    }
                }
            }
        } catch (Throwable t) {
            HookEntry.log("hookByPrefix " + cls.getName() + " failed: " + t);
        }
        return n;
    }

    static int hookAll(Class<?> cls, String name, XC_MethodHook cb) {
        if (cls == null) return 0;
        try {
            Set<XC_MethodHook.Unhook> s = XposedBridge.hookAllMethods(cls, name, cb);
            if (s.isEmpty()) HookEntry.log("no method " + cls.getSimpleName() + "." + name);
            return s.size();
        } catch (Throwable t) {
            HookEntry.log("hook " + cls.getName() + "." + name + " failed: " + t);
            return 0;
        }
    }

    /** Context of a system service object (field mContext) or the current application. */
    static Context context(Object service) {
        if (service != null) {
            for (String f : new String[]{"mContext", "mApp"}) {
                try {
                    Object c = XposedHelpers.getObjectField(service, f);
                    if (c instanceof Context) return (Context) c;
                } catch (Throwable ignored) {
                }
            }
        }
        try {
            return AndroidAppHelper.currentApplication();
        } catch (Throwable t) {
            return null;
        }
    }

    static synchronized String[] packagesForUid(Context ctx, int uid) {
        long now = SystemClock.uptimeMillis();
        if (now - uidCacheTime > 60000) {
            UID_CACHE.clear();
            uidCacheTime = now;
        }
        String[] p = UID_CACHE.get(uid);
        if (p != null) return p;
        try {
            PackageManager pm = ctx == null ? null : ctx.getPackageManager();
            p = pm == null ? null : pm.getPackagesForUid(uid);
        } catch (Throwable ignored) {
        }
        if (p == null) p = new String[0];
        UID_CACHE.put(uid, p);
        return p;
    }

    /** Best guess of the calling package: a String argument that is one of the caller's packages, else the first package of the uid. */
    static String callerPackage(Object service, Object[] args, int uid) {
        Context ctx = context(service);
        String[] pkgs = packagesForUid(ctx, uid);
        if (args != null) {
            for (Object a : args) {
                if (a instanceof String) {
                    String s = (String) a;
                    for (String p : pkgs) if (p.equals(s)) return s;
                }
            }
        }
        if (pkgs.length > 0) return pkgs[0];
        if (args != null) {
            for (Object a : args) if (a instanceof String && ((String) a).indexOf('.') > 0) return (String) a;
        }
        return null;
    }

    static boolean isSystemUid(int uid) {
        return uid < 10000;
    }

    /**
     * Packages that must always see real WiFi/cell data: the settings / status-bar UI (including the
     * OEM WiFi settings apps that replace it on ColorOS/OxygenOS), otherwise the WiFi list is empty
     * and the user cannot join a network while spoofing. Location spoof still applies to them.
     */
    static boolean isInfraPackage(String pkg) {
        if (pkg == null) return false;
        return pkg.equals(Keys.PKG) || pkg.startsWith("com.android.settings") || pkg.startsWith("com.android.systemui")
                || pkg.equals("com.android.phone") || pkg.equals("com.android.shell")
                || pkg.endsWith(".wirelesssettings") || pkg.equals("com.android.wifi.resources")
                || pkg.equals("com.android.captiveportallogin");
    }

    static int callingUid() {
        return Binder.getCallingUid();
    }

    static final String MASKED_BSSID = "02:00:00:00:00:00";

    /**
     * Android 12+ hands the connected network's {@code WifiInfo} to apps inside
     * {@code NetworkCapabilities.getTransportInfo()} (ConnectivityManager.getNetworkCapabilities and
     * NetworkCallback.onCapabilitiesChanged), which carries the same BSSID that getConnectionInfo
     * does. Returns a copy with the BSSID masked, or null when there is nothing to mask.
     */
    static Object maskWifiTransport(Object nc, boolean hideSsid) {
        if (nc == null) return null;
        try {
            Object ti = XposedHelpers.callMethod(nc, "getTransportInfo");
            if (!(ti instanceof android.net.wifi.WifiInfo)) return null;
            android.net.wifi.WifiInfo w = (android.net.wifi.WifiInfo) ti;
            String bssid = w.getBSSID();
            if (bssid == null || MASKED_BSSID.equals(bssid)) return null;
            Object copy = XposedHelpers.newInstance(w.getClass(), w);
            XposedHelpers.callMethod(copy, "setBSSID", MASKED_BSSID);
            try {
                XposedHelpers.callMethod(copy, "setMacAddress", MASKED_BSSID);
            } catch (Throwable ignored) {
            }
            if (hideSsid) SystemHooks.hideSsid(copy);
            Object out = XposedHelpers.newInstance(nc.getClass(), nc);
            XposedHelpers.callMethod(out, "setTransportInfo", copy);
            return out;
        } catch (Throwable t) {
            HookEntry.log("mask WifiInfo in NetworkCapabilities failed: " + t);
            return null;
        }
    }

    /** ServiceState without cell identity (Android 10+); the original when the copy is unavailable. */
    static Object sanitizeServiceState(Object state) {
        if (state == null) return null;
        try {
            Object copy = XposedHelpers.callMethod(state, "createLocationInfoSanitizedCopy", true);
            return copy == null ? state : copy;
        } catch (Throwable t) {
            return state;
        }
    }

    /** Hand an empty cell list to a TelephonyManager.CellInfoCallback / ICellInfoCallback. */
    static void deliverEmptyCells(Object callback, java.util.concurrent.Executor executor) {
        if (callback == null) return;
        Runnable r = new Runnable() {
            @Override
            public void run() {
                try {
                    XposedHelpers.callMethod(callback, "onCellInfo", new java.util.ArrayList<>());
                } catch (Throwable t) {
                    HookEntry.log("empty cell callback failed: " + t);
                }
            }
        };
        if (executor != null) {
            try {
                executor.execute(r);
                return;
            } catch (Throwable ignored) {
            }
        }
        r.run();
    }
}
