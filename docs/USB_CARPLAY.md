# USB CarPlay（fake iPhone）— 架构与全局链路

> 目标：Android 手机（root/LSPosed）**数据线直连**车机，让车机把手机识别为 CarPlay 源，而非 Android Auto。
> 目标车机：东风小康商用 van/pickup 原厂固件（研究澄清为**深圳白牌安卓 HU** + zLink 类固件内置 CarPlay 宿主；
> 「酷威」是 Google AA UI 代号而非车机品牌——白牌 HU 不 role-switch，手机保持 device 模式成立）。
> 协议层依据见 `.tmp/usb-carplay-research.md`（376 行调研：MFi 规范 / usbmuxd / libimobiledevice / 内核 / 先例）；
> 车机侧情报见 `.tmp/coolwalk-hu-research.md`（已完成：11 检测门 / 离线 vs 上车 / Tier 0–5 侦察套件）。运行时总链路另见 [EXECUTION.md](EXECUTION.md)。

---

## 1. 背景与判定依据

现状（用户真机）：手机插车机 → 手机呈现 stock 复合（MTP+RNDIS 等，手机自家 VID）→
车机对**任意** VID 先试 AOA（AAP HUIG 强制）→ 判定 Android Auto；iPhone 才走 CarPlay。

车机如何判 CarPlay（`.tmp/usb-carplay-research.md` Q1/Q7，一手来源交叉验证）：

| 门 | 内容 | fake iPhone 对策 |
|----|------|------------------|
| VID 门 | Apple `0x05AC` + iPhone PID 模式 `0x12xx` | 描述符直接报 0x05AC（SAIC 等车机对 Apple VID **跳过** AA 路径） |
| 配置门 | CarPlay 配置 = `usbmux(FF/FE/02)` + `CDC-NCM(02/0D)`(+`Apple Ethernet FF/FD/01`) | configfs 按接口组合构建 |
| 模式门 | vendor `0x45` 查模式（IN，4 字节应答）/ `0x52` 切 CarPlay 配置（**IN 方向**：收 1 字节模式值→回 0x00 ack 或 STALL，随后重枚举；DiPlay `IphoneUsbHost` 实证） | ffs ep0（`FUNCTIONFS_ALL_CTRL_RECIP`）应答 |
| MFi 门 | iAP2 配件认证：**验证者是 iPhone 侧** | fake iPhone 直接回 `AuthenticationSucceeded`（与无线版"不验签"同策略） |
| AOA 门 | AAP 对任意 VID 试 0x53 字符串 | ffs ep0 对 0x53 回**通用零数据应答**（真 iPhone 公开行为未知，上车侦察可调项）→ 车机放弃 AOA → 转 CarPlay 分支 |

结论：**协议层零未知**（每层都有权威来源，宿主/设备端互为镜像）；**真正生死项在安卓手机内核**
（`CONFIG_USB_CONFIGFS/_F_NCM/_F_FS` 是否 =y/m、UDC 是否被厂商 HAL 抢占）。
先例：OkcarOS（1039★，刷 ROM 路线，真机全链路可通，组织已删）；**root 应用形态是公开空白**。

## 2. 总体架构（三层进程）

