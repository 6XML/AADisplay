package io.github.nitsuya.aa.display.xposed.hook.aa.coolwalk

import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.github.kyuubiran.ezxhelper.init.InitFields
import com.github.kyuubiran.ezxhelper.utils.argTypes
import com.github.kyuubiran.ezxhelper.utils.getIdByName
import com.github.kyuubiran.ezxhelper.utils.loadClass
import com.github.kyuubiran.ezxhelper.utils.staticMethod
import io.github.nitsuya.aa.display.ui.aa.split.SplitPane
import io.github.nitsuya.aa.display.xposed.util.log
import io.github.nitsuya.aa.display.xposed.util.logDebug
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Shared mutable hook state for Coolwalk / facet chrome submodules in one gearhead process.
 */
class CoolwalkHookEnv {

    var layoutInfoConstructors: List<Constructor<*>> = emptyList()
    /**
     * CarActivity launch entry:
     * - ≤17.4: static SysUi `a(Intent)`
     * - 17.8+: instance ProjectionContext launcher `i(Intent)` (+ [startSingletonGetter])
     */
    var startMethod: Method? = null
    /** Static no-arg getter returning the ProjectionContext launcher singleton (17.8+). */
    var startSingletonGetter: Method? = null
    /** Fallback receiver when no singleton getter (legacy instance capture). */
    @Volatile var startReceiver: Any? = null
    @Volatile var hasVerticalRailField: Field? = null
    @Volatile var layoutResourceIdField: Field? = null
    var contentBoundsMethods: List<Method> = emptyList()
    var facetBarSurfaceMethods: List<Method> = emptyList()
    var huTouchDispatchMethod: Method? = null
    /** Coolwalk compositor window bounds class (`{blX=` toString); may be null if DexKit miss. */
    var projectionBoundsClassName: String? = null

    @Volatile var mRailHostDownTime = 0L
    @Volatile var mHuRailGesture = false
    @Volatile var mHuPeelGesture = false
    @Volatile var mAaUiRailConsume = false
    @Volatile var mCachedFullscreenPane: Int = SplitPane.FULLSCREEN_NONE
    @Volatile var mSplitStateSeen = false
    val mRailMoveLock = Any()
    var mPendingRailMove: android.view.MotionEvent? = null
    @Volatile var mRailMoveGeneration = 0

    var mSplitStateReceiver: android.content.BroadcastReceiver? = null
    var mRailConsumeReceiver: android.content.BroadcastReceiver? = null

    var resLayoutLeftResourceId: Int = 0
    var resLayoutRightResourceId: Int = 0
    var resIdStatusBarId: Int = 0
    var resIdLauncherAndDashboardIconContainerId: Int = 0
    var resIdLauncherAndDashboardIconId: Int = 0
    val facetBarLayoutIds = mutableSetOf<Int>()
    val railHostLayoutIds = mutableSetOf<Int>()
    val railWidthDimenIds = mutableSetOf<Int>()
    var canHookFacetBar: Boolean = false
    var mInjectingFacetBar: Boolean = false
    val facetBarInjectedTag = Any()
    val mFacetEnsureHandler = Handler(Looper.getMainLooper())
    var mFacetEnsureDeadlineMs = 0L
    val mReclaimFollowUps = java.util.WeakHashMap<android.view.View, Runnable>()

    var mLayoutWidthDp: Int = 0
    var mLayoutHeightDp: Int = 0
    @Volatile var mLoggedDashboardStarve = false
    @Volatile var mLastProjectionConfigRewrite: String? = null

    var mAutoOpenSessionAtMs = 0L
    @Volatile var mAaDisplayShownThisSession = false
    @Volatile var mAutoOpenArmed = false
    var mAutoOpenShownReceiver: android.content.BroadcastReceiver? = null
    var mFullBleedRelaunchReceiver: android.content.BroadcastReceiver? = null
    var mCarConnectedKickHooked = false
    var mCarConnectedListenerHooked = false

    val mRailMoveFrameCallback = android.view.Choreographer.FrameCallback {
        AaCoolwalkHuTouchHook.flushPendingRailMove(this)
    }
    val mRailMovePostToFrame = Runnable {
        val choreographer = android.view.Choreographer.getInstance()
        choreographer.removeFrameCallback(mRailMoveFrameCallback)
        choreographer.postFrameCallback(mRailMoveFrameCallback)
    }

    fun layoutWidthPx(): Int =
        kotlin.math.max(
            mLayoutWidthDp,
            kotlin.math.max(CoolwalkRailCoordinator.layoutWidthPx(), CoolwalkRailCoordinator.observedFullHuWidthPx()),
        ).takeIf { it > 0 } ?: 0

    fun layoutHeightPx(): Int =
        kotlin.math.max(
            mLayoutHeightDp,
            CoolwalkRailCoordinator.layoutHeightPx(),
        ).takeIf { it > 0 } ?: 0

    fun logProjectionConfigRewriteOnce(message: String) {
        if (mLastProjectionConfigRewrite == message) return
        mLastProjectionConfigRewrite = message
        logDebug(TAG, message)
    }

