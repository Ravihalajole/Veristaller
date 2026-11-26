package dev.veristaller.root

/**
 * Single-quote a value for /system/bin/sh.
 * Every path that reaches a root script goes through this.
 */
fun shQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

/**
 * Small DSL so a privileged command can never be assembled by raw string
 * concatenation without quoting. Each [line] is an independent shell line;
 * use [chained] when every command must succeed for the line to count.
 */
class ShellScript {

    private val lines = ArrayList<String>()

    fun line(command: String): ShellScript {
        if (command.isNotBlank()) lines.add(command)
        return this
    }

    fun chained(vararg commands: String): ShellScript {
        lines.add(commands.filter { it.isNotBlank() }.joinToString(" && "))
        return this
    }

    fun optional(command: String): ShellScript {
        lines.add("$command 2>/dev/null || true")
        return this
    }

    /**
     * End every script with an exit-code sentinel. libsu reports the status of
     * the last command, which would otherwise hide a failure in an earlier
     * line; RootShell strips the sentinel and uses its value instead.
     */
    fun render(): String = buildString {
        append(lines.joinToString("\n"))
        append("\necho \"__VST_EXIT:\$?\"")
    }

    override fun toString(): String = render()
}
