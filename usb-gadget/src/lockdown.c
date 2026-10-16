/*
 * lockdown.c — lockdownd DEVICE side (fake iPhone).
 *
 * Protocol (mirrors libimobiledevice host src/lockdown.c and DiPlay
 * LockdownCarKitClient/LockdownPairingClient, both verified local):
 *   frame  = 4-byte big-endian length + XML plist
 *   GetValue        -> identity table (PEM DevicePublicKey as <data>;
 *                      hosts read it with plist_get_data_val)
 *   SetValue        -> accept
 *   Pair            -> auto-trust: accept/adopt the host record, reply with
 *                      full record + random EscrowBag, persist per HostID
 *   ValidatePair    -> echo stored record + EscrowBag
 *   CheckMode       -> "paired"
 *   StartSession    -> SessionID + EnableSessionSSL:false (v1; v2 = TLS)
 *   StartService    -> dynamic usbmux port + EnableServiceSSL:false
 *   StopSession/Unpair/ReadPassport/WritePassport/others -> minimal ack
 *
 * PairRecord shape (DiPlay toPairRequestDictionary + libimobiledevice
 * lockdownd_pair_record_to_plist): DeviceCertificate(data) HostCertificate(data)
 * HostID(str) RootCertificate(data) SystemBUID(str) [+DevicePublicKey(data),
 * WiFiAddress(str), EscrowBag(data) on device side].
 * Certificates in records: real iPhones carry PEM text in <data> nodes
 * (libimobiledevice stores strlen() of the PEM — data node of PEM bytes);
 * we follow that (PEM in <data>).
 */
#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>

#include "aad.h"

#define PAIR_DIR "/data/adb/aadusb/pair"
#define ID_DIR   "/data/adb/aadusb"

struct aad_lockdown {
    int conn_id;
    int running;
    pthread_t th;
    /* deferred: mux must be stopped after lockdown stops */
    aad_lockdown **list;
};

/* global active session (one lockdown stream at a time) */
static pthread_mutex_t ld_list_lock = PTHREAD_MUTEX_INITIALIZER;
static aad_lockdown *g_active = NULL;

/* ---------------- identity ---------------- */

static aad_plist *ident_node(const char *key)
{
    if (!key) return NULL;
    if (!strcmp(key, "QueryType"))         return aad_plist_new_string("com.apple.mobile.lockdown");
    if (!strcmp(key, "Product"))           return aad_plist_new_string(AAD_PRODUCT);
    if (!strcmp(key, "ProductType"))       return aad_plist_new_string("iPhone14,5");
    if (!strcmp(key, "ProductVersion"))    return aad_plist_new_string("17.4");
    if (!strcmp(key, "BuildVersion"))      return aad_plist_new_string("21E219");
    if (!strcmp(key, "DeviceClass"))       return aad_plist_new_string("iPhone");
    if (!strcmp(key, "DeviceName"))        return aad_plist_new_string(AAD_PRODUCT);
    if (!strcmp(key, "UniqueDeviceID"))    return aad_plist_new_string(AAD_UDID);
    if (!strcmp(key, "SerialNumber"))      return aad_plist_new_string("X2C7CQKZ8F2L");
    if (!strcmp(key, "ModelNumber"))       return aad_plist_new_string("MF112LP/A");
    if (!strcmp(key, "WiFiAddress"))       return aad_plist_new_string(AAD_WIFI_MAC);
    if (!strcmp(key, "ProtocolVersion"))   return aad_plist_new_int(12);
    if (!strcmp(key, "DevicePublicKey")) {
        return aad_plist_new_data(AAD_DEVICE_PUB_PEM, strlen(AAD_DEVICE_PUB_PEM));
    }
    if (!strcmp(key, "Timezone")) {
        aad_plist *a = aad_plist_new_array();
        aad_plist_array_append(a, aad_plist_new_string("CST"));
        return a;
    }
    if (!strcmp(key, "DisplayResolution")) {
        aad_plist *a = aad_plist_new_array();
        aad_plist_array_append(a, aad_plist_new_string("1170x2532"));
        return a;
    }
    return NULL;
}

