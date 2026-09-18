package android.os;

/** Minimal host stub. The test advances <code>tick</code> manually; the value is monotonic, never wall-clock. */
public final class SystemClock {
    public static long tick = 1000000L;
    private SystemClock() { }
    public static long uptimeMillis() { return tick; }
    public static long elapsedRealtime() { return tick; }
    public static long elapsedRealtimeNanos() { return tick * 1000000L; }
}