```
车机（原厂 CarPlay host，固件）
   │  USB：05AC:12xx，CarPlay 配置 = usbmux(FFS) + NCM(内核) + AppleEthernet(FFS stub)
   │  媒体：NCM 链路本地 IPv6 上 TCP/UDP（0x4301 通告地址）
┌──┴────────────────────────────────────────────────────────────────┐
│ 手机                                                              │
│                                                                   │
│ ① aad-usb — root 守护（C，Magisk service，非 app 进程）            │
│    configfs 复合设备 + UDC 接管/恢复（watchdog 幂等）              │
│    ep0：0x45 回模式 / 0x52 收模式→ack+重枚举 / 0x53 零数据应答    │
│    usbmux v1/v2 设备端（伪 TCP，端口登记：62078 lockdown / 动态）   │
│    lockdown 设备端（身份表 + 自动信任 Pair + TLS* + StartService） │
│    carkit 服务流 ⇄ 本地 TCP(127.0.0.1:48350) 桥给 app             │
│    线缆/UDC 状态监视 + 失败回滚（恢复 vendor 原配置）               │
│                                                                   │
│ ② AADisplay app（现有 JVM 进程）                                   │
│    CarPlayChannel 设置（AA / CarPlay × 无线 / USB）               │
│    iAP2 设备端（复用 carplay/protocol/*；wired 模式跳过 BT 前言）   │
│    CarPlayUsbHandoff（bind usb0 + 拨 0x4301 地址:airPlayPort）     │
│    媒体/触控/NowPlaying/方向盘（复用现有 source 栈，socket 换 NCM）  │
│                                                                   │
│ ③ system_server（xposed/carplay/*，现状不变）                      │
│    CarPlayAudioCapture / CarPlayProcessBoost（CP-live 门控）       │
└───────────────────────────────────────────────────────────────────┘
```

\* lockdown TLS 为 v2（OpenSSL 交叉编译）；v1 先走 `EnableSessionSSL:false` 老路径
（libimobiledevice 对 false 即明文继续，协议合法分支），缩小 M1 面。

### 与仓库规则的符合性

| 规则（AGENTS.md / EXECUTION.md） | 本方案 |
|---|---|
| §5 全局链路评估先行 | 本文档 |
| 15. App 进程不申请 Magisk su | **遵守**：aad-usb 是 Magisk 模块的独立 root service，app 只连 `127.0.0.1` socket，不 exec su |
| 17. CarPlay 协议不进 system_server | **遵守**：usbmux/lockdown/iAP2 均在 daemon/app（协议载体），system_server 仍只有音频采集/进程提权两个"系统能力" |
| 最小改动 / 新钩子命名 | 无新 Xposed 钩子；app 侧新增文件均在 `carplay/` 包；C 项目放新顶层目录 `usb-gadget/`（不进 `settings.gradle.kts`，独立 CMake+NDK 构建） |
| 禁止改名冻结契约（AADD/ICoreManager/LastSplitStore/ClusterLyricStore key） | 不触碰；新增持久化 key 见 §5 |
| 许可证 GPLv3 | C 守护按 GPLv3 著录（新代码，无第三方二进制） |

## 3. 全局链路（冷启动：设置 CarPlay/USB → 插线 → 出画）

```
 用户                 app                    aad-usb(root)              车机
  │ 出口通道=CarPlay     │                        │                        │
  │ USB 方式            │──ARM(USB)─────────────▶│ 置 ARMED               │
  │                     │◀─STATE=ARMED───────────│                        │
  │ 插数据线            │                        │                        │
  │                     │                        │ ① UDC online 事件       │
  │                     │                        │ ② 抢 UDC（unvendor）    │
  │                     │                        │ ③ 绑 configfs          │
  │                     │                        │    [默认配置:usbmux]    │──枚举──▶ 05AC:12xx
  │                     │                        │◀──0x45(模式)──────────  │
  │                     │                        │───3:3:3:0 / 5:3:3:0───▶ │
  │                     │                        │◀──0x52(切 CarPlay)────  │
  │                     │                        │ ④ 回 0 + 重枚举         │
  │                     │                        │    [CarPlay 配置:       │──再枚举▶ usbmux+NCM+AE
  │                     │                        │     usbmux+NCM+AE]      │
  │                     │                        │        NCM→usb0(内核)   │
  │                     │                        │◀──usbmux CONNECT 62078  │
  │                     │                        │ ⑤ lockdown 身份/Pair    │
  │                     │                        │   (自动信任) StartSession│
  │                     │                        │◀──StartService carkit── │
  │                     │◀─CARKIT=sock:port───── │ ⑥ 桥 carkit 字节流      │
  │                     │──iAP2 0x1D00──────────▶│                        │
  │                     │◀─0x1D01(Vehicle+USBHost)│                       │
  │                     │──0x1D02 Accepted ─────▶│                        │
  │                     │──0xAA00 auth 请求 ────▶│（车机 MFi 证书/签名）    │
  │                     │──0xAA05 Succeeded（不验）▶                       │
  │                     │──0x4300 StartSession ─▶│                        │
  │                     │◀─0x4301(ipv6Addrs,airPlayPort,Ed25519)─         │
  │ ⑦ bind usb0，拨 0x4301 地址:port → AirPlay pair-setup(SRP6a/ChaCha)    │
  │ ⑧ RTSP SETUP/RECORD → H.264 + LPCM + 触控回传 + NowPlaying             │
  │◀────────────── 车机出画（1920×720 24:9 / 60fps ≤25Mbps 有线）──────────│
 拔线/切回 AA / 会话停 → ⑨ daemon 恢复 vendor UDC 原配置（充电/MTP 正常）
```

