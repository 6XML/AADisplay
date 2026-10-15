package io.github.nitsuya.aa.display.xposed.carplay

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Process
import android.os.UserHandle
import io.github.nitsuya.aa.display.xposed.util.log
import io.github.nitsuya.aa.display.xposed.util.logDebug
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * system_server tap that feeds **two exclusive** CarPlay audio streams:
 * - **media** — entertainment (SETUP `audioType=media`, 48 kHz stereo) via [pull]
 * - **speech** — navigation / assistant (SETUP `audioType=speech`, 16 kHz mono) via [pullSpeech]
 *
 * Separation rules (DiPlay mixes both; duplicate PCM = echo):
 * - Media capture matches only USAGE_MEDIA/GAME/UNKNOWN ([AudioPlaybackCaptureConfiguration]
 *   + [MediaProjection]; fallback [MediaRecorder.AudioSource.REMOTE_SUBMIX] is unfiltered).
 * - Speech capture is AudioPolicy [AudioMix] loopback for NAVIGATION/ASSISTANT/SONIFICATION
 *   only. Never downsample or remotesubmix-mirror media into speech — silence is preferred
 *   over double content. MediaProjection cannot legally match NAVIGATION usage.
 *
 * Mute: playback-capture → stream mute (pre-volume tap) + local-device mute.
 * remotesubmix → no mute (device mute zeros the tap on some OEMs).
 *
 * Arm / disarm follows the CarPlay FGS session ([reportCarPlaySessionLive]), not
 * individual RTSP audio SETUP/TEARDOWN — soft reconnect must not unmute the phone.
 */
object CarPlayAudioCapture {
    private const val TAG = "AAD_CarPlayAudioCap"

    // STREAM_VOICE_ASSISTANCE = 13 (removed from the API 36 AudioManager surface).
    private const val STREAM_VOICE_ASSISTANCE = 13

    /** Streams muted on the phone while playback-capture media is armed (car is the sink). */
    private val MUTED_STREAMS = listOf(
        AudioManager.STREAM_MUSIC,
        AudioManager.STREAM_NOTIFICATION,
        STREAM_VOICE_ASSISTANCE,
        AudioManager.STREAM_SYSTEM,
        AudioManager.STREAM_ALARM,
    )

    private val MEDIA_CAPTURE_USAGES = intArrayOf(
        AudioAttributes.USAGE_MEDIA,
        AudioAttributes.USAGE_GAME,
        AudioAttributes.USAGE_UNKNOWN,
    )

    /** Nav / assistant prompts that belong on the CarPlay speech channel. */
    private val SPEECH_CAPTURE_USAGES = intArrayOf(
        AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,
        AudioAttributes.USAGE_ASSISTANCE_SONIFICATION,
        AudioAttributes.USAGE_ASSISTANT,
    )

    /** DiPlay entertainment media: PCM_FORMAT[0x8000] = 48 kHz stereo. */
    private const val MEDIA_SAMPLE_RATE = 48_000
    private const val MEDIA_CHANNEL_MASK = AudioFormat.CHANNEL_IN_STEREO
    /** DiPlay voice bit PCM_FORMAT[0x10] = 16 kHz mono. */
    private const val SPEECH_SAMPLE_RATE = 16_000
    private const val SPEECH_CHANNEL_MASK = AudioFormat.CHANNEL_IN_MONO

    private const val MEDIA_BYTES_PER_MS = MEDIA_SAMPLE_RATE * 2 /* ch */ * 2 /* s16 */ / 1000
    private const val SPEECH_BYTES_PER_MS = SPEECH_SAMPLE_RATE * 1 /* ch */ * 2 /* s16 */ / 1000
    /**
     * Hard cap ~200ms @ 48 kHz stereo s16. Prefer a short skip over lip-lag; a taller
     * ring only let Binder stalls ship stale PCM while video always takes the latest frame.
     */
    private const val MEDIA_RING_BYTES = MEDIA_BYTES_PER_MS * 200
    /** Hard cap ~400ms @ 16 kHz mono s16. */
    private const val SPEECH_RING_BYTES = SPEECH_BYTES_PER_MS * 400
    /**
     * Drop-to-live hysteresis: trim when backlog exceeds [LIVE_HIGH_MS], down to
     * [LIVE_LOW_MS] (under DiPlay audioLatencyMs). Keep the floor near one–two packets
     * so a matched producer/consumer cannot sit forever at ~200ms of sticky latency.
     * Applied on both write and pull so capture drops stale PCM before TX recovers.
     */
    private const val LIVE_HIGH_MS = 100
    private const val LIVE_LOW_MS = 40
    private const val MEDIA_HIGH_WATERMARK_BYTES = MEDIA_BYTES_PER_MS * LIVE_HIGH_MS
    private const val MEDIA_LOW_WATERMARK_BYTES = MEDIA_BYTES_PER_MS * LIVE_LOW_MS
    private const val SPEECH_HIGH_WATERMARK_BYTES = SPEECH_BYTES_PER_MS * LIVE_HIGH_MS
    private const val SPEECH_LOW_WATERMARK_BYTES = SPEECH_BYTES_PER_MS * LIVE_LOW_MS
    private const val WATCHDOG_PERIOD_MS = 30_000L
    private const val PULL_TIMEOUT_MS = 5 * 60_000L
    private const val MEDIA_READ_BYTES = 3_840 // 20 ms @ 48 kHz stereo s16
    private const val SPEECH_READ_BYTES = 640 // 20 ms @ 16 kHz mono s16

    private val opLock = Any()
    private val armed = AtomicBoolean(false)
    private val lastPullMs = AtomicLong(0L)

    private val mediaRing = PcmRing(
        MEDIA_RING_BYTES,
        MEDIA_HIGH_WATERMARK_BYTES,
        MEDIA_LOW_WATERMARK_BYTES,
    )
    private val speechRing = PcmRing(
        SPEECH_RING_BYTES,
        SPEECH_HIGH_WATERMARK_BYTES,
        SPEECH_LOW_WATERMARK_BYTES,
    )

