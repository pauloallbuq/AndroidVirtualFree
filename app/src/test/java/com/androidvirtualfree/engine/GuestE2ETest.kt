package com.androidvirtualfree.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Teste ponta-a-ponta REAL do motor (roda no CI quando AVF_E2E_ISO está definido):
 * usa exatamente IsoReader/BootConfig/VmConfig/QemuProcess/RfbClient/GuestAdb do app para
 *  1) dar boot no Android-x86 9.0 dentro do QEMU (TCG, como no celular),
 *  2) ler a tela via VNC,
 *  3) instalar um APK DENTRO do convidado e abri-lo,
 *  4) verificar root no convidado,
 *  5) desligar, reiniciar e verificar que app e arquivo persistiram.
 */
class GuestE2ETest {
    private val env = System.getenv()
    private val out by lazy { File(env["AVF_E2E_OUT"] ?: "build/e2e").apply { mkdirs() } }
    private val report = StringBuilder()
    private fun log(s: String) { println("[e2e] $s"); report.append(s).append('\n'); File(out, "report.txt").writeText(report.toString()) }

    private class Screen : RfbClient.Listener {
        @Volatile var w = 0; @Volatile var h = 0
        @Volatile var px = IntArray(0)
        @Volatile var frames = 0
        override fun onResize(width: Int, height: Int) { synchronized(this) { w = width; h = height; px = IntArray(width * height) } }
        override fun onRect(x: Int, y: Int, w2: Int, h2: Int, argb: IntArray) = synchronized(this) {
            for (r in 0 until h2) System.arraycopy(argb, r * w2, px, (y + r) * w + x, w2)
        }
        override fun onCopyRect(srcX: Int, srcY: Int, x: Int, y: Int, w2: Int, h2: Int) = synchronized(this) {
            val tmp = IntArray(w2 * h2)
            for (r in 0 until h2) System.arraycopy(px, (srcY + r) * w + srcX, tmp, r * w2, w2)
            for (r in 0 until h2) System.arraycopy(tmp, r * w2, px, (y + r) * w + x, w2)
        }
        override fun onFrameComplete() { frames++ }
        fun savePpm(f: File) = synchronized(this) {
            f.outputStream().buffered().use { o ->
                o.write("P6\n$w $h\n255\n".toByteArray())
                for (p in px) { o.write((p shr 16) and 0xff); o.write((p shr 8) and 0xff); o.write(p and 0xff) }
            }
        }
        fun fraction(pred: (Int, Int, Int) -> Boolean): Double = synchronized(this) {
            if (px.isEmpty()) 0.0 else px.count { pred((it shr 16) and 0xff, (it shr 8) and 0xff, it and 0xff) }.toDouble() / px.size
        }
    }

    private fun bootVm(tag: String, iso: File, data: File, work: File): Triple<QemuProcess, RfbClient, Screen> {
        val (bc, files) = BootConfig.prepare(iso, File(work, "boot"), "1280x720")
        log("cmdline: ${bc.cmdline}")
        val cfg = VmConfig(
            qemuBinary = env["AVF_E2E_QEMU"] ?: "qemu-system-x86_64",
            firmwareDir = null,
            kernel = files.first, initrd = files.second, cmdline = bc.cmdline,
            iso = iso, dataDisk = data,
            vnc = VncEndpoint.Tcp(1),
            serialLog = File(out, "serial-$tag.log"),
            accel = env["AVF_E2E_ACCEL"] ?: "tcg,thread=multi,tb-size=256",
            serialSpec = "tcp:127.0.0.1:4555,server,nowait",
        )
        log("qemu: ${cfg.args().joinToString(" ")}")
        val q = QemuProcess(cfg, emptyMap(), File(out, "qemu-$tag.log"))
        q.start()
        startConsole(tag)
        val screen = Screen()
        var sock: Socket? = null
        for (i in 0 until 60) {
            sock = runCatching { Socket("127.0.0.1", 5901) }.getOrNull()
            if (sock != null) break
            assertTrue("QEMU morreu: ${q.logTail()}", q.isAlive)
            Thread.sleep(500)
        }
        val s = sock ?: error("VNC não abriu")
        val c = RfbClient(s.getInputStream(), s.getOutputStream(), s, screen)
        c.handshake()
        log("[$tag] VNC conectado: ${c.width}x${c.height} '${c.serverName}'")
        thread(isDaemon = true) { runCatching { c.run() } }
        return Triple(q, c, screen)
    }

    /** Console root (ttyS0) do Android-x86: registra logcat/dmesg periodicamente para diagnóstico. */
    private fun startConsole(tag: String) {
        thread(isDaemon = true) {
            var s: Socket? = null
            for (i in 0 until 120) { s = runCatching { Socket("127.0.0.1", 4555) }.getOrNull(); if (s != null) break; Thread.sleep(250) }
            val sock = s ?: return@thread
            val f = File(out, "console-$tag.log")
            thread(isDaemon = true) { runCatching { sock.getInputStream().copyTo(f.outputStream()) } }
            val o = sock.getOutputStream()
            runCatching {
                while (true) {
                    Thread.sleep(30_000)
                    o.write(("\necho ==== $(date) ====; getprop sys.boot_completed; getprop init.svc.adbd; getprop service.adb.tcp.port; getprop persist.adb.tcp.port; " +
                        "uptime; logcat -d -v brief -b main,system,crash -t 150; dmesg | tail -20\n").toByteArray())
                    o.flush()
                }
            }
        }
    }