/* ---------------- stream helpers ---------------- */

/* read exactly len bytes from the mux conn (loop over short reads) */
static int rd_full(int conn_id, unsigned char *buf, int len, int timeout_ms)
{
    int off = 0;
    while (off < len) {
        int n = aad_mux_read(conn_id, buf + off, (size_t)(len - off), timeout_ms);
        if (n < 0) return -1;
        if (n == 0) {
            /* timeout with nothing: keep waiting but re-check liveness */
            if (!aad_mux_conn_alive(conn_id)) return -1;
            continue;
        }
        off += n;
    }
    return 0;
}

static int wr_full(int conn_id, const void *buf, size_t len)
{
    return aad_mux_write(conn_id, buf, len) == (int)len ? 0 : -1;
}

static int send_plist(int conn_id, const aad_plist *pl)
{
    size_t len = 0;
    char *xml = aad_plist_render(pl, &len);
    if (!xml) return -1;
    unsigned char hdr[4];
    hdr[0] = (unsigned char)((len >> 24) & 0xff);
    hdr[1] = (unsigned char)((len >> 16) & 0xff);
    hdr[2] = (unsigned char)((len >> 8) & 0xff);
    hdr[3] = (unsigned char)(len & 0xff);
    int rc = 0;
    if (wr_full(conn_id, hdr, 4) != 0) rc = -1;
    else if (len && wr_full(conn_id, xml, len) != 0) rc = -1;
    free(xml);
    return rc;
}

/* ---------------- pair record persistence ---------------- */

static void sanitize_hostid(const char *in, char *out, size_t outlen)
{
    size_t n = 0;
    for (const char *p = in; p && *p && n + 1 < outlen; p++) {
        char c = *p;
        if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') ||
            (c >= 'A' && c <= 'F') || c == '-' || c == '_')
            out[n++] = (c >= 'A' && c <= 'F') ? (char)(c - 'A' + 'a') : c;
    }
    out[n] = 0;
    if (n == 0) snprintf(out, outlen, "host");
}

/* forward: used by save_pair_record below, defined in the handlers section */
static aad_plist *clone_node_shallow(const aad_plist *n);

static int save_pair_record(const char *hostid, const aad_plist *record,
                            const unsigned char *escrow, size_t escrow_len)
{
    char fname[256], path[512];
    sanitize_hostid(hostid, fname, sizeof(fname));
    snprintf(path, sizeof(path), "%s/%s", PAIR_DIR, fname);
    /* wrapper: {HostID, PairRecord, EscrowBag} rendered to disk */
    aad_plist *w = aad_plist_new_dict();
    {
        const char *hid = aad_plist_get_string(record, "HostID", NULL);
        if (hid) aad_plist_dict_set(w, "HostID", aad_plist_new_string(hid));
        aad_plist_dict_set(w, "PairRecord", clone_node_shallow(record));
        if (escrow)
            aad_plist_dict_set(w, "EscrowBag", aad_plist_new_data(escrow, escrow_len));
    }
    char *xml;
    size_t xml_len;
    xml = aad_plist_render(w, &xml_len);
    aad_plist_free(w);
    if (!xml) return -1;
    aad_mkdirs(ID_DIR);
    aad_mkdirs(PAIR_DIR);
    int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0600);
    if (fd < 0) { free(xml); return -1; }
    ssize_t r = write(fd, xml, xml_len);
    close(fd);
    free(xml);
    return (r == (ssize_t)xml_len) ? 0 : -1;
}

