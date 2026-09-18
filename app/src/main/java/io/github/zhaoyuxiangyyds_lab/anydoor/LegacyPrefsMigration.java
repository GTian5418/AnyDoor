package io.github.zhaoyuxiangyyds_lab.anydoor;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Process;
import android.util.Base64;
import android.util.Xml;
import org.xmlpull.v1.XmlPullParser;
import java.io.StringReader;
import java.util.*;

/** One-time NSP migration. Runs on a worker, preserves originals, never imports another Android user. */
public final class LegacyPrefsMigration {
    private static final String DONE = "private_storage_v1";
    private LegacyPrefsMigration() {}
    interface Reader { RootShell.Result read(String command); }
    public static void migrate(Context c) throws Exception { migrate(c, RootShell::run); }
    static synchronized void migrate(Context c, Reader reader) throws Exception {
        SharedPreferences app = Config.app(c);
        if (app.getBoolean(DONE, false)) return;
        SharedPreferences config = c.getSharedPreferences(Keys.CONFIG, Context.MODE_PRIVATE);
        PackageInfo info = c.getPackageManager().getPackageInfo(Keys.PKG, 0);
        if ((!config.contains(Keys.LAT) || app.getBoolean("migration_in_progress", false)) && info.lastUpdateTime != info.firstInstallTime) {
            int user = Process.myUid() / 100000;
            String prefsDir = user == 0 ? "prefs" : "prefs" + user;
            // Names are fixed by this app. ls lists only matching old module directories; data never enters a command.
            RootShell.Result r = reader.read("id -u | grep -qx 0 || exit 1\n"
                    + "d=$(ls -td /data/misc/*/" + prefsDir + "/" + Keys.PKG + " 2>/dev/null | head -n 1)\n"
                    + "[ -n \"$d\" ] || exit 0\n"
                    + "for n in config app; do\n"
                    + " f=\"$d/$n.xml\"; [ ! -f \"$f.bak\" ] || f=\"$f.bak\"\n"
                    + " if [ -f \"$f\" ]; then printf '%s:' \"$n\"; base64 \"$f\" | tr -d '\\n'; printf '\\n'; fi\n"
                    + "done");
            if (!r.ok()) throw new IllegalStateException("迁移旧设置需要 Root，请授权后重试。" + r.err);
            Map<String,Map<String,Object>> parsed = new HashMap<>();
            for (String line : r.out.split("\n")) {
                if (line.isEmpty()) continue;
                int sep = line.indexOf(':');
                if (sep < 0) throw new IllegalStateException("旧设置读取结果不完整");
                parsed.put(line.substring(0, sep), parse(new String(Base64.decode(line.substring(sep + 1), Base64.DEFAULT), "UTF-8")));
            }
            if (!app.edit().putBoolean("migration_in_progress", true).commit()) throw new IllegalStateException("无法记录迁移进度");
            // Parse both before writing either. Repeatable if the process stops between commits.
            if (parsed.containsKey("config")) {
                SharedPreferences.Editor e = config.edit(); copy(e, parsed.get("config"));
                if (!e.putBoolean(Keys.STARTED, false).commit()) throw new IllegalStateException("迁移配置保存失败");
            }
            if (parsed.containsKey("app")) {
                SharedPreferences.Editor e = app.edit(); copy(e, parsed.get("app"));
                if (!e.commit()) throw new IllegalStateException("迁移收藏保存失败");
            }
        }
        if (!app.edit().remove("migration_in_progress").putBoolean(DONE, true).commit()) throw new IllegalStateException("迁移状态保存失败");
    }
    static Map<String,Object> parse(String xml) throws Exception {
        XmlPullParser p = Xml.newPullParser(); p.setInput(new StringReader(xml));
        Map<String,Object> m = new HashMap<>();
        boolean root = false;
        for (int event = p.next(); event != XmlPullParser.END_DOCUMENT; event = p.next()) {
            if (event != XmlPullParser.START_TAG) continue;
            String tag = p.getName();
            if ("map".equals(tag)) { root = true; continue; }
            if (!root) throw new IllegalArgumentException("Expected preferences map");
            String key = p.getAttributeValue(null, "name"), value = p.getAttributeValue(null, "value");
            if (key == null) throw new IllegalArgumentException("Missing preference name");
            switch (tag) {
                case "string": m.put(key, p.nextText()); break;
                case "boolean": m.put(key, Boolean.parseBoolean(value)); break;
                case "int": m.put(key, Integer.parseInt(value)); break;
                case "long": m.put(key, Long.parseLong(value)); break;
                case "float": m.put(key, Float.parseFloat(value)); break;
                default: throw new IllegalArgumentException("Unsupported preference type: " + tag);
            }
        }
        if (!root) throw new IllegalArgumentException("Empty preferences XML");
        return m;
    }
    private static void copy(SharedPreferences.Editor e, Map<String,Object> m) {
        for (Map.Entry<String,Object> en : m.entrySet()) {
            String k = en.getKey(); Object v = en.getValue();
            if (v instanceof String) e.putString(k, (String)v);
            else if (v instanceof Boolean) e.putBoolean(k, (Boolean)v);
            else if (v instanceof Integer) e.putInt(k, (Integer)v);
            else if (v instanceof Long) e.putLong(k, (Long)v);
            else if (v instanceof Float) e.putFloat(k, (Float)v);
        }
    }
}
