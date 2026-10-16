/*
 * aad.h — AADisplay USB CarPlay gadget daemon (fake iPhone) shared definitions.
 *
 * Part of the AADisplay project (GPLv3). See docs/USB_CARPLAY.md for the design.
 *
 * The daemon (root, Magisk service) presents a configfs/FunctionFS composite
 * device that looks like an iPhone (Apple VID 0x05AC, usbmux + NCM composite)
 * and implements the *device* side of usbmux v1/v2 + lockdown, then bridges
 * the carkit service stream to the AADisplay app over a local TCP socket.
 */
#ifndef AAD_H
#define AAD_H

#include <stdint.h>
#include <stddef.h>

#define AAD_VERSION "0.1.0"

/* Fake iPhone identity (see identity.c). Must be consistent with the
 * usbmuxd host gate: VID 0x05AC, PID in 0x1290..0x12AF. */
#define AAD_VID       0x05ac
#define AAD_PID       0x12a8   /* real iPhone 12-family PID (usb.ids) */
#define AAD_UDID      "0000811000165a2e2658501e"
#define AAD_PRODUCT   "iPhone"
#define AAD_MANUF     "Apple Inc."
#define AAD_BCD_USB   0x0200
#define AAD_BCD_DEV   0x1704   /* "iOS 17.4" */
#define AAD_MAXPOWER  250      /* 500 mA (2 mA units) */

/* usbmux (real iPhone: FF/FE/02, EP 0x04 OUT / 0x85 IN, 512B) */
#define USBMUX_CLASS      0xff
#define USBMUX_SUBCLASS   0xfe
#define USBMUX_PROTOCOL   0x02
#define USBMUX_EP_OUT     0x04
#define USBMUX_EP_IN      0x85
#define USBMUX_WMAXPKT    512
#define USBMUX_STR        "Apple USB Multiplexor"

/* Apple USB Ethernet (real iPhone: FF/FD/01, EP 0x05 / 0x86) */
#define AE_CLASS          0xff
#define AE_SUBCLASS       0xfd
#define AE_PROTOCOL       0x01
#define AE_EP_OUT         0x05
#define AE_EP_IN          0x86
#define AE_WMAXPKT        512
#define AE_STR            "Apple USB Ethernet"

/* PTP (Image) stub, mirrors real iPhone cfg1/cfg3 interfaces */
#define PTP_CLASS         0x06
#define PTP_SUBCLASS      0x01
#define PTP_PROTOCOL      0x01
#define PTP_EP_OUT        0x02
#define PTP_EP_IN         0x81
#define PTP_EP_INT        0x83
#define PTP_WMAXPKT       512
#define PTP_STR           "Apple PTP"

/* usbmux protocol constants (mirror usbmuxd/src/{usb.h,device.c}, GPL) */
#define USB_MTU   (3 * 16384)   /* max mux packet on the wire */
#define USB_MRU   16384
#define MUX_V1    1
#define MUX_V2    2
#define MUX_MAGIC_V2 0xfeedface
#define MUX_PROTO_VERSION 0
#define MUX_PROTO_CONTROL 1
#define MUX_PROTO_TCP     6
#define MUX_PROTO_SETUP   7

/* lockdown */
#define LOCKDOWN_PORT     62078  /* 0xf27e */
#define CARPLAY_PORT_DEFAULT 53593 /* (informational; CarPlay media is on NCM) */

/* Apple vendor device requests (bmRequestType 0xC0) */
#define APPLE_GET_MODE    0x45
#define APPLE_SET_MODE    0x52
#define AOA_GET_STRING    0x53   /* Android AOA — we answer "no AOA" */

/* Mode bytes (usbmuxd: "3:3:3:0 for initial mode, 5:3:3:0 otherwise") */
#define MODE_INITIAL      3
#define MODE_CARPLAY      5

/* Control protocol (daemon <-> app, local TCP) */
#define AAD_CTL_PORT_DEFAULT 48350

/* Stages reported to the app */
enum aad_stage {
    STG_IDLE = 0,       /* daemon up, vendor USB untouched */
    STG_ARMED,          /* user wants CarPlay-USB; waiting for cable */
    STG_TAKING,         /* UDC takeover in progress */
    STG_PRESENT,        /* fake iPhone enumerated, vendor config selected */
    STG_USBMUX,         /* usbmux handshake done, stream active */
    STG_LOCKDOWN,       /* lockdown session active */
    STG_CARKIT,         /* carkit stream bridged to app */
    STG_RESTORING,      /* tearing back to vendor USB */
    STG_ERROR,
};

const char *aad_stage_name(int stage);

/* log levels: 0 verbose .. 3 error (maps to logcat V/D/I/E) */
void aad_log(int level, const char *fmt, ...) __attribute__((format(printf, 2, 3)));
#define LOGV(...) aad_log(0, __VA_ARGS__)
#define LOGD(...) aad_log(1, __VA_ARGS__)
#define LOGI(...) aad_log(2, __VA_ARGS__)
#define LOGE(...) aad_log(3, __VA_ARGS__)

