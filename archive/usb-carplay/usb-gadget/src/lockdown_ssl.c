/*
 * lockdown_ssl.c — TLS upgrade for lockdown after EnableSessionSSL:true.
 *
 * Uses Android's system libssl/libcrypto via dlopen (BoringSSL-compatible
 * OpenSSL 1.1 surface) so the Magisk binary stays free of a vendored TLS tree.
 * Device presents AAD_DEVICE_CERT_PEM + AAD_DEVICE_KEY_PEM as TLS server.
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <pthread.h>
#include <string.h>
#include <stdlib.h>
#include <stdint.h>

#include "aad.h"

/* Minimal OpenSSL 1.1 / BoringSSL declarations (avoid shipping headers). */
typedef struct ssl_st SSL;
typedef struct ssl_ctx_st SSL_CTX;
typedef struct ssl_method_st SSL_METHOD;
typedef struct bio_st BIO;
typedef struct bio_method_st BIO_METHOD;
typedef struct evp_pkey_st EVP_PKEY;
typedef struct x509_st X509;
typedef struct bio_method_st BIO_METHOD;

#define SSL_FILETYPE_PEM 1
#define BIO_TYPE_SOURCE_SINK 0x0400
#define BIO_FLAGS_READ 0x01
#define BIO_FLAGS_WRITE 0x02
#define BIO_FLAGS_IO_SPECIAL 0x04
#define BIO_FLAGS_SHOULD_RETRY 0x08

static void *Hssl, *Hcrypto;
static SSL_CTX *g_ctx;
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;

/* per-conn SSL */
#define SSL_SLOT_MAX 16
static struct {
    int conn_id;
    SSL *ssl;
    BIO *rbio;
    BIO *wbio;
} g_slots[SSL_SLOT_MAX];

/* dlsym helpers */
static void *(*p_SSL_CTX_new)(const SSL_METHOD *);
static void (*p_SSL_CTX_free)(SSL_CTX *);
static const SSL_METHOD *(*p_TLS_server_method)(void);
static int (*p_SSL_CTX_use_PrivateKey)(SSL_CTX *, EVP_PKEY *);
static int (*p_SSL_CTX_check_private_key)(const SSL_CTX *);
static void (*p_SSL_CTX_set_verify)(SSL_CTX *, int, void *);
static SSL *(*p_SSL_new)(SSL_CTX *);
static void (*p_SSL_free)(SSL *);
static void (*p_SSL_set_bio)(SSL *, BIO *, BIO *);
static void (*p_SSL_set_accept_state)(SSL *);
static void (*p_SSL_set_verify)(SSL *, int, void *);
static int (*p_SSL_accept)(SSL *);
static int (*p_SSL_read)(SSL *, void *, int);
static int (*p_SSL_write)(SSL *, const void *, int);
static int (*p_SSL_get_error)(const SSL *, int);
static unsigned long (*p_ERR_get_error)(void);
static char *(*p_ERR_error_string)(unsigned long, char *);
static X509 *(*p_d2i_X509)(X509 **, const unsigned char **, long);
static X509 *(*p_PEM_read_bio_X509)(BIO *, X509 **, void *, void *);
static int (*p_SSL_CTX_use_certificate)(SSL_CTX *, X509 *);
static int (*p_SSL_use_certificate)(SSL *, X509 *);
static int (*p_SSL_CTX_add1_chain_cert)(SSL_CTX *, X509 *);
static void (*p_X509_free)(X509 *);
static BIO_METHOD *(*p_BIO_meth_new)(int, const char *);
static int (*p_BIO_meth_set_write)(BIO_METHOD *, int (*)(BIO *, const char *, int));
static int (*p_BIO_meth_set_read)(BIO_METHOD *, int (*)(BIO *, char *, int));
static int (*p_BIO_meth_set_ctrl)(BIO_METHOD *, long (*)(BIO *, int, long, void *));
static int (*p_BIO_meth_set_create)(BIO_METHOD *, int (*)(BIO *));
static int (*p_BIO_meth_set_destroy)(BIO_METHOD *, int (*)(BIO *));
static BIO *(*p_BIO_new)(const BIO_METHOD *);
static void (*p_BIO_free)(BIO *);
static void (*p_BIO_set_data)(BIO *, void *);
static void *(*p_BIO_get_data)(BIO *);
static void (*p_BIO_set_init)(BIO *, int);
static void (*p_BIO_set_flags)(BIO *, int);
static void (*p_BIO_clear_flags)(BIO *, int);
static BIO *(*p_BIO_new_mem_buf)(const void *, int);
static EVP_PKEY *(*p_PEM_read_bio_PrivateKey)(BIO *, EVP_PKEY **, void *, void *);
static void (*p_EVP_PKEY_free)(EVP_PKEY *);
static int (*p_OPENSSL_init_ssl)(uint64_t, void *);

