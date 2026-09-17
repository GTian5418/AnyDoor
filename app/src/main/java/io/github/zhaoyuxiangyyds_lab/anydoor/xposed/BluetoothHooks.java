package io.github.zhaoyuxiangyyds_lab.anydoor.xposed;

import io.github.zhaoyuxiangyyds_lab.anydoor.Keys;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Hooks in the com.android.bluetooth process: in privacy mode no BLE / classic scan result ever
 * leaves the stack, so apps cannot use nearby beacons or devices to infer where the phone is.
 */
final class BluetoothHooks {
    private BluetoothHooks() {}

    static void install(XC_LoadPackage.LoadPackageParam lp, final SpoofState st) {
        ClassLoader cl = lp.classLoader;
        XC_MethodHook drop = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                if (st.privacy() && st.bool(Keys.BT_BLOCK, true)) p.setResult(null);
            }
        };
        int n = 0;
        // BLE scan results arrive from native into GattService.onScanResult(...)
        for (String c : new String[]{"com.android.bluetooth.gatt.GattService", "com.android.bluetooth.le_scan.ScanNativeInterface",
                "com.android.bluetooth.le_scan.TransitionalScanHelper"}) {
            Class<?> cls = XposedHelpers.findClassIfExists(c, cl);
            if (cls != null) n += HookUtil.hookAll(cls, "onScanResult", drop);
        }
        // classic discovery results
        Class<?> rd = XposedHelpers.findClassIfExists("com.android.bluetooth.btservice.RemoteDevices", cl);
        if (rd != null) n += HookUtil.hookAll(rd, "deviceFoundCallback", drop);
        HookEntry.log("bluetooth hooks installed: " + n);
    }
}
