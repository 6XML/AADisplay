/*
 * main.c — aad-usb daemon (fake iPhone USB gadget) entry point.
 *
 * Runs as a Magisk service (root). State machine:
 *
 *   IDLE ──ARM (app)──▶ ARMED ──cable──▶ TAKING ─▶ PRESENT ─▶ USBMUX
 *    ▲                                        │          │
 *    │ teardown (DISARM / cable out / stop)   │          ▼
 *    └──────── RESTORING ◀────────────────────┘      LOCKDOWN → CARKIT
 *
 * UDC lifecycle: vendor driver is snapshotted at takeover (driver symlink +
 * sys.usb.config) and restored on teardown (setprop first, direct rebind as
 * fallback). The fake composite is a configfs gadget on the same UDC.
 *
 * 0x52 mode switch (vendor request from a host): the ffs layer records it,
 * the main loop rebuilds/re-binds the composite with the requested layout
 * (4-config initial vs 6-config CarPlay) and lets the host re-enumerate.
 */
#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <pthread.h>

#ifdef __ANDROID__
#include <sys/system_properties.h>
#endif

#include "aad.h"

struct daemon {
    char udc[64];
    char vendor_driver[128];      /* basename of /sys/class/udc/<x>/driver */
    char vendor_gadget[64];       /* configfs gadget holding the UDC (AOSP HAL "g1") */
    char vendor_usb_config[128];  /* saved sys.usb.config */
    int vendor_snap_done;
    volatile int armed;
    volatile int stop;
    aad_gadget *g;
    aad_ffs_ctl *ctl;
    aad_bridge *br;
    int stage;
    int carplay_layout;
    int ctl_port;
    unsigned ovr_vid, ovr_pid;    /* 0 = AAD defaults */
    volatile int skip_defend;     /* 1 during intentional UDC reenum pulse */
    volatile int want_reenum;     /* control-socket REENUM request */
    volatile int want_carplay;    /* control-socket CARPLAY → rebuild usbmux+NCM */
};

static struct daemon D = {0};
static volatile sig_atomic_t g_signal = 0;

static void on_signal(int s)
{
    (void)s;
    g_signal = 1;
}

/* ---------------- vendor USB snapshot / restore ---------------- */

static void snapshot_vendor(void)
{
    if (D.vendor_snap_done) return;
    aad_udc_driver(D.udc, D.vendor_driver, sizeof(D.vendor_driver));
    aad_udc_configfs_holder(D.udc, D.vendor_gadget, sizeof(D.vendor_gadget));
#ifdef __ANDROID__
    __system_property_get("sys.usb.config", D.vendor_usb_config);
#endif
    D.vendor_snap_done = 1;
    LOGI("vendor snapshot: driver='%s' configfs_gadget='%s' usb.config='%s'",
         D.vendor_driver, D.vendor_gadget, D.vendor_usb_config);
}

#ifdef __ANDROID__
/* ctl.stop / ctl.start — init property interface (same as `stop`/`start`). */
static void init_svc(const char *action, const char *name)
{
    char prop[64];
    snprintf(prop, sizeof(prop), "ctl.%s", action);
    __system_property_set(prop, name);
}

/* sys.usb.config=none tears down USB *and* stops adbd (USB + TCP).
 * Bring adbd back on TCP so wifi debugging survives fake-iPhone takeover. */
static void ensure_adbd_tcp(void)
{
    char port[32] = {0};
    __system_property_get("service.adb.tcp.port", port);
    if (port[0] == 0 || !strcmp(port, "0") || !strcmp(port, "-1"))
        __system_property_set("service.adb.tcp.port", "5555");
    init_svc("stop", "adbd");
    usleep(200 * 1000);
    init_svc("start", "adbd");
    LOGI("adbd restarted for TCP (service.adb.tcp.port + start adbd)");
}
#endif

/* Ask the vendor stack to give up the UDC.
 * - stop USB HAL first (Samsung coral HAL reclaims g1 within ~1s otherwise)
 * - platform driver bound  -> sysfs unbind
 * - configfs HAL (AOSP usb gadget, e.g. "g1") -> "sys.usb.config none"
 *   then clear the holder's UDC file; re-start adbd on TCP. */
