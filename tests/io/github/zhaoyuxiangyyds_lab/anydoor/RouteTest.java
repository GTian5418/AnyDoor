package io.github.zhaoyuxiangyyds_lab.anydoor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.*;

/** Route planning against canned Amap responses: alternatives, multi-leg combination, fallbacks. */
public class RouteTest {
    static int count;
    static void check(boolean b, String m) { if (!b) throw new AssertionError(m); count++; }

    /** v5 style response with n paths, each a straight two-vertex polyline shifted by its index. */
    static String v5(int n, double lng, double lat) {
        JSONArray paths = new JSONArray();
        for (int i = 0; i < n; i++) {
            String poly = String.format(Locale.US, "%.6f,%.6f;%.6f,%.6f", lng, lat + i * 0.001, lng + 0.01, lat + 0.01 + i * 0.001);
            paths.put(new JSONObject().put("distance", 1000 + i * 100).put("cost", new JSONObject().put("duration", 600 + i * 60))
                    .put("steps", new JSONArray().put(new JSONObject().put("polyline", poly))));
        }
        return new JSONObject().put("status", "1").put("info", "OK").put("route", new JSONObject().put("paths", paths)).toString();
    }

    static String v3Walking(double lng, double lat) {
        String poly = String.format(Locale.US, "%.6f,%.6f;%.6f,%.6f", lng, lat, lng + 0.02, lat + 0.02);
        JSONArray paths = new JSONArray().put(new JSONObject().put("distance", "2500").put("duration", "1800")
                .put("steps", new JSONArray().put(new JSONObject().put("polyline", poly))));
        return new JSONObject().put("status", "1").put("info", "OK").put("route", new JSONObject().put("paths", paths)).toString();
    }