要点：

- **USB 身份即模式开关**：AA 模式下 daemon 不接管 UDC（vendor 原行为，AA 照旧）；
  CarPlay 模式 + 插线才切 fake 配置。两种车机路径天然互斥（一时刻一身份）。
- **竞争处理**：插线后 vendor HAL 与 daemon 抢 UDC。daemon 以 UDC sysfs `online`/
  netlink 监听抢跑；抢输则可强制 unbind vendor 驱动（root 可），效果=重枚举。
  v1 容忍"短暂 MTP → 05AC"的二次枚举（车机检测到新设备重走判定）；v2 做毫秒级抢跑。
- **恢复保证**：daemon 启动即快照 vendor 原 UDC/配置；任意失败/停止/超时/看门狗
  → 解绑自己 + 重绑 vendor。手机 USB 功能不可被本特性永久破坏（硬约束）。

## 4. 进程边界与 IPC

| 边界 | 协议 | 方向 |
|------|------|------|
| app ⇄ aad-usb | TCP `127.0.0.1:48350`，4B 大端长度 + 行式文本帧（`ARM`/`DISARM`/`GET CARKIT`/`STATE=…`） | app 主 |
| aad-usb ⇄ app（carkit 桥） | daemon 应答 `CARKIT=port` 后监听该端口；app 连上后**裸字节流**（iAP2 TLV8）双向 | 双向 |
| app ⇄ system_server | 现有 `ICoreManager`（reportCarPlaySessionLive 等，**不改契约**） | app 主 |
| daemon ⇄ 内核 | configfs/UDC/`/dev/usb-ffs/*`/usb0（root sysfs，不经 IPC） | — |

- daemon 崩溃/失联 → app 侧 `CarPlayStatusUi` 显示"USB 守护未连接"，媒体会话随之失效，
  车机侧自然掉线；app **不**尝试以自身身份代答 USB（app 无 root）。
- daemon 日志 → `STATE=` 镜像 + logcat tag `AAD_usb`（沿用仓库日志规范）。

## 5. 设置与持久化契约（新增）

| 项 | 载体 | key/路径 |
|----|------|----------|
| 出口通道×方式 | app `filesDir`（沿用 CarPlayChannel 文件型持久化风格） | `aadisplay_carplay_channel` 值扩为 `aa`/`cp`/`cp-usb` |
| daemon 控制 | root 可读控制文件 | `/data/adb/aadusb/state`（daemon 自持；app 经 socket 间接操作，不直接写） |
| lockdown 配对记录 | root 存储 | `/data/adb/aadusb/pair/*.plist`（每车机 HostID 一条；**新命名域**，不碰 LastSplitStore/ClusterLyricStore） |
| 设备身份（UDID/DevicePublicKey/证书） | v1 嵌入 `identity.c`（静态 20 年证书，自动信任流程；私钥不入库）；v2 首跑生成落盘 | v2 时落 `/data/adb/aadusb/identity/`（RSA-2048 PEM + 固定 "iPhone" 身份表） |

