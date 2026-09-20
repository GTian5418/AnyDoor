package io.github.zhaoyuxiangyyds_lab.anydoor.xposed;

import android.location.Location;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * system_server-side fallback driver ("系统直推"). The normal driver registers test providers and
 * pushes fixes through them, which replaces the real gps/network providers for the whole device.
 * That is not possible while exempt apps must keep their real location, and it fails on ROMs that
 * refuse OP_MOCK_LOCATION. In those cases the app kicks this pump once per interval (through the
 * {@code anydoor.pump} last-location probe) and we hand a fresh spoofed fix directly to every
 * location registration of a non-exempt app, exactly where a provider report would be delivered:
 * <ul>
 * <li>Android 12+: {@code LocationProviderManager.deliverToListeners(registration ->
 *     registration.acceptLocationChange(result))} for the gps/network/fused/passive managers;</li>
 * <li>Android 8.1 - 11: {@code Receiver.callLocationChangedLocked(location)} for every UpdateRecord
 *     in {@code LocationManagerService.mRecordsByProvider}, honouring the request's interval /
 *     displacement filter like {@code handleLocationChangedLocked} does.</li>
 * </ul>
 * The real providers keep running untouched, so exempt apps still get their real fixes (rewritten
 * for nobody), and the system's last-location cache is never polluted with spoofed data.
 */
final class Pump {
    private Pump() {}

    private static final String[] PROVIDERS = {"gps", "network", "fused", "passive"};
    /** set on the delivering thread while we inject, so the rewrite hooks pass our fix through */
    static final ThreadLocal<Boolean> INJECTING = new ThreadLocal<>();

    private static final Map<String, Object> MANAGERS = new ConcurrentHashMap<>();   // 12+
    private static volatile Object lms;                                            // <= 11
    private static Class<?> lmsClass;
    private static Method deliverToListeners;                                     // 12+
    private static String shouldBroadcast;                                        // <= 11
    private static Handler handler;
    private static SpoofState state;
    private static final AtomicLong injected = new AtomicLong();
    private static volatile long lastInject;
    private static volatile long lastKick;
    private static volatile String status = "none";

