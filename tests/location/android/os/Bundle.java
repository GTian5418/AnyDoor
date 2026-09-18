package android.os;

/** Minimal host stub. Only needed to satisfy the LocationListener signature. */
public class Bundle {
    public void putInt(String key, int value) { }
    public int getInt(String key) { return 0; }
}