`CarPlayChannel` 扩展语义：`cp-usb` 时 **不**启动 BT/无线发现栈（CarPlayBluetoothLink/Bonjour 保持
AA 行为不变），改由 `CarPlayUsbLink`（app 新增）管 daemon 与 0x4301 后的媒体链路。

## 6. 复用与新增代码清单

**复用（零/小改）**：
- `carplay/protocol/{Iap2Framing,Iap2Csm,Iap2Session,Tlv8,BinaryPlist,AirPlayPairing,AirPlayPairVerify,Srp6aSha512,HkdfSha512,ControlChannelCrypto,RtspMessage,RtpH264Packetizer,CarPlayEventChannel,ScreenStreamEgress}.kt` — source 方向协议栈（换传输对象即可）
- `carplay/{CarPlayRtspSession,CarPlayVideoEncoder,CarPlayGlCompositor,CarPlaySourceSession,CarPlayAudioEgress,CarPlayInputBridge,CarPlayNowPlaying,CarPlayMediaClock,CarPlayHostRuntime,CarPlayProjectionService}.kt` — 媒体/编解码/会话编排
- `xposed/carplay/{CarPlayAudioCapture,CarPlayProcessBoost}.kt` — 系统能力（CP-live 门控不变）

**新增**：
| 位置 | 内容 |
|------|------|
| `usb-gadget/src/`（C） | `main.c`（守护/状态机/vendor 快照恢复）、`ffs.c`（ep0：0x45/0x52/0x53）、`usbmux.c`（v1/v2 伪 TCP）、`lockdown.c`（plist 收发/自动信任 Pair）、`bridge.c`（carkit→本地 socket）、`identity.c`（静态身份 PEM，自动信任）、`configfs.c`（6 配置布局/UDC 接管）、`plist.c`（极简 Apple plist）——已完稿，host 构建+单测通过 |
| `usb-gadget/{Makefile,recon.sh,magisk/}` | host/NDK 双目标构建、Magisk 模块模板、手机内核 M0 侦察脚本 |
| `carplay/CarPlayUsbLink.kt` | daemon 客户端 + 连接状态机 + 0x4301 后 `usb0` handoff（镜像 `CarPlayWifiHandoff` 的 ensure 语义） |
| `carplay/protocol/Iap2Session.kt` | 增 `wired` 入口（跳过 detect/link-sync，直入 0x1D00；其余状态不变） |
| `CarPlayStatusUi` | USB 段状态行（枚举/usbmux/lockdown/iAP2/媒体）+ daemon 连接指示 |

## 7. 里程碑

| 阶段 | 内容 | 验收 |
|------|------|------|
| M0 侦察 | 手机内核三组 config + UDC/vendor HAL 侦察（§8 清单，`usb-gadget/recon.sh` 已就绪待用户跑）；酷威车机情报（`.tmp/coolwalk-hu-research.md` **已完成**） | 路线 A 生死判定；车机门清单（11 门已列出） |
| M1 守护原型 | aad-usb：configfs 双布局（initial 4 配置 / CarPlay 6 配置）+ 0x45/0x52/0x53 + usbmux v1/v2 + lockdown（EnableSessionSSL:false）+ carkit 桥；Magisk 模块形态 | **代码已完稿**（9 C 文件 + Makefile + 模块模板；host 构建 + plist/PEM 单测通过）；余：arm64 NDK 构建 + 手机↔**PC 宿主**（usbmuxd/libimobiledevice）验证 枚举→模式→lockdown→carkit，`ideviceinfo` 认出 iPhone |
| M2 全链路 | app `CarPlayUsbLink` + iAP2 wired + usb0 handoff + 媒体复用栈 | 手机 ↔ **LIVI（PC/Mac 宿主）** 出 H.264 + 音频 + 触控往返（离线可迭代，不依赖真车） |
| M3 实弹 | 酷威车机：按 Q7 门逐条撞（0x45/0x52 序列、MFi 分支、NCM 驱动、配置号） | 插线 3s 内建会话（MFi §23.2.4 时限）、出画、拔线 1s 拆除、恢复 vendor 配置 |
| M4 打磨 | 开机自动 ARM、抢跑竞争、TLS(lockdown)、Magisk 模块分发、状态页 UX、CHANGELOG | 用户验收 |

