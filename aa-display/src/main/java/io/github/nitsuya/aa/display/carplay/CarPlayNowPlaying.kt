package io.github.nitsuya.aa.display.carplay

import android.annotation.SuppressLint
import android.content.Context
import android.database.ContentObserver
import android.provider.Settings
import android.util.Log
import io.github.nitsuya.aa.display.CoreApi
import io.github.nitsuya.aa.display.carplay.protocol.Iap2Framing
import io.github.nitsuya.aa.display.carplay.protocol.NowPlayingEgress
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
 * clears) via [NowPlayingEgress] — Bluetooth [io.github.nitsuya.aa.display.carplay.protocol.Iap2Session]
 * for DiPlay, Wi‑Fi [io.github.nitsuya.aa.display.carplay.protocol.WifiIap2Link] for MIB3/Audi —
 * plus the iAP2 v2 file-transfer session for the cover (type 2, ≤ 2 MiB, FLAG_LAST on the
 * final chunk). A title or track-id change carries the current elapsed in that same update.
 * MediaItem persistent id tracks the bound track media id, so a lyric line does not look
 * like a new song.
 *
 * Runs in the app process while the CarPlay session is live; [start] arms the Store
 * observer, [attachEgress] binds the live transport (may swap BT → Wi‑Fi).
 */
object CarPlayNowPlaying {
    private val TAG = CarPlayConsts.TAG

    // MEDIA_ITEM group field ids (DiPlay CarPlayPlaybackStatus).
    private const val FIELD_PERSISTENT_ID = 0
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
    /** iAP2 file-transfer data flags (Wireshark FT_DATAGRAM_NAMES). */
    private const val FLAG_LAST = 0x40
    private const val FLAG_FIRST = 0x80
    private const val FILE_TYPE_NOW_PLAYING_ARTWORK = 0x02
    /** iPhone idle NowPlaying uses 0x80 as ArtworkFileTransferIdentifier "none". */
    private const val ART_ID_NONE = 0x80
    private const val ART_MAX_BYTES = 2 * 1024 * 1024
    private const val ART_CHUNK_BYTES = 1_024

    private const val ELAPSED_TICK_MS = 1_000L
    private const val FNV64_OFFSET = 0xcbf29ce484222325UL
    private const val FNV64_PRIME = 0x100000001b3UL

    private val active = AtomicBoolean(false)
    private val armed = AtomicBoolean(false)
    @Volatile
    private var egress: NowPlayingEgress? = null

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
    /** Track media id last written as MediaItem PersistentIdentifier. Lyric lines do not change it. */
    @Volatile
    private var pushedTrackId: String? = null
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
    /** File-transfer id last associated with the HU card; re-sent on lyric ticks (Audi). */
    @Volatile
    private var boundArtTransferId: Int? = null
    @Volatile
    private var boundArtTrackId: String? = null

    private val artLock = Any()
    private var pendingArt: PendingArt? = null
    private var nextArtId = 1

    private class PendingArt(val id: Int, val trackId: String?, val bytes: ByteArray, var offset: Int)

    /** True after [start] and before [stop]. */
    fun isStarted(): Boolean = active.get()

    /** True when [candidate] is the currently bound NowPlaying transport. */
    fun isBoundTo(candidate: NowPlayingEgress?): Boolean =
        candidate != null && egress === candidate

