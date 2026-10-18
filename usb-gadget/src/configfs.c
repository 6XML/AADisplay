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
 * configfs attribute names (kernel 5.10, live-verified on the target phone):
 *   base:  idVendor / idProduct / bcdDevice ...  (flat files; 6.x kernels
 *           use an ID/ subdirectory instead — we try both)
 *   strings/0x409: manufacturer / product / serialnumber   (6.x: "serial")
 *   configs/b.N: MaxPower / bmAttributes                  (no
 *           bConfigurationValue file on 5.10 — the value derives from the
 *           directory name b.N)
 *
 * FunctionFS instance setup (verified against v5.10 f_fs.c + uapi):
 *   1. mkdir functions/ffs.<inst> — registers the ffs_dev under <inst>.
 *      Instance names must be unique GLOBALLY (the vendor gadget registers
 *      its own ffs.ptp/ffs.mtp/... in the same kernel name space): ours are
 *      usbmux / aadptp / aadptp2 / aadeth.
 *   2. mkdir /dev/usb-ffs/<inst> + mount("functionfs") — the ep files live
 *      on a private per-instance superblock that only exists AFTER the
 *      mount (v5.10 ffs_sb_fill creates ep0; nothing exists before it).
 *   3. open ep0; write V2 descriptors (flags HAS_FS|HAS_HS|VIRTUAL_ADDR|
 *      ALL_CTRL_RECIP) + strings (v5.10: 16B header + u16 lang + NUL-
 *      terminated UTF-8 strings — NOT the legacy fixed 30x u16 tables).
 *   4. the kernel then runs ffs_epfiles_create() + ffs_ready()
 *      (desc_ready=1, the configfs "ready" attribute turns 1). The UDC bind
 *      rejects ffs functions that are not desc_ready (-ENODEV in
 *      ffs_do_functionfs_bind), so this MUST happen before the UDC write.
 *   5. open the endpoint files — with VIRTUAL_ADDR the kernel names them
 *      ep%02x of the FULL bEndpointAddress (ep04/ep85, ep02/ep81/ep83,
 *      ep05/ep86).
 *
 * The kernel f_ncm function handles CDC-NCM fully in kernel (no ep files).
 * The ep0 control traffic of all ffs functions is served by ffs.c.
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
#include <sys/mount.h>
#elif defined(__APPLE__)
#include <libkern/OSByteOrder.h>
#define htole16(x) OSSwapHostToLittleInt16(x)
#define le16toh(x) OSSwapLittleToHostInt16(x)
#define le32toh(x) OSSwapLittleToHostInt32(x)
#endif

#include "aad.h"

/* functionfs_flags (uapi/linux/usb/functionfs.h, kernel 5.10) */
#define FF_HAS_FS_DESC    1
#define FF_HAS_HS_DESC    2
#define FF_VIRTUAL_ADDR   16
#define FF_ALL_CTRL_RECIP 64

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
    /* /dev/usb-ffs mounts WE created (unmounted in destroy) */
    char dev_mounted[4][16];
    int dev_nmounted;
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

/*
 * config function <-> function link. The target is stored ABSOLUTE; the
 * kernel normalizes it to its vendor-style relative form (live-verified:
 * writing an absolute target yields a relative symlink on disk).
 */
static int symlink_fn(const char *base, const char *configdir, const char *target)
{
    char path[PATH_MAX], abs[PATH_MAX];
    snprintf(path, sizeof(path), "%s/%s", configdir, target);
    snprintf(abs, sizeof(abs), "%s/%s", base, target);
    if (symlink(abs, path) != 0 && errno != EEXIST) {
        LOGE("symlink %s -> %s failed: %s", path, abs, strerror(errno));
        return -1;
    }
    return 0;
}

/*
 * Mount the per-instance functionfs superblock under /dev/usb-ffs/<name>.
 * Skipped when <name> is already mounted (ep0 present). Returns 0 on
 * success (mounted or pre-mounted), -1 otherwise. Records self-created
 * mounts in g for the teardown umount.
 */
static int mount_ffs_dev(aad_gadget *g, const char *name)
{
    char dev[PATH_MAX], chk[PATH_MAX];
    snprintf(dev, sizeof(dev), "/dev/usb-ffs/%s", name);
    snprintf(chk, sizeof(chk), "%s/ep0", dev);
    if (access(chk, F_OK) == 0)
        return 0; /* somebody else mounted it (e.g. vendor init) */
    if (mkdir(dev, 0550) != 0 && errno != EEXIST) {
        LOGE("mkdir %s: %s (SELinux?)", dev, strerror(errno));
        return -1;
    }
#ifdef __linux__
    if (mount(name, dev, "functionfs", 0,
              "rmode=0550 fmode=0660 uid=0 gid=0") != 0) {
        LOGE("mount functionfs %s: %s (SELinux?)", name, strerror(errno));
        return -1;
    }
#else
    LOGI("[host] skip mount: functionfs %s at %s", name, dev);
#endif
    if (g->dev_nmounted < (int)(sizeof(g->dev_mounted) / sizeof(g->dev_mounted[0]))) {
        snprintf(g->dev_mounted[g->dev_nmounted],
                 sizeof(g->dev_mounted[0]), "%s", name);
        g->dev_nmounted++;
    }
    return 0;
}

