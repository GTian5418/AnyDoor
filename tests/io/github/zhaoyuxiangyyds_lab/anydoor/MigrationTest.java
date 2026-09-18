package io.github.zhaoyuxiangyyds_lab.anydoor;
import android.content.Context;import java.util.*;
public class MigrationTest {
 static int count;static void check(boolean b,String m){if(!b)throw new AssertionError(m);count++;}
 static String config="<map><string name='lat'>31</string><string name='lng'>121</string><long name='seed' value='9007199254740993'/><int name='interval' value='1000'/><boolean name='started' value='true'/></map>";
 static String app="<map><string name='favorites'>[{&quot;name&quot;:&quot;收藏 &amp; 测试&quot;}]</string><string name='theme'>dark</string></map>";
 static String line(String name,String xml)throws Exception{return name+":"+java.util.Base64.getEncoder().encodeToString(xml.getBytes("UTF-8"))+"\n";}
 static RootShell.Result result(String s){RootShell.Result r=new RootShell.Result();r.code=0;r.out=s;return r;}
 public static void main(String[]args)throws Exception{
  String good=line("config",config)+line("app",app);
  Context c=new Context();LegacyPrefsMigration.migrate(c,command->result(good));
  MemoryPrefs cfg=(MemoryPrefs)c.getSharedPreferences("config",0),a=(MemoryPrefs)c.getSharedPreferences("app",0);
  check(cfg.getString("lat","").equals("31"),"coordinates migrated");
  check(cfg.getLong("seed",0)==9007199254740993L,"64-bit seed preserved");
  check(cfg.getInt("interval",0)==1000,"integer preference type preserved");
  check(!cfg.getBoolean("started",true),"migration never resumes stale active state");
  check(a.getString("favorites","").contains("收藏 & 测试")&&a.getString("theme","").equals("dark"),"favorites and UI settings preserved");
  LegacyPrefsMigration.migrate(c,command->{throw new AssertionError("migration repeated");});count++;
  Context denied=new Context();RootShell.Result no=result("");no.code=1;
  try{LegacyPrefsMigration.migrate(denied,command->no);throw new AssertionError();}catch(IllegalStateException expected){count++;}
  check(!Config.app(denied).getBoolean("private_storage_v1",false),"denial does not mark migration complete");
  LegacyPrefsMigration.migrate(denied,command->result(good));check(Config.app(denied).getBoolean("private_storage_v1",false),"retry after grant succeeds");
  Context broken=new Context();String truncated=line("config",config)+line("app","<map><string");
  try{LegacyPrefsMigration.migrate(broken,command->result(truncated));throw new AssertionError();}catch(Exception expected){count++;}
  check(!broken.getSharedPreferences("config",0).contains("lat"),"all XML parsed before importing anything");
  Context partial=new Context();MemoryPrefs ap=(MemoryPrefs)partial.getSharedPreferences("app",0);ap.failCommit=2;
  try{LegacyPrefsMigration.migrate(partial,command->result(good));throw new AssertionError();}catch(IllegalStateException expected){count++;}
  check(partial.getSharedPreferences("config",0).contains("lat")&&ap.getBoolean("migration_in_progress",false),"partial persistence remains retryable");
  LegacyPrefsMigration.migrate(partial,command->result(good));check(ap.getString("theme","").equals("dark"),"retry completes second file despite first file already imported");
  android.os.Process.uid=1010000;Context profile=new Context();
  LegacyPrefsMigration.migrate(profile,command->{check(command.contains("/prefs10/"),"migration isolated by Android user");return result("");});
  android.os.Process.uid=10000;
  Context fresh=new Context();fresh.pm.info.lastUpdateTime=1;
  LegacyPrefsMigration.migrate(fresh,command->{throw new AssertionError("new installation should not request migration root");});count++;
  System.out.println("MigrationTest: "+count+" assertions passed");
 }
}
