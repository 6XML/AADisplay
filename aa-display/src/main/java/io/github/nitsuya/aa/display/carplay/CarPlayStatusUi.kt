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
 * Channel radio is always visible; [panel_aa] vs [panel_carplay] swap with the selection.
 */
object CarPlayStatusUi {
    /** Swallows the listener callback raised while the channel radio is synced programmatically. */
    private var channelChangeFromUser = true

    fun bind(activity: Activity, binding: ActivityMainBinding) {
        binding.rgCarplayChannel.setOnCheckedChangeListener { _, checkedId ->
            if (!channelChangeFromUser) {
                channelChangeFromUser = true
                return@setOnCheckedChangeListener
            }
            val channel = if (checkedId == R.id.rb_channel_cp) {
                CarPlayChannel.CHANNEL_CARPLAY
            } else {
                CarPlayChannel.CHANNEL_AA
            }
            CarPlayChannel.set(activity, channel)
            if (channel == CarPlayChannel.CHANNEL_CARPLAY) {
                // Exclusive: CP uses Host only — never AA-shell tee.
                CarPlayProjectionService.start(activity)
                CarPlayHostActivity.launch(activity)
                toast(activity, "已切换到 CarPlay")
            } else {
                CarPlayProjectionService.stop(activity)
                CarPlayHostActivity.current?.finish()
                // Runtime teardown is owned by FGS tearDown; finish trampoline only.
                toast(activity, "已切回 Android Auto")
            }
            refresh(activity, binding)
        }
        binding.btnCarplayHost.setOnClickListener {
            CarPlayChannel.set(activity, CarPlayChannel.CHANNEL_CARPLAY)
            CarPlayProjectionService.start(activity)
            CarPlayHostActivity.launch(activity)
            refresh(activity, binding)
        }
        refresh(activity, binding)
        // Re-arm after adb install / force-stop: stopped apps miss BOOT_COMPLETED;
        // opening the status page must restore …cafe RFCOMM when channel=CarPlay.
        ensureArmedIfCarPlay(activity)
    }

    fun handleLaunchIntent(activity: Activity, binding: ActivityMainBinding, intent: Intent?) {
        when (intent?.action) {
            CarPlayConsts.ACTION_CONNECT -> {
                CarPlayChannel.set(activity, CarPlayChannel.CHANNEL_CARPLAY)
                CarPlayProjectionService.start(activity)
                // FGS also launches Host; startHost extra kept for older callers.
                if (intent.getBooleanExtra("startHost", true)) {
                    CarPlayHostActivity.launch(activity)
                }
                refresh(activity, binding)
                toast(activity, "CarPlay 已启动")
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
        try {
            CarPlayProjectionService.start(activity, launchHostTrampoline = false)
            if (!CarPlayHostRuntime.isRunning()) {
                CarPlayHostRuntime.ensureStarted(activity)
            }
        } catch (e: Throwable) {
            android.util.Log.e(CarPlayConsts.TAG, "ensureArmedIfCarPlay failed", e)
        }
    }

    @SuppressLint("SetTextI18n")
    fun refresh(activity: Activity, binding: ActivityMainBinding) {
        val live = try {
            CoreApi.isCarPlaySessionLive
        } catch (_: Throwable) {
            ProjectionSessionGate.isCarPlayLive()
        }
        val channel = CarPlayChannel.get(activity)
        val carPlay = channel == CarPlayChannel.CHANNEL_CARPLAY
        val hostUp = CarPlayHostRuntime.isRunning()
        // `live` = FGS / SourceSession armed, not "HU already linked".
        val service = when {
            !carPlay -> "—"
            live -> "已就绪（等车机）"
            else -> "未启动"
        }
        val content = when {
            !carPlay -> "Android Auto"
            hostUp -> "分屏投屏"
            live -> "测试图（Host 未就绪）"
            else -> "—"
        }
        binding.tvCarplayStatus.text =
            "服务：$service" +
                "\n画面：$content" +
                "\nHost：" + (if (hostUp) "运行中" else "未启动") +
                "\n提示：DiPlay 帧率设 60；通话留手机，其余音频出车机"

        val wantId = if (carPlay) R.id.rb_channel_cp else R.id.rb_channel_aa
        if (binding.rgCarplayChannel.checkedRadioButtonId != wantId) {
            channelChangeFromUser = false
            binding.rgCarplayChannel.check(wantId)
        }

        binding.panelAa.visibility = if (carPlay) View.GONE else View.VISIBLE
        binding.panelCarplay.visibility = if (carPlay) View.VISIBLE else View.GONE
    }

    private fun toast(activity: Activity, message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }
}
