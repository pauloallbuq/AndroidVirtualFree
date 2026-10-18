package com.androidvirtualfree.engine

import dadb.AdbKeyPair
import dadb.Dadb
import dadb.InstallResult
import java.io.File

/**
 * Comunicação com o adbd do Android convidado (rede interna do QEMU, porta encaminhada
 * só para 127.0.0.1 e só enquanto há uma operação em andamento).
 */
class GuestAdb(private val port: Int, keyDir: File) {
    private val priv = File(keyDir, "adbkey")
    private val pub = File(keyDir, "adbkey.pub")
    private val keyPair: AdbKeyPair by lazy {
        ensureKey()
        AdbKeyPair.read(priv, pub)
    }

    private fun ensureKey() { if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub) }

    /** Chave pública no formato de /data/misc/adb/adb_keys (autorizada pelo console root). */
    fun publicKey(): String { ensureKey(); return pub.readText().trim() }

    fun <T> session(block: (Dadb) -> T): T =
        Dadb.create("127.0.0.1", port, keyPair, connectTimeout = 5_000, socketTimeout = 180_000).use(block)

    fun shell(cmd: String): String = session { it.shell(cmd).allOutput }

    fun isBootCompleted(): Boolean = runCatching {
        session { it.shell("getprop sys.boot_completed").output.trim() == "1" }
    }.getOrDefault(false)

    /** Instala dentro do convidado. Retorna null em caso de sucesso ou a mensagem de erro do PackageManager. */
    fun install(apk: File): String? {
        var last: String? = null
        // Sob TCG o system_server do convidado pode ser reiniciado pelo Watchdog no meio da instalação
        // ("Broken pipe", "Can't find service"): espera o Android voltar e tenta de novo (até 3x).
        repeat(3) { attempt ->
            last = runCatching {
                session { d ->
                    when (val r = d.install(apk, "-r")) {
                        is InstallResult.Success -> null
                        is InstallResult.Failure -> r.reason.trim().ifEmpty { "falha desconhecida" }
                    }
                }
            }.getOrElse { it.message ?: it.javaClass.simpleName }
            val err = last ?: return null
            if (TRANSIENT.none { it in err }) return err
            waitSystemReady(10 * 60 * 1000L)
        }
        return last
    }

    private fun waitSystemReady(timeoutMs: Long) {
        val end = System.currentTimeMillis() + timeoutMs
        Thread.sleep(15_000)
        while (System.currentTimeMillis() < end) {
            val ok = runCatching {
                shell("getprop sys.boot_completed; service check package").let { it.startsWith("1") && ": found" in it }
            }.getOrDefault(false)
            if (ok) { Thread.sleep(10_000); return }
            Thread.sleep(10_000)
        }
    }

    fun push(file: File, remotePath: String) = session { it.push(file, remotePath) }

    fun isInstalled(pkg: String): Boolean =
        shell("pm list packages $pkg").lines().any { it.trim() == "package:$pkg" }

    fun launch(pkg: String): String =
        shell("monkey -p $pkg -c android.intent.category.LAUNCHER 1")

    private companion object {
        val TRANSIENT = listOf("Broken pipe", "Can't find service", "DeadObject", "Connection reset", "closed", "timeout", "Timeout")
    }

    /** Desliga o Android convidado de forma limpa (grava o disco). */
    fun powerOff() {
        runCatching { session { it.shell("sync; reboot -p") } }
    }
}
