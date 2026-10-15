# 在 Mac 上测试无线 CarPlay Source（aa-display 伪装 iPhone）

aa-display 的 `carplay/` 模块是 **Source 侧**（伪装 iPhone）：

```
Mac: CarPlay Simulator (Host/车机)
        ▲ 经典蓝牙（iAP2 控制信道）
        │
        │ 同 Wi-Fi（RTSP 控制 + 媒体流）
        ▼
安卓真机: aa-display (Source/伪装 iPhone)
  FrameEgressTee → CarPlayVideoEncoder(AVC)
  CarPlayRtspSession(服务器) + CarPlayBonjour(注册/发现) + CarPlayBluetoothLink(RFCOMM/iAP2)
```

Mac 上的 **CarPlay Simulator** 扮演 Host（车机），正好配对。⚠️ 不要用安卓模拟器（AVD）：虚拟蓝牙/虚拟 Wi-Fi 跑不通 RFCOMM iAP2 和 mDNS。

## 1. Mac 侧安装 CarPlay Simulator

1. 登录 [developer.apple.com/download/all](https://developer.apple.com/download/all/)
2. 下载 **Additional Tools for Xcode 27**（对应本机 Xcode 版本；Xcode 主安装包不含模拟器）
3. 安装：DMG 是目录型，把 `Hardware/CarPlay Simulator.app` 拷到 `/Applications/`（本机已完成，2026-10-14）
4. 打开 CarPlay Simulator，保持窗口前台（部分版本切后台会断开会话）

## 2. 组网

| 端 | 要求 |
|---|---|
| Mac | 2.4G Wi-Fi（或有线 + AP 同时给安卓发 Wi-Fi）；Wi-Fi 关闭 AP 隔离/ACL |
| 安卓真机（Android 13+） | 与 Mac **同一 SSID**；装 aa-display debug APK；已 root + LSPosed 激活（若走 Xposed 链路） |
| 蓝牙 | 安卓与 Mac **先在系统蓝牙设置里完成配对**（经典蓝牙，SPP/serial port） |

验证同网段：Mac 上 `ping <安卓IP>`，且安卓上打开浏览器访问 `http://<MacIP>` 应可达（确认无隔离）。

## 3. 分层验证（推荐顺序，勿等全链路）

辅助脚本：[`tools/carplay-test/carplay_probe.sh`](../tools/carplay-test/carplay_probe.sh)

### L1 Bonjour 注册/发现

1. 安卓 logcat：`adb logcat -s AADisplay_CarPlay:*`，确认 `RTSP control listening port=xxxx`、`registerControl`
2. Mac：`./carplay_probe.sh browse` —— 应出现安卓注册的 `_carplay-ctrl._tcp` 实例
3. `./carplay_probe.sh resolve <实例名>` 拿到 IP:端口

> 注意：真车机是**车机广播、iPhone 发现**；你的 `CarPlaySourceSession` 目前是"自己注册 control + 自己 discover"。用 CarPlay Simulator 测试时要确认它是广播方还是监听方——若方向与真车相反，L1 的"发现方"在 Mac 上，脚本 browse 看不到东西属正常，改看 Mac 的广播（见 §4 抓包）。

### L2 蓝牙 iAP2

1. Mac：`./carplay_probe.sh btsnoop start`
2. 触发连接（配对已预先完成；Source 会 **dial** bonded 的 iAP2 UUID，并 listen 兜底）
3. `./carplay_probe.sh btsnoop stop` → 生成 `.btsnoop`，用 Wireshark 打开，过滤器 `btsctlv || l2cap`
4. 安卓 logcat 期望顺序：`RFCOMM peer connected/accepted` → `detect exchanged` → `link ready` → `StartIdentification` → `IdentificationAccepted` → `RequestAuthenticationCertificate` → `AuthenticationSucceeded` → `RequestAccessoryWiFi` / `CarPlayAvailability` → `AccessoryWiFi` 与/或 `CarPlayStartSession`
5. **基线对照**：先拿**真 iPhone**（iOS 16+）连一次 CarPlay Simulator / diplay，同样抓 btsnoop + Wireshark，确认模拟器到底要求哪种握手（是否必须经典蓝牙 iAP2，还是 Wi-Fi 直连）——这一步决定后面所有实现方向

### L3 RTSP 控制

```bash
./carplay_probe.sh rtsp <安卓IP> <port> OPTIONS
./carplay_probe.sh rtsp <安卓IP> <port> DESCRIBE
# 模拟车机 SETUP/RECORD 用 Wireshark 抓到的真实报文序列（§4 基线）
```

完整报文自动存到 `tools/carplay-test/rtsp-capture/`。

### L4 视频/音频流（最后做）

AVC RTP + FairPlay 加密。先按 §4 抓真 iPhone 的 RTP 包格式（负载是否加密、加密模式、key 协商位置），再实现 `CarPlayVideoEncoder` 回调里的 RTP 组包。

## 4. 抓真 iPhone 基线（最重要的一次抓取）

Mac（Wireshark + btsnoop 双管齐下）：

```bash
./carplay_probe.sh btsnoop start
sudo tcpdump -i en0 -w ~/carplay-baseline.pcap   # Wi-Fi 侧：RTSP + RTP
# 真 iPhone（iOS 16+）设置 → 通用 → CarPlay → 连接 Mac 上的 CarPlay Simulator
# 操作几分钟（切 App、滑动、放音乐）
# Ctrl-C 停 tcpdump
./carplay_probe.sh btsnoop stop
```

基线里要标清楚的时刻点（用秒数对齐三份日志：tcpdump / btsnoop / 安卓 logcat）：
- iPhone 出现在 Wi-Fi 列表的时刻
- 首次 RTSP OPTIONS/SETUP/RECORD 序列
- 首帧视频 RTP 的 Ssrc/加密标志
- 触控输入回传帧格式

## 5. 竞品参照：CarWith

`~/Downloads/CarWith & Carplay Bridge.apk` 就是"安卓伪装 iPhone"的同类产品。建议：

1. 装一台旧安卓机，与 Mac（CarPlay Simulator）同网 + 蓝牙配对
2. 全套抓包（同 §4）
3. 与自己的实现逐帧对比：发现 → 握手 → 密钥协商 → RTP，找差距

## 6. 已知风险（提前说清楚）

| 风险 | 说明 | 对策 |
|---|---|---|
| Simulator 协议行为未知 | iOS 16 连模拟器主要走 Wi-Fi，是否强制经典蓝牙 iAP2 无公开资料 | §4 基线抓取定夺 |
| MFi/FairPlay 身份校验 | Host（Apple 或真车机）可能校验 source 的 MFi 身份、FairPlay 密钥 | 看基线里协商是否可伪造；不行则只能到"握手通过但黑屏" |
| 真车差异 | Simulator 与量产车机协议实现可能有差异 | 最终以真车复测为准，Mac 只算第一道关卡 |

## 相关代码

- `aa-display/src/main/java/io/github/nitsuya/aa/display/carplay/`（协议栈，见 [EXECUTION.md §14](EXECUTION.md)）
- `CarPlayConsts.TAG = "AADisplay_CarPlay"`（logcat 过滤用）
