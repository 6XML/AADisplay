/*
 * ffs.c — FunctionFS control (ep0) handlers.
 *
 * ep0 protocol (kernel f_fs.c, verified):
 *   read() 12 bytes = { struct usb_ctrlrequest(8); u8 type; u8 pad[3]; }
 *   IN  request: write(data <= wLength) then write(1 byte, 0x80)  [status]
 *   OUT request: read(wLength) then write(1 byte, 0x00)           [status]
 *   every request MUST be completed (the kernel cannot stall a
 *   user-routed request from userspace) — unknowns get zero-data/status.
 *
 * Requests reaching the usbmux ffs ep0 (FUNCTIONFS_ALL_CTRL_RECIP set):
 *   - device-level vendor 0x45 (GET_MODE, wLen=4)  -> 4 mode bytes
 *   - device-level vendor 0x52 (SET_MODE, wLen=1)  -> 1 byte ack, re-enumerate
 *   - device-level vendor 0x53 (AOA)               -> "no AOA" (zero data)
 *   - interface/endpoint requests on our functions -> zero/status (never hang)
 */
#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <poll.h>
#if defined(__linux__)
#include <endian.h>
#elif defined(__APPLE__)
#include <libkern/OSByteOrder.h>
#define htole16(x) OSSwapHostToLittleInt16(x)
#define le16toh(x) OSSwapLittleToHostInt16(x)
#define le32toh(x) OSSwapLittleToHostInt32(x)
#endif

#include "aad.h"

/* usb_ctrlrequest (uapi ch9.h) */
struct usb_ctrlrequest {
    uint8_t bmRequestType;
    uint8_t bRequest;
    uint16_t wValue;
    uint16_t wIndex;
    uint16_t wLength;
} __attribute__((packed));

struct ffs_event {
    struct usb_ctrlrequest setup;
    uint8_t type;
    uint8_t pad[3];
} __attribute__((packed));

#define FFS_SETUP 5 /* FUNCTIONFS_SETUP */
#define USB_DIR_IN 0x80

struct aad_ffs_ctl {
    aad_gadget *g;
    int ep0_usbmux;
    int ep0_ptp, ep0_ptp2, ep0_ae;
    aad_mode_cb cb;
    void *user;
    int mode;          /* byte0 of the 0x45 answer */
    int run;
    pthread_t th_ctl, th_eps;
    /* re-enum request (set by 0x52, executed on the main thread) */
    volatile int reenum_pending;
    volatile int reenum_mode;
};

/* FunctionFS ep0 completion (kernel f_fs.c):
 *   IN  (host←device): write(data) — UDC does status; wLength==0 → write(0)
 *   OUT (host→device): read(data)  — opposite-direction I/O stalls
 * The old "write 0x80/0x00 status byte" protocol is gadgetfs, not ffs. */
static void answer_in(int ep0, int wlen)
{
    if (wlen <= 0) {
        ssize_t r = write(ep0, "", 0);
        (void)r;
        return;
    }
    int left = wlen;
    while (left > 0) {
        int chunk = left > 4096 ? 4096 : left;
        unsigned char b[4096] = {0};
        ssize_t r = write(ep0, b, chunk);
        if (r <= 0) break;
        left -= (int)r;
    }
}

static void answer_out(int ep0, int wlen)
{
    if (wlen > 0) {
        unsigned char b[4096];
        int left = wlen;
        while (left > 0) {
            int chunk = left > 4096 ? 4096 : left;
            ssize_t r = read(ep0, b, chunk);
            if (r <= 0) break;
            left -= (int)r;
        }
    } else {
        /* delayed status for zero-length OUT */
        unsigned char st = 0;
        ssize_t r = write(ep0, &st, 0);
        (void)r;
    }
}

