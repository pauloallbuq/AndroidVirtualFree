package com.androidvirtualfree.engine

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Arquivos do Android convidado: ISO (baixada uma vez), disco persistente e firmwares do QEMU. */
class GuestImage(private val ctx: Context) {
    val dir = File(ctx.filesDir, "vm").apply { mkdirs() }
    val iso = File(dir, ISO_NAME)
    val dataDisk = File(dir, "data.qcow2")
    val firmware = File(dir, "qemu")
    val bootDir = File(dir, "boot")
    val libLinks = File(dir, "libs")

    /**
     * Bibliotecas do Termux com soname versionado (libz.so.1) são empacotadas como libz_so_1.so.
     * Cria symlinks com o nome original apontando para nativeLibraryDir (os binários não são alterados).
     */
    fun linkLibraries(nativeDir: String, abi: String) {
        libLinks.deleteRecursively(); libLinks.mkdirs()
        val map = runCatching { ctx.assets.open("qemu/libmap-$abi.txt").bufferedReader().readLines() }.getOrDefault(emptyList())
        for (line in map) {
            val (name, file) = line.trim().split(' ').takeIf { it.size == 2 } ?: continue
            android.system.Os.symlink("$nativeDir/$file", File(libLinks, name).path)
        }
    }

    /** Copia firmwares e o disco /data inicial (somente se ainda não existir: preserva os dados). */
    fun installAssets() {
        val am = ctx.assets
        val stamp = File(firmware, ".version")
        val ver = ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime.toString()
        if (stamp.takeIf { it.exists() }?.readText() != ver) {
            firmware.deleteRecursively()
            copyAssetDir("qemu", firmware)
            stamp.writeText(ver)
        }
        if (!dataDisk.exists()) {
            val tmp = File(dir, "data.qcow2.tmp")
            am.open("vm/data.qcow2").use { i -> FileOutputStream(tmp).use { i.copyTo(it, 1 shl 20) } }
            if (!tmp.renameTo(dataDisk)) throw IOException("Falha ao criar o disco de dados")
        }
    }

    private fun copyAssetDir(path: String, out: File) {
        val children = ctx.assets.list(path) ?: emptyArray()
        if (children.isEmpty()) {
            out.parentFile?.mkdirs()
            ctx.assets.open(path).use { i -> FileOutputStream(out).use { i.copyTo(it) } }
            return
        }
        out.mkdirs()
        for (c in children) copyAssetDir("$path/$c", File(out, c))
    }

    /** Baixa a ISO oficial do Android-x86 (com retomada). [progress] recebe (baixado, total). */
    fun downloadIso(progress: (Long, Long) -> Unit) {
        if (iso.exists()) return
        val part = File(dir, "$ISO_NAME.part")
        var attempts = 0
        while (true) {
            try {
                downloadOnce(part, progress)
                break
            } catch (e: IOException) {
                if (++attempts >= 5) throw IOException("Falha no download do Android-x86: ${e.message}", e)
                Thread.sleep(3_000L * attempts)
            }
        }
        // valida antes de aceitar
        runCatching { IsoReader(part).use { r -> r.find("kernel") ?: throw IOException("kernel ausente") } }
            .onFailure { part.delete(); throw IOException("ISO baixada está corrompida; tente novamente", it) }
        if (!part.renameTo(iso)) throw IOException("Falha ao salvar a ISO")
    }

    private fun downloadOnce(part: File, progress: (Long, Long) -> Unit) {
        var url = URL(ISO_URL)
        var c: HttpURLConnection? = null
        var redirects = 0
        val have = part.length()
        while (true) {
            val conn = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000; readTimeout = 60_000
                setRequestProperty("User-Agent", "Wget/1.21.4")
                if (have > 0) setRequestProperty("Range", "bytes=$have-")
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location") ?: throw IOException("redirecionamento sem destino")
                conn.disconnect()
                url = URL(url, loc)
                if (++redirects > 10) throw IOException("redirecionamentos demais")
                continue
            }
            if (code == 416 && have > 0) { conn.disconnect(); return } // já completo
            if (code != 200 && code != 206) throw IOException("HTTP $code")
            if (conn.contentType?.startsWith("text/html") == true) throw IOException("espelho retornou página HTML")
            c = conn
            break
        }
        val conn = c!!
        val append = conn.responseCode == 206
        val len = conn.contentLengthLong
        val total = if (len > 0) (if (append) have + len else len) else -1L
        var done = if (append) have else 0L
        conn.inputStream.use { inp ->
            FileOutputStream(part, append).use { out ->
                val buf = ByteArray(1 shl 16)
                var last = 0L
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (done - last > (2 shl 20)) { progress(done, total); last = done }
                }
            }
        }
        if (total > 0 && done < total) throw IOException("download incompleto")
        progress(done, total)
    }

    companion object {
        const val ISO_NAME = "android-x86_64-9.0-r2.iso"
        const val ISO_URL = "https://downloads.sourceforge.net/project/android-x86/Release%209.0/android-x86_64-9.0-r2.iso"
        const val REQUIRED_FREE_BYTES = 3L * 1024 * 1024 * 1024
    }
}