/* read back a stored record dict + escrow (NULLs on miss) */
static void load_pair_record(const char *hostid, aad_plist **out_record,
                             unsigned char **out_escrow, size_t *out_escrow_len)
{
    *out_record = NULL;
    *out_escrow = NULL;
    *out_escrow_len = 0;
    char fname[256], path[512];
    sanitize_hostid(hostid, fname, sizeof(fname));
    snprintf(path, sizeof(path), "%s/%s", PAIR_DIR, fname);
    FILE *f = fopen(path, "rb");
    if (!f) return;
    fseek(f, 0, SEEK_END);
    long sz = ftell(f);
    fseek(f, 0, SEEK_SET);
    if (sz <= 0 || sz > (4 * 1024 * 1024)) { fclose(f); return; }
    char *buf = malloc((size_t)sz + 1);
    size_t got = fread(buf, 1, (size_t)sz, f);
    fclose(f);
    buf[got] = 0;
    aad_plist *doc = aad_plist_parse(buf, got);
    free(buf);
    if (!doc) return;
    const aad_plist *rec = aad_plist_get(doc, "PairRecord");
    if (rec) *out_record = (aad_plist *)rec; /* transfer ownership */
    const aad_plist *eb = aad_plist_get(doc, "EscrowBag");
    size_t el = 0;
    const unsigned char *ed = aad_plist_get_data(eb, &el);
    if (ed && el) {
        *out_escrow = malloc(el);
        memcpy(*out_escrow, ed, el);
        *out_escrow_len = el;
    }
    aad_plist_free(doc);
}

/* ---------------- request handlers ---------------- */

static void resp_basic(aad_plist **out, const char *request)
{
    aad_plist *d = aad_plist_new_dict();
    aad_plist_dict_set(d, "Request", aad_plist_new_string(request));
    aad_plist_dict_set(d, "Result", aad_plist_new_string(request));
    *out = d;
}

static void handle_getvalue(const aad_plist *req, aad_plist **out)
{
    aad_plist *d = aad_plist_new_dict();
    aad_plist_dict_set(d, "Request", aad_plist_new_string("GetValue"));
    aad_plist_dict_set(d, "Result", aad_plist_new_string("GetValue"));
    const char *key = aad_plist_get_string(req, "Key", NULL);
    if (key && key[0]) {
        aad_plist *v = ident_node(key);
        if (v) aad_plist_dict_set(d, key, v);
        /* unknown key: no value node (host treats as "not available") */
    } else {
        /* GetValue without Key: full identity table */
        static const char *keys[] = {
            "Product", "ProductType", "ProductVersion", "BuildVersion",
            "DeviceClass", "DeviceName", "UniqueDeviceID", "SerialNumber",
            "ModelNumber", "WiFiAddress", "ProtocolVersion", "DevicePublicKey",
        };
        for (size_t i = 0; i < sizeof(keys) / sizeof(keys[0]); i++) {
            aad_plist *v = ident_node(keys[i]);
            if (v) aad_plist_dict_set(d, keys[i], v);
        }
    }
    *out = d;
}

static aad_plist *clone_node_shallow(const aad_plist *n)
{
    /* rebuild a node with fresh owned copies (values re-copied) */
    if (!n) return NULL;
    switch (n->type) {
    case 1: { /* dict */
        aad_plist *d = aad_plist_new_dict();
        for (int i = 0; i < n->count; i++) {
            const aad_plist *c = n->items[i];
            aad_plist *cc = clone_node_shallow(c);
            if (cc) aad_plist_dict_set(d, c->key ? c->key : "", cc);
        }
        return d;
    }
    case 2: {
        aad_plist *a = aad_plist_new_array();
        for (int i = 0; i < n->count; i++) {
            aad_plist *cc = clone_node_shallow(n->items[i]);
            if (cc) aad_plist_dict_set(a, "", cc);
        }
        return a;
    }
    case 3:
        return aad_plist_new_string(n->str ? n->str : "");
    case 4:
        return aad_plist_new_data(n->data, n->data_len);
    case 5:
        return aad_plist_new_int((int)n->ival);
    case 6:
        return aad_plist_new_bool(n->bval);
    default:
        return NULL;
    }
}

