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
#ifdef __ANDROID__
    __system_property_get("sys.usb.config", D.vendor_usb_config);
#endif
    D.vendor_snap_done = 1;
    LOGI("vendor snapshot: driver='%s' usb.config='%s'",
         D.vendor_driver, D.vendor_usb_config);
}

static void release_vendor_udc(void)
{
    if (!D.vendor_driver[0]) return;
    char path[256];
    snprintf(path, sizeof(path), "/sys/bus/platform/drivers/%s/unbind",
             D.vendor_driver);
    if (aad_sysfs_write(path, D.udc) != 0)
        LOGI("vendor unbind %s failed — attempting bind anyway", D.vendor_driver);
}

static void restore_vendor(void)
{
    /* 1) ask the vendor USB HAL to reconfigure (covers module + configfs HALs) */
#ifdef __ANDROID__
    if (D.vendor_usb_config[0])
        __system_property_set("sys.usb.config", D.vendor_usb_config);
#endif
    /* 2) direct rebind fallback */
    if (D.vendor_driver[0]) {
        char path[256];
        snprintf(path, sizeof(path), "/sys/bus/platform/drivers/%s/bind",
                 D.vendor_driver);
        if (aad_sysfs_write(path, D.udc) != 0) {
            char cfg[256];
            snprintf(cfg, sizeof(cfg), "/config/usb_gadget/%s/UDC",
                     D.vendor_driver);
            if (aad_sysfs_write(cfg, D.udc) != 0)
                LOGE("vendor restore failed — USB stays unbound until re-plug/reboot");
        }
    }
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
    if (attach_stack(carplay) != 0) {
        LOGE("stack attach failed — restoring vendor USB");
        aad_gadget_destroy(g);
        D.g = NULL;
        restore_vendor();
        push_stage(STG_ERROR);
        return;
    }
    push_stage(STG_PRESENT);
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

/* 0x52 mode switch: rebuild the composite with the requested layout */
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
    LOGI("layout switch %s -> %s (gadget rebuild)",
         D.carplay_layout ? "carplay" : "initial",
         carplay ? "carplay" : "initial");
    aad_gadget_unbind(D.g);
    detach_stack();
    aad_gadget_destroy(D.g);
    D.g = NULL;
    D.carplay_layout = carplay;
    if (aad_gadget_create(D.udc, carplay, &D.g) == 0 &&
        attach_stack(carplay) == 0) {
        push_stage(STG_PRESENT);
    } else {
        if (D.g) { aad_gadget_destroy(D.g); D.g = NULL; }
        restore_vendor();
        push_stage(STG_ERROR);
    }
}

/* ---------------- control-socket callbacks ---------------- */

static void arm_cb(int on, void *user)
{
    (void)user;
    D.armed = on;
    push_stage(on ? STG_ARMED : STG_IDLE);
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
        else if (!strcmp(argv[i], "--quiet"))
            setenv("AAD_QUIET", "1", 1);
        else { usage(argv[0]); return 1; }
    }
    if (D.ctl_port <= 0) D.ctl_port = AAD_CTL_PORT_DEFAULT;

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
    push_stage(STG_IDLE);

    LOGI("aad-usb %s ready (udc=%s ctl=127.0.0.1:%d)",
         AAD_VERSION, D.udc, D.ctl_port);

    while (!D.stop) {
        if (g_signal) D.stop = 1;

        int cable = aad_cable_attached(D.udc);
        int want = D.armed && cable && !D.stop;

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

        if (want && !D.g)
            takeover_fake(1);
        else if (!want && D.g)
            teardown_fake();

        /* derive the live stage while the fake device is attached */
        if (D.g && D.stage >= STG_PRESENT && D.stage <= STG_CARKIT) {
            int st = STG_PRESENT;
            if (aad_mux_handshake_done()) st = STG_USBMUX;
            if (aad_lockdown_active()) st = STG_LOCKDOWN;
            if (aad_bridge_has_carkit(D.br)) st = STG_CARKIT;
            if (st != D.stage)
                push_stage(st);
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
