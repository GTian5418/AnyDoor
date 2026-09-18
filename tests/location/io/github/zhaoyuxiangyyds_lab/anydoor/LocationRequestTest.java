package io.github.zhaoyuxiangyyds_lab.anydoor;

import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;

/**
 * Host regression tests for RealLocationRequest. The production class is compiled unmodified
 * against minimal android.location / android.os stubs; every assertion drives the real control
 * flow and inspects real listener/timer/registration state rather than source text.
 */
public class LocationRequestTest {
    static int count;

    static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        count++;
    }

    static final String GPS = LocationManager.GPS_PROVIDER;
    static final String NET = LocationManager.NETWORK_PROVIDER;
    static final long TIMEOUT = RealLocationRequest.TIMEOUT_MS;
    static final long MAX_AGE = RealLocationRequest.MAX_AGE_MS;

    // ---------------------------------------------------------------- harness

    static final class Result {
        int calls;
        Location location;
        String error;
        final List<String> errors = new ArrayList<String>();
    }

    static Result probe() { return new Result(); }

    static RealLocationRequest.Callback callback(final Result result) {
        return new RealLocationRequest.Callback() {
            public void complete(Location location, String error) {
                result.calls++;
                result.location = location;
                result.error = error;
                if (error != null) result.errors.add(error);
            }
        };
    }

    static boolean busy(Result r) { return r.error != null && r.error.contains("正在定位"); }
    static boolean timedOut(Result r) { return r.error != null && r.error.contains("定位超时"); }
    static boolean denied(Result r) { return r.error != null && r.error.contains("权限"); }
    static boolean switchedOff(Result r) { return r.error != null && r.error.contains("开启系统定位"); }
    static boolean noSource(Result r) { return r.error != null && r.error.contains("暂无可用定位源"); }
    static boolean unavailable(Result r) { return r.error != null && r.error.contains("系统定位服务不可用"); }
    static boolean closed(Result r) { return r.error != null && r.error.contains("页面已关闭"); }
    static boolean cancelled(Result r) { return r.error != null && r.error.contains("已取消"); }

    /** One request object plus the stub platform it talks to. */
    static final class H {
        final LocationManager manager = new LocationManager();
        final Handler handler = new Handler(Looper.getMainLooper());
        final RealLocationRequest request;

        H() { request = new RealLocationRequest(manager, handler); }

        /** Advances the looper queue and the monotonic clock together, as a real device would. */
        void tick(long ms) {
            handler.advance(ms);
            SystemClock.tick += ms;
        }
    }

    /** A fix whose monotonic stamp is <code>ageMs</code> milliseconds old. Negative means the future. */
    static Location loc(double lat, double lng, long ageMs) {
        Location location = new Location("test");
        location.setLatitude(lat);
        location.setLongitude(lng);
        location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos() - ageMs * 1000000L);
        location.setTime(System.currentTimeMillis() - ageMs);
        return location;
    }

    static Location mockLoc(double lat, double lng, long ageMs) {
        Location location = loc(lat, lng, ageMs);
        location.setMock(true);
        return location;
    }

    // ---------------------------------------------------------------- cache

    /** A usable last-known fix must complete synchronously, without registering anything. */
    static void testValidCacheCompletesImmediately() {
        H h = new H();
        h.manager.enable(GPS);
        Location cached = loc(31.2, 121.5, 1000);
        h.manager.lastKnown.put(GPS, cached);
        Result r = probe();
        h.request.request(callback(r));
        check(r.calls == 1, "a usable cached fix completes inside request()");
        check(r.location == cached, "the cached instance is handed back untouched");
        check(r.error == null, "a cache hit reports no error");
        check(h.manager.requestCalls == 0, "a cache hit registers no listener");
        check(h.manager.active().isEmpty(), "a cache hit leaves no active registration");
        check(h.handler.pending() == 0, "a cache hit cancels the timeout it had armed");
        check(h.manager.lastKnownCalls == 1, "the cache is read exactly once per provider");
    }

    /** With two usable caches the freshest one must win, regardless of provider order. */
    static void testYoungestCacheWins() {
        H h = new H();
        h.manager.enable(GPS, NET);
        Location older = loc(1.0, 1.0, 50000);
        Location newer = loc(2.0, 2.0, 500);
        h.manager.lastKnown.put(GPS, older);
        h.manager.lastKnown.put(NET, newer);
        Result r = probe();
        h.request.request(callback(r));
        check(r.calls == 1 && r.location == newer, "the youngest usable cached fix wins");
        check(h.manager.lastKnownCalls == 2, "both enabled providers are consulted for cache");

        H h2 = new H();
        h2.manager.enable(GPS, NET);
        Location older2 = loc(1.0, 1.0, 40000);
        Location newer2 = loc(2.0, 2.0, 10);
        h2.manager.lastKnown.put(GPS, newer2);
        h2.manager.lastKnown.put(NET, older2);
        Result r2 = probe();
        h2.request.request(callback(r2));
        check(r2.calls == 1 && r2.location == newer2, "cache choice does not depend on provider order");
    }

    static void expectCacheRejected(Location bad, String label) {
        H h = new H();
        h.manager.enable(GPS);
        h.manager.lastKnown.put(GPS, bad);
        Result r = probe();
        h.request.request(callback(r));
        check(r.calls == 0, "an unusable cached fix is not delivered (" + label + ")");
        check(h.manager.requestCalls == 1, "an unusable cached fix falls back to a live listener (" + label + ")");
        check(h.handler.pending() == 1, "an unusable cached fix keeps the timeout armed (" + label + ")");
        h.tick(TIMEOUT);
        check(timedOut(r), "an unusable cached fix still ends in a timeout (" + label + ")");
        check(h.manager.active().isEmpty() && h.handler.pending() == 0,
                "an unusable cached fix leaves no residue (" + label + ")");
    }

    static void testCacheRejectsUnusableFixes() {
        expectCacheRejected(null, "no cache at all");
        expectCacheRejected(mockLoc(31.0, 121.0, 0), "mock provider");
        expectCacheRejected(loc(31.0, 121.0, MAX_AGE + 1), "older than 60s");
        expectCacheRejected(loc(31.0, 121.0, -5000), "stamped in the future");
        expectCacheRejected(loc(Double.NaN, 121.0, 0), "NaN latitude");
        expectCacheRejected(loc(31.0, Double.NaN, 0), "NaN longitude");
        expectCacheRejected(loc(Double.POSITIVE_INFINITY, 121.0, 0), "infinite latitude");
        expectCacheRejected(loc(90.0001, 121.0, 0), "latitude past the pole");
        expectCacheRejected(loc(31.0, 180.0001, 0), "longitude past the antimeridian");
    }

    /** Boundary behaviour of the freshness rule, exercised directly on the production helpers. */
    static void testUsabilityBoundaries() {
        check(RealLocationRequest.usable(loc(0, 0, 0)), "a brand new fix is usable");
        check(RealLocationRequest.usable(loc(0, 0, MAX_AGE)), "a fix exactly 60s old is still usable");
        check(!RealLocationRequest.usable(loc(0, 0, MAX_AGE + 1)), "a fix 60s and 1ms old is rejected");
        check(!RealLocationRequest.usable(loc(0, 0, -1)), "a fix 1ms in the future is rejected");
        check(!RealLocationRequest.usable(null), "a null fix is rejected");
        check(!RealLocationRequest.usable(mockLoc(0, 0, 0)), "a mock fix is rejected");
        check(!RealLocationRequest.usable(loc(Double.NaN, 0, 0)), "NaN latitude is rejected");
        check(!RealLocationRequest.usable(loc(0, Double.NaN, 0)), "NaN longitude is rejected");
        check(!RealLocationRequest.usable(loc(90.0001, 0, 0)), "latitude above 90 is rejected");
        check(!RealLocationRequest.usable(loc(-90.0001, 0, 0)), "latitude below -90 is rejected");
        check(!RealLocationRequest.usable(loc(0, 180.0001, 0)), "longitude above 180 is rejected");
        check(!RealLocationRequest.usable(loc(0, -180.0001, 0)), "longitude below -180 is rejected");
        check(RealLocationRequest.usable(loc(90, 180, 0)), "the pole and antimeridian corners are accepted");
        check(RealLocationRequest.usable(loc(-90, -180, 0)), "the opposite corners are accepted");
    }

    /** Every rejection rule must also apply to fixes arriving on a live listener. */
    static void testLiveCallbackRejectsUnusableFixes() {
        H h = new H();
        h.manager.enable(GPS);
        Result r = probe();
        h.request.request(callback(r));
        check(h.manager.active().size() == 1, "one listener is registered for the one enabled provider");
        LocationListener listener = h.manager.active().get(0).listener;

        listener.onLocationChanged(null);
        check(r.calls == 0, "a null fix on the listener is ignored");
        listener.onLocationChanged(mockLoc(31.0, 121.0, 0));
        check(r.calls == 0, "a mock fix on the listener is ignored");
        listener.onLocationChanged(loc(31.0, 121.0, MAX_AGE + 1));
        check(r.calls == 0, "a stale fix on the listener is ignored");
        listener.onLocationChanged(loc(31.0, 121.0, -1000));
        check(r.calls == 0, "a future fix on the listener is ignored");
        listener.onLocationChanged(loc(Double.NaN, 121.0, 0));
        check(r.calls == 0, "a NaN fix on the listener is ignored");
        listener.onLocationChanged(loc(31.0, 180.5, 0));
        check(r.calls == 0, "an out-of-range fix on the listener is ignored");
        check(r.calls == 0 && h.manager.active().size() == 1, "rejected fixes leave the request pending");
        check(h.handler.pending() == 1, "rejected fixes leave the timeout running");

        Location good = loc(31.2, 121.5, 10);
        listener.onLocationChanged(good);
        check(r.calls == 1 && r.location == good, "the first usable fix completes the request");
        check(h.manager.active().isEmpty(), "the listener is removed as soon as the request completes");
        check(h.handler.pending() == 0, "the timeout is removed as soon as the request completes");

        listener.onLocationChanged(loc(1.0, 1.0, 0));
        check(r.calls == 1, "the old listener cannot complete the request a second time");
    }

    // ---------------------------------------------------------------- providers

    static void testSeparateListenerPerProvider() {
        H h = new H();
        h.manager.enable(GPS, NET);
        Result r = probe();
        h.request.request(callback(r));
        List<LocationManager.Registration> regs = h.manager.active();
        check(regs.size() == 2, "both enabled providers are registered");
        check(regs.get(0).listener != regs.get(1).listener, "each provider gets its own listener instance");
        check(!regs.get(0).listener.equals(regs.get(1).listener), "the listeners are not equal either");
        check(regs.get(0).minTime == 0L && regs.get(1).minTime == 0L, "updates are requested with no time throttle");
        check(regs.get(0).minDistance == 0f && regs.get(1).minDistance == 0f, "updates are requested with no distance throttle");
        check(regs.get(0).looper == h.handler.getLooper(), "updates are delivered on the supplied looper");
        check(r.calls == 0, "registering listeners does not complete the request by itself");
    }

    static void testSimultaneousProvidersCompleteOnce() {
        H h = new H();
        h.manager.enable(GPS, NET);
        Result r = probe();
        h.request.request(callback(r));
        int delivered = h.manager.dispatchAny(loc(31.0, 121.0, 0));
        check(delivered == 2, "the stub delivers the same fix to both live listeners");
        check(r.calls == 1, "two providers reporting at once complete the request exactly once");
        check(h.manager.active().isEmpty(), "both registrations are torn down after the first fix");

        H h2 = new H();
        h2.manager.enable(GPS, NET);
        Result r2 = probe();
        h2.request.request(callback(r2));
        h2.manager.dispatch(NET, loc(31.0, 121.0, 0));
        check(r2.calls == 1, "the network provider alone completes the request");
        h2.manager.dispatch(GPS, loc(31.0, 121.0, 0));
        check(r2.calls == 1, "the later gps fix cannot complete the request again");
    }

    static void testSingleProviderFailureStillSucceeds() {
        H h = new H();
        h.manager.enable(GPS, NET);
        h.manager.runtimeOnRequest.add(GPS);
        Result r = probe();
        h.request.request(callback(r));
        check(r.calls == 0, "a broken provider does not end the request while another is healthy");
        List<LocationManager.Registration> regs = h.manager.active();
        check(regs.size() == 1 && regs.get(0).provider.equals(NET), "the healthy provider is still registered");
        h.manager.dispatch(NET, loc(31.0, 121.0, 0));
        check(r.calls == 1 && r.error == null, "a single provider failure still yields a fix");

        H h2 = new H();
        h2.manager.enable(GPS, NET);
        h2.manager.securityOnRequest.add(GPS);
        Result r2 = probe();
        h2.request.request(callback(r2));
        check(h2.manager.active().size() == 1, "a security failure on one provider does not abort the other");
        check(r2.calls == 0, "a security failure on one provider keeps the request pending");
        h2.manager.dispatch(NET, loc(31.0, 121.0, 0));
        check(r2.calls == 1 && r2.error == null, "partial permission denial still succeeds via the granted provider");

        H h3 = new H();
        h3.manager.enable(GPS, NET);
        h3.manager.runtimeOnLastKnown.add(GPS);
        Result r3 = probe();
        h3.request.request(callback(r3));
        check(h3.manager.active().size() == 2, "a broken cache read does not stop listener registration");
        h3.manager.dispatch(GPS, loc(31.0, 121.0, 0));
        check(r3.calls == 1 && r3.error == null, "a broken cache read still allows a live fix");
    }

    static void testPermissionDenied() {
        H h = new H();
        h.manager.enable(GPS, NET);
        h.manager.securityOnRequest.add(GPS);
        h.manager.securityOnRequest.add(NET);
        Result r = probe();
        h.request.request(callback(r));
        check(r.calls == 1 && denied(r), "denial from both providers reports the permission error");
        check(h.manager.active().isEmpty(), "a denied request leaves no registration");
        check(h.handler.pending() == 0, "a denied request leaves no timer");

        H h2 = new H();
        h2.manager.securityOnEnabled.add(GPS);
        h2.manager.securityOnEnabled.add(NET);
        Result r2 = probe();
        h2.request.request(callback(r2));
        check(r2.calls == 1 && denied(r2), "denial while querying providers reports the permission error");
        check(!switchedOff(r2), "a permission error is not reported as the location switch being off");
        check(h2.manager.requestCalls == 0, "no listener is registered after a provider query is denied");

        // Permission errors take priority over the "switch is off" hint when only one provider is denied.
        H h3 = new H();
        h3.manager.securityOnEnabled.add(GPS);
        Result r3 = probe();
        h3.request.request(callback(r3));
        check(r3.calls == 1 && denied(r3), "a denied provider is reported as a permission problem");
        check(!switchedOff(r3), "a disabled second provider does not downgrade the permission message");
    }

    static void testLocationSwitchedOff() {
        H h = new H();
        Result r = probe();
        h.request.request(callback(r));
        check(r.calls == 1 && switchedOff(r), "with every provider disabled the user is asked to enable location");
        check(!denied(r), "a disabled switch is not reported as a permission problem");
        check(h.manager.requestCalls == 0, "nothing is registered while location is off");
        check(h.manager.active().isEmpty(), "no registration survives a switched-off location");
        check(h.handler.pending() == 0, "no timer survives a switched-off location");

        H h2 = new H();
        h2.manager.enable(GPS, NET);
        h2.manager.disableAll();
        Result r2 = probe();
        h2.request.request(callback(r2));
        check(r2.calls == 1 && switchedOff(r2), "disabling every provider after enabling them is detected");
    }

    static void testEnabledButBrokenProviders() {
        H h = new H();
        h.manager.enable(GPS, NET);
        h.manager.runtimeOnRequest.add(GPS);
        h.manager.runtimeOnRequest.add(NET);
        Result r = probe();
        h.request.request(callback(r));
        check(r.calls == 1 && noSource(r), "enabled but broken providers report that no source is usable");
        check(!switchedOff(r), "a broken provider is not reported as the switch being off");
        check(!denied(r), "a broken provider is not reported as a permission problem");
        check(h.manager.active().isEmpty() && h.handler.pending() == 0, "a broken-provider failure leaves no residue");
    }

    /** A ROM that binds the registration and then throws must still be fully cleaned up. */
    static void testPartialRegistrationFailureIsCleanedUp() {
        H h = new H();
        h.manager.enable(GPS);
        h.manager.registerThenThrow.add(GPS);
        Result r = probe();
        h.request.request(callback(r));
        check(r.calls == 1 && denied(r), "a registration that throws after binding is treated as denied");
        check(h.manager.active().isEmpty(), "a partially bound registration is removed");
        check(h.manager.removedListeners().size() == 1, "a partially bound listener is explicitly removed");
        check(h.handler.pending() == 0, "a failed registration leaves no timer");
    }

    /** When the platform refuses to unregister, the generation guard must still block late fixes. */
    static void testRemoveFailureDoesNotBreakCompletion() {
        H h = new H();
        h.manager.enable(GPS);
        h.manager.throwOnRemove = true;
        Result r = probe();
        h.request.request(callback(r));
        h.manager.dispatch(GPS, loc(31.0, 121.0, 0));
        check(r.calls == 1 && r.error == null, "a removeUpdates failure does not swallow the fix");
        check(h.manager.hasActive(), "the stub still holds the registration after a failed removal");
        h.manager.dispatch(GPS, loc(1.0, 1.0, 0));
        check(r.calls == 1, "a registration that could not be removed cannot complete the request twice");
        Result blocked = probe();
        h.request.request(callback(blocked));
        check(blocked.calls == 1 && blocked.error.contains("尚未释放"), "failed cleanup blocks new registrations explicitly");
        check(h.manager.active().size() == 1, "failed removals cannot accumulate orphan listeners");
        check(h.handler.pending() == 0, "blocked cleanup adds no timeout");
        h.manager.throwOnRemove = false;
        Result retry = probe();
        h.request.request(callback(retry));
        check(retry.calls == 0 && h.manager.active().size() == 1, "retry removes the old registration before adding a new one");
        h.manager.dispatch(GPS, loc(31.0, 121.0, 0));
        check(retry.calls == 1 && retry.error == null, "request recovers after removal becomes available");
        check(h.manager.active().isEmpty() && h.handler.pending() == 0, "recovered request fully cleans up");
    }

    // ---------------------------------------------------------------- timing

    static void testTimeoutDeadline() {
        H h = new H();
        h.manager.enable(GPS);
        Result r = probe();
        h.request.request(callback(r));
        check(h.handler.pending() == 1, "exactly one timer is armed per request");
        h.tick(TIMEOUT - 1);
        check(r.calls == 0, "nothing fires one millisecond before the deadline");
        check(h.handler.pending() == 1, "the timer is still armed one millisecond before the deadline");
        check(h.manager.active().size() == 1, "the listener is still registered one millisecond before the deadline");
        h.tick(1);
        check(r.calls == 1 && timedOut(r), "the timeout fires exactly at the deadline");
        check(h.handler.pending() == 0, "the fired timer is not left on the queue");
        check(h.manager.active().isEmpty(), "the listener is removed when the timeout fires");
        check(h.manager.removedListeners().size() == 1, "the listener is explicitly unregistered on timeout");
        check(RealLocationRequest.TIMEOUT_MS == 8000L, "the documented 8s timeout is what is enforced");
        check(RealLocationRequest.MAX_AGE_MS == 60000L, "the documented 60s cache window is what is enforced");
    }

    static void testTenTimeoutsThenRecovery() {
        H h = new H();
        h.manager.enable(GPS);
        for (int i = 0; i < 10; i++) {
            Result r = probe();
            h.request.request(callback(r));
            check(h.handler.pending() == 1, "timeout round " + i + " arms exactly one timer");
            h.tick(TIMEOUT);
            check(r.calls == 1 && timedOut(r), "timeout round " + i + " reports the timeout");
            check(r.errors.size() == 1, "timeout round " + i + " reports exactly one error");
            check(h.handler.pending() == 0, "timeout round " + i + " leaves no timer behind");
            check(h.manager.active().isEmpty(), "timeout round " + i + " leaves no listener behind");
            check(h.manager.removedListeners().size() == i + 1, "timeout round " + i + " unregisters its listener");
        }
        Result r = probe();
        h.request.request(callback(r));
        check(r.calls == 0, "the request after ten timeouts starts pending");
        h.manager.dispatch(GPS, loc(31.2, 121.5, 0));
        check(r.calls == 1 && r.error == null, "the request after ten timeouts recovers and succeeds");
        check(h.handler.pending() == 0, "the recovered request leaves no timer behind");
        check(h.manager.active().isEmpty(), "the recovered request leaves no listener behind");
    }

    static void testTwentyConsecutiveSuccesses() {
        H h = new H();
        h.manager.enable(GPS, NET);
        for (int i = 0; i < 20; i++) {
            Result r = probe();
            h.request.request(callback(r));
            check(r.calls == 0, "success round " + i + " starts pending");
            check(h.manager.active().size() == 2, "success round " + i + " registers both providers");
            h.manager.dispatch(GPS, loc(30.0 + i * 0.001, 120.0, 0));
            check(r.calls == 1 && r.error == null, "success round " + i + " completes with a fix");
            check(h.handler.pending() == 0, "success round " + i + " leaves no timer behind");
            check(h.manager.active().isEmpty(), "success round " + i + " leaves no listener behind");
            check(h.manager.everRegistered().size() == 2 * (i + 1),
                    "success round " + i + " registers exactly two listeners");
            check(h.manager.removedListeners().size() == 2 * (i + 1),
                    "success round " + i + " unregisters exactly the two it registered");
        }
        h.tick(TIMEOUT * 4);
        check(h.handler.pending() == 0, "no timer from the previous twenty rounds ever fires");
        check(h.manager.active().isEmpty(), "no listener from the previous twenty rounds is still live");
    }

    // ---------------------------------------------------------------- lifecycle

    static void testBusyReentrancyKeepsOriginalRequest() {
        H h = new H();
        h.manager.enable(GPS);
        Result first = probe();
        h.request.request(callback(first));
        Result second = probe();
        h.request.request(callback(second));
        check(second.calls == 1 && busy(second), "a second request while busy is rejected immediately");
        check(first.calls == 0, "a busy rejection does not disturb the in-flight request");
        check(h.manager.active().size() == 1, "a busy rejection registers nothing extra");
        check(h.handler.pending() == 1, "a busy rejection does not add a timer");
        Location fix = loc(31.0, 121.0, 0);
        h.manager.dispatch(GPS, fix);
        check(first.calls == 1 && first.location == fix, "the original request still completes after a busy rejection");
        check(second.calls == 1, "the rejected callback fires exactly once");
        check(second.location == null, "the rejected callback receives no fix");
    }

    static void testStaleCallbackAndTimerCannotCompleteNextRequest() {
        H h = new H();
        h.manager.enable(GPS);
        Result a = probe();
        h.request.request(callback(a));
        List<Runnable> armed = h.handler.posted();
        check(armed.size() == 1, "exactly one timer runnable is posted per request");
        Runnable timeoutA = armed.get(0);
        LocationListener listenerA = h.manager.active().get(0).listener;
        h.tick(TIMEOUT);
        check(a.calls == 1 && timedOut(a), "the first request times out");

        Result b = probe();
        h.request.request(callback(b));
        LocationListener listenerB = h.manager.active().get(0).listener;
        check(listenerB != listenerA, "each request gets a fresh listener instance");
        check(h.handler.pending() == 1, "the new request arms its own timer");

        listenerA.onLocationChanged(loc(31.0, 121.0, 0));
        check(b.calls == 0, "a late fix from the previous request cannot complete the new one");
        timeoutA.run();
        check(b.calls == 0, "a stale timeout runnable cannot complete the new one");
        check(h.handler.pending() == 1, "a stale timeout runnable does not disarm the live timer");
        check(h.manager.active().size() == 1, "a stale callback does not remove the live listener");

        Location fix = loc(31.0, 121.0, 0);
        h.manager.dispatch(GPS, fix);
        check(b.calls == 1 && b.location == fix, "the new request completes normally afterwards");
    }

    static void testCloseDropsPendingRequestAndCleansUp() {
        H h = new H();
        h.manager.enable(GPS, NET);
        Result r = probe();
        h.request.request(callback(r));
        check(h.manager.active().size() == 2 && h.handler.pending() == 1, "the request is genuinely in flight");
        h.request.close();
        check(r.calls == 0, "close does not invoke the pending callback");
        check(h.manager.active().isEmpty(), "close unregisters every listener");
        check(h.manager.removedListeners().size() == 2, "close explicitly unregisters each listener");
        check(h.handler.pending() == 0, "close removes the timer");
        h.tick(TIMEOUT * 2);
        check(r.calls == 0, "the timeout can no longer fire after close");

        Result after = probe();
        h.request.request(callback(after));
        check(after.calls == 1 && closed(after), "a request after close reports that the page is closed");
        check(h.manager.active().isEmpty(), "a closed request registers nothing");
        check(h.handler.pending() == 0, "a closed request arms no timer");
        check(h.manager.requestCalls == 2, "only the two in-flight attempts ever reached the platform");

        RealLocationRequest idle = new RealLocationRequest(h.manager, h.handler);
        idle.close();
        check(true, "closing an unused request is harmless");
    }

    static void testCancelIsRetryable() {
        H h = new H();
        h.manager.enable(GPS);
        Result r = probe();
        h.request.request(callback(r));
        h.request.cancel();
        check(r.calls == 1 && cancelled(r), "cancel notifies the pending callback");
        check(h.manager.active().isEmpty(), "cancel unregisters the listener");
        check(h.handler.pending() == 0, "cancel removes the timer");

        Result retry = probe();
        h.request.request(callback(retry));
        check(retry.calls == 0, "a new request can start after cancel");
        check(h.manager.active().size() == 1, "the retry registers a fresh listener");
        h.manager.dispatch(GPS, loc(31.0, 121.0, 0));
        check(retry.calls == 1 && retry.error == null, "the retry succeeds after cancel");

        h.request.cancel();
        check(retry.calls == 1, "cancel after completion does not fire a second callback");
        check(r.calls == 1, "the earlier cancelled callback is not fired again");

        RealLocationRequest fresh = new RealLocationRequest(h.manager, h.handler);
        fresh.cancel();
        Result idle = probe();
        fresh.request(callback(idle));
        check(idle.calls == 0, "cancel with nothing pending does not consume the request");
        check(h.manager.active().size() == 1, "a request after a no-op cancel still registers a listener");
        h.manager.dispatch(GPS, loc(31.0, 121.0, 0));
        check(idle.calls == 1 && idle.error == null, "a request after a no-op cancel still succeeds");
        fresh.close();
    }

    static void testMissingSystemService() {
        Handler handler = new Handler(Looper.getMainLooper());
        RealLocationRequest request = new RealLocationRequest(null, handler);
        Result r = probe();
        request.request(callback(r));
        check(r.calls == 1 && unavailable(r), "a missing system service is reported immediately");
        check(r.location == null, "a missing system service reports no fix");
        check(handler.pending() == 0, "a missing system service arms no timer");
        request.close();
        check(handler.pending() == 0, "closing after a missing service is harmless");
        Result after = probe();
        request.request(callback(after));
        check(after.calls == 1 && closed(after), "a closed request still refuses to run without a service");
    }

    // ---------------------------------------------------------------- age

    static void testMonotonicAge() {
        Location fix = loc(0, 0, 5000);
        check(RealLocationRequest.ageMillis(fix) == 5000L, "age is measured from the monotonic clock");
        SystemClock.tick += 1000;
        long later = RealLocationRequest.ageMillis(fix);
        check(later == 6000L, "age grows by exactly the elapsed time");
        check(later > 5000L, "age is monotonically increasing");
        check(RealLocationRequest.usable(fix), "the fix is still usable at six seconds");

        fix.setTime(0L);
        check(RealLocationRequest.ageMillis(fix) == later, "an absurd wall clock is ignored while a monotonic stamp exists");
        fix.setTime(System.currentTimeMillis() + 3600000L);
        check(RealLocationRequest.ageMillis(fix) == later, "a wall clock in the future is ignored as well");

        Location wallOnly = new Location("test");
        wallOnly.setLatitude(1.0);
        wallOnly.setLongitude(1.0);
        wallOnly.setTime(System.currentTimeMillis() - 7000L);
        long fallback = RealLocationRequest.ageMillis(wallOnly);
        check(fallback >= 7000L && fallback <= 7100L, "a fix without a monotonic stamp falls back to the wall clock");
        check(RealLocationRequest.usable(wallOnly), "a fresh wall-clock-only fix is usable");
        wallOnly.setTime(System.currentTimeMillis() - (MAX_AGE + 1000L));
        check(!RealLocationRequest.usable(wallOnly), "a stale wall-clock-only fix is rejected");

        Location zero = loc(0, 0, 0);
        zero.setElapsedRealtimeNanos(0L);
        zero.setTime(System.currentTimeMillis());
        long absent = RealLocationRequest.ageMillis(zero);
        check(absent >= 0L && absent <= 2L, "a zero monotonic stamp falls back to the wall clock instead of reading as ~11 days");
    }

    // ---------------------------------------------------------------- main

    public static void main(String[] args) {
        testValidCacheCompletesImmediately();
        testYoungestCacheWins();
        testCacheRejectsUnusableFixes();
        testUsabilityBoundaries();
        testLiveCallbackRejectsUnusableFixes();
        testSeparateListenerPerProvider();
        testSimultaneousProvidersCompleteOnce();
        testSingleProviderFailureStillSucceeds();
        testPermissionDenied();
        testLocationSwitchedOff();
        testEnabledButBrokenProviders();
        testPartialRegistrationFailureIsCleanedUp();
        testRemoveFailureDoesNotBreakCompletion();
        testTimeoutDeadline();
        testTenTimeoutsThenRecovery();
        testTwentyConsecutiveSuccesses();
        testBusyReentrancyKeepsOriginalRequest();
        testStaleCallbackAndTimerCannotCompleteNextRequest();
        testCloseDropsPendingRequestAndCleansUp();
        testCancelIsRetryable();
        testMissingSystemService();
        testMonotonicAge();
        System.out.println("LocationRequestTest: " + count + " assertions passed");
    }
}
