/* util.c — sysfs / fs / logging helpers for the aad-usb daemon. */
#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdarg.h>
#include <stdint.h>
#include <unistd.h>
#include <errno.h>
#include <time.h>
#include <dirent.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <sys/syscall.h>

#ifdef __ANDROID__
#include <android/log.h>
#endif

#include "aad.h"

static int g_verbose = 1;

void aad_log(int level, const char *fmt, ...)
{
    if (level == 0 && !g_verbose) return;
    char msg[2048];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(msg, sizeof(msg), fmt, ap);
    va_end(ap);
#ifdef __ANDROID__
    static const int prio[] = { ANDROID_LOG_VERBOSE, ANDROID_LOG_DEBUG, ANDROID_LOG_INFO, ANDROID_LOG_ERROR };
    __android_log_print(prio[level <= 3 ? level : 3], "AAD_usb", "%s", msg);
#else
    static const char *tag[] = { "V", "D", "I", "E" };
    fprintf(stderr, "[aad-usb %s] %s\n", tag[level <= 3 ? level : 3], msg);
    fflush(stderr);
#endif
}

const char *aad_stage_name(int stage)
{
    switch (stage) {
    case STG_IDLE:      return "idle";
    case STG_ARMED:     return "armed";
    case STG_TAKING:    return "taking";
    case STG_PRESENT:   return "present";
    case STG_USBMUX:    return "usbmux";
    case STG_LOCKDOWN:  return "lockdown";
    case STG_CARKIT:    return "carkit";
    case STG_RESTORING: return "restoring";
    case STG_ERROR:     return "error";
    default:            return "?";
    }
}

int aad_sysfs_write(const char *path, const char *value)
{
    int fd = open(path, O_WRONLY | O_CLOEXEC);
    if (fd < 0) {
        LOGE("sysfs write open failed: %s (%s)", path, strerror(errno));
        return -1;
    }
    /* configfs UDC unbind: kernel store strips a trailing newline then
     * treats an empty name as unbind. A 0-byte write never reaches store
     * usefully on some kernels — always send at least "\n". */
    const char *payload = value;
    size_t n = strlen(value);
    if (n == 0) {
        payload = "\n";
        n = 1;
    }
    ssize_t r = write(fd, payload, n);
    int rc = (r == (ssize_t)n) ? 0 : -1;
    if (rc) LOGE("sysfs write failed: %s (%s)", path, strerror(errno));
    close(fd);
    return rc;
}

int aad_sysfs_read_str(const char *path, char *buf, size_t len)
{
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return -1;
    ssize_t r = read(fd, buf, len - 1);
    close(fd);
    if (r < 0) return -1;
    buf[r] = 0;
    /* strip trailing newlines/spaces */
    while (r > 0 && (buf[r-1] == '\n' || buf[r-1] == '\r' || buf[r-1] == ' '))
        buf[--r] = 0;
    return 0;
}

int aad_mkdirs(const char *path)
{
    char tmp[512];
    snprintf(tmp, sizeof(tmp), "%s", path);
    for (char *p = tmp + 1; *p; p++) {
        if (*p == '/') {
            *p = 0;
            if (mkdir(tmp, 0755) != 0 && errno != EEXIST) {
                LOGE("mkdirs: mkdir %s failed (%s)", tmp, strerror(errno));
                return -1;
            }
            *p = '/';
        }
    }
    if (mkdir(tmp, 0755) != 0 && errno != EEXIST) {
        LOGE("mkdirs: mkdir %s failed (%s)", tmp, strerror(errno));
        return -1;
    }
    return 0;
}

/* recursive rm -rf (single user configfs/adb dir — no symlink shenanigans expected) */
int aad_rmrf(const char *path)
{
    struct stat st;
    if (lstat(path, &st) != 0) {
        if (errno == ENOENT) return 0;
        LOGE("rmrf: lstat %s failed (%s)", path, strerror(errno));
        return -1;
    }
    if (S_ISDIR(st.st_mode)) {
        DIR *d = opendir(path);
        if (!d) {
            LOGE("rmrf: opendir %s failed (%s)", path, strerror(errno));
            return -1;
        }
        struct dirent *de;
        while ((de = readdir(d)) != NULL) {
            if (!strcmp(de->d_name, ".") || !strcmp(de->d_name, "..")) continue;
            char child[512];
            snprintf(child, sizeof(child), "%s/%s", path, de->d_name);
            if (aad_rmrf(child) != 0) {
                closedir(d);
                return -1;
            }
        }
        closedir(d);
        if (rmdir(path) != 0) {
            LOGE("rmrf: rmdir %s failed (%s)", path, strerror(errno));
            return -1;
        }
        return 0;
    }
    return unlink(path);
}

