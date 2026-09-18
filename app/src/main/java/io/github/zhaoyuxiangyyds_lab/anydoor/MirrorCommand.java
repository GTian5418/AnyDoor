package io.github.zhaoyuxiangyyds_lab.anydoor;

/** Atomic root snapshot publication. A delayed authorization cannot execute an obsolete write. */
public final class MirrorCommand {
    private MirrorCommand() {}
    private static String quote(String s) { return "'" + s.replace("'", "'\\''") + "'"; }
    public static String build(String path, String base64, long deadlineSeconds, long nonce) {
        return "umask 077\n"
                + "tmp=" + quote(path + ".tmp." + nonce) + "\n"
                + "trap 'rm -f \"$tmp\"' EXIT\n"
                + "[ \"$(date +%s)\" -le " + deadlineSeconds + " ] || exit 124\n"
                + "printf '%s' " + quote(base64) + " | base64 -d > \"$tmp\" && "
                + "chown 1000:1000 \"$tmp\" && chmod 600 \"$tmp\" && "
                + "chcon u:object_r:system_data_file:s0 \"$tmp\" && mv -f \"$tmp\" " + quote(path);
    }
}
