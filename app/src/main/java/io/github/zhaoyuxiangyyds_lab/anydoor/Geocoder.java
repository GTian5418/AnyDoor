package io.github.zhaoyuxiangyyds_lab.anydoor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/** Place search / reverse geocoding over Amap (needs a free key) and Nominatim (no key). All results WGS-84. */
public final class Geocoder {
    private Geocoder() {}

    public static final String UA = "AnyDoor/1.0 (Android location tool)";
    public static final String DEFAULT_NOMINATIM = "https://nominatim.openstreetmap.org";

    public static String http(String url, int timeoutMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.5");
        try {
            int code = c.getResponseCode();
            BufferedReader br = new BufferedReader(new InputStreamReader(
                    code >= 400 ? c.getErrorStream() : c.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            if (code >= 400) throw new Exception("HTTP " + code + ": " + sb.toString().trim());
            return sb.toString();
        } finally {
            c.disconnect();
        }
    }

    private static String enc(String s) throws Exception {
        return URLEncoder.encode(s, "UTF-8");
    }

    /** @return JSON {ok, source, items:[{name,address,lat,lng}], error} */
    public static JSONObject search(String query, String source, String amapKey, String nominatimBase, double nearLat, double nearLng) {
        JSONObject out = new JSONObject();
        JSONArray items = new JSONArray();
        String used = "";
        StringBuilder errors = new StringBuilder();
        boolean tryAmap = ("auto".equals(source) || "amap".equals(source)) && amapKey != null && !amapKey.trim().isEmpty();
        boolean tryNomi = "auto".equals(source) || "nominatim".equals(source);
        if (tryAmap) {
            try {
                amapSearch(query, amapKey.trim(), items);
                used = "amap";
            } catch (Exception e) {
                errors.append("高德: ").append(e.getMessage()).append("\n");
            }
        }
        if (items.length() == 0 && tryNomi) {
            try {
                nominatimSearch(query, nominatimBase, nearLat, nearLng, items);
                used = "nominatim";
            } catch (Exception e) {
                errors.append("Nominatim: ").append(e.getMessage()).append("\n");
            }
        }
        try {
            out.put("ok", items.length() > 0);
            out.put("source", used);
            out.put("items", items);
            out.put("error", errors.toString().trim());
        } catch (Exception ignored) {
        }
        return out;
    }

    private static void amapSearch(String q, String key, JSONArray items) throws Exception {
        String url = "https://restapi.amap.com/v3/place/text?key=" + enc(key) + "&keywords=" + enc(q)
                + "&offset=20&page=1&extensions=base&output=JSON";
        JSONObject o = new JSONObject(http(url, 10000));
        if (!"1".equals(o.optString("status"))) throw new Exception(o.optString("info", "请求失败"));
        JSONArray pois = o.optJSONArray("pois");
        if (pois == null) return;
        for (int i = 0; i < pois.length(); i++) {
            JSONObject p = pois.getJSONObject(i);
            String loc = p.optString("location", "");
            if (!loc.contains(",")) continue;
            String[] ll = loc.split(",");
            double lng = Double.parseDouble(ll[0]), lat = Double.parseDouble(ll[1]);
            double[] w = GeoMath.gcj2wgs(lat, lng);
            String addr = (p.optString("pname", "") + p.optString("cityname", "") + p.optString("adname", "")
                    + " " + p.optString("address", "")).trim();
            items.put(new JSONObject().put("name", p.optString("name")).put("address", addr)
                    .put("lat", w[0]).put("lng", w[1]).put("source", "amap"));
        }
    }

    private static void nominatimSearch(String q, String base, double nearLat, double nearLng, JSONArray items) throws Exception {
        if (base == null || base.trim().isEmpty()) base = DEFAULT_NOMINATIM;
        String url = base.replaceAll("/+$", "") + "/search?format=jsonv2&limit=20&addressdetails=1&accept-language=zh-CN,zh&q=" + enc(q);
        if (nearLat != 0 || nearLng != 0) {
            double d = 1.5;
            url += "&viewbox=" + (nearLng - d) + "," + (nearLat + d) + "," + (nearLng + d) + "," + (nearLat - d);
        }
        JSONArray arr = new JSONArray(http(url, 12000));
        for (int i = 0; i < arr.length(); i++) {
            JSONObject r = arr.getJSONObject(i);
            String display = r.optString("display_name", "");
            String name = r.optString("name", "");
            if (name.isEmpty()) name = display.split(",")[0].trim();
            items.put(new JSONObject().put("name", name).put("address", display)
                    .put("lat", r.getDouble("lat")).put("lng", r.getDouble("lon")).put("source", "osm"));
        }
    }

    /** @return JSON {ok, address, source, error} for a WGS-84 point */
    public static JSONObject reverse(double lat, double lng, String source, String amapKey, String nominatimBase) {
        JSONObject out = new JSONObject();
        String addr = null, used = "";
        StringBuilder errors = new StringBuilder();
        boolean tryAmap = ("auto".equals(source) || "amap".equals(source)) && amapKey != null && !amapKey.trim().isEmpty();
        boolean tryNomi = "auto".equals(source) || "nominatim".equals(source);
        if (tryAmap) {
            try {
                double[] g = GeoMath.wgs2gcj(lat, lng);
                String url = "https://restapi.amap.com/v3/geocode/regeo?key=" + enc(amapKey.trim())
                        + "&location=" + String.format(java.util.Locale.US, "%.6f,%.6f", g[1], g[0]) + "&extensions=base&output=JSON";
                JSONObject o = new JSONObject(http(url, 10000));
                if (!"1".equals(o.optString("status"))) throw new Exception(o.optString("info", "请求失败"));
                JSONObject rg = o.optJSONObject("regeocode");
                if (rg != null) {
                    String a = rg.optString("formatted_address", "");
                    if (!a.isEmpty() && !"[]".equals(a)) {
                        addr = a;
                        used = "amap";
                    }
                }
            } catch (Exception e) {
                errors.append("高德: ").append(e.getMessage()).append("\n");
            }
        }
        if (addr == null && tryNomi) {
            try {
                if (nominatimBase == null || nominatimBase.trim().isEmpty()) nominatimBase = DEFAULT_NOMINATIM;
                String url = nominatimBase.replaceAll("/+$", "") + "/reverse?format=jsonv2&zoom=18&accept-language=zh-CN,zh&lat="
                        + lat + "&lon=" + lng;
                JSONObject o = new JSONObject(http(url, 12000));
                String a = o.optString("display_name", "");
                if (!a.isEmpty()) {
                    addr = a;
                    used = "nominatim";
                }
            } catch (Exception e) {
                errors.append("Nominatim: ").append(e.getMessage()).append("\n");
            }
        }
        try {
            out.put("ok", addr != null);
            out.put("address", addr == null ? "" : addr);
            out.put("source", used);
            out.put("error", errors.toString().trim());
        } catch (Exception ignored) {
        }
        return out;
    }

    // ------------------------------------------------------------------ route planning

    /** HTTP GET used by route planning; replaced by host tests with canned Amap responses. */
    public interface Fetcher {
        String get(String url, int timeoutMs) throws Exception;
    }

    public static volatile Fetcher fetcher = new Fetcher() {
        @Override
        public String get(String url, int timeoutMs) throws Exception {
            return http(url, timeoutMs);
        }
    };

    /** Most alternatives offered per plan (Amap returns up to three). */
    public static final int MAX_ROUTES = 3;

    /**
     * Plan road-following routes through the given WGS-84 points with Amap's direction API.
     * Consecutive points are routed pairwise so every mode supports intermediate waypoints
     * (途经点). Amap's alternatives are kept: variant k of the whole route takes the k-th
     * alternative of every leg (the last one when a leg has fewer), so the user can pick another
     * road instead of the default recommendation.
     *
     * @param mode walking | running | bicycling | driving (running uses the walking network)
     * @return {ok, mode, routes:[{points:[{lat,lng,s}], distance, duration, label}], points, distance,
     * duration, error}; s=1 marks a turn / intersection; points/distance/duration repeat routes[0]
     */
    public static JSONObject route(String mode, JSONArray pts, String amapKey) {
        JSONObject out = new JSONObject();
        try {
            if (pts == null || pts.length() < 2) throw new Exception("至少需要起点和终点");
            if (amapKey == null || amapKey.trim().isEmpty()) throw new Exception("NO_KEY");
            List<JSONArray> legs = new ArrayList<>();
            for (int i = 1; i < pts.length(); i++) {
                JSONObject a = pts.getJSONObject(i - 1), b = pts.getJSONObject(i);
                double[] ga = GeoMath.wgs2gcj(a.getDouble("lat"), a.getDouble("lng"));
                double[] gb = GeoMath.wgs2gcj(b.getDouble("lat"), b.getDouble("lng"));
                String origin = String.format(java.util.Locale.US, "%.6f,%.6f", ga[1], ga[0]);
                String dest = String.format(java.util.Locale.US, "%.6f,%.6f", gb[1], gb[0]);
                legs.add(amapPaths(mode, origin, dest, amapKey.trim()));
            }
            JSONArray routes = combine(legs);
            if (routes.length() == 0) throw new Exception("未返回路径");
            JSONObject best = routes.getJSONObject(0);
            out.put("ok", true).put("mode", mode).put("routes", routes)
                    .put("points", best.getJSONArray("points"))
                    .put("distance", best.getDouble("distance")).put("duration", best.getDouble("duration"));
        } catch (Exception e) {
            try {
                out.put("ok", false).put("error", e.getMessage() == null ? String.valueOf(e) : e.getMessage());
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    /**
     * Build the route variants from the per-leg alternatives (each leg: array of normalized paths
     * {distance, duration, steps:[{polyline}]}). Variants that end up identical are dropped.
     */
    public static JSONArray combine(List<JSONArray> legs) throws Exception {
        int variants = 0;
        for (JSONArray leg : legs) variants = Math.max(variants, leg.length());
        variants = Math.min(variants, MAX_ROUTES);
        JSONArray routes = new JSONArray();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int k = 0; k < variants; k++) {
            JSONArray points = new JSONArray();
            double distance = 0, duration = 0;
            StringBuilder signature = new StringBuilder();
            for (JSONArray leg : legs) {
                if (leg.length() == 0) continue;
                int idx = Math.min(k, leg.length() - 1);
                signature.append(idx).append('/');
                JSONObject path = leg.getJSONObject(idx);
                distance += path.optDouble("distance", 0);
                duration += path.optDouble("duration", 0);
                appendSteps(points, path.optJSONArray("steps"));
            }
            if (points.length() < 2) continue;
            // a leg with fewer alternatives repeats its last one; skip variants that repeat a whole route
            String sig = signature + "|" + points.length() + "|" + Math.round(distance);
            if (!seen.add(sig)) continue;
            routes.put(new JSONObject().put("points", points).put("distance", distance).put("duration", duration)
                    .put("label", routes.length() == 0 ? "高德推荐" : "备选 " + (routes.length() + 1)));
        }
        return routes;
    }

    /** Append the WGS-84 vertices of Amap steps; the first vertex of each step is flagged s=1. */
    private static void appendSteps(JSONArray points, JSONArray steps) throws Exception {
        if (steps == null) return;
        for (int j = 0; j < steps.length(); j++) {
            String poly = steps.getJSONObject(j).optString("polyline", "");
            boolean first = true;
            for (String pair : poly.split(";")) {
                String[] ll = pair.split(",");
                if (ll.length != 2) continue;
                double[] w = GeoMath.gcj2wgs(Double.parseDouble(ll[1]), Double.parseDouble(ll[0]));
                if (points.length() > 0) {
                    JSONObject last = points.getJSONObject(points.length() - 1);
                    if (Math.abs(last.getDouble("lat") - w[0]) < 1e-7 && Math.abs(last.getDouble("lng") - w[1]) < 1e-7) {
                        if (first) last.put("s", 1);
                        first = false;
                        continue;
                    }
                }
                JSONObject o = new JSONObject().put("lat", w[0]).put("lng", w[1]);
                if (first) o.put("s", 1);   // start of an Amap step = a turn / crossing
                first = false;
                points.put(o);
            }
        }
    }

    /**
     * One leg: up to {@link #MAX_ROUTES} Amap paths, normalized to {distance, duration, steps}.
     * Walking / bicycling use 路径规划 2.0 (v5, {@code alternative_route=3}) and fall back to the
     * single-path v3/v4 services; driving uses v3 {@code strategy=10}, which is documented to
     * return several results, and falls back to v5.
     */
    static JSONArray amapPaths(String mode, String origin, String dest, String key) throws Exception {
        String base = "&origin=" + origin + "&destination=" + dest + "&key=" + enc(key);
        String primary, fallback;
        if ("bicycling".equals(mode)) {
            primary = "https://restapi.amap.com/v5/direction/bicycling?alternative_route=" + MAX_ROUTES + "&show_fields=cost,polyline" + base;
            fallback = "https://restapi.amap.com/v4/direction/bicycling?" + base.substring(1);
        } else if ("driving".equals(mode)) {
            primary = "https://restapi.amap.com/v3/direction/driving?strategy=10&extensions=base&output=JSON" + base;
            fallback = "https://restapi.amap.com/v5/direction/driving?strategy=32&show_fields=cost,polyline" + base;
        } else {
            primary = "https://restapi.amap.com/v5/direction/walking?alternative_route=" + MAX_ROUTES + "&show_fields=cost,polyline" + base;
            fallback = "https://restapi.amap.com/v3/direction/walking?output=JSON" + base;
        }
        try {
            return parsePaths(new JSONObject(fetcher.get(primary, 15000)));
        } catch (Exception first) {
            try {
                return parsePaths(new JSONObject(fetcher.get(fallback, 15000)));
            } catch (Exception second) {
                throw first.getMessage() == null ? second : first;
            }
        }
    }

    /** Normalize a v3 / v4 / v5 direction response to [{distance, duration, steps:[{polyline}]}]. */
    static JSONArray parsePaths(JSONObject o) throws Exception {
        JSONArray paths;
        if (o.has("errcode")) {                                   // v4 bicycling
            if (o.optInt("errcode", -1) != 0) throw new Exception(o.optString("errmsg", o.optString("errdetail", "请求失败")));
            paths = o.getJSONObject("data").optJSONArray("paths");
        } else {                                                  // v3 / v5
            if (!"1".equals(o.optString("status"))) throw new Exception(o.optString("info", "请求失败"));
            paths = o.getJSONObject("route").optJSONArray("paths");
        }
        if (paths == null || paths.length() == 0) throw new Exception("该起终点之间没有可用路径");
        JSONArray out = new JSONArray();
        for (int i = 0; i < paths.length() && i < MAX_ROUTES; i++) {
            JSONObject p = paths.getJSONObject(i);
            double duration = p.optDouble("duration", Double.NaN);                       // v3 / v4
            if (Double.isNaN(duration)) {
                JSONObject cost = p.optJSONObject("cost");                              // v5 show_fields=cost
                duration = cost == null ? 0 : cost.optDouble("duration", 0);
            }
            JSONArray steps = new JSONArray();
            JSONArray in = p.optJSONArray("steps");
            for (int j = 0; in != null && j < in.length(); j++) {
                steps.put(new JSONObject().put("polyline", in.getJSONObject(j).optString("polyline", "")));
            }
            out.put(new JSONObject().put("distance", p.optDouble("distance", 0)).put("duration", duration).put("steps", steps));
        }
        return out;
    }
}