static void release_vendor_udc(void)
{
#ifdef __ANDROID__
    /* Keep HAL from racing us while fake gadget is up. */
    init_svc("stop", "vendor.usb-hal-1-3");
    init_svc("stop", "usbd");
    if (D.vendor_gadget[0])
        __system_property_set("sys.usb.config", "none");
#endif
    if (D.vendor_driver[0]) {
        char path[256];
        snprintf(path, sizeof(path), "/sys/bus/platform/drivers/%s/unbind",
                 D.vendor_driver);
        aad_sysfs_write(path, D.udc);
    }
    if (D.vendor_gadget[0]) {
        char path[256];
        snprintf(path, sizeof(path), "/config/usb_gadget/%s/UDC", D.vendor_gadget);
        for (int attempt = 0; attempt < 5; attempt++) {
            aad_sysfs_write(path, "");
#ifdef __ANDROID__
            /* Keep configfs=1 — our fake gadget IS a configfs gadget.
             * Setting configfs=0 (old Android USB) kills pull-up / enum. */
            __system_property_set("sys.usb.configfs", "1");
            __system_property_set("sys.usb.config", "none");
#endif
            usleep(200 * 1000);
            char cur[64] = {0};
            aad_sysfs_read_str(path, cur, sizeof(cur));
            LOGI("release %s/UDC attempt=%d holder='%s'",
                 D.vendor_gadget, attempt, cur);
            if (cur[0] == 0) break;
        }
    }
#ifdef __ANDROID__
    /* config=none stopped adbd — restore wifi adb before host enum window. */
    ensure_adbd_tcp();
#endif
    /* give the UDC a moment to settle to a free state ("not attached"/
     * "notattached" — spelling varies by kernel; use the whitelist, not an
     * exact match) */
    for (int i = 0; i < 10; i++) {
        if (!aad_cable_attached(D.udc))
            break;
        usleep(200 * 1000);
    }
}

static void restore_vendor(void)
{
    /* 1) re-enable configfs + USB HAL, then ask HAL to reconfigure */
#ifdef __ANDROID__
    __system_property_set("sys.usb.configfs", "1");
    init_svc("start", "vendor.usb-hal-1-3");
    usleep(200 * 1000);
    const char *cfg = D.vendor_usb_config[0] ? D.vendor_usb_config : "mtp,adb";
    __system_property_set("sys.usb.config", cfg);
    /* force a none→cfg edge if HAL ignored a same-value set */
    usleep(100 * 1000);
    __system_property_set("sys.usb.config", "none");
    usleep(100 * 1000);
    __system_property_set("sys.usb.config", cfg);
#endif
    /* 2) wait for the HAL to rebind the UDC */
    for (int i = 0; i < 15; i++) {
        if (aad_cable_attached(D.udc))
            return; /* HAL took it back */
        char holder[64] = {0};
        if (D.vendor_gadget[0]) {
            char path[256];
            snprintf(path, sizeof(path), "/config/usb_gadget/%s/UDC",
                     D.vendor_gadget);
            aad_sysfs_read_str(path, holder, sizeof(holder));
            if (holder[0])
                return;
        }
        usleep(200 * 1000);
    }
    /* 3) direct rebind fallbacks (may ENODEV if cable/UDC unsettled) */
    if (D.vendor_gadget[0]) {
        char path[256];
        snprintf(path, sizeof(path), "/config/usb_gadget/%s/UDC",
                 D.vendor_gadget);
        if (aad_sysfs_write(path, D.udc) == 0)
            return;
    }
    if (D.vendor_driver[0]) {
        char path[256];
        snprintf(path, sizeof(path), "/sys/bus/platform/drivers/%s/bind",
                 D.vendor_driver);
        if (aad_sysfs_write(path, D.udc) == 0)
            return;
    }
    LOGE("vendor restore failed — USB stays unbound until re-plug/reboot");
}

/* ---------------- state push ---------------- */

static void push_stage(int st)
{
    if (st == D.stage) return;
    D.stage = st;
    aad_bridge_push_state(D.br, st, NULL);
    LOGI("stage: %s", aad_stage_name(st));
}

/* ---------------- stack attach / detach ---------------- */

static void on_lockdown(int conn_id, void *user)
{
    (void)user;
    aad_lockdown *ld;
    if (aad_lockdown_start(&ld, conn_id) != 0) {
        LOGE("lockdown start failed on conn %d", conn_id);
        aad_mux_conn_close(conn_id, "lockdown start failed");
    }
}

static void on_bridge(int pair_fd, void *user)
{
    aad_bridge_publish_carkit((aad_bridge *)user, pair_fd);
}

/* attach ffs ctl + mux onto an already-created (bound or unbound) gadget */
static int attach_stack(int carplay)
{
    if (aad_ffs_ctl_start(D.g, aad_gadget_ep0_usbmux(D.g), NULL, NULL, &D.ctl) != 0)
        return -1;
    aad_ffs_ctl_set_mode(D.ctl, carplay ? MODE_CARPLAY : MODE_INITIAL);
    if (aad_mux_start(aad_gadget_ep_usb_out(D.g), aad_gadget_ep_usb_in(D.g),
                      on_lockdown, on_bridge, D.br) != 0)
        return -1;
    return 0;
}

