# CarPlay 协议外部参考

AADisplay 无线 CarPlay Source（`carplay/`）为**自研骨架**；下列仓库仅作联调对照，**禁止把其 binaries / 固件 / PDF 原文拷进本仓库**（版权与角色都不匹配）。读流程、字段名、状态顺序即可。

## [45clouds/WirelessCarPlay](https://github.com/45clouds/WirelessCarPlay)（MIT）

### 它解决什么

树莓派插车机 **USB 有线 CarPlay**，iPhone 经 BT/WiFi 连派：无线会话中继到有线配件。  
角色 = 对 iPhone 的无线中继 + 对 HU 的 **USB 配件（常需 MFi 协处理器）**，不是 Android 假 Source。

### 对 AADisplay 有用的部分

| 材料（上游仓库内） | 用途 |
|--------------------|------|
| `iap2.pdf` | iAP2 成帧 / identification 对照 `protocol/Iap2Framing.kt`、`CarPlayBluetoothLink` |
| `carplay.pdf` | 会话与媒体通道总览；对照 RTSP/SETUP 与后续 RTP 缺口 |
| `Bluetooth Accessories.pdf` | RFCOMM / 配件发现与配对顺序 |
| `Device Authentication.pdf` / `Accessory Authentication.pdf` / `Authentication Coprocessor.pdf` | 理解 HU 为何卡认证；解释纯 App Source 的上限与盒子+MFi 备选 |
| `mDNSResponder/` / Bonjour 相关 | `_carplay-ctrl._tcp.` 等发现形态对照 `CarPlayBonjour` |

### 不要当什么用

- 不要当 AADisplay 的出画实现去 port（平台是 RPi + USB，角色也不是手机 Source）。
- 不要 vendor `binaries/` 或把 PDF 检入本树。
- 不能替代目标 HU 上的真机抓包；认证细节以车机实测为准。

### 和本仓库的映射

```
WirelessCarPlay 文档概念     →  AADisplay 落点
iAP2 RFCOMM                  →  carplay/CarPlayBluetoothLink + protocol/Iap2Framing + Iap2Session
Bonjour / mDNS               →  carplay/CarPlayBonjour
CarPlay 控制 / 媒体概述      →  carplay/CarPlayRtspSession + protocol/RtpH264Packetizer（RTP interleaved）
配件认证 / Coprocessor       →  仅架构警示；本模块无 MFi 芯片路径（FairPlay 仍待目标 HU 联调）
```

### Source 侧 iAP2 字节序（对 diplay / 真车机）

AADisplay = **Source（device）**；DiPlay / xcertplay = **Receiver（accessory）**。

RFCOMM UUID（易混）：

| 末字节 | UUID | 谁广播 | 谁 dial |
|--------|------|--------|---------|
| `…cafe` | phone | AADisplay listen | HU 回连 |
| `…caff` | accessory | HU listen | AADisplay 首连 |

RFCOMM 上自研状态机：

1. **Detect** — `FF 55 02 00 EE 10`（仅此 6 字节；之后不再用 `FF 55` 包）
2. **Link Sync** — `FF 5A` 帧，控制位 SYN(`0x80`)/ACK(`0x40`)，协商 MTU / session（control + EA）
3. **Identification** — 发 `StartIdentification`(`0x1D00`)；收配件 `IdentificationInformation`(`0x1D01`)；回 `IdentificationAccepted`(`0x1D02`)
4. **Auth（Source 主动）** — `0xAA00` → 收 `0xAA01` → `0xAA02`+challenge → 收 `0xAA03` → `0xAA05`（本模块不验签，无 MFi 芯片）
5. **Wireless handoff** — `0x4E0E` / `0x4E0D` / `0x5702` / `0x4300`；收 `0x5703`（SSID/口令）与/或 `0x4301`（`CarPlayStartSession`：端口、device id、可选 IP）

`0x4301` 之后才期望媒体 **TCP**（本机 RTSP listen 或按 StartSession IP:port dial）。StartSession dial → `GET /info`/`OPTIONS` → Pair-Setup → 加密控制帧 SETUP/RECORD → screen `dataPort`（HKP DataStream ChaCha + AVC）。legacy FairPlay AES/`fp-setup` 仍待真机对照。

Debug 下可选 `CarPlayLocalLoopback`（本机对 RTSP 发 SETUP/RECORD），用于验证 RTP 写出路径。

运行时链路见 [EXECUTION.md §14](EXECUTION.md)。

## 其它（刻意不纳入代码）

- **CatPlay**（[catplay-labs/catplay](https://github.com/catplay-labs/catplay)）：开源协议栈，但 GitHub `license` 未标明；未明确许可前 **禁止 vendor**。角色偏 dongle + MFi，与 1A 手机直连不同。
- **CarWith Bridge 类 APK**：包名伪装 CarLife + 自研 Source；只作行为对照，不拷贝其代码。
