package dev.veristaller.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShellParsingTest {

    @Test
    fun `parses stat output`() {
        val stat = ShellParsing.stat("1000 1000 644 12345\n")
        assertEquals("1000:1000", stat?.owner)
        assertEquals("644", stat?.mode)
        assertEquals(12345L, stat?.size ?: -1L)
    }

    @Test
    fun `rejects malformed stat output`() {
        assertNull(ShellParsing.stat("oops"))
        assertNull(ShellParsing.stat("1000 1000 644 notanumber"))
    }

    @Test
    fun `parses pm install session id`() {
        assertEquals(42, ShellParsing.sessionId("Success: created install session [42]") ?: -1)
        assertNull(ShellParsing.sessionId("Failure [INSTALL_FAILED_INVALID_APK]"))
    }

    @Test
    fun `parses a hash pair in order`() {
        val first = "a".repeat(64)
        val second = "b".repeat(64)
        assertEquals(first to second, ShellParsing.sha256Pair("$first  /x\n$second  /y\n"))
    }

    @Test
    fun `parses a single hash`() {
        val hash = "0123456789abcdef".repeat(4)
        assertEquals(hash, ShellParsing.sha256("$hash  /data/app/base.apk"))
    }

    @Test
    fun `strips the exit sentinel and uses its code`() {
        val (clean, code) = ShellParsing.exitCode("present\n__VST_EXIT:7\n", 0)
        assertEquals("present", clean)
        assertEquals(7, code)
    }

    @Test
    fun `guard is safe only when every check passes`() {
        val good = ShellParsing.guard(
            "FILE=1\nWRITABLE=1\nNOLINK=1\nSCOPE=1\nNAME=1\n",
        )
        assertEquals(true, good.safe)
        val symlink = ShellParsing.guard(
            "FILE=1\nWRITABLE=1\nNOLINK=0\nSCOPE=1\nNAME=1\n",
        )
        assertEquals(false, symlink.safe)
    }

    @Test
    fun `sentinel is appended to rendered scripts`() {
        val script = dev.veristaller.root.ShellScript().line("echo hi")
        assertEquals("echo hi\necho \"__VST_EXIT:\$?\"", script.render())
    }
}
