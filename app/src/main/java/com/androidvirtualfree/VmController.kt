package com.androidvirtualfree

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.util.Log
import com.androidvirtualfree.engine.BootConfig
import com.androidvirtualfree.engine.GuestAdb
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
    private const val BOOT_TIMEOUT_MS = 40L * 60 * 1000

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val ops = Executors.newSingleThreadExecutor()
    private val listeners = CopyOnWriteArrayList<(VmState) -> Unit>()

    @Volatile var state = VmState(Phase.STOPPED); private set
    @Volatile private var qemu: QemuProcess? = null
    @Volatile private var stopRequested = false
    lateinit var vncSocket: File; private set
    private lateinit var adb: GuestAdb

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
        val cfg = VmConfig(
            qemuBinary = "$nativeDir/libqemu_x86_64.so",
            firmwareDir = img.firmware,
            kernel = files.first, initrd = files.second, cmdline = bc.cmdline,
            iso = img.iso, dataDisk = img.dataDisk,
            vnc = VncEndpoint.Unix(vncSocket),
            serialLog = File(img.dir, "serial.log"),
            memoryMb = memoryForDevice(ctx),
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

        // aguarda o Android convidado completar o boot (sys.boot_completed=1 via adb)
        p.openAdbForward(ADB_PORT)
        val t0 = System.currentTimeMillis()
        try {
            while (true) {
                if (!p.isAlive) throw IllegalStateException(ctx.getString(R.string.msg_qemu_died, p.logTail()))
                if (stopRequested) return
                if (adb.isBootCompleted()) break
                if (System.currentTimeMillis() - t0 > BOOT_TIMEOUT_MS) throw IllegalStateException(ctx.getString(R.string.msg_boot_timeout))
                val min = (System.currentTimeMillis() - t0) / 60000
                set(Phase.BOOTING, ctx.getString(R.string.msg_booting_wait, min))
                Thread.sleep(5_000)
            }
        } finally {
            runCatching { p.closeAdbForward(ADB_PORT) }
        }
        set(Phase.RUNNING, ctx.getString(R.string.msg_ready, (System.currentTimeMillis() - t0) / 1000))

        // vigia o processo
        while (p.isAlive) Thread.sleep(1_000)
        qemu = null
        if (stopRequested) set(Phase.STOPPED, ctx.getString(R.string.msg_stopped))
        else set(Phase.ERROR, ctx.getString(R.string.msg_qemu_died, p.logTail()))
    }

    /** Guest RAM: 2 GB em aparelhos com ≥ 6 GB; 1,5 GB abaixo disso. */
    private fun memoryForDevice(ctx: Context): Int {
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
                if (graceful) {
                    runCatching { p.openAdbForward(ADB_PORT) }
                    adb.powerOff()
                    runCatching { p.closeAdbForward(ADB_PORT) }
                }
                if (!graceful || !p.waitFor(45_000)) p.quit()
            } finally {
                qemu = null
                set(Phase.STOPPED, ctx.getString(R.string.msg_stopped))
            }
        }.start()
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
