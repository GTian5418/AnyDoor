package com.zyx.anydoor;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * Foreground service that drives the spoof: keeps the "started" flag, pushes mock fixes through
 * test providers (so apps get updates even without a real GPS fix), runs route simulation and the
 * floating joystick.
 */
public class SpoofService extends Service {
    private static final String TAG = "AnyDoor";
    public static final String ACTION_START = Keys.PKG + ".START";
    public static final String ACTION_STOP = Keys.PKG + ".STOP";
    public static final String ACTION_JOY = Keys.PKG + ".JOY";
    private static final String CHANNEL = "spoof";
    private static final int NOTIF_ID = 1;

    static volatile SpoofService instance;

    private LocationManager lm;
    private Handler ui;
    private Handler bg;
    private HandlerThread bgThread;
    private volatile boolean running;
    private boolean providersAdded;
    private volatile String mockError = "";
    private volatile long lastPush;

    // base position (WGS-84) – the un-jittered target
    private double baseLat, baseLng, alt, acc, speed, bearing;
    private long lastTick;

    // route simulation
    private double[][] route;
    private double routeSpeed;
    private boolean routeLoop;
    private volatile boolean routeActive;
    private int routeSeg;
    private double routeOffset;
    private double routeTotal;

    // joystick
    private JoystickOverlay joystick;
    private volatile float joyX, joyY;
    private double joySpeed = 1.5;
    private long lastConfigWrite;

    public static boolean isRunning() {
        SpoofService s = instance;
        return s != null && s.running;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        ui = new Handler(Looper.getMainLooper());
        bgThread = new HandlerThread("anydoor-bg");
        bgThread.start();
        bg = new Handler(bgThread.getLooper());
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSpoof();
        } else if (ACTION_JOY.equals(action)) {
            setJoystickVisible(!(joystick != null && joystick.isShown()));
        } else {
            startSpoof();
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        running = false;
        removeProviders();
        setJoystickVisible(false);
        bgThread.quitSafely();
        instance = null;
        super.onDestroy();
    }

    // ------------------------------------------------------------------ lifecycle

    private void startSpoof() {
        Config.ensureDefaults(this);
        SharedPreferences c = Config.config(this);
        baseLat = Config.num(c, Keys.LAT, 39.908722);
        baseLng = Config.num(c, Keys.LNG, 116.397499);
        reloadParams(c);
        c.edit().putBoolean(Keys.STARTED, true).commit();
        startForeground(NOTIF_ID, buildNotification());
        if (running) return;
        running = true;
        lastTick = SystemClock.elapsedRealtime();
        bg.post(new Runnable() {
            @Override
            public void run() {
                ensureMockPermission();
                addProviders();
            }
        });
        ui.removeCallbacks(ticker);
        ui.post(ticker);
    }

    private void stopSpoof() {
        running = false;
        routeActive = false;
        ui.removeCallbacks(ticker);
        Config.config(this).edit().putBoolean(Keys.STARTED, false)
                .putString(Keys.SPEED, "0").commit();
        setJoystickVisible(false);
        removeProviders();
        stopForeground(true);
        stopSelf();
    }

    /** Re-read tunables that the UI may change while running. */
    private void reloadParams(SharedPreferences c) {
        alt = Config.num(c, Keys.ALT, 50);
        acc = Config.num(c, Keys.ACC, 8);
        if (!routeActive && joyX == 0 && joyY == 0) {
            speed = Config.num(c, Keys.SPEED, 0);
            bearing = Config.num(c, Keys.BEARING, 0);
        }
        try {
            joySpeed = Double.parseDouble(Config.app(this).getString(Keys.JOY_SPEED, "1.5"));
        } catch (Exception ignored) {
        }
    }

    /** Called by the UI when the user picks a new target. */
    public void setBase(double lat, double lng) {
        baseLat = lat;
        baseLng = lng;
        routeActive = false;
        writeConfig(true);
        pushNow();
    }

