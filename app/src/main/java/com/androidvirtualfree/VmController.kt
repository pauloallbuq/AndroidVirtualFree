package com.androidvirtualfree

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.util.Log
import com.androidvirtualfree.engine.BootConfig
import com.androidvirtualfree.engine.GuestAdb
import com.androidvirtualfree.engine.GuestConsole
import android.net.LocalSocket
import android.net.LocalSocketAddress
import com.androidvirtualfree.engine.GuestImage
import com.androidvirtualfree.engine.QemuProcess
import com.androidvirtualfree.engine.VmConfig
import com.androidvirtualfree.engine.VncEndpoint
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

enum class Phase { STOPPED, PREPARING, BOOTING, RUNNING, STOPPING, ERROR }

data class VmState(val phase: Phase, val message: String = "", val progress: Int = -1)

/** Orquestra a máquina virtual. Única instância por processo; o VmService a mantém viva. */
object VmController {
    private const val TAG = "AVF"
    const val ADB_PORT = 15555
    const val VIDEO = "1280x720"
    private const val BOOT_TIMEOUT_MS = 90L * 60 * 1000

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val ops = Executors.newSingleThreadExecutor()
    private val listeners = CopyOnWriteArrayList<(VmState) -> Unit>()

    @Volatile var state = VmState(Phase.STOPPED); private set
    @Volatile private var qemu: QemuProcess? = null
    @Volatile private var stopRequested = false
    @Volatile private var restartRequested = false
    lateinit var vncSocket: File; private set
    private lateinit var adb: GuestAdb
    @Volatile private var console: GuestConsole? = null

    fun addListener(l: (VmState) -> Unit) { listeners += l; l(state) }
    fun removeListener(l: (VmState) -> Unit) { listeners -= l }

    private fun set(phase: Phase, msg: String = "", progress: Int = -1) {
        val s = VmState(phase, msg, progress)
        state = s
        Log.i(TAG, "$phase $msg")
        main.post { listeners.forEach { it(s) } }
    }

    val isActive get() = state.phase in setOf(Phase.PREPARING, Phase.BOOTING, Phase.RUNNING, Phase.STOPPING)

    fun start(ctx: Context) {
        if (isActive) return
        val app = ctx.applicationContext
        stopRequested = false
        set(Phase.PREPARING, app.getString(R.string.msg_preparing))
        worker.execute {
            try { boot(app) } catch (t: Throwable) {
                Log.e(TAG, "falha", t)
                qemu?.quit(); qemu = null
                set(Phase.ERROR, app.getString(R.string.msg_error, t.message ?: t.javaClass.simpleName))
            }
        }
    }

