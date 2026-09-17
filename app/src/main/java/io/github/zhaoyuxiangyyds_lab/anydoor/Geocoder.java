package io.github.zhaoyuxiangyyds_lab.anydoor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

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

    /**
     * Plan a road-following route through the given WGS-84 points with Amap's direction API.
     * Consecutive points are routed pairwise so every mode supports intermediate waypoints.
     *
     * @param mode walking | running | bicycling | driving (running uses the walking network)
     * @return {ok, points:[{lat,lng,s}], distance, duration, error}; s=1 marks a turn / intersection
     */
    public static JSONObject route(String mode, JSONArray pts, String amapKey) {
        JSONObject out = new JSONObject();
        try {
            if (pts == null || pts.length() < 2) throw new Exception("至少需要起点和终点");
            if (amapKey == null || amapKey.trim().isEmpty()) throw new Exception("NO_KEY");
            JSONArray points = new JSONArray();
            double distance = 0, duration = 0;
            for (int i = 1; i < pts.length(); i++) {
                JSONObject a = pts.getJSONObject(i - 1), b = pts.getJSONObject(i);
                double[] ga = GeoMath.wgs2gcj(a.getDouble("lat"), a.getDouble("lng"));
                double[] gb = GeoMath.wgs2gcj(b.getDouble("lat"), b.getDouble("lng"));
                String origin = String.format(java.util.Locale.US, "%.6f,%.6f", ga[1], ga[0]);
                String dest = String.format(java.util.Locale.US, "%.6f,%.6f", gb[1], gb[0]);
                JSONObject path = amapPath(mode, origin, dest, amapKey.trim());
                distance += path.optDouble("distance", 0);
                duration += path.optDouble("duration", 0);
                JSONArray steps = path.optJSONArray("steps");
                if (steps == null) continue;
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
            if (points.length() < 2) throw new Exception("未返回路径");
            out.put("ok", true).put("points", points).put("distance", distance).put("duration", duration).put("mode", mode);
        } catch (Exception e) {
            try {
                out.put("ok", false).put("error", e.getMessage() == null ? String.valueOf(e) : e.getMessage());
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    /** One Amap direction request; returns the first path object {distance, duration, steps}. */
    private static JSONObject amapPath(String mode, String origin, String dest, String key) throws Exception {
        String url;
        boolean v4 = false;
        if ("bicycling".equals(mode)) {
            url = "https://restapi.amap.com/v4/direction/bicycling?key=" + enc(key) + "&origin=" + origin + "&destination=" + dest;
            v4 = true;
        } else if ("driving".equals(mode)) {
            url = "https://restapi.amap.com/v3/direction/driving?key=" + enc(key) + "&origin=" + origin + "&destination=" + dest
                    + "&strategy=10&extensions=base&output=JSON";
        } else {
            url = "https://restapi.amap.com/v3/direction/walking?key=" + enc(key) + "&origin=" + origin + "&destination=" + dest + "&output=JSON";
        }
        JSONObject o = new JSONObject(http(url, 15000));
        JSONArray paths;
        if (v4) {
            if (o.optInt("errcode", -1) != 0) throw new Exception(o.optString("errmsg", o.optString("errdetail", "请求失败")));
            paths = o.getJSONObject("data").optJSONArray("paths");
        } else {
            if (!"1".equals(o.optString("status"))) throw new Exception(o.optString("info", "请求失败"));
            paths = o.getJSONObject("route").optJSONArray("paths");
        }
        if (paths == null || paths.length() == 0) throw new Exception("该起终点之间没有可用路径");
        return paths.getJSONObject(0);
    }
}
