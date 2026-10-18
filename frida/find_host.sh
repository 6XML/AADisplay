#!/bin/sh
# 在 Mac 端跑（iPhone 已 USB 连 Mac）：遍历所有 iOS 进程，找出真正导出
# ccchacha20poly1305_encrypt/decrypt 的宿主进程（预期 airplayd，但不绑死）。
# 输出：<pid> <procname> HIT ...
#
# 注意：遍历会短暂 attach 每个进程，属只读探测，对 CarPlay 会话无副作用，
# 但建议在「上车前 / 未连车机时」跑，避免干扰连接时序。
set -u
for line in $(frida-ps -U 2>/dev/null); do
    pid=$(echo "$line" | awk '{print $1}')
    name=$(echo "$line" | awk '{print $2}')
    [ -z "$pid" ] && continue
    [ -z "$name" ] && continue
    out=$(frida -U -p "$pid" -e "$(cat "$(dirname "$0")/find_host.js")" 2>/dev/null | grep 'HIT' || true)
    if [ -n "$out" ]; then
        echo "$pid $name $out"
    fi
done
echo "done."