    fun loadProjectionResources() {
        val pkg = InitFields.appContext.packageName
        val res = InitFields.appContext.resources
        fun layoutId(name: String): Int = res.getIdentifier(name, "layout", pkg).also { id ->
            if (id != 0) facetBarLayoutIds.add(id)
        }

        layoutId("gh_coolwalk_vertical_facet_bar")
        layoutId("gh_coolwalk_facet_bar")
        layoutId("gh_coolwalk_facet_bar_rhd")
        layoutId("gh_coolwalk_vertical_facet_bar_rhd")

        resIdStatusBarId = getIdByName("status_bar")
        resIdLauncherAndDashboardIconContainerId = getIdByName("launcher_and_dashboard_icon_container")
        resIdLauncherAndDashboardIconId = getIdByName("launcher_and_dashboard_icon")

        resLayoutLeftResourceId = res.getIdentifier("sys_ui_layout_canonical_vertical_rail_lhd", "layout", pkg)
        resLayoutRightResourceId = res.getIdentifier("sys_ui_layout_canonical_vertical_rail_rhd", "layout", pkg)
        if (resLayoutLeftResourceId != 0) railHostLayoutIds.add(resLayoutLeftResourceId)
        if (resLayoutRightResourceId != 0) railHostLayoutIds.add(resLayoutRightResourceId)

        for (name in RAIL_WIDTH_DIMEN_NAMES) {
            val id = res.getIdentifier(name, "dimen", pkg)
            if (id != 0) railWidthDimenIds.add(id)
        }

        if (resLayoutLeftResourceId == 0) {
            log(TAG, "AaUiHook: LHD canonical layout missing; still forcing hasVerticalRail=true")
        }
        canHookFacetBar =
            facetBarLayoutIds.isNotEmpty() &&
            resIdStatusBarId != 0 &&
            resIdLauncherAndDashboardIconContainerId != 0 &&
            resIdLauncherAndDashboardIconId != 0
        if (!canHookFacetBar) {
            log(
                TAG,
                "AaUiHook: skip facet-bar override, missing resources: facetIds=$facetBarLayoutIds, " +
                    "status=$resIdStatusBarId, launcherContainer=$resIdLauncherAndDashboardIconContainerId, " +
                    "launcherIcon=$resIdLauncherAndDashboardIconId",
            )
        } else {
            logDebug(TAG, "AaUiHook: facet layout ids=$facetBarLayoutIds railHosts=$railHostLayoutIds")
        }
        logDebug(TAG, "AaUiHook: rail width dimens=$railWidthDimenIds")
    }

    fun resolveLayoutInfoConstructors(className: String): List<Constructor<*>> {
        val clazz = loadClass(className)
        val matched = clazz.declaredConstructors.filter { ctor ->
            val p = ctor.parameterTypes
            p.size >= 10 &&
                p[0] == Int::class.javaPrimitiveType &&
                p[1] == Int::class.javaPrimitiveType &&
                p[2] == Int::class.javaPrimitiveType &&
                p[4] == Boolean::class.javaPrimitiveType &&
                p[5] == Boolean::class.javaPrimitiveType &&
                p[7] == Boolean::class.javaPrimitiveType &&
                p[8] == Boolean::class.javaPrimitiveType &&
                p[9] == Boolean::class.javaPrimitiveType &&
                !p[6].isPrimitive &&
                (p[3] == Int::class.javaPrimitiveType || p[3].isEnum)
        }
        if (matched.isEmpty()) {
            throw NoSuchMethodException("AaUiHook: not found compatible LayoutInfo constructor for $className")
        }
        for (ctor in matched) {
            ctor.isAccessible = true
        }
        val primary = matched.first()
        log(
            TAG,
            "AaUiHook: LayoutInfo constructor selected, paramCount=${primary.parameterCount}, " +
                "layoutTypeArg=${primary.parameterTypes[3].name}, matches=${matched.size}",
        )
        return matched
    }

    /**
     * Name-based fallback only (≤17.4 SysUi). 17.8 ProjectionContext launcher must be
     * resolved via DexKit ([resolveProjectionLauncherStart]).
     */
    fun resolveLegacyCarStartActivityMethod(): Method? {
        val clazz = try {
            loadClass(CAR_SYSTEM_UI_CONTROLLER_SERVICE)
        } catch (_: Throwable) {
            return null
        }
        try {
            return clazz.staticMethod("a", null, argTypes(Intent::class.java)).also {
                startSingletonGetter = null
                logDebug(TAG, "AaUiHook: startMethod=SysUi.a(Intent)")
            }
        } catch (_: Throwable) {
            logDebug(TAG, "AaUiHook: SysUi.a missing, scanning static Intent methods")
        }
        val candidates = clazz.declaredMethods.filter { m ->
            Modifier.isStatic(m.modifiers) &&
                m.parameterTypes.size == 1 &&
                m.parameterTypes[0] == Intent::class.java &&
                (m.returnType == Void.TYPE || m.returnType == Void::class.java)
        }
        val picked = candidates.firstOrNull()
        if (picked != null) {
            picked.isAccessible = true
            startSingletonGetter = null
            logDebug(
                TAG,
                "AaUiHook: startMethod=SysUi.${picked.name}(Intent) " +
                    "candidates=${candidates.map { it.name }}",
            )
        }
        return picked
    }

