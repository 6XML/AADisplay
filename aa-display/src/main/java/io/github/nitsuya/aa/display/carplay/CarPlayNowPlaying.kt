package io.github.nitsuya.aa.display.carplay

import android.annotation.SuppressLint
import android.content.Context
import android.database.ContentObserver
import android.provider.Settings
import android.util.Log
import io.github.nitsuya.aa.display.CoreApi
import io.github.nitsuya.aa.display.carplay.protocol.Iap2Framing
import io.github.nitsuya.aa.display.carplay.protocol.Iap2Session
import io.github.nitsuya.aa.display.xposed.cluster.ClusterArtStore
import io.github.nitsuya.aa.display.xposed.cluster.ClusterLyricStore
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CarPlay NowPlaying metadata driver (instrument cluster card + MediaSession on DiPlay).
 *
 * Data source: the shared [ClusterLyricStore] / [ClusterArtStore] (Settings.Global, written
 * by system_server [io.github.nitsuya.aa.display.xposed.cluster.ClusterLyricMirror]; a normal
 * app can read global settings, and the art JPEG comes over the CoreApi Binder because the
 * `/data/system` file is SELinux-walled). Same content the AA cluster path uses, so the BYD
 * cluster card shows the current lyric line as the caption (DiPlay re-triggers its 8 s
 * caption on every title change — the lyric-line-as-title trick, verbatim from the AA path).
 *
 * Transport: iAP2 0x5001 NowPlayingUpdate (incremental — omitted field keeps, empty string
 * clears) on the control session, plus the iAP2 v2 file-transfer session for the cover
 * (type 2, ≤ 2 MiB, FLAG_LAST on the final chunk).
 *
 * Runs in the app process while the CarPlay session is live; armed from
 * [CarPlaySourceSession] once the RTSP session is established.
 */
object CarPlayNowPlaying {
    private val TAG = CarPlayConsts.TAG

    // MEDIA_ITEM group field ids (DiPlay CarPlayPlaybackStatus).
    private const val FIELD_TITLE = 1
    private const val FIELD_DURATION = 4
    private const val FIELD_ALBUM = 6
    private const val FIELD_ARTIST = 12
    private const val FIELD_ARTWORK_TRANSFER_ID = 26
    // PLAYBACK group field ids.
    private const val FIELD_STATUS = 0
    private const val FIELD_ELAPSED = 1
    private const val FIELD_SOURCE_APP = 7

    private const val STATUS_PLAYING = 1

    // iAP2 v2 file transfer (DiPlay Iap2FileTransferReceiver).
    private const val OP_DATA = 0x00
    private const val OP_START = 0x01
    private const val OP_CANCEL = 0x02
    private const val OP_SETUP = 0x04
    private const val OP_SUCCESS = 0x05
    private const val FLAG_LAST = 0x40
    private const val FILE_TYPE_NOW_PLAYING_ARTWORK = 0x02
    private const val ART_MAX_BYTES = 2 * 1024 * 1024
    private const val ART_CHUNK_BYTES = 1_024

    private const val ELAPSED_TICK_MS = 1_000L

    private val active = AtomicBoolean(false)
    private val armed = AtomicBoolean(false)
    @Volatile
    private var session: Iap2Session? = null

    // Application context only (single instance for the session lifetime) — no leak.
    @SuppressLint("StaticFieldLeak")
    @Volatile
    private var context: Context? = null
    private var handler: android.os.Handler? = null
    private var observer: ContentObserver? = null
    private var elapsedTick: Runnable? = null

    // Last pushed values — the diff base for incremental updates.
    @Volatile
    private var pushedTitle: String? = null
    @Volatile
    private var pushedArtist: String? = null
    @Volatile
    private var pushedAlbum: String? = null
    @Volatile
    private var pushedSourceApp: String? = null
    @Volatile
    private var pushedDurationMs: Long? = null
    @Volatile
    private var pushedStatus: Int? = null
    @Volatile
    private var pushedElapsedSec: Long = -1L
    @Volatile
    private var pushedArtRevision: Long = -1L

    private val artLock = Any()
    private var pendingArt: PendingArt? = null
    private var nextArtId = 1

    private class PendingArt(val id: Int, val bytes: ByteArray, var offset: Int)