/* detach mux + ffs (gadget must be unbound first so readers wake) */
static void detach_stack(void)
{
    if (D.ctl) { aad_ffs_ctl_stop(D.ctl); D.ctl = NULL; }
    aad_mux_stop();
    aad_lockdown_stop_active();
}

/* ---------------- takeover / teardown ---------------- */

static void takeover_fake(int carplay)
{
    push_stage(STG_TAKING);
    snapshot_vendor();
    release_vendor_udc();

    aad_gadget *g;
    if (aad_gadget_create(D.udc, carplay, &g) != 0) {
        LOGE("fake gadget create failed — restoring vendor USB");
        restore_vendor();
        push_stage(STG_ERROR);
        return;
    }
    D.g = g;
    D.carplay_layout = carplay;
    /* ep0 readers MUST be live before UDC bind — host starts SET_ADDRESS
     * immediately on pull-up; vendor 0x45 and ffs ENABLE need a waiter. */
    if (attach_stack(carplay) != 0) {
        LOGE("stack attach failed — restoring vendor USB");
        aad_gadget_destroy(g);
        D.g = NULL;
        restore_vendor();
        push_stage(STG_ERROR);
        return;
    }
    if (aad_gadget_bind(g) != 0) {
        LOGE("UDC bind failed — restoring vendor USB");
        detach_stack();
        aad_gadget_destroy(g);
        D.g = NULL;
        restore_vendor();
        push_stage(STG_ERROR);
        return;
    }
    /* Force a bus detach/attach so macOS tears down the previous Samsung
     * enumeration and re-reads our Apple descriptors from scratch. */
    D.skip_defend = 1;
    if (aad_gadget_reenum_pulse(g) != 0) {
        D.skip_defend = 0;
        LOGE("reenum pulse failed — restoring vendor USB");
        detach_stack();
        aad_gadget_destroy(g);
        D.g = NULL;
        restore_vendor();
        push_stage(STG_ERROR);
        return;
    }
    D.skip_defend = 0;
    LOGI("gadget bound to UDC %s (layout=%s enum_safe=%d)", D.udc,
         carplay ? "carplay" : "initial", AAD_ENUM_SAFE);
    /* NCM data iface (usb0) stays DOWN until userspace ups it — CarPlay
     * handoff and Mac en* both need carrier. Best-effort; ignore misses. */
    aad_bringup_ncm_ifaces();
    push_stage(STG_PRESENT);
}

/* If something steals the UDC while we are present, reclaim it.
 * When hijacking g1, "ours" IS g1 — never clear g1/UDC or set
 * sys.usb.config=none (that tore down the Apple identity mid-hold). */
static void defend_udc(void)
{
    if (!D.g || D.skip_defend) return;
    char path[256], holder[64] = {0};
    snprintf(path, sizeof(path), "%s/UDC", aad_gadget_base(D.g));
    aad_sysfs_read_str(path, holder, sizeof(holder));
    if (holder[0] != 0 && !strcmp(holder, D.udc))
        return; /* still ours */
    LOGI("UDC not on %s (holder='%s') — reclaiming",
         aad_gadget_base(D.g), holder);
#ifdef __ANDROID__
    init_svc("stop", "vendor.usb-hal-1-3");
#endif
    usleep(100 * 1000);
    if (aad_gadget_ensure_bound(D.g) == 0)
        LOGI("UDC reclaimed on %s", aad_gadget_base(D.g));
    else
        LOGE("UDC reclaim bind failed on %s", aad_gadget_base(D.g));
}

static void teardown_fake(void)
{
    push_stage(STG_RESTORING);
    /* unbind FIRST: wakes every ffs ep / mux ep reader in the kernel */
    if (D.g)
        aad_gadget_unbind(D.g);
    detach_stack();
    if (D.g) {
        aad_gadget_destroy(D.g);
        D.g = NULL;
    }
    D.carplay_layout = 0;
    restore_vendor();
    push_stage(D.armed ? STG_ARMED : STG_IDLE);
}

/* 0x52 mode switch: prefer soft NCM add (keep usbmux FFS); hard rebuild
 * only when dropping NCM or soft promote fails. */
