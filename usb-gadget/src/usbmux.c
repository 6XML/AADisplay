/*
 * usbmux.c — usbmux DEVICE side (fake iPhone), v1 + v2.
 *
 * Mirrors the host-side semantics of usbmuxd/src/device.c (GPL) inverted:
 *  - handshake: host VERSION(2.0, v1 framed) -> we answer VERSION(2.0, v1)
 *    -> host SETUP(0x07, v2 framed) -> stream ready
 *  - pseudo TCP: host dials (SYN sport=H,dport=service); we answer
 *    SYN|ACK (sport=service,dport=H); host ACKs; established.
 *    tcphdr as in usbmuxd (th_off=5, win<<8), max payload = USB_MTU-36.
 *  - v2 stream sequence numbers: per-packet tx_seq/rx_seq (16-bit wrap).
 *
 * The real iPhone does exactly this inside its usbmuxd daemon; the kernel
 * ffs endpoints carry the raw bytes.
 */
#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <unistd.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <sys/socket.h>
#include <netinet/in.h>

#include "aad.h"

/* ---- wire structures ---- */

struct mux_hdr {           /* v2 = 16 bytes; v1 uses the first 8 */
    uint32_t protocol;    /* BE */
    uint32_t length;      /* BE, includes header */
    uint32_t magic;       /* BE, v2 only (0xfeedface) */
    uint16_t tx_seq;      /* BE, v2 */
    uint16_t rx_seq;      /* BE, v2 */
} __attribute__((packed));

struct version_hdr {
    uint32_t major, minor, padding; /* BE */
} __attribute__((packed));

struct tcphdr_m {
    uint16_t th_sport, th_dport;   /* BE */
    uint32_t th_seq, th_ack;       /* BE */
    uint8_t th_off;                /* *4 */
    uint8_t th_flags;
    uint16_t th_win;               /* BE, <<8 */
    uint16_t th_sum, th_urp;
} __attribute__((packed));

#define TH_SYN 0x02
#define TH_RST 0x04
#define TH_ACK 0x10

#define CONN_MAX 64
#define CONN_BUF_MAX (256 * 1024)
#define TX_WIN 131072

enum conn_state { C_FREE, C_SYN_RCVD, C_EST, C_CLOSING };

struct mux_conn {
    int id;
    int state;
    uint16_t hsport, dport;
    uint32_t tx_seq, tx_acked;
    uint32_t rx_seq, rx_ack, rx_win;
    unsigned char *rbuf;
    uint32_t rlen, rcap;
    int closed;
    int is_bridge;
    int pair_fd;
    pthread_t pump_th;
    int pump_started;
    long last_active; /* aad_now_ms of last rx/tx (idle reaping) */
    pthread_mutex_t lock;
    pthread_cond_t cond;
};

static struct {
    int running;
    int ep_out, ep_in;
    int version;               /* 0 = pre-handshake, 1, 2 */
    int handshake;
    uint16_t stx, srx;         /* v2 stream seqs */
    pthread_mutex_t lock;
    struct mux_conn conns[CONN_MAX];
    int next_id;
    int next_bridge_port;
    pthread_t rx_th;
    int rx_started;
    unsigned char rxbig[USB_MTU];
    uint32_t rxlen;            /* pending partial packet */
    void (*on_lockdown)(int, void *);
    void (*on_bridge)(int, void *);
    void *user;
} M = {0};

static uint16_t be16(uint16_t v) { return __builtin_bswap16(v); }
static uint32_t be32(uint32_t v) { return __builtin_bswap32(v); }

/* ---------------- TX ---------------- */

static pthread_mutex_t tx_lock = PTHREAD_MUTEX_INITIALIZER;

static int send_raw(const void *buf, int len)
{
    const unsigned char *p = buf;
    while (len > 0) {
        ssize_t r = write(M.ep_out, p, len);
        if (r < 0) {
            if (errno == EINTR) continue;
            LOGE("usbmux tx failed: %s", strerror(errno));
            return -1;
        }
        p += r;
        len -= (int)r;
    }
    return 0;
}

