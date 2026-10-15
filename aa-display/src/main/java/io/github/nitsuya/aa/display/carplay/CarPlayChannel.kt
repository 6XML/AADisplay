package io.github.nitsuya.aa.display.carplay

import android.content.Context

/**
 * User-selected egress channel, persisted app-side (not part of the module behavior pipeline).
 *
 *  - [CHANNEL_AA]: stock Android Auto egress only; the CarPlay source must not run.
 *  - [CHANNEL_CARPLAY]: wireless CarPlay source armed; egress goes to the CarPlay HU
 *    (AA-shell tee when an AA session is attached, else the CarPlay host panes).
 *
 * The default is [CHANNEL_AA] so real-car behavior is unchanged until the user opts in.
 */
object CarPlayChannel {
    const val CHANNEL_AA = 0
    const val CHANNEL_CARPLAY = 1

    private const val PREFS = "aadisplay_carplay"
    private const val KEY_CHANNEL = "channel"

    fun get(context: Context): Int =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_CHANNEL, CHANNEL_AA)

    fun set(context: Context, channel: Int) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_CHANNEL, if (channel == CHANNEL_CARPLAY) CHANNEL_CARPLAY else CHANNEL_AA)
            .apply()
    }

    fun useCarPlayEgress(context: Context): Boolean = get(context) == CHANNEL_CARPLAY
}