static void rebuild_layout(int carplay)
{
    if (!D.g) return;
    if (carplay == D.carplay_layout) {
        /* layout already correct: force re-enumeration so the host
         * re-runs its config selection */
        aad_gadget_unbind(D.g);
        aad_gadget_bind(D.g);
        LOGI("re-enum requested (layout unchanged: %s)",
             carplay ? "carplay" : "initial");
        return;
    }
    if (carplay && !D.carplay_layout) {
        LOGI("layout switch initial -> carplay (soft NCM promote)");
        D.skip_defend = 1;
        if (aad_gadget_promote_ncm(D.g) == 0) {
            D.carplay_layout = 1;
            if (D.ctl)
                aad_ffs_ctl_set_mode(D.ctl, MODE_CARPLAY);
            /* Host must re-select config; brief detach so macOS sees NCM. */
            if (aad_gadget_reenum_pulse(D.g) != 0)
                LOGE("soft promote reenum pulse failed");
            aad_bringup_ncm_ifaces();
            push_stage(STG_PRESENT);
            D.skip_defend = 0;
            return;
        }
        D.skip_defend = 0;
        LOGE("soft NCM promote failed — falling back to hard rebuild");
    }
    LOGI("layout switch %s -> %s (gadget rebuild)",
         D.carplay_layout ? "carplay" : "initial",
         carplay ? "carplay" : "initial");
    aad_gadget_unbind(D.g);
    detach_stack();
    aad_gadget_destroy(D.g);
    D.g = NULL;
    D.carplay_layout = carplay;
    if (aad_gadget_create(D.udc, carplay, &D.g) == 0 &&
        attach_stack(carplay) == 0 &&
        aad_gadget_bind(D.g) == 0) {
        if (carplay)
            aad_bringup_ncm_ifaces();
        push_stage(STG_PRESENT);
    } else {
        if (D.g) {
            detach_stack();
            aad_gadget_destroy(D.g);
            D.g = NULL;
        }
        restore_vendor();
        push_stage(STG_ERROR);
    }
}

/* ---------------- control-socket callbacks ---------------- */

static void reenum_cb(void *user)
{
    (void)user;
    D.want_reenum = 1;
}

static void carplay_cb(void *user)
{
    (void)user;
    D.want_carplay = 1;
}

static void arm_cb(int on, void *user)
{
    (void)user;
    D.armed = on;
    if (!on) {
        /* Main loop teardowns when !want && D.g. Don't claim IDLE here if
         * teardown is about to push RESTORING — only update when idle. */
        if (!D.g)
            push_stage(STG_IDLE);
        return;
    }
    /* CRITICAL: never clobber PRESENT/USBMUX/LOCKDOWN/CARKIT with ARMED.
     * CarPlayUsbLink (and repeated ARM) used to reset stage → derivation
     * (`stage >= PRESENT`) stopped running → HU saw Apple VID but no mux. */
    if (!D.g)
        push_stage(STG_ARMED);
}

static int state_cb(void *user)
{
    (void)user;
    return D.stage;
}

/* ---------------- main ---------------- */

static void usage(const char *argv0)
{
    fprintf(stderr,
            "aad-usb (fake iPhone USB gadget daemon)\n"
            "  --port=N        control socket port (default %d)\n"
            "  --udc=NAME      UDC name (default: first /sys/class/udc entry)\n"
            "  --vid=0xXXXX    override idVendor (bring-up; e.g. 0x18d1)\n"
            "  --pid=0xXXXX    override idProduct\n"
            "  --quiet         no verbose logs\n", AAD_CTL_PORT_DEFAULT);
    (void)argv0;
}