static void handle_pair(const aad_plist *req, aad_plist **out)
{
    aad_plist *d = aad_plist_new_dict();
    aad_plist_dict_set(d, "Request", aad_plist_new_string("Pair"));
    aad_plist_dict_set(d, "Result", aad_plist_new_string("Pair"));

    const aad_plist *inrec = aad_plist_get(req, "PairRecord");
    aad_plist *rec = aad_plist_new_dict();

    /* host-side fields: adopt from request when present, else synthesize */
    const aad_plist *h;
    if ((h = inrec ? aad_plist_get(inrec, "HostID") : NULL) && h->type == 3)
        aad_plist_dict_set(rec, "HostID", aad_plist_new_string(h->str ? h->str : ""));
    else {
        char hostid[45];
        aad_random_bytes((unsigned char *)hostid, 20);
        for (int i = 0; i < 40; i++)
            hostid[i] = "0123456789abcdef"[(hostid[i >> 1] >> (4 * (i % 2 ? 0 : 4))) & 0xf];
        hostid[40] = 0;
        aad_plist_dict_set(rec, "HostID", aad_plist_new_string(hostid));
    }
    if ((h = inrec ? aad_plist_get(inrec, "SystemBUID") : NULL) && h->type == 3)
        aad_plist_dict_set(rec, "SystemBUID",
                            aad_plist_new_string(h->str ? h->str : "com.apple.lockdown"));
    else
        aad_plist_dict_set(rec, "SystemBUID", aad_plist_new_string("com.apple.lockdown"));

    /* certificates: host's where provided, device's otherwise (PEM in <data>) */
    if ((h = inrec ? aad_plist_get(inrec, "HostCertificate") : NULL) && h->type == 4)
        aad_plist_dict_set(rec, "HostCertificate", aad_plist_new_data(h->data, h->data_len));
    else {
        /* no host cert supplied (first run, older hosts): present our root as
         * the record root and a self-issued host placeholder */
        aad_plist_dict_set(rec, "HostCertificate",
                            aad_plist_new_data(AAD_ROOT_CERT_PEM, strlen(AAD_ROOT_CERT_PEM)));
    }
    if ((h = inrec ? aad_plist_get(inrec, "RootCertificate") : NULL) && h->type == 4)
        aad_plist_dict_set(rec, "RootCertificate", aad_plist_new_data(h->data, h->data_len));
    else
        aad_plist_dict_set(rec, "RootCertificate",
                            aad_plist_new_data(AAD_ROOT_CERT_PEM, strlen(AAD_ROOT_CERT_PEM)));

    /* device side (ours) */
    aad_plist_dict_set(rec, "DevicePublicKey",
                       aad_plist_new_data(AAD_DEVICE_PUB_PEM, strlen(AAD_DEVICE_PUB_PEM)));
    aad_plist_dict_set(rec, "DeviceCertificate",
                       aad_plist_new_data(AAD_DEVICE_CERT_PEM, strlen(AAD_DEVICE_CERT_PEM)));
    aad_plist_dict_set(rec, "WiFiAddress", aad_plist_new_string(AAD_WIFI_MAC));

    /* escrow: fresh random blob (auto-trust: we never reject) */
    unsigned char escrow[256];
    aad_random_bytes(escrow, sizeof(escrow));
    aad_plist_dict_set(rec, "EscrowBag", aad_plist_new_data(escrow, sizeof(escrow)));

    const char *hostid = aad_plist_get_string(rec, "HostID", NULL);
    if (hostid)
        save_pair_record(hostid, rec, escrow, sizeof(escrow));

    aad_plist_dict_set(d, "PairRecord", rec);
    *out = d;
    LOGI("lockdown: Pair accepted (hostid=%.12s…)", hostid ? hostid : "?");
}

