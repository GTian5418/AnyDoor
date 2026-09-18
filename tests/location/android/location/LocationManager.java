package android.location;

import android.os.Looper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Minimal host stub for the LocationManager binder surface used by RealLocationRequest.
 *
 * <p>Every fault the framework can raise is injectable per provider: SecurityException or a plain
 * RuntimeException from {@code isProviderEnabled}, {@code getLastKnownLocation} and
 * {@code requestLocationUpdates}, plus the ROM quirk where a registration is bound and then the
 * call throws. All registrations and removals are recorded so leaks are observable.
 */
public class LocationManager {
    public static final String GPS_PROVIDER = "gps";
    public static final String NETWORK_PROVIDER = "network";
    public static final int OUT_OF_SERVICE = 0;
    public static final int TEMPORARILY_UNAVAILABLE = 1;
    public static final int AVAILABLE = 2;

    public final Map<String, Boolean> enabled = new LinkedHashMap<String, Boolean>();
    public final Map<String, Location> lastKnown = new LinkedHashMap<String, Location>();
    public final Set<String> securityOnEnabled = new LinkedHashSet<String>();
    public final Set<String> runtimeOnEnabled = new LinkedHashSet<String>();
    public final Set<String> securityOnLastKnown = new LinkedHashSet<String>();
    public final Set<String> runtimeOnLastKnown = new LinkedHashSet<String>();
    public final Set<String> securityOnRequest = new LinkedHashSet<String>();
    public final Set<String> runtimeOnRequest = new LinkedHashSet<String>();
    /** Providers where the registration is recorded first and a SecurityException is thrown after. */
    public final Set<String> registerThenThrow = new LinkedHashSet<String>();
    public boolean throwOnRemove;

    public int requestCalls;
    public int lastKnownCalls;
    public int removeCalls;

    private final List<Registration> active = new ArrayList<Registration>();
    private final List<Registration> ever = new ArrayList<Registration>();
    private final List<LocationListener> removed = new ArrayList<LocationListener>();

    public LocationManager() {
        enabled.put(GPS_PROVIDER, Boolean.FALSE);
        enabled.put(NETWORK_PROVIDER, Boolean.FALSE);
    }

    public void enable(String... providers) {
        for (String provider : providers) enabled.put(provider, Boolean.TRUE);
    }

    public void disableAll() {
        for (String provider : enabled.keySet()) enabled.put(provider, Boolean.FALSE);
    }

    public boolean isProviderEnabled(String provider) {
        if (securityOnEnabled.contains(provider)) throw new SecurityException("denied " + provider);
        if (runtimeOnEnabled.contains(provider)) throw new RuntimeException("broken " + provider);
        return Boolean.TRUE.equals(enabled.get(provider));
    }

    public Location getLastKnownLocation(String provider) {
        lastKnownCalls++;
        if (securityOnLastKnown.contains(provider)) throw new SecurityException("denied " + provider);
        if (runtimeOnLastKnown.contains(provider)) throw new RuntimeException("broken " + provider);
        return lastKnown.get(provider);
    }

    public void requestLocationUpdates(String provider, long minTime, float minDistance,
                                       LocationListener listener, Looper looper) {
        requestCalls++;
        Registration registration = new Registration(provider, listener, minTime, minDistance, looper);
        if (registerThenThrow.contains(provider)) {
            active.add(registration);
            ever.add(registration);
            throw new SecurityException("bound then denied " + provider);
        }
        if (securityOnRequest.contains(provider)) throw new SecurityException("denied " + provider);
        if (runtimeOnRequest.contains(provider)) throw new RuntimeException("broken " + provider);
        active.add(registration);
        ever.add(registration);
    }

    public void removeUpdates(LocationListener listener) {
        removeCalls++;
        if (throwOnRemove) throw new RuntimeException("remove failed");
        removed.add(listener);
        for (int i = active.size() - 1; i >= 0; i--) if (active.get(i).listener == listener) active.remove(i);
    }

    /** Registrations the framework still holds. Must be empty after every terminal outcome. */
    public List<Registration> active() { return new ArrayList<Registration>(active); }
    /** Every registration ever made, including ones later removed. */
    public List<Registration> everRegistered() { return new ArrayList<Registration>(ever); }
    public List<LocationListener> removedListeners() { return new ArrayList<LocationListener>(removed); }
    public boolean hasActive() { return !active.isEmpty(); }

    /** Delivers a fix to every live registration of one provider. Returns how many listeners ran. */
    public int dispatch(String provider, Location location) {
        int delivered = 0;
        for (Registration registration : new ArrayList<Registration>(active)) {
            if (registration.provider.equals(provider)) {
                registration.listener.onLocationChanged(location);
                delivered++;
            }
        }
        return delivered;
    }

    /** Delivers the same fix to every live registration, simulating both providers reporting at once. */
    public int dispatchAny(Location location) {
        int delivered = 0;
        for (Registration registration : new ArrayList<Registration>(active)) {
            registration.listener.onLocationChanged(location);
            delivered++;
        }
        return delivered;
    }

    public static final class Registration {
        public final String provider;
        public final LocationListener listener;
        public final long minTime;
        public final float minDistance;
        public final Looper looper;

        Registration(String provider, LocationListener listener, long minTime, float minDistance, Looper looper) {
            this.provider = provider;
            this.listener = listener;
            this.minTime = minTime;
            this.minDistance = minDistance;
            this.looper = looper;
        }
    }
}
