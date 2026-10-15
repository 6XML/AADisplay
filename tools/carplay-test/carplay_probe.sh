#!/usr/bin/env bash
#
# carplay_probe.sh — Mac 侧 CarPlay Source（伪装 iPhone）诊断工具
#
# 用途：aa-display 的 carplay 模块（Source 侧）在 Mac 上用 CarPlay Simulator 测试时，
#       对 Bonjour / RTSP / 蓝牙 iAP2 三层链路做分层探测。
#
# 用法：
#   ./carplay_probe.sh browse                 # 轮询发现 _carplay-ctrl._tcp（看安卓是否注册成功）
#   ./carplay_probe.sh resolve <服务名>       # 解析某个发现的服务，拿到 IP:端口
#   ./carplay_probe.sh rtsp <ip> <port> [METHOD]   # 手动打 RTSP（默认 OPTIONS）
#   ./carplay_probe.sh btsnoop start          # 开始蓝牙抓包（需 sudo）
#   ./carplay_probe.sh btsnoop stop           # 停止并输出 .btsnoop 文件路径（用 Wireshark 打开）
#   ./carplay_probe.sh logcat                 # 打印安卓侧要跑的 logcat 命令
#
set -u

TYPE="_carplay-ctrl._tcp"
RTSP_DIR="$(cd "$(dirname "$0")" && pwd)"
RTSP_OUT="$RTSP_DIR/rtsp-capture"
mkdir -p "$RTSP_OUT"

cmd_browse() {
  echo ">> 浏览 $TYPE （Ctrl-C 退出）。安卓侧需已启动 CarPlayProjectionService 且与 Mac 同 Wi-Fi"
  dns-sd -B "$TYPE" .
}

cmd_resolve() {
  local name="${1:?用法: resolve <服务名>（来自 browse 输出，去掉尾部点号）}"
  dns-sd -L "$name" "$TYPE" .
}

cmd_rtsp() {
  local ip="${1:?用法: rtsp <ip> <port> [METHOD]}"
  local port="${2:?缺少端口}"
  local method="${3:-OPTIONS}"
  local url="rtsp://$ip:$port/"
  local f="$RTSP_OUT/$(date +%H%M%S)-${method}.txt"
  echo ">> $method $url"
  curl -sS -v -X "$method" "$url" -o /dev/null 2>&1 | tee "$f"
  echo ">> 完整报文已存: $f"
}

cmd_btsnoop() {
  local action="${1:-}"
  case "$action" in
    start)
      sudo defaults write /var/db/Bluetooth/Settings AppleBTLog -string btsnoop
      sudo killall bluetoothd
      echo ">> 蓝牙 btsnoop 抓包已开启（bluetoothd 会自动拉起）。现在去做配对/连接，完成后运行 stop。"
      ;;
    stop)
      sudo killall bluetoothd
      sudo defaults delete /var/db/Bluetooth/Settings AppleBTLog
      local latest
      latest=$(ls -t /var/db/Bluetooth/Logs/*.btsnoop 2>/dev/null | head -1)
      if [[ -n "${latest:-}" ]]; then
        cp "$latest" "$RTSP_DIR/capture-$(date +%H%M%S).btsnoop"
        echo ">> 停止。最新抓包: ${latest}（已复制到 $RTSP_DIR，用 Wireshark 打开，过滤器: btsctlv 或 l2cap）"
      else
        echo ">> 停止。未找到 /var/db/Bluetooth/Logs/*.btsnoop，检查蓝牙日志目录"
      fi
      ;;
    *)
      echo "用法: btsnoop start|stop" ;;
  esac
}

cmd_logcat() {
  cat <<'EOF'
>> 安卓侧（USB 连电脑后）跑：
   adb logcat -s AADisplay_CarPlay:*
   # tag 见 CarPlayConsts.TAG
>> 启动 CarPlay 出口：在 aa-display 状态页点启动 CarPlay（ProjectionSessionService Intent CONNECT）
EOF
}

case "${1:-}" in
  browse)  cmd_browse ;;
  resolve) cmd_resolve "${@:2}" ;;
  rtsp)    cmd_rtsp "${@:2}" ;;
  btsnoop) cmd_btsnoop "${2:-}" ;;
  logcat)  cmd_logcat ;;
  *)
    grep '^#' "$0" | sed 's/^# \{0,1\}//' | sed '1d' ;;
esac
