package com.androidvirtualfree.engine

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Console root do Android-x86 (ttyS0 do QEMU, socket privado do app).
 * Usado para detectar o fim do boot, autorizar a chave ADB do app e desligar o convidado.
 */
class GuestConsole(private val input: InputStream, private val output: OutputStream, private val closer: Closeable?) : Closeable {
    private var seq = 0
    private val buf = ByteArrayOutputStream()

    /** Executa [cmd] como root e devolve a saída (sem o eco). Lança IOException em timeout. */
    @Synchronized
    fun run(cmd: String, timeoutMs: Long = 30_000): String {
        val n = ++seq
        // o eco do terminal mostra "__AVF$((n+0))__"; só a saída real mostra "__AVF<n>__"
        val start = "__AVS$n" + "__"
        val end = "__AVF$n" + "__"
        output.write("\necho __AVS\$(($n+0))__; $cmd; echo __AVF\$(($n+0))__\n".toByteArray())
        output.flush()
        val deadline = System.currentTimeMillis() + timeoutMs
        val chunk = ByteArray(8192)
        while (true) {
            val text = buf.toString("UTF-8")
            val e = text.indexOf(end)
            if (e >= 0) {
                val s = text.lastIndexOf(start, e)
                val out = if (s >= 0) text.substring(s + start.length, e) else ""
                val rest = text.substring(e + end.length)
                buf.reset(); buf.write(rest.toByteArray())
                return out.replace("\r", "").trim('\n', ' ')
            }
            if (System.currentTimeMillis() > deadline) throw IOException("console do convidado não respondeu")
            if (input.available() > 0 || waitData(deadline)) {
                val r = input.read(chunk, 0, minOf(chunk.size, maxOf(1, input.available())))
                if (r < 0) throw IOException("console fechado")
                buf.write(chunk, 0, r)
                if (buf.size() > 4 shl 20) { val t = buf.toString("UTF-8").takeLast(1 shl 20); buf.reset(); buf.write(t.toByteArray()) }
            }
        }
    }

    private fun waitData(deadline: Long): Boolean {
        while (input.available() == 0) {
            if (System.currentTimeMillis() > deadline) return false
            Thread.sleep(50)
        }
        return true
    }

    /**
     * O init do Android liga panic_on_oops; sob TCG, um aviso do driver de vídeo (ttm_bo_vm_open, ao
     * depurar o surfaceflinger durante um ANR) vira pânico e reinicia o convidado. Desligamos o pânico:
     * o processo afetado é encerrado e o Android o reinicia, sem derrubar a máquina virtual.
     */
    fun tuneKernel(): Boolean = runCatching {
        run("echo 0 > /proc/sys/kernel/panic_on_oops; cat /proc/sys/kernel/panic_on_oops", 20_000).trim().endsWith("0")
    }.getOrDefault(false)

    /**
     * Sob emulação por software, serviços pesados que vêm na ISO (Google Play Services, Chrome, Gmail…)
     * saturam a CPU e fazem o Watchdog do Android reiniciar o system_server. Desativa-os no convidado
     * assim que o PackageManager responde (reversível: `pm enable <pacote>` como root).
     * Retorna true quando concluído.
     */
    fun trimGuest(): Boolean = runCatching {
        val list = HEAVY_PACKAGES.joinToString(" ")
        val out = run("if service check package | grep -q ': found'; then " +
            "for p in $list; do pm list packages -e \$p 2>/dev/null | grep -qx \"package:\$p\" && pm disable \$p >/dev/null 2>&1; done; " +
            "echo TRIM_DONE; else echo TRIM_WAIT; fi", 300_000)
        out.contains("TRIM_DONE")
    }.getOrDefault(false)

    fun bootCompleted(): Boolean = runCatching { run("getprop sys.boot_completed", 15_000) == "1" }.getOrDefault(false)

    /**
     * Ajustes únicos após o boot: autoriza a chave ADB pública do app, pula o assistente de
     * configuração e desliga animações (o convidado roda sem aceleração de hardware).
     */
    fun prepareAfterBoot(adbPublicKey: String) {
        val key = adbPublicKey.trim().replace("'", "")
        run("mkdir -p /data/misc/adb; grep -qF '$key' /data/misc/adb/adb_keys 2>/dev/null || echo '$key' >> /data/misc/adb/adb_keys; " +
            "chown system:shell /data/misc/adb/adb_keys; chmod 0640 /data/misc/adb/adb_keys", 120_000)
        // rede do convidado (Wi-Fi virtual sobre a placa do QEMU): liga o Wi-Fi e espera o IP
        run("settings put global wifi_on 1; svc wifi enable; for i in $(seq 1 45); do " +
            "ip -4 addr 2>/dev/null | grep -q 'inet 10\\.0\\.2\\.' && break; sleep 2; done; ip -4 addr | grep 'inet 10\\.0\\.2\\.' || echo NET_FAIL", 240_000)
        // adbd do convidado escutando na rede interna do QEMU (porta 5555; só o app alcança via hostfwd)
        run("setprop persist.adb.tcp.port 5555; setprop service.adb.tcp.port 5555; stop adbd; start adbd; sleep 2; " +
            "netstat -ltn 2>/dev/null | grep -q ':5555' && echo ADB_TCP_OK || echo ADB_TCP_FAIL", 120_000)
        run("settings put global device_provisioned 1; settings put secure user_setup_complete 1; " +
            "pm disable-user --user 0 com.google.android.setupwizard >/dev/null 2>&1; " +
            "settings put global window_animation_scale 0; settings put global transition_animation_scale 0; " +
            "settings put global animator_duration_scale 0; settings put system screen_off_timeout 2147483647; " +
            // sob emulação lenta o Android acusa "não está respondendo" à toa; não cobrir a tela com esses avisos
            "settings put global hide_error_dialogs 1; settings put secure anr_show_background 0; " +
            // a configuração só é relida numa mudança de configuração: alterna o modo noturno e volta
            "cmd uimode night yes >/dev/null 2>&1; cmd uimode night no >/dev/null 2>&1; " +
            "am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1", 400_000)
    }

    fun powerOff() { runCatching { output.write("\nsync; reboot -p\n".toByteArray()); output.flush() } }

    companion object {
        val HEAVY_PACKAGES = listOf(
            "com.google.android.gms", "com.google.android.gsf", "com.android.vending", "com.android.chrome",
            "com.google.android.gm", "com.google.android.youtube", "com.google.android.apps.maps",
            "com.google.android.music", "com.google.android.apps.photos", "com.google.android.apps.docs",
            "com.google.android.videos", "com.google.android.apps.restore", "com.google.android.setupwizard",
            "com.google.android.googlequicksearchbox", "org.android_x86.analytics",
        )
    }

    override fun close() { runCatching { closer?.close() } }
}
