package com.androidvirtualfree.engine

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Servidor RFB 3.8 falso: valida handshake, pixel format, Raw, CopyRect, DesktopSize e eventos de entrada. */
class RfbClientTest {
    @Test fun fullProtocolRoundTrip() {
        val server = ServerSocket(0)
        val received = ArrayList<String>()
        val serverDone = CountDownLatch(1)
        val requestsRead = CountDownLatch(1)
        thread {
            server.accept().use { s ->
                val i = DataInputStream(s.getInputStream()); val o = DataOutputStream(s.getOutputStream())
                o.write("RFB 003.008\n".toByteArray()); o.flush()
                val v = ByteArray(12); i.readFully(v); received += String(v).trim()
                o.writeByte(1); o.writeByte(1); o.flush()               // 1 tipo: None
                received += "sec=${i.readUnsignedByte()}"
                o.writeInt(0); o.flush()                               // SecurityResult OK
                received += "shared=${i.readUnsignedByte()}"
                o.writeShort(4); o.writeShort(2); o.write(ByteArray(16)); o.writeInt(4); o.write("QEMU".toByteArray()); o.flush()
                received += "msg=${i.readUnsignedByte()}"; i.skipBytes(3)  // SetPixelFormat
                received += "bpp=${i.readUnsignedByte()}"; i.skipBytes(15)
                received += "msg=${i.readUnsignedByte()}"; i.skipBytes(1)  // SetEncodings
                val n = i.readUnsignedShort(); received += "enc=" + (0 until n).map { i.readInt() }
                received += "msg=${i.readUnsignedByte()} inc=${i.readUnsignedByte()}"; i.skipBytes(8)
                // update: rect raw 2x1 em (0,0), copyrect 2x1 de (0,0) para (2,0)
                o.writeByte(0); o.writeByte(0); o.writeShort(2)
                o.writeShort(0); o.writeShort(0); o.writeShort(2); o.writeShort(1); o.writeInt(0)
                o.write(byteArrayOf(0x30, 0x20, 0x10, 0, 0x03, 0x02, 0x01, 0))   // BGRX
                o.writeShort(2); o.writeShort(0); o.writeShort(2); o.writeShort(1); o.writeInt(1)
                o.writeShort(0); o.writeShort(0)
                // DesktopSize em outro update
                o.writeByte(0); o.writeByte(0); o.writeShort(1)
                o.writeShort(0); o.writeShort(0); o.writeShort(8); o.writeShort(6); o.writeInt(-223)
                o.flush()
                received += "msg=${i.readUnsignedByte()} inc=${i.readUnsignedByte()}"; i.skipBytes(8) // incremental
                received += "msg=${i.readUnsignedByte()} inc=${i.readUnsignedByte()}"; i.skipBytes(8) // full após resize
                requestsRead.countDown()
                received += "ptr=${i.readUnsignedByte()} b=${i.readUnsignedByte()} x=${i.readUnsignedShort()} y=${i.readUnsignedShort()}"
                received += "key=${i.readUnsignedByte()} d=${i.readUnsignedByte()}".also { i.skipBytes(2) } + " sym=${Integer.toHexString(i.readInt())}"
                serverDone.countDown()
                Thread.sleep(200)
            }
        }
        val events = ArrayList<String>()
        val pixels = IntArray(8 * 6)
        var w = 0
        val gotResize = CountDownLatch(2)
        val sock = Socket("127.0.0.1", server.localPort)
        val c = RfbClient(sock.getInputStream(), sock.getOutputStream(), sock, object : RfbClient.Listener {
            override fun onResize(width: Int, height: Int) { w = width; events += "resize ${width}x$height"; gotResize.countDown() }
            override fun onRect(x: Int, y: Int, w2: Int, h: Int, argb: IntArray) {
                for (k in argb.indices) pixels[y * w + x + k] = argb[k]; events += "raw $x,$y ${w2}x$h"
            }
            override fun onCopyRect(srcX: Int, srcY: Int, x: Int, y: Int, w2: Int, h: Int) {
                for (k in 0 until w2) pixels[y * w + x + k] = pixels[srcY * w + srcX + k]; events += "copy $srcX,$srcY->$x,$y"
            }
            override fun onFrameComplete() { events += "frame" }
        })
        c.handshake()
        assertEquals("QEMU", c.serverName)
        val t = thread { runCatching { c.run() } }
        assertEquals(true, gotResize.await(5, TimeUnit.SECONDS))
        assertEquals(true, requestsRead.await(5, TimeUnit.SECONDS))
        c.sendPointer(3, 2, 1)
        c.sendKey(RfbClient.KEY_ESCAPE, true)
        assertEquals(true, serverDone.await(5, TimeUnit.SECONDS))
        c.close(); t.join(2000)

        assertEquals(0xFF102030.toInt(), pixels[0])
        assertEquals(0xFF010203.toInt(), pixels[1])
        assertEquals(pixels[0], pixels[2])
        assertEquals(pixels[1], pixels[3])
        assertEquals(listOf("resize 4x2", "raw 0,0 2x1", "copy 0,0->2,0", "frame", "resize 8x6", "frame"), events)
        assertEquals(listOf(
            "RFB 003.008", "sec=1", "shared=1", "msg=0", "bpp=32", "msg=2", "enc=[1, 0, -223]",
            "msg=3 inc=0", "msg=3 inc=1", "msg=3 inc=0", "ptr=5 b=1 x=3 y=2", "key=4 d=1 sym=ff1b"), received)
    }
}
