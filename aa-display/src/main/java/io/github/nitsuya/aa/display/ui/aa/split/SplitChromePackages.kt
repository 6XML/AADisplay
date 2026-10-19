package io.github.nitsuya.aa.display.ui.aa.split

import io.github.nitsuya.aa.display.BuildConfig

/**
 * Packages treated as VD chrome / Secondary HOME (not user apps).
 *
 * Empty-pane detection must ignore launcher / edge-chooser tasks that OEMs place
 * on secondary displays. [OEM_SECONDARY_HOME] is an extensible set of known
 * Secondary HOME / edge packages; AOSP baselines stay in [BOUNCE_EXCLUDED].
 */
internal object SplitChromePackages {
    /** Common OEM Secondary HOME / edge chooser packages (extend as needed). */
    private val OEM_SECONDARY_HOME: Set<String> = setOf(
        "com.sec.android.app.launcher",
        "com.samsung.android.app.appsedge",
    )

    val BOUNCE_EXCLUDED: Set<String> = setOf(
        BuildConfig.APPLICATION_ID,
        "android",
        "com.android.systemui",
        "com.android.launcher3",
    ) + OEM_SECONDARY_HOME
}
