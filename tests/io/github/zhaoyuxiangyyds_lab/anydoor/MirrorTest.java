package io.github.zhaoyuxiangyyds_lab.anydoor;
import java.nio.file.*;import java.util.Base64;
public class MirrorTest {
    static int count;static void check(boolean b,String m){if(!b)throw new AssertionError(m);count++;}
    public static void main(String[]args)throws Exception{
        Path p=Paths.get(args[1]).resolve("mirror atomic ' test.json");
        String path=p.toAbsolutePath().toString().replace('\\','/');
        String json="{\"started\":false,\"text\":\"中文\"}";
        String b64=Base64.getEncoder().encodeToString(json.getBytes("UTF-8"));
        String[]shell={args[0]};long end=System.currentTimeMillis()/1000+10;
        RootShell.Result r=RootShell.runCommand(shell,"chown() { return 0; }; chcon() { return 0; };\n"+MirrorCommand.build(path,b64,end,1),3000);
        check(r.ok()&&new String(Files.readAllBytes(p),"UTF-8").equals(json),"atomically publishes exact JSON including UTF-8 and quoted path: code="+r.code+", err="+r.err+", out="+r.out);
        String stop=new String(Files.readAllBytes(p),"UTF-8");
        String active=Base64.getEncoder().encodeToString("{\"started\":true}".getBytes("UTF-8"));
        r=RootShell.runCommand(shell,"chown() { return 0; }; chcon() { return 42; };\n"+MirrorCommand.build(path,active,end,2),3000);
        check(r.code==42,"SELinux labeling failure is not swallowed");
        check(new String(Files.readAllBytes(p),"UTF-8").equals(stop),"failed publication retains complete previous snapshot");
        check(!Files.exists(Paths.get(p+".tmp.2")),"failed temporary file cleaned");
        r=RootShell.runCommand(shell,MirrorCommand.build(path,active,1,3),3000);
        check(r.code==124&&new String(Files.readAllBytes(p),"UTF-8").equals(stop),"authorization delayed past deadline cannot overwrite newer snapshot");
        Files.delete(p);
        System.out.println("MirrorTest: "+count+" assertions passed");
    }
}