    private var appContext: Context? = null
    private var audioManager: AudioManager? = null
    private var savedVolumes: IntArray? = null
    /** Parallel to [savedVolumes] — only streams that accepted get/set on this OEM. */
    private var mutedStreamTypes: IntArray? = null
    private var phoneMuted = false
    /** Remotesubmix path: speaker-device volume zeroed (stream mute would silence the mix). */
    private var speakerDeviceMuted = false
    private var savedSpeakerVolumes: IntArray? = null
    private var savedSpeakerStreamTypes: IntArray? = null
    private var mediaProjection: MediaProjection? = null
    private var mediaRecord: AudioRecord? = null
    private var speechRecord: AudioRecord? = null
    private var audioPolicy: Any? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var readerRunning = AtomicBoolean(false)
    @Volatile
    private var mediaReaderThread: Thread? = null
    @Volatile
    private var speechReaderThread: Thread? = null
    @Volatile private var mediaMode: String = "none"
    @Volatile private var speechMode: String = "none"
    private var totalMediaPulled = 0L
    private var totalSpeechPulled = 0L
    /** Peak |s16| seen by media reader since arm (0 ⇒ likely muted tap). */
    private val mediaPcmPeak = AtomicInteger(0)

    fun install(context: Context) {
        synchronized(opLock) {
            if (audioManager != null) return
            appContext = context.applicationContext ?: context
            audioManager = context.getSystemService(AudioManager::class.java)
            logDebug(TAG, "installed (idle)")
        }
    }

    fun isEnabled(): Boolean = armed.get()

    fun setEnabled(enabled: Boolean) {
        synchronized(opLock) {
            if (enabled == armed.get()) return
            // AIDL arrives with the app uid; MediaProjection / REMOTE_SUBMIX / AudioPolicy
            // all check Binder.getCallingUid() for CAPTURE_AUDIO_OUTPUT /
            // MANAGE_MEDIA_PROJECTION. Drop to system identity for the arm window.
            val identity = Binder.clearCallingIdentity()
            try {
                if (enabled) enableLocked() else disableLocked()
            } finally {
                Binder.restoreCallingIdentity(identity)
            }
        }
    }

    // ------------------------------------------------------------------
    // Arm / disarm
    // ------------------------------------------------------------------

    private fun enableLocked() {
        val am = audioManager ?: run {
            log(TAG, "setEnabled(true) before install — ignored")
            return
        }
        val ctx = appContext ?: run {
            log(TAG, "setEnabled(true) without context — ignored")
            return
        }
        mediaRing.clear()
        speechRing.clear()
        totalMediaPulled = 0L
        totalSpeechPulled = 0L
        mediaPcmPeak.set(0)
        phoneMuted = false
        speakerDeviceMuted = false
        savedSpeakerVolumes = null
        savedSpeakerStreamTypes = null
        mutedStreamTypes = null

        val threads = HandlerThread("aad-cp-audio-capture").also { it.start() }
        val handler = Handler(threads.looper)
        var proj: MediaProjection? = null
        var mediaRec: AudioRecord? = null
        var speechRec: AudioRecord? = null
        var policy: Any? = null
        var mMode = "none"
        var sMode = "none"
        try {
            proj = obtainMediaProjection(ctx)
            if (proj != null) {
                runCatching {
                    proj.registerCallback(
                        object : MediaProjection.Callback() {
                            override fun onStop() {
                                log(TAG, "MediaProjection stopped — disarming")
                                setEnabled(false)
                            }
                        },
                        handler,
                    )
                }
                mediaRec = openPlaybackCaptureRecord(proj, MEDIA_SAMPLE_RATE, MEDIA_CHANNEL_MASK, MEDIA_CAPTURE_USAGES)
                if (mediaRec != null) mMode = "playback-capture"
            }
            if (mediaRec == null) {
                mediaRec = openRemoteSubmixRecord(MEDIA_SAMPLE_RATE, MEDIA_CHANNEL_MASK)
                mMode = "remote-submix"
            }
            if (mediaRec == null || mediaRec.state != AudioRecord.STATE_INITIALIZED) {
                val st = mediaRec?.state ?: -1
                throw IllegalStateException(
                    "media AudioRecord not initialized (mode=$mMode state=$st)",
                )
            }
            mediaRec.startRecording()
            if (mediaRec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException("media AudioRecord failed to start (mode=$mMode)")
            }

            // Speech: AudioPolicy loopback for nav/assistant. REMOTE_SUBMIX only when media
            // is also remotesubmix (no mute) — otherwise mute would zero the speech mix.
            val speechPair = openSpeechRecord(ctx, mediaIsPlaybackCapture = mMode == "playback-capture")
            speechRec = speechPair.first
            policy = speechPair.second
            sMode = speechPair.third
            if (speechRec != null) {
                if (speechRec.state != AudioRecord.STATE_INITIALIZED) {
                    log(TAG, "speech AudioRecord not initialized (mode=$sMode) — speech silence")
                    runCatching { speechRec.release() }
                    speechRec = null
                    unregisterAudioPolicy(am, policy)
                    policy = null
                    sMode = "none"
                } else {
                    speechRec.startRecording()
                    if (speechRec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                        log(TAG, "speech AudioRecord failed to start (mode=$sMode) — speech silence")
                        runCatching { speechRec.release() }
                        speechRec = null
                        unregisterAudioPolicy(am, policy)
                        policy = null
                        sMode = "none"
                    }
                }
            }

            // Start readers BEFORE mute so the first packs are not racing a silent mix.
            mediaProjection = proj
            mediaRecord = mediaRec
            speechRecord = speechRec
            audioPolicy = policy
            captureThread = threads
            captureHandler = handler
            mediaMode = mMode
            speechMode = sMode
            armed.set(true)
            lastPullMs.set(System.currentTimeMillis())
            startMediaReaderLocked(mediaRec, mMode)
            speechRec?.let { startSpeechReaderLocked(it) }

            // Kill phone-side sinks so CarPlay is the only audible path (phone speaker /
            // wired / A2DP→车机 BT 与 WiFi CarPlay 叠在一起就是「重音」).
            // playback-capture: stream mute (pre-volume tap still hears apps) + device mute.
            // remotesubmix: do NOT mute — on Samsung, setStreamVolumeIndex(0) on SPEAKER
            // often zeros the remote-submix tap too (all-zero PCM → car silent). Accept
            // possible phone+car double on this fallback path.
            if (mMode == "playback-capture") {
                // OEM stream tables differ — STREAM_VOICE_ASSISTANCE(13) throws
                // "Bad stream type" on Samsung API 33 and used to abort the whole arm
                // after playback-capture was already live (phone unmuted again).
                phoneMuted = mutePhoneStreams(am)
                speakerDeviceMuted = muteLocalPlaybackDevices()
            } else {
                log(TAG, "media=$mMode — skip local mute so remotesubmix tap stays live")
            }

            log(
                TAG,
                "armed: media=$mMode speech=$sMode usagesMedia=${MEDIA_CAPTURE_USAGES.toList()} " +
                    "usagesSpeech=${SPEECH_CAPTURE_USAGES.toList()} phoneMuted=$phoneMuted " +
                    "speakerDeviceMuted=$speakerDeviceMuted (vols=${savedVolumes?.toList()})",
            )
            scheduleWatchdog(handler)
        } catch (e: Throwable) {
            log(TAG, "enable failed — staying idle", e)
            runCatching { mediaRec?.release() }
            runCatching { speechRec?.release() }
            unregisterAudioPolicy(am, policy)
            runCatching { proj?.stop() }
            threads.quitSafely()
            mediaProjection = null
            mediaRecord = null
            speechRecord = null
            audioPolicy = null
            captureThread = null
            captureHandler = null
            mediaMode = "none"
            speechMode = "none"
            armed.set(false)
        }
    }