static BIO_METHOD *g_mux_meth;

static int mux_bio_write(BIO *b, const char *buf, int len)
{
    int cid = (int)(intptr_t)p_BIO_get_data(b);
    if (len <= 0) return 0;
    int n = aad_mux_write(cid, buf, (size_t)len);
    if (n < 0) return -1;
    if (n == 0) {
        p_BIO_set_flags(b, BIO_FLAGS_WRITE | BIO_FLAGS_SHOULD_RETRY);
        return -1;
    }
    return n;
}

static int mux_bio_read(BIO *b, char *buf, int len)
{
    int cid = (int)(intptr_t)p_BIO_get_data(b);
    if (len <= 0) return 0;
    int n = aad_mux_read(cid, buf, (size_t)len, 5000);
    if (n < 0) return -1;
    if (n == 0) {
        p_BIO_set_flags(b, BIO_FLAGS_READ | BIO_FLAGS_SHOULD_RETRY);
        return -1;
    }
    return n;
}

static long mux_bio_ctrl(BIO *b, int cmd, long num, void *ptr)
{
    (void)b; (void)num; (void)ptr;
    /* BIO_CTRL_FLUSH = 11 */
    if (cmd == 11) return 1;
    return 0;
}

static int mux_bio_create(BIO *b)
{
    p_BIO_set_init(b, 1);
    return 1;
}

static int mux_bio_destroy(BIO *b)
{
    (void)b;
    return 1;
}

