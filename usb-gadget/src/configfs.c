/*
 * configfs.c — build the fake-iPhone USB composite via configfs, take over
 * the phone's UDC, and restore the vendor USB state on teardown.
 *
 * Layout (mirrors a modern iPhone, e.g. 12 Pro Max / iOS 26: 6 configs):
 *   cfg1: PTP                                   (initial-mode cfg1)
 *   cfg2: usbmux                               (iPod)
 *   cfg3: PTP + usbmux                         (Apple Mobile Device)
 *   cfg4: PTP + usbmux + AppleEthernet
 *   cfg5: usbmux + NCM + AppleEthernet         (CarPlay, pre-iOS17 shape)
 *   cfg6: usbmux + NCM                         (CarPlay, iOS17+ shape)
 * CarPlay layout = all 6, cfg6 active. Initial layout = cfg1..4, cfg1 active
 * (matches usbmuxd guess_mode: 6 configs -> mode 4 CarPlay, <=4 -> mode 1).
 *
 * ffs functions (usbmux/ptp/ptp2/appleeth) are driven by the ffs.c control
 * thread; the kernel f_ncm function handles CDC-NCM (control + data) fully in
 * kernel, including class requests and alt switching.
 */
#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <sys/stat.h>
#if defined(__linux__)
#include <endian.h>
#elif defined(__APPLE__)
#include <libkern/OSByteOrder.h>
#define htole16(x) OSSwapHostToLittleInt16(x)
#define le16toh(x) OSSwapLittleToHostInt16(x)
#define le32toh(x) OSSwapLittleToHostInt32(x)
#endif

#include "aad.h"

struct aad_gadget {
    char udc[64];
    char base[PATH_MAX];
    int carplay_layout;
    /* ffs ep0 fds — must stay open for the gadget's whole life */
    int ep0_usbmux;
    int ep0_ptp, ep0_ptp2, ep0_ae;
    /* bulk data fds */
    int ep_usb_out, ep_usb_in;
    int ep_ae_out, ep_ae_in;
    int ep_ptp_out, ep_ptp_in, ep_ptp_int;
    int ep_ptp2_out, ep_ptp2_in, ep_ptp2_int;
    int bound;
};

const char *aad_gadget_udc(const aad_gadget *g) { return g->udc; }
int aad_gadget_ep0_usbmux(const aad_gadget *g) { return g->ep0_usbmux; }
int aad_gadget_ep_usb_out(const aad_gadget *g) { return g->ep_usb_out; }
int aad_gadget_ep_usb_in(const aad_gadget *g) { return g->ep_usb_in; }
int aad_gadget_layout(const aad_gadget *g) { return g->carplay_layout; }

static int wf(const char *dir, const char *file, const char *value)
{
    char path[PATH_MAX];
    snprintf(path, sizeof(path), "%s/%s/%s", dir, file, value);
    int fd = open(path, O_WRONLY | O_CLOEXEC);
    if (fd < 0) {
        if (errno != ENOENT)
            LOGE("write %s/%s=%s: %s", dir, file, value, strerror(errno));
        return -1;
    }
    ssize_t n = write(fd, value, strlen(value));
    int rc = (n < 0) ? -1 : 0;
    if (rc) LOGE("write %s failed: %s", path, strerror(errno));
    close(fd);
    return rc;
}

static int mkdirs(const char *p) { return aad_mkdirs(p); }

static int symlink_fn(const char *configdir, const char *target)
{
    char path[PATH_MAX];
    snprintf(path, sizeof(path), "%s/%s", configdir, target);
    if (symlink(target, path) != 0 && errno != EEXIST) {
        LOGE("symlink %s -> %s failed: %s", path, target, strerror(errno));
        return -1;
    }
    return 0;
}