static void unmount_ffs_devs(aad_gadget *g)
{
    for (int i = 0; i < g->dev_nmounted; i++) {
        char dev[PATH_MAX];
        snprintf(dev, sizeof(dev), "/dev/usb-ffs/%s", g->dev_mounted[i]);
#ifdef __linux__
        if (umount2(dev, MNT_DETACH) != 0)
            LOGI("umount %s: %s", dev, strerror(errno));
        rmdir(dev); /* best effort; the vendor keeps /dev/usb-ffs itself */
#endif
    }
    g->dev_nmounted = 0;
}

/*
 * ffs function scaffolding: see the file header for the 5-step flow.
 */
static int install_ffs(aad_gadget *g, const char *name,
                       uint8_t cls, uint8_t sub, uint8_t proto,
                       const char *ifstr, uint8_t ifid,
                       const uint8_t *eps, int neps,
                       int flags_ctrl, uint16_t wmax_hs,
                       int *ep0_fd, int *ep_fds)
{
    char dir[PATH_MAX], dev[PATH_MAX];
    snprintf(dir, sizeof(dir), "%s/functions/ffs.%s", g->base, name);
    if (mkdir(dir, 0755) != 0 && errno != EEXIST) {
        LOGE("mkdir %s failed: %s", dir, strerror(errno));
        return -1;
    }

    /* functionfs mount (must precede the ep0 open) */
    if (mount_ffs_dev(g, name) != 0)
        return -1;

    snprintf(dev, sizeof(dev), "/dev/usb-ffs/%s", name);
    int ep0 = open(dev, O_RDWR | O_CLOEXEC);
    if (ep0 < 0) {
        LOGE("open %s/ep0 failed: %s (functionfs not mounted / SELinux?)",
             dev, strerror(errno));
        return -1;
    }

    /* ---- descriptors (V2: 20B header = magic/len/flags/fs/hs) ---- */
    uint8_t dbuf[64];
    memset(dbuf, 0, sizeof(dbuf));
    uint32_t *h = (uint32_t *)dbuf;
    h[0] = 3;                    /* FUNCTIONFS_DESCRIPTORS_MAGIC_V2 */
    h[1] = 0;                    /* length, fixed below */
    h[2] = FF_HAS_FS_DESC | FF_HAS_HS_DESC | FF_VIRTUAL_ADDR |
           (flags_ctrl ? FF_ALL_CTRL_RECIP : 0);
    /* 5.10 uapi: per-speed counts = number of descriptor ENTITIES
     * (interfaces + endpoints) in that speed's block (v5.10 ffs_do_descs
     * parses exactly `count` entities; a 66B block with count=1 is
     * rejected EINVAL — the bytes must all be consumed). */
    h[3] = 1 + neps;             /* fs_count = 1 interface + neps */
    h[4] = 1 + neps;             /* hs_count (same shape) */
    uint8_t *p = dbuf + 20;

    for (int set = 0; set < 2; set++) {
        uint16_t wmax = set ? wmax_hs : 64; /* FS=64, HS per-function */
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
        i->iInterface = ifid; /* assigned at bind: 3 gadget strings + ffs */
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
            ed->bmAttributes = (eps[e * 2 + 1] & 1) ? 1 : 2; /* int vs bulk */
            ed->wMaxPacketSize = htole16((uint16_t)(eps[e * 2 + 1] & 1 ? 16 : wmax));
            ed->bInterval = 1;
            p += 7;
        }
    }
    h[1] = (uint32_t)(p - dbuf);
    ssize_t w = write(ep0, dbuf, p - dbuf);
    if (w != (ssize_t)(p - dbuf)) {
        LOGE("ffs %s: descriptor write failed: %s (errno=%d)", name,
             strerror(errno), (int)errno);
        close(ep0);
        return -1;
    }

    /* ---- strings (5.10: 16B header + u16 lang + NUL-terminated UTF-8) ---- */
    uint8_t sbuf[128];
    memset(sbuf, 0, sizeof(sbuf));
    uint32_t *sh = (uint32_t *)sbuf;
    sh[0] = 2;                   /* FUNCTIONFS_STRINGS_MAGIC */
    sh[2] = 1;                   /* str_count */
    sh[3] = 1;                   /* lang_count */
    uint16_t lang = htole16(0x409);
    memcpy(sbuf + 16, &lang, 2);
    size_t ilen = strlen(ifstr);
    memcpy(sbuf + 18, ifstr, ilen);   /* + the NUL from memset */
    sh[1] = (uint32_t)(16 + 2 + ilen + 1);
    w = write(ep0, sbuf, sh[1]);
    if (w != (ssize_t)sh[1]) {
        LOGE("ffs %s: strings write failed: %s", name, strerror(errno));
        close(ep0);
        return -1;
    }

    /* kernel: ffs_epfiles_create() + ffs_ready() just ran. Verify via the
     * configfs "ready" attribute when the kernel exposes it. */
    {
        char rpath[PATH_MAX], rv[16] = {0};
        snprintf(rpath, sizeof(rpath), "%s/ready", dir);
        if (aad_sysfs_read_str(rpath, rv, sizeof(rv)) == 0 && rv[0] != '1') {
            LOGE("ffs %s: kernel reports not-ready after descs/strings", name);
            close(ep0);
            return -1;
        }
    }

    /* ---- open declared endpoints (created after the strings write;
     *      VIRTUAL_ADDR => ep%02x of the FULL bEndpointAddress) ---- */
    for (int e = 0; e < neps; e++) {
        char ep[64];
        snprintf(ep, sizeof(ep), "%s/ep%02x", dev, eps[e * 2]);
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
    unmount_ffs_devs(g);
    aad_rmrfdirs(g->base);
    free(g);
}

