package io.github.zhaoyuxiangyyds_lab.anydoor;

import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;

/** One bounded, non-blocking request. All methods and callbacks run on the supplied handler's looper. */
final class RealLocationRequest {
    static final long TIMEOUT_MS = 8000;
    static final long MAX_AGE_MS = 60000;
    interface Callback { void complete(Location location, String error); }

    private final LocationManager manager;
    private final Handler handler;
    private final List<LocationListener> listeners = new ArrayList<>();
    private Callback pending;
    private Runnable timeout;
    private long generation;
    private boolean closed;

    RealLocationRequest(LocationManager manager, Handler handler) {
        this.manager = manager;
        this.handler = handler;
    }

    void request(Callback callback) {
        if (closed) { callback.complete(null, "页面已关闭，请重新打开"); return; }
        if (pending != null) { callback.complete(null, "正在定位，请稍候再试"); return; }
        // Retain failed removals and retry before registering again; never accumulate orphan listeners.
        removeListeners();
        if (!listeners.isEmpty()) {
            callback.complete(null, "上一次定位监听尚未释放，请稍后重试");
            return;
        }
        pending = callback;
        final long token = ++generation;
        if (manager == null) { finish(null, "系统定位服务不可用"); return; }
        timeout = () -> {
            if (pending != null && token == generation)
                finish(null, "定位超时：请到窗边/室外，并确认已开启定位与 WiFi 后重试");
        };
        handler.postDelayed(timeout, TIMEOUT_MS);
        int registered = 0;
        boolean enabled = false;
        boolean denied = false;
        Location cached = null;
        List<String> providers = new ArrayList<>();
        for (String provider : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
            try {
                if (!manager.isProviderEnabled(provider)) continue;
                enabled = true;
                providers.add(provider);
                Location location = manager.getLastKnownLocation(provider);
                if (usable(location) && (cached == null || ageMillis(location) < ageMillis(cached))) cached = location;
            } catch (SecurityException e) { denied = true; }
            catch (RuntimeException ignored) { /* Another provider can still work. */ }
        }
        if (cached != null) { finish(cached, null); return; }
        for (String provider : providers) {
            if (pending == null || token != generation) break;
            // A separate listener for each provider avoids replacing a shared registration on some ROMs.
            LocationListener listener = new LocationListener() {
                @Override public void onLocationChanged(Location location) {
                    if (pending != null && token == generation && usable(location)) finish(location, null);
                }
                @Override public void onProviderDisabled(String provider) { }
                @Override public void onProviderEnabled(String provider) { }
                @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
            };
            listeners.add(listener); // Track even a registration that partially succeeds before throwing.
            try {
                manager.requestLocationUpdates(provider, 0L, 0f, listener, handler.getLooper());
                registered++;
            } catch (SecurityException e) { denied = true; }
            catch (RuntimeException ignored) { /* Do not stop the other provider. */ }
        }
        if (pending != null && registered == 0) {
            finish(null, denied ? "请在系统设置中授予任意门定位权限后重试"
                    : !enabled ? "请先开启系统定位（GPS 或网络定位）"
                    : "暂无可用定位源，请确认定位设置后重试");
        }
    }

    static long ageMillis(Location location) {
        long nanos = location.getElapsedRealtimeNanos();
        return nanos > 0 ? (SystemClock.elapsedRealtimeNanos() - nanos) / 1000000L
                : System.currentTimeMillis() - location.getTime();
    }

    static boolean usable(Location location) {
        if (location == null || location.isFromMockProvider()) return false;
        double lat = location.getLatitude(), lng = location.getLongitude();
        long age = ageMillis(location);
        return Double.isFinite(lat) && Double.isFinite(lng) && Math.abs(lat) <= 90 && Math.abs(lng) <= 180
                && age >= 0 && age <= MAX_AGE_MS;
    }

    void cancel() {
        if (pending != null) finish(null, "定位已取消，请停止模拟后重试");
    }

    void close() {
        closed = true;
        pending = null;
        cleanup();
    }

    private void finish(Location location, String error) {
        Callback callback = pending;
        pending = null;
        cleanup();
        if (callback != null) callback.complete(location, error);
    }

    private void cleanup() {
        generation++; // Ignore late callbacks from a previous request, even during the next request.
        if (timeout != null) handler.removeCallbacks(timeout);
        timeout = null;
        removeListeners();
    }

    private void removeListeners() {
        for (java.util.Iterator<LocationListener> it = listeners.iterator(); it.hasNext();) {
            try { manager.removeUpdates(it.next()); it.remove(); }
            catch (RuntimeException ignored) { /* Retry on the next request or close, without adding more. */ }
        }
    }
}