    /** Arm the store observer + initial push. Safe from any thread; idempotent. */
    fun start(context: Context, session: Iap2Session) {
        if (!active.compareAndSet(false, true)) return
        val app = context.applicationContext
        this.context = app
        this.session = session
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        handler = h
        val obs = object : ContentObserver(h) {
            override fun onChange(selfChange: Boolean, uri: android.net.Uri?) {
                h.post { refresh() }
            }
        }
        observer = obs
        try {
            val cr = app.contentResolver
            for (key in OBSERVED_KEYS) {
                cr.registerContentObserver(Settings.Global.getUriFor(key), false, obs)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "CarPlay nowplaying observer registration failed", e)
        }
        val tick = object : Runnable {
            override fun run() {
                if (!active.get()) return
                pushElapsedTick()
                h.postDelayed(this, ELAPSED_TICK_MS)
            }
        }
        elapsedTick = tick
        h.postDelayed(tick, ELAPSED_TICK_MS)
        // Full first push (if anything is fresh) so a new session shows the current track.
        h.post { refresh(full = true) }
        Log.i(TAG, "CarPlay nowplaying armed (lyric store → 0x5001 + artwork transfer)")
    }

    fun stop() {
        if (!active.compareAndSet(true, false)) return
        armed.set(false)
        val h = handler
        h?.removeCallbacksAndMessages(null)
        elapsedTick = null
        handler = null
        observer?.let {
            try {
                context?.contentResolver?.unregisterContentObserver(it)
            } catch (_: Throwable) {
            }
        }
        observer = null
        session = null
        synchronized(artLock) {
            pendingArt = null
        }
        pushedTitle = null
        pushedArtist = null
        pushedAlbum = null
        pushedSourceApp = null
        pushedDurationMs = null
        pushedStatus = null
        pushedElapsedSec = -1L
        pushedArtRevision = -1L
    }

    /** Called when the host sends StartNowPlayingUpdates (0x5000). */
    fun onUpdatesRequested() {
        armed.set(true)
        handler?.post { refresh(full = true) }
    }

    private fun refresh(full: Boolean = false) {
        if (!active.get()) return
        if (!armed.get() && !full) return
        val ctx = context ?: return
        val cr = ctx.contentResolver
        val fresh = runCatching { ClusterLyricStore.readFresh(cr) }.getOrNull()
        val sourcePkg =
            Settings.Global.getString(cr, ClusterLyricStore.SETTINGS_SOURCE_PKG)?.trim().orEmpty()
                .ifEmpty { null }
        val progress = runCatching { ClusterLyricStore.readProgress(cr) }.getOrNull()
        val artRevision =
            Settings.Global.getString(cr, ClusterArtStore.SETTINGS_ART_REVISION)?.toLongOrNull()
                ?: 0L

        val title = fresh?.title?.ifBlank { null }
        val artist = fresh?.subtitle?.ifBlank { null }
        val album = fresh?.album?.ifBlank { null }
        val durationMs = progress?.durationMs?.takeIf { it > 0L }
        val status = if (progress?.playbackState == android.media.session.PlaybackState.STATE_PLAYING && fresh != null) {
            STATUS_PLAYING
        } else {
            0
        }

        val media = linkedMapOf<Int, ByteArray>()
        val playback = linkedMapOf<Int, ByteArray>()
        if (title != pushedTitle) {
            pushedTitle = title
            media[FIELD_TITLE] = Iap2Framing.utf8Param(title.orEmpty())
        }
        if (artist != pushedArtist) {
            pushedArtist = artist
            media[FIELD_ARTIST] = Iap2Framing.utf8Param(artist.orEmpty())
        }
        if (album != pushedAlbum) {
            pushedAlbum = album
            media[FIELD_ALBUM] = Iap2Framing.utf8Param(album.orEmpty())
        }
        if (durationMs != pushedDurationMs) {
            pushedDurationMs = durationMs
            // 0 = cleared (DiPlay: present value replaces; empty string / 0 resets the card).
            media[FIELD_DURATION] = Iap2Framing.u32Param(durationMs ?: 0L)
        }
        if (status != pushedStatus) {
            pushedStatus = status
            playback[FIELD_STATUS] = Iap2Framing.u8Param(status)
        }
        if (sourcePkg != pushedSourceApp) {
            pushedSourceApp = sourcePkg
            playback[FIELD_SOURCE_APP] = Iap2Framing.utf8Param(sourcePkg.orEmpty())
        }
        if (full) pushedElapsedSec = -1L
        pushElapsedTick(progress, force = full)

        if (artRevision != pushedArtRevision) {
            pushedArtRevision = artRevision
            if (artRevision > 0L) {
                maybeTransferArtwork()
            }
        }

        if (media.isNotEmpty() || playback.isNotEmpty()) {
            session?.sendNowPlaying(media, playback)
            if (media.size + playback.size > 2 || title != null) {
                Log.i(
                    TAG,
                    "nowplaying pushed media=${media.keys.toList()} playback=${playback.keys.toList()} " +
                        "title=${title?.take(24)}",
                )
            }
        }
    }

