package io.github.zhaoyuxiangyyds_lab.anydoor;
import android.content.SharedPreferences;import java.util.*;
public class MemoryPrefs implements SharedPreferences {
 public Map<String,Object> values=new HashMap<>();public int commits,failCommit=-1;
 public Map<String,?> getAll(){return new HashMap<>(values);}
 public String getString(String k,String d){return (String)values.getOrDefault(k,d);}
 @SuppressWarnings("unchecked") public Set<String> getStringSet(String k,Set<String>d){return (Set<String>)values.getOrDefault(k,d);}
 public boolean getBoolean(String k,boolean d){return (Boolean)values.getOrDefault(k,d);}
 public int getInt(String k,int d){return (Integer)values.getOrDefault(k,d);}
 public long getLong(String k,long d){return (Long)values.getOrDefault(k,d);}
 public float getFloat(String k,float d){return (Float)values.getOrDefault(k,d);}
 public boolean contains(String k){return values.containsKey(k);}
 public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l){}
 public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l){}
 public Editor edit(){return new Editor(){
  Map<String,Object> pending=new HashMap<>();boolean clear;
  public Editor putString(String k,String v){pending.put(k,v);return this;}
  public Editor putStringSet(String k,Set<String>v){pending.put(k,v);return this;}
  public Editor putInt(String k,int v){pending.put(k,v);return this;}
  public Editor putLong(String k,long v){pending.put(k,v);return this;}
  public Editor putFloat(String k,float v){pending.put(k,v);return this;}
  public Editor putBoolean(String k,boolean v){pending.put(k,v);return this;}
  public Editor remove(String k){pending.put(k,null);return this;}
  public Editor clear(){clear=true;return this;}
  public boolean commit(){if(++commits==failCommit)return false;if(clear)values.clear();for(Map.Entry<String,Object> e:pending.entrySet()){if(e.getValue()==null)values.remove(e.getKey());else values.put(e.getKey(),e.getValue());}return true;}
  public void apply(){commit();}
 };}
}
