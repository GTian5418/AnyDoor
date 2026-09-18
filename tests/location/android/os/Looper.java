package android.os;

/** Minimal host stub: no threads, no queue of its own. The Handler owns scheduling. */
public final class Looper {
    public static final Looper MAIN = new Looper();
    private Looper() { }
    public static Looper getMainLooper() { return MAIN; }
    public static Looper myLooper() { return MAIN; }
}