/*
 * ffs function scaffolding: mkdir the configfs function, open /dev/usb-ffs,
 * install V2 descriptors (FS+HS) + interface string, keep the ep0 fd open,
 * then open the declared bulk/INT endpoint files.
 *
 * Descriptor block (per uapi functionfs.h, V2):
 *   [0]=magic(3) [4]=length [8]=flags | u32 fs_count | u32 hs_count
 *   per count: usb_interface_descriptor(9) + per-ep usb_endpoint_descriptor(7)
 * Strings write:
 *   [0]=magic(2) [4]=length [8]=u32 count | count x {u16 id; u8 utf16le[30]}
 */
static int install_ffs(const char *base, const char *name,
                       uint8_t cls, uint8_t sub, uint8_t proto,
                       const char *ifstr,
                       const uint8_t *eps, int neps,
                       int flags_ctrl,
                       int *ep0_fd, int *ep_fds, int nep_out)
{
    char dir[PATH_MAX], dev[PATH_MAX];
    snprintf(dir, sizeof(dir), "%s/functions/ffs.%s", base, name);
    if (mkdir(dir, 0755) != 0 && errno != EEXIST) {
        LOGE("mkdir %s failed: %s", dir, strerror(errno));
        return -1;
    }
    snprintf(dev, sizeof(dev), "/dev/usb-ffs/%s", name);
    int ep0 = open(dev, O_RDWR | O_CLOEXEC);
    if (ep0 < 0) {
        LOGE("open %s failed: %s (is CONFIG_USB_F_FS available?)", dev, strerror(errno));
        return -1;
    }

    /* ---- descriptors (FS + HS) ---- */
    uint8_t dbuf[64];
    memset(dbuf, 0, sizeof(dbuf));
    uint32_t *h = (uint32_t *)dbuf;
    h[0] = 3;                    /* FUNCTIONFS_DESCRIPTORS_MAGIC_V2 */
    h[1] = 0;                    /* length, fixed below */
    h[2] = 1 | 2 | (flags_ctrl ? 64 : 0); /* FS | HS | ALL_CTRL_RECIP */
    h[3] = 1;                    /* fs_count */
    h[4] = 1;                    /* hs_count */
    uint8_t *p = dbuf + 20;

    for (int set = 0; set < 2; set++) {
        uint16_t wmax = set ? USBMUX_WMAXPKT : 64; /* FS=64, HS=512 */
        /* interface descriptor (9) */
        struct {
            uint8_t bLength, bDescriptorType;
            uint8_t bInterfaceNumber, bAlternateSetting;
            uint8_t bNumEndpoints;
            uint8_t bInterfaceClass, bInterfaceSubClass, bInterfaceProtocol;
            uint8_t iInterface;
        } *i = (void *)p;
        i->bLength = 9;
        i->bDescriptorType = 4;
        i->bInterfaceNumber = 0; /* composite core renumbers */
        i->bAlternateSetting = 0;
        i->bNumEndpoints = (uint8_t)neps;
        i->bInterfaceClass = cls;
        i->bInterfaceSubClass = sub;
        i->bInterfaceProtocol = proto;
        i->iInterface = 1;
        p += 9;
        for (int e = 0; e < neps; e++) {
            struct {
                uint8_t bLength, bDescriptorType, bAddress, bmAttributes;
                uint16_t wMaxPacketSize;
                uint8_t bInterval;
            } *ed = (void *)p;
            ed->bLength = 7;
            ed->bDescriptorType = 5;
            ed->bAddress = eps[e * 2];
            ed->bmAttributes = (eps[e * 2 + 1] & 1) ? 2 : 0x02; /* INT=2? no: bulk=2, int=1 */
            ed->bmAttributes = (eps[e * 2 + 1] & 1) ? 1 : 2;
            ed->wMaxPacketSize = htole16((uint16_t)(eps[e * 2 + 1] & 1 ? 16 : wmax));
            ed->bInterval = 1;
            p += 7;
        }
    }
    h[1] = (uint32_t)(p - dbuf);
    ssize_t w = write(ep0, dbuf, p - dbuf);
    if (w != (ssize_t)(p - dbuf)) {
        LOGE("ffs %s: descriptor write failed: %s", name, strerror(errno));
        close(ep0);
        return -1;
    }

    /* ---- strings (one: iInterface=1) ---- */
    uint8_t sbuf[64];
    memset(sbuf, 0, sizeof(sbuf));
    uint32_t *sh = (uint32_t *)sbuf;
    sh[0] = 2;                   /* FUNCTIONFS_STRINGS_MAGIC */
    sh[1] = 0;
    sh[2] = 1;                   /* count */
    uint16_t *sid = (uint16_t *)(sbuf + 12);
    *sid = 1;
    uint8_t *sdata = sbuf + 14;
    for (size_t i = 0; ifstr[i]; i++)
        ((uint16_t *)sdata)[i] = htole16(ifstr[i]);
    size_t slen = 12 + 2 + 30;
    sh[1] = (uint32_t)slen;
    w = write(ep0, sbuf, slen);
    if (w != (ssize_t)slen) {
        LOGE("ffs %s: strings write failed: %s", name, strerror(errno));
        close(ep0);
        return -1;
    }

    /* ---- open declared endpoints (created by the kernel after descs) ---- */
    for (int e = 0; e < neps; e++) {
        char ep[64];
        snprintf(ep, sizeof(ep), "%s/ep%02x", dev, eps[e * 2] & 0x1f);
        int f = open(ep, O_RDWR | O_CLOEXEC);
        if (f < 0) {
            LOGE("open %s failed: %s", ep, strerror(errno));
            close(ep0);
            for (int j = 0; j < e; j++) close(ep_fds[j]);
            return -1;
        }
        ep_fds[e] = f;
    }
    *ep0_fd = ep0;
    (void)nep_out;
    return 0;
}