    private fun disableLocked() {
        readerRunning.set(false)
        mediaReaderThread?.interrupt()
        mediaReaderThread = null
        speechReaderThread?.interrupt()
        speechReaderThread = null
        try {
            mediaRecord?.stop()
        } catch (e: Throwable) {
            logDebug(TAG, "media AudioRecord.stop failed: ${e.message}")
        }
        try {
            mediaRecord?.release()
        } catch (e: Throwable) {
            logDebug(TAG, "media AudioRecord.release failed: ${e.message}")
        }
        mediaRecord = null
        try {
            speechRecord?.stop()
        } catch (e: Throwable) {
            logDebug(TAG, "speech AudioRecord.stop failed: ${e.message}")
        }
        try {
            speechRecord?.release()
        } catch (e: Throwable) {
            logDebug(TAG, "speech AudioRecord.release failed: ${e.message}")
        }
        speechRecord = null
        unregisterAudioPolicy(audioManager, audioPolicy)
        audioPolicy = null
        try {
            mediaProjection?.stop()
        } catch (e: Throwable) {
            logDebug(TAG, "MediaProjection.stop failed: ${e.message}")
        }
        mediaProjection = null
        captureHandler?.removeCallbacksAndMessages(null)
        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null
        armed.set(false)
        if (phoneMuted) {
            restorePhoneStreams(audioManager)
        }
        if (speakerDeviceMuted) {
            restoreLocalPlaybackDevices()
        }
        savedVolumes = null
        mutedStreamTypes = null
        phoneMuted = false
        speakerDeviceMuted = false
        savedSpeakerVolumes = null
        savedSpeakerStreamTypes = null
        log(
            TAG,
            "disarmed: media=$mediaMode speech=$speechMode " +
                "pulledMedia=${totalMediaPulled / 1024}KB pulledSpeech=${totalSpeechPulled / 1024}KB " +
                "dropMedia=${mediaRing.dropped / 1024}KB dropSpeech=${speechRing.dropped / 1024}KB",
        )
        mediaMode = "none"
        speechMode = "none"
    }

    private fun startMediaReaderLocked(record: AudioRecord, mode: String) {
        readerRunning.set(true)
        mediaReaderThread = Thread(
            {
                val buf = ByteArray(MEDIA_READ_BYTES)
                var written = 0L
                var firstNonZeroLogged = false
                while (readerRunning.get() && !Thread.currentThread().isInterrupted) {
                    val n = try {
                        record.read(buf, 0, buf.size)
                    } catch (e: Throwable) {
                        log(TAG, "media AudioRecord.read failed", e)
                        break
                    }
                    if (n > 0) {
                        val chunk = if (n == buf.size) buf else buf.copyOf(n)
                        mediaRing.write(chunk)
                        written += n
                        var peak = 0
                        var i = 0
                        while (i + 1 < n) {
                            val s = (chunk[i].toInt() and 0xff) or (chunk[i + 1].toInt() shl 8)
                            val abs = if (s < 0) -s else s
                            if (abs > peak) peak = abs
                            i += 2
                        }
                        if (peak > mediaPcmPeak.get()) mediaPcmPeak.set(peak)
                        if (!firstNonZeroLogged && peak > 0) {
                            firstNonZeroLogged = true
                            log(TAG, "media capture first non-zero PCM mode=$mode written=${written}B peak=$peak")
                        }
                    } else if (n < 0) {
                        log(TAG, "media AudioRecord.read error=$n — stopping reader")
                        break
                    }
                }
            },
            "aad-cp-audio-media",
        ).also {
            it.isDaemon = true
            it.start()
        }
    }

