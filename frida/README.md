# Frida 事件通道明文抓取（越狱 iPhone × 奥迪）

抓 **airplayd**（或实际宿主）里 `ccchacha20poly1305_encrypt/decrypt` 的
**加密前 / 解密后明文**，只留 **AAD=2 的事件/控制通道**帧，
重点：RTSP `POST /command` + `iAPSendMessage`、iAP2 检测帧 `ff 55 02 00 ee 10`、链路 `ff 5a` SYN/ACK。
画面流（AAD=128）不 dump，只计数（每 5s 汇总一行，用来标出画面流开始/出现时刻）。

拓扑：**Mac ←USB(数据线)→ 越狱 iPhone ⇄ 无线 CarPlay → 奥迪 MMI**。
Frida 走 USB，不碰 Wi-Fi pcap（事件通道已加密，pcap 解不开）。

## 文件

| 文件 | 用途 |
|------|------|
| `aad_event_hook.js` | 主 hook：AAD=2 帧全量记录（分类 tag + hex/text），AAD≠2 只计数 + 5s 汇总 |
| `find_host.js` | 单进程符号探测（是否导出 cc-chacha 符号） |
| `find_host.sh` | 遍历全部 iOS 进程跑探测，输出宿主 `<pid> <name>` |

## 上车前（Mac 端，iPhone USB 已连）

```bash
# 1. 确认宿主进程（预期 airplayd；不连车机时跑，避免干扰连接时序）
./find_host.sh
# 输出示例: 1234 airplayd HIT 1234 airplayd enc+dec in /usr/lib/...

# 2. 确认 Frida 能看到该进程
frida-ps -U | grep -i -E 'airplay|carplay'
```

## 上车后

```bash
# 主抓取（输出 → iphone-event.txt）
frida -U airplayd -l aad_event_hook.js -o iphone-event.txt
```

- 若 `airplayd` 进程名对不上 / attach 失败：用 `find_host.sh` 打出的 pid：
  `frida -U -p <pid> -l aad_event_hook.js -o iphone-event.txt`
- 若脚本启动打印 `!! 当前进程未导出 ...`：说明 attach 错进程，同上换 pid。

## 操作时间线（手机侧）

1. 脚本 attach 成功（看到 `hook ready`）后再开始
2. iPhone 连无线 CarPlay → 等 MMI CarPlay 画面出现
3. **出画后再观察约 10–20s**（覆盖画面出现后的事件）
4. Ctrl-C 停止

## 日志格式

```
[T+1:23.456 10:32:41] ENCRYPT ->HU aadlen=2 tag=RTSP len=1234 aad=[0a 0b]
    text: POST /command RTSP/1.0 ... C-RTP-Info ... iAPSendMessage ...
[T+1:24.001 10:32:41] DECRYPT <-HU aadlen=2 tag=DETECT len=6 aad=[0a 0b]
00000000  ff 55 02 00 ee 10  |.....|
[T+5:00.002 10:32:45] SUMMARY enc=12 dec=8 events(aad=2)=5 skippedTotal=380(+120/5s) { aad128=380 }
```

- `tag`：`RTSP`（POST /command / iAPSendMessage）、`DETECT`（ff 55 02 00 ee 10）、
  `LINK`（ff 5a）、`IAP?`/`TEXT`（其它文本）、`OTHER`（其它二进制）
- `ENCRYPT ->HU` = 手机发出（加密前明文）；`DECRYPT <-HU` = 车机发来（解密后明文）
- `skippedTotal` 突增（尤其 `aad128`）= 画面流开始/恢复 —— 用它对齐「画面出来」时刻
- 单帧载荷 dump 上限 1024 字节（超出截断并标注全长），事件通道帧一般远小于此

## 分析提示

```bash
# 会话建立→出画时序主视图
rg -n 'tag=(RTSP|DETECT|LINK)' iphone-event.txt

# iAPSendMessage 内容
rg -n 'iAPSendMessage' iphone-event.txt

# 画面流起止
rg -n 'SUMMARY' iphone-event.txt
```

## 备注

- 符号在目标进程所有模块里动态搜索（不绑死 libcommonCrypto / libSystem），iOS 版本差异免疫。
- 解密失败帧（auth tag 不通过）记一行 `FAILED`，不打载荷。
- 需要更长窗口：`-o` 只是追加 console 输出，可加 `-O`（覆盖）；时间戳含相对 `T+` 与墙上时钟，两段日志可拼接。
