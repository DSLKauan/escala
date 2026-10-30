package com.example.escala

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.view.WindowManager.LayoutParams as LP

/**
 * Foreground service: MediaProjection -> VirtualDisplay -> (SurfaceTexture/GL) -> SurfaceView em overlay.
 */
class CaptureService : Service() {

    companion object {
        const val ACTION_STOP = "com.example.escala.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        const val EXTRA_MODE = "mode"
        const val EXTRA_SCALE = "scale"
        const val EXTRA_MINI = "mini"
        private const val CHANNEL_ID = "escala"
        private const val NOTIF_ID = 1
        private const val TAG = "Escala"
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private lateinit var dm: DisplayManager

    private var projection: MediaProjection? = null
    private var projectionCb: MediaProjection.Callback? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var pipeline: GlPipeline? = null
    private var overlay: SurfaceView? = null

    private var mode = 2
    private var scalePct = 67
    private var mini = true
    private var screenW = 0
    private var screenH = 0
    private var capW = 0
    private var capH = 0
    private var dpi = 0

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) applySize()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        dm = getSystemService(DisplayManager::class.java)
    }

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (projection != null) return START_NOT_STICKY

        createChannel()
        // Precisa vir ANTES de getMediaProjection() e só depois de o usuário autorizar.
        startForeground(NOTIF_ID, buildNotification("Iniciando…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            intent.getParcelableExtra<Intent>(EXTRA_DATA)
        }
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        mode = intent.getIntExtra(EXTRA_MODE, 2)
        scalePct = intent.getIntExtra(EXTRA_SCALE, 67)
        mini = intent.getBooleanExtra(EXTRA_MINI, true)
        dpi = resources.displayMetrics.densityDpi
        val (sw, sh) = realSize()
        screenW = sw
        screenH = sh
        computeCapture()

        try {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            val p = mpm.getMediaProjection(resultCode, data)
            val cb = object : MediaProjection.Callback() {
                override fun onStop() {
                    main.post { stopSelf() }
                }
            }
            // Android 14+: o callback precisa estar registrado antes de createVirtualDisplay().
            p.registerCallback(cb, main)
            projection = p
            projectionCb = cb
            addOverlay()
            dm.registerDisplayListener(displayListener, main)
        } catch (t: Throwable) {
            Log.e(TAG, "falha ao iniciar", t)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    // ---- overlay ----------------------------------------------------------------------------

    private fun addOverlay() {
        val view = SurfaceView(this)
        view.setZOrderOnTop(true)
        view.holder.setFormat(PixelFormat.OPAQUE)
        view.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = startPipeline(holder.surface)
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) = stopPipeline()
        })
        overlay = view
        wm.addView(view, makeLp())
    }

    private fun makeLp(): LP {
        val w = if (mini) screenW / 2 else screenW
        val h = if (mini) screenH / 2 else screenH
        // FLAG_NOT_TOUCHABLE: toques passam para o jogo. (No Android 12+ o sistema limita a ~0,8 a
        // opacidade de janelas assim; ver README, "próximos passos".)
        val flags = LP.FLAG_NOT_FOCUSABLE or LP.FLAG_NOT_TOUCHABLE or
            LP.FLAG_LAYOUT_IN_SCREEN or LP.FLAG_LAYOUT_NO_LIMITS
        return LP(w, h, LP.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.OPAQUE).apply {
            gravity = if (mini) Gravity.TOP or Gravity.END else Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = LP.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            title = "escala-overlay"
        }
    }

    // ---- pipeline de captura + GL ------------------------------------------------------------

    private fun startPipeline(outSurface: Surface) {
        val p = GlPipeline(outSurface, capW, capH, mode, 0.8f) { fps ->
            main.post { updateNotification("$fps fps · captura ${capW}x$capH") }
        }
        pipeline = p
        p.start { captureSurface ->
            main.post {
                if (pipeline !== p) return@post
                try {
                    virtualDisplay = projection?.createVirtualDisplay(
                        "escala-capture", capW, capH, dpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        captureSurface, null, null
                    )
                } catch (t: Throwable) {
                    Log.e(TAG, "createVirtualDisplay falhou", t)
                    stopSelf()
                }
            }
        }
    }

    private fun stopPipeline() {
        virtualDisplay?.release()
        virtualDisplay = null
        pipeline?.stop()
        pipeline = null
    }

    private fun computeCapture() {
        capW = ((screenW * scalePct / 100) and 1.inv()).coerceAtLeast(2)
        capH = ((screenH * scalePct / 100) and 1.inv()).coerceAtLeast(2)
    }

    /** Rotação / mudança de tela: redimensiona captura e overlay. */
    private fun applySize() {
        val (sw, sh) = realSize()
        if (sw == screenW && sh == screenH) return
        screenW = sw
        screenH = sh
        computeCapture()
        pipeline?.setSourceSize(capW, capH)
        virtualDisplay?.resize(capW, capH, dpi)
        val v = overlay ?: return
        try {
            wm.updateViewLayout(v, makeLp())
        } catch (e: Exception) {
            Log.w(TAG, e)
        }
    }

    @Suppress("DEPRECATION")
    private fun realSize(): Pair<Int, Int> {
        val m = DisplayMetrics()
        dm.getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(m)
        return m.widthPixels to m.heightPixels
    }

    // ---- notificação -------------------------------------------------------------------------

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Escala", NotificationManager.IMPORTANCE_LOW)
        )
    }

    @Suppress("DEPRECATION")
    private fun buildNotification(text: String): Notification {
        val stop = PendingIntent.getService(
            this, 0, Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val action = Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Parar", stop).build()
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Escala ativa")
            .setContentText(text)
            .setOngoing(true)
            .addAction(action)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    override fun onDestroy() {
        dm.unregisterDisplayListener(displayListener)
        stopPipeline()
        overlay?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
                Log.w(TAG, e)
            }
        }
        overlay = null
        projectionCb?.let { projection?.unregisterCallback(it) }
        projection?.stop()
        projection = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
