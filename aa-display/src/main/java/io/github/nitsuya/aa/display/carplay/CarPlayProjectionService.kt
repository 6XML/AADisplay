package io.github.nitsuya.aa.display.carplay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import io.github.nitsuya.aa.display.CoreApi
import io.github.nitsuya.aa.display.R
import io.github.nitsuya.aa.display.egress.ShellFrameEgress
import io.github.nitsuya.aa.display.ui.main.MainActivity
import java.util.concurrent.atomic.AtomicReference

/**
 * Foreground service owning [CarPlaySourceSession] for wireless CarPlay Source egress.
 *
 * Still registers a [ShellFrameEgress] sink (AA must not import this class) but exclusive
 * channel policy ignores AA shell frames — CarPlay paints only via Host GL.
 */
class CarPlayProjectionService : Service() {
    companion object {
        private val sessionRef = AtomicReference<CarPlaySourceSession?>(null)
        private val glCompositorRef = AtomicReference<CarPlayGlCompositor?>(null)

        init {
            ShellFrameEgress.install(
                object : ShellFrameEgress.Sink {
                    override fun attachShell(view: android.view.View?) {
                        sessionRef.get()?.attachShellView(view)
                    }

                    override fun onShellSize(width: Int, height: Int) {
                        sessionRef.get()?.onShellSize(width, height)
                    }
                },
            )
        }

        fun sessionOrNull(): CarPlaySourceSession? = sessionRef.get()

        /**
         * CarPlay-only Host GPU compositor (SurfaceTexture → GLES → encoder).
         * Null detaches; session will fall back to [FrameEgressTee] Canvas pump.
         */
        fun attachGlCompositor(compositor: CarPlayGlCompositor?) {
            glCompositorRef.set(compositor)
            sessionRef.get()?.attachGlCompositor(compositor)
        }

        /** Ensures the [ShellFrameEgress] sink is registered (companion init). */
        fun ensureShellSinkInstalled() {
            // Touching the companion object runs init {}.
        }

        fun start(context: Context) {
            ensureShellSinkInstalled()
            val i = Intent(context, CarPlayProjectionService::class.java).setAction(CarPlayConsts.ACTION_CONNECT)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            val i = Intent(context, CarPlayProjectionService::class.java).setAction(CarPlayConsts.ACTION_STOP)
            context.startService(i)
        }

        fun attachShell(view: android.view.View?) {
            sessionRef.get()?.attachShellView(view)
        }

        fun onShellSize(width: Int, height: Int) {
            sessionRef.get()?.onShellSize(width, height)
        }
    }

    private var session: CarPlaySourceSession? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            CarPlayConsts.ACTION_STOP -> {
                CarPlayChannel.set(this, CarPlayChannel.CHANNEL_AA)
                tearDown("stop-intent")
                stopSelf()
                return START_NOT_STICKY
            }
            CarPlayConsts.ACTION_CONNECT -> {
                CarPlayChannel.set(this, CarPlayChannel.CHANNEL_CARPLAY)
                ensureForeground()
                ensureSession()
            }
            // System restart / sticky re-delivery: only re-arm when the user chose CarPlay.
            else -> {
                if (CarPlayChannel.useCarPlayEgress(this)) {
                    ensureForeground()
                    ensureSession()
                } else {
                    tearDown("channel-aa")
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
        }
        return START_STICKY
    }

    private fun ensureForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CarPlayConsts.NOTIFICATION_CHANNEL_ID,
                "CarPlay projection",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CarPlayConsts.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("AADisplay CarPlay")
            .setContentText("Wireless CarPlay Source session")
            .setSmallIcon(R.drawable.ic_round_check_circle_24)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                CarPlayConsts.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(CarPlayConsts.NOTIFICATION_ID, notification)
        }
    }

    private fun ensureSession() {
        if (session != null) {
            CarPlayHostRuntime.ensureStartedSync(this, timeoutMs = 5_000L)
            glCompositorRef.get()?.let { session?.attachGlCompositor(it) }
            return
        }
        Log.i(CarPlayConsts.TAG, "CarPlayProjectionService CONNECT")
        // Sync Host before session: SETUP must see glCompositor, not tee test-pattern.
        val hostOk = CarPlayHostRuntime.ensureStartedSync(this, timeoutMs = 5_000L)
        Log.i(CarPlayConsts.TAG, "CarPlayProjectionService hostRuntime=$hostOk")
        val s = CarPlaySourceSession(applicationContext)
        session = s
        sessionRef.set(s)
        (glCompositorRef.get() ?: CarPlayHostRuntime.compositorOrNull())?.let {
            glCompositorRef.set(it)
            s.attachGlCompositor(it)
        }
        s.start()
        CarPlayHostActivity.launch(this)
        reportLive(true)
    }

    private fun tearDown(reason: String) {
        Log.i(CarPlayConsts.TAG, "CarPlayProjectionService tearDown reason=$reason")
        session?.stop()
        session = null
        sessionRef.set(null)
        CarPlayHostRuntime.stop(reason)
        reportLive(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun reportLive(live: Boolean) {
        try {
            CoreApi.reportCarPlaySessionLive(live)
        } catch (e: Throwable) {
            Log.e(CarPlayConsts.TAG, "reportCarPlaySessionLive failed", e)
        }
    }

    override fun onDestroy() {
        tearDown("service-destroy")
        super.onDestroy()
    }
}