    /**
     * Arm the Store observer + elapsed tick. Safe from any thread; idempotent.
     * Call [attachEgress] once the BT or Wi‑Fi iAP2 transport is ready.
     */
    fun start(context: Context) {
        if (!active.compareAndSet(false, true)) return
        val app = context.applicationContext
        this.context = app
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
                // Poll Store every second so lyric/track/art updates are not solely
                // dependent on Settings ContentObserver (OEM notify gaps).
                refresh()
                h.postDelayed(this, ELAPSED_TICK_MS)
            }
        }
        elapsedTick = tick
        h.postDelayed(tick, ELAPSED_TICK_MS)
        Log.i(TAG, "CarPlay nowplaying observer armed (awaiting egress)")
    }

    /**
     * Bind or replace the outbound transport. Resets the incremental diff base so the
     * next refresh (or [onUpdatesRequested]) sends a full card on the new link.
     */
    fun attachEgress(egress: NowPlayingEgress) {
        this.egress = egress
        synchronized(artLock) {
            pendingArt = null
        }
        clearDiffState()
        Log.i(TAG, "CarPlay nowplaying egress=${egress.javaClass.simpleName}")
        if (armed.get()) {
            handler?.post { refresh(full = true) }
        }
    }

    /** Clear transport if it still matches [egress]; observer stays until [stop]. */
    fun detachEgress(egress: NowPlayingEgress) {
        if (this.egress === egress) {
            this.egress = null
            synchronized(artLock) {
                pendingArt = null
            }
            Log.i(TAG, "CarPlay nowplaying egress detached")
        }
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
        egress = null
        synchronized(artLock) {
            pendingArt = null
        }
        clearDiffState()
    }

    private fun clearDiffState() {
        pushedTitle = null
        pushedTrackId = null
        pushedArtist = null
        pushedAlbum = null
        pushedSourceApp = null
        pushedDurationMs = null
        pushedStatus = null
        pushedElapsedSec = -1L
        pushedArtRevision = -1L
        boundArtTransferId = null
        boundArtTrackId = null
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
        val trackId =
            Settings.Global.getString(cr, ClusterLyricStore.SETTINGS_TRACK_MEDIA_ID)?.trim().orEmpty()
                .ifEmpty { null }
        val durationMs = progress?.durationMs?.takeIf { it > 0L }
        val status = if (progress?.playbackState == android.media.session.PlaybackState.STATE_PLAYING && fresh != null) {
            STATUS_PLAYING
        } else {
            0
        }

        val media = linkedMapOf<Int, ByteArray>()
        val playback = linkedMapOf<Int, ByteArray>()
        val titleChanged = title != pushedTitle
        if (titleChanged) {
            pushedTitle = title
            media[FIELD_TITLE] = Iap2Framing.utf8Param(title.orEmpty())
        }
        val trackChanged = trackId != pushedTrackId
        if (trackChanged) {
            pushedTrackId = trackId
            // 8 zero bytes clears; a stable non-zero id tells the HU this is the same track.
            media[FIELD_PERSISTENT_ID] = if (trackId == null) {
                ByteArray(8)
            } else {
                persistentIdBytes(trackId)
            }
            // New track → drop prior cover binding (iPhone idle uses 0x80 as "no art").
            if (boundArtTransferId != null && boundArtTrackId != trackId) {
                boundArtTransferId = null
                boundArtTrackId = null
                media[FIELD_ARTWORK_TRANSFER_ID] = Iap2Framing.u8Param(ART_ID_NONE)
                pushedArtRevision = -1L
            }
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
        // Title or track-id packets can be treated as a track change. Put the current
        // elapsed in that same 0x5001 so a reset is overwritten in-message.
        val carryElapsed = titleChanged || media.containsKey(FIELD_PERSISTENT_ID)
        if (carryElapsed) {
            elapsedParam(progress, force = true)?.let { playback[FIELD_ELAPSED] = it }
        }

        // Audi drops cover when MediaItem updates omit field 26 — re-assert on every media push.
        val artId = boundArtTransferId
        if (artId != null && boundArtTrackId == trackId && media.isNotEmpty()) {
            media[FIELD_ARTWORK_TRANSFER_ID] = Iap2Framing.u8Param(artId)
        }

        if (artRevision != pushedArtRevision) {
            if (artRevision > 0L) {
                maybeTransferArtwork(artRevision, trackId)
            } else {
                pushedArtRevision = artRevision
            }
        }

        if (media.isNotEmpty() || playback.isNotEmpty()) {
            egress?.sendNowPlaying(media, playback)
            if (media.size + playback.size > 2 || title != null) {
                Log.i(
                    TAG,
                    "nowplaying pushed media=${media.keys.toList()} playback=${playback.keys.toList()} " +
                        "title=${title?.take(24)} art=${media[FIELD_ARTWORK_TRANSFER_ID]?.getOrNull(0)?.toInt()?.and(0xff)}",
                )
            }
        }
        if (!carryElapsed) {
            pushElapsedTick(progress, force = full)
        }
    }

    /** While playing, keep ELAPSED moving at 1 s cadence (whole-second dedup). */
    private fun pushElapsedTick(progressOverride: ClusterLyricStore.Progress? = null, force: Boolean = false) {
        val elapsed = elapsedParam(progressOverride, force) ?: return
        egress?.sendNowPlaying(emptyMap(), linkedMapOf(FIELD_ELAPSED to elapsed))
    }

    /**
     * Current elapsed, or null when not playing or the whole second was already sent.
     * [force] still attaches the value so a title update carries the clock in the same packet.
     */
    private fun elapsedParam(progressOverride: ClusterLyricStore.Progress? = null, force: Boolean = false): ByteArray? {
        val ctx = context ?: return null
        val progress = progressOverride ?: runCatching {
            ClusterLyricStore.readProgress(ctx.contentResolver)
        }.getOrNull() ?: return null
        if (pushedStatus != STATUS_PLAYING) {
            pushedElapsedSec = -1L
            return null
        }
        val pos = ClusterLyricStore.extrapolatePosition(progress)
        val sec = pos / 1_000L
        if (!force && sec == pushedElapsedSec) return null
        pushedElapsedSec = sec
        return Iap2Framing.u32Param(sec * 1_000L)
    }

    /** 8-byte big-endian id. Zero is reserved for "cleared"; same track string always maps to the same bytes. */
    private fun persistentIdBytes(trackId: String): ByteArray {
        var hash = FNV64_OFFSET
        for (b in trackId.toByteArray(Charsets.UTF_8)) {
            hash = hash xor (b.toULong() and 0xffUL)
            hash *= FNV64_PRIME
        }
        if (hash == 0UL) hash = 1UL
        val out = ByteArray(8)
        var v = hash
        for (i in 7 downTo 0) {
            out[i] = (v and 0xffUL).toByte()
            v = v shr 8
        }
        return out
    }

    // ------------------------------------------------------------------
    // Artwork: iAP2 v2 file transfer (session 12)
    // ------------------------------------------------------------------

    private fun maybeTransferArtwork(revision: Long, trackId: String?) {
        Thread(
            {
                val e = egress
                val jpeg = try {
                    CoreApi.getClusterArtJpeg()
                } catch (ex: Throwable) {
                    Log.w(TAG, "cluster art fetch failed", ex)
                    null
                }
                if (e == null || jpeg == null || jpeg.isEmpty()) {
                    Log.w(TAG, "artwork skip — jpeg=${jpeg?.size ?: -1}B egress=${e != null}")
                    return@Thread
                }
                if (jpeg.size > ART_MAX_BYTES) {
                    Log.w(TAG, "cluster art too large (${jpeg.size}B) — skipping")
                    return@Thread
                }
                val id = synchronized(artLock) {
                    if (pendingArt != null) return@synchronized -1
                    // Avoid 0 and 0x80 (none sentinel); keep 1..127.
                    val i = nextArtId.coerceIn(1, 0x7f)
                    nextArtId = if (i >= 0x7f) 1 else i + 1
                    pendingArt = PendingArt(i, trackId, jpeg, 0)
                    i
                }
                if (id < 0) return@Thread
                pushedArtRevision = revision
                // SETUP: [id, opcode=4, 8B BE size, 2B BE fileType]
                val setup = ByteArray(12)
                setup[0] = id.toByte()
                setup[1] = OP_SETUP.toByte()
                val size = jpeg.size.toLong()
                for (i in 0 until 8) {
                    setup[2 + i] = ((size ushr ((7 - i) * 8)) and 0xffL).toByte()
                }
                setup[10] = ((FILE_TYPE_NOW_PLAYING_ARTWORK ushr 8) and 0xff).toByte()
                setup[11] = (FILE_TYPE_NOW_PLAYING_ARTWORK and 0xff).toByte()
                e.sendFileTransfer(setup)
                // Announce transfer id with current card so Audi binds cover to this track
                // before DATA arrives (art-only post-SUCCESS was ignored on the cluster).
                announceArtworkId(id, trackId)
                Log.i(TAG, "artwork transfer started id=$id size=${jpeg.size}B track=${trackId?.take(24)}")
            },
            "cp-nowplaying-art",
        ).apply { isDaemon = true }.start()
    }

    /** Push MediaItem field 26 (+ title / pid when known) onto the live egress. */
    private fun announceArtworkId(id: Int, trackId: String?) {
        val media = linkedMapOf<Int, ByteArray>()
        media[FIELD_ARTWORK_TRANSFER_ID] = Iap2Framing.u8Param(id)
        pushedTitle?.let { media[FIELD_TITLE] = Iap2Framing.utf8Param(it) }
        if (trackId != null) {
            media[FIELD_PERSISTENT_ID] = persistentIdBytes(trackId)
        }
        egress?.sendNowPlaying(media, emptyMap())
        Log.i(TAG, "artwork announced id=$id in nowplaying media=${media.keys.toList()}")
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
                    // Allow retry on next revision/poll.
                    pushedArtRevision = -1L
                    Log.i(TAG, "artwork transfer cancelled id=$id")
                    return
                }
                else -> return
            }
        }
        if (op == OP_START) {
            sendArtChunks(art)
        } else {
            boundArtTransferId = id
            boundArtTrackId = art.trackId
            // Re-assert cover with the live title so lyric ticks do not orphan the image.
            announceArtworkId(id, art.trackId)
            Log.i(TAG, "artwork delivered id=$id (${art.bytes.size}B)")
        }
    }

    private fun sendArtChunks(art: PendingArt) {
        val e = egress ?: return
        var off = art.offset
        val total = art.bytes.size
        while (off < total) {
            val chunk = minOf(ART_CHUNK_BYTES, total - off)
            val first = off == 0
            val last = off + chunk >= total
            // 0xC0 = first+last, 0x80 = first, 0x40 = last, 0x00 = middle.
            val flags = (if (first) FLAG_FIRST else 0) or (if (last) FLAG_LAST else 0)
            val dg = ByteArray(2 + chunk)
            dg[0] = art.id.toByte()
            dg[1] = (flags or OP_DATA).toByte()
            System.arraycopy(art.bytes, off, dg, 2, chunk)
            e.sendFileTransfer(dg)
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
        ClusterLyricStore.SETTINGS_UPDATED_MS,
        ClusterLyricStore.SETTINGS_POSITION_MS,
        ClusterArtStore.SETTINGS_ART_MEDIA_ID,
        ClusterArtStore.SETTINGS_ART_REVISION,
        ClusterLyricStore.SETTINGS_DURATION_MS,
        ClusterLyricStore.SETTINGS_PLAYBACK_STATE,
    )
}