static void handle_validate_pair(const aad_plist *req, aad_plist **out)
{
    aad_plist *d = aad_plist_new_dict();
    aad_plist_dict_set(d, "Request", aad_plist_new_string("ValidatePair"));
    aad_plist_dict_set(d, "Result", aad_plist_new_string("ValidatePair"));
    const aad_plist *inrec = aad_plist_get(req, "PairRecord");
    const char *hostid = inrec ? aad_plist_get_string(inrec, "HostID", NULL) : NULL;

    aad_plist *stored = NULL;
    unsigned char *escrow = NULL;
    size_t escrow_len = 0;
    if (hostid)
        load_pair_record(hostid, &stored, &escrow, &escrow_len);
    if (!stored) {
        /* no record yet (host validated before we saw Pair, or new host):
         * accept whatever it sent — auto-trust */
        if (inrec) stored = clone_node_shallow(inrec);
        else stored = NULL;
        if (!stored) {
            aad_plist_free(d);
            resp_basic(out, "ValidatePair");
            return;
        }
    }
    aad_plist_dict_set(d, "PairRecord", stored);
    if (escrow)
        aad_plist_dict_set(d, "EscrowBag", aad_plist_new_data(escrow, escrow_len));
    free(escrow);
    *out = d;
}

static void handle_start_session(aad_lockdown *ld, const aad_plist *req, aad_plist **out)
{
    (void)req;
    aad_plist *d = aad_plist_new_dict();
    aad_plist_dict_set(d, "Request", aad_plist_new_string("StartSession"));
    aad_plist_dict_set(d, "Result", aad_plist_new_string("StartSession"));
    char sid[33];
    aad_random_bytes((unsigned char *)sid, 16);
    for (int i = 0; i < 32; i++)
        sid[i] = "0123456789abcdef"[(sid[i >> 1] >> (4 * (i % 2 ? 0 : 4))) & 0xf];
    sid[32] = 0;
    aad_plist_dict_set(d, "SessionID", aad_plist_new_string(sid));
    aad_plist_dict_set(d, "EnableSessionSSL", aad_plist_new_bool(0)); /* v1: no TLS */
    *out = d;
    if (ld) LOGI("lockdown: StartSession %s (SSL off in v1)", sid);
}

static void handle_start_service(aad_lockdown *ld, const aad_plist *req, aad_plist **out)
{
    (void)ld;
    aad_plist *d = aad_plist_new_dict();
    aad_plist_dict_set(d, "Request", aad_plist_new_string("StartService"));
    aad_plist_dict_set(d, "Result", aad_plist_new_string("StartService"));
    const char *svc = aad_plist_get_string(req, "Service", NULL);
    if (!svc || !svc[0]) {
        aad_plist_free(d);
        resp_basic(out, "StartService");
        aad_plist_dict_set(*out, "Error", aad_plist_new_string("MissingService"));
        return;
    }
    int port = aad_mux_alloc_bridge_port();
    aad_plist_dict_set(d, "Service", aad_plist_new_string(svc));
    aad_plist_dict_set(d, "Port", aad_plist_new_int(port));
    aad_plist_dict_set(d, "EnableServiceSSL", aad_plist_new_bool(0));
    *out = d;
    LOGI("lockdown: StartService %s -> mux port %d", svc, port);
}

/* ---------------- main loop ---------------- */