    private fun startSpeechReaderLocked(record: AudioRecord) {
        speechReaderThread = Thread(
            {
                val buf = ByteArray(SPEECH_READ_BYTES)
                var written = 0L
                var firstNonZeroLogged = false
                while (readerRunning.get() && !Thread.currentThread().isInterrupted) {
                    val n = try {
                        record.read(buf, 0, buf.size)
                    } catch (e: Throwable) {
                        log(TAG, "speech AudioRecord.read failed", e)
                        break
                    }
                    if (n > 0) {
                        val chunk = if (n == buf.size) buf else buf.copyOf(n)
                        speechRing.write(chunk)
                        written += n
                        if (!firstNonZeroLogged && chunk.any { it != 0.toByte() }) {
                            firstNonZeroLogged = true
                            log(TAG, "speech capture first non-zero PCM mode=$speechMode written=${written}B")
                        }
                    } else if (n < 0) {
                        log(TAG, "speech AudioRecord.read error=$n — stopping reader")
                        break
                    }
                }
            },
            "aad-cp-audio-speech",
        ).also {
            it.isDaemon = true
            it.start()
        }
    }

    /**
     * Per-stream mute that tolerates OEM-missing types (e.g. Samsung rejects stream 13).
     * Returns true if at least MUSIC (or any listed stream) was muted.
     */
    private fun mutePhoneStreams(am: AudioManager): Boolean {
        val vols = ArrayList<Int>(MUTED_STREAMS.size)
        val types = ArrayList<Int>(MUTED_STREAMS.size)
        for (stream in MUTED_STREAMS) {
            val cur = runCatching { am.getStreamVolume(stream) }.getOrNull() ?: continue
            runCatching { am.setStreamMute(stream, true) }
            val setOk = runCatching { am.setStreamVolume(stream, 0, 0) }
            if (setOk.isFailure) {
                logDebug(TAG, "setStreamVolume($stream) skipped: ${setOk.exceptionOrNull()?.message}")
                continue
            }
            types.add(stream)
            vols.add(cur)
        }
        if (types.isEmpty()) {
            log(TAG, "phone stream mute: no usable streams")
            return false
        }
        mutedStreamTypes = types.toIntArray()
        savedVolumes = vols.toIntArray()
        log(TAG, "phone stream mute ok streams=${types} saved=${vols}")
        return true
    }

    private fun restorePhoneStreams(am: AudioManager?) {
        if (am == null) return
        val types = mutedStreamTypes ?: return
        val vols = savedVolumes ?: return
        for (i in types.indices) {
            if (i >= vols.size) break
            val stream = types[i]
            val vol = vols[i]
            runCatching { am.setStreamMute(stream, false) }
            runCatching { am.setStreamVolume(stream, vol, 0) }
        }
    }

    /**
     * Zero MUSIC (etc.) on every **local** output device via
     * [AudioSystem.setStreamVolumeIndex], leaving [DEVICE_OUT_REMOTE_SUBMIX] alone so
     * remotesubmix capture still hears the mix. Includes A2DP — a phone still bonded to
     * the car over BT music while CarPlay WiFi audio runs is a common 「重音」 source.
     */
    private fun muteLocalPlaybackDevices(): Boolean {
        return try {
            val audioSystem = Class.forName("android.media.AudioSystem")
            val getIndex = audioSystem.methods.first { m ->
                m.name == "getStreamVolumeIndex" && m.parameterTypes.size == 2
            }
            val setIndex = audioSystem.methods.first { m ->
                m.name == "setStreamVolumeIndex" && m.parameterTypes.size == 3
            }
            val devices = localPlaybackDeviceMasks(audioSystem)
            if (devices.isEmpty()) return false
            // Prefer streams already validated by mutePhoneStreams; else probe MUTED_STREAMS.
            val streams = mutedStreamTypes?.toList() ?: MUTED_STREAMS
            val saved = ArrayList<Int>(streams.size * devices.size)
            val applied = ArrayList<Int>()
            for (stream in streams) {
                var ok = true
                val before = saved.size
                for (device in devices) {
                    val cur = runCatching {
                        getIndex.invoke(null, stream, device) as? Int
                    }.getOrNull()
                    if (cur == null) {
                        ok = false
                        break
                    }
                    saved.add(cur)
                    if (runCatching { setIndex.invoke(null, stream, 0, device) }.isFailure) {
                        ok = false
                        break
                    }
                }
                if (!ok) {
                    while (saved.size > before) saved.removeAt(saved.lastIndex)
                    continue
                }
                applied.add(stream)
            }
            if (applied.isEmpty()) return false
            savedSpeakerVolumes = saved.toIntArray()
            savedSpeakerStreamTypes = applied.toIntArray()
            log(
                TAG,
                "local-device mute applied streams=$applied " +
                    "devices=${devices.map { "0x${it.toString(16)}" }}",
            )
            true
        } catch (e: Throwable) {
            log(TAG, "local-device mute failed", e)
            false
        }
    }

    private fun restoreLocalPlaybackDevices() {
        val saved = savedSpeakerVolumes ?: return
        val streams = savedSpeakerStreamTypes?.toList()
            ?: mutedStreamTypes?.toList()
            ?: MUTED_STREAMS
        try {
            val audioSystem = Class.forName("android.media.AudioSystem")
            val setIndex = audioSystem.methods.first { m ->
                m.name == "setStreamVolumeIndex" && m.parameterTypes.size == 3
            }
            val devices = localPlaybackDeviceMasks(audioSystem)
            var si = 0
            for (stream in streams) {
                for (device in devices) {
                    if (si >= saved.size) return
                    runCatching { setIndex.invoke(null, stream, saved[si++], device) }
                }
            }
            log(TAG, "local-device mute restored")
        } catch (e: Throwable) {
            logDebug(TAG, "local-device mute restore failed: ${e.message}")
        }
    }

