package io.github.zhaoyuxiangyyds_lab.anydoor;

import java.util.*;

/** Tracks each provider independently, including partial creation and failed cleanup. */
public final class ProviderController {
    public interface Backend {
        void add(String name) throws Exception;
        void enable(String name) throws Exception;
        void remove(String name) throws Exception;
        void push(String name) throws Exception;
    }
    private static final String[] NAMES = {"gps", "network"};
    private final Backend backend;
    private final Set<String> owned = new HashSet<>(), enabled = new HashSet<>();
    private final Map<String,String> errors = new LinkedHashMap<>();
    private final Map<String,Long> retry = new HashMap<>();
    private boolean running;
    private long generation;
    private long lastPush, lastReconcile;
    public ProviderController(Backend backend) { this.backend = backend; }
    public synchronized void restoreOwnership(String name) { if (Arrays.asList(NAMES).contains(name)) owned.add(name); }
    public synchronized long start() { running = true; return ++generation; }
    public synchronized void stop() {
        running = false; generation++;
        for (String name : NAMES) remove(name);
    }
    public synchronized void reconcile(long token, boolean driver, boolean network, long now) {
        if (!running || token != generation) return;
        lastReconcile = now;
        for (String name : NAMES) {
            boolean want = driver && (!"network".equals(name) || network);
            if (!want) { remove(name); continue; }
            if (enabled.contains(name) || now < retry.getOrDefault(name, 0L)) continue;
            if (owned.contains(name) && !remove(name)) { retry.put(name, now + 5000); continue; }
            retry.put(name, now + 5000);
            try {
                backend.add(name); owned.add(name); // record before enable, which can fail independently
                backend.enable(name); enabled.add(name); errors.remove(name);
            } catch (Exception e) { errors.put(name, "创建/启用: " + e); }
        }
    }
    private boolean remove(String name) {
        if (!owned.contains(name)) { errors.remove(name); return true; }
        try {
            backend.remove(name); owned.remove(name); enabled.remove(name); errors.remove(name); retry.remove(name);
            return true;
        } catch (Exception e) { enabled.remove(name); errors.put(name, "清理: " + e); return false; }
    }
    public synchronized void push(long now) {
        if (!running) return;
        for (String name : NAMES) {
            if (!enabled.contains(name)) continue;
            try { backend.push(name); lastPush = now; errors.remove(name); }
            catch (Exception e) { errors.put(name, "推送: " + e); enabled.remove(name); retry.put(name, lastReconcile + 5000); }
        }
    }
    public synchronized boolean hasProviders() { return !enabled.isEmpty(); }
    public synchronized boolean enabled(String name) { return enabled.contains(name); }
    public synchronized boolean owned(String name) { return owned.contains(name); }
    public synchronized String error() {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String,String> e : errors.entrySet()) { if (b.length() > 0) b.append("; "); b.append(e.getKey()).append(": ").append(e.getValue()); }
        return b.toString();
    }
    public synchronized long lastPush() { return lastPush; }
}
