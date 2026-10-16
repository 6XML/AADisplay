/*
 * bridge.c — local control channel between the aad-usb daemon (root) and the
 * AADisplay app (unprivileged).
 *
 * Control socket: TCP 127.0.0.1:<port>, newline-terminated text lines.
 *   app -> daemon:  VER | ARM | DISARM | STATE | GET CARKIT
 *   daemon -> app:  VER <v> | STATE <stage> [detail] | CARKIT <port|none>
 *
 * CARKIT <port> is pushed when a carkit service stream is established
 * (lockdown StartService -> host dials the mux bridge port -> socketpair).
 * The app connects to 127.0.0.1:<port> and then the stream carries the raw
 * iAP2 byte stream (device side); this file pumps app fd <-> pair fd.
 */
#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <poll.h>

#include "aad.h"

#ifdef __ANDROID__
#define aad_accept(fd, a, b) accept4(fd, a, b, SOCK_CLOEXEC)
#else
#define aad_accept(fd, a, b) accept(fd, a, b)
#endif

#ifndef SOCK_CLOEXEC
#define SOCK_CLOEXEC 0
#endif

struct ctl_client {
    int fd;
    aad_bridge *br;
    struct ctl_client *next;
};

struct carkit_stream {
    int pair_fd;      /* socketpair end facing the mux conn pump */
    int lsock;        /* listener for the app connection */
    int port;
    int app_fd;       /* accepted app socket (-1 until accepted) */
    pthread_t th;
    int running;
    struct carkit_stream *next;
};

struct aad_bridge {
    int lsock;
    int port;
    int run;
    pthread_t th;
    pthread_mutex_t lock;
    struct ctl_client *clients;
    struct carkit_stream *carkits;
    void (*on_arm)(int on, void *user);
    int (*get_state)(void *user); /* returns aad stage */
    void *user;
};

static void client_send(struct ctl_client *cl, const char *line)
{
    ssize_t n = strlen(line);
    ssize_t r = write(cl->fd, line, (size_t)n);
    if (r < 0) {
        if (errno == EAGAIN || errno == EINTR) return;
        cl->fd = -1; /* caller reaps below */
    }
}

void aad_bridge_push_state(aad_bridge *br, int stage, const char *detail)
{
    if (!br) return;
    char line[256];
    snprintf(line, sizeof(line), "STATE %s%s%s\n", aad_stage_name(stage),
             detail ? " " : "", detail ? detail : "");
    pthread_mutex_lock(&br->lock);
    struct ctl_client **pp = &br->clients;
    while (*pp) {
        struct ctl_client *cl = *pp;
        client_send(cl, line);
        if (cl->fd < 0) {
            *pp = cl->next;
            close(cl->fd);
            free(cl);
        } else {
            pp = &cl->next;
        }
    }
    pthread_mutex_unlock(&br->lock);
}

/* ---------------- carkit stream pump ---------------- */

static void *carkit_thread(void *arg)
{
    struct carkit_stream *cs = arg;
    int app = cs->app_fd;
    int pair = cs->pair_fd;
    unsigned char b1[8192], b2[8192];

    int al = 1, pl = 1;
    while (cs->running && (al || pl)) {
        struct pollfd p[2] = {
            { app, POLLIN, 0 },
            { pair, POLLIN, 0 },
        };
        if (!al) p[0].fd = -1;
        if (!pl) p[1].fd = -1;
        int pr = poll(p, 2, 500);
        if (pr < 0) {
            if (errno == EINTR) continue;
            break;
        }
        if (pr == 0) continue;
        if (al && (p[0].revents & (POLLIN | POLLHUP))) {
            ssize_t n = read(app, b1, sizeof(b1));
            if (n <= 0) al = 0;
            else {
                ssize_t off = 0;
                while (off < n) {
                    ssize_t r = send(pair, b1 + off, (size_t)(n - off), MSG_NOSIGNAL);
                    if (r <= 0) { pl = 0; break; }
                    off += r;
                }
            }
        }
        if (pl && (p[1].revents & (POLLIN | POLLHUP))) {
            ssize_t n = read(pair, b2, sizeof(b2));
            if (n <= 0) pl = 0;
            else {
                ssize_t off = 0;
                while (off < n) {
                    ssize_t r = send(app, b2 + off, (size_t)(n - off), MSG_NOSIGNAL);
                    if (r <= 0) { al = 0; break; }
                    off += r;
                }
            }
        }
    }
    cs->running = 0;
    if (app >= 0) close(app);
    if (pair >= 0) close(pair);
    return NULL;
}

