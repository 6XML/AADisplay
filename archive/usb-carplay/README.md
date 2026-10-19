# archive/usb-carplay — wired CarPlay (WIP)

Lab / Magisk work for USB fake-iPhone CarPlay. **Not enabled in public builds.**

| Path | Contents |
|------|----------|
| `usb-gadget/` | NDK + Magisk `aadusb` daemon (`make android`) |
| `usb-gadget/scripts/` | Host smoke / bring-up scripts (`host_*`, `m1_*`, `mac_*`) |
| `USB_CARPLAY.md` | Architecture, milestones, control protocol |
| `frida/` | Pair / lockdown lab scripts used with the daemon |

## App gating

Wired egress stays in the main app tree (`CarPlayUsbLink`, `CarPlayUsbHandoff`, `CHANNEL_CARPLAY_USB`) but is off by default:

- `CarPlayChannel.USB_CARPLAY_ENABLED = false`
- Status page has no USB radio; leftover `cp-usb` / channel `2` falls back to Android Auto
- BootArm does not treat USB as an active CarPlay selection while the flag is false

## Resume development

1. Optionally move this tree back to the repo root (`usb-gadget/`, `docs/USB_CARPLAY.md`, `frida/` pair scripts).
2. Set `CarPlayChannel.USB_CARPLAY_ENABLED = true`.
3. Restore the USB radio + daemon line in `activity_main.xml` / `CarPlayStatusUi` (see git history).
4. Install Magisk module from `usb-gadget/` and follow `USB_CARPLAY.md`.