int main(int argc, char **argv)
{
    for (int i = 1; i < argc; i++) {
        if (!strncmp(argv[i], "--port=", 7))
            D.ctl_port = atoi(argv[i] + 7);
        else if (!strncmp(argv[i], "--udc=", 6))
            snprintf(D.udc, sizeof(D.udc), "%s", argv[i] + 6);
        else if (!strncmp(argv[i], "--vid=", 6))
            D.ovr_vid = (unsigned)strtoul(argv[i] + 6, NULL, 0);
        else if (!strncmp(argv[i], "--pid=", 6))
            D.ovr_pid = (unsigned)strtoul(argv[i] + 6, NULL, 0);
        else if (!strcmp(argv[i], "--quiet"))
            setenv("AAD_QUIET", "1", 1);
        else { usage(argv[0]); return 1; }
    }
    if (D.ctl_port <= 0) D.ctl_port = AAD_CTL_PORT_DEFAULT;
    if (D.ovr_vid || D.ovr_pid)
        aad_gadget_set_ids(D.ovr_vid ? D.ovr_vid : AAD_VID,
                           D.ovr_pid ? D.ovr_pid : AAD_PID);

    if (geteuid() != 0) {
        fprintf(stderr, "aad-usb: must run as root (Magisk service)\n");
        return 1;
    }

    if (D.udc[0] == 0) {
        if (aad_probe_udc(D.udc, sizeof(D.udc)) != 0) {
            LOGE("no UDC found — cannot run (CONFIG_USB_CONFIGFS? kernel?)");
            return 1;
        }
    }

    struct sigaction sa = {0};
    sa.sa_handler = on_signal;
    sigaction(SIGINT, &sa, NULL);
    sigaction(SIGTERM, &sa, NULL);
    signal(SIGPIPE, SIG_IGN);

    if (aad_bridge_start(&D.br, D.ctl_port) != 0) {
        LOGE("control socket start failed");
        return 1;
    }
    aad_bridge_set_arming_cb(D.br, arm_cb, &D);
    aad_bridge_set_state_cb(D.br, state_cb, &D);
    aad_bridge_set_reenum_cb(D.br, reenum_cb, &D);
    aad_bridge_set_carplay_cb(D.br, carplay_cb, &D);
    push_stage(STG_IDLE);

    LOGI("aad-usb %s ready (udc=%s ctl=127.0.0.1:%d)",
         AAD_VERSION, D.udc, D.ctl_port);

    while (!D.stop) {
        if (g_signal) D.stop = 1;

        /* Do NOT gate takeover on aad_cable_attached(): after release_vendor
         * unbinds g1, dwc3 state is "not attached" even with the cable in,
         * so requiring cable leaves us stuck in ARMED with USB dead (cfg=none).
         * Arm → take immediately; DISARM → teardown/restore. */
        int want = D.armed && !D.stop;

        /* host requested a layout change (0x52) */
        if (D.ctl) {
            int m = aad_ffs_ctl_take_reenum(D.ctl);
            if (m >= 0) {
                int carplay = (m == MODE_CARPLAY);
                if (D.g && carplay == D.carplay_layout) {
                    aad_gadget_unbind(D.g);
                    aad_gadget_bind(D.g);
                    LOGI("re-enum (layout unchanged)");
                } else {
                    rebuild_layout(carplay);
                }
            }
        }

        if (want && !D.g) {
            /* Stay in ERROR after a failed create until DISARM clears armed —
             * otherwise we spin takeover every 500ms and thrash vendor USB.
             * Default: usbmux-only (real iPhone boot). macOS usbmuxd will not
             * ListDevices a usbmux+NCM composite (AppleUSBCDC claims it; exclusive
             * on FF/FE/02 but DeviceList empty). Host 0x52 promotes to CarPlay.
             * AAD_CARPLAY_FIRST=1 restores jump-to-composite (legacy / some HU). */
            if (D.stage != STG_ERROR) {
                int carplay_first = getenv("AAD_CARPLAY_FIRST") &&
                                    getenv("AAD_CARPLAY_FIRST")[0] == '1';
                takeover_fake(carplay_first ? 1 : 0);
            }
        } else if (!want && D.g) {
            teardown_fake();
        } else if (!want && D.stage == STG_ERROR) {
            push_stage(STG_IDLE);
        }

        if (want && D.g && D.want_carplay) {
            D.want_carplay = 0;
            LOGI("CARPLAY: promote to usbmux+NCM (0x52 equivalent)");
            rebuild_layout(1);
        }

        if (want && D.g && D.want_reenum) {
            D.want_reenum = 0;
            LOGI("REENUM: host-visible detach ≥1.5s (skip defend)");
            D.skip_defend = 1;
            if (aad_gadget_reenum_pulse(D.g) != 0)
                LOGE("REENUM pulse failed");
            D.skip_defend = 0;
        }

        if (want && D.g)
            defend_udc();

        /* Derive live stage while fake gadget exists — also heal ARMED if
         * arm_cb / race left D.g set but stage stuck below PRESENT. */
        if (D.g && D.armed) {
            int st = STG_PRESENT;
            if (aad_mux_handshake_done()) st = STG_USBMUX;
            if (aad_lockdown_active()) st = STG_LOCKDOWN;
            if (aad_bridge_has_carkit(D.br)) st = STG_CARKIT;
            if (st != D.stage)
                push_stage(st);
            /* Host may select NCM data altsetting seconds after enum. */
            static int ncm_ticks;
            if ((++ncm_ticks % 4) == 0) /* ~2s */
                aad_bringup_ncm_ifaces();
        }
        if (D.g)
            aad_mux_reap_idle(30 * 60 * 1000);

        if (D.stop)
            break;
        usleep(500 * 1000);
    }

    /* exit path */
    if (D.g)
        teardown_fake();
    if (D.br)
        aad_bridge_stop(D.br);
    LOGI("aad-usb exiting");
    return 0;
}
