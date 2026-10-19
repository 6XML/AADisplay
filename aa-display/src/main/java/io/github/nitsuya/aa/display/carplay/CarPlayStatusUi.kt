package io.github.nitsuya.aa.display.carplay

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.view.View
import android.widget.Toast
import io.github.nitsuya.aa.display.CoreApi
import io.github.nitsuya.aa.display.R
import io.github.nitsuya.aa.display.databinding.ActivityMainBinding
import io.github.nitsuya.aa.display.egress.ProjectionSessionGate

/**
 * Status-page CarPlay controls. Keeps [io.github.nitsuya.aa.display.ui.main.MainActivity]
 * free of CarPlay session / channel logic beyond a single bind call.
 *
 * Channel radio is always visible when Android Auto is installed; without gearhead the
 * AA option is hidden and CarPlay is the only egress. [panel_aa] vs [panel_carplay]
 * swap with the selection. Wired USB is gated by [CarPlayChannel.USB_CARPLAY_ENABLED]
 * and hidden from UI while WIP.
 */
object CarPlayStatusUi {
    /** Swallows the listener callback raised while the channel radio is synced programmatically. */
    private var channelChangeFromUser = true
    /** Channel waiting on [CarPlayRuntimePerms] dialog (wireless only). */
    private var pendingChannelAfterPerms: Int? = null

    fun bind(activity: Activity, binding: ActivityMainBinding) {
        applyAaAvailability(activity, binding)
        binding.rgCarplayChannel.setOnCheckedChangeListener { _, checkedId ->
            if (!channelChangeFromUser) {
                channelChangeFromUser = true
                return@setOnCheckedChangeListener
            }
            val channel = when (checkedId) {
                R.id.rb_channel_cp -> CarPlayChannel.CHANNEL_CARPLAY
                else -> CarPlayChannel.CHANNEL_AA
            }
            applyChannel(activity, binding, channel)
        }
        binding.btnCarplayHost.setOnClickListener {
            applyChannel(activity, binding, CarPlayChannel.CHANNEL_CARPLAY)
            // Re-prompt discoverable so the HU can find the phone after reconnect.
            CarPlayProjectionService.requestDiscoverable(activity)
        }
        binding.btnBtDiscoverable.setOnClickListener {
            CarPlayProjectionService.requestDiscoverable(activity)
            toast(activity, "请允许蓝牙可被发现（300 秒）")
        }
        refresh(activity, binding)
        ensureArmedIfCarPlay(activity)
    }

    /**
     * Called from [io.github.nitsuya.aa.display.ui.main.MainActivity.onRequestPermissionsResult].
     */
    fun onPermissionsResult(
        activity: Activity,
        binding: ActivityMainBinding,
        requestCode: Int,
        grantResults: IntArray,
    ) {
        if (!CarPlayRuntimePerms.onRequestResult(requestCode, grantResults)) {
            if (requestCode == CarPlayRuntimePerms.REQUEST_CODE) {
                pendingChannelAfterPerms = null
                toast(activity, "需要蓝牙权限才能连接 CarPlay")
                // Revert radio to last persisted so sticky FGS is not armed.
                refresh(activity, binding)
            }
            return
        }
        val channel = pendingChannelAfterPerms ?: CarPlayChannel.CHANNEL_CARPLAY
        pendingChannelAfterPerms = null
        finishApplyChannel(activity, binding, channel)
    }

    private fun applyChannel(
        activity: Activity,
        binding: ActivityMainBinding,
        channel: Int,
    ) {
        if (channel == CarPlayChannel.CHANNEL_CARPLAY &&
            !CarPlayRuntimePerms.ensureWireless(activity)
        ) {
            pendingChannelAfterPerms = channel
            toast(activity, "请允许蓝牙 / 附近设备权限")
            return
        }
        finishApplyChannel(activity, binding, channel)
    }

    private fun finishApplyChannel(
        activity: Activity,
        binding: ActivityMainBinding,
        channel: Int,
    ) {
        CarPlayChannel.set(activity, channel)
        if (CarPlayChannel.useCarPlayEgress(activity)) {
            // Listen-only until the HU dials — do not boot Host/VD/encoder here.
            CarPlayProjectionService.start(activity, launchHostTrampoline = false)
            CarPlayProjectionService.requestDiscoverable(activity)
            toast(activity, "已切到 CarPlay，请允许蓝牙可发现后到车机上连接")
        } else {
            CarPlayProjectionService.stop(activity)
            CarPlayHostActivity.current?.finish()
            toast(activity, "已切回 Android Auto")
        }
        refresh(activity, binding)
    }