static int load_syms(void)
{
    Hcrypto = dlopen("libcrypto.so", RTLD_NOW);
    Hssl = dlopen("libssl.so", RTLD_NOW);
    if (!Hcrypto || !Hssl) {
        LOGE("lockdown_ssl: dlopen failed crypto=%p ssl=%p err=%s",
             Hcrypto, Hssl, dlerror());
        return -1;
    }
#define SYM(h, p, n) do { \
    p = dlsym(h, n); \
    if (!p) { LOGE("lockdown_ssl: missing %s", n); return -1; } \
} while (0)
    SYM(Hssl, p_OPENSSL_init_ssl, "OPENSSL_init_ssl");
    SYM(Hssl, p_SSL_CTX_new, "SSL_CTX_new");
    SYM(Hssl, p_SSL_CTX_free, "SSL_CTX_free");
    SYM(Hssl, p_TLS_server_method, "TLS_server_method");
    SYM(Hssl, p_SSL_CTX_use_PrivateKey, "SSL_CTX_use_PrivateKey");
    SYM(Hssl, p_SSL_CTX_check_private_key, "SSL_CTX_check_private_key");
    SYM(Hssl, p_SSL_CTX_use_certificate, "SSL_CTX_use_certificate");
    SYM(Hssl, p_SSL_use_certificate, "SSL_use_certificate");
    /* optional chain helper — may be missing on older BoringSSL */
    p_SSL_CTX_add1_chain_cert = dlsym(Hssl, "SSL_CTX_add1_chain_cert");
    if (!p_SSL_CTX_add1_chain_cert)
        p_SSL_CTX_add1_chain_cert = dlsym(Hssl, "SSL_CTX_add_extra_chain_cert");
    p_SSL_CTX_set_verify = dlsym(Hssl, "SSL_CTX_set_verify");
    p_SSL_set_verify = dlsym(Hssl, "SSL_set_verify");
    SYM(Hssl, p_SSL_new, "SSL_new");
    SYM(Hssl, p_SSL_free, "SSL_free");
    SYM(Hssl, p_SSL_set_bio, "SSL_set_bio");
    SYM(Hssl, p_SSL_set_accept_state, "SSL_set_accept_state");
    SYM(Hssl, p_SSL_accept, "SSL_accept");
    SYM(Hssl, p_SSL_read, "SSL_read");
    SYM(Hssl, p_SSL_write, "SSL_write");
    SYM(Hssl, p_SSL_get_error, "SSL_get_error");
    SYM(Hcrypto, p_ERR_get_error, "ERR_get_error");
    SYM(Hcrypto, p_ERR_error_string, "ERR_error_string");
    SYM(Hcrypto, p_d2i_X509, "d2i_X509");
    SYM(Hcrypto, p_PEM_read_bio_X509, "PEM_read_bio_X509");
    SYM(Hcrypto, p_X509_free, "X509_free");
    SYM(Hcrypto, p_BIO_meth_new, "BIO_meth_new");
    SYM(Hcrypto, p_BIO_meth_set_write, "BIO_meth_set_write");
    SYM(Hcrypto, p_BIO_meth_set_read, "BIO_meth_set_read");
    SYM(Hcrypto, p_BIO_meth_set_ctrl, "BIO_meth_set_ctrl");
    SYM(Hcrypto, p_BIO_meth_set_create, "BIO_meth_set_create");
    SYM(Hcrypto, p_BIO_meth_set_destroy, "BIO_meth_set_destroy");
    SYM(Hcrypto, p_BIO_new, "BIO_new");
    SYM(Hcrypto, p_BIO_free, "BIO_free");
    SYM(Hcrypto, p_BIO_set_data, "BIO_set_data");
    SYM(Hcrypto, p_BIO_get_data, "BIO_get_data");
    SYM(Hcrypto, p_BIO_set_init, "BIO_set_init");
    SYM(Hcrypto, p_BIO_set_flags, "BIO_set_flags");
    SYM(Hcrypto, p_BIO_clear_flags, "BIO_clear_flags");
    SYM(Hcrypto, p_BIO_new_mem_buf, "BIO_new_mem_buf");
    SYM(Hcrypto, p_PEM_read_bio_PrivateKey, "PEM_read_bio_PrivateKey");
    SYM(Hcrypto, p_EVP_PKEY_free, "EVP_PKEY_free");
#undef SYM
    return 0;
}

