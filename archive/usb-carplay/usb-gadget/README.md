# usb-gadget — aad-usb（假 iPhone USB 守护）

AADisplay 的独立 C 工程（**不在 Gradle 图里**；归档于 `archive/usb-carplay/`，见 [USB_CARPLAY.md](../USB_CARPLAY.md)）。主线默认关闭有线通道。
root 守护进程经 **configfs/FunctionFS** 把手机 UDC 变成一台「iPhone」
（Apple VID `0x05AC`、usbmux+NCM 复合设备），在**设备侧**实现
**usbmux（v1/v2）+ lockdownd**，并把宿主拨入的 carkit 服务流桥接到
AADisplay App（`127.0.0.1:48350` 行协议）。目的：白牌安卓车机（zLink 类，
固件内置 CarPlay 宿主）识别它为 CarPlay 源。

> 协议 ground truth：DiPlay（宿主侧镜像）+ usbmuxd/libimobiledevice（GPL 主机侧）
> + 内核 `f_fs.c`/`f_ncm.c`，克隆在 `.tmp/research/`（仓库外）。

## 文件

| 文件 | 职责 |
|------|------|
| `src/aad.h` | 各层共享 API 契约（plist 节点、stage 枚举、mux/lockdown/bridge 接口） |
| `src/util.c` | 日志 / sysfs / 目录 / UDC 探测（跳过 `dummy_udc`） |
| `src/plist.c` | 极简 Apple XML plist |
| `src/identity.c` | 预生成证书/公钥 PEM（私钥不入库） |
| `src/configfs.c` | configfs 复合：CarPlay 布局默认 **usbmux+NCM**；FFS ep0/desc/strings |
| `src/ffs.c` | FunctionFS ep0：`0x45` / `0x52` / `0x53` |
| `src/usbmux.c` | usbmux 设备侧 |
| `src/lockdown.c` | lockdownd 设备侧（EnableSessionSSL:false） |
| `src/bridge.c` | `127.0.0.1:48350` 行协议 + carkit 流泵（推送 `CARKIT <port>`） |
| `src/main.c` | 状态机；vendor 快照/恢复；create 失败停在 ERROR |
| `test/test_plist.c` | host 单测 |
| `Makefile` | host / android / module |
| `magisk/` | Magisk 模块（二进制 → `/data/adb/aadusb/aad-usb`） |
| `recon.sh` | M0 内核侦察 |
| `scripts/m1_arm_test.sh` | 手机侧 ARM 20s 后 DISARM |

## 构建

```bash
make host      # host 二进制
make test      # plist 单测
make android   # arm64-v8a（需 NDK，装在 `$ANDROID_HOME/ndk/`）
make module    # Magisk zip → build/aadusb-<ver>.zip
```

## 安装与使用（手机）

1. `adb push build/aad-usb-arm64 /data/local/tmp/aad-usb` 或闪 Magisk zip → 重启。
2. 守护开机 IDLE（不碰 vendor USB）。App 选 **CarPlay 有线** 发 `ARM`，或：
   `printf 'ARM\n' | toybox nc 127.0.0.1 48350`
3. 插线到 PC/车机 → 预期枚举 `05AC:12a8` → usbmux → lockdown → `CARKIT <port>`。
4. `DISARM` / 拔线 → 恢复 `sys.usb.config` 快照。

## 当前状态（v0.1.0 · SM-F721B）

- ✅ host 构建 + 单测；arm64 NDK 构建 + Magisk 模块路径
- ✅ M0：Flip4 `CONFIG_USB_CONFIGFS/F_FS/F_NCM=y`，ffs/ncm mkdir OK
- ✅ 真机：独立 `aadusb` 可绑 UDC（`STATE present` / `addressed`）
- ✅ **枚举根因**：独立 gadget 到不了 `configured`；**劫持 stock `g1`**（`sys.usb.config=none` → 换函数/VID → 再绑）可达 `configured`
- ✅ 守护已走 g1 劫持；Mac `0x05AC:0x12A8` + usbmux v2 + `idevice_id`
- ✅ lockdown：`ideviceinfo -s` PASS（清 conn `closed`；Apple plist）
- ✅ NCM：`f2=ncm.0`；`usb0` MAC pin；Mac `en12`；**L3**：绕过 `unreachable` rule 后 IPv6 TCP / IPv4 ping PASS
- ✅ carkit：`StartService com.apple.carkit.service` → publish → `stage: carkit`（TLS 后）
- ✅ lockdown TLS：系统 libssl；PairRecord 设备 Root CA；`accept ok`
- ⏳ CarPlay Simulator 仍卡 MobileDevice Pair（已对齐 PKCS#1 DevicePublicKey + PairRecord `WiFiMACAddress`）；M2 app iAP2→AirPlay 出画；M3 真车

**联调注意**：ARM 会拆掉 USB adb——先开**无线调试**。若 Mac 与 adb 皆空（USB limbo），重启手机或跑 `scripts/recover_usb.sh`。

对照脚本：`scripts/m1_g1_ncm_swap.sh`（g1→configured）、`scripts/m1_hold_present.sh`（旧独立 gadget 路径）。
