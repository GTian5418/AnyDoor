package de.robv.android.xposed;
import java.util.*;
public class XSharedPreferences {
 public static boolean fail,failRead;
 public static Map<String,Object> values=new HashMap<>();
 public XSharedPreferences(String p,String f){if(fail)throw new IllegalStateException("constructor fault");}
 public void reload(){if(failRead)throw new IllegalStateException("reload fault");}
 public Map<String,?> getAll(){return new HashMap<>(values);}
}