/* proto: MUX_PROTO_*; hdrlen: 0 (VERSION/SETUP) or 20 (TCP) */
static int send_packet(int proto, const void *tcp, const void *data, int dlen)
{
    int hsize = (M.version >= MUX_V2) ? 16 : 8;
    int hdrlen = (proto == MUX_PROTO_TCP) ? (int)sizeof(struct tcphdr_m) : 0;
    int total = hsize + hdrlen + dlen;
    if (total > USB_MTU) {
        LOGE("mux packet too large (%d)", total);
        return -1;
    }
    static __thread unsigned char pkt[USB_MTU];
    struct mux_hdr *h = (struct mux_hdr *)pkt;
    memset(pkt, 0, total);
    h->protocol = be32((uint32_t)proto);
    h->length = be32((uint32_t)total);
    pthread_mutex_lock(&M.lock);
    if (M.version >= MUX_V2) {
        h->magic = be32(MUX_MAGIC_V2);
        h->tx_seq = be16(M.stx++);
        h->rx_seq = be16(M.srx);
    }
    pthread_mutex_unlock(&M.lock);
    if (tcp) memcpy(pkt + hsize, tcp, hdrlen);
    if (data && dlen) memcpy(pkt + hsize + hdrlen, data, dlen);
    pthread_mutex_lock(&tx_lock);
    int rc = send_raw(pkt, total);
    pthread_mutex_unlock(&tx_lock);
    return rc;
}

static int send_version(void)
{
    /*
     * Always v1 framing (8B header): the usbmuxd host reads the VERSION
     * response with the framing of its pre-handshake state (version==0 =>
     * 8 bytes; see device.c send_packet/process_packet: dev->version<2 ? 8).
     * A 16B v2 frame here would make the host parse its magic/seq words as
     * major/minor. After the host processes this it switches to v2 itself.
     */
    static __thread unsigned char pkt[64];
    struct mux_hdr *h = (struct mux_hdr *)pkt;
    memset(pkt, 0, 16);
    h->protocol = be32(MUX_PROTO_VERSION);
    h->length = be32(8 + 12);
    struct version_hdr *vh = (struct version_hdr *)(pkt + 8);
    vh->major = be32(2);
    vh->minor = be32(0);
    return send_raw(pkt, 8 + 12);
}

static int send_tcp(struct mux_conn *c, uint8_t flags, const void *data, int dlen)
{
    struct tcphdr_m th;
    memset(&th, 0, sizeof(th));
    th.th_sport = be16(c->dport);
    th.th_dport = be16(c->hsport);
    th.th_seq = be32(c->tx_seq);
    th.th_ack = be32(c->tx_acked ? c->tx_acked : c->tx_seq);
    th.th_flags = flags;
    th.th_off = 5;
    th.th_win = be16((uint16_t)(TX_WIN >> 8));
    return send_packet(MUX_PROTO_TCP, &th, data, dlen);
}

static int send_anon_rst(uint16_t sport, uint16_t dport, uint32_t ack)
{
    struct tcphdr_m th;
    memset(&th, 0, sizeof(th));
    th.th_sport = be16(sport);
    th.th_dport = be16(dport);
    th.th_ack = be32(ack);
    th.th_flags = TH_RST;
    th.th_off = 5;
    return send_packet(MUX_PROTO_TCP, &th, NULL, 0);
}

/* ---------------- connections ---------------- */

static struct mux_conn *conn_alloc(void)
{
    for (int i = 0; i < CONN_MAX; i++) {
        if (M.conns[i].state == C_FREE)
            return &M.conns[i];
    }
    return NULL;
}

static struct mux_conn *conn_find(uint16_t hsport, uint16_t dport)
{
    for (int i = 0; i < CONN_MAX; i++) {
        struct mux_conn *c = &M.conns[i];
        if (c->state != C_FREE && c->state != C_CLOSING &&
            c->hsport == hsport && c->dport == dport)
            return c;
    }
    return NULL;
}

