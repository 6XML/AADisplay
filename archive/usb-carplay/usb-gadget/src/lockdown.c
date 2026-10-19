/*
 * lockdown.c — lockdownd DEVICE side (fake iPhone).
 *
 * Protocol (mirrors libimobiledevice host src/lockdown.c and DiPlay
 * LockdownCarKitClient/LockdownPairingClient, both verified local):
 *   frame  = 4-byte big-endian length + XML plist
 *   GetValue        -> identity table (DevicePublicKey = PKCS#1 PEM <data>)
 *   SetValue        -> accept
 *   Pair            -> auto-trust: accept/adopt the host record, reply with
 *                      full record + random EscrowBag, persist per HostID
 *   ValidatePair    -> echo stored record + EscrowBag
 *   CheckMode       -> "paired"
 *   StartSession    -> SessionID + EnableSessionSSL:true → TLS upgrade
 *   StartService    -> dynamic usbmux port + EnableServiceSSL:false
 *   StopSession/Unpair/ReadPassport/WritePassport/others -> minimal ack
 *
 * PairRecord: certs are X509 DER in <data> (MobileDevice/SecureTransport;
 * PEM caused TLSV1_ALERT_UNKNOWN_CA on AMDeviceStartSession). WiFi field is
 * WiFiMACAddress (GetValue key remains WiFiAddress). EscrowBag random.
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

/* Crash-proof trace for Mac MobileDevice bring-up (logcat can drop). */
static void ld_trace(const char *msg)
{
    int fd = open("/data/adb/aadusb/ld_trace.log",
                  O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0644);
    if (fd < 0) return;
    dprintf(fd, "%s\n", msg ? msg : "");
    close(fd);
}

