package io.github.zhaoyuxiangyyds_lab.anydoor.xposed;

import android.os.Bundle;

import io.github.zhaoyuxiangyyds_lab.anydoor.Keys;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Hooks in the com.android.phone process (ITelephony): hide cell identities from normal apps. */
final class PhoneHooks {
    private PhoneHooks() {}

    static void install(XC_LoadPackage.LoadPackageParam lp, final SpoofState st) {
        ClassLoader cl = lp.classLoader;
        List<Class<?>> targets = new ArrayList<>();
        for (String n : new String[]{"com.android.phone.PhoneInterfaceManager", "com.android.phone.HwPhoneInterfaceManager"}) {
            Class<?> c = XposedHelpers.findClassIfExists(n, cl);
            if (c != null) targets.add(c);
        }
        if (targets.isEmpty()) {
            HookEntry.log("PhoneInterfaceManager not found");
            return;
        }
        XC_MethodHook hide = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                if (!SystemHooks.shouldBlock(st, p.thisObject, p.args, Keys.CELL_BLOCK)) return;
                Class<?> rt = ((Method) p.method).getReturnType();
                if (rt == Bundle.class) p.setResult(new Bundle());
                else if (List.class.isAssignableFrom(rt)) p.setResult(new ArrayList<>());
                else if (rt == void.class) p.setResult(null);
                else p.setResult(null);
            }
        };
        int n = 0;
        for (Class<?> c : targets) {
            for (String m : new String[]{"getAllCellInfo", "getCellLocation", "getNeighboringCellInfo"}) {
                n += HookUtil.hookAll(c, m, hide);
            }
        }
        HookEntry.log("phone hooks installed: " + n);

        // ---- privacy mode: IMEI / MEID / IMSI / ICCID / phone number for normal apps ----
        XC_MethodHook fakeId = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                // apps that are not allowed to read identifiers keep getting their SecurityException / null
                if (p.getThrowable() != null || p.getResult() == null || !st.idSpoof()) return;
                int uid = android.os.Binder.getCallingUid();
                if (HookUtil.isSystemUid(uid)) return;
                String pkg = HookUtil.callerPackage(p.thisObject, p.args, uid);
                if (HookUtil.isInfraPackage(pkg) || st.isExempt(pkg)) return;
                String fake = st.fakeIdFor(((Method) p.method).getName());
                if (fake != null) p.setResult(fake);
            }
        };
        String[] prefixes = {"getDeviceId", "getImei", "getMeid", "getSubscriberId", "getIccSerialNumber", "getLine1Number"};
        int m = 0;
        for (Class<?> c : targets) m += HookUtil.hookByPrefix(c, prefixes, fakeId);
        Class<?> subInfo = XposedHelpers.findClassIfExists("com.android.internal.telephony.PhoneSubInfoController", cl);
        if (subInfo != null) m += HookUtil.hookByPrefix(subInfo, prefixes, fakeId);
        HookEntry.log("identity hooks installed: " + m);
    }
}
