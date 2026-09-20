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

    /** Called from the binder thread of the probe; the actual delivery runs on our own thread. */
    static void kick() {
        lastKick = SystemClock.elapsedRealtime();
        Handler h = handler();
        if (h == null) return;
        h.removeCallbacks(INJECT);
        h.post(INJECT);
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
                List<Object> list = records.get(provider);
                if (list == null || list.isEmpty()) continue;
                Location fix = st.build(provider, null);
                for (Object r : new ArrayList<>(list)) {
                    Object receiver = XposedHelpers.getObjectField(r, "mReceiver");
                    String pkg = SystemHooks.DeliverHook.receiverPackage(receiver);
                    if (pkg == null || st.isExempt(pkg)) continue;
                    Object last = XposedHelpers.getObjectField(r, "mLastFixBroadcast");
                    if (last != null) {
                        boolean ok;
                        try {
                            ok = (Boolean) XposedHelpers.callStaticMethod(lmsClass, shouldBroadcast, fix, last, r, now);
                        } catch (Throwable t) {
                            ok = true;
                        }
                        if (!ok) continue;
                    }
                    if (last instanceof Location) ((Location) last).set(fix);
                    else XposedHelpers.setObjectField(r, "mLastFixBroadcast", new Location(fix));
                    INJECTING.set(Boolean.TRUE);
                    try {
                        Object delivered = XposedHelpers.callMethod(receiver, "callLocationChangedLocked", fix);
                        if (!Boolean.FALSE.equals(delivered)) total++;
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
            }
        }
        return total;
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