    public static void main(String[] args) throws Exception {
        final List<String> urls = new ArrayList<>();
        final Map<String, String> answers = new HashMap<>();   // url prefix → body
        Geocoder.fetcher = new Geocoder.Fetcher() {
            public String get(String url, int timeoutMs) throws Exception {
                urls.add(url);
                for (Map.Entry<String, String> e : answers.entrySet()) if (url.startsWith(e.getKey())) return e.getValue();
                throw new Exception("unexpected " + url);
            }
        };
        JSONArray pts = new JSONArray().put(new JSONObject().put("lat", 39.90).put("lng", 116.40)).put(new JSONObject().put("lat", 39.91).put("lng", 116.41));

        // walking: three v5 alternatives → three routes, first one mirrored at top level
        answers.put("https://restapi.amap.com/v5/direction/walking", v5(3, 116.40, 39.90));
        JSONObject r = Geocoder.route("walking", pts, "k");
        check(r.getBoolean("ok"), "walking plan ok: " + r.optString("error"));
        check(urls.size() == 1 && urls.get(0).contains("alternative_route=3") && urls.get(0).contains("show_fields=cost,polyline"), "v5 walking asks for alternatives + polylines");
        check(r.getJSONArray("routes").length() == 3, "three alternatives kept");
        check(r.getJSONArray("routes").getJSONObject(0).getString("label").equals("高德推荐"), "first route labelled as recommended");
        check(r.getJSONArray("routes").getJSONObject(2).getDouble("duration") == 720, "v5 duration read from cost");
        check(r.getJSONArray("points").length() == r.getJSONArray("routes").getJSONObject(0).getJSONArray("points").length(), "top-level points repeat route 0");
        check(r.getJSONArray("routes").getJSONObject(1).getJSONArray("points").getJSONObject(0).getInt("s") == 1, "step starts flagged as crossings");
        check(Math.abs(r.getJSONArray("points").getJSONObject(0).getDouble("lat") - 39.90) < 0.01, "polyline converted back near the WGS-84 origin");

        // running uses the walking network too
        urls.clear();
        r = Geocoder.route("running", pts, "k");
        check(r.getBoolean("ok") && urls.get(0).contains("/v5/direction/walking"), "running plans on the walking service");

        // v5 failure falls back to v3 walking (single route, v3 duration field)
        urls.clear();
        answers.put("https://restapi.amap.com/v5/direction/walking", new JSONObject().put("status", "0").put("info", "INVALID_PARAMS").toString());
        answers.put("https://restapi.amap.com/v3/direction/walking", v3Walking(116.40, 39.90));
        r = Geocoder.route("walking", pts, "k");
        check(r.getBoolean("ok") && urls.size() == 2 && urls.get(1).contains("/v3/direction/walking"), "v5 error → v3 fallback");
        check(r.getJSONArray("routes").length() == 1 && r.getDouble("duration") == 1800, "single v3 route with its duration");

        // both fail → the primary service's error is reported
        answers.put("https://restapi.amap.com/v3/direction/walking", new JSONObject().put("status", "0").put("info", "DAILY_QUERY_OVER_LIMIT").toString());
        r = Geocoder.route("walking", pts, "k");
        check(!r.getBoolean("ok") && r.getString("error").equals("INVALID_PARAMS"), "primary error surfaces when both fail");

        // driving: v3 strategy=10 (multiple paths) first, v5 as fallback
        urls.clear();
        answers.put("https://restapi.amap.com/v3/direction/driving", v5(2, 116.40, 39.90));   // same JSON shape as v3 with duration in cost → check fallback duration logic
        r = Geocoder.route("driving", pts, "k");
        check(r.getBoolean("ok") && urls.get(0).contains("/v3/direction/driving") && urls.get(0).contains("strategy=10"), "driving uses v3 strategy=10");
        check(r.getJSONArray("routes").length() == 2, "two driving alternatives");

        // bicycling: v5 alternatives, v4 fallback with errcode shape
        urls.clear();
        answers.put("https://restapi.amap.com/v5/direction/bicycling", new JSONObject().put("status", "0").put("info", "SERVICE_NOT_AVAILABLE").toString());
        String poly = "116.400000,39.900000;116.410000,39.910000";
        answers.put("https://restapi.amap.com/v4/direction/bicycling", new JSONObject().put("errcode", 0).put("data", new JSONObject().put("paths",
                new JSONArray().put(new JSONObject().put("distance", 1500).put("duration", 400).put("steps", new JSONArray().put(new JSONObject().put("polyline", poly)))))).toString());
        r = Geocoder.route("bicycling", pts, "k");
        check(r.getBoolean("ok") && urls.size() == 2 && urls.get(1).contains("/v4/direction/bicycling"), "bicycling v5 → v4 fallback");
        check(r.getJSONArray("routes").length() == 1 && r.getDouble("distance") == 1500, "v4 path parsed");

        // multi-leg (via point): variant k takes the k-th alternative of each leg, the last one when a leg has fewer
        List<JSONArray> legs = new ArrayList<>();
        legs.add(Geocoder.parsePaths(new JSONObject(v5(3, 116.40, 39.90))));
        legs.add(Geocoder.parsePaths(new JSONObject(v5(1, 116.41, 39.91))));
        JSONArray routes = Geocoder.combine(legs);
        check(routes.length() == 3, "three variants from a 3-path leg plus a 1-path leg");
        for (int k = 0; k < 3; k++) {
            JSONObject v = routes.getJSONObject(k);
            check(Math.abs(v.getDouble("distance") - (1000 + k * 100 + 1000)) < 1e-6, "variant " + k + " sums its own leg alternatives");
            // alternative 0 of leg 1 ends exactly where leg 2 starts → shared vertex merged; the shifted ones do not
            check(v.getJSONArray("points").length() == (k == 0 ? 3 : 4), "legs concatenated, shared vertex merged once");
        }
        // identical variants are dropped (both legs single-path)
        legs.clear();
        legs.add(Geocoder.parsePaths(new JSONObject(v5(1, 116.40, 39.90))));
        legs.add(Geocoder.parsePaths(new JSONObject(v5(1, 116.41, 39.91))));
        check(Geocoder.combine(legs).length() == 1, "no duplicate variants");
        // never more than MAX_ROUTES even if a service returns more
        legs.clear();
        legs.add(Geocoder.parsePaths(new JSONObject(v5(5, 116.40, 39.90))));
        check(Geocoder.combine(legs).length() == Geocoder.MAX_ROUTES, "alternatives capped");

        // input validation
        check(!Geocoder.route("walking", new JSONArray().put(new JSONObject().put("lat", 1).put("lng", 2)), "k").getBoolean("ok"), "one point rejected");
        check(Geocoder.route("walking", pts, "").getString("error").equals("NO_KEY"), "missing key reported as NO_KEY");
        System.out.println("RouteTest: " + count + " assertions passed");
    }
}