    fun handleLaunchIntent(activity: Activity, binding: ActivityMainBinding, intent: Intent?) {
        when (intent?.action) {
            CarPlayConsts.ACTION_CONNECT -> {
                applyChannel(activity, binding, CarPlayChannel.CHANNEL_CARPLAY)
            }
            CarPlayConsts.ACTION_STOP -> {
                CarPlayProjectionService.stop(activity)
                refresh(activity, binding)
                toast(activity, "CarPlay 已停止")
            }
        }
    }

    /**
     * Start FGS when the persisted channel is CarPlay (idempotent if already live).
     * No Host trampoline: status page is already foreground; launching
     * [CarPlayHostActivity] then [Activity.moveTaskToBack] used to send the whole
     * status-page task to the background (felt like the app auto-exited).
     */
    fun ensureArmedIfCarPlay(activity: Activity) {
        if (!CarPlayChannel.useCarPlayEgress(activity)) return
        if (CarPlayChannel.useWirelessCarPlay(activity) &&
            !CarPlayRuntimePerms.ensureWireless(activity)
        ) {
            pendingChannelAfterPerms = CarPlayChannel.CHANNEL_CARPLAY
            return
        }
        try {
            CarPlayProjectionService.start(activity, launchHostTrampoline = false)
        } catch (e: Throwable) {
            android.util.Log.e(CarPlayConsts.TAG, "ensureArmedIfCarPlay failed", e)
        }
    }

    /** Hide AA radio when gearhead is not installed; CarPlay is the only egress. */
    private fun applyAaAvailability(activity: Activity, binding: ActivityMainBinding) {
        val aaInstalled = CarPlayChannel.isAndroidAutoInstalled(activity)
        binding.rbChannelAa.visibility = if (aaInstalled) View.VISIBLE else View.GONE
        // Without AA there is nothing to choose — keep the CarPlay row for clarity.
        binding.rgCarplayChannel.visibility = View.VISIBLE
    }

    @SuppressLint("SetTextI18n")
    fun refresh(activity: Activity, binding: ActivityMainBinding) {
        applyAaAvailability(activity, binding)
        val live = try {
            CoreApi.isCarPlaySessionLive
        } catch (_: Throwable) {
            ProjectionSessionGate.isCarPlayLive()
        }
        val channel = CarPlayChannel.get(activity)
        val carPlay = CarPlayChannel.useCarPlayEgress(activity)
        val armed = CarPlayProjectionService.isSessionArmed()
        val hostUp = CarPlayHostRuntime.isRunning()
        val aaInstalled = CarPlayChannel.isAndroidAutoInstalled(activity)

        // Plain-language status for the CarPlay panel (hidden when AA is selected).
        val (state, detail, stateColor) = when {
            !carPlay -> Triple("", "", activity.getColor(R.color.color_text_primary))
            live && hostUp -> Triple(
                "投屏中 / 已出画",
                "Host 与编码器已启动；车机断开后仍保持监听",
                activity.getColor(R.color.color_primary),
            )
            live -> Triple(
                "正在准备画面…",
                "稍等几秒；若一直停在这里，点下方「重新连接」",
                activity.getColor(R.color.color_outline),
            )
            armed -> Triple(
                "等待车机连接",
                "蓝牙已监听，车机连上后再启动画面编码（省电）",
                activity.getColor(R.color.color_primary),
            )
            else -> Triple(
                "尚未启动",
                if (aaInstalled) {
                    "点下方「重新连接」，或重新勾选上方「CarPlay 无线」"
                } else {
                    "点下方「重新连接」开始监听；车机用无线 CarPlay 连接"
                },
                activity.getColor(R.color.color_error),
            )
        }
        binding.tvCarplayState.text = state
        binding.tvCarplayState.setTextColor(stateColor)
        binding.tvCarplayDetail.text = detail
        binding.btnCarplayHost.text = when {
            !carPlay -> "重新连接"
            armed || live -> "重新连接"
            else -> "开始连接"
        }

        val wantId = when {
            !aaInstalled || channel == CarPlayChannel.CHANNEL_CARPLAY -> R.id.rb_channel_cp
            else -> R.id.rb_channel_aa
        }
        if (binding.rgCarplayChannel.checkedRadioButtonId != wantId) {
            channelChangeFromUser = false
            binding.rgCarplayChannel.check(wantId)
        }

        // DPI panel stays visible for both AA and CarPlay (labels adapt in MainActivity).
        binding.panelAa.visibility = View.VISIBLE
        binding.panelCarplay.visibility = if (carPlay) View.VISIBLE else View.GONE
    }

    private fun toast(activity: Activity, message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }
}
