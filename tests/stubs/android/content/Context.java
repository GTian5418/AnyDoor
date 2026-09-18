package android.content;
import java.util.*;import io.github.zhaoyuxiangyyds_lab.anydoor.MemoryPrefs;
public class Context {
 public static final int MODE_PRIVATE=0; public static final String LOCATION_SERVICE="location";
 public Map<String,MemoryPrefs> prefs=new HashMap<>();public android.content.pm.PackageManager pm=new android.content.pm.PackageManager();
 public SharedPreferences getSharedPreferences(String n,int mode){return prefs.computeIfAbsent(n,k->new MemoryPrefs());}
 public android.content.pm.PackageManager getPackageManager(){return pm;}
 public Object getSystemService(String n){return null;}
}