struct aad_lockdown {
    int conn_id;
    int running;
    int want_ssl; /* set by StartSession; accept TLS after plaintext reply */
    /* Host-signed DeviceCertificate from Pair (for TLS leaf). */
    unsigned char *ssl_leaf;
    size_t ssl_leaf_len;
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
    if (!strcmp(key, "ProductName"))       return aad_plist_new_string(AAD_PRODUCT); /* Simulator */
    if (!strcmp(key, "ProductType"))       return aad_plist_new_string("iPhone14,5");
    if (!strcmp(key, "ProductVersion"))    return aad_plist_new_string("17.4");
    if (!strcmp(key, "BuildVersion"))      return aad_plist_new_string("21E219");
    if (!strcmp(key, "DeviceClass"))       return aad_plist_new_string("iPhone");
    if (!strcmp(key, "DeviceName"))        return aad_plist_new_string(AAD_PRODUCT);
    if (!strcmp(key, "UniqueDeviceID"))    return aad_plist_new_string(AAD_UDID);
    if (!strcmp(key, "SerialNumber"))      return aad_plist_new_string("X2C7CQKZ8F2L");
    if (!strcmp(key, "ModelNumber"))       return aad_plist_new_string("MF112LP/A");
    if (!strcmp(key, "HardwareModel"))     return aad_plist_new_string("D16AP"); /* iPhone14,5 */
    if (!strcmp(key, "MarketingName"))     return aad_plist_new_string("iPhone 13 mini");
    if (!strcmp(key, "CPUArchitecture"))   return aad_plist_new_string("arm64e");
    if (!strcmp(key, "DeviceColor"))       return aad_plist_new_string("1");
    if (!strcmp(key, "DeviceEnclosureColor")) return aad_plist_new_string("1");
    if (!strcmp(key, "HardwarePlatform"))  return aad_plist_new_string("t8110");
    if (!strcmp(key, "WiFiAddress"))       return aad_plist_new_string(AAD_WIFI_MAC);
    if (!strcmp(key, "BluetoothAddress"))  return aad_plist_new_string("52:54:00:a1:b2:c4");
    if (!strcmp(key, "EthernetAddress"))   return aad_plist_new_string(AAD_WIFI_MAC);
    /* ChipID == UniqueChipID for MobileDevice / CarPlay Simulator probes. */
    if (!strcmp(key, "ChipID") || !strcmp(key, "UniqueChipID"))
        return aad_plist_new_int(0x00165a2e);
    /* MobileDevice / CarPlay Simulator probe these before showing UI. */
    if (!strcmp(key, "ActivationState"))   return aad_plist_new_string("Activated");
    if (!strcmp(key, "ActivationStateAcknowledged")) return aad_plist_new_bool(1);
    if (!strcmp(key, "PasswordProtected")) return aad_plist_new_bool(0);
    if (!strcmp(key, "BrickState"))        return aad_plist_new_bool(0);
    if (!strcmp(key, "ProductionSOC"))     return aad_plist_new_bool(1);
    if (!strcmp(key, "Uses24HourClock"))   return aad_plist_new_bool(1);
    if (!strcmp(key, "TimeZone"))          return aad_plist_new_string("Asia/Shanghai");
    if (!strcmp(key, "ProtocolVersion"))   return aad_plist_new_int(2);
    /* SEP / production status — Simulator probes these after ProductName. */
    if (!strcmp(key, "SecurityDomain"))
        return aad_plist_new_int(1);
    if (!strcmp(key, "EffectiveProductionStatusAp") ||
        !strcmp(key, "EffectiveSecurityModeAp") ||
        !strcmp(key, "ProductionStatus") ||
        !strcmp(key, "SecurityMode"))
        return aad_plist_new_bool(1);
    /* CarPlay Simulator / MobileDevice capability probes (MissingValue → no UI). */
    if (!strcmp(key, "SupportsCarPlayWireless") ||
        !strcmp(key, "CarPlayCapability") ||
        !strcmp(key, "SupportsCarPlay") ||
        !strcmp(key, "WirelessCarPlaySupport") ||
        !strcmp(key, "TelephonyCapability") ||
        !strcmp(key, "BasebandStatus") ||
        !strcmp(key, "HasWiFi") ||
        !strcmp(key, "WifiConnected") ||
        !strcmp(key, "TrustedHostAttached"))
        return aad_plist_new_bool(1);
    if (!strcmp(key, "BasebandVersion"))
        return aad_plist_new_string("1.00.00");
    if (!strcmp(key, "FirmwareVersion"))
        return aad_plist_new_string("iBoot-10151.101.3");
    if (!strcmp(key, "RegionInfo"))
        return aad_plist_new_string("LL/A");
    if (!strcmp(key, "MLBSerialNumber"))
        return aad_plist_new_string("F3Y123456789");
    if (!strcmp(key, "SupportedDeviceFamilies")) {
        aad_plist *a = aad_plist_new_array();
        aad_plist_array_append(a, aad_plist_new_int(1)); /* iPhone */
        return a;
    }
    if (!strcmp(key, "DevicePublicKey")) {
        /* PKCS#1 "BEGIN RSA PUBLIC KEY" PEM in <data> (real lockdownd) */
        return aad_plist_new_data(AAD_DEVICE_PUB_PEM, strlen(AAD_DEVICE_PUB_PEM));
    }
    /* MobileDevice builds /var/db/lockdown pair records from these GetValues
     * when Pair only returns EscrowBag (real lockdownd shape). Must match
     * the leaf/CA we present on StartSession TLS. */
    if (!strcmp(key, "DeviceCertificate"))
        return aad_plist_new_data(AAD_DEVICE_CERT_DER, AAD_DEVICE_CERT_DER_LEN);
    if (!strcmp(key, "RootCertificate"))
        return aad_plist_new_data(AAD_ROOT_CERT_DER, AAD_ROOT_CERT_DER_LEN);
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
        int n;
        if (aad_lockdown_ssl_active(conn_id))
            n = aad_lockdown_ssl_read(conn_id, buf + off, (size_t)(len - off), timeout_ms);
        else
            n = aad_mux_read(conn_id, buf + off, (size_t)(len - off), timeout_ms);
        if (n < 0) {
            char tb[80];
            snprintf(tb, sizeof(tb), "rd_full fail conn=%d off=%d/%d ssl=%d",
                     conn_id, off, len, aad_lockdown_ssl_active(conn_id));
            ld_trace(tb);
            return -1;
        }
        if (n == 0) {
            /* timeout with nothing: keep waiting but re-check liveness */
            if (!aad_mux_conn_alive(conn_id)) {
                ld_trace("rd_full dead conn");
                return -1;
            }
            continue;
        }
        off += n;
    }
    return 0;
}

