# Regression tests

Run `python tests/run.py` from the repository. Requires JDK 17, Python 3, Node.js, Bash and an Android API jar. Set `ANDROID_SDK_ROOT` (default `D:/Android/Sdk`), `ANDROID_JAR`, or `TEST_BASH` to override locations. JSON 20240303 and KXML 2.3.0 test dependencies are downloaded from Maven Central with pinned SHA-256 checksums, into the ignored `build/tests/` directory.

Production classes are compiled directly: ProviderController, ConfigSnapshot, SpoofState, RootShell, MirrorCommand and LegacyPrefsMigration. The `stubs/` directory replaces only external platform/framework IO. RootShell uses a host Bash subprocess rather than real su. Migration injects root command output; XML parsing and migration control flow are real. MirrorCommand runs against host temporary files with chown/chcon substituted; tests verify exit propagation, preserving the previous snapshot, temporary-file cleanup, UTF-8/quoting and stale-command rejection. Diagnostic decisions execute the actual JavaScript function.

These tests do not execute Android system_server, Xposed hook installation, SELinux policy, or WeChat/DingTalk. A successful run establishes host-level behavior, not device compatibility. No test phones were connected for 1.3.3.

Physical-device regression checklist: upgrade/reboot, old-hook mismatch, Root denial then grant, single-provider failure, network toggle, repeated quick start/stop, background service loss, real-position recovery after stop, exemption changes, scoped app configuration, and app clones/work profiles. Keep OS/framework/app versions with each result.
