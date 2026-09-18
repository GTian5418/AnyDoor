package io.github.zhaoyuxiangyyds_lab.anydoor; public class Config {
 public static final String MIRROR_PATH=System.getProperty("test.mirror");
 public static android.content.SharedPreferences app(android.content.Context c){return c.getSharedPreferences(Keys.APP,0);}
}