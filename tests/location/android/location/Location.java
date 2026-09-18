package android.location;

/** Minimal host stub. Mock flag, wall clock, monotonic stamp and coordinates are all settable. */
public class Location {
    private String provider = "";
    private double latitude;
    private double longitude;
    private float accuracy;
    private long time;
    private long elapsedRealtimeNanos;
    private boolean mock;

    public Location(String provider) { this.provider = provider; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public double getLatitude() { return latitude; }
    public void setLatitude(double latitude) { this.latitude = latitude; }
    public double getLongitude() { return longitude; }
    public void setLongitude(double longitude) { this.longitude = longitude; }
    public float getAccuracy() { return accuracy; }
    public void setAccuracy(float accuracy) { this.accuracy = accuracy; }
    /** Wall-clock milliseconds since the epoch. */
    public long getTime() { return time; }
    public void setTime(long time) { this.time = time; }
    /** Monotonic milliseconds since boot, in nanoseconds. Zero means "unknown". */
    public long getElapsedRealtimeNanos() { return elapsedRealtimeNanos; }
    public void setElapsedRealtimeNanos(long nanos) { this.elapsedRealtimeNanos = nanos; }
    public boolean isFromMockProvider() { return mock; }
    public void setMock(boolean mock) { this.mock = mock; }

    @Override public String toString() {
        return "Location{" + provider + " " + latitude + "," + longitude
                + " mock=" + mock + " ageNanos=" + elapsedRealtimeNanos + "}";
    }
}
