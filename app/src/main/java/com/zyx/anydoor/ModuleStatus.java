package com.zyx.anydoor;

/** Hooked by HookEntry in our own process; returns true only when the framework loaded the module. */
public final class ModuleStatus {
    private ModuleStatus() {}

    public static boolean isModuleActive() {
        return false;
    }
}
