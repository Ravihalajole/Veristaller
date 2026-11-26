package dev.veristaller.root

/**
 * Every privileged command the app can run. Keeping them here means the
 * command catalogue in docs/ARCHITECTURE.md and the code cannot drift apart.
 */
object RootOps {

    fun probe(): ShellScript = ShellScript()
        .line("echo \"UID=\$(id -u)\"")
        .line("echo \"SDK=\$(getprop ro.build.version.sdk)\"")
        .line("echo \"REL=\$(getprop ro.build.version.release)\"")
        .line("echo \"MODEL=\$(getprop ro.product.model)\"")
        .line("echo \"ABI=\$(getprop ro.product.cpu.abilist)\"")
        .line("echo \"ABI32=\$(getprop ro.product.cpu.abilist32)\"")
        .line("echo \"ABI64=\$(getprop ro.product.cpu.abilist64)\"")
        .line("echo \"SEL=\$(getenforce 2>/dev/null || echo unknown)\"")
        .line("echo \"FREE=\$(df -k /data 2>/dev/null | tail -n 1 | awk '{print \$4}')\"")
        .line(
            "if [ -d /data/adb/magisk ]; then echo MGR=Magisk; " +
                "elif [ -d /data/adb/ap ]; then echo MGR=APatch; " +
                "elif command -v ksud >/dev/null 2>&1; then echo MGR=KernelSU; " +
                "else echo MGR=su; fi",
        )
        .line(
            "for t in pm stat find chcon restorecon getenforce sha256sum rm mkdir cp mv am; do " +
                "command -v \$t >/dev/null 2>&1 || echo \"MISSING=\$t\"; done",
        )

    fun pmPath(pkg: String): ShellScript =
        ShellScript().line("pm path ${shQuote(pkg)}")

    fun dumpCodePath(pkg: String): ShellScript =
        ShellScript().line(
            "cmd package dump ${shQuote(pkg)} 2>/dev/null | " +
                "grep -m1 -o 'codePath=[^ ]*' | cut -d= -f2",
        )

    fun findBase(dir: String): ShellScript =
        ShellScript().line("find ${shQuote(dir)} -maxdepth 4 -name base.apk 2>/dev/null | head -n 1")

    fun stat(path: String): ShellScript =
        ShellScript().line("stat -c '%u %g %a %s' ${shQuote(path)}")

    fun context(path: String): ShellScript =
        ShellScript().line("ls -Z ${shQuote(path)} 2>/dev/null | awk '{print \$1}'")

    fun testFile(path: String): ShellScript =
        ShellScript().line("test -f ${shQuote(path)} && echo present || echo missing")

    /**
     * Final safety gate: the file must exist, sit under /data/app, not be a
     * symlink, and have a writable parent. Output is parsed by ShellParsing.
     */
    fun pathGuard(path: String): ShellScript = ShellScript().line(
        "p=${shQuote(path)}; d=\$(dirname \"\$p\"); " +
            "test -f \"\$p\" && echo FILE=1 || echo FILE=0; " +
            "test -w \"\$d\" && echo WRITABLE=1 || echo WRITABLE=0; " +
            "test ! -L \"\$p\" && echo NOLINK=1 || echo NOLINK=0; " +
            "case \"\$p\" in /data/app/*) echo SCOPE=1 ;; *) echo SCOPE=0 ;; esac; " +
            "case \"\$p\" in */base.apk) echo NAME=1 ;; *) echo NAME=0 ;; esac",
    )

    fun forceStop(pkg: String): ShellScript =
        ShellScript().line(
            "am force-stop ${shQuote(pkg)}; sleep 0.3; " +
                "pidof ${shQuote(pkg)} 2>/dev/null || true",
        )

    fun isRunning(pkg: String): ShellScript =
        ShellScript().line("pidof ${shQuote(pkg)} 2>/dev/null || true")

    /** Broader process check used by preflight, including background services. */
    fun processesFor(pkg: String): ShellScript =
        ShellScript().line(
            "dumpsys activity processes 2>/dev/null | grep -F ${shQuote(pkg)} || true",
        )