static int build_config(aad_gadget *g, int cfg, const char *const *fns, int nfns)
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
    /* NOTE: no bConfigurationValue attribute on 5.10 kernels — the value
     * derives from the directory name b.<cfg>. Nothing to write. */
    for (int i = 0; i < nfns; i++)
        if (symlink_fn(g->base, dir, fns[i]) != 0)
            return -1;
    return 0;
}

/* ffs instance names are a GLOBAL kernel name space (ffs_name_dev): the
 * vendor gadget registers ffs.adb/ffs.mtp/ffs.ptp/ffs.diag... — reuse would
 * fail with -EBUSY. Hence the aad* prefixes. */
static const char *FN_USBMUX = "functions/ffs.usbmux";
static const char *FN_PTP    = "functions/ffs.aadptp";
static const char *FN_PTP2   = "functions/ffs.aadptp2";
static const char *FN_AE     = "functions/ffs.aadeth";
static const char *FN_NCM    = "functions/ncm.0";

/*
 * configfs attribute layouts differ between kernel generations:
 *   5.10: base/idVendor, strings/0x409/serialnumber
 *   6.x:  base/ID/vendor,  strings/0x409/serial
 * Try the 5.10 name first, fall back to the other; ENOENT on a write
 * attempt is expected for the layout that is not present.
 */
