package android.os;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal host stub for the main-looper Handler. There is no background thread: a test drives the
 * queue explicitly with {@link #advance(long)}, so ordering and deadlines are deterministic.
 */
public class Handler {
    private static final int MAX_STEPS = 100000;

    private final Looper looper;
    private final List<Task> tasks = new ArrayList<Task>();
    private final List<Runnable> posted = new ArrayList<Runnable>();
    private long now;

    public Handler() { this(Looper.getMainLooper()); }
    public Handler(Looper looper) { this.looper = looper; }

    public Looper getLooper() { return looper; }
    /** Virtual time of this queue, in milliseconds. */
    public long now() { return now; }
    /** Number of callbacks still scheduled. Used to prove that nothing is left behind. */
    public int pending() { return tasks.size(); }
    /** Every runnable ever posted, in order. Used to replay a stale timer deliberately. */
    public List<Runnable> posted() { return new ArrayList<Runnable>(posted); }

    public boolean post(Runnable r) { return postDelayed(r, 0L); }

    public boolean postDelayed(Runnable r, long delayMillis) {
        if (r == null) return false;
        tasks.add(new Task(r, now + Math.max(0L, delayMillis)));
        posted.add(r);
        return true;
    }

    public void removeCallbacks(Runnable r) {
        for (int i = tasks.size() - 1; i >= 0; i--) if (tasks.get(i).run == r) tasks.remove(i);
    }

    /** Runs every task due within the next <code>ms</code> milliseconds, earliest first. */
    public void advance(long ms) {
        long target = now + Math.max(0L, ms);
        for (int step = 0; step < MAX_STEPS; step++) {
            Task next = null;
            for (Task t : tasks) if (t.due <= target && (next == null || t.due < next.due)) next = t;
            if (next == null) break;
            tasks.remove(next);
            now = Math.max(now, next.due);
            next.run.run();
        }
        now = target;
    }

    private static final class Task {
        final Runnable run;
        final long due;
        Task(Runnable run, long due) { this.run = run; this.due = due; }
    }
}
