package io.github.zhaoyuxiangyyds_lab.anydoor;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Bounded root command execution. Never discard exit status or serialize pipe draining. */
public final class RootShell {
    private RootShell() {}
    public static final class Result {
        public int code = -1;
        public String out = "", err = "";
        public boolean ok() { return code == 0; }
    }
    public static Result run(String script) { return runCommand(new String[]{"su"}, script, 12000); }

    static Result runCommand(String[] command, String script, long timeoutMs) {
        Result r = new Result();
        Process p = null;
        Drain stdout = null, stderr = null;
        try {
            p = new ProcessBuilder(command).start();
            stdout = new Drain(p.getInputStream()); stderr = new Drain(p.getErrorStream());
            stdout.start(); stderr.start();
            final Process process = p;
            Thread writer = new Thread(() -> {
                try (OutputStream out = process.getOutputStream()) {
                    out.write((script + "\nexit $?\n").getBytes(StandardCharsets.UTF_8));
                } catch (IOException ignored) { }
            }, "anydoor-su-input");
            writer.setDaemon(true); writer.start();
            if (p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) r.code = p.exitValue();
            else { r.code = 124; r.err = "Root command timed out after " + timeoutMs + " ms"; }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); r.err = "Root command interrupted";
        } catch (Exception e) { r.err = e.toString(); }
        finally {
            if (p != null) {
                p.destroy();
                if (p.isAlive()) p.destroyForcibly();
            }
            if (stdout != null) r.out = stdout.finish();
            if (stderr != null) { String text = stderr.finish(); if (!text.isEmpty()) r.err += (r.err.isEmpty() ? "" : "\n") + text; }
        }
        return r;
    }
    public static boolean available() { Result r = run("id"); return r.ok() && r.out.contains("uid=0"); }

    private static final class Drain extends Thread {
        private final InputStream in;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Drain(InputStream in) { super("anydoor-su-output"); this.in = in; setDaemon(true); }
        public void run() {
            byte[] buffer = new byte[4096];
            try {
                int n;
                while ((n = in.read(buffer)) != -1) {
                    synchronized (bytes) { if (bytes.size() < 2 * 1024 * 1024) bytes.write(buffer, 0, Math.min(n, 2 * 1024 * 1024 - bytes.size())); }
                }
            } catch (IOException ignored) { }
        }
        String finish() {
            try { join(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            synchronized (bytes) { return new String(bytes.toByteArray(), StandardCharsets.UTF_8).trim(); }
        }
    }
}