    private fun boot(ctx: Context) {
        val img = GuestImage(ctx)
        val free = StatFs(img.dir.path).availableBytes
        if (!img.iso.exists() && free < GuestImage.REQUIRED_FREE_BYTES)
            throw IllegalStateException(ctx.getString(R.string.msg_no_space, free / (1 shl 20)))

        img.installAssets()
        img.downloadIso { done, total ->
            val pct = if (total > 0) (done * 100 / total).toInt() else -1
            set(Phase.PREPARING, ctx.getString(R.string.msg_downloading, done / (1 shl 20), if (total > 0) total / (1 shl 20) else 0), pct)
        }
        if (stopRequested) { set(Phase.STOPPED); return }
        set(Phase.PREPARING, ctx.getString(R.string.msg_extracting))
        val (bc, files) = BootConfig.prepare(img.iso, img.bootDir, VIDEO)

        val nativeDir = ctx.applicationInfo.nativeLibraryDir
        img.linkLibraries(nativeDir, if (File(nativeDir).name == "x86_64") "x86_64" else "arm64-v8a")
        vncSocket = File(img.dir, "vnc.sock")
        val consoleSock = File(img.dir, "console.sock").apply { delete() }
        val cfg = VmConfig(
            qemuBinary = "$nativeDir/libqemu_x86_64.so",
            firmwareDir = img.firmware,
            kernel = files.first, initrd = files.second, cmdline = bc.cmdline,
            iso = img.iso, dataDisk = img.dataDisk,
            vnc = VncEndpoint.Unix(vncSocket),
            serialLog = File(img.dir, "serial.log"),
            memoryMb = memoryForDevice(ctx),
            // ttyS0 (kernel) vai para serial.log; ttyS1 = shell root do convidado
            extraArgs = listOf("-serial", "unix:${consoleSock.absolutePath},server=on,wait=off"),
        )
        val env = mapOf(
            "LD_LIBRARY_PATH" to "${img.libLinks.absolutePath}:$nativeDir",
            "TMPDIR" to ctx.cacheDir.absolutePath,
            "HOME" to img.dir.absolutePath,
        )
        val p = QemuProcess(cfg, env, File(img.dir, "qemu.log"))
        adb = GuestAdb(ADB_PORT, img.dir)
        p.start()
        qemu = p
        set(Phase.BOOTING, ctx.getString(R.string.msg_booting))

        // conecta ao console root do convidado (ttyS0) e aguarda sys.boot_completed=1
        val t0 = System.currentTimeMillis()
        var con: GuestConsole? = null
        var tuned = false
        var trimmed = false
        while (true) {
            if (!p.isAlive) throw IllegalStateException(ctx.getString(R.string.msg_qemu_died, p.logTail()))
            if (stopRequested) return
            if (System.currentTimeMillis() - t0 > BOOT_TIMEOUT_MS) throw IllegalStateException(ctx.getString(R.string.msg_boot_timeout))
            if (con == null) con = runCatching {
                val s = LocalSocket().apply { connect(LocalSocketAddress(consoleSock.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM)) }
                GuestConsole(s.inputStream, s.outputStream, s)
            }.getOrNull()?.also { console = it }
            if (con != null && !tuned) tuned = con.tuneKernel()
            if (con != null && tuned && !trimmed) trimmed = con.trimGuest()
            if (con != null && con.bootCompleted()) break
            val min = (System.currentTimeMillis() - t0) / 60000
            set(Phase.BOOTING, ctx.getString(R.string.msg_booting_wait, min))
            Thread.sleep(10_000)
        }
        set(Phase.BOOTING, ctx.getString(R.string.msg_finishing))
        runCatching { con!!.prepareAfterBoot(adb.publicKey()) }.onFailure { Log.w(TAG, "prepareAfterBoot", it) }
        set(Phase.RUNNING, ctx.getString(R.string.msg_ready, (System.currentTimeMillis() - t0) / 1000))

        // vigia o processo
        while (p.isAlive) Thread.sleep(1_000)
        qemu = null
        if (stopRequested) set(Phase.STOPPED, ctx.getString(R.string.msg_stopped))
        else set(Phase.ERROR, ctx.getString(R.string.msg_qemu_died, p.logTail()))
    }

    /** Guest RAM: 2 GB em aparelhos com ≥ 6 GB; 1,5 GB abaixo disso. */
    fun memoryForDevice(ctx: Context): Int {
        val am = ctx.getSystemService(android.app.ActivityManager::class.java)
        val mi = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return if (mi.totalMem >= 5L * 1024 * 1024 * 1024) 2048 else 1536
    }

    fun stop(ctx: Context) {
        val p = qemu
        stopRequested = true
        if (p == null) { if (state.phase != Phase.PREPARING) set(Phase.STOPPED); return }
        val graceful = state.phase == Phase.RUNNING
        set(Phase.STOPPING, ctx.getString(R.string.msg_stopping))
        Thread {
            try {
                if (graceful) console?.powerOff()
                if (!graceful || !p.waitFor(60_000)) p.quit()
            } finally {
                qemu = null
                console?.close(); console = null
                set(Phase.STOPPED, ctx.getString(R.string.msg_stopped))
                if (restartRequested) { restartRequested = false; main.post { VmService.start(ctx) } }
            }
        }.start()
    }

    /** Reinicia: desliga o convidado de forma limpa e inicia de novo (mesmo disco). */
    fun restart(ctx: Context) {
        if (state.phase != Phase.RUNNING) return
        restartRequested = true
        stop(ctx)
    }

