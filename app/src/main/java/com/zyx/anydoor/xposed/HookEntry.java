package com.zyx.anydoor.xposed;

import com.zyx.anydoor.Keys;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class HookEntry implements IXposedHookLoadPackage {
    static final String TAG = "AnyDoor";
    private static SpoofState state;

    static synchronized SpoofState state() {
        if (state == null) state = new SpoofState();
        return state;
    }

    static void log(String s) {
        XposedBridge.log(TAG + ": " + s);
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        String pkg = lp.packageName;
        try {
            if (Keys.PKG.equals(pkg)) {
                // Let the app know the module is active.
                XposedHelpers.findAndHookMethod("com.zyx.anydoor.ModuleStatus", lp.classLoader,
                        "isModuleActive", XC_MethodReplacement.returnConstant(true));
                return;
            }
            // system_server is delivered as "android" on stock LSPosed and as "system" on some
            // forks (e.g. JingMatrix Vector). Both mean the framework process.
            if ("android".equals(pkg) || "system".equals(pkg)) {
                SystemHooks.install(lp, state());
                return;
            }
            if ("com.android.phone".equals(pkg)) {
                PhoneHooks.install(lp, state());
                return;
            }
            AppHooks.install(lp, state());
        } catch (Throwable t) {
            log("install failed for " + pkg + ": " + t);
        }
    }
}