int aad_gadget_open_ep(aad_gadget *g, const char *name, int *fd)
{
    char dev[128];
    snprintf(dev, sizeof(dev), "/dev/usb-ffs/%s", name);
    int f = open(dev, O_RDWR | O_CLOEXEC);
    if (f < 0) {
        LOGE("re-open %s failed: %s", dev, strerror(errno));
        return -1;
    }
    *fd = f;
    return 0;
}

static void close_fd(int *fd)
{
    if (*fd >= 0) { close(*fd); *fd = -1; }
}

void aad_gadget_unbind(aad_gadget *g)
{
    if (!g || !g->bound) return;
    char path[PATH_MAX];
    snprintf(path, sizeof(path), "%s/UDC", g->base);
    /* empty write = unbind */
    int fd = open(path, O_WRONLY | O_CLOEXEC);
    if (fd < 0) {
        LOGE("open %s failed: %s", path, strerror(errno));
        return;
    }
    ssize_t r = write(fd, "", 0);
    (void)r;
    close(fd);
    g->bound = 0;
}

int aad_gadget_bind(aad_gadget *g)
{
    if (!g || g->bound) return 0;
    char path[PATH_MAX];
    snprintf(path, sizeof(path), "%s/UDC", g->base);
    if (aad_sysfs_write(path, g->udc) != 0)
        return -1;
    g->bound = 1;
    return 0;
}

void aad_gadget_destroy(aad_gadget *g)
{
    if (!g) return;
    aad_gadget_unbind(g);
    close_fd(&g->ep0_usbmux);
    close_fd(&g->ep0_ptp);
    close_fd(&g->ep0_ptp2);
    close_fd(&g->ep0_ae);
    close_fd(&g->ep_usb_out); close_fd(&g->ep_usb_in);
    close_fd(&g->ep_ae_out);  close_fd(&g->ep_ae_in);
    close_fd(&g->ep_ptp_out); close_fd(&g->ep_ptp_in); close_fd(&g->ep_ptp_int);
    close_fd(&g->ep_ptp2_out); close_fd(&g->ep_ptp2_in); close_fd(&g->ep_ptp2_int);
    aad_rmrf(g->base);
    free(g);
}

