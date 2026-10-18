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
import io.github.nitsuya.aa.display.ui.main.MainActivity
import java.util.concurrent.atomic.AtomicReference

/**
 * Foreground service owning [CarPlaySourceSession] for wireless CarPlay Source egress.
 * CarPlay paints only via Host GL.
 */
class CarPlayProjectionService : Service() {
    companion object {
        private val sessionRef = AtomicReference<CarPlaySourceSession?>(null)
        private val glCompositorRef = AtomicReference<CarPlayGlCompositor?>(null)

        /**
         * CarPlay-only Host GPU compositor (SurfaceTexture → GLES → encoder).
         * Null detaches; session will fall back to [FrameEgressTee] Canvas pump.
         */
        fun attachGlCompositor(compositor: CarPlayGlCompositor?) {
            glCompositorRef.set(compositor)
            sessionRef.get()?.attachGlCompositor(compositor)
        }

        fun start(context: Context, launchHostTrampoline: Boolean = true) {
            val i = Intent(context, CarPlayProjectionService::class.java)
                .setAction(CarPlayConsts.ACTION_CONNECT)
                .putExtra(EXTRA_LAUNCH_HOST_TRAMPOLINE, launchHostTrampoline)
            context.startForegroundService(i)
        }

        private const val EXTRA_LAUNCH_HOST_TRAMPOLINE = "launchHostTrampoline"

        /** True once [ensureSession] created a live [CarPlaySourceSession] (RFCOMM listening). */
        fun isSessionArmed(): Boolean = sessionRef.get() != null

        fun stop(context: Context) {
            val i = Intent(context, CarPlayProjectionService::class.java).setAction(CarPlayConsts.ACTION_STOP)
            context.startService(i)
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
                val launchHost = intent.getBooleanExtra(EXTRA_LAUNCH_HOST_TRAMPOLINE, true)
                ensureSession(launchHostTrampoline = launchHost)
            }
            // System restart / sticky re-delivery: only re-arm when the user chose CarPlay.
            else -> {
                if (CarPlayChannel.useCarPlayEgress(this)) {
                    ensureForeground()
                    ensureSession(launchHostTrampoline = true)
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
                "CarPlay 投屏",
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
            .setContentText("已就绪，等待车机连接")
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

    private fun ensureSession(launchHostTrampoline: Boolean = true) {
        if (session != null) {
            // Soft re-arm (AutoArm ladder / sticky restart): keep the existing
            // SourceSession + RFCOMM listen. Rebinding GL here used to recycle
            // MediaCodec 3× before the HU dialed and looked like connect failure.
            reportLive(true)
            if (!CarPlayHostRuntime.isRunning()) {
                CarPlayHostRuntime.ensureStartedSync(this, timeoutMs = 5_000L)
                glCompositorRef.get()?.let { session?.attachGlCompositor(it) }
            }
            return
        }
        Log.i(CarPlayConsts.TAG, "CarPlayProjectionService CONNECT")
        // Live flag first: Host onCreateSplitDisplay must skip FacetBar settle.
        reportLive(true)
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
        // Skip when status page already armed us — trampoline + moveTaskToBack must not
        // steal the UI task (see CarPlayHostActivity taskAffinity).
        if (launchHostTrampoline) {
            CarPlayHostActivity.launch(this)
        }
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
