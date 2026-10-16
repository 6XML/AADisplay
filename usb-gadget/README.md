# usb-gadget — aad-usb（假 iPhone USB 守护）

AADisplay 的独立 C 工程（**不在 Gradle 图里**，见 [docs/USB_CARPLAY.md](../docs/USB_CARPLAY.md)）。
root 守护进程经 **configfs/FunctionFS** 把手机 UDC 变成一台「iPhone」
（Apple VID `0x05AC`、6 配置 usbmux+NCM 复合设备），在**设备侧**实现
**usbmux（v1/v2）+ lockdownd**，并把宿主拨入的 carkit 服务流桥接到
AADisplay App（`127.0.0.1:48350` 行协议）。目的：白牌安卓车机（zLink 类，
固件内置 CarPlay 宿主）识别它为 CarPlay 源。

> 协议 ground truth：DiPlay（宿主侧镜像）+ usbmuxd/libimobiledevice（GPL 主机侧）
> + 内核 `f_fs.c`/`f_ncm.c`，克隆在 `.tmp/research/`（仓库外）。

## 文件

| 文件 | 职责 |
|------|------|
| `src/aad.h` | 各层共享 API 契约（plist 节点、stage 枚举、mux/lockdown/bridge 接口） |
| `src/util.c` | 日志 / sysfs / 目录 / UDC 探测（`aad_probe_udc`、`aad_udc_driver`、`aad_cable_attached`） |
| `src/plist.c` | 极简 Apple XML plist 渲染/解析（dict/array/string/int/bool/data/real；base64；PEM→DER） |
| `src/identity.c` | 预生成证书/公钥 PEM（20 年有效期；**私钥不入库**，在 `.tmp/identity/`） |
| `src/configfs.c` | configfs 复合设备：6 配置布局（initial 4 / CarPlay 6）、UDC 接管/恢复 |
| `src/ffs.c` | FunctionFS ep0：vendor `0x45` GET_MODE / `0x52` SET_MODE（IN 方向 ack+重枚举）/ `0x53` AOA / 零数据兜底 |
| `src/usbmux.c` | usbmux 设备侧：VERSION 握手（恒 v1 帧应答）、伪 TCP（SYN/ACK/RST）、v2 流、bulk 收发 |
| `src/lockdown.c` | lockdownd 设备侧：GetValue/SetValue/Pair（自动信任）/ValidatePair/StartSession/StartService… |
| `src/bridge.c` | 127.0.0.1:48350 控制套接字（ARM/DISARM/STATE/CARKIT 行协议）+ carkit 流泵 |
| `src/main.c` | 状态机：IDLE→ARMED→(cable)→TAKING→PRESENT→USBMUX→LOCKDOWN→CARKIT；vendor 快照/恢复 |
| `test/test_plist.c` | host 单测：b64/PEM/plist 往返/渲染格式（`make test`） |
| `Makefile` | host（macOS/Linux）+ arm64 Android（NDK 自动探测）+ Magisk 模块 zip |
| `magisk/` | Magisk 模块模板（`module.prop` / `service.sh` / `post-fs-data.sh`） |
| `recon.sh` | M0 内核侦察（在**手机**上以 root 跑，输出贴回 docs） |

## 构建

```bash
make host      # host 二进制（开发/语法验证）
make test      # host 单测（plist/base64/PEM/渲染格式）
make android   # arm64-v8a 二进制（需 NDK；自动探测 SDK/gradle 缓存，缺则提示安装）
make module    # Magisk 模块 zip → build/aadusb-<ver>.zip
```

NDK 探测顺序：`$NDK_DIR` → `$NDK_HOME` → `<sdk>/ndk/*` → gradle transforms 缓存。
未找到时 `make android` 打印安装指引（装到标准 SDK 位置，勿另起隔离缓存）。

## 安装与使用（手机）

1. 装 Magisk 模块（`build/aadusb-*.zip`）→ 重启。守护开机自启，**IDLE 不动 vendor USB**。
2. 手机端连 `127.0.0.1:48350` 发 `ARM`（AADisplay App 的 CarPlay 控制 UI，M2）。
3. 插 USB 线到车机 → 守护接管 UDC，建 6 配置假 iPhone，车机枚举并（预期）识别 CarPlay 源。
4. App 收 `CARKIT <port>` 推送后连该端口，走 iAP2（wired：跳过 FF55 直接 0x1D00）。
5. 拔线 / `DISARM` / 守护退出 → 恢复 vendor USB（`setprop sys.usb.config <快照>` + 平台驱动 bind 兜底）。

## 安全边界

- **只**在 `ARM` 且线缆在位时接管 UDC；任何时刻可 `DISARM` 恢复。
- 不动 `sys.usb.*` 以外的任何系统状态；pair 记录仅写 `/data/adb/aadusb/pair/`。
- 证书为**自签 CarPlay 设备身份**（自动信任流程用），不代表任何真实 Apple 设备；私钥不入库。
- App 进程不申请 su（Magisk 模块以 root 跑守护，App 只走本地 TCP）。

## 当前状态（v0.1.0）

- ✅ host 构建 + 单测通过（plist 渲染格式与 libimobiledevice 对齐；usbmux v1/v2 帧、
  0x45/0x52 方向、lockdown 帧格式均对照一手源码验证）
- ⏳ arm64 构建需 NDK（本机未装）
- ⏳ M0：手机内核 `CONFIG_USB_CONFIGFS/F_FS/F_NCM` 侦察（`recon.sh`）——生死门
- ⏳ M3：上车侦察（酷威报告 §6.1 检测门表）
