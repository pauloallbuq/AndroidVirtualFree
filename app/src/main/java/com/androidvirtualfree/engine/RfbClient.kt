package com.androidvirtualfree.engine

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Cliente RFB/VNC mínimo (sem autenticação; o socket do QEMU é privado do app).
 * Encodings: Raw, CopyRect e DesktopSize. Pixels entregues como ARGB_8888.
 */
class RfbClient(input: InputStream, output: OutputStream, private val closer: Closeable?, private val listener: Listener) : Closeable {

    interface Listener {
        fun onResize(width: Int, height: Int)
        fun onRect(x: Int, y: Int, w: Int, h: Int, argb: IntArray)
        fun onCopyRect(srcX: Int, srcY: Int, x: Int, y: Int, w: Int, h: Int)
        fun onFrameComplete()
    }

    private val inp = DataInputStream(BufferedInputStream(input, 1 shl 16))
    private val out = DataOutputStream(BufferedOutputStream(output, 4096))
    @Volatile private var closed = false
    var width = 0; private set
    var height = 0; private set
    var serverName = ""; private set

    fun handshake() {
        val ver = ByteArray(12).also { inp.readFully(it) }
        val verStr = String(ver, Charsets.US_ASCII)
        if (!verStr.startsWith("RFB ")) throw IOException("Servidor VNC inválido: $verStr")
        val minor = verStr.substring(8, 11).toIntOrNull() ?: 3
        val useMinor = if (minor >= 8) 8 else if (minor == 7) 7 else 3
        out.write("RFB 003.00$useMinor\n".toByteArray()); out.flush()

        if (useMinor == 3) {
            when (val t = inp.readInt()) {
                1 -> Unit
                0 -> throw IOException("VNC recusou: ${readReason()}")
                else -> throw IOException("Segurança VNC não suportada ($t)")
            }
        } else {
            val n = inp.readUnsignedByte()
            if (n == 0) throw IOException("VNC recusou: ${readReason()}")
            val types = ByteArray(n).also { inp.readFully(it) }
            if (types.none { it.toInt() == 1 }) throw IOException("VNC exige autenticação não suportada")
            out.writeByte(1); out.flush()
            if (useMinor == 8 && inp.readInt() != 0) throw IOException("VNC: falha na segurança: ${readReason()}")
        }
        out.writeByte(1); out.flush() // ClientInit: compartilhado
        width = inp.readUnsignedShort()
        height = inp.readUnsignedShort()
        inp.skipBytes(16)
        serverName = String(ByteArray(inp.readInt()).also { inp.readFully(it) }, Charsets.UTF_8)

        // 32 bpp, little-endian, R<<16 G<<8 B -> já é ARGB ao ler como int LE
        synchronized(out) {
            out.writeByte(0); out.write(ByteArray(3))
            out.writeByte(32); out.writeByte(24); out.writeByte(0); out.writeByte(1)
            out.writeShort(255); out.writeShort(255); out.writeShort(255)
            out.writeByte(16); out.writeByte(8); out.writeByte(0); out.write(ByteArray(3))
            val enc = intArrayOf(ENC_COPYRECT, ENC_RAW, ENC_DESKTOP_SIZE)
            out.writeByte(2); out.writeByte(0); out.writeShort(enc.size)
            enc.forEach { out.writeInt(it) }
            out.flush()
        }
        listener.onResize(width, height)
    }

    private fun readReason(): String =
        runCatching { String(ByteArray(inp.readInt()).also { inp.readFully(it) }) }.getOrDefault("?")

    private fun requestUpdate(incremental: Boolean) = synchronized(out) {
        out.writeByte(3); out.writeByte(if (incremental) 1 else 0)
        out.writeShort(0); out.writeShort(0); out.writeShort(width); out.writeShort(height)
        out.flush()
    }

    /** Laço bloqueante de recepção; termina com exceção quando o socket fecha. */
    fun run() {
        requestUpdate(false)
        var row = ByteArray(0)
        while (!closed) {
            when (val type = inp.readUnsignedByte()) {
                0 -> {
                    inp.readUnsignedByte()
                    val n = inp.readUnsignedShort()
                    var resized = false
                    for (r in 0 until n) {
                        val x = inp.readUnsignedShort(); val y = inp.readUnsignedShort()
                        val w = inp.readUnsignedShort(); val h = inp.readUnsignedShort()
                        when (val enc = inp.readInt()) {
                            ENC_RAW -> {
                                val px = IntArray(w * h)
                                val need = w * 4
                                if (row.size < need) row = ByteArray(need)
                                var i = 0
                                for (yy in 0 until h) {
                                    inp.readFully(row, 0, need)
                                    var o = 0
                                    for (xx in 0 until w) {
                                        px[i++] = (row[o].toInt() and 0xff) or ((row[o + 1].toInt() and 0xff) shl 8) or
                                            ((row[o + 2].toInt() and 0xff) shl 16) or OPAQUE
                                        o += 4
                                    }
                                }
                                listener.onRect(x, y, w, h, px)
                            }
                            ENC_COPYRECT -> {
                                val sx = inp.readUnsignedShort(); val sy = inp.readUnsignedShort()
                                listener.onCopyRect(sx, sy, x, y, w, h)
                            }
                            ENC_DESKTOP_SIZE -> {
                                width = w; height = h; resized = true
                                listener.onResize(w, h)
                            }
                            else -> throw IOException("Encoding VNC inesperado: $enc")
                        }
                    }
                    listener.onFrameComplete()
                    requestUpdate(!resized)
                }
                1 -> { inp.readUnsignedByte(); inp.readUnsignedShort(); val n = inp.readUnsignedShort(); inp.skipBytes(n * 6) }
                2 -> Unit
                3 -> { inp.skipBytes(3); val len = inp.readInt(); inp.skipBytes(len) }
                else -> throw IOException("Mensagem VNC desconhecida: $type")
            }
        }
    }

    fun sendPointer(x: Int, y: Int, buttons: Int) = synchronized(out) {
        out.writeByte(5); out.writeByte(buttons)
        out.writeShort(x.coerceIn(0, maxOf(0, width - 1))); out.writeShort(y.coerceIn(0, maxOf(0, height - 1)))
        out.flush()
    }

    fun sendKey(keysym: Int, down: Boolean) = synchronized(out) {
        out.writeByte(4); out.writeByte(if (down) 1 else 0); out.writeShort(0); out.writeInt(keysym)
        out.flush()
    }

    override fun close() {
        closed = true
        runCatching { closer?.close() }
    }

    companion object {
        const val ENC_RAW = 0
        const val ENC_COPYRECT = 1
        const val ENC_DESKTOP_SIZE = -223
        private const val OPAQUE = -0x1000000

        const val KEY_ESCAPE = 0xff1b
        const val KEY_RETURN = 0xff0d
        const val KEY_BACKSPACE = 0xff08
        const val KEY_TAB = 0xff09
        const val KEY_HOME = 0xff50
        const val KEY_LEFT = 0xff51
        const val KEY_UP = 0xff52
        const val KEY_RIGHT = 0xff53
        const val KEY_DOWN = 0xff54
    }
}
