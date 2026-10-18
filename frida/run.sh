#!/bin/sh
# 一键抓取：自动找宿主进程（优先 airplayd，否则全进程扫描）→ attach → 输出落盘。
# iPhone 已 USB 连 Mac。看到 "hook ready" 后再去连车机。Ctrl-C 结束。
set -u
cd "$(dirname "$0")"
HOSTJS="$(cat find_host.js)"

check() {
    # $1=pid → 有 HIT 输出则成功
    frida -U -p "$1" -e "$HOSTJS" 2>/dev/null | grep 'HIT' || true
}

echo "== 查找事件通道宿主进程 =="
pid=""
# 优先 airplayd
for line in $(frida-ps -U 2>/dev/null | awk '$2 ~ /airplay/ {print $1}'); do
    out=$(check "$line")
    if [ -n "$out" ]; then
        pid=$line
        echo "host: $line (airplay*)"
        break
    fi
done
if [ -z "$pid" ]; then
    # 兜底：airplayd 按需拉起，可能还没起 —— 直接 attach airplay*/CarPlay，让 hook 常驻
    for line in $(frida-ps -U 2>/dev/null | awk '$2 ~ /airplay|CarPlay/ {print $1 " " $2}'); do
        p=$(echo "$line" | awk '{print $1}')
        out=$(check "$p")
        if [ -n "$out" ]; then
            pid=$p
            echo "host: $line"
            break
        fi
    done
fi
if [ -z "$pid" ]; then
    for line in $(frida-ps -U 2>/dev/null); do
        p=$(echo "$line" | awk '{print $1}')
        [ -z "$p" ] && continue
        out=$(check "$p")
        if [ -n "$out" ]; then
            pid=$p
            echo "host: $line"
            break
        fi
    done
fi
if [ -z "$pid" ]; then
    # 最后兜底：符号扫描全空但 airplay 系进程存在（dylib 可能懒加载），先 attach 上等
    for line in $(frida-ps -U 2>/dev/null | awk '$2 ~ /airplay|CarPlay/ {print $1}'); do
        pid=$line
        echo "warn: 未扫到符号，按进程名 attach airplay/CarPlay（$line），若提示无符号请先触发一次 CarPlay 再重跑"
        break
    done
fi
[ -z "$pid" ] && { echo "!! 未找到宿主（无符号且无 airplay/CarPlay 进程）。请先在手机上触发一次 CarPlay 设置/连接，再重跑 ./run.sh"; exit 1; }

LOG="iphone-event_$(date +%Y%m%d_%H%M%S).txt"
echo "== 开始抓取 → $LOG （连车机吧，出画后停 20s 再 Ctrl-C）=="
exec frida -U -p "$pid" -l aad_event_hook.js -o "$LOG"
