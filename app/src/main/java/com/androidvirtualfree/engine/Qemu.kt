package com.androidvirtualfree.engine

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.TimeUnit

sealed class VncEndpoint {
    data class Unix(val socket: File) : VncEndpoint()
    data class Tcp(val display: Int) : VncEndpoint() { val port get() = 5900 + display }
}

data class VmConfig(
    val qemuBinary: String,
    val firmwareDir: File?,          // null = diretório padrão do QEMU do host (testes no CI)
    val kernel: File,
    val initrd: File,
    val cmdline: String,
    val iso: File,
    val dataDisk: File,
    val vnc: VncEndpoint,
    val serialLog: File,
    val memoryMb: Int = 2048,        // conservador para um celular de 6 GB
    val cpus: Int = 4,
    val accel: String = "tcg,thread=multi,tb-size=256",
    val serialSpec: String? = null,  // ex.: tcp:127.0.0.1:4555,server,nowait (console root do convidado, testes)
) {
    fun args(): List<String> {
        val a = ArrayList<String>()
        a += qemuBinary
        firmwareDir?.let { a += listOf("-L", it.absolutePath) }
        a += listOf(
            "-M", "pc", "-accel", accel,
            "-cpu", if (accel.startsWith("kvm")) "host" else "max",
            "-smp", cpus.toString(), "-m", memoryMb.toString(),
            "-kernel", kernel.absolutePath, "-initrd", initrd.absolutePath, "-append", cmdline,
            "-drive", "file=${dataDisk.absolutePath},if=ide,index=0,media=disk,format=qcow2,cache=writeback,discard=unmap",
            "-drive", "file=${iso.absolutePath},if=ide,index=2,media=cdrom,readonly=on,format=raw",
            "-netdev", "user,id=$NETDEV", "-device", "virtio-net-pci,netdev=$NETDEV,romfile=",
            "-vga", "std",
            "-usb", "-device", "usb-tablet",
            "-vnc", when (vnc) {
                is VncEndpoint.Unix -> "unix:${vnc.socket.absolutePath}"
                is VncEndpoint.Tcp -> "127.0.0.1:${vnc.display}"
            },
            "-serial", serialSpec ?: "file:${serialLog.absolutePath}",
            "-monitor", "stdio",
        )
        return a
    }

    companion object { const val NETDEV = "n0" }
}

/** Processo do QEMU. O monitor HMP fica no stdin (privado ao app, sem porta aberta). */
class QemuProcess(private val config: VmConfig, private val env: Map<String, String>, private val log: File) {
    private var process: Process? = null
    private var monitor: OutputStream? = null

    fun start() {
        (config.vnc as? VncEndpoint.Unix)?.socket?.delete()
        config.serialLog.delete()
        val pb = ProcessBuilder(config.args())
            .redirectErrorStream(true)
            .redirectOutput(log)
        pb.environment().putAll(env)
        val p = pb.start()
        process = p
        monitor = p.outputStream
    }

    val isAlive: Boolean get() = process?.isAlive == true
    fun exitCode(): Int? = process?.let { if (it.isAlive) null else it.exitValue() }

    @Synchronized
    fun monitor(cmd: String) {
        val m = monitor ?: throw IOException("QEMU não iniciado")
        m.write((cmd + "\n").toByteArray())
        m.flush()
    }

    /** Redireciona temporariamente uma porta TCP local para o adbd do convidado. */
    fun openAdbForward(hostPort: Int) = monitor("hostfwd_add ${VmConfig.NETDEV} tcp:127.0.0.1:$hostPort-:5555")
    fun closeAdbForward(hostPort: Int) = monitor("hostfwd_remove ${VmConfig.NETDEV} tcp:127.0.0.1:$hostPort")

    fun waitFor(ms: Long): Boolean = process?.waitFor(ms, TimeUnit.MILLISECONDS) ?: true

    fun quit() {
        runCatching { monitor("quit") }
        if (!waitFor(10_000)) process?.destroyForcibly()
    }

    fun logTail(lines: Int = 15): String =
        runCatching { log.readLines().filter { !it.startsWith("(qemu)") && it.isNotBlank() }.takeLast(lines).joinToString("\n") }
            .getOrDefault("")
}
