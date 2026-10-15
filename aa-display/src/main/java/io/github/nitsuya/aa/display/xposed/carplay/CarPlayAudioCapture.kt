package io.github.nitsuya.aa.display.xposed.carplay

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Handler
import android.os.HandlerThread
import io.github.nitsuya.aa.display.xposed.util.log
import io.github.nitsuya.aa.display.xposed.util.logDebug
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * system_server tap that feeds the CarPlay main audio stream (SETUP type 100).
 *
 * `AudioPlaybackCapture` (public since API 29; system uid holds CAPTURE_AUDIO_OUTPUT)
 * renders every non-call stream the user hears on the phone into 16 kHz mono PCM16, which
 * the AADisplay app pulls over [io.github.nitsuya.aa.display.xposed.ICoreManager.pullCarPlayAudio]
 * and encrypts into the CarPlay audio DataStream. While armed, the captured streams are
 * muted locally (volumes saved / restored) so the car speaker is the only audible sink —
 * parity with the Android Auto path, where the HU is the audio destination.
 *
 * Captured usages (stream bits): MUSIC (app audio, Douyin, games), NOTIFICATION,
 * VOICE_ASSISTANCE (nav TTS; stream 13 — constant removed from API 36 jar, value kept),
 * SYSTEM, ALARM. VOICE_CALL + DTMF stay on the phone — a call must ring where the user is.
 *
 * The local SDK's android.jar lacks `AudioPlaybackCapture` / `AudioPlaybackCaptureClient`,
 * so the capture plumbing is resolved reflectively (same convention as
 * [io.github.nitsuya.aa.display.xposed.hook.PhoneHidRedirect] for framework symbols
 * absent from the compile SDK). Every device running API 29+ has the real classes in
 * framework.jar; if resolution fails here we stay idle and the egress sends silence.
 *
 * This is a system capability (like VirtualDisplay creation), NOT part of the CarPlay
 * protocol stack, which stays in the app process under `carplay/` (EXECUTION.md §14 / §15 #17).
 *
 * Arm / disarm is driven by the app ([io.github.nitsuya.aa.display.xposed.ICoreManager.setCarPlayAudioEgressEnabled])
 * once its CarPlay audio stream opens / tears down — never by session liveness alone, so an
 * idle CarPlay channel never silences the phone.
 */
object CarPlayAudioCapture {
    private const val TAG = "AAD_CarPlayAudioCap"

    // STREAM_VOICE_ASSISTANCE = 13 (removed from the API 36 AudioManager surface).
    private const val STREAM_VOICE_ASSISTANCE = 13

    /**
     * Non-call streams: captured to the car AND muted locally while armed.
     * Bitmask for AudioPlaybackCaptureConfiguration.Builder(int streamTypes, boolean) —
     * each stream type is `(1 << STREAM_*)`, not the raw stream id ORed together.
     */
    private const val CAPTURED_STREAMS =
        (1 shl AudioManager.STREAM_MUSIC) or
            (1 shl AudioManager.STREAM_NOTIFICATION) or
            (1 shl STREAM_VOICE_ASSISTANCE) or
            (1 shl AudioManager.STREAM_SYSTEM) or
            (1 shl AudioManager.STREAM_ALARM)

    private val CAPTURED_STREAM_LIST = listOf(
        AudioManager.STREAM_MUSIC,
        AudioManager.STREAM_NOTIFICATION,
        STREAM_VOICE_ASSISTANCE,
        AudioManager.STREAM_SYSTEM,
        AudioManager.STREAM_ALARM,
    )

    private const val SAMPLE_RATE = 16_000
    /** ~8 s of 16 kHz mono s16 (32 KB/s) — absorbs jitter, bounds memory. */
    private const val RING_BYTES = 256 * 1024
    private const val WATCHDOG_PERIOD_MS = 30_000L
    /** No pull for this long (app process gone?) → disarm + restore volumes. */
    private const val PULL_TIMEOUT_MS = 5 * 60_000L

    private val opLock = Any()
    private val armed = AtomicBoolean(false)
    private val lastPullMs = AtomicLong(0L)

    private val ring = ByteArray(RING_BYTES)
    private val ringLock = Any()
    private var ringHead = 0
    private var ringTail = 0
    private var available = 0
    private var totalPulledBytes = 0L
    private var totalDroppedBytes = 0L

    private var audioManager: AudioManager? = null
    private var savedVolumes: IntArray? = null
    private var capture: Any? = null // AudioPlaybackCapture (resolved reflectively)
    private var client: Any? = null // AudioPlaybackCaptureClient (resolved reflectively)
    private var callbackThread: HandlerThread? = null
    private var callbackHandler: Handler? = null
    private var executor: ExecutorService? = null

    /**
     * Idempotent install from [io.github.nitsuya.aa.display.xposed.CoreManagerService.systemReady].
     * Resolves the reflective plumbing up front so [setEnabled] can fail fast with a reason.
     */
    fun install(context: Context) {
        synchronized(opLock) {
            if (audioManager != null) return
            audioManager = context.getSystemService(AudioManager::class.java)
            resolveCaptureApi()
            logDebug(TAG, "installed (idle)")
        }
    }

    fun isEnabled(): Boolean = armed.get()

    fun setEnabled(enabled: Boolean) {
        synchronized(opLock) {
            if (enabled == armed.get()) return
            if (enabled) enableLocked() else disableLocked()
        }
    }

    // ------------------------------------------------------------------
    // Reflective AudioPlaybackCapture plumbing (public API, absent from this SDK's jar)
    // ------------------------------------------------------------------

    private var createCaptureMethod: Method? = null
    private var clientBuilderCtor: java.lang.reflect.Constructor<*>? = null
    private var clientSetAudioFormatMethod: Method? = null
    private var clientBuildMethod: Method? = null
    private var clientStartMethod: Method? = null
    private var clientStopMethod: Method? = null
    private var clientReleaseMethod: Method? = null
    private var clientRegisterCallbackMethod: Method? = null
    private var onAudioAvailableInterface: Class<*>? = null
    private var configBuilderCtor: java.lang.reflect.Constructor<*>? = null
    private var apiResolved = false

    // Intentional: these framework classes are absent from the compile SDK's android.jar,
    // so lint sees only the string literals. The real classes are public since API 29.
    @SuppressLint("PrivateApi")
    private fun resolveCaptureApi() {
        runCatching {
            val amClass = AudioManager::class.java
            val captureClass = Class.forName("android.media.AudioPlaybackCapture")
            val clientClass = Class.forName("android.media.AudioPlaybackCaptureClient")
            val clientBuilderClass = Class.forName("android.media.AudioPlaybackCaptureClient\$Builder")
            val configBuilderClass = Class.forName("android.media.AudioPlaybackCaptureConfiguration\$Builder")

            createCaptureMethod = findMethod(amClass, "createAudioPlaybackCaptureConfig")?.also {
                it.isAccessible = true
            }
            clientBuilderCtor = clientBuilderClass
                .getDeclaredConstructor(captureClass, java.util.concurrent.Executor::class.java)
                .also { it.isAccessible = true }
            clientSetAudioFormatMethod = findMethod(clientBuilderClass, "setAudioFormat")
            clientBuildMethod = findMethod(clientBuilderClass, "build")
            clientStartMethod = findMethod(clientClass, "start")
            clientStopMethod = findMethod(clientClass, "stop")
            clientReleaseMethod = findMethod(clientClass, "release")
            clientRegisterCallbackMethod = findMethod(
                clientClass,
                "registerCallback",
            )?.also { it.isAccessible = true }
            onAudioAvailableInterface =
                Class.forName("android.media.AudioPlaybackCaptureClient\$OnAudioAvailableListener")

            requireNotNull(createCaptureMethod) { "AudioManager.createAudioPlaybackCaptureConfig not found" }
            requireNotNull(clientBuilderCtor) { "AudioPlaybackCaptureClient.Builder(capture, executor) not found" }
            requireNotNull(clientSetAudioFormatMethod) { "Builder.setAudioFormat not found" }
            requireNotNull(clientBuildMethod) { "Builder.build not found" }
            requireNotNull(clientStartMethod) { "AudioPlaybackCaptureClient.start not found" }
            requireNotNull(clientStopMethod) { "AudioPlaybackCaptureClient.stop not found" }
            requireNotNull(clientReleaseMethod) { "AudioPlaybackCaptureClient.release not found" }
            requireNotNull(clientRegisterCallbackMethod) { "AudioPlaybackCaptureClient.registerCallback not found" }
            requireNotNull(onAudioAvailableInterface) { "OnAudioAvailableListener not found" }

            // The (int streamTypes, boolean includeAllActivePlayback) builder — API 33+.
            configBuilderCtor = configBuilderClass
                .getConstructor(Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                .also { it.isAccessible = true }
            apiResolved = true
            log(TAG, "AudioPlaybackCapture API resolved")
        }.onFailure {
            apiResolved = false
            log(TAG, "AudioPlaybackCapture API not resolvable on this ROM — capture will be unavailable", it)
        }
    }

    private fun findMethod(clazz: Class<*>, name: String): Method? =
        clazz.methods.firstOrNull { it.name == name }?.also { it.isAccessible = true }

    /** Walks the class + superclasses (incl. interfaces) for a method by name. */
    private fun findMethodDeep(clazz: Class<*>, name: String): Method? {
        var c: Class<*>? = clazz
        while (c != null) {
            try {
                c.declaredMethods.firstOrNull { it.name == name }?.let {
                    it.isAccessible = true
                    return it
                }
            } catch (_: Throwable) {
            }
            c = c.superclass
        }
        for (iface in clazz.interfaces) {
            findMethodDeep(iface, name)?.let { return it }
        }
        return null
    }

    // ------------------------------------------------------------------
    // Arm / disarm
    // ------------------------------------------------------------------

    private fun enableLocked() {
        val am = audioManager ?: run {
            log(TAG, "setEnabled(true) before install — ignored")
            return
        }
        if (!apiResolved) {
            log(TAG, "setEnabled(true) but capture API unresolved — staying idle")
            return
        }
        // Reset the ring before the new producer exists (previous client already released).
        synchronized(ringLock) {
            ringHead = 0
            ringTail = 0
            available = 0
        }
        totalPulledBytes = 0L
        totalDroppedBytes = 0L
        val threads = HandlerThread("aad-cp-audio-capture").also { it.start() }
        val handler = Handler(threads.looper)
        val pool = Executors.newSingleThreadExecutor { r ->
            Thread(r, "aad-cp-audio-exec").apply { isDaemon = true }
        }
        var capRef: Any? = null
        return try {
            val config = requireNotNull(configBuilderCtor)
                .newInstance(CAPTURED_STREAMS, true)
            val cap = requireNotNull(createCaptureMethod)!!.invoke(am, config)
            capRef = cap
            // CHANNEL_MASK_MONO = 0x4 (constant removed from the API 36 AudioFormat surface).
            val format = AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(0x4)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build()
            val builder = requireNotNull(clientBuilderCtor)!!.newInstance(cap, pool)
            val builder2 = requireNotNull(clientSetAudioFormatMethod)!!.invoke(builder, format)
            val c = requireNotNull(clientBuildMethod)!!.invoke(builder2)

            // The callback must implement the real OnAudioAvailableListener — a dynamic
            // proxy over the reflectively-loaded interface does that without a compile dep.
            val listener = Proxy.newProxyInstance(
                c.javaClass.classLoader,
                arrayOf(requireNotNull(onAudioAvailableInterface)),
                { _, method, args ->
                    if (method.name == "onAudioAvailable") {
                        args?.getOrNull(1)?.let { a ->
                            if (a is ByteBuffer) {
                                val n = a.remaining()
                                if (n > 0) {
                                    val out = ByteArray(n)
                                    a.get(out)
                                    write(out)
                                }
                            }
                        }
                    }
                    null
                },
            )
            requireNotNull(clientRegisterCallbackMethod)!!.invoke(c, listener, handler)
            requireNotNull(clientStartMethod)!!.invoke(c)

            // Mute the captured streams locally (saved for restore on disarm).
            savedVolumes = IntArray(CAPTURED_STREAM_LIST.size) {
                am.getStreamVolume(CAPTURED_STREAM_LIST[it])
            }
            for (stream in CAPTURED_STREAM_LIST) {
                am.setStreamVolume(stream, 0, 0)
            }
            capture = cap
            client = c
            callbackThread = threads
            callbackHandler = handler
            executor = pool
            armed.set(true)
            lastPullMs.set(System.currentTimeMillis())
            log(
                TAG,
                "armed: streams=0x${CAPTURED_STREAMS.toString(16)} → CarPlay, phone muted " +
                    "(vols=${savedVolumes!!.toList()})",
            )
            scheduleWatchdog(handler)
        } catch (e: Throwable) {
            log(TAG, "enable failed — staying idle", e)
            releaseLocked(threads, handler, pool, capRef)
        }
    }

    private fun disableLocked() {
        releaseLocked(callbackThread, callbackHandler, executor, capture)
        callbackThread = null
        callbackHandler = null
        executor = null
        capture = null
        client = null
        armed.set(false)
        // Restore the saved stream volumes.
        val am = audioManager
        savedVolumes?.let { vols ->
            am?.let {
                for ((i, stream) in CAPTURED_STREAM_LIST.withIndex()) {
                    it.setStreamVolume(stream, vols[i], 0)
                }
            }
        }
        savedVolumes = null
        log(
            TAG,
            "disarmed: phone audio restored (pulled=${totalPulledBytes / 1024}KB " +
                "dropped=${totalDroppedBytes / 1024}KB)",
        )
    }

    private fun releaseLocked(
        threads: HandlerThread?,
        handler: Handler?,
        pool: ExecutorService?,
        captureRef: Any?,
    ) {
        handler?.removeCallbacksAndMessages(null)
        try {
            client?.let { clientStopMethod?.invoke(it) }
        } catch (e: Throwable) {
            logDebug(TAG, "client.stop failed: ${e.message}")
        }
        try {
            client?.let { clientReleaseMethod?.invoke(it) }
        } catch (e: Throwable) {
            logDebug(TAG, "client.release failed: ${e.message}")
        }
        client = null
        if (captureRef != null) {
            runCatching {
                findMethodDeep(captureRef.javaClass, "release")?.invoke(captureRef)
            }.onFailure { logDebug(TAG, "capture.release failed: ${it.message}") }
        }
        pool?.shutdown()
        threads?.quitSafely()
    }

    // ------------------------------------------------------------------
    // Ring buffer (producer: capture callback thread; consumer: binder pull thread)
    // ------------------------------------------------------------------

    /**
     * Drains up to [maxBytes] bytes of captured little-endian s16 mono PCM into a fresh
     * array (empty when disabled or idle). AIDL `out byte[]` cannot return into the
     * caller's buffer, so the pull result travels as the method return value.
     */
    fun pull(maxBytes: Int): ByteArray {
        if (!armed.get()) return ByteArray(0)
        lastPullMs.set(System.currentTimeMillis())
        val out = synchronized(ringLock) {
            val take = minOf(available, maxBytes).coerceAtLeast(0)
            if (take <= 0) return ByteArray(0)
            val arr = ByteArray(take)
            var off = 0
            while (off < take) {
                val chunk = minOf(available - off, RING_BYTES - ringTail, take - off)
                System.arraycopy(ring, ringTail, arr, off, chunk)
                ringTail = (ringTail + chunk) % RING_BYTES
                available -= chunk
                off += chunk
            }
            arr
        }
        totalPulledBytes += out.size
        return out
    }

    /** Capture callback: ring write, dropping the oldest when full. */
    private fun write(data: ByteArray) {
        synchronized(ringLock) {
            val free = RING_BYTES - available
            if (data.size > free) {
                val drop = data.size - free
                ringTail = (ringTail + drop) % RING_BYTES
                available -= drop
                totalDroppedBytes += drop.toLong()
            }
            var off = 0
            while (off < data.size) {
                val chunk = minOf(data.size - off, RING_BYTES - ringHead)
                System.arraycopy(data, off, ring, ringHead, chunk)
                ringHead = (ringHead + chunk) % RING_BYTES
                available += chunk
                off += chunk
            }
        }
    }

    /** Failsafe: the app is the only puller; if it dies, disarm + un-mute. */
    private fun scheduleWatchdog(handler: Handler) {
        handler.postDelayed(
            {
                if (!armed.get()) return@postDelayed
                if (System.currentTimeMillis() - lastPullMs.get() > PULL_TIMEOUT_MS) {
                    log(TAG, "no pull for ${PULL_TIMEOUT_MS / 1000}s — disarming (app gone?)")
                    setEnabled(false)
                    return@postDelayed
                }
                scheduleWatchdog(handler)
            },
            WATCHDOG_PERIOD_MS,
        )
    }
}