/* ---- util.c ---- */
int  aad_sysfs_write(const char *path, const char *value);
int  aad_sysfs_read_str(const char *path, char *buf, size_t len);   /* strips trailing NL */
int  aad_mkdirs(const char *path);
int  aad_rmrf(const char *path);
long aad_now_ms(void);
void aad_random_bytes(unsigned char *buf, size_t len);
unsigned aad_random_u32(void);
/* find first UDC under /sys/class/udc; returns 0 on success */
int  aad_probe_udc(char *buf, size_t len);
/* current driver bound to the UDC ("" if none) */
int  aad_udc_driver(const char *udc, char *buf, size_t len);
/* is a cable attached to the UDC (state file contains connected/configured/suspended) */
int  aad_cable_attached(const char *udc);

/* ---- plist.c ---- */
struct aad_plist {
    int type; /* 0 none, 1 dict, 2 array, 3 string, 4 data, 5 int, 6 bool */
    char *key;                 /* for dict children only (owned) */
    char *str;                 /* type 3 (owned, NUL-terminated) */
    unsigned char *data;       /* type 4 */
    size_t data_len;
    int64_t ival;              /* type 5 */
    int bval;                  /* type 6 */
    struct aad_plist **items;  /* dict/array children */
    int count;
    int cap;
};
typedef struct aad_plist aad_plist;
/* parse an XML plist document; returns NULL on error. Caller frees. */
aad_plist *aad_plist_parse(const char *data, size_t len);
/* render to a static-ish buffer; returns malloc'd NUL-terminated string. Caller frees. */
char *aad_plist_render(const aad_plist *pl, size_t *out_len);
void  aad_plist_free(aad_plist *pl);
/* node accessors (type-erased): values are borrowed, do not free */
int   aad_plist_is_dict(const aad_plist *pl);
int   aad_plist_dict_size(const aad_plist *pl);
const aad_plist *aad_plist_dict_get(const aad_plist *dict, const char *key);
const char *aad_plist_get_string(const aad_plist *pl, const char *key, size_t *out_len);
const aad_plist *aad_plist_get(const aad_plist *dict, const char *key);
int   aad_plist_get_int(const aad_plist *dict, const char *key, int *out);
/* builders */
aad_plist *aad_plist_new_dict(void);
void  aad_plist_dict_set(aad_plist *dict, const char *key, aad_plist *value); /* takes ownership */
void  aad_plist_array_append(aad_plist *arr, aad_plist *value);                 /* takes ownership */
aad_plist *aad_plist_new_string(const char *s);
aad_plist *aad_plist_new_int(int v);
aad_plist *aad_plist_new_bool(int v);
aad_plist *aad_plist_new_data(const void *data, size_t len);
aad_plist *aad_plist_new_array(void);
/* data node accessor (PEM/DER blobs) */
const unsigned char *aad_plist_get_data(const aad_plist *pl, size_t *out_len);
/* base64 / PEM helpers */
int   aad_b64_decode(const char *in, size_t inlen, unsigned char **out, size_t *outlen);
int   aad_pem_to_der(const char *pem, unsigned char **out, size_t *outlen);

/* ---- configfs.c ---- */
typedef struct aad_gadget aad_gadget;
/*
 * Build the 6-config fake-iPhone composite (configfs) and bind it to the UDC.
 * active_cfg = 1 (initial layout, cfg1 active) or 6 (CarPlay layout, cfg6 active).
 * Takes ownership of `udc` (the UDC name). Caller must call aad_gadget_unbind()
 * before changing anything; aad_gadget_destroy() tears everything down.
 */
int  aad_gadget_create(const char *udc, int carplay_layout, aad_gadget **out);
/* unbind UDC (configfs) so the caller can restore the vendor driver */
void aad_gadget_unbind(aad_gadget *g);
/* rebind (after unbind) — idempotent */
int  aad_gadget_bind(aad_gadget *g);
void aad_gadget_destroy(aad_gadget *g);
const char *aad_gadget_udc(const aad_gadget *g);
/* open ffs endpoint files (ep0/ep04/ep85/ep05/ep86...) — fd owned by caller */
int  aad_gadget_open_ep(aad_gadget *g, const char *name, int *fd);
/* pre-opened endpoint fds (kept open for the gadget's whole life) */
int  aad_gadget_ep0_usbmux(const aad_gadget *g);
int  aad_gadget_ep_usb_out(const aad_gadget *g);
int  aad_gadget_ep_usb_in(const aad_gadget *g);
/* current composite layout (0 = initial 4-cfg, 1 = carplay 6-cfg) */
int  aad_gadget_layout(const aad_gadget *g);