long aad_now_ms(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

void aad_random_bytes(unsigned char *buf, size_t len)
{
    int fd = open("/dev/urandom", O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        /* fallback: time-based (non-cryptographic, last resort) */
        unsigned seed = (unsigned)time(NULL) ^ (unsigned)getpid();
        for (size_t i = 0; i < len; i++) {
            seed = seed * 1103515245u + 12345u;
            buf[i] = (unsigned char)(seed >> 16);
        }
        return;
    }
    size_t done = 0;
    while (done < len) {
        ssize_t r = read(fd, buf + done, len - done);
        if (r <= 0) { close(fd); return; }
        done += (size_t)r;
    }
    close(fd);
}

unsigned aad_random_u32(void)
{
    unsigned v;
    aad_random_bytes((unsigned char *)&v, sizeof(v));
    return v;
}

int aad_probe_udc(char *buf, size_t len)
{
    DIR *d = opendir("/sys/class/udc");
    if (!d) {
        LOGE("no /sys/class/udc (kernel has no USB device controller exposed)");
        return -1;
    }
    struct dirent *de;
    char fallback[64] = {0};
    int found = -1;
    while ((de = readdir(d)) != NULL) {
        if (!strcmp(de->d_name, ".") || !strcmp(de->d_name, "..")) continue;
        /* Prefer a real SoC UDC; skip kernel dummy_udc.* used for testing. */
        if (!strncmp(de->d_name, "dummy_udc", 9)) {
            if (!fallback[0]) snprintf(fallback, sizeof(fallback), "%s", de->d_name);
            continue;
        }
        snprintf(buf, len, "%s", de->d_name);
        found = 0;
        break;
    }
    if (found != 0 && fallback[0]) {
        snprintf(buf, len, "%s", fallback);
        found = 0;
    }
    closedir(d);
    if (found == 0) LOGI("probed UDC: %s", buf);
    return found;
}

int aad_udc_driver(const char *udc, char *buf, size_t len)
{
    /* open() would FOLLOW the symlink (and fail on a dir) — readlink + basename */
    char path[256], link[512];
    buf[0] = 0;
    snprintf(path, sizeof(path), "/sys/class/udc/%s/driver", udc);
    ssize_t n = readlink(path, link, sizeof(link) - 1);
    if (n <= 0)
        return 0; /* no driver bound */
    link[n] = 0;
    const char *base = strrchr(link, '/');
    base = base ? base + 1 : link;
    snprintf(buf, len, "%s", base);
    return 0;
}

/* Which configfs gadget (if any) currently holds this UDC?
 * (configfs-bound UDCs have NO driver symlink — the holder lives in
 * /config/usb_gadget/<g>/UDC; e.g. the AOSP USB Gadget HAL's "g1") */
int aad_udc_configfs_holder(const char *udc, char *buf, size_t len)
{
    buf[0] = 0;
    DIR *d = opendir("/config/usb_gadget");
    if (!d) return 0; /* configfs not mounted / not the vendor mechanism */
    struct dirent *de;
    int found = 0;
    while ((de = readdir(d)) != NULL) {
        if (!strcmp(de->d_name, ".") || !strcmp(de->d_name, "..")) continue;
        char upath[256], val[64] = {0};
        snprintf(upath, sizeof(upath), "/config/usb_gadget/%s/UDC", de->d_name);
        if (aad_sysfs_read_str(upath, val, sizeof(val)) == 0 &&
            val[0] && !strcmp(val, udc)) {
            snprintf(buf, len, "%s", de->d_name);
            found = 1;
            break;
        }
    }
    closedir(d);
    return found;
}

/* configfs-aware recursive removal: RMDIR object dirs only, bottom-up.
 * configfs attribute files (serialnumber/MaxPower/...) are NOT unlinkable
 * (EPERM) — the kernel drops them when their object dir is rmdir'd. */
int aad_rmrfdirs(const char *path)
{
    struct stat st;
    if (lstat(path, &st) != 0)
        return (errno == ENOENT) ? 0 : -1;
    if (!S_ISDIR(st.st_mode))
        return 0; /* attribute file: leave it; the object rmdir covers it */
    DIR *d = opendir(path);
    if (!d) return -1;
    int fail = 0;
    struct dirent *de;
    while ((de = readdir(d)) != NULL) {
        if (!strcmp(de->d_name, ".") || !strcmp(de->d_name, "..")) continue;
        char child[512];
        snprintf(child, sizeof(child), "%s/%s", path, de->d_name);
        if (aad_rmrfdirs(child) != 0) fail = 1;
    }
    closedir(d);
    if (rmdir(path) != 0 && errno != ENOENT)
        fail = 1;
    return fail ? -1 : 0;
}

int aad_cable_attached(const char *udc)
{
    char path[256], state[64] = {0};
    snprintf(path, sizeof(path), "/sys/class/udc/%s/state", udc);
    if (aad_sysfs_read_str(path, state, sizeof(state)) != 0)
        return 0;
    /* state: "connected" / "configured" / "suspended" = cable present;
     * "notconfigured" / "notattached" / "" = not present (varies by driver) */
    if (!strcmp(state, "connected") || !strcmp(state, "configured") ||
        !strcmp(state, "suspended") || !strcmp(state, "connected,configured"))
        return 1;
    return 0;
}

void aad_bringup_ncm_ifaces(void)
{
    /* Prefer toybox/ip; fall back to busybox. Ignore failures — iface may
     * not exist yet if the host has not selected the NCM data altsetting.
     *
     * Also pin MAC to the configfs ncm.0/dev_addr (AAD_WIFI_MAC) when the
     * kernel kept a random address from an earlier HAL bind — identity and
     * AirPlay DeviceID expect 52:54:00:a1:b2:c3. */
    static const char *want_mac = "52:54:00:a1:b2:c3";
    static const char *names[] = { "usb0", "usb1", "ncm0", NULL };
    for (int i = 0; names[i]; i++) {
        char path[64], oper[32] = {0}, mac[32] = {0};
        snprintf(path, sizeof(path), "/sys/class/net/%s/operstate", names[i]);
        if (access(path, F_OK) != 0)
            continue;
        aad_sysfs_read_str(path, oper, sizeof(oper));
        snprintf(path, sizeof(path), "/sys/class/net/%s/address", names[i]);
        aad_sysfs_read_str(path, mac, sizeof(mac));
        int mac_ok = (mac[0] && !strcasecmp(mac, want_mac));
        if (!strcmp(oper, "up") && mac_ok)
            continue;
        char cmd[192];
        if (!mac_ok) {
            /* address change needs the iface down */
            snprintf(cmd, sizeof(cmd),
                     "ip link set %s down 2>/dev/null; "
                     "ip link set %s address %s 2>/dev/null; "
                     "ip link set %s up 2>/dev/null",
                     names[i], names[i], want_mac, names[i]);
        } else {
            snprintf(cmd, sizeof(cmd), "ip link set %s up 2>/dev/null", names[i]);
        }
        int rc = system(cmd);
        LOGI("ncm bringup %s (was '%s' mac='%s') rc=%d", names[i], oper, mac, rc);
        /* Android policy routing often parks fe80::/64 only in a per-iface
         * table; add a main-table LL route so root listeners / AirPlay dial
         * work without ConnectivityManager bind. Also accept inbound on the
         * NCM iface before OEM input_dos / fw chains. */
        /* Android installs `from all unreachable` at pref 32000. Without
         * earlier usb0/fe80 rules, SYN-ACK and AirPlay dials die as
         * "Network is unreachable" even when L2 + tcpdump look fine.
         * Keep cmd ≥768: a 192B snprintf truncated mid-token and flooded
         * daemon.log with `ip` usage text. */
        char rcmd[768];
        snprintf(rcmd, sizeof(rcmd),
                 "ip -6 route replace fe80::/64 dev %s metric 256 2>/dev/null; "
                 "ip -6 rule add to fe80::/64 lookup main pref 22000 2>/dev/null; "
                 "ip -6 rule add oif %s lookup main pref 22001 2>/dev/null; "
                 "ip -6 rule add iif %s lookup main pref 22002 2>/dev/null; "
                 "ip route replace 169.254.0.0/16 dev %s 2>/dev/null; "
                 "ip rule add to 169.254.0.0/16 lookup main pref 22000 2>/dev/null; "
                 "ip rule add oif %s lookup main pref 22001 2>/dev/null; "
                 "ip rule add iif %s lookup main pref 22002 2>/dev/null; "
                 "ip6tables -C INPUT -i %s -j ACCEPT 2>/dev/null || "
                 "ip6tables -I INPUT 1 -i %s -j ACCEPT 2>/dev/null; "
                 "iptables -C INPUT -i %s -j ACCEPT 2>/dev/null || "
                 "iptables -I INPUT 1 -i %s -j ACCEPT 2>/dev/null",
                 names[i], names[i], names[i], names[i], names[i], names[i],
                 names[i], names[i], names[i], names[i]);
        system(rcmd);
    }
}