    /** All OUT sinks except REMOTE_SUBMIX (and 0). */
    private fun localPlaybackDeviceMasks(audioSystem: Class<*>): IntArray {
        val names = listOf(
            "DEVICE_OUT_EARPIECE",
            "DEVICE_OUT_SPEAKER",
            "DEVICE_OUT_SPEAKER_SAFE",
            "DEVICE_OUT_WIRED_HEADSET",
            "DEVICE_OUT_WIRED_HEADPHONE",
            "DEVICE_OUT_BLUETOOTH_SCO",
            "DEVICE_OUT_BLUETOOTH_SCO_HEADSET",
            "DEVICE_OUT_BLUETOOTH_SCO_CARKIT",
            "DEVICE_OUT_BLUETOOTH_A2DP",
            "DEVICE_OUT_BLUETOOTH_A2DP_HEADPHONES",
            "DEVICE_OUT_BLUETOOTH_A2DP_SPEAKER",
            "DEVICE_OUT_USB_ACCESSORY",
            "DEVICE_OUT_USB_DEVICE",
            "DEVICE_OUT_USB_HEADSET",
            "DEVICE_OUT_HDMI",
            "DEVICE_OUT_HDMI_ARC",
            "DEVICE_OUT_LINE",
        )
        return names.mapNotNull { name ->
            runCatching { audioSystem.getField(name).getInt(null) }.getOrNull()
                ?.takeIf { it != 0 }
        }.distinct().toIntArray()
    }

    /** True when an exclusive nav/assistant AudioPolicy tap is armed (not media bleed). */
    fun isSpeechCaptureReady(): Boolean = synchronized(opLock) {
        armed.get() && speechMode == "audio-policy-mix" && speechRecord != null
    }