    /**
     * AA 17.8+: GH.ProjectionContext launcher — use the DexKit needle method itself
     * (`startCarActivity(intent:%s, activityOptions:%s)` → `(Intent, Options)V`).
     * Do **not** pick a random `(Intent)V` on the class: sibling `e` is a lifetime no-op.
     */
    fun resolveProjectionLauncherStart(needleMethod: Method): Boolean {
        val hostClass = needleMethod.declaringClass
        val params = needleMethod.parameterTypes
        if (params.size < 2 || params[0] != Intent::class.java) {
            log(
                TAG,
                "AaUiHook: ProjectionContext needle expected (Intent, Options)V got $needleMethod",
            )
            return false
        }
        if (needleMethod.returnType != Void.TYPE && needleMethod.returnType != Void::class.java) {
            log(TAG, "AaUiHook: ProjectionContext needle not void: $needleMethod")
            return false
        }
        // Options type must be constructible (empty ActivityOptions holder).
        val optionsCtor = runCatching {
            params[1].getDeclaredConstructor().also { it.isAccessible = true }
        }.getOrNull()
        if (optionsCtor == null) {
            log(TAG, "AaUiHook: ProjectionContext options type has no no-arg ctor: ${params[1].name}")
            return false
        }
        needleMethod.isAccessible = true
        val getter = hostClass.declaredMethods.firstOrNull { m ->
            Modifier.isStatic(m.modifiers) &&
                m.parameterCount == 0 &&
                m.returnType == hostClass
        }
        if (getter == null) {
            log(TAG, "AaUiHook: ProjectionContext singleton getter missing on ${hostClass.name}")
            return false
        }
        getter.isAccessible = true
        startMethod = needleMethod
        startSingletonGetter = getter
        logDebug(
            TAG,
            "AaUiHook: startMethod=ProjectionContext.${needleMethod.name}" +
                "(Intent, ${params[1].simpleName}) getter=${getter.name} class=${hostClass.name}",
        )
        return true
    }

    /** Invoke startMethod: static / singleton getter / captured receiver. */
    fun invokeStartCarActivity(intent: Intent) {
        val method = startMethod ?: throw IllegalStateException("startMethod null")
        if (Modifier.isStatic(method.modifiers)) {
            method.invoke(null, intent)
            return
        }
        val getter = startSingletonGetter
        val receiver = if (getter != null) {
            getter.invoke(null)
                ?: throw NullPointerException("ProjectionContext singleton null (not connected)")
        } else {
            startReceiver
                ?: throw NullPointerException("startReceiver null")
        }
        when (method.parameterTypes.size) {
            1 -> method.invoke(receiver, intent)
            2 -> {
                val optionsType = method.parameterTypes[1]
                val options = optionsType.getDeclaredConstructor().newInstance()
                method.invoke(receiver, intent, options)
            }
            else -> throw IllegalStateException(
                "startMethod unexpected arity ${method.parameterTypes.size}: $method",
            )
        }
    }

    companion object {
        const val TAG = "AAD_AaUiHook"

        const val CAR_SYSTEM_UI_CONTROLLER_SERVICE =
            "com.google.android.projection.gearhead.service.CarSystemUiControllerService"

        /** DexKit needle on GH.ProjectionContext launcher (17.8+ AutoOpen). */
        const val PROJECTION_START_NEEDLE =
            "startCarActivity(intent:%s, activityOptions:%s)"

        val RAIL_WIDTH_DIMEN_NAMES = arrayOf(
            "cielo_vertical_rail_width",
            "coolwalk_vertical_rail_width",
            "coolwalk_edge_column_width_padded",
            "rail_coolwalk_edge_column_width_padded",
            "floating_vertical_rail_space_width",
            "gearhead_edge_column_width",
            "gearhead_edge_column_width_padded",
            "rail_max_width",
            "legacy_facet_bar_touch_target_width",
        )

        const val FACET_ENSURE_WINDOW_MS = 2_000L
        const val FACET_ENSURE_POLL_MS = 250L
        val FACET_ENSURE_TOKEN = Any()
        val AUTO_OPEN_TOKEN = Any()
        val FULL_BLEED_RELAUNCH_TOKEN = Any()
        const val AUTO_OPEN_REARM_GAP_MS = 24_000L

        val PROJECTION_CONFIG_KEYS = setOf(
            "content_bounds", "contentBounds", "content_insets", "contentInsets",
        )

        val AUTO_OPEN_DELAYS_MS = longArrayOf(
            0L, 1500L, 5000L, 12_000L, 24_000L,
        )
    }
}