/* accepts the app connection for a carkit stream */
static void *carkit_accept_th(void *arg)
{
    struct carkit_stream *cs = arg;
    for (;;) {
        struct pollfd p = { cs->lsock, POLLIN, 0 };
        int pr = poll(&p, 1, 1000);
        if (pr <= 0) {
            if (!cs->running) break;
            continue;
        }
        int fd = aad_accept(cs->lsock, NULL, NULL);
        if (fd < 0) continue;
        cs->app_fd = fd;
        /* the app connects once per stream; stop accepting further */
        break;
    }
    if (cs->app_fd >= 0 && cs->running)
        carkit_thread(cs);
    else if (cs->app_fd >= 0)
        close(cs->app_fd);
    return NULL;
}

void aad_bridge_publish_carkit(aad_bridge *br, int pair_fd)
{
    if (!br) return;
    int lsock = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (lsock < 0) { close(pair_fd); return; }
    int one = 1;
    setsockopt(lsock, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
    struct sockaddr_in sa = {0};
    sa.sin_family = AF_INET;
    sa.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    sa.sin_port = 0; /* ephemeral; port discovered below */
    if (bind(lsock, (struct sockaddr *)&sa, sizeof(sa)) != 0 || listen(lsock, 1) != 0) {
        close(lsock);
        close(pair_fd);
        return;
    }
    socklen_t sl = sizeof(sa);
    getsockname(lsock, (struct sockaddr *)&sa, &sl);

    struct carkit_stream *cs = calloc(1, sizeof(*cs));
    cs->pair_fd = pair_fd;
    cs->lsock = lsock;
    cs->port = ntohs(sa.sin_port);
    cs->app_fd = -1;
    cs->running = 1;

    pthread_mutex_lock(&br->lock);
    cs->next = br->carkits;
    br->carkits = cs;
    pthread_mutex_unlock(&br->lock);

    pthread_create(&cs->th, NULL, carkit_accept_th, cs);

    aad_bridge_push_state(br, STG_CARKIT, NULL);
    LOGI("bridge: carkit stream on 127.0.0.1:%d", cs->port);
}

/* report the port of the first live carkit stream (for GET CARKIT) */
static int first_carkit_port(aad_bridge *br)
{
    pthread_mutex_lock(&br->lock);
    int port = -1;
    for (struct carkit_stream *cs = br->carkits; cs; cs = cs->next)
        if (cs->running) { port = cs->port; break; }
    pthread_mutex_unlock(&br->lock);
    return port;
}

int aad_bridge_has_carkit(aad_bridge *br)
{
    return first_carkit_port(br) > 0;
}

/* ---------------- control client thread ---------------- */

static void *handle_client(void *arg)
{
    struct ctl_client *cl = arg;
    aad_bridge *br = cl->br;
    char buf[1024];
    size_t off = 0;
    for (;;) {
        ssize_t n = read(cl->fd, buf + off, sizeof(buf) - 1 - off);
        if (n <= 0) break;
        off += (size_t)n;
        /* process all complete lines */
        while (off > 0) {
            char *nl = memchr(buf, '\n', off);
            if (!nl) {
                if (off == sizeof(buf) - 1) { client_send(cl, "ERR toobig\n"); break; }
                break;
            }
            *nl = 0;
            size_t llen = (size_t)(nl - buf);
            memmove(buf, nl + 1, off - llen - 1);
            off = off - llen - 1;

            if (!strcmp(buf, "VER")) {
                char l[64];
                snprintf(l, sizeof(l), "VER %s\n", AAD_VERSION);
                client_send(cl, l);
            } else if (!strcmp(buf, "ARM")) {
                client_send(cl, "ACK ARM\n");
                if (br->on_arm) br->on_arm(1, br->user);
            } else if (!strcmp(buf, "DISARM")) {
                client_send(cl, "ACK DISARM\n");
                if (br->on_arm) br->on_arm(0, br->user);
            } else if (!strcmp(buf, "STATE")) {
                int st = br->get_state ? br->get_state(br->user) : STG_IDLE;
                char l[64];
                snprintf(l, sizeof(l), "STATE %s\n", aad_stage_name(st));
                client_send(cl, l);
            } else if (!strcmp(buf, "GET CARKIT")) {
                int port = first_carkit_port(br);
                char l[64];
                if (port > 0)
                    snprintf(l, sizeof(l), "CARKIT %d\n", port);
                else
                    snprintf(l, sizeof(l), "CARKIT none\n");
                client_send(cl, l);
            } else if (buf[0]) {
                client_send(cl, "ERR unknown\n");
            }
        }
    }
    cl->fd = -1;
    return NULL;
}

static void *ctl_thread(void *arg)
{
    aad_bridge *br = arg;
    for (;;) {
        struct pollfd p = { br->lsock, POLLIN, 0 };
        int pr = poll(&p, 1, 500);
        if (pr < 0) {
            if (errno == EINTR) continue;
            break;
        }
        if (pr == 0) continue;
        int fd = aad_accept(br->lsock, NULL, NULL);
        if (fd < 0) continue;
        struct ctl_client *cl = calloc(1, sizeof(*cl));
        cl->fd = fd;
        cl->br = br;
        pthread_mutex_lock(&br->lock);
        cl->next = br->clients;
        br->clients = cl;
        pthread_mutex_unlock(&br->lock);

        pthread_t th;
        /* detached worker */
        pthread_attr_t at;
        pthread_attr_init(&at);
        pthread_attr_setdetachstate(&at, PTHREAD_CREATE_DETACHED);
        if (pthread_create(&th, &at, handle_client, cl) != 0) {
            pthread_attr_destroy(&at);
            close(fd);
            pthread_mutex_lock(&br->lock);
            br->clients = (br->clients == cl) ? cl->next : br->clients;
            free(cl);
            pthread_mutex_unlock(&br->lock);
        } else {
            pthread_attr_destroy(&at);
        }
    }
    return NULL;
}

int aad_bridge_start(aad_bridge **out, int port)
{
    aad_bridge *br = calloc(1, sizeof(*br));
    if (!br) return -1;
    pthread_mutex_init(&br->lock, NULL);
    br->lsock = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (br->lsock < 0) { free(br); return -1; }
    int one = 1;
    setsockopt(br->lsock, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
    struct sockaddr_in sa = {0};
    sa.sin_family = AF_INET;
    sa.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    sa.sin_port = htons((uint16_t)port);
    if (bind(br->lsock, (struct sockaddr *)&sa, sizeof(sa)) != 0) {
        LOGE("bridge: bind 127.0.0.1:%d failed: %s", port, strerror(errno));
        close(br->lsock);
        free(br);
        return -1;
    }
    if (listen(br->lsock, 8) != 0) {
        close(br->lsock);
        free(br);
        return -1;
    }
    br->port = port;
    br->run = 1;
    if (pthread_create(&br->th, NULL, ctl_thread, br) != 0) {
        close(br->lsock);
        free(br);
        return -1;
    }
    *out = br;
    LOGI("bridge: control socket on 127.0.0.1:%d", port);
    return 0;
}

void aad_bridge_set_arming_cb(aad_bridge *br, void (*cb)(int on, void *user), void *user)
{
    if (br) { br->on_arm = cb; if (user) br->user = user; }
}

void aad_bridge_set_state_cb(aad_bridge *br, int (*cb)(void *user), void *user)
{
    if (br) { br->get_state = cb; if (user) br->user = user; }
}

void aad_bridge_stop(aad_bridge *br)
{
    if (!br) return;
    br->run = 0;
    close(br->lsock); /* wakes ctl_thread's poll */
    pthread_join(br->th, NULL);
    pthread_mutex_lock(&br->lock);
    struct ctl_client *cl = br->clients;
    while (cl) {
        struct ctl_client *next = cl->next;
        close(cl->fd);
        free(cl);
        cl = next;
    }
    br->clients = NULL;
    struct carkit_stream *cs = br->carkits;
    while (cs) {
        struct carkit_stream *next = cs->next;
        cs->running = 0;
        if (cs->lsock >= 0) close(cs->lsock);   /* wakes accept poll */
        if (cs->app_fd >= 0) close(cs->app_fd); /* wakes pump */
        if (cs->pair_fd >= 0) close(cs->pair_fd);
        pthread_join(cs->th, NULL);              /* accept(+pump) thread done */
        free(cs);
        cs = next;
    }
    br->carkits = NULL;
    pthread_mutex_unlock(&br->lock);
    free(br);
}