/* usbmux ep0 thread: the interesting requests live here */
static void *usbmux_ep0_thread(void *arg)
{
    aad_ffs_ctl *ctl = arg;
    int ep0 = ctl->ep0_usbmux;
    unsigned char modebytes[4];
    while (ctl->run) {
        struct ffs_event ev;
        ssize_t n = read(ep0, &ev, sizeof(ev));
        if (n < 0) {
            if (errno == EINTR) continue;
            break; /* ep0 closed (gadget teardown) */
        }
        if (n == 0) continue;   /* spurious stall-via-read; kernel did the rest */
        if (n != (ssize_t)sizeof(ev)) continue; /* defensive; events are 12B */
        if (ev.type != FFS_SETUP) {
            if (ev.type == 2 /* ENABLE */ || ev.type == 3 /* DISABLE */) {
                LOGI("ffs usbmux ep0 event type=%u", (unsigned)ev.type);
                /* DISABLE (3) = bus reset / UDC unbind. Sticky mux v2 framing
                 * after REENUM made Mac DeviceList empty — reset stream. */
                if (ev.type == 3)
                    aad_mux_bus_reset();
            }
            continue;
        }
        uint8_t rt = ev.setup.bmRequestType;
        uint8_t req = ev.setup.bRequest;
        uint16_t wval = le16toh(ev.setup.wValue);
        uint16_t widx = le16toh(ev.setup.wIndex);
        uint16_t wlen = le16toh(ev.setup.wLength);
        LOGI("ffs SETUP rt=0x%02x req=0x%02x wValue=0x%04x wIndex=0x%04x wLen=%u",
             rt, req, wval, widx, wlen);

        /* ALL_CTRL_RECIP may deliver a few standard requests. Complete
         * SET_CONFIGURATION / SET_INTERFACE status only — never fabricate
         * GET_DESCRIPTOR payloads (that would break enum). */
        if ((rt & 0x60) == 0x00) { /* USB_TYPE_STANDARD */
            if (req == 0x09 /* SET_CONFIGURATION */ ||
                req == 0x0b /* SET_INTERFACE */) {
                if (rt & USB_DIR_IN)
                    answer_in(ep0, wlen);
                else
                    answer_out(ep0, wlen);
                LOGI("ffs completed standard req=0x%02x (delayed status)", req);
                continue;
            }
            /* GET_DESCRIPTOR etc. must stay in composite — stall if misrouted. */
            if (rt & USB_DIR_IN) {
                unsigned char dummy;
                read(ep0, &dummy, 1);
            } else {
                answer_out(ep0, wlen);
            }
            LOGE("ffs STALL unexpected standard req=0x%02x rt=0x%02x", req, rt);
            continue;
        }

        if ((rt & 0x1f) == 0 && req == APPLE_GET_MODE && wlen == 4) {
            modebytes[0] = (unsigned char)ctl->mode;
            modebytes[1] = 3; modebytes[2] = 3; modebytes[3] = 0;
            if (write(ep0, modebytes, 4) != 4)
                LOGE("0x45 write failed: %s", strerror(errno));
            else
                LOGI("0x45 -> %u:%u:%u:%u", modebytes[0], modebytes[1],
                     modebytes[2], modebytes[3]);
            continue;
        }
        if ((rt & 0x1f) == 0 && req == APPLE_SET_MODE) {
            /*
             * 0x52 is VENDOR|DEVICE|IN (0xC0) on real iPhones (DiPlay
             * IphoneUsbHost: controlTransfer(0xC0, 0x52, 0, 4, buf, 1)):
             * the DEVICE returns one byte: 0 = accepted (device then
             * re-enumerates), request stalled = rejected (iOS 12/15 quirk,
             * DiPlay #509/#455/#100). Hosts send wIndex = desired mode:
             * 1..3 = initial class, 4..6 = CarPlay class (DiPlay idx 4;
             * LIVI-style hosts may select config 6 directly).
             */
            int wi = (int)widx;
            int target = (wi >= 4 && wi <= 6) ? MODE_CARPLAY
                       : (wi >= 1 && wi <= 3) ? MODE_INITIAL
                       : -1;
            if (rt & USB_DIR_IN) {
                if (target >= 0) {
                    unsigned char ack = 0;
                    if (wlen >= 1) {
                        if (write(ep0, &ack, 1) == 1) {
                            LOGI("0x52 wIndex=%d accepted -> re-enumerate (layout=%s)",
                                 wi, target == MODE_CARPLAY ? "carplay" : "initial");
                            ctl->reenum_mode = target;
                            ctl->reenum_pending = 1;
                        }
                    } else {
                        answer_in(ep0, 0);
                        ctl->reenum_mode = target;
                        ctl->reenum_pending = 1;
                    }
                } else {
                    /* reject = STALL. ffs contract: a read() while an IN
                     * request is pending makes the kernel stall it. */
                    unsigned char dummy;
                    read(ep0, &dummy, 1);
                    LOGI("0x52 wIndex=%d rejected (stall)", wi);
                }
            } else {
                /* defensive: some host stacks may use OUT — accept write,
                 * same re-enumeration semantics */
                unsigned char d = 0;
                if (wlen >= 1) read(ep0, &d, 1);
                else answer_out(ep0, 0);
                if (target >= 0) {
                    LOGI("0x52(OUT) wIndex=%d accepted -> re-enumerate", wi);
                    ctl->reenum_mode = target;
                    ctl->reenum_pending = 1;
                }
            }
            continue;
        }
        /* AOA + unknowns: complete (never hang the control pipe). */
        if (rt & USB_DIR_IN)
            answer_in(ep0, wlen);
        else
            answer_out(ep0, wlen);
    }
    return NULL;
}

