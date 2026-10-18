package com.androidvirtualfree

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.os.StatFs
import android.text.format.Formatter
import com.androidvirtualfree.engine.GuestImage
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import com.androidvirtualfree.engine.RfbClient
import com.androidvirtualfree.ui.VncView
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

class MainActivity : Activity() {
    private lateinit var vnc: VncView
    private lateinit var startPanel: View
    private lateinit var vmBar: View
    private lateinit var status: TextView
    private lateinit var toast: TextView
    private lateinit var progress: ProgressBar
    private lateinit var btnStart: Button
    private lateinit var btnInstall: Button
    private lateinit var info: TextView
    @Volatile private var busy = false

    private val listener: (VmState) -> Unit = { render(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        vnc = findViewById(R.id.vnc)
        startPanel = findViewById(R.id.start_panel)
        vmBar = findViewById(R.id.vm_bar)
        status = findViewById(R.id.status)
        toast = findViewById(R.id.vm_message)
        progress = findViewById(R.id.progress)
        btnStart = findViewById(R.id.btn_start)
        btnInstall = findViewById(R.id.btn_install)

        btnStart.setOnClickListener {
            if (busy) return@setOnClickListener
            if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
            VmService.start(this)
        }
        btnInstall.setOnClickListener { pickApk() }
        findViewById<Button>(R.id.btn_stop).setOnClickListener { VmController.stop(this) }
        findViewById<Button>(R.id.btn_restart).setOnClickListener { VmController.restart(this) }
        findViewById<Button>(R.id.btn_push).setOnClickListener { pick(REQ_PUSH, "*/*") }
        findViewById<Button>(R.id.btn_root).setOnClickListener { rootDialog() }
        findViewById<Button>(R.id.btn_import_iso).setOnClickListener { if (stoppedOrWarn()) pick(REQ_ISO, "*/*") }
        findViewById<Button>(R.id.btn_restore).setOnClickListener { if (stoppedOrWarn()) pick(REQ_RESTORE, "*/*") }
        findViewById<Button>(R.id.btn_backup).setOnClickListener {
            if (stoppedOrWarn()) startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "application/octet-stream"
                putExtra(Intent.EXTRA_TITLE, "avf-backup-${System.currentTimeMillis() / 1000}.qcow2")
            }, REQ_BACKUP)
        }
        info = findViewById(R.id.info)
        vnc.onDisconnected = { if (VmController.isActive) VmController.vncSocket.let { vnc.connect(it) } }
        enterFullscreen()
    }

    override fun onStart() { super.onStart(); VmController.addListener(listener) }
    override fun onStop() { VmController.removeListener(listener); super.onStop() }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) enterFullscreen() }

    private fun render(s: VmState) {
        val showVm = s.phase == Phase.BOOTING || s.phase == Phase.RUNNING || s.phase == Phase.STOPPING
        startPanel.visibility = if (showVm) View.GONE else View.VISIBLE
        vnc.visibility = if (showVm) View.VISIBLE else View.INVISIBLE
        vmBar.visibility = if (showVm) View.VISIBLE else View.GONE
        btnInstall.isEnabled = s.phase == Phase.RUNNING
        btnStart.isEnabled = s.phase == Phase.STOPPED || s.phase == Phase.ERROR
        btnStart.text = getString(if (s.phase == Phase.PREPARING) R.string.btn_preparing else R.string.btn_start)
        status.text = s.message
        if (!showVm) refreshInfo()
        progress.visibility = if (s.phase == Phase.PREPARING) View.VISIBLE else View.GONE
        progress.isIndeterminate = s.progress < 0
        if (s.progress >= 0) progress.progress = s.progress
        if (s.phase != Phase.RUNNING || toast.tag == null) toast.text = s.message
        toast.visibility = if (showVm) View.VISIBLE else View.GONE
        if (showVm) vnc.connect(VmController.vncSocket) else vnc.disconnect()
    }

    private fun pickApk() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/vnd.android.package-archive", "application/octet-stream"))
        }
        startActivityForResult(i, REQ_APK)
    }

    @Deprecated("API de Activity simples, sem AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        when (requestCode) {
            REQ_APK -> { showMessage(getString(R.string.msg_installing)); Thread { prepareAndInstall(uri) }.start() }
            REQ_PUSH -> Thread { pushToGuest(uri) }.start()
            REQ_ISO -> fileOp(R.string.msg_iso_ok) { img, p -> contentResolver.openInputStream(uri)!!.use { img.importIso(it, p) } }
            REQ_RESTORE -> fileOp(R.string.msg_restore_ok) { img, p -> contentResolver.openInputStream(uri)!!.use { img.restoreData(it, p) } }
            REQ_BACKUP -> fileOp(R.string.msg_backup_ok, (GuestImage(this).dataDisk.length() shr 20).toInt()) { img, p ->
                contentResolver.openOutputStream(uri, "wt")!!.use { img.backupData(it, p) } }
        }
    }

    private fun pick(req: Int, mime: String) = startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE); type = mime
    }, req)

    private fun stoppedOrWarn(): Boolean {
        val ok = !VmController.isActive && !busy
        if (!ok) status.text = getString(R.string.msg_need_stopped)
        return ok
    }

    /** Operação de arquivo longa (ISO/backup) fora da thread de UI, com progresso real em MB. */
    private fun fileOp(okMsg: Int, vararg args: Any, op: (GuestImage, (Long) -> Unit) -> Unit) {
        busy = true
        Thread {
            val msg = try {
                op(GuestImage(this)) { done -> runOnUiThread { status.text = getString(R.string.msg_copying, (done shr 20).toInt()) } }
                getString(okMsg, *args)
            } catch (e: Exception) { getString(R.string.msg_op_failed, e.message ?: e.javaClass.simpleName) }
            runOnUiThread { busy = false; status.text = msg; refreshInfo() }
        }.start()
    }

    private fun pushToGuest(uri: Uri) {
        val name = contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "arquivo"
        val f = File(cacheDir, "push-${System.currentTimeMillis()}")
        try {
            contentResolver.openInputStream(uri)!!.use { i -> FileOutputStream(f).use { i.copyTo(it, 1 shl 20) } }
            runOnUiThread { showMessage(getString(R.string.msg_copying, (f.length() shr 20).toInt())); VmController.pushFile(this, f, name) { showMessage(it) } }
        } catch (e: Exception) {
            f.delete(); runOnUiThread { showMessage(getString(R.string.msg_push_failed, e.message ?: "?")) }
        }
    }

    private fun rootDialog() {
        Thread {
            val cur = VmController.guestRootEnabled()
            runOnUiThread {
                val label = getString(when (cur) { true -> R.string.root_on; false -> R.string.root_off; null -> R.string.root_unknown })
                AlertDialog.Builder(this)
                    .setTitle(R.string.root_title)
                    .setMessage(getString(R.string.root_text, label))
                    .setPositiveButton(if (cur == true) R.string.root_disable else R.string.root_enable) { _, _ ->
                        VmController.setGuestRoot(this, cur != true) { showMessage(it) }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }.start()
    }

    /** Memória e armazenamento reais do aparelho e do convidado. */
    private fun refreshInfo() {
        val img = GuestImage(this)
        val mi = ActivityManager.MemoryInfo().also { getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        val free = StatFs(img.dir.path).availableBytes
        info.text = getString(R.string.info_fmt,
            Formatter.formatShortFileSize(this, mi.availMem), Formatter.formatShortFileSize(this, mi.totalMem),
            VmController.memoryForDevice(this),
            Formatter.formatShortFileSize(this, free),
            if (img.dataDisk.exists()) Formatter.formatShortFileSize(this, img.dataDisk.length()) else "—",
            getString(if (img.iso.exists()) R.string.info_iso_ok else R.string.info_iso_missing))
    }

    private fun prepareAndInstall(uri: Uri) {
        val f = File(cacheDir, "install-${System.currentTimeMillis()}.apk")
        try {
            contentResolver.openInputStream(uri)!!.use { i -> FileOutputStream(f).use { i.copyTo(it, 1 shl 20) } }
            @Suppress("DEPRECATION")
            val info = packageManager.getPackageArchiveInfo(f.path, 0)
            if (info == null) { f.delete(); runOnUiThread { showMessage(getString(R.string.err_not_apk)) }; return }
            val warn = abiWarning(f)
            runOnUiThread {
                showMessage(getString(R.string.msg_installing_pkg, info.packageName) + (warn?.let { "\n$it" } ?: ""))
                VmController.installApk(this, f, info.packageName) { showMessage(it) }
            }
        } catch (e: Exception) {
            f.delete()
            runOnUiThread { showMessage(getString(R.string.msg_install_failed, e.message ?: "?")) }
        }
    }

    /** O convidado é x86_64: APKs só com bibliotecas ARM precisam de tradução (native bridge). */
    private fun abiWarning(f: File): String? = runCatching {
        ZipFile(f).use { z ->
            val abis = z.entries().asSequence().map { it.name }.filter { it.startsWith("lib/") && it.endsWith(".so") }
                .map { it.split('/')[1] }.toSet()
            if (abis.isNotEmpty() && abis.none { it == "x86" || it == "x86_64" }) getString(R.string.warn_arm_only) else null
        }
    }.getOrNull()

    private fun showMessage(m: String) {
        toast.tag = m
        toast.text = m
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (VmController.state.phase != Phase.RUNNING) return super.dispatchKeyEvent(event)
        if (event.action != KeyEvent.ACTION_DOWN) return true
        val sym = when (event.keyCode) {
            KeyEvent.KEYCODE_BACK -> RfbClient.KEY_ESCAPE        // Esc = Voltar no Android-x86
            KeyEvent.KEYCODE_ENTER -> RfbClient.KEY_RETURN
            KeyEvent.KEYCODE_DEL -> RfbClient.KEY_BACKSPACE
            KeyEvent.KEYCODE_TAB -> RfbClient.KEY_TAB
            KeyEvent.KEYCODE_DPAD_LEFT -> RfbClient.KEY_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> RfbClient.KEY_RIGHT
            KeyEvent.KEYCODE_DPAD_UP -> RfbClient.KEY_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> RfbClient.KEY_DOWN
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> return super.dispatchKeyEvent(event)
            else -> event.unicodeChar.takeIf { it in 0x20..0xff } ?: return super.dispatchKeyEvent(event)
        }
        vnc.sendKey(sym)
        return true
    }

    private fun enterFullscreen() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        }
    }

    companion object {
        private const val REQ_APK = 42
        private const val REQ_PUSH = 43
        private const val REQ_ISO = 44
        private const val REQ_BACKUP = 45
        private const val REQ_RESTORE = 46
    }
}