static void *ld_thread(void *arg)
{
    aad_lockdown *ld = arg;
    int cid = ld->conn_id;
    unsigned char hdr[4];
    static __thread unsigned char body[512 * 1024];

    while (ld->running) {
        if (rd_full(cid, hdr, 4, 120000) != 0)
            break;
        uint32_t len = ((uint32_t)hdr[0] << 24) | ((uint32_t)hdr[1] << 16) |
                       ((uint32_t)hdr[2] << 8) | hdr[3];
        if (len == 0 || len > 512 * 1024) {
            LOGE("lockdown: bad frame length %u — resync", len);
            continue;
        }
        if (rd_full(cid, body, (int)len, 120000) != 0)
            break;
        aad_plist *req = aad_plist_parse((const char *)body, len);
        if (!req) {
            LOGE("lockdown: plist parse failed (%u bytes)", len);
            continue;
        }
        const char *request = aad_plist_get_string(req, "Request", NULL);
        aad_plist *resp = NULL;
        if (!request) {
            resp_basic(&resp, "GetValue");
        } else if (!strcmp(request, "GetValue")) {
            handle_getvalue(req, &resp);
        } else if (!strcmp(request, "SetValue")) {
            resp_basic(&resp, "SetValue");
        } else if (!strcmp(request, "Pair")) {
            handle_pair(req, &resp);
        } else if (!strcmp(request, "ValidatePair")) {
            handle_validate_pair(req, &resp);
        } else if (!strcmp(request, "CheckMode")) {
            aad_plist *d = aad_plist_new_dict();
            aad_plist_dict_set(d, "Request", aad_plist_new_string("CheckMode"));
            aad_plist_dict_set(d, "Result", aad_plist_new_string("CheckMode"));
            aad_plist_dict_set(d, "Mode", aad_plist_new_string("paired"));
            resp = d;
        } else if (!strcmp(request, "StartSession")) {
            handle_start_session(ld, req, &resp);
        } else if (!strcmp(request, "StartService")) {
            handle_start_service(ld, req, &resp);
        } else if (!strcmp(request, "StopSession")) {
            resp_basic(&resp, "StopSession");
        } else if (!strcmp(request, "Unpair")) {
            resp_basic(&resp, "Unpair");
        } else if (!strcmp(request, "ReadPassport")) {
            aad_plist *d = aad_plist_new_dict();
            aad_plist_dict_set(d, "Request", aad_plist_new_string("ReadPassport"));
            aad_plist_dict_set(d, "Result", aad_plist_new_string("ReadPassport"));
            aad_plist_dict_set(d, "Passport", aad_plist_new_data("", 0));
            resp = d;
        } else if (!strcmp(request, "WritePassport")) {
            resp_basic(&resp, "WritePassport");
        } else {
            LOGI("lockdown: unhandled request %s — echoing", request);
            resp_basic(&resp, request);
        }
        if (send_plist(cid, resp) != 0)
            LOGE("lockdown: response send failed");
        aad_plist_free(resp);
        aad_plist_free(req);
    }

    pthread_mutex_lock(&ld_list_lock);
    if (g_active == ld) g_active = NULL;
    pthread_mutex_unlock(&ld_list_lock);
    LOGI("lockdown: session ended (conn %d)", cid);
    return NULL;
}

int aad_lockdown_start(aad_lockdown **out, int conn_id)
{
    aad_lockdown *ld = calloc(1, sizeof(*ld));
    if (!ld) return -1;
    ld->conn_id = conn_id;
    ld->running = 1;
    if (pthread_create(&ld->th, NULL, ld_thread, ld) != 0) {
        free(ld);
        return -1;
    }
    pthread_mutex_lock(&ld_list_lock);
    aad_lockdown *old = g_active;
    g_active = ld;
    pthread_mutex_unlock(&ld_list_lock);
    if (old)
        aad_lockdown_stop(old); /* must run outside the lock (it re-locks) */
    *out = ld;
    LOGI("lockdown: session started on conn %d", conn_id);
    return 0;
}

void aad_lockdown_stop(aad_lockdown *ld)
{
    if (!ld) return;
    ld->running = 0;
    pthread_mutex_lock(&ld_list_lock);
    if (g_active == ld) g_active = NULL;
    pthread_mutex_unlock(&ld_list_lock);
    /* close the conn first: wakes the (possibly blocked) read loop */
    aad_mux_conn_close(ld->conn_id, "lockdown session ended");
    pthread_join(ld->th, NULL);
    free(ld);
}

void aad_lockdown_stop_active(void)
{
    pthread_mutex_lock(&ld_list_lock);
    aad_lockdown *ld = g_active;
    g_active = NULL;
    pthread_mutex_unlock(&ld_list_lock);
    if (ld)
        aad_lockdown_stop(ld);
}

int aad_lockdown_active(void)
{
    pthread_mutex_lock(&ld_list_lock);
    int a = (g_active != NULL);
    pthread_mutex_unlock(&ld_list_lock);
    return a;
}