static int port_registered(uint16_t port, int *is_bridge)
{
    if (port == LOCKDOWN_PORT) { *is_bridge = 0; return 1; }
    pthread_mutex_lock(&M.lock);
    int reg = (port >= 50000 && port < M.next_bridge_port);
    pthread_mutex_unlock(&M.lock);
    *is_bridge = 1;
    return reg;
}

static void conn_teardown(struct mux_conn *c, const char *reason)
{
    pthread_mutex_lock(&c->lock);
    if (c->state == C_CLOSING) { pthread_mutex_unlock(&c->lock); return; }
    c->state = C_CLOSING;
    c->closed = 1;
    pthread_cond_broadcast(&c->cond);
    pthread_mutex_unlock(&c->lock);

    if (c->is_bridge) {
        int self = 0;
        pthread_mutex_lock(&c->lock);
        /* the pump clears pump_started before calling conn_close, so a
         * self-invocation is detected by that flag, never by pthread_equal
         * on a possibly-already-joined thread */
        if (c->pump_started && pthread_equal(pthread_self(), c->pump_th))
            self = 1;
        if (!self) {
            if (c->pair_fd >= 0) close(c->pair_fd);
            c->pair_fd = -1;
            int started = c->pump_started;
            pthread_t th = c->pump_th;
            c->pump_started = 0;
            pthread_mutex_unlock(&c->lock);
            if (started)
                pthread_join(th, NULL); /* never join self */
        } else {
            pthread_mutex_unlock(&c->lock);
        }
    }
    free(c->rbuf);
    c->rbuf = NULL;
    c->rlen = 0;
    /* RST with reason text (host logs it; mirrors real device behavior) */
    if (M.running) {
        if (reason && reason[0])
            send_tcp(c, TH_RST, reason, (int)strlen(reason));
        else
            send_tcp(c, TH_RST, NULL, 0);
    }
    c->state = C_FREE;
    c->id = -1; /* never reused: id checks in read/write/conn_close */
}

int aad_mux_conn_alive(int conn_id)
{
    struct mux_conn *c = &M.conns[conn_id];
    return (c->id == conn_id) ? (c->state == C_EST) : 0;
}

void aad_mux_conn_close(int conn_id, const char *rst_reason)
{
    struct mux_conn *c = &M.conns[conn_id];
    if (c->id == conn_id && c->state != C_FREE && c->state != C_CLOSING)
        conn_teardown(c, rst_reason);
}

int aad_mux_read(int conn_id, void *buf, size_t len, int timeout_ms)
{
    struct mux_conn *c = &M.conns[conn_id];
    if (c->id != conn_id) return -1;
    pthread_mutex_lock(&c->lock);
    while (!c->closed && c->rlen == 0) {
        if (timeout_ms <= 0)
            pthread_cond_wait(&c->cond, &c->lock);
        else {
            struct timespec ts;
            clock_gettime(CLOCK_REALTIME, &ts);
            ts.tv_sec += timeout_ms / 1000;
            ts.tv_nsec += (timeout_ms % 1000) * 1000000L;
            if (ts.tv_nsec >= 1000000000L) { ts.tv_sec++; ts.tv_nsec -= 1000000000L; }
            if (pthread_cond_timedwait(&c->cond, &c->lock, &ts) == ETIMEDOUT)
                return 0;
        }
    }
    int n = (int)len;
    if (c->rlen < (uint32_t)n) n = (int)c->rlen;
    if (n > 0) {
        memcpy(buf, c->rbuf, n);
        memmove(c->rbuf, c->rbuf + n, c->rlen - n);
        c->rlen -= n;
    }
    pthread_mutex_unlock(&c->lock);
    return c->closed && n == 0 ? -1 : n;
}

