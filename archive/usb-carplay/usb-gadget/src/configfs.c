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
#include <dirent.h>
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

/* Optional VID/PID override for Mac bring-up (0 = defaults). */
static unsigned g_ovr_vid, g_ovr_pid;

void aad_gadget_set_ids(unsigned vid, unsigned pid)
{
    g_ovr_vid = vid;
    g_ovr_pid = pid;
}

struct aad_gadget {
    char udc[64];
    char base[PATH_MAX];
    int carplay_layout;
    int hijack_g1;               /* 1 = mutate stock g1 (Samsung); 0 = own aadusb */
    char saved_vid[16];
    char saved_pid[16];
    char saved_manuf[64];
    char saved_prod[64];
    char saved_serial[64];
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
const char *aad_gadget_base(const aad_gadget *g) { return g ? g->base : NULL; }
int aad_gadget_hijack(const aad_gadget *g) { return g ? g->hijack_g1 : 0; }
int aad_gadget_ep0_usbmux(const aad_gadget *g) { return g->ep0_usbmux; }
int aad_gadget_ep_usb_out(const aad_gadget *g) { return g->ep_usb_out; }
int aad_gadget_ep_usb_in(const aad_gadget *g) { return g->ep_usb_in; }
int aad_gadget_layout(const aad_gadget *g) { return g->carplay_layout; }

static int wf(const char *dir, const char *file, const char *value)
{
    char path[PATH_MAX];
    /* Path is dir/file; value is the write payload (never a path component). */
    snprintf(path, sizeof(path), "%s/%s", dir, file);
    int fd = open(path, O_WRONLY | O_CLOEXEC);
    if (fd < 0) {
        if (errno != ENOENT)
            LOGE("open %s (value=%s): %s", path, value, strerror(errno));
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
 * link_name NULL → basename of target (AOSP style). Samsung g1 uses f1/f2…
 */
static int symlink_fn_as(const char *base, const char *configdir,
                         const char *target, const char *link_name)
{
    char path[PATH_MAX], abs[PATH_MAX];
    const char *bn = link_name;
    if (!bn) {
        bn = strrchr(target, '/');
        bn = bn ? bn + 1 : target;
    }
    snprintf(path, sizeof(path), "%s/%s", configdir, bn);
    snprintf(abs, sizeof(abs), "%s/%s", base, target);
    unlink(path); /* replace stale link */
    if (symlink(abs, path) != 0) {
        LOGE("symlink %s -> %s failed: %s", path, abs, strerror(errno));
        return -1;
    }
    return 0;
}

static int symlink_fn(const char *base, const char *configdir, const char *target)
{
    return symlink_fn_as(base, configdir, target, NULL);
}

/* Remove Samsung/AOSP config function symlinks (f1..f9 / bare names). */
static void clear_config_links(const char *configdir)
{
    DIR *d = opendir(configdir);
    if (!d) return;
    struct dirent *de;
    while ((de = readdir(d)) != NULL) {
        if (de->d_name[0] == '.') continue;
        if (!strcmp(de->d_name, "MaxPower") ||
            !strcmp(de->d_name, "bmAttributes") ||
            !strcmp(de->d_name, "strings"))
            continue;
        char p[PATH_MAX];
        snprintf(p, sizeof(p), "%s/%s", configdir, de->d_name);
        if (unlink(p) == 0)
            LOGI("cleared config link %s", p);
    }
    closedir(d);
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
    struct stat st;
    snprintf(dev, sizeof(dev), "/dev/usb-ffs/%s", name);
    snprintf(chk, sizeof(chk), "%s/ep0", dev);
    /* Real functionfs exposes ep0 as a file (chardev on AOSP, regular
     * file on some Samsung/QCOM builds). A leftover empty *directory*
     * from a failed mount must not short-circuit us. */
    if (stat(chk, &st) == 0 && !S_ISDIR(st.st_mode))
        return 0; /* somebody else mounted it (e.g. vendor init) */
    if (stat(chk, &st) == 0 && S_ISDIR(st.st_mode)) {
        LOGI("removing stale dir %s before functionfs mount", chk);
        rmdir(chk);
    }
    if (mkdir("/dev/usb-ffs", 0755) != 0 && errno != EEXIST) {
        LOGE("mkdir /dev/usb-ffs: %s", strerror(errno));
        return -1;
    }
    if (stat(dev, &st) == 0 && S_ISDIR(st.st_mode)) {
        /* If a previous failed run left an unmounted dir, keep it for mount. */
    } else if (mkdir(dev, 0550) != 0 && errno != EEXIST) {
        LOGE("mkdir %s: %s (SELinux?)", dev, strerror(errno));
        return -1;
    }
#ifdef __linux__
    /* Samsung / QCOM: empty options (like stock adb/mtp/ptp mounts). The
     * rmode/uid= options used by some AOSP HALs return EINVAL here. */
    if (mount(name, dev, "functionfs", 0, NULL) != 0) {
        /* Fallback: AOSP-style mode string */
        if (mount(name, dev, "functionfs", 0,
                  "rmode=0550,fmode=0660,uid=0,gid=0") != 0) {
            LOGE("mount functionfs %s @ %s: %s", name, dev, strerror(errno));
            return -1;
        }
    }
#else
    LOGI("[host] skip mount: functionfs %s at %s", name, dev);
#endif
    if (stat(chk, &st) != 0 || S_ISDIR(st.st_mode)) {
        LOGE("functionfs mount %s produced no ep0 node", name);
        umount2(dev, MNT_DETACH);
        return -1;
    }
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
    char ep0path[PATH_MAX];
    snprintf(ep0path, sizeof(ep0path), "%s/ep0", dev);
    int ep0 = open(ep0path, O_RDWR | O_CLOEXEC);
    if (ep0 < 0) {
        LOGE("open %s failed: %s (functionfs not mounted / SELinux?)",
             ep0path, strerror(errno));
        return -1;
    }

    /* ---- descriptors (V2: 20B header = magic/len/flags/fs/hs) ----
     * usbmux 2-ep → 66B; PTP 3-ep → 80B. Keep headroom. */
    uint8_t dbuf[256];
    memset(dbuf, 0, sizeof(dbuf));
    uint32_t *h = (uint32_t *)dbuf;
    h[0] = 3;                    /* FUNCTIONFS_DESCRIPTORS_MAGIC_V2 */
    h[1] = 0;                    /* length, fixed below */
    /* ENUM_SAFE: no VIRTUAL_ADDR (autoconfig EPs). Keep ALL_CTRL_RECIP so
     * Apple hosts get 0x45 before SET_CONFIGURATION — Mac shows 05AC but
     * stays addressed when vendor requests STALL. Avoid VIRTUAL_ADDR: that
     * combo previously bounced Flip4 addressed→default. */
#if AAD_ENUM_SAFE
    h[2] = FF_HAS_FS_DESC | FF_HAS_HS_DESC |
           (flags_ctrl ? FF_ALL_CTRL_RECIP : 0);
#else
    h[2] = FF_HAS_FS_DESC | FF_HAS_HS_DESC | FF_VIRTUAL_ADDR |
           (flags_ctrl ? FF_ALL_CTRL_RECIP : 0);
#endif
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
        /* ffs string table is local to this function — index 1 = first string.
         * ENUM_SAFE: leave 0 so a failed/empty string table cannot make the
         * host abort while fetching iInterface during configuration. */
#if AAD_ENUM_SAFE
        i->iInterface = 0;
#else
        i->iInterface = ifstr && ifstr[0] ? 1 : 0;
#endif
        (void)ifid;
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
    LOGI("ffs %s: writing descriptors len=%u fs=%u hs=%u flags=0x%x enum_safe=%d",
         name, (unsigned)h[1], (unsigned)h[3], (unsigned)h[4], (unsigned)h[2],
         AAD_ENUM_SAFE);
    ssize_t w = write(ep0, dbuf, p - dbuf);
    if (w != (ssize_t)(p - dbuf)) {
        LOGE("ffs %s: descriptor write failed w=%zd want=%u: %s (errno=%d)",
             name, w, (unsigned)(p - dbuf), strerror(errno), (int)errno);
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
    if (ilen > 64) ilen = 64;
    memcpy(sbuf + 18, ifstr, ilen);   /* + the NUL from memset */
    /* Some QCOM kernels require the payload length 4-byte aligned. */
    size_t slen = 16 + 2 + ilen + 1;
    slen = (slen + 3u) & ~3u;
    sh[1] = (uint32_t)slen;
    w = write(ep0, sbuf, slen);
    if (w != (ssize_t)slen) {
        /* Fallback: empty string table (still valid; iInterface may be 0). */
        LOGE("ffs %s: strings write failed (%s) — retry empty table",
             name, strerror(errno));
        memset(sbuf, 0, sizeof(sbuf));
        sh[0] = 2;
        sh[1] = 16;
        sh[2] = 0;
        sh[3] = 0;
        w = write(ep0, sbuf, 16);
        if (w != 16) {
            LOGE("ffs %s: empty strings also failed: %s", name, strerror(errno));
            close(ep0);
            /* Tear down this half-ready ffs instance so UDC bind is not ENODEV. */
            umount2(dev, MNT_DETACH);
            rmdir(dev);
            aad_rmrfdirs(dir);
            return -1;
        }
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

    /* ---- open declared endpoints (created after the strings write).
     * VIRTUAL_ADDR => ep%02x of the FULL bEndpointAddress.
     * Autoconfig (ENUM_SAFE) => ep0 already used; data eps are ep1..epN
     * in declaration order (kernel ffs_epfiles_create). ---- */
    for (int e = 0; e < neps; e++) {
        char ep[64];
#if AAD_ENUM_SAFE
        snprintf(ep, sizeof(ep), "%s/ep%d", dev, e + 1);
#else
        snprintf(ep, sizeof(ep), "%s/ep%02x", dev, eps[e * 2]);
#endif
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

static int gadget_udc_holder(const aad_gadget *g, char *buf, size_t len)
{
    char path[PATH_MAX];
    snprintf(path, sizeof(path), "%s/UDC", g->base);
    return aad_sysfs_read_str(path, buf, len);
}

void aad_gadget_unbind(aad_gadget *g)
{
    if (!g) return;
    char path[PATH_MAX];
    snprintf(path, sizeof(path), "%s/UDC", g->base);
    /* "\n" → empty name after kernel strips newline = unbind */
    if (aad_sysfs_write(path, "") != 0)
        LOGE("unbind %s failed", path);
    g->bound = 0;
}

int aad_gadget_bind(aad_gadget *g)
{
    if (!g) return -1;
    char cur[64] = {0};
    if (gadget_udc_holder(g, cur, sizeof(cur)) == 0 &&
        cur[0] && !strcmp(cur, g->udc)) {
        g->bound = 1;
        return 0;
    }
    char path[PATH_MAX];
    snprintf(path, sizeof(path), "%s/UDC", g->base);
    if (aad_sysfs_write(path, g->udc) != 0) {
        g->bound = 0;
        return -1;
    }
    g->bound = 1;
    return 0;
}

int aad_gadget_ensure_bound(aad_gadget *g)
{
    if (!g) return -1;
    char cur[64] = {0};
    if (gadget_udc_holder(g, cur, sizeof(cur)) == 0 &&
        cur[0] && !strcmp(cur, g->udc)) {
        g->bound = 1;
        return 0;
    }
    /* Sysfs says unbound (or wrong UDC) — force rebind even if flag lied. */
    g->bound = 0;
    return aad_gadget_bind(g);
}

int aad_gadget_reenum_pulse(aad_gadget *g)
{
    if (!g) return -1;
    /* Mac usbmuxd / CarPlay Simulator need a real detach (≥1s). A 300ms
     * blip is often invisible; defend_udc reclaim made it worse (~100ms). */
    LOGI("reenum pulse: unbind → 1500ms → bind (force host re-attach)");
    aad_gadget_unbind(g);
    usleep(1500 * 1000);
    if (aad_gadget_bind(g) != 0) {
        LOGE("reenum pulse: rebind failed");
        return -1;
    }
    return 0;
}

int aad_gadget_promote_ncm(aad_gadget *g)
{
    /* FN_* live later in this file; use literals here to avoid forward decl. */
    static const char *fn_ncm = "functions/ncm.0";
    if (!g) return -1;
    if (g->carplay_layout) {
        LOGI("promote_ncm: already carplay layout");
        return 0;
    }
    char cfg[PATH_MAX], ncm_dir[PATH_MAX];
    snprintf(cfg, sizeof(cfg), "%s/configs/b.1", g->base);
    snprintf(ncm_dir, sizeof(ncm_dir), "%s/%s", g->base, fn_ncm);

    aad_gadget_unbind(g);
    if (access(ncm_dir, F_OK) != 0) {
        if (mkdirs(ncm_dir) != 0) {
            LOGE("promote_ncm: mkdir %s failed: %s", ncm_dir, strerror(errno));
            (void)aad_gadget_bind(g);
            return -1;
        }
    }
    wf(ncm_dir, "dev_addr", "52:54:00:a1:b2:c3");
    wf(ncm_dir, "host_addr", "52:54:00:a1:b2:c4");
    wf(ncm_dir, "qmult", "5");
    if (symlink_fn_as(g->base, cfg, fn_ncm, "f2") != 0) {
        LOGE("promote_ncm: link f2=ncm.0 failed");
        (void)aad_gadget_bind(g);
        return -1;
    }
    g->carplay_layout = 1;
    if (aad_gadget_bind(g) != 0) {
        LOGE("promote_ncm: rebind failed");
        return -1;
    }
    LOGI("promote_ncm: b.1/f2=ncm.0 (usbmux FFS kept)");
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
    if (g->hijack_g1) {
        /* Leave g1 itself; only drop our links + ffs.usbmux instance.
         * restore_vendor() (setprop mtp,adb) rebuilds stock composition. */
        char cfg[PATH_MAX], fn[PATH_MAX], sp[PATH_MAX];
        snprintf(cfg, sizeof(cfg), "%s/configs/b.1", g->base);
        clear_config_links(cfg);
        snprintf(fn, sizeof(fn), "%s/functions/ffs.usbmux", g->base);
        aad_rmrfdirs(fn);
        if (g->saved_vid[0])
            wf(g->base, "idVendor", g->saved_vid);
        if (g->saved_pid[0])
            wf(g->base, "idProduct", g->saved_pid);
        snprintf(sp, sizeof(sp), "%s/strings/0x409", g->base);
        if (g->saved_manuf[0])
            wf(sp, "manufacturer", g->saved_manuf);
        if (g->saved_prod[0])
            wf(sp, "product", g->saved_prod);
        if (g->saved_serial[0])
            wf(sp, "serialnumber", g->saved_serial);
        LOGI("g1 hijack torn down (ids/strings restored; HAL will rebuild links)");
    } else {
        aad_rmrfdirs(g->base);
    }
    free(g);
}

static int build_config(aad_gadget *g, int cfg, const char *const *fns, int nfns)
{
    char dir[PATH_MAX];
    int linked = 0;
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
    for (int i = 0; i < nfns; i++) {
        char abs[PATH_MAX];
        snprintf(abs, sizeof(abs), "%s/%s", g->base, fns[i]);
        if (access(abs, F_OK) != 0) {
            LOGI("config b.%d skip missing %s", cfg, fns[i]);
            continue;
        }
        if (symlink_fn(g->base, dir, fns[i]) != 0)
            return -1;
        linked++;
    }
    /* Empty configs make UDC bind return EINVAL on Samsung 5.10. Drop them. */
    if (linked == 0) {
        LOGI("config b.%d empty — removing (would EINVAL UDC bind)", cfg);
        rmdir(dir);
        return 0;
    }
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
    /* Samsung Flip4: only stock gadget "g1" reaches Mac configured
     * (usb_notify / phy ICL). Hijack g1 when present; else own aadusb. */
    {
        struct stat st;
        if (stat("/config/usb_gadget/g1", &st) == 0 && S_ISDIR(st.st_mode)) {
            snprintf(g->base, sizeof(g->base), "/config/usb_gadget/g1");
            g->hijack_g1 = 1;
        } else {
            snprintf(g->base, sizeof(g->base), "/config/usb_gadget/aadusb");
            g->hijack_g1 = 0;
        }
    }
    snprintf(g->udc, sizeof(g->udc), "%s", udc);
    g->carplay_layout = carplay_layout;
    /* pre-close */
    g->ep0_usbmux = g->ep0_ptp = g->ep0_ptp2 = g->ep0_ae = -1;
    g->ep_usb_out = g->ep_usb_in = -1;
    g->ep_ae_out = g->ep_ae_in = -1;
    g->ep_ptp_out = g->ep_ptp_in = g->ep_ptp_int = -1;
    g->ep_ptp2_out = g->ep_ptp2_in = g->ep_ptp2_int = -1;

    if (g->hijack_g1) {
        {
            char p[PATH_MAX];
            snprintf(p, sizeof(p), "%s/idVendor", g->base);
            aad_sysfs_read_str(p, g->saved_vid, sizeof(g->saved_vid));
            snprintf(p, sizeof(p), "%s/idProduct", g->base);
            aad_sysfs_read_str(p, g->saved_pid, sizeof(g->saved_pid));
            snprintf(p, sizeof(p), "%s/strings/0x409/manufacturer", g->base);
            aad_sysfs_read_str(p, g->saved_manuf, sizeof(g->saved_manuf));
            snprintf(p, sizeof(p), "%s/strings/0x409/product", g->base);
            aad_sysfs_read_str(p, g->saved_prod, sizeof(g->saved_prod));
            snprintf(p, sizeof(p), "%s/strings/0x409/serialnumber", g->base);
            aad_sysfs_read_str(p, g->saved_serial, sizeof(g->saved_serial));
        }
        LOGI("hijacking stock gadget %s (saved vid=%s pid=%s manuf='%s' prod='%s')",
             g->base, g->saved_vid, g->saved_pid, g->saved_manuf, g->saved_prod);
        /* Caller must already have released UDC (sys.usb.config=none). */
        char cfg[PATH_MAX];
        snprintf(cfg, sizeof(cfg), "%s/configs/b.1", g->base);
        clear_config_links(cfg);
        /* Drop os_desc → b.1 link; restitch after we rebuild. */
        {
            char os[PATH_MAX];
            snprintf(os, sizeof(os), "%s/os_desc/b.1", g->base);
            unlink(os);
        }
    } else {
        aad_rmrfdirs(g->base);
        if (mkdirs(g->base) != 0) { aad_gadget_destroy(g); return -1; }
    }

    /* device identity */
    {
        unsigned vid = g_ovr_vid ? g_ovr_vid : AAD_VID;
        unsigned pid = g_ovr_pid ? g_ovr_pid : AAD_PID;
        char vbuf[16], pbuf[16];
        snprintf(vbuf, sizeof(vbuf), "0x%04x", vid);
        snprintf(pbuf, sizeof(pbuf), "0x%04x", pid);
        LOGI("device IDs: vid=%s pid=%s%s hijack=%d", vbuf, pbuf,
             g_ovr_vid ? " (override)" : "", g->hijack_g1);
        if (wf_layout(g->base, "idVendor", "ID/vendor", vbuf) ||
            wf_layout(g->base, "idProduct", "ID/product", pbuf)) {
            LOGE("failed to write device ID");
            aad_gadget_destroy(g);
            return -1;
        }
        /* Keep stock bcdDevice on g1 when present; only set on fresh gadget. */
        if (!g->hijack_g1)
            wf_layout(g->base, "bcdDevice", "ID/device", "0x1704");
    }
    if (!g->hijack_g1) {
        wf(g->base, "bcdUSB", "0x0200");
        wf(g->base, "bMaxPacketSize0", "0x40");
        wf(g->base, "bDeviceClass", "0x00");
        wf(g->base, "bDeviceSubClass", "0x00");
        wf(g->base, "bDeviceProtocol", "0x00");
        wf(g->base, "max_speed", "high-speed");
    }
    /* strings */
    char sp[PATH_MAX];
    snprintf(sp, sizeof(sp), "%s/strings/0x409", g->base);
    mkdirs(sp);
    if (g_ovr_vid && g_ovr_vid != AAD_VID) {
        wf(sp, "manufacturer", "AADisplay");
        wf(sp, "product", "aad-usb");
        wf_layout(sp, "serialnumber", "serial", "AADUSB0001");
    } else {
        wf(sp, "manufacturer", AAD_MANUF);
        wf(sp, "product", AAD_PRODUCT);
        wf_layout(sp, "serialnumber", "serial", AAD_UDID);
    }

    /* usbmux ffs (0x45/0x52 + lockdown). Instance name collision-safe vs
     * stock ffs.adb/mtp/ptp. */
    static const uint8_t ep_usbmux[] = { USBMUX_EP_OUT, 0, USBMUX_EP_IN, 0 };
    int fds_usb[2];
    if (install_ffs(g, "usbmux", USBMUX_CLASS, USBMUX_SUBCLASS, USBMUX_PROTOCOL,
                    USBMUX_STR, 4, ep_usbmux, 2, 1, USBMUX_WMAXPKT,
                    &g->ep0_usbmux, fds_usb) != 0) {
        aad_gadget_destroy(g);
        return -1;
    }
    g->ep_usb_out = fds_usb[0]; g->ep_usb_in = fds_usb[1];
    /* Initial (mode 3): usbmux only — real iPhone boots here; HU probes
     * 0x45 then 0x52 before CarPlay composite. Jumping straight to
     * usbmux+NCM works on Mac but leaves some HUs stuck in UDC default.
     * CarPlay (mode 5): usbmux + NCM (iOS17+ cfg6 shape; AE skipped). */
    LOGI("layout=%s: usbmux on %s (PTP/AE skipped)",
         carplay_layout ? "carplay" : "initial", g->base);

    /* Wire configs/b.1. Samsung expects link names f1/f2… .
     * Stock Flip4 already has functions/ncm.0 from the USB HAL. */
    {
        char cfg[PATH_MAX], ncm_dir[PATH_MAX];
        snprintf(cfg, sizeof(cfg), "%s/configs/b.1", g->base);
        snprintf(ncm_dir, sizeof(ncm_dir), "%s/%s", g->base, FN_NCM);
        int want_ncm = carplay_layout;
        if (want_ncm && access(ncm_dir, F_OK) != 0) {
            if (mkdirs(ncm_dir) != 0)
                LOGI("ncm.0 mkdir failed (will skip NCM): %s", strerror(errno));
        }
        if (want_ncm && access(ncm_dir, F_OK) == 0) {
            /* Stable LAA MACs so Mac/phone both see a consistent link-local
             * pair across ARM cycles (kernel still may rewrite ifname). */
            wf(ncm_dir, "dev_addr", "52:54:00:a1:b2:c3");
            wf(ncm_dir, "host_addr", "52:54:00:a1:b2:c4");
            wf(ncm_dir, "qmult", "5");
        }
        if (g->hijack_g1) {
            mkdirs(cfg);
            wf(cfg, "bmAttributes", "0x80");
            wf(cfg, "MaxPower", "900");
            if (symlink_fn_as(g->base, cfg, FN_USBMUX, "f1") != 0)
                goto fail;
            int have_ncm = 0;
            if (want_ncm && access(ncm_dir, F_OK) == 0 &&
                symlink_fn_as(g->base, cfg, FN_NCM, "f2") == 0)
                have_ncm = 1;
            {
                char os[PATH_MAX];
                snprintf(os, sizeof(os), "%s/os_desc/b.1", g->base);
                symlink(cfg, os); /* best-effort; EEXIST ok */
            }
            LOGI("g1 configs: b.1/f1=usbmux f2=%s",
                 have_ncm ? "ncm.0" : "(none)");
        } else {
            if (want_ncm && access(ncm_dir, F_OK) == 0) {
                if (build_config(g, 1,
                                 (const char *const[]){ FN_USBMUX, FN_NCM }, 2))
                    goto fail;
                LOGI("aadusb configs: b.1=usbmux+ncm");
            } else {
                if (build_config(g, 1, (const char *const[]){ FN_USBMUX }, 1))
                    goto fail;
                LOGI("aadusb configs: b.1=usbmux only%s",
                     want_ncm ? " (no ncm.0)" : " (initial)");
            }
        }
    }
    (void)FN_PTP; (void)FN_PTP2; (void)FN_AE;

    LOGI("gadget %s ready (unbound, hijack=%d, udc=%s)",
         g->base, g->hijack_g1, udc);
    *out = g;
    return 0;

fail:
    aad_gadget_destroy(g);
    return -1;
}
