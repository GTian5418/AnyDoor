package io.github.zhaoyuxiangyyds_lab.anydoor;

import java.util.*;

/** Immutable, validated cross-process state. An active driver must renew its lease. */
public final class ConfigSnapshot {
    public static final String REVISION = "_revision", SCHEMA = "_schema", WALL = "_wall", ELAPSED = "_elapsed";
    public static final int PROTOCOL = 1;
    public static final long LEASE_MS = 15000;
    public final Map<String, String> values;
    public final long revision;
    private ConfigSnapshot(Map<String, String> values, long revision) {
        this.values = Collections.unmodifiableMap(values); this.revision = revision;
    }
    public static ConfigSnapshot from(Map<String, ?> source) {
        if (source == null) return null;
        try {
            Map<String,String> m = new HashMap<>();
            for (Map.Entry<String,?> e : source.entrySet()) if (e.getValue() != null) m.put(e.getKey(), String.valueOf(e.getValue()));
            long revision = Long.parseLong(m.get(REVISION));
            double lat = Double.parseDouble(m.get(Keys.LAT)), lng = Double.parseDouble(m.get(Keys.LNG));
            if (!"1".equals(m.get(SCHEMA)) || revision <= 0 || !Double.isFinite(lat) || !Double.isFinite(lng)
                    || Math.abs(lat) > 90 || Math.abs(lng) > 180 || !m.containsKey(Keys.STARTED)) return null;
            if (!"true".equals(m.get(Keys.STARTED)) && !"false".equals(m.get(Keys.STARTED))) return null;
            Long.parseLong(m.get(WALL)); Long.parseLong(m.get(ELAPSED));
            return new ConfigSnapshot(m, revision);
        } catch (RuntimeException e) { return null; }
    }
    public boolean leaseValid(long wall, long elapsed) {
        long w = wall - Long.parseLong(values.get(WALL)), e = elapsed - Long.parseLong(values.get(ELAPSED));
        return w >= -1000 && w <= LEASE_MS && e >= 0 && e <= LEASE_MS;
    }
    public boolean started(long wall, long elapsed) {
        return "true".equals(values.get(Keys.STARTED)) && leaseValid(wall, elapsed);
    }
}