    /** @param lpm LocationProviderManager class (Android 12+) or null; @param lmsCls LocationManagerService class */
    static void install(Class<?> lpm, Class<?> lmsCls, SpoofState st) {
        state = st;
        lmsClass = lmsCls;
        int n = 0;
        if (lpm != null) {
            deliverToListeners = findDeliver(lpm);
            if (deliverToListeners != null) {
                n = hookConstructors(lpm, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        String name = managerName(p.thisObject);
                        if (name != null) MANAGERS.put(name, p.thisObject);
                    }
                });
                // Also grab the LMS instance so we can recover the managers from mProviderManagers
                // if they were constructed before this hook installed (MANAGERS still empty).
                if (lmsCls != null) hookConstructors(lmsCls, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        lms = p.thisObject;
                    }
                });
                status = n > 0 ? "12+" : "no-ctor";
            } else {
                status = "no-deliver";
            }
        } else if (lmsCls != null) {
            // Android 8.1/9: shouldBroadcastSafe, 10/11: shouldBroadcastSafeLocked
            shouldBroadcast = "shouldBroadcastSafe";
            for (Method m : lmsCls.getDeclaredMethods()) {
                if (m.getName().equals("shouldBroadcastSafeLocked")) shouldBroadcast = m.getName();
            }
            n = hookConstructors(lmsCls, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    lms = p.thisObject;
                }
            });
            status = n > 0 ? "legacy" : "no-ctor";
        }
        HookEntry.log("pump: " + status + " (" + n + " ctor hooks)");
    }

    private static int hookConstructors(Class<?> cls, XC_MethodHook hook) {
        try {
            return XposedBridge.hookAllConstructors(cls, hook).size();
        } catch (Throwable t) {
            HookEntry.log("pump ctor hook failed for " + cls.getName() + ": " + t);
            return 0;
        }
    }

    /** ListenerMultiplexer.deliverToListeners(Function) – protected final, somewhere up the hierarchy. */
    private static Method findDeliver(Class<?> cls) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals("deliverToListeners") || m.getParameterTypes().length != 1) continue;
                if (m.getParameterTypes()[0] != java.util.function.Function.class) continue;
                m.setAccessible(true);
                return m;
            }
        }
        return null;
    }

    private static String managerName(Object manager) {
        try {
            Object n = XposedHelpers.getObjectField(manager, "mName");
            if (n instanceof String) return (String) n;
        } catch (Throwable ignored) {
        }
        try {
            Object n = XposedHelpers.callMethod(manager, "getName");
            if (n instanceof String) return (String) n;
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** providers to inject on the next round: null = all, else those without a running test provider */
    private static volatile java.util.Set<String> only;

    /**
     * Called from the binder thread of the probe; the actual delivery runs on our own thread.
     *
     * @param providers comma separated provider names that have no test provider right now, or
     *                  null/empty for every provider
     */
    static void kick(String providers) {
        lastKick = SystemClock.elapsedRealtime();
        java.util.Set<String> set = null;
        if (providers != null && !providers.trim().isEmpty()) {
            set = new java.util.HashSet<>();
            for (String s : providers.split(",")) {
                String name = s.trim();
                if (!name.isEmpty()) set.add(name);
            }
            // a missing gps/network test provider also starves fused/passive, which are fed by them
            if (!set.isEmpty()) {
                set.add("fused");
                set.add("passive");
            } else set = null;
        }
        only = set;
        Handler h = handler();
        if (h == null) return;
        h.removeCallbacks(INJECT);
        h.post(INJECT);
    }

    static void kick() {
        kick(null);
    }

    private static boolean wanted(String provider) {
        java.util.Set<String> set = only;
        return set == null || set.contains(provider);
    }

    private static synchronized Handler handler() {
        if (handler == null) {
            if ("none".equals(status) || status.startsWith("no-")) return null;
            HandlerThread t = new HandlerThread("anydoor-pump");
            t.start();
            handler = new Handler(t.getLooper());
        }
        return handler;
    }

    private static final Runnable INJECT = new Runnable() {
        @Override
        public void run() {
            SpoofState st = state;
            if (st == null || !st.started()) return;
            try {
                int n = deliverToListeners != null ? injectModern(st) : injectLegacy(st);
                if (n > 0) {
                    injected.addAndGet(n);
                    lastInject = System.currentTimeMillis();
                }
                if (st.debug()) HookEntry.log("pump: injected to " + n + " registrations");
            } catch (Throwable t) {
                HookEntry.log("pump failed: " + t);
            }
        }
    };

    // ------------------------------------------------------------------ Android 12+

    private static int injectModern(final SpoofState st) throws Exception {
        ensureManagers();
        int total = 0;
        for (String provider : PROVIDERS) {
            if (!wanted(provider)) continue;
            Object manager = MANAGERS.get(provider);
            if (manager == null) continue;
            final Location fix = st.build(provider, null);
            final Object result = XposedHelpers.callStaticMethod(
                    Class.forName("android.location.LocationResult"), "wrap", (Object) new Location[]{fix});
            final int[] count = {0};
            Object function = Proxy.newProxyInstance(Pump.class.getClassLoader(),
                    new Class[]{java.util.function.Function.class}, new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            String name = method.getName();
                            if ("apply".equals(name)) {
                                Object registration = args[0];
                                String pkg = registrationPackage(registration);
                                if (pkg == null || st.isExempt(pkg)) return null;
                                INJECTING.set(Boolean.TRUE);
                                try {
                                    Object op = XposedHelpers.callMethod(registration, "acceptLocationChange", result);
                                    if (op != null) count[0]++;
                                    return op;
                                } catch (Throwable t) {
                                    if (st.debug()) HookEntry.log("pump accept failed for " + pkg + ": " + t);
                                    return null;
                                } finally {
                                    INJECTING.set(Boolean.FALSE);
                                }
                            }
                            if ("equals".equals(name)) return proxy == args[0];
                            if ("hashCode".equals(name)) return System.identityHashCode(proxy);
                            if ("toString".equals(name)) return "AnyDoorPump";
                            return null;
                        }
                    });
            deliverToListeners.invoke(manager, function);
            total += count[0];
        }
        return total;
    }

    /**
     * If the manager constructors ran before our hook (MANAGERS empty), pull the live managers out
     * of {@code LocationManagerService.mProviderManagers} (a CopyOnWriteArrayList in Android 12+).
     */
    @SuppressWarnings("unchecked")
    private static void ensureManagers() {
        Object service = lms;
        if (service == null || MANAGERS.size() >= PROVIDERS.length) return;
        try {
            Object list = XposedHelpers.getObjectField(service, "mProviderManagers");
            if (!(list instanceof Iterable)) return;
            for (Object manager : (Iterable<Object>) list) {
                String name = managerName(manager);
                if (name != null) MANAGERS.put(name, manager);
            }
        } catch (Throwable ignored) {
        }
    }

    static String registrationPackage(Object reg) {
        try {
            Object id = XposedHelpers.callMethod(reg, "getIdentity");
            return (String) XposedHelpers.callMethod(id, "getPackageName");
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ------------------------------------------------------------------ Android 8.1 - 11

    /**
     * Mirrors LocationManagerService.handleLocationChangedLocked (8.1 - 11): interval / distance
     * filter, app-op note, coarse copy for coarse-only receivers, numUpdates bookkeeping, and the
     * disposal of exhausted records and dead receivers, so one-shot requests really end after
     * their update and apps in the background are not fed behind the system's back.
     */
    @SuppressWarnings("unchecked")
    private static int injectLegacy(SpoofState st) throws Exception {
        Object service = lms;
        if (service == null) return 0;
        int total = 0;
        Object lock = XposedHelpers.getObjectField(service, "mLock");
        synchronized (lock) {
            Map<String, List<Object>> records = (Map<String, List<Object>>) XposedHelpers.getObjectField(service, "mRecordsByProvider");
            if (records == null) return 0;
            long now = SystemClock.elapsedRealtime();
            for (String provider : PROVIDERS) {
                if (!wanted(provider)) continue;
                List<Object> list = records.get(provider);
                if (list == null || list.isEmpty()) continue;
                Location fix = st.build(provider, null);
                Location coarse = null;
                List<Object> dead = new ArrayList<>(), deadReceivers = new ArrayList<>();
                for (Object r : new ArrayList<>(list)) {
                    Object receiver = XposedHelpers.getObjectField(r, "mReceiver");
                    String pkg = SystemHooks.DeliverHook.receiverPackage(receiver);
                    if (pkg == null || st.isExempt(pkg)) continue;
                    Location deliver = fix;
                    if (isCoarseReceiver(receiver)) {
                        if (coarse == null) coarse = coarsen(service, provider, fix);
                        deliver = coarse;
                    }
                    Object last = XposedHelpers.getObjectField(r, "mLastFixBroadcast");
                    boolean ok = true;
                    if (last != null) {
                        try {
                            ok = (Boolean) XposedHelpers.callStaticMethod(lmsClass, shouldBroadcast, deliver, last, r, now);
                        } catch (Throwable t) {
                            ok = true;
                        }
                    }
                    if (ok && noteAccess(service, receiver)) {
                        if (last instanceof Location) ((Location) last).set(deliver);
                        else XposedHelpers.setObjectField(r, "mLastFixBroadcast", new Location(deliver));
                        INJECTING.set(Boolean.TRUE);
                        try {
                            Object delivered = XposedHelpers.callMethod(receiver, "callLocationChangedLocked", deliver);
                            if (Boolean.FALSE.equals(delivered)) deadReceivers.add(receiver);
                            else total++;
                        } catch (Throwable t) {
                            if (st.debug()) HookEntry.log("pump deliver failed for " + pkg + ": " + t);
                        } finally {
                            INJECTING.set(Boolean.FALSE);
                        }
                        try {
                            XposedHelpers.callMethod(XposedHelpers.getObjectField(r, "mRealRequest"), "decrementNumUpdates");
                        } catch (Throwable ignored) {
                        }
                    }
                    if (expired(r, now)) {
                        try {
                            XposedHelpers.callMethod(receiver, "callRemovedLocked");   // 11 only
                        } catch (Throwable ignored) {
                        }
                        dead.add(r);
                    }
                }
                for (Object receiver : deadReceivers) {
                    try {
                        XposedHelpers.callMethod(service, "removeUpdatesLocked", receiver);
                    } catch (Throwable ignored) {
                    }
                }
                if (!dead.isEmpty()) {
                    for (Object r : dead) {
                        try {
                            XposedHelpers.callMethod(r, "disposeLocked", true);
                        } catch (Throwable ignored) {
                        }
                    }
                    try {
                        XposedHelpers.callMethod(service, "applyRequirementsLocked", provider);
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        return total;
    }

    /** UpdateRecord exhausted (numUpdates used up) or past its expiry, like LMS tracks it. */
    private static boolean expired(Object record, long now) {
        try {
            Object request = XposedHelpers.getObjectField(record, "mRealRequest");
            if ((Integer) XposedHelpers.callMethod(request, "getNumUpdates") <= 0) return true;
            try {
                return XposedHelpers.getLongField(record, "mExpirationRealtimeMs") < now;   // 11
            } catch (Throwable ignored) {
                return (Long) XposedHelpers.callMethod(request, "getExpireAt") < now;    // 8.1 - 10
            }
        } catch (Throwable t) {
            return false;
        }
    }

    /** Receiver only holds ACCESS_COARSE_LOCATION. */
    private static boolean isCoarseReceiver(Object receiver) {
        try {
            return XposedHelpers.getIntField(receiver, "mAllowedResolutionLevel") == 1;        // 8.1 - 10
        } catch (Throwable ignored) {
        }
        try {
            Object id = XposedHelpers.getObjectField(receiver, "mCallerIdentity");
            return XposedHelpers.getIntField(id, "permissionLevel") == 1;                     // 11
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** The system's own coarse copy (LocationFudger); the fine fix if the fudger is unavailable. */
    private static Location coarsen(Object service, String provider, Location fix) {
        try {
            Object fudger;
            try {
                fudger = XposedHelpers.getObjectField(service, "mLocationFudger");            // 8.1 - 10: one per service
            } catch (Throwable ignored) {
                Object manager = XposedHelpers.callMethod(service, "getLocationProviderManager", provider);
                fudger = XposedHelpers.getObjectField(manager, "mLocationFudger");            // 11: one per provider manager
            }
            Object c;
            try {
                c = XposedHelpers.callMethod(fudger, "createCoarse", fix);      // 11
            } catch (Throwable ignored) {
                c = XposedHelpers.callMethod(fudger, "getOrCreate", fix);       // 8.1 - 10
            }
            if (c instanceof Location) return (Location) c;
        } catch (Throwable ignored) {
        }
        return fix;
    }

    /** Note the location app-op the way LMS does before a delivery; false = the app may not receive now. */
    private static boolean noteAccess(Object service, Object receiver) {
        try {
            Object helper = XposedHelpers.getObjectField(service, "mAppOpsHelper");              // 11
            Object id = XposedHelpers.getObjectField(receiver, "mCallerIdentity");
            return (Boolean) XposedHelpers.callMethod(helper, "noteLocationAccess", id);
        } catch (Throwable ignored) {
        }
        try {
            int pid, uid, level = XposedHelpers.getIntField(receiver, "mAllowedResolutionLevel");
            String pkg;
            try {
                Object id = XposedHelpers.getObjectField(receiver, "mCallerIdentity");         // 10
                pid = XposedHelpers.getIntField(id, "mPid");
                uid = XposedHelpers.getIntField(id, "mUid");
                pkg = (String) XposedHelpers.getObjectField(id, "mPackageName");
            } catch (Throwable ignored) {
                pid = XposedHelpers.getIntField(receiver, "mPid");                              // 8.1 / 9
                uid = XposedHelpers.getIntField(receiver, "mUid");
                pkg = (String) XposedHelpers.getObjectField(receiver, "mPackageName");
            }
            return (Boolean) XposedHelpers.callMethod(service, "reportLocationAccessNoThrow", pid, uid, pkg, level);
        } catch (Throwable ignored) {
        }
        return true;
    }

    // ------------------------------------------------------------------ status

    static boolean injecting() {
        return Boolean.TRUE.equals(INJECTING.get());
    }

    static String status() {
        return status;
    }

    static long injected() {
        return injected.get();
    }

    static long lastInject() {
        return lastInject;
    }

    static boolean recentlyKicked() {
        return SystemClock.elapsedRealtime() - lastKick < 5000;
    }
}