    /** Liga/desliga o root (su) dentro do convidado. Nada muda no celular físico. */
    fun setGuestRoot(ctx: Context, enabled: Boolean, onDone: (String) -> Unit) {
        val con = console
        if (con == null || state.phase != Phase.RUNNING) { onDone(ctx.getString(R.string.msg_not_running)); return }
        ops.execute {
            val msg = runCatching {
                con.run("setprop persist.sys.root_access ${if (enabled) 3 else 0}")
                val v = con.run("getprop persist.sys.root_access").trim()
                if (v == (if (enabled) "3" else "0")) ctx.getString(if (enabled) R.string.msg_root_on else R.string.msg_root_off)
                else ctx.getString(R.string.msg_root_fail, v)
            }.getOrElse { ctx.getString(R.string.msg_root_fail, it.message ?: "?") }
            main.post { onDone(msg) }
        }
    }

    /** Lê o estado atual do root no convidado (null se a VM não estiver pronta). */
    fun guestRootEnabled(): Boolean? = runCatching {
        console?.takeIf { state.phase == Phase.RUNNING }?.run("getprop persist.sys.root_access", 10_000)?.trim()?.let { it == "3" || it == "1" }
    }.getOrNull()

    /** Envia um arquivo do celular para /sdcard/Download do convidado. */
    fun pushFile(ctx: Context, file: File, name: String, onDone: (String) -> Unit) {
        val p = qemu
        if (p == null || state.phase != Phase.RUNNING) { onDone(ctx.getString(R.string.msg_not_running)); return }
        ops.execute {
            val safe = name.replace(Regex("[^A-Za-z0-9._ -]"), "_").ifBlank { "arquivo" }
            val msg = try {
                p.openAdbForward(ADB_PORT)
                adb.push(file, "/sdcard/Download/$safe")
                val size = adb.shell("stat -c %s '/sdcard/Download/$safe'").trim().toLongOrNull()
                if (size == file.length()) ctx.getString(R.string.msg_push_ok, safe)
                else ctx.getString(R.string.msg_push_failed, "tamanho no convidado: $size")
            } catch (t: Throwable) {
                ctx.getString(R.string.msg_push_failed, t.message ?: t.javaClass.simpleName)
            } finally {
                runCatching { p.closeAdbForward(ADB_PORT) }
                file.delete()
            }
            main.post { onDone(msg) }
        }
    }

    /** Instala um APK dentro do Android convidado e o abre. */
    fun installApk(ctx: Context, apk: File, pkg: String?, onDone: (String) -> Unit) {
        val p = qemu
        if (p == null || state.phase != Phase.RUNNING) { onDone(ctx.getString(R.string.msg_not_running)); return }
        ops.execute {
            val msg = try {
                p.openAdbForward(ADB_PORT)
                val err = adb.install(apk)
                when {
                    err != null -> ctx.getString(R.string.msg_install_failed, explain(ctx, err))
                    pkg != null && !adb.isInstalled(pkg) -> ctx.getString(R.string.msg_install_failed, "pacote não aparece no convidado")
                    else -> {
                        adb.dismissAnrDialogs()
                        pkg?.let { adb.launch(it) }
                        ctx.getString(R.string.msg_install_ok, pkg ?: apk.name)
                    }
                }
            } catch (t: Throwable) {
                ctx.getString(R.string.msg_install_failed, t.message ?: t.javaClass.simpleName)
            } finally {
                runCatching { p.closeAdbForward(ADB_PORT) }
                apk.delete()
            }
            main.post { onDone(msg) }
        }
    }

    private fun explain(ctx: Context, err: String): String = when {
        "NO_MATCHING_ABIS" in err -> ctx.getString(R.string.err_abi)
        "OLDER_SDK" in err -> ctx.getString(R.string.err_sdk)
        "INSUFFICIENT_STORAGE" in err -> ctx.getString(R.string.err_storage)
        else -> err.take(300)
    }
}
