package dev.veristaller.domain

import dev.veristaller.domain.model.JobSpec
import dev.veristaller.domain.model.SplitApk
import dev.veristaller.root.shQuote

/**
 * Human-readable command preview. Also used by dry-run, so what the user
 * reads on the review screen is exactly what the engine would execute.
 */
object JobPlan {

    fun preview(spec: JobSpec, splits: List<SplitApk>): List<String> {
        val lines = ArrayList<String>()
        val pkg = spec.patch.packageName
        val total = splits.sumOf { it.sizeBytes }
        lines += "# stage"
        lines += "cp <bundle> -> /data/local/tmp/veristaller/stage/${spec.jobId}/"
        lines += "pm install-create -r -S ${total.coerceAtLeast(0)} -i ${shQuote(spec.options.installerPackage)} --user 0"
        splits.forEach { split ->
            lines += "pm install-write <session> ${shQuote(split.fileName)} <staged>/${split.fileName}"
        }
        lines += "pm install-commit <session>"
        lines += ""
        lines += "# locate"
        lines += "pm path ${shQuote(pkg)}"
        lines += "cmd package dump ${shQuote(pkg)} | grep codePath"
        lines += "find <codePath> -maxdepth 4 -name base.apk"
        lines += "path-guard: test -f <base> -a -w <dir> -a ! -L <base>"
        lines += ""
        lines += "# backup"
        lines += "cp -p ${shQuote(spec.installed.baseApk)} ${shQuote("${BackupDir.ROOT}/$pkg/${spec.patch.versionCode}-<ts>/base.apk")}"
        lines += ""
        lines += "# swap"
        lines += "am force-stop ${shQuote(pkg)}"
        lines += "cp ${shQuote(spec.patch.file.absolutePath)} ${shQuote("${spec.installed.codePath}/.base.apk.vst.tmp")}"
        lines += "chown 1000:1000 <tmp> && chmod 0644 <tmp> && chcon u:object_r:apk_data_file:s0 <tmp>"
        lines += "rm -f ${shQuote(spec.installed.baseApk)}"
        lines += "mv <tmp> ${shQuote(spec.installed.baseApk)}"
        lines += ""
        lines += "# fixup"
        lines += "restorecon -F ${shQuote(spec.installed.baseApk)} || chcon u:object_r:apk_data_file:s0 <file>"
        if (spec.options.clearOat) {
            lines += "rm -rf ${shQuote("${spec.installed.codePath}/oat")}"
        }
        lines += ""
        lines += "# verify"
        lines += "stat -c '%u %g %a %s' ${shQuote(spec.installed.baseApk)}"
        lines += "sha256sum ${shQuote(spec.patch.file.absolutePath)} ${shQuote(spec.installed.baseApk)}"
        lines += "ls -Z ${shQuote(spec.installed.baseApk)}"
        lines += "pm path ${shQuote(pkg)}"
        if (spec.options.smokeTest) {
            lines += "monkey -p ${shQuote(pkg)} 1; sleep 5; logcat -d | grep FATAL EXCEPTION"
        }
        return lines
    }

    private object BackupDir {
        const val ROOT = "/data/local/tmp/veristaller/backups"
    }
}