int aad_lockdown_ssl_init(void)
{
    pthread_mutex_lock(&g_lock);
    if (g_ctx) { pthread_mutex_unlock(&g_lock); return 0; }
    if (load_syms() != 0) { pthread_mutex_unlock(&g_lock); return -1; }
    p_OPENSSL_init_ssl(0, NULL);
    g_ctx = p_SSL_CTX_new(p_TLS_server_method());
    if (!g_ctx) {
        LOGE("lockdown_ssl: SSL_CTX_new failed");
        pthread_mutex_unlock(&g_lock);
        return -1;
    }
    {
        const unsigned char *p = AAD_DEVICE_CERT_DER;
        X509 *leaf = p_d2i_X509(NULL, &p, (long)AAD_DEVICE_CERT_DER_LEN);
        if (!leaf || p_SSL_CTX_use_certificate(g_ctx, leaf) != 1) {
            LOGE("lockdown_ssl: use_certificate(leaf) failed");
            if (leaf) p_X509_free(leaf);
            pthread_mutex_unlock(&g_lock);
            return -1;
        }
        p_X509_free(leaf);
        /* Do not attach our CA as chain: MobileDevice trusts Host Root from
         * the Pair record and expects the host-signed DeviceCertificate leaf
         * alone. Sending a foreign CA triggered TLSV1_ALERT_UNKNOWN_CA. */
        (void)p_SSL_CTX_add1_chain_cert;
    }
    BIO *kbio = p_BIO_new_mem_buf(AAD_DEVICE_KEY_PEM, -1);
    EVP_PKEY *pkey = p_PEM_read_bio_PrivateKey(kbio, NULL, NULL, NULL);
    p_BIO_free(kbio);
    if (!pkey || p_SSL_CTX_use_PrivateKey(g_ctx, pkey) != 1) {
        LOGE("lockdown_ssl: use_PrivateKey failed");
        if (pkey) p_EVP_PKEY_free(pkey);
        pthread_mutex_unlock(&g_lock);
        return -1;
    }
    p_EVP_PKEY_free(pkey);
    if (p_SSL_CTX_check_private_key(g_ctx) != 1) {
        LOGE("lockdown_ssl: check_private_key failed");
        pthread_mutex_unlock(&g_lock);
        return -1;
    }
    /* MobileDevice sends HostCertificate as client cert. Never fail
     * SSL_accept with TLSV1_ALERT_UNKNOWN_CA on the host side. */
    if (p_SSL_CTX_set_verify)
        p_SSL_CTX_set_verify(g_ctx, 0 /* SSL_VERIFY_NONE */, NULL);
    g_mux_meth = p_BIO_meth_new(BIO_TYPE_SOURCE_SINK | 0x80 /* unique type */, "aad_mux");
    p_BIO_meth_set_write(g_mux_meth, mux_bio_write);
    p_BIO_meth_set_read(g_mux_meth, mux_bio_read);
    p_BIO_meth_set_ctrl(g_mux_meth, mux_bio_ctrl);
    p_BIO_meth_set_create(g_mux_meth, mux_bio_create);
    p_BIO_meth_set_destroy(g_mux_meth, mux_bio_destroy);
    LOGI("lockdown_ssl: ready (system libssl)");
    pthread_mutex_unlock(&g_lock);
    return 0;
}

static int slot_index(int conn_id)
{
    for (int i = 0; i < SSL_SLOT_MAX; i++)
        if (g_slots[i].conn_id == conn_id && g_slots[i].ssl)
            return i;
    return -1;
}

static X509 *x509_from_blob(const unsigned char *data, size_t len)
{
    if (!data || !len) return NULL;
    /* MobileDevice / idevicepair may put PEM text inside plist <data>. */
    if (len >= 10 && data[0] == '-') {
        BIO *b = p_BIO_new_mem_buf(data, (int)len);
        if (!b) return NULL;
        X509 *x = p_PEM_read_bio_X509(b, NULL, NULL, NULL);
        p_BIO_free(b);
        return x;
    }
    const unsigned char *p = data;
    return p_d2i_X509(NULL, &p, (long)len);
}

