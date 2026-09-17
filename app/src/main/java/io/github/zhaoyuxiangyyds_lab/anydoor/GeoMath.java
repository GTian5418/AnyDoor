package io.github.zhaoyuxiangyyds_lab.anydoor;

import java.util.Random;

/** WGS-84 / GCJ-02 / BD-09 conversions and small geodesy helpers. Pure Java, shared by app and hooks. */
public final class GeoMath {
    private GeoMath() {}

    private static final double PI = Math.PI;
    private static final double A = 6378245.0;
    private static final double EE = 0.00669342162296594323;
    private static final double X_PI = PI * 3000.0 / 180.0;
    public static final double EARTH_R = 6371008.8;

    public static boolean outOfChina(double lat, double lng) {
        return lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271;
    }

    private static double transformLat(double x, double y) {
        double ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(y * PI) + 40.0 * Math.sin(y / 3.0 * PI)) * 2.0 / 3.0;
        ret += (160.0 * Math.sin(y / 12.0 * PI) + 320 * Math.sin(y * PI / 30.0)) * 2.0 / 3.0;
        return ret;
    }

    private static double transformLng(double x, double y) {
        double ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(x * PI) + 40.0 * Math.sin(x / 3.0 * PI)) * 2.0 / 3.0;
        ret += (150.0 * Math.sin(x / 12.0 * PI) + 300.0 * Math.sin(x / 30.0 * PI)) * 2.0 / 3.0;
        return ret;
    }

    /** @return {lat, lng} in GCJ-02 */
    public static double[] wgs2gcj(double lat, double lng) {
        if (outOfChina(lat, lng)) return new double[]{lat, lng};
        double dLat = transformLat(lng - 105.0, lat - 35.0);
        double dLng = transformLng(lng - 105.0, lat - 35.0);
        double radLat = lat / 180.0 * PI;
        double magic = Math.sin(radLat);
        magic = 1 - EE * magic * magic;
        double sqrtMagic = Math.sqrt(magic);
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI);
        dLng = (dLng * 180.0) / (A / sqrtMagic * Math.cos(radLat) * PI);
        return new double[]{lat + dLat, lng + dLng};
    }

    /** @return {lat, lng} in WGS-84 (iterative inverse, ~1e-7 deg) */
    public static double[] gcj2wgs(double lat, double lng) {
        if (outOfChina(lat, lng)) return new double[]{lat, lng};
        double wLat = lat, wLng = lng;
        for (int i = 0; i < 6; i++) {
            double[] g = wgs2gcj(wLat, wLng);
            wLat -= (g[0] - lat);
            wLng -= (g[1] - lng);
        }
        return new double[]{wLat, wLng};
    }

    public static double[] gcj2bd(double lat, double lng) {
        double z = Math.sqrt(lng * lng + lat * lat) + 0.00002 * Math.sin(lat * X_PI);
        double theta = Math.atan2(lat, lng) + 0.000003 * Math.cos(lng * X_PI);
        return new double[]{z * Math.sin(theta) + 0.006, z * Math.cos(theta) + 0.0065};
    }

    public static double[] bd2gcj(double lat, double lng) {
        double x = lng - 0.0065, y = lat - 0.006;
        double z = Math.sqrt(x * x + y * y) - 0.00002 * Math.sin(y * X_PI);
        double theta = Math.atan2(y, x) - 0.000003 * Math.cos(x * X_PI);
        return new double[]{z * Math.sin(theta), z * Math.cos(theta)};
    }

    public static double[] bd2wgs(double lat, double lng) {
        double[] g = bd2gcj(lat, lng);
        return gcj2wgs(g[0], g[1]);
    }

    public static double[] wgs2bd(double lat, double lng) {
        double[] g = wgs2gcj(lat, lng);
        return gcj2bd(g[0], g[1]);
    }

    /** Haversine distance in meters. */
    public static double distance(double lat1, double lng1, double lat2, double lng2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dp = Math.toRadians(lat2 - lat1), dl = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * EARTH_R * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /** Initial bearing in degrees [0,360). */
    public static double bearing(double lat1, double lng1, double lat2, double lng2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2), dl = Math.toRadians(lng2 - lng1);
        double y = Math.sin(dl) * Math.cos(p2);
        double x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
        double b = Math.toDegrees(Math.atan2(y, x));
        return (b + 360.0) % 360.0;
    }

    /** Destination point given start, bearing (deg) and distance (m). @return {lat,lng} */
    public static double[] destination(double lat, double lng, double bearingDeg, double distM) {
        double d = distM / EARTH_R, b = Math.toRadians(bearingDeg);
        double p1 = Math.toRadians(lat), l1 = Math.toRadians(lng);
        double p2 = Math.asin(Math.sin(p1) * Math.cos(d) + Math.cos(p1) * Math.sin(d) * Math.cos(b));
        double l2 = l1 + Math.atan2(Math.sin(b) * Math.sin(d) * Math.cos(p1), Math.cos(d) - Math.sin(p1) * Math.sin(p2));
        return new double[]{Math.toDegrees(p2), ((Math.toDegrees(l2) + 540) % 360) - 180};
    }

    /** Move by dx (east, m) and dy (north, m). @return {lat,lng} */
    public static double[] offset(double lat, double lng, double dxEast, double dyNorth) {
        double dLat = dyNorth / 111320.0;
        double dLng = dxEast / (111320.0 * Math.cos(Math.toRadians(lat)));
        return new double[]{lat + dLat, lng + dLng};
    }

    /**
     * Deterministic random jitter: same (seed, bucket) always yields the same offset, so every process
     * (system_server, hooked apps, our service) agrees on the position within a time bucket.
     * @return {lat,lng}
     */
    public static double[] jitter(double lat, double lng, double radiusM, long seed, long bucket) {
        if (radiusM <= 0) return new double[]{lat, lng};
        Random r = new Random(seed * 1000003L + bucket * 7919L);
        double u = r.nextDouble(), v = r.nextDouble();
        double dist = radiusM * Math.sqrt(u);
        double ang = 2 * PI * v;
        return offset(lat, lng, dist * Math.cos(ang), dist * Math.sin(ang));
    }
}
