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
#include <poll.h>
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
    /* Wire byte: high nibble = data offset in 32-bit words (5 for 20B hdr).
     * Must be (5<<4)==0x50 — a bare 5 is th_x2 on LE BSD layouts and the
     * host discards the segment (lockdown Mux error -8). */
    uint8_t th_off;
    uint8_t th_flags;
    uint16_t th_win;               /* BE, <<8 */
    uint16_t th_sum, th_urp;
} __attribute__((packed));

#define TCP_OFF_WORDS  ((uint8_t)(5 << 4))

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
    /* Match usbmuxd device.c naming:
     *   tx_seq  = our next outbound sequence
     *   tx_ack  = next peer seq we acknowledge (goes in th_ack)
     *   rx_ack  = peer's ack of our data (from their th_ack)
     *   rx_win  = peer receive window */
    uint32_t tx_seq, tx_ack, rx_ack, rx_win;
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

#define BRIDGE_MARK_MAX 64
struct bridge_mark {
    int port;
    int is_carkit;
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
    struct bridge_mark marks[BRIDGE_MARK_MAX];
    int nmarks;
    pthread_t rx_th;
    int rx_started;
    unsigned char rxbig[USB_MTU];
    uint32_t rxlen;            /* pending partial packet */
    int rx_drop;               /* bus reset: discard pending deframe bytes */
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
        /* Device→host = USB IN endpoint. */
        ssize_t r = write(M.ep_in, p, len);
        if (r < 0) {
            if (errno == EINTR || errno == EAGAIN) {
                usleep(1000);
                continue;
            }
            LOGE("usbmux tx failed: %s (errno=%d)", strerror(errno), errno);
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
    th.th_ack = be32(c->tx_ack);
    th.th_flags = flags;
    th.th_off = TCP_OFF_WORDS;
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
    th.th_off = TCP_OFF_WORDS;
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

/* conn_id is a monotonic token (next_id), NOT an array index. */
static struct mux_conn *conn_by_id(int conn_id)
{
    if (conn_id <= 0) return NULL;
    for (int i = 0; i < CONN_MAX; i++) {
        struct mux_conn *c = &M.conns[i];
        if (c->id == conn_id && c->state != C_FREE)
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

/* Set during bus/re-VERSION reset so teardown skips TCP RST on a dead pipe. */
static volatile int g_suppress_rst;

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
    /* RST with reason text (host logs it; mirrors real device behavior).
     * Skip on bus reset — the USB pipe is already gone. */
    if (M.running && !g_suppress_rst) {
        if (reason && reason[0])
            send_tcp(c, TH_RST, reason, (int)strlen(reason));
        else
            send_tcp(c, TH_RST, NULL, 0);
    }
    c->state = C_FREE;
    c->id = -1; /* never reused: id checks in read/write/conn_close */
    c->closed = 0; /* allow clean reuse of this slot */
}

int aad_mux_conn_alive(int conn_id)
{
    struct mux_conn *c = conn_by_id(conn_id);
    return c ? (c->state == C_EST) : 0;
}

void aad_mux_conn_close(int conn_id, const char *rst_reason)
{
    struct mux_conn *c = conn_by_id(conn_id);
    if (c && c->state != C_CLOSING)
        conn_teardown(c, rst_reason);
}

int aad_mux_read(int conn_id, void *buf, size_t len, int timeout_ms)
{
    struct mux_conn *c = conn_by_id(conn_id);
    if (!c) return -1;
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
            if (pthread_cond_timedwait(&c->cond, &c->lock, &ts) == ETIMEDOUT) {
                pthread_mutex_unlock(&c->lock);
                return 0;
            }
        }
    }
    int n = (int)len;
    if (c->rlen < (uint32_t)n) n = (int)c->rlen;
    if (n > 0) {
        memcpy(buf, c->rbuf, n);
        memmove(c->rbuf, c->rbuf + n, c->rlen - n);
        c->rlen -= n;
    }
    int closed = c->closed;
    pthread_mutex_unlock(&c->lock);
    return closed && n == 0 ? -1 : n;
}

int aad_mux_write(int conn_id, const void *buf, size_t len)
{
    struct mux_conn *c = conn_by_id(conn_id);
    if (!c) {
        LOGE("mux write: no conn %d", conn_id);
        return -1;
    }
    const unsigned char *p = buf;
    size_t done = 0;
    int max_payload = USB_MTU - 16 - 20;
    while (done < len) {
        pthread_mutex_lock(&c->lock);
        if (c->closed || c->state != C_EST) {
            pthread_mutex_unlock(&c->lock);
            LOGE("mux write: conn %d closed/state=%d", conn_id, c->state);
            return -1;
        }
        /* In-flight = our tx_seq minus what the peer has ACKed. */
        uint32_t sent = c->tx_seq - c->rx_ack;
        uint32_t sendable = (c->rx_win > sent) ? (c->rx_win - sent) : 0;
        /*
         * usbmux over USB is stop-and-wait enough that a stuck rx_ack
         * (preflight race before the host ACKs our SYN) used to make even
         * the 4-byte lockdown length header fail. Floor sendable so small
         * control replies always go out; still respect max_payload.
         */
        if (sendable < 4096)
            sendable = 4096;
        size_t chunk = len - done;
        if (chunk > sendable) chunk = (size_t)sendable;
        if (chunk > (size_t)max_payload) chunk = (size_t)max_payload;
        uint32_t snap_seq = c->tx_seq;
        uint32_t snap_ack = c->tx_ack;
        /* Advance seq optimistically under lock so concurrent writers on
         * the same conn (should not happen) do not reuse seq. */
        c->tx_seq = snap_seq + (uint32_t)chunk;
        c->last_active = aad_now_ms();
        pthread_mutex_unlock(&c->lock);
        if (chunk == 0) { usleep(1000); continue; }
        {
            struct tcphdr_m th;
            memset(&th, 0, sizeof(th));
            th.th_sport = be16(c->dport);
            th.th_dport = be16(c->hsport);
            th.th_seq = be32(snap_seq);
            th.th_ack = be32(snap_ack);
            th.th_flags = TH_ACK;
            th.th_off = TCP_OFF_WORDS;
            th.th_win = be16((uint16_t)(TX_WIN >> 8));
            if (send_packet(MUX_PROTO_TCP, &th, p + done, (int)chunk) < 0) {
                LOGE("mux conn %d send_packet failed chunk=%zu seq=%u ack=%u",
                     conn_id, chunk, snap_seq, snap_ack);
                return -1;
            }
        }
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
    /*
     * Must not block forever on pair recv: after the App sends 0x1D00 the
     * pump used to sit in recv() while the HU's 0x1D01 filled rbuf — App
     * never saw IdentificationInformation. Poll both directions.
     */
    int fl = fcntl(pair_fd, F_GETFL, 0);
    if (fl >= 0)
        fcntl(pair_fd, F_SETFL, fl | O_NONBLOCK);
    while (from_pair || from_mux) {
        struct pollfd p = { pair_fd, 0, 0 };
        if (from_pair)
            p.events = POLLIN;
        int pr = poll(&p, 1, 50);
        if (pr < 0) {
            if (errno == EINTR) continue;
            break;
        }
        /* pair -> mux */
        if (from_pair && pr > 0 && (p.revents & (POLLIN | POLLHUP | POLLERR))) {
            for (;;) {
                ssize_t n = recv(pair_fd, obuf, sizeof(obuf), 0);
                if (n < 0) {
                    if (errno == EAGAIN || errno == EWOULDBLOCK) break;
                    from_pair = 0;
                    break;
                }
                if (n == 0) { from_pair = 0; break; }
                if (aad_mux_write(conn_id, obuf, (size_t)n) < 0) {
                    from_pair = 0;
                    break;
                }
            }
        }
        /* mux -> pair. timeout must be >0: aad_mux_read(≤0) waits forever
         * on cond and would stall pair→mux (0x1D02 never leaves the phone). */
        if (from_mux) {
            int n = aad_mux_read(conn_id, ibuf, sizeof(ibuf), 20);
            if (n > 0) {
                ssize_t off = 0;
                while (off < n) {
                    ssize_t r = send(pair_fd, ibuf + off, (size_t)(n - off), MSG_NOSIGNAL);
                    if (r < 0) {
                        if (errno == EAGAIN || errno == EWOULDBLOCK) {
                            usleep(1000);
                            continue;
                        }
                        from_mux = 0;
                        break;
                    }
                    off += r;
                }
            } else if (n < 0) {
                from_mux = 0;
            }
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

static int bridge_port_is_carkit(int port)
{
    pthread_mutex_lock(&M.lock);
    int is = 0, found = 0;
    for (int i = 0; i < M.nmarks; i++) {
        if (M.marks[i].port == port) {
            is = M.marks[i].is_carkit;
            found = 1;
            break;
        }
    }
    pthread_mutex_unlock(&M.lock);
    /* Unknown bridge ports (no StartService tag yet): discard, not carkit. */
    return found ? is : 0;
}

static void *discard_bridge_th(void *arg)
{
    int fd = (int)(intptr_t)arg;
    unsigned char buf[1024];
    while (recv(fd, buf, sizeof(buf), 0) > 0) { /* drop */ }
    close(fd);
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
    int carkit = bridge_port_is_carkit(c->dport);
    if (carkit && M.on_bridge) {
        /* on_bridge takes ownership of sv[0] — do NOT close it here. */
        M.on_bridge(sv[0], M.user);
        LOGI("mux bridge port %d -> carkit publish", c->dport);
    } else {
        /* macOS probes (notification_proxy etc.): keep stream alive, drop data */
        pthread_t th;
        if (pthread_create(&th, NULL, discard_bridge_th, (void *)(intptr_t)sv[0]) == 0)
            pthread_detach(th);
        else
            close(sv[0]);
        LOGI("mux bridge port %d -> discard (non-carkit)", c->dport);
    }
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
                    c->closed = 0; /* slot reuse: prior teardown left closed=1 */
                    c->hsport = hs;
                    c->dport = ds;
                    c->is_bridge = is_bridge;
                    c->tx_seq = 0;
                    c->tx_ack = seq + 1; /* ACK their SYN */
                    c->rx_ack = 0;       /* peer has not ACKed us yet */
                    c->rx_win = (uint32_t)win * 256;
                    if (c->rx_win == 0) c->rx_win = 131072;
                    c->pair_fd = -1;
                    c->pump_started = 0;
                    c->last_active = aad_now_ms();
                    c->rlen = 0;
                    c->rbuf = malloc(CONN_BUF_MAX);
                    c->rcap = c->rbuf ? CONN_BUF_MAX : 0;
                    c->rlen = 0;
                    if (!c->rbuf) {
                        c->state = C_FREE;
                        send_anon_rst(hs, ds, seq);
                        return;
                    }
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
                    r.th_off = TCP_OFF_WORDS;
                    r.th_win = be16((uint16_t)(TX_WIN >> 8));
                    send_packet(MUX_PROTO_TCP, &r, NULL, 0);
                    /* SYN consumes one sequence number */
                    c->tx_seq = 1;
                    /*
                     * Host usbmuxd returns Connect Result=0 after SYN|ACK
                     * (and sends the completing ACK). On Flip4 we have seen
                     * carkit bridge ports stick in SYN_RCVD forever — ACK
                     * never arrives — so on_bridge/carkit publish never
                     * runs and the App never sends 0x1D00. notification_
                     * proxy on the same bridge path still completes.
                     * Promote to EST right after SYN|ACK; a late ACK only
                     * updates rx_ack. Seed rx_ack as if host ACKed seq 0.
                     */
                    c->rx_ack = 1;
                    c->state = C_EST;
                    conn_established(c);
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

    /* update window / ack bookkeeping (usbmuxd process_tcp semantics) */
    pthread_mutex_lock(&c->lock);
    {
        uint32_t nw = (uint32_t)win * 256;
        /* Never collapse the send window to 0 on a lone ACK — that made
         * lockdown QueryType responses fail immediately (window timeout). */
        if (nw > 0 || c->rx_win == 0)
            c->rx_win = nw ? nw : 131072;
    }
    if (flags & TH_ACK)
        c->rx_ack = ack; /* peer advanced our send window */
    if (plen)
        c->tx_ack = seq + plen; /* next expected from peer */
    else if (c->state == C_SYN_RCVD && (flags & TH_ACK))
        c->tx_ack = seq; /* completing handshake: peer's post-SYN seq */
    c->last_active = aad_now_ms();
    uint32_t space = c->rcap - c->rlen;
    if (space < plen) {
        uint32_t ack_to = c->tx_ack;
        uint32_t our_seq = c->tx_seq;
        pthread_mutex_unlock(&c->lock);
        struct tcphdr_m a;
        memset(&a, 0, sizeof(a));
        a.th_sport = be16(c->dport);
        a.th_dport = be16(c->hsport);
        a.th_seq = be32(our_seq);
        a.th_ack = be32(ack_to);
        a.th_flags = TH_ACK;
        a.th_off = TCP_OFF_WORDS;
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
    uint32_t ack_to = c->tx_ack;
    uint32_t our_seq = c->tx_seq;
    int need_ack = (plen > 0) || !was_est;
    pthread_cond_broadcast(&c->cond);
    pthread_mutex_unlock(&c->lock);

    /* ack promptly when we took data or finished the handshake */
    if (need_ack) {
        struct tcphdr_m a;
        memset(&a, 0, sizeof(a));
        a.th_sport = be16(c->dport);
        a.th_dport = be16(c->hsport);
        a.th_seq = be32(our_seq);
        a.th_ack = be32(ack_to);
        a.th_flags = TH_ACK;
        a.th_off = TCP_OFF_WORDS;
        a.th_win = be16((uint16_t)(TX_WIN >> 8));
        send_packet(MUX_PROTO_TCP, &a, NULL, 0);
    }

    if (!was_est && c->state == C_EST)
        conn_established(c);
}

static void stream_reset(const char *why, int drop_rx)
{
    /* drop_rx: bus reset must discard partial USB deframe bytes. re-VERSION
     * must NOT — SETUP often shares the same URB as VERSION. */
    pthread_mutex_lock(&M.lock);
    M.handshake = 0;
    M.version = 0;
    M.stx = 0;
    M.srx = 0;
    if (drop_rx)
        M.rx_drop = 1;
    pthread_mutex_unlock(&M.lock);
    g_suppress_rst = 1;
    for (int i = 0; i < CONN_MAX; i++) {
        if (M.conns[i].state != C_FREE)
            conn_teardown(&M.conns[i], why);
    }
    g_suppress_rst = 0;
}

static void dispatch_packet(unsigned char *buf, uint32_t len)
{
    struct mux_hdr *h = (struct mux_hdr *)buf;
    uint32_t proto = be32(h->protocol);
    /* Host always frames VERSION as v1 (8B), including after a bus reset
     * re-handshake. Sticky M.version==2 must not force 16B here. */
    int hsize = (proto == MUX_PROTO_VERSION) ? 8
                : ((M.version >= MUX_V2) ? 16 : 8);
    uint32_t plen = len - (uint32_t)hsize;

    switch (proto) {
    case MUX_PROTO_VERSION: {
        if (plen < 12) return;
        if (M.version != 0 || M.handshake) {
            LOGI("usbmux re-VERSION — reset sticky stream (was v%d)", M.version);
            stream_reset("re-VERSION", 0);
        }
        struct version_hdr *vh = (struct version_hdr *)(buf + 8);
        uint32_t major = be32(vh->major);
        M.version = (major == 1) ? MUX_V1 : MUX_V2;
        LOGI("usbmux handshake: host v%u.%u -> stream v%d",
             major, be32(vh->minor), M.version);
        M.handshake = 1;
        if (send_version() < 0) LOGE("VERSION answer failed");
        break;
    }
    case MUX_PROTO_SETUP:
        /* Host resets seq on SETUP (usbmuxd send_packet); mirror that. */
        pthread_mutex_lock(&M.lock);
        M.stx = 0;
        M.srx = 0xFFFF;
        pthread_mutex_unlock(&M.lock);
        LOGI("usbmux SETUP (v2 ready, seq reset)");
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
        /* Apply bus-reset drop before blocking read / deframe. */
        pthread_mutex_lock(&M.lock);
        if (M.rx_drop) {
            M.rxlen = 0;
            M.rx_drop = 0;
        }
        uint32_t pend = M.rxlen;
        pthread_mutex_unlock(&M.lock);

        /* Host→device = USB OUT endpoint. One read = one URB (<= USB_MTU). */
        ssize_t n = read(M.ep_out, tmp, sizeof(tmp) - pend);
        if (n < 0) {
            if (errno == EINTR) continue;
            /* Pre-ENABLE / DISABLE: ffs bulk eps return transient errors.
             * Keep retrying until UDC teardown sets M.running=0. */
            if (errno == EAGAIN || errno == ESHUTDOWN || errno == ENODEV ||
                errno == EPROTO || errno == ENOTCONN || errno == ECONNRESET ||
                errno == EBADMSG || errno == ENOTSUP || errno == EOPNOTSUPP) {
                usleep(20 * 1000);
                continue;
            }
            LOGE("usbmux rx closed: %s (errno=%d)", strerror(errno), errno);
            break;
        }
        if (n == 0) continue;

        pthread_mutex_lock(&M.lock);
        if (M.rx_drop) {
            M.rxlen = 0;
            M.rx_drop = 0;
            pend = 0;
        }
        if (pend > 0)
            memcpy(M.rxbig + pend, tmp, (size_t)n);
        else
            memcpy(M.rxbig, tmp, (size_t)n);
        M.rxlen = pend + (uint32_t)n;
        pthread_mutex_unlock(&M.lock);

        /* deframe — only touch rxbig/rxlen on this thread */
        for (;;) {
            pthread_mutex_lock(&M.lock);
            int drop = M.rx_drop;
            if (drop) {
                M.rxlen = 0;
                M.rx_drop = 0;
            }
            uint32_t rxlen = M.rxlen;
            int ver = M.version;
            pthread_mutex_unlock(&M.lock);
            if (drop || rxlen < 8)
                break;

            struct mux_hdr *h = (struct mux_hdr *)M.rxbig;
            uint32_t plen = be32(h->length);
            uint32_t proto = be32(h->protocol);
            /* VERSION is always 8B-framed from the host (see send_version). */
            int is_v2 = (proto != MUX_PROTO_VERSION) && (ver >= MUX_V2);
            uint32_t need = is_v2 ? 16 : 8;
            if (plen < need || plen > USB_MTU) {
                memmove(M.rxbig, M.rxbig + 1, rxlen - 1);
                pthread_mutex_lock(&M.lock);
                M.rxlen = rxlen - 1;
                pthread_mutex_unlock(&M.lock);
                continue;
            }
            if (is_v2 && be32(h->magic) != MUX_MAGIC_V2) {
                memmove(M.rxbig, M.rxbig + 1, rxlen - 1);
                pthread_mutex_lock(&M.lock);
                M.rxlen = rxlen - 1;
                pthread_mutex_unlock(&M.lock);
                continue;
            }
            if (rxlen < plen)
                break; /* wait for more */

            if (is_v2) {
                pthread_mutex_lock(&M.lock);
                M.srx = be16(h->rx_seq);
                pthread_mutex_unlock(&M.lock);
            }
            /* Copy packet then shrink buffer before dispatch (VERSION may
             * stream_reset / teardown and must not see stale bytes). */
            static __thread unsigned char pkt[USB_MTU];
            memcpy(pkt, M.rxbig, plen);
            memmove(M.rxbig, M.rxbig + plen, rxlen - plen);
            pthread_mutex_lock(&M.lock);
            M.rxlen = rxlen - plen;
            pthread_mutex_unlock(&M.lock);
            dispatch_packet(pkt, plen);
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

void aad_mux_mark_bridge_service(int port, const char *service)
{
    int carkit = 0;
    if (service) {
        if (strstr(service, "carkit") || strstr(service, "CarKit") ||
            strstr(service, "carplay") || strstr(service, "CarPlay"))
            carkit = 1;
    }
    pthread_mutex_lock(&M.lock);
    int slot = -1;
    for (int i = 0; i < M.nmarks; i++) {
        if (M.marks[i].port == port) { slot = i; break; }
    }
    if (slot < 0 && M.nmarks < BRIDGE_MARK_MAX)
        slot = M.nmarks++;
    if (slot >= 0) {
        M.marks[slot].port = port;
        M.marks[slot].is_carkit = carkit;
    }
    pthread_mutex_unlock(&M.lock);
    LOGI("mux mark port %d service=%s carkit=%d", port,
         service ? service : "?", carkit);
}

int aad_mux_handshake_done(void) { return M.handshake; }

void aad_mux_bus_reset(void)
{
    if (!M.running) return;
    int had = M.handshake || M.version;
    stream_reset("bus reset", 1);
    if (had)
        LOGI("usbmux bus reset — stream cleared (await re-VERSION)");
}

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
     * the rx thread blocks in read(ep_out) and is woken by the ffs
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