int aad_mux_write(int conn_id, const void *buf, size_t len)
{
    struct mux_conn *c = &M.conns[conn_id];
    if (c->id != conn_id) return -1;
    const unsigned char *p = buf;
    size_t done = 0;
    int max_payload = USB_MTU - 16 - 20;
    while (done < len) {
        pthread_mutex_lock(&c->lock);
        if (c->closed) { pthread_mutex_unlock(&c->lock); return -1; }
        uint32_t sent = c->tx_seq - c->tx_acked;
        uint32_t sendable = (c->rx_win > sent) ? (c->rx_win - sent) : 0;
        if (sendable == 0) {
            /* wait for the host to open its window */
            struct timespec ts;
            clock_gettime(CLOCK_REALTIME, &ts);
            ts.tv_sec += 5;
            if (pthread_cond_timedwait(&c->cond, &c->lock, &ts) == ETIMEDOUT) {
                if (c->closed) { pthread_mutex_unlock(&c->lock); return -1; }
                /* window still 0 after 5s — treat as dead (host gone) */
                pthread_mutex_unlock(&c->lock);
                LOGE("mux conn %d window timeout — closing", conn_id);
                conn_teardown(c, "window timeout");
                return -1;
            }
            sent = c->tx_seq - c->tx_acked;
            sendable = (c->rx_win > sent) ? (c->rx_win - sent) : 0;
        }
        size_t chunk = len - done;
        if (chunk > sendable) chunk = (size_t)sendable;
        if (chunk > (size_t)max_payload) chunk = (size_t)max_payload;
        pthread_mutex_unlock(&c->lock);
        if (chunk == 0) { usleep(1000); continue; }
        if (send_tcp(c, TH_ACK, p + done, (int)chunk) < 0)
            return -1;
        pthread_mutex_lock(&c->lock);
        c->tx_seq += (uint32_t)chunk;
        c->tx_acked = c->tx_seq;
        c->last_active = aad_now_ms();
        pthread_mutex_unlock(&c->lock);
        done += chunk;
    }
    return (int)len;
}

/* bridge pump: pair_fd <-> mux conn */
static void *bridge_pump(void *arg)
{
    struct mux_conn *c = arg;
    int pair_fd = c->pair_fd;
    int conn_id = c->id;
    unsigned char ibuf[8192], obuf[8192];
    int from_pair = 1, from_mux = 1;
    while (from_pair || from_mux) {
        /* pair -> mux */
        if (from_pair) {
            ssize_t n = recv(pair_fd, obuf, sizeof(obuf), 0);
            if (n <= 0) from_pair = 0;
            else if (aad_mux_write(conn_id, obuf, (size_t)n) < 0)
                from_pair = 0;
        }
        /* mux -> pair */
        if (from_mux) {
            int n = aad_mux_read(conn_id, ibuf, sizeof(ibuf), 50);
            if (n > 0) {
                ssize_t r = send(pair_fd, ibuf, (size_t)n, MSG_NOSIGNAL);
                if (r < 0) from_mux = 0;
            } else if (n < 0) {
                from_mux = 0;
            }
            /* n == 0 = timeout: keep looping */
        }
        if (from_pair && !from_mux) {
            /* drain what's left of the pair before dying */
        }
    }
    /* close our end first and announce pump shutdown *before* requesting
     * conn teardown, so conn_teardown can never double-close or self-join */
    pthread_mutex_lock(&c->lock);
    if (c->pair_fd >= 0) close(c->pair_fd);
    c->pair_fd = -1;
    c->pump_started = 0;
    pthread_mutex_unlock(&c->lock);
    aad_mux_conn_close(conn_id, "bridge peer closed");
    return NULL;
}

