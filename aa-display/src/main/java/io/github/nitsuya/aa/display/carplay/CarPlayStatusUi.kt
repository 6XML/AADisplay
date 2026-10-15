package io.github.nitsuya.aa.display.carplay

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.widget.Toast
import io.github.nitsuya.aa.display.CoreApi
import io.github.nitsuya.aa.display.R
import io.github.nitsuya.aa.display.databinding.ActivityMainBinding
import io.github.nitsuya.aa.display.egress.ProjectionSessionGate

/**
 * Status-page CarPlay controls. Keeps [io.github.nitsuya.aa.display.ui.main.MainActivity]
 * free of CarPlay session / channel logic beyond a single bind call.
 */
object CarPlayStatusUi {
    /** Swallows the listener callback raised while the channel radio is synced programmatically. */
    private var channelChangeFromUser = true

    fun bind(activity: Activity, binding: ActivityMainBinding) {
        // Register AA-shell tee sink even when the channel stays on Android Auto.
        CarPlayProjectionService.ensureShellSinkInstalled()
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
                CarPlayProjectionService.start(activity)
                if (!ProjectionSessionGate.isAaUiAttached() && CarPlayHostActivity.current == null) {
                    activity.startActivity(Intent(activity, CarPlayHostActivity::class.java))
                }
                toast(activity, "CarPlay 通道已启用")
            } else {
                CarPlayProjectionService.stop(activity)
                toast(activity, "已切回 Android Auto 通道")
            }
            refresh(activity, binding)
        }
        binding.btnCarplayHost.setOnClickListener {
            CarPlayChannel.set(activity, CarPlayChannel.CHANNEL_CARPLAY)
            CarPlayProjectionService.start(activity)
            activity.startActivity(Intent(activity, CarPlayHostActivity::class.java))
            refresh(activity, binding)
        }
        refresh(activity, binding)
    }

    fun handleLaunchIntent(activity: Activity, binding: ActivityMainBinding, intent: Intent?) {
        when (intent?.action) {
            CarPlayConsts.ACTION_CONNECT -> {
                CarPlayProjectionService.start(activity)
                if (intent.getBooleanExtra("startHost", false)) {
                    activity.startActivity(Intent(activity, CarPlayHostActivity::class.java))
                }
                refresh(activity, binding)
                toast(activity, "CarPlay Source 已启动")
            }
            CarPlayConsts.ACTION_STOP -> {
                CarPlayProjectionService.stop(activity)
                refresh(activity, binding)
                toast(activity, "CarPlay Source 已停止")
            }
        }
    }

    @SuppressLint("SetTextI18n")
    fun refresh(activity: Activity, binding: ActivityMainBinding) {
        val live = try {
            CoreApi.isCarPlaySessionLive
        } catch (_: Throwable) {
            ProjectionSessionGate.isCarPlayLive()
        }
        val tee = ProjectionSessionGate.shouldTeeFromAaShell()
        val host = ProjectionSessionGate.shouldUseCarPlayHost()
        val channel = CarPlayChannel.get(activity)
        val content = when {
            tee -> "AA 壳 tee（双通道）"
            host -> "Host 分屏"
            else -> "测试图"
        }
        binding.tvCarplayStatus.text =
            "通道：" + (if (channel == CarPlayChannel.CHANNEL_CARPLAY) "CarPlay" else "Android Auto") +
                "  会话：" + (if (live) "已连接" else "未连接") +
                "\n内容源：$content" +
                if (channel == CarPlayChannel.CHANNEL_CARPLAY) {
                    "\n能力：60fps 自适应（DiPlay 需设帧率=60）· 全部应用音频出车机（通话留手机）· 仪表歌词+封面 · 方向盘 · 三列 Recents · 180s 延迟销毁"
                } else {
                    ""
                }
        val wantId = if (channel == CarPlayChannel.CHANNEL_CARPLAY) {
            R.id.rb_channel_cp
        } else {
            R.id.rb_channel_aa
        }
        if (binding.rgCarplayChannel.checkedRadioButtonId != wantId) {
            channelChangeFromUser = false
            binding.rgCarplayChannel.check(wantId)
        }
    }

    private fun toast(activity: Activity, message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }
}