    /** Launch once and look for a fatal crash in the recent logcat ring. */
    fun smokeTest(pkg: String): ShellScript =
        ShellScript().line(
            "monkey -p ${shQuote(pkg)} -c android.intent.category.LAUNCHER 1 " +
                "2>/dev/null; echo MONKEY=\$?; sleep 5; " +
                "logcat -d -t 300 2>/dev/null | grep -F 'FATAL EXCEPTION' | " +
                "grep -F ${shQuote(pkg)} || true",
        )

    fun mkdirs(dir: String): ShellScript =
        ShellScript().line("mkdir -p ${shQuote(dir)}")

    fun copy(src: String, dst: String): ShellScript =
        ShellScript().line("cp -p ${shQuote(src)} ${shQuote(dst)}")

    fun sha256Pair(first: String, second: String): ShellScript =
        ShellScript().line("sha256sum ${shQuote(first)} ${shQuote(second)}")

    fun sha256(path: String): ShellScript =
        ShellScript().line("sha256sum ${shQuote(path)}")

    fun writeText(path: String, text: String): ShellScript =
        ShellScript().line("printf '%s' ${shQuote(text)} > ${shQuote(path)}")

    fun removeFile(path: String): ShellScript =
        ShellScript().line("rm -f ${shQuote(path)}")

    fun removeTree(path: String): ShellScript =
        ShellScript().line("rm -rf ${shQuote(path)}")

    // ---- install session -------------------------------------------------

    fun installCreate(installer: String, totalBytes: Long): ShellScript =
        ShellScript().line("pm install-create -r -S $totalBytes -i ${shQuote(installer)} --user 0")

    fun installWrite(session: Int, name: String, path: String, sizeBytes: Long): ShellScript =
        ShellScript().line("pm install-write -S $sizeBytes $session ${shQuote(name)} ${shQuote(path)}")

    fun installCommit(session: Int): ShellScript =
        ShellScript().line("pm install-commit $session")

    fun installAbandon(session: Int): ShellScript =
        ShellScript().line("pm install-abandon $session 2>/dev/null || true")

    // ---- swap ------------------------------------------------------------

    fun stageInPlace(patch: String, temp: String): ShellScript = ShellScript().line(
        "cp ${shQuote(patch)} ${shQuote(temp)} && " +
            "chown 1000:1000 ${shQuote(temp)} && " +
            "chmod 0644 ${shQuote(temp)} && " +
            "{ chcon u:object_r:apk_data_file:s0 ${shQuote(temp)} || " +
            "restorecon -F ${shQuote(temp)}; }",
    )

    fun relabel(path: String): ShellScript =
        ShellScript().line(
            "restorecon -F ${shQuote(path)} 2>/dev/null || " +
                "chcon u:object_r:apk_data_file:s0 ${shQuote(path)}",
        )

    /**
     * Ownership, mode and label in one go. Used after any operation that may
     * have created base.apk from scratch (restore, rollback), where a plain
     * copy would leave it owned by root.
     */
    fun fixupApk(path: String): ShellScript = ShellScript().line(
        "chown 1000:1000 ${shQuote(path)} && " +
            "chmod 0644 ${shQuote(path)} && " +
            "{ restorecon -F ${shQuote(path)} 2>/dev/null || " +
            "chcon u:object_r:apk_data_file:s0 ${shQuote(path)}; }",
    )

    fun move(src: String, dst: String): ShellScript =
        ShellScript().line("mv ${shQuote(src)} ${shQuote(dst)}")

    fun moveForce(src: String, dst: String): ShellScript =
        ShellScript().line("mv -f ${shQuote(src)} ${shQuote(dst)}")

    fun clearOat(dir: String): ShellScript = ShellScript()
        .line(
            "rm -rf ${shQuote("$dir/oat")} ${shQuote("$dir/oat_primary")} " +
                "${shQuote("$dir/oat_secondary")} 2>/dev/null || true",
        )
}