    private fun waitBoot(tag: String, q: QemuProcess, adb: GuestAdb, screen: Screen): Long {
        val t0 = System.currentTimeMillis()
        q.openAdbForward(PORT)
        var shot = 0
        while (!adb.isBootCompleted()) {
            if (!q.isAlive) { Thread.sleep(3000); log("[$tag] QEMU saiu durante o boot após ${(System.currentTimeMillis() - t0) / 1000}s, exit=${q.exitCode()}") }
            assertTrue("QEMU saiu durante o boot (exit=${q.exitCode()})", q.isAlive)
            val el = System.currentTimeMillis() - t0
            assertTrue("timeout de boot ($tag)", el < 35L * 60 * 1000)
            if (el / 60000 >= shot) { screen.savePpm(File(out, "$tag-boot-${shot}min.ppm")); log("[$tag] ${el / 1000}s: aguardando boot; tela ${screen.w}x${screen.h}, frames=${screen.frames}"); shot += 2 }
            Thread.sleep(5000)
        }
        val secs = (System.currentTimeMillis() - t0) / 1000
        log("[$tag] sys.boot_completed=1 após ${secs}s")
        return secs
    }

    @Test fun guestBootsInstallsApkAndPersists() {
        val isoPath = env["AVF_E2E_ISO"]
        assumeTrue("AVF_E2E_ISO não definido: teste ponta-a-ponta ignorado", isoPath != null)
        val iso = File(isoPath!!)
        val probe = File(env["AVF_E2E_PROBE_APK"]!!)
        val work = File(out, "work").apply { mkdirs() }
        val data = File(work, "data.qcow2")
        File(env["AVF_E2E_DATA"]!!).copyTo(data, overwrite = true)
        val adb = GuestAdb(PORT, work)

        // ---------- boot 1 ----------
        var (q, c, screen) = bootVm("boot1", iso, data, work)
        waitBoot("boot1", q, adb, screen)
        log("getprop: " + adb.shell("getprop ro.build.version.release; getprop ro.product.cpu.abilist; getprop ro.debuggable; getprop ro.adb.secure; getprop ro.build.type").replace('\n', ' '))
        log("df /data: " + adb.shell("df -h /data | tail -1"))
        Thread.sleep(20_000)
        screen.savePpm(File(out, "boot1-home.ppm"))

        val err = adb.install(probe)
        log("install probe: ${err ?: "Success"}")
        assertNull("instalação no convidado falhou: $err", err)
        assertTrue("pacote não listado no convidado", adb.isInstalled(PROBE_PKG))
        log("launch: " + adb.launch(PROBE_PKG).trim().replace('\n', ' '))
        Thread.sleep(15_000)
        screen.savePpm(File(out, "boot1-probe.ppm"))
        val gold = screen.fraction { r, g, b -> r in 180..240 && g in 145..205 && b in 25..90 }
        log("fração dourada na tela após abrir o probe: ${"%.2f".format(gold)}")
        assertTrue("o app instalado não apareceu na tela do convidado (fração dourada $gold)", gold > 0.30)

        adb.shell("echo avf-persist > /data/local/tmp/avf_marker")
        log("root (su 0 id): " + adb.shell("su 0 id 2>&1").trim())
        log("root (su -c id): " + adb.shell("su -c id 2>&1").trim())
        log("persist.sys.root_access=" + adb.shell("getprop persist.sys.root_access").trim())

        adb.powerOff()
        q.closeAdbForward(PORT)
        val clean = q.waitFor(120_000)
        log("desligamento limpo via 'reboot -p': $clean (exit=${q.exitCode()})")
        if (!clean) q.quit()
        c.close()
        assertTrue("convidado não desligou de forma limpa", clean)

        // ---------- boot 2 (persistência) ----------
        val r2 = bootVm("boot2", iso, data, work); q = r2.first; c = r2.second; screen = r2.third
        waitBoot("boot2", q, adb, screen)
        val still = adb.isInstalled(PROBE_PKG)
        val marker = adb.shell("cat /data/local/tmp/avf_marker").trim()
        log("após reiniciar: pacote instalado=$still, marcador='$marker'")
        Thread.sleep(10_000)
        screen.savePpm(File(out, "boot2-home.ppm"))
        adb.powerOff(); q.closeAdbForward(PORT)
        if (!q.waitFor(120_000)) q.quit()
        c.close()
        assertTrue("app não persistiu", still)
        assertEquals("avf-persist", marker)
        log("RESULTADO: OK")
    }

    companion object {
        const val PORT = 15555
        const val PROBE_PKG = "com.androidvirtualfree.probe"
    }
}