## 8. 需要真机/真车提供的信息（用户侧）

**手机**（定 M1 生死）：
1. 精确型号 + SoC + Android 版本 + root 方式（Magisk? KernelSU? APatch?）
2. adb 跑：
   - `zcat /proc/kconfig | grep -E 'CONFIG_USB_(CONFIGFS|F_NCM|F_FS)'`（或 `/proc/config.gz`）
   - `ls /config/usb_gadget/`、`ls /sys/class/udc/`
   - `getprop | grep -i usb`（尤其 `vendor.usb.use_gadget_hal`、`sys.usb.config`）
   - `ls /vendor/lib/modules`（configfs/NCM/FFS 是否模块）
3. `setprop sys.usb.config ncm` 后是否起 `usbX` 网卡（验证厂商 NCM 链路）
4. 插普通安卓设备时是 MTP 还是仅充电（USB 口行为）

**车机**（白牌安卓 HU + zLink 类内置 CarPlay 宿主；若 HU 可装 APK，架构 B「HU 装 DiPlay」是可行诊断/兜底路线，主路线仍 A）（定 M3 门）：
5. 是否有 adb/开发者 shell（装探针/抓包用）
6. 真 iPhone 插上的实际行为（CarPlay 版本、有无 MFi 弹窗、建会话时长）
7. 有条件时：真 iPhone USB 抓包（USB 协议分析仪 / PC 中转）——枚举描述符、0x45/0x52 交换、选中配置号、MFi 分支

## 9. 风险与边界

| 风险 | 缓解 |
|------|------|
| 手机内核缺 configfs/NCM/FFS（=m 且无模块文件 / 完全缺） | M0 即判定；缺 → 该机只能无线，或 OkcarOS 式刷 ROM（不在本模块范围） |
| 厂商 USB HAL 抢占 UDC | daemon 看门狗幂等重绑 + 恢复快照（§3 硬约束） |
| 原厂车机隐藏门（lockdown 字段/版本/探测顺序） | M2 先对开源宿主（LIVI）迭代 90%，M3 真车撞门；车机诊断日志回传 |
| 伪装 Apple 身份的灰色地带 | 个人实验/自用定位；**不做产品化分发**（README 注明）；GPLv3 著录 |
| 供电/热（100Mbps NCM + 编码） | 有线 NCM 带宽富余（25Mbps 上限）；沿用 CP 存活期 CarPlayProcessBoost 提权；真机热观察 |
| 掉线把手机 USB 留在 fake 态 | 恢复保证（§3）：任何终止路径先恢复 vendor 配置 |

## 10. 参考

- `.tmp/usb-carplay-research.md` — 协议全层 + 安卓 USB gadget 可行性（MFi/usbmuxd/libimobiledevice/内核/先例）
- `.tmp/coolwalk-hu-research.md` — 酷威车机 CarPlay 情报（**已完成**，402 行：§6.1 检测门表 11 门 / §6.2 离线 vs 上车 / §6.3 Tier 0–5 侦察套件；M3 依据）
- `usb-gadget/README.md` — aad-usb 构建/安装/使用（独立 C 工程）
- `.tmp/research/` — 调研克隆的一手源码（WirelessCarPlay PDF 文本、usbmuxd、libimobiledevice、内核 f_fs/f_ncm）
- [CARPLAY_REFS.md](CARPLAY_REFS.md) — 无线 CarPlay 协议对照（iAP2/BT/认证 PDF 来源）
- [EXECUTION.md §14](EXECUTION.md) — 无线 CarPlay 运行时链路（本特性的复用基座）
