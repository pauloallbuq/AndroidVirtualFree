package com.androidvirtualfree.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BootConfigTest {
    private fun iso(): File = File(javaClass.classLoader!!.getResource("mini-android-x86.iso")!!.toURI())

    @Test fun parsesLiveEntryAndAddsPersistentData() {
        val cfg = String(IsoReader(iso()).use { it.readBytes(it.find("isolinux/isolinux.cfg")!!) })
        val bc = BootConfig.parseIsolinux(cfg, "1280x720")
        assertEquals("/kernel", bc.kernelPath)
        assertEquals("/initrd.img", bc.initrdPath)
        val t = bc.cmdline.split(' ')
        assertTrue("root=/dev/ram0" in t)
        assertTrue("androidboot.selinux=permissive" in t)
        assertTrue("buildvariant=userdebug" in t)
        assertTrue("DATA=sda" in t)
        assertTrue("video=1280x720" in t)
        assertFalse(t.any { it == "SRC=" || it == "DATA=" || it.startsWith("initrd=") || it == "INSTALL=1" })
    }

    @Test fun fallbackWhenNoConfig() {
        val bc = BootConfig.parseIsolinux("", "1024x768")
        assertTrue(bc.cmdline.startsWith("root=/dev/ram0"))
        assertTrue("DATA=sda" in bc.cmdline)
    }

    @Test fun isoReaderExtractsFilesAcrossSectors() {
        val out = Files.createTempDirectory("avf").toFile()
        val (_, files) = BootConfig.prepare(iso(), out, "1280x720")
        assertArrayEquals(ByteArray(5120) { (it % 256).toByte() }, files.first.readBytes())
        assertEquals("INITRD".repeat(1000), files.second.readText())
    }

    @Test(expected = java.io.IOException::class)
    fun rejectsNonIso() {
        val f = File.createTempFile("x", ".iso").apply { writeBytes(ByteArray(64 * 2048)) }
        IsoReader(f)
    }

    @Test fun qemuArgsArePrivateAndPersistent() {
        val d = File("/d")
        val args = VmConfig("q", d, File("/k"), File("/i"), "c", File("/iso"), File("/data.qcow2"),
            VncEndpoint.Unix(File("/s/vnc.sock")), File("/serial")).args()
        val s = args.joinToString(" ")
        assertTrue("unix:/s/vnc.sock" in s)               // VNC só por socket privado
        assertTrue("-monitor stdio" in s)                 // monitor sem porta de rede
        assertFalse("hostfwd" in s)                       // adb só é encaminhado sob demanda
        assertTrue("file=/data.qcow2,if=ide,index=0" in s) // disco persistente = sda
        assertTrue("-m 2048" in s)
    }
}