    // ------------------------------------------------------------------ ticking

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            long now = SystemClock.elapsedRealtime();
            double dt = Math.min(2.0, (now - lastTick) / 1000.0);
            lastTick = now;
            SharedPreferences c = Config.config(SpoofService.this);
            reloadParams(c);
            boolean moving = false;
            if (joyX != 0 || joyY != 0) {
                double mag = Math.min(1.0, Math.sqrt(joyX * joyX + joyY * joyY));
                double v = joySpeed * mag;
                bearing = (Math.toDegrees(Math.atan2(joyX, joyY)) + 360) % 360;
                double[] p = GeoMath.destination(baseLat, baseLng, bearing, v * dt);
                baseLat = p[0];
                baseLng = p[1];
                speed = v;
                moving = true;
            } else if (routeActive) {
                advanceRoute(dt);
                moving = true;
            } else if (!c.getString(Keys.LAT, "").equals(fmt(baseLat)) || !c.getString(Keys.LNG, "").equals(fmt(baseLng))) {
                // UI (or another component) changed the target in prefs
                baseLat = Config.num(c, Keys.LAT, baseLat);
                baseLng = Config.num(c, Keys.LNG, baseLng);
            }
            if (moving) writeConfig(false);
            pushNow();
            int interval = Math.max(200, c.getInt(Keys.INTERVAL, Keys.DEFAULT_INTERVAL));
            ui.postDelayed(this, moving ? Math.min(interval, 250) : interval);
        }
    };

    private static String fmt(double d) {
        return String.valueOf(d);
    }

    private void writeConfig(boolean force) {
        long now = SystemClock.elapsedRealtime();
        if (!force && now - lastConfigWrite < 200) return;
        lastConfigWrite = now;
        Config.config(this).edit()
                .putString(Keys.LAT, fmt(baseLat)).putString(Keys.LNG, fmt(baseLng))
                .putString(Keys.SPEED, fmt(speed)).putString(Keys.BEARING, fmt(bearing))
                .commit();
    }

    /** Current (jittered) fix, identical to what the hooks compute for this time bucket. */
    private double[] jittered() {
        SharedPreferences c = Config.config(this);
        double jitter = Config.num(c, Keys.JITTER, 0);
        int interval = Math.max(200, c.getInt(Keys.INTERVAL, Keys.DEFAULT_INTERVAL));
        long bucket = System.currentTimeMillis() / interval;
        return GeoMath.jitter(baseLat, baseLng, jitter, c.getLong(Keys.SEED, 0), bucket);
    }

    private void pushNow() {
        if (!running || !providersAdded) return;
        double[] p = jittered();
        SharedPreferences c = Config.config(this);
        boolean net = c.getBoolean(Keys.MOCK_NETWORK, true);
        push(LocationManager.GPS_PROVIDER, p[0], p[1]);
        if (net) push(LocationManager.NETWORK_PROVIDER, p[0], p[1]);
        lastPush = System.currentTimeMillis();
    }

    private void push(String provider, double lat, double lng) {
        try {
            Location l = new Location(provider);
            l.setLatitude(lat);
            l.setLongitude(lng);
            l.setAltitude(alt);
            l.setAccuracy((float) (LocationManager.NETWORK_PROVIDER.equals(provider) ? Math.max(acc, 20) : acc));
            l.setSpeed((float) speed);
            l.setBearing((float) bearing);
            l.setTime(System.currentTimeMillis());
            l.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
            if (Build.VERSION.SDK_INT >= 26) {
                l.setVerticalAccuracyMeters(3f);
                l.setSpeedAccuracyMetersPerSecond(0.5f);
                l.setBearingAccuracyDegrees(speed > 0.3 ? 10f : 90f);
            }
            lm.setTestProviderLocation(provider, l);
            mockError = "";
        } catch (Throwable t) {
            mockError = String.valueOf(t);
            Log.w(TAG, "setTestProviderLocation " + provider + ": " + t);
        }
    }

    // ------------------------------------------------------------------ mock providers

    private void ensureMockPermission() {
        try {
            RootShell.Result r = RootShell.run("appops get " + Keys.PKG + " android:mock_location");
            if (r.out.contains("allow")) return;
            RootShell.run("appops set " + Keys.PKG + " android:mock_location allow");
        } catch (Throwable t) {
            Log.w(TAG, "appops: " + t);
        }
    }

    private void addProviders() {
        if (!Config.config(this).getBoolean(Keys.MOCK_DRIVER, true)) {
            providersAdded = false;
            return;
        }
        boolean ok = true;
        for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
            try {
                try {
                    lm.removeTestProvider(p);
                } catch (Throwable ignored) {
                }
                boolean gps = LocationManager.GPS_PROVIDER.equals(p);
                lm.addTestProvider(p, !gps, gps, !gps, false, true, true, true,
                        gps ? Criteria.POWER_HIGH : Criteria.POWER_MEDIUM,
                        gps ? Criteria.ACCURACY_FINE : Criteria.ACCURACY_COARSE);
                lm.setTestProviderEnabled(p, true);
                mockError = "";
            } catch (Throwable t) {
                ok = false;
                mockError = String.valueOf(t);
                Log.w(TAG, "addTestProvider " + p + ": " + t);
            }
        }
        providersAdded = ok;
        Config.app(this).edit().putString(Keys.MOCK_ERROR, ok ? "" : mockError).apply();
        if (ok) ui.post(new Runnable() {
            @Override
            public void run() {
                pushNow();
            }
        });
    }

    private void removeProviders() {
        if (!providersAdded) return;
        providersAdded = false;
        for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
            try {
                lm.removeTestProvider(p);
            } catch (Throwable ignored) {
            }
        }
    }

    // ------------------------------------------------------------------ route

    /** @param json {"points":[{"lat":..,"lng":..},...],"speed":m/s,"loop":bool} */
    public boolean startRoute(String json) {
        try {
            JSONObject o = new JSONObject(json);
            JSONArray pts = o.getJSONArray("points");
            if (pts.length() < 2) return false;
            double[][] r = new double[pts.length()][2];
            routeTotal = 0;
            for (int i = 0; i < pts.length(); i++) {
                JSONObject p = pts.getJSONObject(i);
                r[i][0] = p.getDouble("lat");
                r[i][1] = p.getDouble("lng");
                if (i > 0) routeTotal += GeoMath.distance(r[i - 1][0], r[i - 1][1], r[i][0], r[i][1]);
            }
            route = r;
            routeSpeed = Math.max(0.1, o.optDouble("speed", 1.4));
            routeLoop = o.optBoolean("loop", false);
            routeSeg = 0;
            routeOffset = 0;
            baseLat = r[0][0];
            baseLng = r[0][1];
            bearing = GeoMath.bearing(r[0][0], r[0][1], r[1][0], r[1][1]);
            speed = routeSpeed;
            routeActive = true;
            joyX = joyY = 0;
            writeConfig(true);
            if (!running) startSpoof();
            else pushNow();
            return true;
        } catch (Exception e) {
            Log.w(TAG, "route: " + e);
            return false;
        }
    }

    public void stopRoute() {
        routeActive = false;
        speed = 0;
        writeConfig(true);
    }

    private void advanceRoute(double dt) {
        double[][] r = route;
        if (r == null || routeSeg >= r.length - 1) {
            routeActive = false;
            return;
        }
        double remaining = routeSpeed * dt;
        while (remaining > 0) {
            double[] a = r[routeSeg], b = r[routeSeg + 1];
            double segLen = GeoMath.distance(a[0], a[1], b[0], b[1]);
            double left = segLen - routeOffset;
            if (remaining < left) {
                routeOffset += remaining;
                remaining = 0;
            } else {
                remaining -= left;
                routeOffset = 0;
                routeSeg++;
                if (routeSeg >= r.length - 1) {
                    if (routeLoop) {
                        routeSeg = 0;
                    } else {
                        baseLat = r[r.length - 1][0];
                        baseLng = r[r.length - 1][1];
                        routeActive = false;
                        speed = 0;
                        return;
                    }
                }
            }
        }
        double[] a = r[routeSeg], b = r[routeSeg + 1];
        bearing = GeoMath.bearing(a[0], a[1], b[0], b[1]);
        double[] p = GeoMath.destination(a[0], a[1], bearing, routeOffset);
        baseLat = p[0];
        baseLng = p[1];
        speed = routeSpeed;
    }

    // ------------------------------------------------------------------ joystick

    public void setJoystickVisible(boolean show) {
        if (show) {
            if (!Settings.canDrawOverlays(this)) {
                RootShell.run("appops set " + Keys.PKG + " SYSTEM_ALERT_WINDOW allow");
            }
            if (!Settings.canDrawOverlays(this)) {
                Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:" + Keys.PKG));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    startActivity(i);
                } catch (Throwable ignored) {
                }
                return;
            }
            if (joystick == null) joystick = new JoystickOverlay(this, new JoystickOverlay.Listener() {
                @Override
                public void onMove(float x, float y) {
                    joyX = x;
                    joyY = y;
                    if (x == 0 && y == 0) {
                        speed = 0;
                        writeConfig(true);
                    } else if (running) {
                        ui.removeCallbacks(ticker);
                        ui.post(ticker);
                    }
                }

                @Override
                public void onSpeedChanged(double metersPerSecond) {
                    joySpeed = metersPerSecond;
                    Config.app(SpoofService.this).edit().putString(Keys.JOY_SPEED, String.valueOf(metersPerSecond)).apply();
                }

                @Override
                public void onClose() {
                    setJoystickVisible(false);
                }
            });
            joystick.show(joySpeed);
            if (!running) startSpoof();
        } else {
            joyX = joyY = 0;
            if (joystick != null) joystick.hide();
        }
    }

    // ------------------------------------------------------------------ status

    public JSONObject status() {
        JSONObject o = new JSONObject();
        try {
            double[] p = running ? jittered() : new double[]{baseLat, baseLng};
            o.put("running", running);
            o.put("lat", baseLat).put("lng", baseLng);
            o.put("curLat", p[0]).put("curLng", p[1]);
            o.put("speed", speed).put("bearing", bearing);
            o.put("routeActive", routeActive);
            if (route != null) {
                double done = 0;
                for (int i = 0; i < routeSeg && i < route.length - 1; i++)
                    done += GeoMath.distance(route[i][0], route[i][1], route[i + 1][0], route[i + 1][1]);
                done += routeOffset;
                o.put("routeDone", done).put("routeTotal", routeTotal).put("routeSeg", routeSeg);
            }
            o.put("joystick", joystick != null && joystick.isShown());
            o.put("providers", providersAdded);
            o.put("mockError", mockError);
            o.put("lastPush", lastPush);
        } catch (Exception ignored) {
        }
        return o;
    }

    // ------------------------------------------------------------------ notification

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "位置模拟", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, flags);
        Intent stop = new Intent(this, SpoofService.class).setAction(ACTION_STOP);
        PendingIntent ps = PendingIntent.getService(this, 1, stop, flags);
        Intent joy = new Intent(this, SpoofService.class).setAction(ACTION_JOY);
        PendingIntent pj = PendingIntent.getService(this, 2, joy, flags);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("任意门 · 位置模拟中")
                .setContentText(String.format(Locale.US, "%.6f, %.6f", baseLat, baseLng))
                .setContentIntent(pi)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder((android.graphics.drawable.Icon) null, "摇杆", pj).build())
                .addAction(new Notification.Action.Builder((android.graphics.drawable.Icon) null, "停止", ps).build());
        return b.build();
    }

    void refreshNotification() {
        if (!running) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTIF_ID, buildNotification());
    }
}