int aad_lockdown_ssl_accept(int conn_id,
                            const unsigned char *leaf, size_t leaf_len)
{
    if (aad_lockdown_ssl_init() != 0) return -1;
    pthread_mutex_lock(&g_lock);
    int free_i = -1;
    for (int i = 0; i < SSL_SLOT_MAX; i++) {
        if (g_slots[i].ssl && g_slots[i].conn_id == conn_id) {
            pthread_mutex_unlock(&g_lock);
            return 0; /* already */
        }
        if (!g_slots[i].ssl && free_i < 0) free_i = i;
    }
    if (free_i < 0) {
        LOGE("lockdown_ssl: no slot");
        pthread_mutex_unlock(&g_lock);
        return -1;
    }
    SSL *ssl = p_SSL_new(g_ctx);
    /* Prefer host-signed DeviceCertificate from Pair — Mac trusts it via
     * Host Root in /var/db/lockdown. Embedded AAD leaf → UNKNOWN_CA. */
    if (leaf && leaf_len) {
        X509 *x = x509_from_blob(leaf, leaf_len);
        if (x) {
            if (p_SSL_use_certificate(ssl, x) != 1)
                LOGE("lockdown_ssl: SSL_use_certificate(pair leaf) failed");
            else
                LOGI("lockdown_ssl: using Pair DeviceCertificate (%zu bytes)",
                     leaf_len);
            p_X509_free(x);
        } else {
            LOGE("lockdown_ssl: could not parse Pair DeviceCertificate (%zu)",
                 leaf_len);
        }
    }
    BIO *rbio = p_BIO_new(g_mux_meth);
    BIO *wbio = p_BIO_new(g_mux_meth);
    p_BIO_set_data(rbio, (void *)(intptr_t)conn_id);
    p_BIO_set_data(wbio, (void *)(intptr_t)conn_id);
    p_SSL_set_accept_state(ssl);
    if (p_SSL_set_verify)
        p_SSL_set_verify(ssl, 0 /* SSL_VERIFY_NONE */, NULL);
    p_SSL_set_bio(ssl, rbio, wbio);
    g_slots[free_i].conn_id = conn_id;
    g_slots[free_i].ssl = ssl;
    g_slots[free_i].rbio = rbio;
    g_slots[free_i].wbio = wbio;
    pthread_mutex_unlock(&g_lock);

    for (int tries = 0; tries < 200; tries++) {
        int rc = p_SSL_accept(ssl);
        if (rc == 1) {
            LOGI("lockdown_ssl: accept ok conn=%d", conn_id);
            return 0;
        }
        int err = p_SSL_get_error(ssl, rc);
        /* SSL_ERROR_WANT_READ=2 WANT_WRITE=3 */
        if (err == 2 || err == 3)
            continue;
        unsigned long e;
        while ((e = p_ERR_get_error()) != 0) {
            char buf[256];
            p_ERR_error_string(e, buf);
            LOGE("lockdown_ssl: openssl %s", buf);
        }
        LOGE("lockdown_ssl: accept failed conn=%d err=%d rc=%d alive=%d",
             conn_id, err, rc, aad_mux_conn_alive(conn_id));
        aad_lockdown_ssl_close(conn_id);
        return -1;
    }
    LOGE("lockdown_ssl: accept timeout conn=%d", conn_id);
    aad_lockdown_ssl_close(conn_id);
    return -1;
}

void aad_lockdown_ssl_close(int conn_id)
{
    pthread_mutex_lock(&g_lock);
    int i = slot_index(conn_id);
    if (i >= 0) {
        p_SSL_free(g_slots[i].ssl); /* frees BIOs too */
        memset(&g_slots[i], 0, sizeof(g_slots[i]));
    }
    pthread_mutex_unlock(&g_lock);
}

int aad_lockdown_ssl_active(int conn_id)
{
    pthread_mutex_lock(&g_lock);
    int a = slot_index(conn_id) >= 0;
    pthread_mutex_unlock(&g_lock);
    return a;
}

int aad_lockdown_ssl_read(int conn_id, void *buf, size_t len, int timeout_ms)
{
    (void)timeout_ms;
    pthread_mutex_lock(&g_lock);
    int i = slot_index(conn_id);
    SSL *ssl = i >= 0 ? g_slots[i].ssl : NULL;
    pthread_mutex_unlock(&g_lock);
    if (!ssl) return -1;
    for (;;) {
        int n = p_SSL_read(ssl, buf, (int)len);
        if (n > 0) return n;
        int err = p_SSL_get_error(ssl, n);
        if (err == 2 || err == 3) continue;
        return -1;
    }
}

int aad_lockdown_ssl_write(int conn_id, const void *buf, size_t len)
{
    pthread_mutex_lock(&g_lock);
    int i = slot_index(conn_id);
    SSL *ssl = i >= 0 ? g_slots[i].ssl : NULL;
    pthread_mutex_unlock(&g_lock);
    if (!ssl) return -1;
    size_t off = 0;
    while (off < len) {
        int n = p_SSL_write(ssl, (const char *)buf + off, (int)(len - off));
        if (n <= 0) {
            int err = p_SSL_get_error(ssl, n);
            if (err == 2 || err == 3) continue;
            return -1;
        }
        off += (size_t)n;
    }
    return (int)len;
}