/* ---- ffs.c (control plane) ---- */
typedef struct aad_ffs_ctl aad_ffs_ctl;
typedef void (*aad_mode_cb)(int mode, void *user);
/*
 * Install ffs function descriptors (usbmux/ptp/appleeth) and run the ep0
 * handler: answers 0x45 (mode), 0x52 (mode switch -> cb), 0x53 AOA (no-op),
 * stalls the rest. Runs on its own thread while `run` is true.
 * ep0_fd: the /dev/usb-ffs/<fn> fd (kept open for the gadget lifetime).
 */
int  aad_ffs_ctl_start(aad_gadget *g, int ep0_fd, aad_mode_cb cb, void *user, aad_ffs_ctl **out);
void aad_ffs_ctl_stop(aad_ffs_ctl *ctl);
/* current mode reported to 0x45 (set before bind: MODE_CARPLAY for layout 6) */
void aad_ffs_ctl_set_mode(aad_ffs_ctl *ctl, int mode);
/* consume a pending 0x52 mode-switch request (-1 = none) */
int  aad_ffs_ctl_take_reenum(aad_ffs_ctl *ctl);

/* ---- usbmux.c (device side, singleton) ---- */
/*
 * The usbmux device side runs on the usbmux ffs bulk endpoints
 * (/dev/usb-ffs/usbmux/{ep04,ep85}). One active device at a time.
 *
 * Service ports:
 *   62078 (lockdown): accepted conns are handed to on_lockdown(conn_id).
 *   bridge ports (carkit; aad_mux_alloc_bridge_port): accepted conns get a
 *   socketpair; on_bridge(pair_fd) is called and the stream is pumped to the
 *   conn internally.
 */
int  aad_mux_start(int ep_out, int ep_in,
                   void (*on_lockdown)(int conn_id, void *user),
                   void (*on_bridge)(int pair_fd, void *user),
                   void *user);
/* register a previously allocated bridge port as live (carkit started) */
int  aad_mux_alloc_bridge_port(void);
int  aad_mux_enable_bridge_port(int port);
/* connection data (blocking; <=0 on close/error; 0 = timeout if timeout_ms>0) */
int  aad_mux_write(int conn_id, const void *buf, size_t len);
int  aad_mux_read(int conn_id, void *buf, size_t len, int timeout_ms);
void aad_mux_conn_close(int conn_id, const char *rst_reason);
int  aad_mux_conn_alive(int conn_id);
/* close conns idle for longer than idle_ms (host went silent) */
void aad_mux_reap_idle(long idle_ms);
/* stream handshake complete (VERSION exchanged) */
int  aad_mux_handshake_done(void);
void aad_mux_stop(void);

/* ---- lockdown.c ---- */
typedef struct aad_lockdown aad_lockdown;
/*
 * Run the lockdownd device side on the accepted 62078 usbmux pseudo-TCP
 * connection (conn_id from the mux layer). Reads 4B-BE-length + XML plist
 * frames via aad_mux_read and answers GetValue/Pair/ValidatePair/CheckMode/
 * StartSession/StartService/StopSession/etc. Pairing is auto-trust (v1);
 * StartSession answers EnableSessionSSL:false (v2 adds TLS).
 *
 * The carkit service stream reaches the app via the mux on_bridge callback
 * (StartService allocates a bridge port; the host dials it), so this layer
 * has no direct app-facing socket of its own.
 */
int  aad_lockdown_start(aad_lockdown **out, int conn_id);
void aad_lockdown_stop(aad_lockdown *ld);
/* stop the active session (if any) — for daemon teardown */
void aad_lockdown_stop_active(void);
/* is a lockdown session currently active? */
int  aad_lockdown_active(void);

/* ---- identity.c ---- */
/* PEM blobs: root cert / device cert / device public key (pre-generated, see
 * .tmp/identity). Embedded so the daemon has zero runtime crypto deps (v1). */
extern const char AAD_ROOT_CERT_PEM[];
extern const char AAD_DEVICE_CERT_PEM[];
extern const char AAD_DEVICE_PUB_PEM[];
/* WiFi MAC used in pairing records (locally administered) */
#define AAD_WIFI_MAC "52:54:00:a1:b2:c3"

/* ---- bridge.c ---- */
typedef struct aad_bridge aad_bridge;
/* local control socket for the app */
int  aad_bridge_start(aad_bridge **out, int port);
/* push a state line to all attached app clients */
void aad_bridge_push_state(aad_bridge *br, int stage, const char *detail);
/* called by mux on_bridge when a service stream is available */
void aad_bridge_publish_carkit(aad_bridge *br, int pair_fd);
/* any carkit stream currently live? */
int  aad_bridge_has_carkit(aad_bridge *br);
/* wiring (set before aad_bridge_start returns matters for ARM/STATE) */
void aad_bridge_set_arming_cb(aad_bridge *br, void (*cb)(int on, void *user), void *user);
void aad_bridge_set_state_cb(aad_bridge *br, int (*cb)(void *user), void *user);
void aad_bridge_stop(aad_bridge *br);

#endif /* AAD_H */
