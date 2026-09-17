package io.github.zhaoyuxiangyyds_lab.anydoor;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.location.LocationManager;
import android.util.Log;

/** adb-triggerable diagnostics: `am broadcast -a io.github.zhaoyuxiangyyds_lab.anydoor.PROBE`. Logs to tag AnyDoorProbe. */
public class DebugReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(final Context c, Intent intent) {
        if ("search".equals(intent.getStringExtra("mode"))) {
            final String q = intent.getStringExtra("q");
            new Thread(new Runnable() {
                @Override
                public void run() {
                    String key = Config.app(c).getString(Keys.AMAP_KEY, "");
                    Log.i("AnyDoorProbe", "SEARCH key.len=" + (key == null ? 0 : key.length()));
                    org.json.JSONObject r = Geocoder.search(q == null ? "天安门" : q, "auto", key, "", 0, 0);
                    Log.i("AnyDoorProbe", "SEARCH result=" + r.toString());
                    org.json.JSONObject rv = Geocoder.reverse(39.908722, 116.397499, "auto", key, "");
                    Log.i("AnyDoorProbe", "REVERSE result=" + rv.toString());
                }
            }).start();
            return;
        }
        StringBuilder sb = new StringBuilder("PROBE ");
        try {
            LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
            Location probe = null;
            try { probe = lm.getLastKnownLocation(Keys.PROBE_PROVIDER); } catch (Throwable t) { sb.append("probeEx=").append(t).append(' '); }
            sb.append("systemHook=").append(probe != null && Keys.PROBE_PROVIDER.equals(probe.getProvider()));
            if (probe != null) sb.append('(').append(probe.getLatitude()).append(',').append(probe.getLongitude()).append(')');
            Location gps = null;
            try { gps = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER); } catch (Throwable ignored) {}
            sb.append(" gps=").append(gps == null ? "null" : (gps.getLatitude() + "," + gps.getLongitude() + " mock=" + isMock(gps)));
        } catch (Throwable t) {
            sb.append("ERR ").append(t);
        }
        Log.i("AnyDoorProbe", sb.toString());
    }

    private static boolean isMock(Location l) {
        try { return l.isFromMockProvider(); } catch (Throwable t) { return false; }
    }
}
