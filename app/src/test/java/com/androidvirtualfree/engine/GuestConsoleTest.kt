package com.androidvirtualfree.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.concurrent.thread

/** Simula o shell do ttyS0 (com eco do terminal e lixo de edição de linha). */
class GuestConsoleTest {
    @Test fun runIgnoresEchoAndReturnsOutput() {
        val toGuest = PipedOutputStream(); val guestIn = PipedInputStream(toGuest, 1 shl 16)
        val fromGuest = PipedOutputStream(); val hostIn = PipedInputStream(fromGuest, 1 shl 16)
        val sent = ArrayList<String>()
        thread(isDaemon = true) {
            guestIn.bufferedReader().forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                sent += line
                fromGuest.write("console:/ # $line\r\n".toByteArray())          // eco
                val m = Regex("""echo __AVS\$\(\((\d+)\+0\)\)__; (.*); echo __AVF""").find(line) ?: return@forEachLine
                val n = m.groupValues[1]; val cmd = m.groupValues[2]
                val out = when (cmd) { "getprop sys.boot_completed" -> "1"; "id" -> "uid=0(root) gid=0(root)"; else -> "ok:$cmd" }
                fromGuest.write("__AVS${n}__\r\n$out\r\n__AVF${n}__\r\nconsole:/ # ".toByteArray())
                fromGuest.flush()
            }
        }
        val c = GuestConsole(hostIn, toGuest, null)
        assertTrue(c.bootCompleted())
        assertEquals("uid=0(root) gid=0(root)", c.run("id"))
        c.prepareAfterBoot("QAAAAKEY unknown@unknown")
        assertTrue(sent.any { "adb_keys" in it && "QAAAAKEY unknown@unknown" in it })
        assertTrue(sent.any { "device_provisioned 1" in it })
        c.powerOff()
    }
}