static int build_config(aad_gadget *g, int cfg, int carplay,
                        const char *const *fns, int nfns)
{
    char dir[PATH_MAX];
    snprintf(dir, sizeof(dir), "%s/configs/b.%d", g->base, cfg);
    if (mkdirs(dir) != 0) return -1;
    /* MaxPower 500mA (2mA units) */
    char mp[PATH_MAX];
    snprintf(mp, sizeof(mp), "%s/MaxPower", dir);
    int fd = open(mp, O_WRONLY | O_CLOEXEC);
    if (fd >= 0) {
        write(fd, "250", 3);
        close(fd);
    }
    for (int i = 0; i < nfns; i++) {
        if (symlink_fn(dir, fns[i]) != 0) return -1;
    }
    (void)carplay;
    return 0;
}

static const char *FN_USBMUX = "functions/ffs.usbmux";
static const char *FN_PTP    = "functions/ffs.ptp";
static const char *FN_PTP2   = "functions/ffs.ptp2";
static const char *FN_AE     = "functions/ffs.appleeth";
static const char *FN_NCM    = "functions/ncm.0";

int aad_gadget_create(const char *udc, int carplay_layout, aad_gadget **out)
{
    aad_gadget *g = calloc(1, sizeof(*g));
    if (!g) return -1;
    snprintf(g->base, sizeof(g->base), "/config/usb_gadget/aadusb");
    snprintf(g->udc, sizeof(g->udc), "%s", udc);
    g->carplay_layout = carplay_layout;
    /* pre-close */
    g->ep0_usbmux = g->ep0_ptp = g->ep0_ptp2 = g->ep0_ae = -1;
    g->ep_usb_out = g->ep_usb_in = -1;
    g->ep_ae_out = g->ep_ae_in = -1;
    g->ep_ptp_out = g->ep_ptp_in = g->ep_ptp_int = -1;
    g->ep_ptp2_out = g->ep_ptp2_in = g->ep_ptp2_int = -1;

    aad_rmrf(g->base);
    if (mkdirs(g->base) != 0) { aad_gadget_destroy(g); return -1; }

    /* device identity */
    if (wf(g->base, "ID/vendor", "0x05ac") ||
        wf(g->base, "ID/product", "0x12a8") ||
        wf(g->base, "ID/device", "0x1704")) {
        LOGE("failed to write device ID (configfs layout mismatch?)");
        aad_gadget_destroy(g);
        return -1;
    }
    /* strings */
    char sp[PATH_MAX];
    snprintf(sp, sizeof(sp), "%s/strings/0x409", g->base);
    mkdirs(sp);
    snprintf(sp, sizeof(sp), "%s/strings/0x409", g->base);
    wf(sp, "manufacture", AAD_MANUF);
    snprintf(sp, sizeof(sp), "%s/strings/0x409", g->base);
    wf(sp, "product", AAD_PRODUCT);
    snprintf(sp, sizeof(sp), "%s/strings/0x409", g->base);
    wf(sp, "serial", AAD_UDID);

    /* ffs functions */
    static const uint8_t ep_usbmux[] = { USBMUX_EP_OUT, 0, USBMUX_EP_IN, 0 };
    static const uint8_t ep_ptp[]    = { PTP_EP_OUT, 0, PTP_EP_IN, 0, PTP_EP_INT, 1 };
    static const uint8_t ep_ae[]     = { AE_EP_OUT, 0, AE_EP_IN, 0 };
    int fds[5];
    if (install_ffs(g->base, "usbmux", USBMUX_CLASS, USBMUX_SUBCLASS, USBMUX_PROTOCOL,
                    USBMUX_STR, ep_usbmux, 2, 1, &g->ep0_usbmux, fds, 0) ||
        install_ffs(g->base, "ptp", PTP_CLASS, PTP_SUBCLASS, PTP_PROTOCOL,
                    PTP_STR, ep_ptp, 3, 0, &g->ep0_ptp, fds, 0) ||
        install_ffs(g->base, "ptp2", PTP_CLASS, PTP_SUBCLASS, PTP_PROTOCOL,
                    PTP_STR, ep_ptp, 3, 0, &g->ep0_ptp2, fds, 0) ||
        install_ffs(g->base, "appleeth", AE_CLASS, AE_SUBCLASS, AE_PROTOCOL,
                    AE_STR, ep_ae, 2, 0, &g->ep0_ae, fds, 0)) {
        aad_gadget_destroy(g);
        return -1;
    }
    g->ep_usb_out = fds[0]; g->ep_usb_in = fds[1];
    /* ptp fds: [out,in,int] for ptp; [out,in,int] for ptp2 */
    /* (re-read from install order: ptp fds were fds[0..2], ptp2 fds[0..2] last) */
    /* NOTE: install_ffs overwrote fds[] each call; capture via re-open for clarity */
    char d[128];
    int f;
#define REOPEN(name, dst) do { \
        snprintf(d, sizeof(d), "/dev/usb-ffs/" name); \
        f = open(d, O_RDWR | O_CLOEXEC); \
        (dst) = (f >= 0) ? f : -1; \
    } while (0)
    REOPEN("ptp/ep02", g->ep_ptp_out);
    REOPEN("ptp/ep81", g->ep_ptp_in);
    REOPEN("ptp/ep83", g->ep_ptp_int);
    REOPEN("ptp2/ep02", g->ep_ptp2_out);
    REOPEN("ptp2/ep81", g->ep_ptp2_in);
    REOPEN("ptp2/ep83", g->ep_ptp2_int);
    REOPEN("appleeth/ep05", g->ep_ae_out);
    REOPEN("appleeth/ep86", g->ep_ae_in);
