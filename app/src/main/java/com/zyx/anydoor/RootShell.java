package com.zyx.anydoor;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;

/** Minimal `su` helper. */
public final class RootShell {
    private RootShell() {}

    public static final class Result {
        public int code = -1;
        public String out = "";
        public String err = "";

        public boolean ok() {
            return code == 0;
        }
    }

    public static Result run(String script) {
        Result r = new Result();
        Process p = null;
        try {
            p = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(p.getOutputStream());
            os.writeBytes(script + "\nexit $?\n");
            os.flush();
            r.out = read(p.getInputStream());
            r.err = read(p.getErrorStream());
            r.code = p.waitFor();
        } catch (Exception e) {
            r.err = String.valueOf(e);
        } finally {
            if (p != null) p.destroy();
        }
        return r;
    }

    public static boolean available() {
        Result r = run("id");
        return r.ok() && r.out.contains("uid=0");
    }

    private static String read(java.io.InputStream in) throws Exception {
        BufferedReader br = new BufferedReader(new InputStreamReader(in));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line).append('\n');
        return sb.toString().trim();
    }
}