static void conn_established(struct mux_conn *c)
{
    int is_bridge = 0;
    int reg = port_registered(c->dport, &is_bridge);
    if (!reg) {
        LOGI("mux conn to unregistered port %d — RST", c->dport);
        conn_teardown(c, "no such service");
        return;
    }
    LOGI("mux conn %d established sport=%d dport=%d%s",
         c->id, c->hsport, c->dport, is_bridge ? " (bridge)" : "");
    if (!is_bridge) {
        if (M.on_lockdown)
            M.on_lockdown(c->id, M.user);
        return;
    }
    /* bridge port: socketpair + pump + user callback */
    int sv[2];
    if (socketpair(AF_UNIX, SOCK_STREAM, 0, sv) != 0) {
        conn_teardown(c, "socketpair failed");
        return;
    }
    c->pair_fd = sv[1];
    if (pthread_create(&c->pump_th, NULL, bridge_pump, c) == 0)
        c->pump_started = 1;
    else {
        close(sv[1]);
        c->pair_fd = -1;
    }
    if (M.on_bridge)
        M.on_bridge(sv[0], M.user);
    close(sv[0]);
}

/* ---------------- RX ---------------- */

static void handle_tcp(struct mux_conn *lookup, struct tcphdr_m *th,
                       unsigned char *payload, uint32_t plen)
{
    uint16_t hs = be16(th->th_sport);
    uint16_t ds = be16(th->th_dport);
    uint32_t seq = be32(th->th_seq);
    uint32_t ack = be32(th->th_ack);
    uint16_t win = be16(th->th_win);
    uint8_t flags = th->th_flags;

    if (flags & TH_RST) {
        struct mux_conn *c = conn_find(hs, ds) ? conn_find(hs, ds) : conn_find(ds, hs);
        if (c) {
            LOGI("mux conn %d RST from host (%.*s)", c->id,
                 (int)plen, plen ? (const char *)payload : "");
            conn_teardown(c, NULL);
        }
        return;
    }

    struct mux_conn *c = conn_find(hs, ds);

    if (flags & TH_SYN) {
        if (!c) {
            if (!(flags & TH_ACK)) {
                /* dial */
                int is_bridge;
                if (port_registered(ds, &is_bridge)) {
                    c = conn_alloc();
                    if (!c) { send_anon_rst(hs, ds, seq); return; }
                    c->id = ++M.next_id;
                    c->state = C_SYN_RCVD;
                    c->hsport = hs;
                    c->dport = ds;
                    c->is_bridge = is_bridge;
                    c->tx_seq = 0;
                    c->tx_acked = 0;
                    c->rx_seq = seq;
                    c->rx_ack = 0;
                    c->rx_win = (uint32_t)win * 256;
                    c->pair_fd = -1;
                    c->last_active = aad_now_ms();
                    pthread_mutex_init(&c->lock, NULL);
                    pthread_cond_init(&c->cond, NULL);
                    LOGI("mux SYN %d -> service %d", c->id, ds);
                    /* SYN|ACK: our seq=0, ack=their seq+1 */
                    struct tcphdr_m r;
                    memset(&r, 0, sizeof(r));
                    r.th_sport = be16(ds);
                    r.th_dport = be16(hs);
                    r.th_seq = be32(0);
                    r.th_ack = be32(seq + 1);
                    r.th_flags = TH_SYN | TH_ACK;
                    r.th_off = 5;
                    r.th_win = be16((uint16_t)(TX_WIN >> 8));
                    send_packet(MUX_PROTO_TCP, &r, NULL, 0);
                } else {
                    LOGI("mux SYN to unregistered %d — RST", ds);
                    send_anon_rst(hs, ds, seq);
                }
            }
            return;
        }
        /* (we never initiate; ignore stray SYN on live conn) */
        return;
    }

    if (!c || c->state == C_FREE) {
        send_anon_rst(hs, ds, seq);
        return;
    }

    /* update window bookkeeping */
    pthread_mutex_lock(&c->lock);
    c->rx_seq = seq;
    c->rx_ack = ack;
    c->rx_win = (uint32_t)win * 256;
    if (flags & TH_ACK)
        c->tx_acked = ack;
    c->last_active = aad_now_ms();
    uint32_t space = c->rcap - c->rlen;
    if (space < plen) {
        /* no room: ack anyway, drop */
        pthread_mutex_unlock(&c->lock);
        struct tcphdr_m a;
        memset(&a, 0, sizeof(a));
        a.th_sport = be16(c->dport);
        a.th_dport = be16(c->hsport);
        a.th_seq = be32(c->tx_seq);
        a.th_ack = be32(seq + (uint32_t)plen);
        a.th_flags = TH_ACK;
        a.th_off = 5;
        a.th_win = be16((uint16_t)(TX_WIN >> 8));
        send_packet(MUX_PROTO_TCP, &a, NULL, 0);
        return;
    }
    if (plen) {
        memcpy(c->rbuf + c->rlen, payload, plen);
        c->rlen += plen;
    }
    int was_est = (c->state == C_EST);
    if (c->state == C_SYN_RCVD)
        c->state = C_EST;
    pthread_cond_broadcast(&c->cond);
    pthread_mutex_unlock(&c->lock);

    /* ack the data promptly (usbmuxd note: "device likes it best when we
     * are promply ACKing data" — we are the device; the host likes the same) */
    struct tcphdr_m a;
    memset(&a, 0, sizeof(a));
    a.th_sport = be16(c->dport);
    a.th_dport = be16(c->hsport);
    a.th_seq = be32(c->tx_seq);
    a.th_ack = be32(seq + (uint32_t)plen);
    a.th_flags = TH_ACK;
    a.th_off = 5;
    a.th_win = be16((uint16_t)(TX_WIN >> 8));
    send_packet(MUX_PROTO_TCP, &a, NULL, 0);

    if (!was_est && c->state == C_EST)
        conn_established(c);
}

