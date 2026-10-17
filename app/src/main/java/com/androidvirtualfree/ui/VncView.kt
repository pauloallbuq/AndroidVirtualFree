package com.androidvirtualfree.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.androidvirtualfree.engine.RfbClient
import java.io.File
import java.util.concurrent.Executors

/** Exibe a tela do Android convidado (VNC do QEMU via socket UNIX privado) e envia toques. */
class VncView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : SurfaceView(ctx, attrs),
    SurfaceHolder.Callback, RfbClient.Listener {

    private var client: RfbClient? = null
    private var thread: Thread? = null
    private val sender = Executors.newSingleThreadExecutor()
    private var fb: Bitmap? = null
    private val dst = RectF()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    @Volatile private var surfaceReady = false
    var onDisconnected: (() -> Unit)? = null

    init { holder.addCallback(this); isFocusable = true }

    val isConnected get() = thread?.isAlive == true

    /** Conecta (tentando até o QEMU criar o socket). */
    fun connect(socket: File) {
        if (isConnected) return
        thread = Thread({
            var attempts = 0
            while (!Thread.currentThread().isInterrupted) {
                try {
                    val s = LocalSocket()
                    s.connect(LocalSocketAddress(socket.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
                    val c = RfbClient(s.inputStream, s.outputStream, s, this)
                    client = c
                    c.handshake()
                    c.run()
                } catch (e: Exception) {
                    if (Thread.currentThread().isInterrupted) break
                    Log.d("AVF", "vnc: ${e.message}")
                    client?.close(); client = null
                    if (++attempts > 600) break
                    try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
                }
            }
            post { onDisconnected?.invoke() }
        }, "vnc").apply { start() }
    }

    fun disconnect() {
        thread?.interrupt()
        client?.close(); client = null
        thread = null
    }

    // ---- RfbClient.Listener (thread de rede) ----
    private val lock = Any()

    override fun onResize(width: Int, height: Int) {
        synchronized(lock) {
            fb?.recycle()
            fb = Bitmap.createBitmap(maxOf(1, width), maxOf(1, height), Bitmap.Config.ARGB_8888)
        }
    }

    override fun onRect(x: Int, y: Int, w: Int, h: Int, argb: IntArray) {
        synchronized(lock) {
            val b = fb ?: return
            if (x + w <= b.width && y + h <= b.height) b.setPixels(argb, 0, w, x, y, w, h)
        }
    }

    override fun onCopyRect(srcX: Int, srcY: Int, x: Int, y: Int, w: Int, h: Int) {
        synchronized(lock) {
            val b = fb ?: return
            val tmp = IntArray(w * h)
            b.getPixels(tmp, 0, w, srcX, srcY, w, h)
            b.setPixels(tmp, 0, w, x, y, w, h)
        }
    }

    override fun onFrameComplete() { draw() }

    private fun draw() {
        synchronized(lock) {
            val b = fb ?: return
            if (!surfaceReady) return
            val c: Canvas = holder.lockCanvas() ?: return
            try {
                c.drawColor(Color.BLACK)
                val scale = minOf(c.width.toFloat() / b.width, c.height.toFloat() / b.height)
                val w = b.width * scale; val h = b.height * scale
                dst.set((c.width - w) / 2, (c.height - h) / 2, (c.width + w) / 2, (c.height + h) / 2)
                c.drawBitmap(b, null, dst, paint)
            } finally {
                holder.unlockCanvasAndPost(c)
            }
        }
    }

    // ---- entrada ----
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val c = client ?: return true
        val b = fb ?: return true
        if (dst.width() <= 0) return true
        val gx = ((e.x - dst.left) / dst.width() * b.width).toInt()
        val gy = ((e.y - dst.top) / dst.height() * b.height).toInt()
        val buttons = when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> 1
            else -> 0
        }
        sender.execute { runCatching { c.sendPointer(gx, gy, buttons) } }
        return true
    }

    fun sendKey(keysym: Int) {
        val c = client ?: return
        sender.execute { runCatching { c.sendKey(keysym, true); c.sendKey(keysym, false) } }
    }

    override fun surfaceCreated(holder: SurfaceHolder) { surfaceReady = true }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceReady = true
        sender.execute { runCatching { draw() } }
    }
    override fun surfaceDestroyed(holder: SurfaceHolder) { surfaceReady = false }
}
