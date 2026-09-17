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

    /** Packages that must always see real WiFi/cell data (system UI). Location spoof still applies to them. */
    static boolean isInfraPackage(String pkg) {
        if (pkg == null) return false;
        return pkg.equals(Keys.PKG) || pkg.startsWith("com.android.settings") || pkg.startsWith("com.android.systemui")
                || pkg.equals("com.android.phone") || pkg.equals("com.android.shell");
    }

    static int callingUid() {
        return Binder.getCallingUid();
    }
}