    /** While playing, keep ELAPSED moving at 1 s cadence (whole-second dedup). */
    private fun pushElapsedTick(progressOverride: ClusterLyricStore.Progress? = null, force: Boolean = false) {
        val ctx = context ?: return
        val progress = progressOverride ?: runCatching {
            ClusterLyricStore.readProgress(ctx.contentResolver)
        }.getOrNull() ?: return
        if (pushedStatus != STATUS_PLAYING) {
            pushedElapsedSec = -1L
            return
        }
        val pos = ClusterLyricStore.extrapolatePosition(progress)
        val sec = pos / 1_000L
        if (!force && sec == pushedElapsedSec) return
        pushedElapsedSec = sec
        session?.sendNowPlaying(emptyMap(), linkedMapOf(FIELD_ELAPSED to Iap2Framing.u32Param(sec * 1_000L)))
    }

    // ------------------------------------------------------------------
    // Artwork: iAP2 v2 file transfer (session 12)
    // ------------------------------------------------------------------

    private fun maybeTransferArtwork() {
        Thread(
            {
                val s = session
                val jpeg = try {
                    CoreApi.getClusterArtJpeg()
                } catch (e: Throwable) {
                    Log.w(TAG, "cluster art fetch failed", e)
                    null
                }
                if (s == null || jpeg == null || jpeg.isEmpty()) return@Thread
                if (jpeg.size > ART_MAX_BYTES) {
                    Log.w(TAG, "cluster art too large (${jpeg.size}B) — skipping")
                    return@Thread
                }
                val id = synchronized(artLock) {
                    if (pendingArt != null) return@synchronized -1
                    val i = nextArtId
                    nextArtId = ((i % 254) + 1)
                    pendingArt = PendingArt(i, jpeg, 0)
                    i
                }
                if (id < 0) return@Thread
                // SETUP: [id, opcode=4, 8B BE size, 2B fileType]
                val setup = ByteArray(12)
                setup[0] = id.toByte()
                setup[1] = OP_SETUP.toByte()
                var size = jpeg.size.toLong()
                for (i in 0 until 8) {
                    setup[2 + i] = (size and 0xff).toByte()
                    size = size ushr 8
                }
                setup[10] = ((FILE_TYPE_NOW_PLAYING_ARTWORK ushr 8) and 0xff).toByte()
                setup[11] = (FILE_TYPE_NOW_PLAYING_ARTWORK and 0xff).toByte()
                s.sendFileTransfer(setup)
                Log.i(TAG, "artwork transfer started id=$id size=${jpeg.size}B")
            },
            "cp-nowplaying-art",
        ).apply { isDaemon = true }.start()
    }

    /** iAP2 session 12 replies (host → source): start / success / cancel. */
    fun onFileTransferDatagram(dg: ByteArray) {
        if (dg.size < 2) return
        val id = dg[0].toInt() and 0xff
        val op = dg[1].toInt() and 0x0f
        val art: PendingArt
        synchronized(artLock) {
            art = pendingArt ?: return
            if (art.id != id) return
            when (op) {
                OP_START -> Unit // fall through to data
                OP_SUCCESS -> pendingArt = null
                OP_CANCEL -> {
                    pendingArt = null
                    Log.i(TAG, "artwork transfer cancelled id=$id")
                    return
                }
                else -> return
            }
        }
        if (op == OP_START) {
            sendArtChunks(art)
        } else {
            // Success: reference the transfer in the next NowPlaying update.
            session?.sendNowPlaying(
                linkedMapOf(FIELD_ARTWORK_TRANSFER_ID to Iap2Framing.u8Param(id)),
                emptyMap(),
            )
            Log.i(TAG, "artwork delivered id=$id (${art.bytes.size}B)")
        }
    }

    private fun sendArtChunks(art: PendingArt) {
        val s = session ?: return
        var off = art.offset
        val total = art.bytes.size
        while (off < total) {
            val chunk = minOf(ART_CHUNK_BYTES, total - off)
            val last = off + chunk >= total
            val dg = ByteArray(2 + chunk)
            dg[0] = art.id.toByte()
            dg[1] = ((if (last) FLAG_LAST else 0) or OP_DATA).toByte()
            System.arraycopy(art.bytes, off, dg, 2, chunk)
            s.sendFileTransfer(dg)
            off += chunk
        }
        art.offset = total
    }

    private val OBSERVED_KEYS = listOf(
        ClusterLyricStore.SETTINGS_TITLE,
        ClusterLyricStore.SETTINGS_SUBTITLE,
        ClusterLyricStore.SETTINGS_ALBUM,
        ClusterLyricStore.SETTINGS_SOURCE_PKG,
        ClusterLyricStore.SETTINGS_TRACK_MEDIA_ID,
        ClusterArtStore.SETTINGS_ART_MEDIA_ID,
        ClusterArtStore.SETTINGS_ART_REVISION,
        ClusterLyricStore.SETTINGS_DURATION_MS,
        ClusterLyricStore.SETTINGS_PLAYBACK_STATE,
    )
}
