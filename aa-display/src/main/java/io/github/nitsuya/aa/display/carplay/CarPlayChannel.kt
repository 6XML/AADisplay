package io.github.nitsuya.aa.display.carplay

import android.content.Context
import android.util.Log
import java.io.File

/**
 * User-selected video egress channel, persisted app-side.
 *
 * Channels are **mutually exclusive** — one paints at a time:
 *  - [CHANNEL_AA]: stock Android Auto only; CarPlay Source must not run.
 *  - [CHANNEL_CARPLAY]: wireless CarPlay Source + [CarPlayHostActivity] GL panes;
 *    never tee the AA shell into CarPlay.
 *
 * Persistence uses [Context.getFilesDir] (not SharedPreferences). On this Z Flip the
 * app never created `shared_prefs/` — SP writes were silent no-ops — so AutoArm always
 * saw default AA after reboot and skipped FGS / `…cafe` RFCOMM listen.
 *
 * The default is [CHANNEL_AA] so real-car behavior is unchanged until the user opts in.
 */
object CarPlayChannel {
    const val CHANNEL_AA = 0
    const val CHANNEL_CARPLAY = 1

    private const val FILE_NAME = "aadisplay_carplay_channel"
    private const val LEGACY_PREFS = "aadisplay_carplay"
    private const val LEGACY_KEY = "channel"

    fun get(context: Context): Int {
        val app = context.applicationContext
        readFile(app)?.let { return it }
        // One-shot migrate from legacy SharedPreferences (if any OEM ever wrote it).
        val legacy = try {
            app.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
                .getInt(LEGACY_KEY, -1)
        } catch (_: Throwable) {
            -1
        }
        if (legacy == CHANNEL_CARPLAY || legacy == CHANNEL_AA) {
            writeFile(app, legacy)
            return legacy
        }
        return CHANNEL_AA
    }

    fun set(context: Context, channel: Int) {
        val value = if (channel == CHANNEL_CARPLAY) CHANNEL_CARPLAY else CHANNEL_AA
        val app = context.applicationContext
        if (!writeFile(app, value)) {
            Log.e(CarPlayConsts.TAG, "CarPlayChannel set failed channel=$value")
        }
        // Best-effort legacy mirror (ignore failures — SP has been unreliable here).
        try {
            app.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putInt(LEGACY_KEY, value)
                .commit()
        } catch (_: Throwable) {
        }
    }

    fun useCarPlayEgress(context: Context): Boolean = get(context) == CHANNEL_CARPLAY

    private fun channelFile(app: Context): File = File(app.filesDir, FILE_NAME)

    private fun readFile(app: Context): Int? {
        val f = channelFile(app)
        if (!f.isFile) return null
        return try {
            when (f.readText().trim()) {
                CHANNEL_CARPLAY.toString(), "cp", "carplay" -> CHANNEL_CARPLAY
                CHANNEL_AA.toString(), "aa" -> CHANNEL_AA
                else -> null
            }
        } catch (e: Throwable) {
            Log.w(CarPlayConsts.TAG, "CarPlayChannel read failed", e)
            null
        }
    }

    private fun writeFile(app: Context, channel: Int): Boolean {
        return try {
            val dir = app.filesDir
            if (!dir.exists() && !dir.mkdirs()) {
                Log.e(CarPlayConsts.TAG, "CarPlayChannel filesDir missing")
                return false
            }
            val f = channelFile(app)
            val tmp = File(dir, "$FILE_NAME.tmp")
            tmp.writeText(channel.toString())
            if (!tmp.renameTo(f)) {
                f.writeText(channel.toString())
                tmp.delete()
            }
            Log.i(CarPlayConsts.TAG, "CarPlayChannel persisted channel=$channel path=${f.absolutePath}")
            true
        } catch (e: Throwable) {
            Log.e(CarPlayConsts.TAG, "CarPlayChannel write failed", e)
            false
        }
    }
}