/* generic ep0 responder for the non-usbmux ffs functions */
static void *eps_thread(void *arg)
{
    aad_ffs_ctl *ctl = arg;
    struct pollfd pfd[4] = {
        { ctl->ep0_ptp,  POLLIN, 0 },
        { ctl->ep0_ptp2, POLLIN, 0 },
        { ctl->ep0_ae,   POLLIN, 0 },
    };
    while (ctl->run) {
        /* drop closed fds */
        int np = 0;
        for (int i = 0; i < 3; i++)
            if (pfd[i].fd >= 0) pfd[np++] = pfd[i];
        if (np == 0) break;
        int pr = poll(pfd, np, 500);
        if (pr <= 0) continue;
        for (int i = 0; i < np; i++) {
            if (!(pfd[i].revents & POLLIN)) continue;
            struct ffs_event ev;
            ssize_t n = read(pfd[i].fd, &ev, sizeof(ev));
            if (n < 0) {
                if (errno == EINTR) continue;
                pfd[i].fd = -1; /* fd closed (teardown) */
                continue;
            }
            if (n != (ssize_t)sizeof(ev)) continue;
            if (ev.type != FFS_SETUP) continue;
            uint16_t wlen = le16toh(ev.setup.wLength);
            if (ev.setup.bmRequestType & USB_DIR_IN)
                answer_in(pfd[i].fd, wlen);
            else
                answer_out(pfd[i].fd, wlen);
        }
    }
    return NULL;
}

int aad_ffs_ctl_start(aad_gadget *g, int ep0_fd, aad_mode_cb cb, void *user, aad_ffs_ctl **out)
{
    aad_ffs_ctl *ctl = calloc(1, sizeof(*ctl));
    if (!ctl) return -1;
    ctl->g = g;
    ctl->ep0_usbmux = ep0_fd;
    /* re-open the other ep0 fds (we need our own handles per thread);
     * -1 fds are dropped by the poll loop */
    char d[128];
    snprintf(d, sizeof(d), "/dev/usb-ffs/aadptp");
    ctl->ep0_ptp = open(d, O_RDWR | O_CLOEXEC);
    snprintf(d, sizeof(d), "/dev/usb-ffs/aadptp2");
    ctl->ep0_ptp2 = open(d, O_RDWR | O_CLOEXEC);
    snprintf(d, sizeof(d), "/dev/usb-ffs/aadeth");
    ctl->ep0_ae = open(d, O_RDWR | O_CLOEXEC);
    ctl->cb = cb;
    ctl->user = user;
    ctl->mode = MODE_CARPLAY;
    ctl->run = 1;
    if (pthread_create(&ctl->th_ctl, NULL, usbmux_ep0_thread, ctl) != 0) { free(ctl); return -1; }
    if (pthread_create(&ctl->th_eps, NULL, eps_thread, ctl) != 0) {
        ctl->run = 0;
        pthread_join(ctl->th_ctl, NULL);
        free(ctl);
        return -1;
    }
    *out = ctl;
    return 0;
}

void aad_ffs_ctl_set_mode(aad_ffs_ctl *ctl, int mode)
{
    if (ctl) ctl->mode = mode;
}

void aad_ffs_ctl_stop(aad_ffs_ctl *ctl)
{
    if (!ctl) return;
    ctl->run = 0;
    pthread_join(ctl->th_ctl, NULL);
    pthread_join(ctl->th_eps, NULL);
    close(ctl->ep0_ptp);
    close(ctl->ep0_ptp2);
    close(ctl->ep0_ae);
    free(ctl);
}

/* polled from the main watchdog: returns the 0x52 mode that wants re-enum */
int aad_ffs_ctl_take_reenum(aad_ffs_ctl *ctl)
{
    if (ctl && ctl->reenum_pending) {
        ctl->reenum_pending = 0;
        return ctl->reenum_mode;
    }
    return -1;
}