    private fun openPlaybackCaptureRecord(
        proj: MediaProjection,
        sampleRate: Int,
        channelMask: Int,
        usages: IntArray,
    ): AudioRecord? {
        return try {
            val configBuilder = AudioPlaybackCaptureConfiguration.Builder(proj)
            for (usage in usages) {
                configBuilder.addMatchingUsage(usage)
            }
            val config = configBuilder.build()
            val format = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(channelMask)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build()
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                channelMask,
                AudioFormat.ENCODING_PCM_16BIT,
            ).coerceAtLeast(if (sampleRate >= 40_000) MEDIA_READ_BYTES else SPEECH_READ_BYTES)
            // Keep the native capture buffer small — minBuf*4 held ~100ms+ of stale PCM
            // before it ever reached the ring during rapid screen switches.
            AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBuf * 2)
                .setAudioPlaybackCaptureConfig(config)
                .build()
        } catch (e: Throwable) {
            log(TAG, "playback-capture open failed usages=${usages.toList()}", e)
            null
        }
    }

    private fun openRemoteSubmixRecord(sampleRate: Int, channelMask: Int): AudioRecord? {
        return try {
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                channelMask,
                AudioFormat.ENCODING_PCM_16BIT,
            ).coerceAtLeast(if (sampleRate >= 40_000) MEDIA_READ_BYTES else SPEECH_READ_BYTES)
            AudioRecord(
                MediaRecorder.AudioSource.REMOTE_SUBMIX,
                sampleRate,
                channelMask,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2,
            )
        } catch (e: Throwable) {
            log(TAG, "REMOTE_SUBMIX open failed rate=$sampleRate", e)
            null
        }
    }

    /**
     * @return Triple(record, audioPolicyOrNull, modeLabel)
     */
    private fun openSpeechRecord(
        ctx: Context,
        mediaIsPlaybackCapture: Boolean,
    ): Triple<AudioRecord?, Any?, String> {
        openSpeechViaAudioPolicy(ctx)?.let { (rec, policy) ->
            return Triple(rec, policy, "audio-policy-mix")
        }
        // Do not open a second REMOTE_SUBMIX for speech: it captures the same mix as media
        // and DiPlay plays both → music echo. Prefer silence over duplicate content.
        if (mediaIsPlaybackCapture) {
            log(TAG, "no speech AudioPolicy mix — nav prompts not in MediaProjection allowlist")
        } else {
            log(TAG, "no speech tap — leaving speech silent (avoid media/speech double)")
        }
        return Triple(null, null, "none")
    }

    /**
     * system_server AudioPolicy + AudioMix loopback for speech/nav usages.
     * Soft-fails on OEMs that hide or restrict the API.
     */
    private fun openSpeechViaAudioPolicy(ctx: Context): Pair<AudioRecord, Any>? {
        return try {
            val ruleBuilderClass = Class.forName("android.media.audiopolicy.AudioMixingRule\$Builder")
            val ruleBuilder = ruleBuilderClass.getDeclaredConstructor().newInstance()
            val addRule = ruleBuilderClass.methods.first { m ->
                m.name == "addRule" && m.parameterTypes.size == 2
            }
            val ruleMatchUsage = Class.forName("android.media.audiopolicy.AudioMixingRule")
                .getField("RULE_MATCH_ATTRIBUTE_USAGE")
                .getInt(null)
            for (usage in SPEECH_CAPTURE_USAGES) {
                val attrs = AudioAttributes.Builder().setUsage(usage).build()
                addRule.invoke(ruleBuilder, attrs, ruleMatchUsage)
            }
            val rule = ruleBuilderClass.getMethod("build").invoke(ruleBuilder)

            val mixBuilderClass = Class.forName("android.media.audiopolicy.AudioMix\$Builder")
            val mixBuilder = mixBuilderClass.getConstructor(
                Class.forName("android.media.audiopolicy.AudioMixingRule"),
            ).newInstance(rule)
            val format = AudioFormat.Builder()
                .setSampleRate(SPEECH_SAMPLE_RATE)
                .setChannelMask(SPEECH_CHANNEL_MASK)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build()
            mixBuilderClass.getMethod("setFormat", AudioFormat::class.java).invoke(mixBuilder, format)
            val routeLoopBack = Class.forName("android.media.audiopolicy.AudioMix")
                .getField("ROUTE_FLAG_LOOP_BACK")
                .getInt(null)
            mixBuilderClass.getMethod("setRouteFlags", Int::class.javaPrimitiveType)
                .invoke(mixBuilder, routeLoopBack)
            val mix = mixBuilderClass.getMethod("build").invoke(mixBuilder)

            val policyBuilderClass = Class.forName("android.media.audiopolicy.AudioPolicy\$Builder")
            val policyBuilder = policyBuilderClass.getConstructor(Context::class.java).newInstance(ctx)
            policyBuilderClass.getMethod(
                "addMix",
                Class.forName("android.media.audiopolicy.AudioMix"),
            ).invoke(policyBuilder, mix)
            val policy = policyBuilderClass.getMethod("build").invoke(policyBuilder)

            val am = audioManager ?: return null
            val register = AudioManager::class.java.methods.first { m ->
                m.name == "registerAudioPolicy" && m.parameterTypes.size == 1
            }
            val regResult = register.invoke(am, policy)
            // @SystemApi registerAudioPolicy returns int (0 = success) or void/boolean on OEMs.
            if (regResult is Int && regResult != 0) {
                log(TAG, "registerAudioPolicy failed code=$regResult")
                return null
            }
            if (regResult is Boolean && !regResult) {
                log(TAG, "registerAudioPolicy returned false")
                return null
            }

            val createRecord = policy.javaClass.methods.first { m ->
                m.name == "createAudioRecordSource" && m.parameterTypes.size == 1
            }
            val record = createRecord.invoke(policy, mix) as? AudioRecord
            if (record == null) {
                unregisterAudioPolicy(am, policy)
                return null
            }
            log(TAG, "speech AudioPolicy mix registered usages=${SPEECH_CAPTURE_USAGES.toList()}")
            record to policy
        } catch (e: Throwable) {
            logDebug(TAG, "speech AudioPolicy mix unavailable: ${e.message}")
            null
        }
    }

    private fun unregisterAudioPolicy(am: AudioManager?, policy: Any?) {
        if (am == null || policy == null) return
        runCatching {
            val m = AudioManager::class.java.methods.first { method ->
                method.name == "unregisterAudioPolicy" || method.name == "unregisterAudioPolicyAsync"
            }
            m.invoke(am, policy)
        }.onFailure { logDebug(TAG, "unregisterAudioPolicy: ${it.message}") }
    }

    // ------------------------------------------------------------------
    // MediaProjection acquisition (Samsung / AOSP)
    // ------------------------------------------------------------------

    /**
     * Root cause on Z Flip / One UI (API 33): we run inside system_server. Local
     * [MediaProjectionManagerService.BinderService.createProjection] uses
     * [Context.checkCallingPermission], which **always denies** when
     * `callingPid == myPid` — even after [Binder.clearCallingIdentity]. Samsung also
     * has no `createProjectionInternal`. Falling through to REMOTE_SUBMIX skips phone
     * mute → sound plays on the Z Flip speaker while DiPlay still gets PCM.
     *
     * Fix: construct the service's inner `MediaProjection` directly (same path the
     * Binder method takes after the permission gate).
     */
    private fun obtainMediaProjection(ctx: Context): MediaProjection? {
        runCatching {
            val proj = createProjectionDirect(ctx)
            if (proj != null) {
                log(TAG, "MediaProjection via direct service construct")
                return proj
            }
        }.onFailure { log(TAG, "direct MediaProjection construct failed", it) }

        // AOSP builds that expose createProjectionInternal.
        runCatching {
            val proj = createProjectionInternal(ctx)
            if (proj != null) {
                log(TAG, "MediaProjection via createProjectionInternal")
                return proj
            }
        }.onFailure { log(TAG, "createProjectionInternal failed", it) }

        // Cross-process only: same-process BinderService hits checkCallingPermission deny.
        runCatching {
            val proj = createProjectionViaBinder(ctx)
            if (proj != null) {
                log(TAG, "MediaProjection via IMediaProjectionManager.createProjection")
                return proj
            }
        }.onFailure { log(TAG, "createProjection binder failed", it) }

        val mpm = ctx.getSystemService(MediaProjectionManager::class.java)
        if (mpm != null) {
            for (m in mpm.javaClass.methods.filter {
                it.name.contains("MediaProjection", ignoreCase = true) &&
                    it.name.startsWith("sem")
            }) {
                runCatching {
                    val args = Array(m.parameterTypes.size) { idx ->
                        defaultArgFor(m.parameterTypes[idx], idx)
                    }
                    val proj = m.invoke(mpm, *args) as? MediaProjection
                    if (proj != null) {
                        log(TAG, "MediaProjection via ${m.name}(${m.parameterTypes.size})")
                        return proj
                    }
                }.onFailure {
                    log(TAG, "${m.name} failed: ${it.message}")
                }
            }
        }

        log(TAG, "no MediaProjection — will try REMOTE_SUBMIX (phone mute skipped)")
        return null
    }

    /**
     * One UI: `(service, type, uid, packageName, targetSdk, isPrivileged)`.
     * AOSP: `(service, uid, packageName, type, isPermanentGrant)` or similar.
     */
    private fun createProjectionDirect(ctx: Context): MediaProjection? {
        val service = resolveMediaProjectionManagerService() ?: run {
            log(TAG, "MediaProjectionManagerService not found for direct construct")
            return null
        }
        val cl = service.javaClass.classLoader
        val inner = Class.forName(
            "com.android.server.media.projection.MediaProjectionManagerService\$MediaProjection",
            false,
            cl,
        )
        val pkg = "android"
        val uid = Process.SYSTEM_UID
        val typeMirroring = 1 // TYPE_MIRRORING — canProjectAudio on Samsung for 0/1/2
        val appInfo = runCatching { ctx.packageManager.getApplicationInfo(pkg, 0) }.getOrNull()
        val targetSdk = appInfo?.targetSdkVersion ?: 33
        val privileged = true

        for (ctor in inner.declaredConstructors.sortedByDescending { it.parameterTypes.size }) {
            ctor.isAccessible = true
            val pts = ctor.parameterTypes
            if (pts.isEmpty() || !pts[0].isInstance(service)) continue
            val candidates: List<Array<Any?>> = when {
                // (Service, type, uid, pkg, int, boolean) — One UI: targetSdk+privileged;
                // AOSP variant: userId+permanentGrant.
                pts.size == 6 &&
                    pts[1] == Int::class.javaPrimitiveType &&
                    pts[2] == Int::class.javaPrimitiveType &&
                    pts[3] == String::class.java &&
                    pts[4] == Int::class.javaPrimitiveType &&
                    pts[5] == Boolean::class.javaPrimitiveType -> listOf(
                    arrayOf(service, typeMirroring, uid, pkg, targetSdk, privileged),
                    arrayOf(service, typeMirroring, uid, pkg, 0, true),
                )
                // AOSP-like: (Service, uid, pkg, type, permanentGrant)
                pts.size == 5 &&
                    pts[1] == Int::class.javaPrimitiveType &&
                    pts[2] == String::class.java &&
                    pts[3] == Int::class.javaPrimitiveType &&
                    pts[4] == Boolean::class.javaPrimitiveType -> listOf(
                    arrayOf(service, uid, pkg, typeMirroring, true),
                )
                else -> continue
            }
            for (args in candidates) {
                val iProj = runCatching { ctor.newInstance(*args) }.onFailure {
                    logDebug(TAG, "MediaProjection ctor ${pts.size} failed: ${it.message}")
                }.getOrNull() ?: continue
                val wrapped = wrapIMediaProjection(ctx, iProj)
                if (wrapped != null) {
                    log(TAG, "direct MediaProjection ctor args=${pts.size} type=$typeMirroring uid=$uid")
                    return wrapped
                }
            }
        }
        log(
            TAG,
            "no matching MediaProjection ctor among ${inner.declaredConstructors.size} " +
                "(service=${service.javaClass.name})",
        )
        return null
    }

    private fun createProjectionInternal(ctx: Context): MediaProjection? {
        val service = resolveMediaProjectionManagerService() ?: return null
        val method = service.javaClass.declaredMethods
            .filter { it.name == "createProjectionInternal" }
            .maxByOrNull { it.parameterTypes.size }
            ?.also { it.isAccessible = true }
            ?: return null
        val args = Array(method.parameterTypes.size) { idx ->
            defaultArgFor(method.parameterTypes[idx], idx, packageName = "android")
        }
        val iProj = method.invoke(service, *args) ?: return null
        return wrapIMediaProjection(ctx, iProj)
    }

    private fun createProjectionViaBinder(ctx: Context): MediaProjection? {
        val stub = mediaProjectionManagerStub() ?: return null
        // Same-process BinderService: checkCallingPermission always denies (callingPid==myPid).
        if (stub.javaClass.name.contains("BinderService")) {
            logDebug(TAG, "skip createProjection binder — local BinderService (checkCallingPermission deny)")
            return null
        }
        val create = stub.javaClass.methods
            .filter { it.name == "createProjection" }
            .maxByOrNull { it.parameterTypes.size }
            ?: return null
        val args = Array(create.parameterTypes.size) { idx ->
            defaultArgFor(create.parameterTypes[idx], idx, packageName = "android")
        }
        val iProj = create.invoke(stub, *args) ?: return null
        return wrapIMediaProjection(ctx, iProj)
    }

    private fun resolveMediaProjectionManagerService(): Any? {
        runCatching {
            val localServices = Class.forName("com.android.server.LocalServices")
            val svcClass = Class.forName(
                "com.android.server.media.projection.MediaProjectionManagerService",
            )
            val getService = localServices.methods.firstOrNull { m ->
                m.name == "getService" &&
                    m.parameterTypes.size == 1 &&
                    m.parameterTypes[0] == Class::class.java
            }
            val got = getService?.invoke(null, svcClass)
            if (got != null) return got
        }
        val stub = mediaProjectionManagerStub() ?: return null
        return unwrapMediaProjectionManagerService(stub)
    }

    private fun mediaProjectionManagerStub(): Any? {
        val smClass = Class.forName("android.os.ServiceManager")
        val binder = smClass.getMethod("getService", String::class.java)
            .invoke(null, Context.MEDIA_PROJECTION_SERVICE) as? IBinder
            ?: return null
        return Class.forName("android.media.projection.IMediaProjectionManager\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder)
    }

    private fun unwrapMediaProjectionManagerService(stub: Any): Any? {
        var c: Class<*>? = stub.javaClass
        while (c != null) {
            for (f in c.declaredFields) {
                if (f.name != "this$0" && !f.name.startsWith("this$")) continue
                runCatching {
                    f.isAccessible = true
                    val outer = f.get(stub) ?: return@runCatching
                    if (outer.javaClass.name.contains("MediaProjectionManagerService")) {
                        return outer
                    }
                }
            }
            c = c.superclass
        }
        return null
    }

    private fun wrapIMediaProjection(ctx: Context, iProj: Any): MediaProjection? {
        val iProjClass = Class.forName("android.media.projection.IMediaProjection")
        val impl = when {
            iProjClass.isInstance(iProj) -> iProj
            iProj is IBinder -> {
                Class.forName("android.media.projection.IMediaProjection\$Stub")
                    .getMethod("asInterface", IBinder::class.java)
                    .invoke(null, iProj)
            }
            else -> {
                // Service inner MediaProjection extends Stub — use as Binder.
                val asBinder = iProj.javaClass.methods.firstOrNull {
                    it.name == "asBinder" && it.parameterTypes.isEmpty()
                }
                val token = asBinder?.invoke(iProj) as? IBinder
                if (token != null) {
                    Class.forName("android.media.projection.IMediaProjection\$Stub")
                        .getMethod("asInterface", IBinder::class.java)
                        .invoke(null, token)
                } else {
                    iProj
                }
            }
        } ?: return null
        val ctor = MediaProjection::class.java.declaredConstructors.firstOrNull { c ->
            c.parameterTypes.size >= 2 &&
                c.parameterTypes[0] == Context::class.java &&
                iProjClass.isAssignableFrom(c.parameterTypes[1])
        }?.also { it.isAccessible = true }
            ?: return null
        return ctor.newInstance(ctx, impl) as? MediaProjection
    }

    private fun defaultArgFor(
        type: Class<*>,
        index: Int,
        packageName: String = "android",
    ): Any? = when {
        type == Int::class.javaPrimitiveType -> when (index) {
            0 -> Process.myUid() // processUid
            2 -> 0 // TYPE_SCREEN_CAPTURE / displayId depending on overload
            else -> 0
        }
        type == Boolean::class.javaPrimitiveType -> true // permanentGrant — keep capture alive
        type == String::class.java -> packageName
        type == Long::class.javaPrimitiveType -> 0L
        type.name == "android.os.UserHandle" -> {
            runCatching {
                UserHandle::class.java.getMethod("of", Int::class.javaPrimitiveType)
                    .invoke(null, 0)
            }.getOrElse {
                UserHandle::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType)
                    .also { it.isAccessible = true }
                    .newInstance(0)
            }
        }
        else -> null
    }

    // ------------------------------------------------------------------
    // Pull
    // ------------------------------------------------------------------

    /** Media: little-endian s16 stereo 48 kHz. */
    fun pull(maxBytes: Int): ByteArray {
        if (!armed.get()) return ByteArray(0)
        lastPullMs.set(System.currentTimeMillis())
        val out = mediaRing.pull(maxBytes)
        totalMediaPulled += out.size
        return out
    }

    /** Speech: little-endian s16 mono 16 kHz. */
    fun pullSpeech(maxBytes: Int): ByteArray {
        if (!armed.get()) return ByteArray(0)
        lastPullMs.set(System.currentTimeMillis())
        val out = speechRing.pull(maxBytes)
        totalSpeechPulled += out.size
        return out
    }

    private fun scheduleWatchdog(handler: Handler) {
        handler.postDelayed(
            {
                if (!armed.get()) return@postDelayed
                if (System.currentTimeMillis() - lastPullMs.get() > PULL_TIMEOUT_MS) {
                    log(TAG, "no pull for ${PULL_TIMEOUT_MS / 1000}s — disarming (app gone?)")
                    setEnabled(false)
                    return@postDelayed
                }
                log(
                    TAG,
                    "watchdog media=$mediaMode speech=$speechMode " +
                        "pulledMedia=${totalMediaPulled / 1024}KB pulledSpeech=${totalSpeechPulled / 1024}KB " +
                        "pcmPeak=${mediaPcmPeak.get()} dropMedia=${mediaRing.dropped / 1024}KB",
                )
                scheduleWatchdog(handler)
            },
            WATCHDOG_PERIOD_MS,
        )
    }

    /**
     * Simple SPSC ring for one producer / one Binder consumer.
     * When backlog exceeds [highWatermarkBytes], drop oldest down to [lowWatermarkBytes]
     * on both write and pull so stale PCM is discarded even while Binder is late.
     */
    private class PcmRing(
        private val capacity: Int,
        private val highWatermarkBytes: Int,
        private val lowWatermarkBytes: Int,
    ) {
        private val buf = ByteArray(capacity)
        private val lock = Any()
        private var head = 0
        private var tail = 0
        private var available = 0
        var dropped = 0L
            private set

        fun clear() {
            synchronized(lock) {
                head = 0
                tail = 0
                available = 0
                dropped = 0L
            }
        }

        fun write(data: ByteArray) {
            if (data.isEmpty()) return
            synchronized(lock) {
                // Keep only what fits; prefer the newest samples when the payload is huge.
                var srcOff = 0
                var srcLen = data.size
                if (srcLen > capacity) {
                    srcOff = srcLen - capacity
                    srcLen = capacity
                }
                val free = capacity - available
                if (srcLen > free) {
                    // Round drop UP to a 4-byte PCM frame so available + srcLen <= capacity.
                    // Rounding down left available > capacity and broke pull wrap math.
                    var drop = srcLen - free
                    val rem = drop % 4
                    if (rem != 0) drop += 4 - rem
                    if (drop > available) drop = available - (available % 4)
                    if (drop > 0) {
                        tail = (tail + drop) % capacity
                        available -= drop
                        dropped += drop.toLong()
                    }
                }
                var off = 0
                while (off < srcLen) {
                    val chunk = minOf(srcLen - off, capacity - head)
                    if (chunk <= 0) break
                    System.arraycopy(data, srcOff + off, buf, head, chunk)
                    head = (head + chunk) % capacity
                    available += chunk
                    off += chunk
                }
                if (available > capacity) available = capacity
                // Drop-to-live on the producer side: do not wait for a late Binder pull
                // to discover a full second of backlog already queued for TX.
                trimToWatermarkLocked()
            }
        }

        fun pull(maxBytes: Int): ByteArray {
            synchronized(lock) {
                trimToWatermarkLocked()
                val take = minOf(available, maxBytes.coerceAtLeast(0))
                if (take <= 0) return ByteArray(0)
                val arr = ByteArray(take)
                var off = 0
                while (off < take) {
                    // Remaining in the ring is [available], remaining to fill is [take - off].
                    // Never use (available - off): after a wrap chunk that double-subtracts
                    // and can yield chunk==0 → spin forever while holding the lock, which
                    // wedges every Binder pullCarPlayAudio + the capture writer (no car
                    // audio, phone stays muted).
                    val chunk = minOf(take - off, available, capacity - tail)
                    if (chunk <= 0) break
                    System.arraycopy(buf, tail, arr, off, chunk)
                    tail = (tail + chunk) % capacity
                    available -= chunk
                    off += chunk
                }
                return if (off == take) arr else arr.copyOf(off)
            }
        }

        private fun trimToWatermarkLocked() {
            val high = highWatermarkBytes.coerceIn(0, capacity)
            val low = lowWatermarkBytes.coerceIn(0, high)
            if (available <= high) return
            var drop = available - low
            drop -= drop % 4
            if (drop <= 0) return
            tail = (tail + drop) % capacity
            available -= drop
            dropped += drop.toLong()
        }
    }
}
