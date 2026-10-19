package io.github.nitsuya.aa.display.carplay

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import java.io.File

/**
 * User-selected video egress channel, persisted app-side.
 *
 * Channels are **mutually exclusive** — one paints at a time:
 *  - [CHANNEL_AA]: stock Android Auto only; CarPlay Source must not run.
 *  - [CHANNEL_CARPLAY]: wireless CarPlay Source + [CarPlayHostActivity] GL panes;
 *    never tee the AA shell into CarPlay.
 *  - [CHANNEL_CARPLAY_USB]: wired fake-iPhone path via aad-usb + NCM (no BT/Bonjour).
 *    Gated by [USB_CARPLAY_ENABLED] (WIP; off in public builds).
 *
 * Persistence uses [Context.getFilesDir] (not SharedPreferences). On some OEMs
 * SharedPreferences never materializes `shared_prefs/` (silent no-ops), so AutoArm
 * would always see default AA after reboot and skip FGS / `…cafe` RFCOMM listen.
 *
 * Default when nothing is persisted: [CHANNEL_AA] if Android Auto (gearhead) is
 * installed, otherwise [CHANNEL_CARPLAY] so phones without AA can use wireless
 * CarPlay without a manual channel switch.
 */
object CarPlayChannel {
    const val CHANNEL_AA = 0
    const val CHANNEL_CARPLAY = 1
    const val CHANNEL_CARPLAY_USB = 2

    /** Same package LSPosed / BridgeService resolve for Android Auto. */
    const val GEARHEAD_PACKAGE = "com.google.android.projection.gearhead"

    /**
     * Wired USB egress — WIP. Keep false for public builds so leftover `cp-usb`
     * cannot arm FGS or enter [CarPlaySourceSession] USB transport.
     */
    const val USB_CARPLAY_ENABLED = false

    private const val FILE_NAME = "aadisplay_carplay_channel"
    /** Mirrored for system_server [io.github.nitsuya.aa.display.xposed.carplay.CarPlayBootArm]. */
    const val SETTINGS_CHANNEL = "aadisplay_carplay_channel"
    private const val LEGACY_PREFS = "aadisplay_carplay"
    private const val LEGACY_KEY = "channel"

    fun isAndroidAutoInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(GEARHEAD_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (_: Throwable) {
            false
        }
    }

    /** Channel used when no file / legacy prefs exist yet. */
    fun defaultChannel(context: Context): Int =
        if (isAndroidAutoInstalled(context)) CHANNEL_AA else CHANNEL_CARPLAY

    fun get(context: Context): Int {
        val app = context.applicationContext
        val raw = readFile(app) ?: migrateLegacy(app)
        if (raw != null) {
            return resolveActive(app, raw, clearDirtyUsb = true)
        }
        val def = defaultChannel(app)
        // Persist so BootArm / AutoArm see an explicit choice after first read.
        writeFile(app, def)
        Log.i(CarPlayConsts.TAG, "CarPlayChannel default applied channel=$def aaInstalled=${isAndroidAutoInstalled(app)}")
        return def
    }

    fun set(context: Context, channel: Int) {
        val value = normalizeForWrite(context.applicationContext, channel)
        val app = context.applicationContext
        if (!writeFile(app, value)) {
            Log.e(CarPlayConsts.TAG, "CarPlayChannel set failed channel=$value")
        }
        // Best-effort legacy mirror (ignore failures — SP has been unreliable on some OEMs).
        try {
            app.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putInt(LEGACY_KEY, value)
                .commit()
        } catch (_: Throwable) {
        }
    }

    /** Active CarPlay Source egress (not AA). USB counts only when [USB_CARPLAY_ENABLED]. */
    fun useCarPlayEgress(context: Context): Boolean {
        val ch = get(context)
        return ch == CHANNEL_CARPLAY || (USB_CARPLAY_ENABLED && ch == CHANNEL_CARPLAY_USB)
    }

    fun useUsbCarPlay(context: Context): Boolean =
        USB_CARPLAY_ENABLED && get(context) == CHANNEL_CARPLAY_USB

    fun useWirelessCarPlay(context: Context): Boolean = get(context) == CHANNEL_CARPLAY

    /** Map a persisted / requested channel to what is allowed to be active. */
    private fun resolveActive(app: Context, channel: Int, clearDirtyUsb: Boolean): Int {
        if (channel == CHANNEL_CARPLAY_USB && !USB_CARPLAY_ENABLED) {
            val fallback = defaultChannel(app)
            if (clearDirtyUsb) {
                writeFile(app, fallback)
                Log.i(CarPlayConsts.TAG, "CarPlayChannel USB disabled — cleared leftover cp-usb → $fallback")
            }
            return fallback
        }
        return when (channel) {
            CHANNEL_CARPLAY -> CHANNEL_CARPLAY
            CHANNEL_CARPLAY_USB -> CHANNEL_CARPLAY_USB
            else -> CHANNEL_AA
        }
    }

    private fun normalizeForWrite(app: Context, channel: Int): Int = when (channel) {
        CHANNEL_CARPLAY -> CHANNEL_CARPLAY
        CHANNEL_CARPLAY_USB -> if (USB_CARPLAY_ENABLED) CHANNEL_CARPLAY_USB else defaultChannel(app)
        else -> CHANNEL_AA
    }

    private fun migrateLegacy(app: Context): Int? {
        val legacy = try {
            app.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
                .getInt(LEGACY_KEY, -1)
        } catch (_: Throwable) {
            -1
        }
        if (legacy != CHANNEL_CARPLAY && legacy != CHANNEL_AA && legacy != CHANNEL_CARPLAY_USB) {
            return null
        }
        val active = resolveActive(app, legacy, clearDirtyUsb = false)
        writeFile(app, active)
        return active
    }

    private fun channelFile(app: Context): File = File(app.filesDir, FILE_NAME)

    private fun readFile(app: Context): Int? {
        val f = channelFile(app)
        if (!f.isFile) return null
        return try {
            when (f.readText().trim()) {
                CHANNEL_CARPLAY.toString(), "cp", "carplay" -> CHANNEL_CARPLAY
                CHANNEL_CARPLAY_USB.toString(), "cp-usb", "carplay-usb", "usb" -> CHANNEL_CARPLAY_USB
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
            mirrorSettings(app, channel)
            true
        } catch (e: Throwable) {
            Log.e(CarPlayConsts.TAG, "CarPlayChannel write failed", e)
            false
        }
    }

    /** Best-effort Settings.Global so system_server BootArm can read without CE files. */
    private fun mirrorSettings(app: Context, channel: Int) {
        try {
            Settings.Global.putInt(app.contentResolver, SETTINGS_CHANNEL, channel)
        } catch (e: Throwable) {
            // App uid lacks WRITE_SECURE_SETTINGS; BootArm also reads the CE file.
            Log.w(
                CarPlayConsts.TAG,
                "CarPlayChannel Settings.Global mirror skipped: ${e.message}",
            )
        }
    }
}