#undef REOPEN

    /* kernel NCM function */
    {
        char np[PATH_MAX];
        snprintf(np, sizeof(np), "%s/functions/ncm.0", g->base);
        if (mkdir(np, 0755) != 0 && errno != EEXIST) {
            LOGE("mkdir functions/ncm.0 failed: %s (is CONFIG_USB_F_NCM available?)",
                 strerror(errno));
            aad_gadget_destroy(g);
            return -1;
        }
    }

    /* configs — creation order = interface/activation order (first = active) */
    if (carplay_layout) {
        /* 6 configs; b.6 first => active on bind */
        if (build_config(g, 6, 1, (const char *const[]){ FN_USBMUX, FN_NCM }, 2) ||
            build_config(g, 1, 1, (const char *const[]){ FN_PTP }, 1) ||
            build_config(g, 2, 1, (const char *const[]){ FN_USBMUX }, 1) ||
            build_config(g, 3, 1, (const char *const[]){ FN_PTP2, FN_USBMUX }, 2) ||
            build_config(g, 4, 1, (const char *const[]){ FN_PTP2, FN_USBMUX, FN_AE }, 3) ||
            build_config(g, 5, 1, (const char *const[]){ FN_USBMUX, FN_NCM, FN_AE }, 3))
            goto fail;
    } else {
        /* 4-config initial layout; b.1 first => active */
        if (build_config(g, 1, 0, (const char *const[]){ FN_PTP }, 1) ||
            build_config(g, 2, 0, (const char *const[]){ FN_USBMUX }, 1) ||
            build_config(g, 3, 0, (const char *const[]){ FN_PTP2, FN_USBMUX }, 2) ||
            build_config(g, 4, 0, (const char *const[]){ FN_PTP2, FN_USBMUX, FN_AE }, 3))
            goto fail;
    }

    if (aad_gadget_bind(g) != 0)
        goto fail;

    LOGI("gadget %s bound to UDC %s (layout=%s, bNumConfigs=%d)",
         g->base, udc, carplay_layout ? "carplay" : "initial",
         carplay_layout ? 6 : 4);
    *out = g;
    return 0;

fail:
    aad_gadget_destroy(g);
    return -1;
}