static int wr_full(int conn_id, const void *buf, size_t len)
{
    if (aad_lockdown_ssl_active(conn_id))
        return aad_lockdown_ssl_write(conn_id, buf, len) == (int)len ? 0 : -1;
    return aad_mux_write(conn_id, buf, len) == (int)len ? 0 : -1;
}

/* Serialize lockdown replies across parallel preflight conns — concurrent
 * FunctionFS IN writes were intermittently failing (alive=1, no USB errno). */
static pthread_mutex_t resp_tx_lock = PTHREAD_MUTEX_INITIALIZER;

static int send_plist(int conn_id, const aad_plist *pl)
{
    size_t len = 0;
    char *xml = aad_plist_render(pl, &len);
    if (!xml) {
        LOGE("lockdown: plist render failed");
        return -1;
    }
    unsigned char hdr[4];
    hdr[0] = (unsigned char)((len >> 24) & 0xff);
    hdr[1] = (unsigned char)((len >> 16) & 0xff);
    hdr[2] = (unsigned char)((len >> 8) & 0xff);
    hdr[3] = (unsigned char)(len & 0xff);
    int rc = 0;
    pthread_mutex_lock(&resp_tx_lock);
    if (wr_full(conn_id, hdr, 4) != 0) {
        LOGE("lockdown: hdr write failed conn=%d alive=%d len=%zu",
             conn_id, aad_mux_conn_alive(conn_id), len);
        rc = -1;
    } else if (len && wr_full(conn_id, xml, len) != 0) {
        LOGE("lockdown: body write failed conn=%d alive=%d len=%zu",
             conn_id, aad_mux_conn_alive(conn_id), len);
        rc = -1;
    }
    pthread_mutex_unlock(&resp_tx_lock);
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
    if (rec) *out_record = clone_node_shallow(rec);
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

/* iOS 5+: omit Result when successful. If present it MUST be "Success" —
 * libimobiledevice lockdown_check_result rejects any other string (e.g. the
 * request name) as UNKNOWN_ERROR. Never echo the request into Result. */
static void resp_ok(aad_plist **out, const char *request)
{
    aad_plist *d = aad_plist_new_dict();
    aad_plist_dict_set(d, "Request", aad_plist_new_string(request));
    *out = d;
}

static void handle_getvalue(const aad_plist *req, aad_plist **out)
{
    aad_plist *d = aad_plist_new_dict();
    aad_plist_dict_set(d, "Request", aad_plist_new_string("GetValue"));
    const char *key = aad_plist_get_string(req, "Key", NULL);
    const char *domain = aad_plist_get_string(req, "Domain", NULL);
    LOGI("lockdown: GetValue domain=%s key=%s",
         domain && domain[0] ? domain : "(root)",
         key && key[0] ? key : "(all)");
    /* Domain ignored for v1: answer from root identity so MobileDevice
     * probes (ActivationState etc.) succeed even under named domains. */
    if (key && key[0]) {
        aad_plist *v = ident_node(key);
        aad_plist_dict_set(d, "Key", aad_plist_new_string(key));
        if (v) {
            aad_plist_dict_set(d, "Value", v);
        } else {
            /* Explicit miss — hosts prefer Error over silent empty Value. */
            aad_plist_dict_set(d, "Error", aad_plist_new_string("MissingValue"));
        }
    } else {
        /* GetValue without Key: Value = full identity dict */
        aad_plist *vals = aad_plist_new_dict();
        static const char *keys[] = {
            "Product", "ProductType", "ProductVersion", "BuildVersion",
            "DeviceClass", "DeviceName", "UniqueDeviceID", "SerialNumber",
            "ModelNumber", "HardwareModel", "CPUArchitecture",
            "WiFiAddress", "BluetoothAddress", "EthernetAddress",
            "ActivationState", "ActivationStateAcknowledged",
            "PasswordProtected", "BrickState", "ProductionSOC",
            "ProtocolVersion", "DevicePublicKey", "UniqueChipID",
            "DeviceCertificate", "RootCertificate",
            "TimeZone", "Uses24HourClock",
        };
        for (size_t i = 0; i < sizeof(keys) / sizeof(keys[0]); i++) {
            aad_plist *v = ident_node(keys[i]);
            if (v) aad_plist_dict_set(vals, keys[i], v);
        }
        aad_plist_dict_set(d, "Value", vals);
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
    /* Real lockdownd Pair success is ONLY EscrowBag + Request — no PairRecord
     * in the reply. Host builds /var/db/lockdown from GetValue certs + EscrowBag.
     * Returning PairRecord made AMDeviceValidatePairing → kAMDInvalidResponseError. */
    aad_plist *d = aad_plist_new_dict();
    aad_plist_dict_set(d, "Request", aad_plist_new_string("Pair"));

    const aad_plist *inrec = aad_plist_get(req, "PairRecord");
    aad_plist *rec = aad_plist_new_dict();

    /* Persist host fields so StartSession can require a prior Pair. */
    const aad_plist *h;
    if ((h = inrec ? aad_plist_get(inrec, "HostID") : NULL) && h->type == 3)
        aad_plist_dict_set(rec, "HostID", aad_plist_new_string(h->str ? h->str : ""));
    else {
        char hostid[45];
        unsigned char raw[20];
        aad_random_bytes(raw, sizeof(raw));
        for (int i = 0; i < 20; i++) {
            hostid[i * 2]     = "0123456789abcdef"[(raw[i] >> 4) & 0xf];
            hostid[i * 2 + 1] = "0123456789abcdef"[raw[i] & 0xf];
        }
        hostid[40] = 0;
        aad_plist_dict_set(rec, "HostID", aad_plist_new_string(hostid));
    }
    if ((h = inrec ? aad_plist_get(inrec, "SystemBUID") : NULL) && h->type == 3)
        aad_plist_dict_set(rec, "SystemBUID",
                            aad_plist_new_string(h->str ? h->str : "com.apple.lockdown"));
    else
        aad_plist_dict_set(rec, "SystemBUID", aad_plist_new_string("com.apple.lockdown"));

    if ((h = inrec ? aad_plist_get(inrec, "HostCertificate") : NULL) && h->type == 4)
        aad_plist_dict_set(rec, "HostCertificate", aad_plist_new_data(h->data, h->data_len));
    else {
        aad_plist_dict_set(rec, "HostCertificate",
                            aad_plist_new_data(AAD_ROOT_CERT_DER, AAD_ROOT_CERT_DER_LEN));
    }
    /* Adopt host PairRecord DeviceCertificate + RootCertificate. Mac stores
     * those in /var/db/lockdown; TLS must present the same leaf (host-signed
     * our DevicePublicKey) or SecureTransport alerts UNKNOWN_CA. */
    if ((h = inrec ? aad_plist_get(inrec, "RootCertificate") : NULL) && h->type == 4)
        aad_plist_dict_set(rec, "RootCertificate", aad_plist_new_data(h->data, h->data_len));
    else
        aad_plist_dict_set(rec, "RootCertificate",
                           aad_plist_new_data(AAD_ROOT_CERT_DER, AAD_ROOT_CERT_DER_LEN));
    if ((h = inrec ? aad_plist_get(inrec, "DeviceCertificate") : NULL) && h->type == 4)
        aad_plist_dict_set(rec, "DeviceCertificate", aad_plist_new_data(h->data, h->data_len));
    else
        aad_plist_dict_set(rec, "DeviceCertificate",
                           aad_plist_new_data(AAD_DEVICE_CERT_DER, AAD_DEVICE_CERT_DER_LEN));
    aad_plist_dict_set(rec, "DevicePublicKey",
                       aad_plist_new_data(AAD_DEVICE_PUB_DER, AAD_DEVICE_PUB_DER_LEN));
    aad_plist_dict_set(rec, "WiFiMACAddress", aad_plist_new_string(AAD_WIFI_MAC));

    /* Real EscrowBag is 32 bytes (SHA-256 sized). */
    unsigned char escrow[32];
    aad_random_bytes(escrow, sizeof(escrow));

    const char *hostid = aad_plist_get_string(rec, "HostID", NULL);
    if (hostid)
        save_pair_record(hostid, rec, escrow, sizeof(escrow));
    aad_plist_free(rec);

    aad_plist_dict_set(d, "EscrowBag", aad_plist_new_data(escrow, sizeof(escrow)));
    *out = d;
    LOGI("lockdown: Pair accepted (hostid=%.12s… escrow=32)", hostid ? hostid : "?");
    {
        char tb[96];
        size_t dcl = 0;
        const aad_plist *dc = aad_plist_get(
            aad_plist_get(req, "PairRecord"), "DeviceCertificate");
        (void)aad_plist_get_data(dc, &dcl);
        snprintf(tb, sizeof(tb), "Pair ok host=%.12s devcert=%zu",
                 hostid ? hostid : "?", dcl);
        ld_trace(tb);
    }
}

static void handle_validate_pair(const aad_plist *req, aad_plist **out)
{
    /* MobileDevice ValidatePairing expects a minimal OK (no PairRecord echo).
     * Extra keys → kAMDInvalidResponseError (0xe8000013). */
    const aad_plist *inrec = aad_plist_get(req, "PairRecord");
    const char *hostid = inrec ? aad_plist_get_string(inrec, "HostID", NULL) : NULL;
    aad_plist *stored = NULL;
    unsigned char *escrow = NULL;
    size_t escrow_len = 0;
    if (hostid)
        load_pair_record(hostid, &stored, &escrow, &escrow_len);
    aad_plist_free(stored);
    free(escrow);

    aad_plist *d = aad_plist_new_dict();
    aad_plist_dict_set(d, "Request", aad_plist_new_string("ValidatePair"));
    *out = d;
    LOGI("lockdown: ValidatePair ok (hostid=%.12s…)", hostid ? hostid : "?");
}

static void handle_start_session(aad_lockdown *ld, const aad_plist *req, aad_plist **out)
{
    const char *hostid = aad_plist_get_string(req, "HostID", NULL);
    aad_plist *stored = NULL;
    unsigned char *escrow = NULL;
    size_t escrow_len = 0;
    if (hostid && hostid[0])
        load_pair_record(hostid, &stored, &escrow, &escrow_len);
    free(escrow);

    /* Unpaired StartSession + EnableSessionSSL races MobileDevice Pair and
     * yields TLSV1_ALERT_UNKNOWN_CA (no Root in /var/db/lockdown yet). */
    if (!stored) {
        aad_plist *d = aad_plist_new_dict();
        aad_plist_dict_set(d, "Request", aad_plist_new_string("StartSession"));
        aad_plist_dict_set(d, "Error", aad_plist_new_string("InvalidHostID"));
        *out = d;
        if (ld) ld->want_ssl = 0;
        LOGI("lockdown: StartSession rejected (unpaired hostid=%.12s…)",
             hostid ? hostid : "?");
        return;
    }

    /* Stash Pair DeviceCertificate for TLS leaf on this conn. */
    if (ld) {
        free(ld->ssl_leaf);
        ld->ssl_leaf = NULL;
        ld->ssl_leaf_len = 0;
        size_t cl = 0;
        const unsigned char *cd =
            aad_plist_get_data(aad_plist_get(stored, "DeviceCertificate"), &cl);
        if (cd && cl) {
            ld->ssl_leaf = malloc(cl);
            if (ld->ssl_leaf) {
                memcpy(ld->ssl_leaf, cd, cl);
                ld->ssl_leaf_len = cl;
            }
        }
    }
    aad_plist_free(stored);

    aad_plist *d = aad_plist_new_dict();
    aad_plist_dict_set(d, "Request", aad_plist_new_string("StartSession"));
    char sid[33];
    unsigned char raw[16];
    aad_random_bytes(raw, sizeof(raw));
    for (int i = 0; i < 16; i++) {
        sid[i * 2]     = "0123456789abcdef"[(raw[i] >> 4) & 0xf];
        sid[i * 2 + 1] = "0123456789abcdef"[raw[i] & 0xf];
    }
    sid[32] = 0;
    aad_plist_dict_set(d, "SessionID", aad_plist_new_string(sid));
    aad_plist_dict_set(d, "EnableSessionSSL", aad_plist_new_bool(1));
    *out = d;
    if (ld) {
        ld->want_ssl = 1;
        LOGI("lockdown: StartSession %s (SSL on, leaf=%zu)",
             sid, ld->ssl_leaf_len);
        char tb[128];
        snprintf(tb, sizeof(tb), "StartSession sid=%.16s leaf=%zu host=%.12s",
                 sid, ld->ssl_leaf_len, hostid ? hostid : "?");
        ld_trace(tb);
    }
}

static void handle_start_service(aad_lockdown *ld, const aad_plist *req, aad_plist **out)
{
    (void)ld;
    aad_plist *d = aad_plist_new_dict();
    aad_plist_dict_set(d, "Request", aad_plist_new_string("StartService"));
    const char *svc = aad_plist_get_string(req, "Service", NULL);
    if (!svc || !svc[0]) {
        aad_plist_free(d);
        resp_ok(out, "StartService");
        aad_plist_dict_set(*out, "Error", aad_plist_new_string("MissingService"));
        return;
    }
    int port = aad_mux_alloc_bridge_port();
    aad_plist_dict_set(d, "Service", aad_plist_new_string(svc));
    aad_plist_dict_set(d, "Port", aad_plist_new_int(port));
    aad_plist_dict_set(d, "EnableServiceSSL", aad_plist_new_bool(0));
    *out = d;
    /* Tag the port so mux on_bridge can discard non-carkit probes
     * (macOS insecure_notification_proxy etc.) instead of publishing
     * them as CarPlay carkit. */
    aad_mux_mark_bridge_service(port, svc);
    LOGI("lockdown: StartService %s -> mux port %d", svc, port);
    {
        char tb[160];
        snprintf(tb, sizeof(tb), "StartService %s -> %d", svc, port);
        ld_trace(tb);
    }
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
            /* Byte-scan "resync" used to walk the XML body of QueryType
             * (hdr was already mid-plist) and never answer — usbmuxd then
             * keeps DeviceList empty. Close and let the host open a fresh
             * lockdown TCP. */
            LOGE("lockdown: bad frame length %u (hdr=%02x%02x%02x%02x) — closing",
                 len, hdr[0], hdr[1], hdr[2], hdr[3]);
            break;
        }
        if (rd_full(cid, body, (int)len, 120000) != 0)
            break;
        aad_plist *req = aad_plist_parse((const char *)body, len);
        if (!req) {
            LOGE("lockdown: plist parse failed (%u bytes) head=%02x%02x%02x%02x%02x%02x%02x%02x",
                 len,
                 len > 0 ? body[0] : 0, len > 1 ? body[1] : 0,
                 len > 2 ? body[2] : 0, len > 3 ? body[3] : 0,
                 len > 4 ? body[4] : 0, len > 5 ? body[5] : 0,
                 len > 6 ? body[6] : 0, len > 7 ? body[7] : 0);
            int fd = open("/data/adb/aadusb/last_fail.plist",
                          O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);
            if (fd >= 0) {
                (void)write(fd, body, len);
                close(fd);
            }
            continue;
        }
        {
            const char *rn = aad_plist_get_string(req, "Request", NULL);
            LOGI("lockdown: Request=%s (%u bytes)", rn ?: "(none)", len);
            char tb[120];
            snprintf(tb, sizeof(tb), "req=%s len=%u ssl=%d conn=%d",
                     rn ? rn : "?", len, aad_lockdown_ssl_active(cid), cid);
            ld_trace(tb);
        }
        const char *request = aad_plist_get_string(req, "Request", NULL);
        aad_plist *resp = NULL;
        if (!request) {
            resp_ok(&resp, "GetValue");
        } else if (!strcmp(request, "QueryType")) {
            /* First packet from usbmuxd/idevice — must advertise lockdown. */
            aad_plist *d = aad_plist_new_dict();
            aad_plist_dict_set(d, "Request", aad_plist_new_string("QueryType"));
            aad_plist_dict_set(d, "Type",
                               aad_plist_new_string("com.apple.mobile.lockdown"));
            resp = d;
            LOGI("lockdown: QueryType -> com.apple.mobile.lockdown");
        } else if (!strcmp(request, "GetValue")) {
            handle_getvalue(req, &resp);
        } else if (!strcmp(request, "SetValue")) {
            resp_ok(&resp, "SetValue");
        } else if (!strcmp(request, "Pair")) {
            handle_pair(req, &resp);
        } else if (!strcmp(request, "ValidatePair")) {
            handle_validate_pair(req, &resp);
        } else if (!strcmp(request, "CheckMode")) {
            aad_plist *d = aad_plist_new_dict();
            aad_plist_dict_set(d, "Request", aad_plist_new_string("CheckMode"));
            aad_plist_dict_set(d, "Mode", aad_plist_new_string("paired"));
            resp = d;
        } else if (!strcmp(request, "StartSession")) {
            handle_start_session(ld, req, &resp);
        } else if (!strcmp(request, "StartService")) {
            handle_start_service(ld, req, &resp);
        } else if (!strcmp(request, "StopSession")) {
            resp_ok(&resp, "StopSession");
        } else if (!strcmp(request, "Unpair")) {
            resp_ok(&resp, "Unpair");
        } else if (!strcmp(request, "ReadPassport")) {
            aad_plist *d = aad_plist_new_dict();
            aad_plist_dict_set(d, "Request", aad_plist_new_string("ReadPassport"));
            aad_plist_dict_set(d, "Passport", aad_plist_new_data("", 0));
            resp = d;
        } else if (!strcmp(request, "WritePassport")) {
            resp_ok(&resp, "WritePassport");
        } else {
            LOGI("lockdown: unhandled request %s — echoing", request);
            resp_ok(&resp, request);
        }
        if (send_plist(cid, resp) != 0) {
            LOGE("lockdown: response send failed (conn %d alive=%d req=%s)",
                 cid, aad_mux_conn_alive(cid),
                 request ? request : "?");
        } else {
            LOGI("lockdown: response ok (conn %d req=%s)",
                 cid, request ? request : "?");
            /* Host upgrades this mux connection to TLS immediately after
             * reading EnableSessionSSL:true — accept before next plist. */
            if (ld->want_ssl && request && !strcmp(request, "StartSession")) {
                ld->want_ssl = 0;
                char tb[96];
                snprintf(tb, sizeof(tb), "TLS accept begin conn=%d leaf=%zu",
                         cid, ld->ssl_leaf_len);
                ld_trace(tb);
                int arc = aad_lockdown_ssl_accept(cid, ld->ssl_leaf, ld->ssl_leaf_len);
                snprintf(tb, sizeof(tb), "TLS accept end conn=%d rc=%d", cid, arc);
                ld_trace(tb);
                if (arc != 0) {
                    LOGE("lockdown: TLS accept failed conn=%d — closing", cid);
                    aad_plist_free(resp);
                    aad_plist_free(req);
                    break; /* Mac already mid-TLS; plaintext resync is impossible */
                }
            }
        }
        aad_plist_free(resp);
        aad_plist_free(req);
    }

    aad_lockdown_ssl_close(cid);
    free(ld->ssl_leaf);
    ld->ssl_leaf = NULL;
    ld->ssl_leaf_len = 0;
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
    /* usbmuxd opens several parallel lockdown conns during preflight —
     * never tear down an existing session when a new one arrives. */
    pthread_mutex_lock(&ld_list_lock);
    g_active = ld;
    pthread_mutex_unlock(&ld_list_lock);
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
