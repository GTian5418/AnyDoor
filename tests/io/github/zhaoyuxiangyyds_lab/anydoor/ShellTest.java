package io.github.zhaoyuxiangyyds_lab.anydoor;
public class ShellTest {
    static void check(boolean b,String s) { if(!b)throw new AssertionError(s); }
    public static void main(String[] args) {
        String[] shell={args[0]};
        RootShell.Result r=RootShell.runCommand(shell,"printf '中文 output'; printf 'error output' >&2; exit 7",3000);
        check(r.code==7&&r.out.contains("中文")&&r.err.contains("error output"),"exit status and UTF-8 preserved");
        r=RootShell.runCommand(shell,"i=0; while [ $i -lt 6000 ]; do printf '0123456789012345678901234567890123456789'; printf '0123456789012345678901234567890123456789' >&2; i=$((i+1)); done; echo END_OUT; echo END_ERR >&2",10000);
        check(r.ok()&&r.out.contains("END_OUT")&&r.err.contains("END_ERR"),"large stdout/stderr drained concurrently");
        long start=System.nanoTime();
        r=RootShell.runCommand(shell,"sleep 5",200);
        long millis=(System.nanoTime()-start)/1000000;
        check(r.code==124&&millis<2500,"bounded timeout, actual ms="+millis);
        System.out.println("ShellTest: 3 assertions passed (timeout "+millis+" ms)");
    }
}
