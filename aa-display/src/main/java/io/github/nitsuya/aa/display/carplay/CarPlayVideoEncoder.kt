package io.github.nitsuya.aa.display.carplay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * AVC encoder for the CarPlay screen stream.
 *
 * Two input modes:
 *  - Surface input (preferred): the frame pump draws straight into the encoder's
 *    `lockHardwareBuffer` canvas — C2 does the RGB→YUV conversion in hardware, so no
 *    per-pixel CPU work.
 *  - Buffer input (fallback): rotating ARGD8888 slots filled by `getPixels` on the pump
 *    thread, converted to NV12 on a worker thread (the ~100 ms color conversion never
 *    blocks the compositor feed).
 *
 * Surface-input needs `createInputSurface` + `lockHardwareBuffer` (API 26); both exist
 * on minSdk 33, but a failure to configure degrades to buffer input.
 */
class CarPlayVideoEncoder(
    /** bytes, ptsUs, isKey, isCodecConfig */
    private val onEncodedAccessUnit: (ByteArray, Long, Boolean, Boolean) -> Unit,
) {
    private val running = AtomicBoolean(false)
    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private val surfaceFrameFailLogged = AtomicBoolean(false)
    private val drainFailLogged = AtomicBoolean(false)
    private val droppedFrames = AtomicLong(0)

    /** Frames dropped for back-pressure (converter busy / surface unusable), since process start. */
    fun dropCount(): Long = droppedFrames.get()
    private var width = CarPlayConsts.DEFAULT_VIDEO_WIDTH
    private var height = CarPlayConsts.DEFAULT_VIDEO_HEIGHT
    private var frameIndex = 0L

    // Buffer-input mode: rotating conversion slots; the pump fills the opposite slot the
    // converter reads.
    private var argbSlots = arrayOf(IntArray(0), IntArray(0))
    private var nv12Slots = arrayOf(ByteArray(0), ByteArray(0))
    private var slot = 0
    @Volatile
    private var converterBusy = false
    private val converterQueue = LinkedBlockingQueue<Int>(1)
    private var converterThread: Thread? = null

    fun start(width: Int, height: Int) {
        stop()
        this.width = align16(width.coerceAtLeast(16))
        this.height = align16(height.coerceAtLeast(16))
        val pixels = this.width * this.height
        argbSlots = arrayOf(IntArray(pixels), IntArray(pixels))
        nv12Slots = arrayOf(ByteArray(pixels + pixels / 2), ByteArray(pixels + pixels / 2))
        slot = 0
        converterBusy = false
        surfaceFrameFailLogged.set(false)
        drainFailLogged.set(false)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, this.width, this.height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, CarPlayConsts.DEFAULT_VIDEO_BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, CarPlayConsts.DEFAULT_VIDEO_FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val encoder = try {
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        } catch (e: Throwable) {
            Log.e(CarPlayConsts.TAG, "AVC encoder create failed", e)
            return
        }
        val surface = try {
            encoder.createInputSurface()
        } catch (e: Throwable) {
            Log.w(CarPlayConsts.TAG, "AVC createInputSurface failed; buffer mode", e)
            null
        }
        try {
            encoder.configure(format, surface, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
        } catch (e: Throwable) {
            try {
                surface?.release()
            } catch (_: Throwable) {
            }
            Log.e(CarPlayConsts.TAG, "AVC encoder cannot support advertised dimensions", e)
            try {
                encoder.release()
            } catch (_: Throwable) {
            }
            codec = null
            running.set(false)
            return
        }
        codec = encoder
        inputSurface = surface
        running.set(true)
        if (surface == null) {
            converterThread = thread(name = "carplay-avc-convert", isDaemon = true) { converterLoop(encoder) }
        }
        thread(name = "carplay-avc-drain", isDaemon = true) { drainLoop(encoder) }
        Log.i(CarPlayConsts.TAG, "AVC encoder ${this.width}x${this.height} input=${if (surface != null) "surface" else "buffer"}")
    }

    /** Surface-input mode active? */
    fun hasSurfaceInput(): Boolean = inputSurface != null

    /** True when already running at the given (unaligned) size — lets callers skip redundant restarts. */
    fun isRunningAt(width: Int, height: Int): Boolean =
        running.get() &&
            this.width == align16(width.coerceAtLeast(16)) &&
            this.height == align16(height.coerceAtLeast(16))

    /** DiPlay recovery / showUI asks for an IDR via event `forceKeyFrame`. */
    fun requestKeyFrame() {
        val encoder = codec ?: return
        if (!running.get()) return
        try {
            val params = Bundle()
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            encoder.setParameters(params)
            Log.i(CarPlayConsts.TAG, "AVC requestKeyFrame")
        } catch (e: Throwable) {
            Log.w(CarPlayConsts.TAG, "AVC requestKeyFrame failed", e)
        }
    }

    /**
     * Surface-input feed: [draw] renders the full frame into the encoder's hardware canvas
     * (`lockHardwareCanvas` — zero-copy for the pump; C2 converts in hardware). Frame
     * timestamps are component-assigned; DiPlay's pacer self-heals any offset.
     */
    fun encodeCanvas(draw: (Canvas) -> Unit) {
        val encoder = codec ?: return
        if (!running.get()) return
        val surface = inputSurface ?: return
        try {
            val canvas = surface.lockHardwareCanvas()
            draw(canvas)
            surface.unlockCanvasAndPost(canvas)
            frameIndex++
        } catch (e: Throwable) {
            droppedFrames.incrementAndGet()
            if (!surfaceFrameFailLogged.compareAndSet(false, true)) return
            Log.w(CarPlayConsts.TAG, "AVC hardware canvas unusable — surface input will drop frames", e)
        }
    }

    /**
     * Buffer-input feed (fallback): copies pixels into the free slot and hands it to the
     * converter thread; drops the frame when the converter is behind.
     */
    fun encodeBitmap(bitmap: Bitmap) {
        val encoder = codec ?: return
        if (!running.get() || bitmap.isRecycled) return
        if (inputSurface != null) {
            // Surface mode ignores bitmap feeds.
            return
        }
        val scaled = if (bitmap.width == width && bitmap.height == height) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, width, height, true)
        }
        if (converterBusy) {
            droppedFrames.incrementAndGet()
            if (scaled !== bitmap) scaled.recycle()
            return
        }
        slot = 1 - slot
        converterBusy = true
        try {
            bitmap.getPixels(argbSlots[slot], 0, width, 0, 0, width, height)
        } catch (e: Throwable) {
            converterBusy = false
            if (scaled !== bitmap) scaled.recycle()
            return
        }
        if (scaled !== bitmap) scaled.recycle()
        if (!converterQueue.offer(slot)) {
            converterBusy = false
            droppedFrames.incrementAndGet()
        }
    }

    private fun converterLoop(encoder: MediaCodec) {
        var iters = 0
        var sumConvert = 0L
        var sumDequeue = 0L
        var lastStat = android.os.SystemClock.uptimeMillis()
        while (running.get() || !converterQueue.isEmpty()) {
            val slotIndex = try {
                converterQueue.poll(100, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                null
            }
            if (slotIndex == null) continue
            val t0 = android.os.SystemClock.uptimeMillis()
            try {
                convertSlot(slotIndex)
            } catch (e: Throwable) {
                Log.e(CarPlayConsts.TAG, "AVC convert failed", e)
            } finally {
                // The ARGD slot is free for the pump to refill once the conversion finished.
                converterBusy = false
            }
            val t1 = android.os.SystemClock.uptimeMillis()
            sumConvert += t1 - t0
            val inIndex = try {
                encoder.dequeueInputBuffer(10_000)
            } catch (_: Throwable) {
                continue
            }
            sumDequeue += android.os.SystemClock.uptimeMillis() - t1
            iters++
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastStat > 30_000) {
                Log.i(
                    CarPlayConsts.TAG,
                    "AVC worker ${(iters * 1000.0 / (now - lastStat)).toInt()}fps " +
                        "convertAvg=${sumConvert / iters}ms dequeueWaitAvg=${sumDequeue / iters}ms",
                )
                iters = 0
                sumConvert = 0
                sumDequeue = 0
                lastStat = now
            }
            if (inIndex < 0) continue
            try {
                val input: ByteBuffer = encoder.getInputBuffer(inIndex) ?: continue
                val nv12 = nv12Slots[slotIndex]
                input.clear()
                input.put(nv12)
                val pts = frameIndex * (1_000_000L / CarPlayConsts.DEFAULT_VIDEO_FPS)
                frameIndex++
                encoder.queueInputBuffer(inIndex, 0, nv12.size, pts, 0)
            } catch (e: Throwable) {
                Log.e(CarPlayConsts.TAG, "queueInputBuffer failed", e)
            }
        }
        converterBusy = false
    }

    private fun convertSlot(slotIndex: Int) {
        val w = width
        val h = height
        val argb = argbSlots[slotIndex]
        val out = nv12Slots[slotIndex]
        val ySize = w * h
        var yIndex = 0
        var uvIndex = ySize
        var index = 0
        for (j in 0 until h) {
            for (i in 0 until w) {
                val c = argb[index++]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                out[yIndex++] = y.coerceIn(0, 255).toByte()
                if (j % 2 == 0 && i % 2 == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    out[uvIndex++] = u.coerceIn(0, 255).toByte()
                    out[uvIndex++] = v.coerceIn(0, 255).toByte()
                }
            }
        }
    }

    private fun drainLoop(encoder: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running.get()) {
            // The codec can be released by a concurrent start()/stop() (DiPlay re-SETUP);
            // a stray call must never take down the app — fail the iteration instead.
            try {
                drainOnce(encoder, info)
            } catch (e: Throwable) {
                if (!drainFailLogged.compareAndSet(false, true)) continue
                Log.w(CarPlayConsts.TAG, "AVC drain loop stopping (codec released mid-drain?)", e)
                break
            }
        }
    }

    private fun drainOnce(encoder: MediaCodec, info: MediaCodec.BufferInfo) {
        if (!running.get()) return
        val outIndex = try {
            encoder.dequeueOutputBuffer(info, 10_000)
        } catch (_: Throwable) {
            return
        }
        when {
            outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> return
            outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                try {
                    Log.i(CarPlayConsts.TAG, "AVC output format ${encoder.outputFormat}")
                } catch (_: Throwable) {
                    // Codec released between dequeue and getOutputFormat — ignore.
                }
            }
            outIndex >= 0 -> {
                    try {
                        val buffer = encoder.getOutputBuffer(outIndex)
                        if (buffer != null && info.size > 0) {
                            val bytes = ByteArray(info.size)
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            buffer.get(bytes)
                            val key = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                            val config = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                            onEncodedAccessUnit(bytes, info.presentationTimeUs, key, config)
                        }
                    } catch (e: Throwable) {
                        Log.e(CarPlayConsts.TAG, "AVC drain failed", e)
                    } finally {
                        try {
                            encoder.releaseOutputBuffer(outIndex, false)
                        } catch (_: Throwable) {
                        }
                    }
            }
        }
    }

    fun stop() {
        running.set(false)
        try {
            codec?.stop()
        } catch (_: Throwable) {
        }
        try {
            codec?.release()
        } catch (_: Throwable) {
        }
        codec = null
        try {
            inputSurface?.release()
        } catch (_: Throwable) {
        }
        inputSurface = null
        converterThread?.interrupt()
        converterThread = null
        frameIndex = 0L
    }

    private fun align16(v: Int): Int = (v + 15) and 15.inv()
}