static void dispatch_packet(unsigned char *buf, uint32_t len)
{
    struct mux_hdr *h = (struct mux_hdr *)buf;
    uint32_t proto = be32(h->protocol);
    int hsize = (M.version >= MUX_V2) ? 16 : 8;
    uint32_t plen = len - (uint32_t)hsize;

    switch (proto) {
    case MUX_PROTO_VERSION: {
        if (plen < 12) return;
        struct version_hdr *vh = (struct version_hdr *)(buf + hsize);
        uint32_t major = be32(vh->major);
        if (M.version == 0) {
            M.version = (major == 1) ? MUX_V1 : MUX_V2;
            LOGI("usbmux handshake: host v%u.%u -> stream v%d",
                 major, be32(vh->minor), M.version);
        }
        M.handshake = 1;
        if (send_version() < 0) LOGE("VERSION answer failed");
        break;
    }
    case MUX_PROTO_SETUP:
        LOGI("usbmux SETUP (v2 ready)");
        M.handshake = 1;
        break;
    case MUX_PROTO_TCP: {
        if (plen < sizeof(struct tcphdr_m)) return;
        struct tcphdr_m *th = (struct tcphdr_m *)(buf + hsize);
        unsigned char *payload = (unsigned char *)(th + 1);
        uint32_t plen2 = plen - (uint32_t)sizeof(struct tcphdr_m);
        if (M.version == 0) return; /* TCP before VERSION: ignore */
        handle_tcp(NULL, th, payload, plen2);
        break;
    }
    case MUX_PROTO_CONTROL:
        /* host-side log channel; ignore */
        break;
    default:
        LOGI("mux: unknown protocol 0x%x", proto);
        break;
    }
}

