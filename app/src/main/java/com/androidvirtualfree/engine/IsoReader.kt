package com.androidvirtualfree.engine

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** Leitor mínimo de ISO9660 (volume primário) para extrair kernel/initrd/config da ISO do Android-x86. */
class IsoReader(file: File) : Closeable {
    data class Entry(val name: String, val lba: Long, val size: Long, val isDir: Boolean)

    private val raf = RandomAccessFile(file, "r")
    val root: Entry

    init {
        var found: Entry? = null
        var sector = 16L
        while (sector < 64) {
            val b = readSector(sector)
            if (String(b, 1, 5, Charsets.US_ASCII) != "CD001") {
                close(); throw IOException("Arquivo não é uma imagem ISO9660 válida")
            }
            val type = b[0].toInt() and 0xff
            if (type == 1) { found = parseRecord(b, 156); break }
            if (type == 255) break
            sector++
        }
        root = found ?: run { close(); throw IOException("ISO sem descritor de volume primário") }
    }

    private fun readSector(n: Long): ByteArray {
        val b = ByteArray(SECTOR)
        raf.seek(n * SECTOR)
        raf.readFully(b)
        return b
    }

    private fun le32(b: ByteArray, o: Int): Long =
        (b[o].toLong() and 0xff) or ((b[o + 1].toLong() and 0xff) shl 8) or
            ((b[o + 2].toLong() and 0xff) shl 16) or ((b[o + 3].toLong() and 0xff) shl 24)

    private fun parseRecord(b: ByteArray, o: Int): Entry? {
        val len = b[o].toInt() and 0xff
        if (len == 0) return null
        val lba = le32(b, o + 2)
        val size = le32(b, o + 10)
        val isDir = (b[o + 25].toInt() and 0x02) != 0
        val nameLen = b[o + 32].toInt() and 0xff
        val name = if (nameLen == 1 && (b[o + 33].toInt() == 0 || b[o + 33].toInt() == 1)) {
            if (b[o + 33].toInt() == 0) "." else ".."
        } else {
            String(b, o + 33, nameLen, Charsets.ISO_8859_1).substringBefore(';').trimEnd('.')
        }
        return Entry(name, lba, size, isDir)
    }

    fun list(dir: Entry): List<Entry> {
        require(dir.isDir)
        val out = ArrayList<Entry>()
        val sectors = (dir.size + SECTOR - 1) / SECTOR
        for (s in 0 until sectors) {
            val b = readSector(dir.lba + s)
            var o = 0
            while (o < SECTOR) {
                val len = b[o].toInt() and 0xff
                if (len == 0) break // registros não atravessam setores
                parseRecord(b, o)?.let { if (it.name != "." && it.name != "..") out.add(it) }
                o += len
            }
        }
        return out
    }

    /** Busca sem diferenciar maiúsculas (nomes ISO9660 nível 1 são maiúsculos). */
    fun find(path: String): Entry? {
        var cur = root
        for (part in path.trim('/').split('/').filter { it.isNotEmpty() }) {
            if (!cur.isDir) return null
            cur = list(cur).firstOrNull { it.name.equals(part, ignoreCase = true) } ?: return null
        }
        return cur
    }

    fun readBytes(e: Entry): ByteArray {
        require(e.size < 16L * 1024 * 1024)
        val b = ByteArray(e.size.toInt())
        raf.seek(e.lba * SECTOR)
        raf.readFully(b)
        return b
    }

    fun extract(e: Entry, out: File) {
        val tmp = File(out.path + ".tmp")
        RandomAccessFile(tmp, "rw").use { o ->
            o.setLength(0)
            val buf = ByteArray(1 shl 20)
            var left = e.size
            raf.seek(e.lba * SECTOR)
            while (left > 0) {
                val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) throw IOException("ISO truncada")
                o.write(buf, 0, n)
                left -= n
            }
        }
        if (!tmp.renameTo(out)) throw IOException("Falha ao gravar ${out.name}")
    }

    override fun close() = raf.close()

    companion object { const val SECTOR = 2048 }
}
