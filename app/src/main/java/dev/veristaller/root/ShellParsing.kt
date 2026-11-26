package dev.veristaller.root

object ShellParsing {

    data class Stat(
        val uid: String,
        val gid: String,
        val mode: String,
        val size: Long,
    ) {
        val owner: String get() = "$uid:$gid"
    }

    /** Parses the output of `stat -c '%u %g %a %s'`. */
    fun stat(output: String): Stat? {
        val parts = output.trim().lineSequence().firstOrNull()
            ?.trim()
            ?.split(Regex("\\s+"))
            ?: return null
        if (parts.size < 4) return null
        return Stat(
            uid = parts[0],
            gid = parts[1],
            mode = parts[2],
            size = parts[3].toLongOrNull() ?: return null,
        )
    }

    /** Parses a single `sha256sum <file>` line. */
    fun sha256(output: String): String? = hashes(output).firstOrNull()

    private fun hashes(output: String): List<String> = output.lineSequence()
        .mapNotNull { line ->
            val first = line.trim().substringBefore(' ')
            if (first.length == 64 && first.all { it in "0123456789abcdef" }) first else null
        }
        .toList()

    /** Strips the sentinel appended by ShellScript and returns the real exit code. */
    fun exitCode(output: String, fallback: Int): Pair<String, Int> {
        val lines = output.lineSequence().toList()
        val index = lines.indexOfFirst { it.startsWith("__VST_EXIT:") }
        if (index < 0) return output to fallback
        val code = lines[index].removePrefix("__VST_EXIT:").trim().toIntOrNull() ?: fallback
        val clean = lines.filterIndexed { i, _ -> i != index }
            .dropLastWhile { it.isBlank() }
            .joinToString("\n")
        return clean to code
    }

    data class Guard(
        val file: Boolean,
        val writable: Boolean,
        val noLink: Boolean,
        val inScope: Boolean,
        val namedBase: Boolean,
    ) {
        val safe: Boolean get() = file && writable && noLink && inScope && namedBase
    }

    /** Parses the KEY=1/0 lines emitted by RootOps.pathGuard. */
    fun guard(output: String): Guard {
        val values = HashMap<String, Boolean>()
        output.lineSequence().forEach { line ->
            val key = line.substringBefore('=').trim()
            if (key.isNotBlank()) values[key] = line.substringAfter('=').trim() == "1"
        }
        return Guard(
            file = values["FILE"] == true,
            writable = values["WRITABLE"] == true,
            noLink = values["NOLINK"] == true,
            inScope = values["SCOPE"] == true,
            namedBase = values["NAME"] == true,
        )
    }

    /** Parses `Success: created install session [1234]`. */
    fun sessionId(output: String): Int? {
        val match = Regex("\\[(\\d+)]").find(output) ?: return null
        return match.groupValues.getOrNull(1)?.toIntOrNull()
    }

    fun freeKbToBytes(kb: Long): Long = kb * 1024L
}