static void *rx_thread(void *arg)
{
    (void)arg;
    static __thread unsigned char tmp[USB_MTU];
    while (M.running) {
        /* read() fills from buffer start; stage in tmp, then append after
         * any pending partial packet. A single read returns one URB (<= USB_MTU);
         * cap the read so pend + n fits rxbig (a mux packet is <= USB_MTU). */
        uint32_t pend = M.rxlen;
        ssize_t n = read(M.ep_in, tmp, sizeof(tmp) - pend);
        if (n < 0) {
            if (errno == EINTR) continue;
            LOGE("usbmux rx closed: %s", strerror(errno));
            break;
        }
        if (n == 0) continue;
        if (pend > 0)
            memcpy(M.rxbig + pend, tmp, (size_t)n);
        else
            memcpy(M.rxbig, tmp, (size_t)n);
        M.rxlen = pend + (uint32_t)n;
        /* deframe */
        while (M.rxlen >= 8) {
            struct mux_hdr *h = (struct mux_hdr *)M.rxbig;
            uint32_t plen = be32(h->length);
            int is_v2 = (M.version >= MUX_V2);
            uint32_t need = is_v2 ? 16 : 8;
            if (plen < need || plen > USB_MTU) {
                /* desync: drop one byte and resync */
                memmove(M.rxbig, M.rxbig + 1, M.rxlen - 1);
                M.rxlen--;
                continue;
            }
            if (is_v2 && be32(h->magic) != MUX_MAGIC_V2) {
                memmove(M.rxbig, M.rxbig + 1, M.rxlen - 1);
                M.rxlen--;
                continue;
            }
            if (M.rxlen < plen) break; /* wait for more */
            if (is_v2) {
                pthread_mutex_lock(&M.lock);
                M.srx = be16(h->rx_seq);
                pthread_mutex_unlock(&M.lock);
            }
            dispatch_packet(M.rxbig, plen);
            memmove(M.rxbig, M.rxbig + plen, M.rxlen - plen);
            M.rxlen -= plen;
        }
    }
    return NULL;
}

/* ---------------- public API ---------------- */

int aad_mux_start(int ep_out, int ep_in,
                  void (*on_lockdown)(int, void *),
                  void (*on_bridge)(int, void *),
                  void *user)
{
    memset(&M, 0, sizeof(M));
    M.ep_out = ep_out;
    M.ep_in = ep_in;
    M.on_lockdown = on_lockdown;
    M.on_bridge = on_bridge;
    M.user = user;
    M.next_bridge_port = 50000;
    for (int i = 0; i < CONN_MAX; i++) {
        M.conns[i].state = C_FREE;
        M.conns[i].pair_fd = -1;
        pthread_mutex_init(&M.conns[i].lock, NULL);
        pthread_cond_init(&M.conns[i].cond, NULL);
    }
    M.running = 1;
    if (pthread_create(&M.rx_th, NULL, rx_thread, NULL) != 0) {
        M.running = 0;
        return -1;
    }
    M.rx_started = 1;
    return 0;
}

int aad_mux_alloc_bridge_port(void)
{
    pthread_mutex_lock(&M.lock);
    int port = M.next_bridge_port++;
    pthread_mutex_unlock(&M.lock);
    return port;
}

int aad_mux_enable_bridge_port(int port)
{
    (void)port; /* registration window [50000, next) covers it */
    return 0;
}

int aad_mux_handshake_done(void) { return M.handshake; }

void aad_mux_reap_idle(long idle_ms)
{
    if (!M.running) return;
    long now = aad_now_ms();
    for (int i = 0; i < CONN_MAX; i++) {
        struct mux_conn *c = &M.conns[i];
        if (c->state != C_EST) continue;
        pthread_mutex_lock(&c->lock);
        long idle = now - c->last_active;
        pthread_mutex_unlock(&c->lock);
        if (c->last_active != 0 && idle > idle_ms) {
            LOGI("mux conn %d idle %ld ms — reaping", c->id, idle);
            aad_mux_conn_close(c->id, "idle timeout");
        }
    }
}

void aad_mux_stop(void)
{
    /*
     * The caller MUST unbind the UDC (aad_gadget_unbind) before calling:
     * the rx thread blocks in read(ep_in) and is woken by the ffs
     * endpoint disable (-ESHUTDOWN). Join it before tearing down conns
     * so no dispatch races with the teardown.
     */
    M.running = 0;
    if (M.rx_started)
        pthread_join(M.rx_th, NULL);
    M.rx_started = 0;
    for (int i = 0; i < CONN_MAX; i++)
        if (M.conns[i].state != C_FREE)
            conn_teardown(&M.conns[i], "daemon stopping");
    M.handshake = 0;
    M.version = 0;
}