static int wf_layout(const char *base, const char *a, const char *b,
                     const char *value)
{
    if (wf(base, a, value) == 0) return 0;
    if (wf(base, b, value) == 0) return 0;
    LOGE("write %s|%s failed on both layouts", a, b);
    return -1;
}

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

    aad_rmrfdirs(g->base);
    if (mkdirs(g->base) != 0) { aad_gadget_destroy(g); return -1; }

    /* device identity (5.10 flat idVendor.. vs 6.x ID/ subdirs) */
    if (wf_layout(g->base, "idVendor", "ID/vendor", "0x05ac") ||
        wf_layout(g->base, "idProduct", "ID/product", "0x12a8") ||
        wf_layout(g->base, "bcdDevice", "ID/device", "0x1704")) {
        LOGE("failed to write device ID");
        aad_gadget_destroy(g);
        return -1;
    }
    /* strings (5.10: serialnumber; 6.x: serial) */
    char sp[PATH_MAX];
    snprintf(sp, sizeof(sp), "%s/strings/0x409", g->base);
    mkdirs(sp);
    wf(sp, "manufacturer", AAD_MANUF);
    wf(sp, "product", AAD_PRODUCT);
    wf_layout(sp, "serialnumber", "serial", AAD_UDID);

    /* ffs functions (usbmux ep0 serves the vendor 0x45/0x52 requests) */
    static const uint8_t ep_usbmux[] = { USBMUX_EP_OUT, 0, USBMUX_EP_IN, 0 };
    static const uint8_t ep_ptp[]    = { PTP_EP_OUT, 0, PTP_EP_IN, 0, PTP_EP_INT, 1 };
    static const uint8_t ep_ae[]     = { AE_EP_OUT, 0, AE_EP_IN, 0 };
    /* ffs string IDs: 3 gadget strings (manufacturer/product/serialnumber)
     * + one per ffs function, allocated in BIND order (config list order,
     * functions in config order). CarPlay layout binds b.6 (usbmux+ncm)
     * first => usbmux=4, aadptp=5; initial layout binds b.1 (aadptp) first
     * => aadptp=4, usbmux=5. iInterface is fixed at ep0-write time, so it
     * must already reference the ID the string will get at bind. */
    int usb_ifid = carplay_layout ? 4 : 5;
    int ptp_ifid = carplay_layout ? 5 : 4;
    int fds_usb[2], fds_ptp[3], fds_ptp2[3], fds_ae[2];
    if (install_ffs(g, "usbmux", USBMUX_CLASS, USBMUX_SUBCLASS, USBMUX_PROTOCOL,
                    USBMUX_STR, (uint8_t)usb_ifid, ep_usbmux, 2, 1, USBMUX_WMAXPKT, &g->ep0_usbmux, fds_usb) ||
        install_ffs(g, "aadptp", PTP_CLASS, PTP_SUBCLASS, PTP_PROTOCOL,
                    PTP_STR, (uint8_t)ptp_ifid, ep_ptp, 3, 0, PTP_WMAXPKT, &g->ep0_ptp, fds_ptp) ||
        install_ffs(g, "aadptp2", PTP_CLASS, PTP_SUBCLASS, PTP_PROTOCOL,
                    PTP_STR, 6, ep_ptp, 3, 0, PTP_WMAXPKT, &g->ep0_ptp2, fds_ptp2) ||
        install_ffs(g, "aadeth", AE_CLASS, AE_SUBCLASS, AE_PROTOCOL,
                    AE_STR, 7, ep_ae, 2, 0, AE_WMAXPKT, &g->ep0_ae, fds_ae)) {
        aad_gadget_destroy(g);
        return -1;
    }
    g->ep_usb_out = fds_usb[0]; g->ep_usb_in = fds_usb[1];
    /* the other eps are re-opened below (REOPEN); drop these handles */
    for (int e = 0; e < 3; e++) close(fds_ptp[e]);
    for (int e = 0; e < 3; e++) close(fds_ptp2[e]);
    for (int e = 0; e < 2; e++) close(fds_ae[e]);
    /* re-open the remaining endpoints */
    char d[128];
    int f;
#define REOPEN(name, dst) do { \
        snprintf(d, sizeof(d), "/dev/usb-ffs/" name); \
        f = open(d, O_RDWR | O_CLOEXEC); \
        (dst) = (f >= 0) ? f : -1; \
    } while (0)
    REOPEN("aadptp/ep02", g->ep_ptp_out);
    REOPEN("aadptp/ep81", g->ep_ptp_in);
    REOPEN("aadptp/ep83", g->ep_ptp_int);
    REOPEN("aadptp2/ep02", g->ep_ptp2_out);
    REOPEN("aadptp2/ep81", g->ep_ptp2_in);
    REOPEN("aadptp2/ep83", g->ep_ptp2_int);
    REOPEN("aadeth/ep05", g->ep_ae_out);
    REOPEN("aadeth/ep86", g->ep_ae_in);
#undef REOPEN

    /* kernel NCM function (no userspace eps) */
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

    /* configs — creation order = enumeration order (first = active) */
    if (carplay_layout) {
        /* 6 configs; b.6 first => active on bind */
        if (build_config(g, 6, (const char *const[]){ FN_USBMUX, FN_NCM }, 2) ||
            build_config(g, 1, (const char *const[]){ FN_PTP }, 1) ||
            build_config(g, 2, (const char *const[]){ FN_USBMUX }, 1) ||
            build_config(g, 3, (const char *const[]){ FN_PTP2, FN_USBMUX }, 2) ||
            build_config(g, 4, (const char *const[]){ FN_PTP2, FN_USBMUX, FN_AE }, 3) ||
            build_config(g, 5, (const char *const[]){ FN_USBMUX, FN_NCM, FN_AE }, 3))
            goto fail;
    } else {
        /* 4-config initial layout; b.1 first => active */
        if (build_config(g, 1, (const char *const[]){ FN_PTP }, 1) ||
            build_config(g, 2, (const char *const[]){ FN_USBMUX }, 1) ||
            build_config(g, 3, (const char *const[]){ FN_PTP2, FN_USBMUX }, 2) ||
            build_config(g, 4, (const char *const[]){ FN_PTP2, FN_USBMUX, FN_AE }, 3))
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
