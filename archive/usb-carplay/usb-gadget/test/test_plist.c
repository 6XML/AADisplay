/*
 * test_plist.c — host-side unit tests for the daemon's pure-C core:
 * base64 (incl. padding + whitespace), PEM->DER, plist render/parse roundtrip,
 * and the embedded identity blobs.
 *
 * Run: make test   (host build; no USB/kernel needed)
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>

#include "aad.h"

static int g_fail = 0;

#define CHECK(cond, ...) do { \
    if (!(cond)) { g_fail++; printf("FAIL %s:%d: ", __FILE__, __LINE__); \
        printf(__VA_ARGS__); printf("\n"); } \
} while (0)

static void test_b64_known(void)
{
    unsigned char *out; size_t n;

    /* "A" -> "QQ==" (1 data char + 2 pad) */
    aad_b64_decode("QQ==", 4, &out, &n);
    CHECK(n == 1 && out[0] == 'A', "QQ== expected 'A', got n=%zu", n);
    free(out);

    /* "QUI=" -> 0x41 0x42 ("AB") — the padding-shift case */
    aad_b64_decode("QUI=", 4, &out, &n);
    CHECK(n == 2 && out[0] == 0x41 && out[1] == 0x42,
          "QUI= expected 41 42, got n=%zu %02x %02x", n, out[0], out[1]);
    free(out);

    /* "aGVsbG8gd29ybGQ=" -> "hello world" (12 bytes, 1 pad) */
    aad_b64_decode("aGVsbG8gd29ybGQ=", 16, &out, &n);
    CHECK(n == 11 && !memcmp(out, "hello world", 11),
          "hello world: n=%zu", n);
    free(out);

    /* whitespace in the middle must not break group alignment */
    aad_b64_decode("aGVs bG8=", 7, &out, &n);
    CHECK(n == 3 && !memcmp(out, "hel", 3), "ws hello: n=%zu", n);
    free(out);

    /* newline-wrapped (PEM-style) */
    aad_b64_decode("aGVs\nbG8=\n", 8, &out, &n);
    CHECK(n == 3 && !memcmp(out, "hel", 3), "nl hello: n=%zu", n);
    free(out);

    /* garbage: decodes up to the bad char, no crash */
    aad_b64_decode("aGVs!@#%", 8, &out, &n);
    CHECK(n <= 3, "garbage: n=%zu", n);
    free(out);

    /* empty */
    aad_b64_decode("", 0, &out, &n);
    CHECK(n == 0, "empty: n=%zu", n);
    free(out);
}

static void test_pem(void)
{
    unsigned char *der; size_t n = 0;

    /* device public key: PKCS#1 SubjectPublicKeyInfo starts 30 82 01 01 */
    CHECK(aad_pem_to_der(AAD_DEVICE_PUB_PEM, &der, &n) == 0, "pem_to_der(pub) failed");
    CHECK(n > 100 && n < 400, "pub der len %zu", n);
    CHECK(der[0] == 0x30 && der[1] == 0x82, "pub der DER magic");
    free(der);

    /* root + device certs: X.509 30 82 ... with >1KB length */
    CHECK(aad_pem_to_der(AAD_ROOT_CERT_PEM, &der, &n) == 0, "pem_to_der(root) failed");
    CHECK(n > 500 && n < 3000, "root der len %zu", n);
    CHECK(der[0] == 0x30 && (der[1] & 0x80), "root der DER magic");
    free(der);

    CHECK(aad_pem_to_der(AAD_DEVICE_CERT_PEM, &der, &n) == 0, "pem_to_der(cert) failed");
    CHECK(n > 500 && n < 3000, "cert der len %zu", n);
    CHECK(der[0] == 0x30 && (der[1] & 0x80), "cert der DER magic");
    free(der);

    /* PEM must be valid base64 end-to-end (no stray chars): decode the body
     * manually is overkill — pem_to_der success + sizes is the gate */
    CHECK(strlen(AAD_DEVICE_PUB_PEM) > 100, "pub pem too short");
    CHECK(strlen(AAD_ROOT_CERT_PEM) > 200, "root pem too short");
}

static void test_plist_roundtrip(void)
{
    /* build a lockdown-shaped doc */
    aad_plist *d = aad_plist_new_dict();
    aad_plist_dict_set(d, "Request", aad_plist_new_string("StartService"));
    aad_plist_dict_set(d, "Service", aad_plist_new_string("com.apple.carkit.service"));
    aad_plist_dict_set(d, "Port", aad_plist_new_int(50001));
    aad_plist_dict_set(d, "EnableServiceSSL", aad_plist_new_bool(0));
    aad_plist_dict_set(d, "EscrowBag", aad_plist_new_data("abc123", 6));
    aad_plist *tz = aad_plist_new_array();
    aad_plist_array_append(tz, aad_plist_new_string("CST"));
    aad_plist_dict_set(d, "Timezone", tz);
    aad_plist_dict_set(d, "EmptyStr", aad_plist_new_string(""));
    aad_plist_dict_set(d, "Escaped", aad_plist_new_string("a<b>&c\"d"));

    size_t len = 0;
    char *xml = aad_plist_render(d, &len);
    CHECK(xml && len > 100, "render: %s", xml ? "ok" : "NULL");
    /* Apple wire format: bare <key>/<string>, no quotes, no <value> */
    CHECK(strstr(xml, "<key>Request</key>") != NULL, "Apple key form");
    CHECK(strstr(xml, "<string>StartService</string>") != NULL, "Apple string form");
    CHECK(strstr(xml, "<value>") == NULL, "no <value> wrapper");
    CHECK(strstr(xml, "<key>\"") == NULL, "no quoted keys");

    aad_plist *p = aad_plist_parse(xml, len);
    CHECK(p != NULL, "parse of own render failed");
    if (p) {
        CHECK(!strcmp(aad_plist_get_string(p, "Request", NULL), "StartService"), "Request");
        CHECK(!strcmp(aad_plist_get_string(p, "Service", NULL), "com.apple.carkit.service"), "Service");
        int port = 0;
        CHECK(aad_plist_get_int(p, "Port", &port) == 0 && port == 50001, "Port=%d", port);
        const aad_plist *ssl = aad_plist_get(p, "EnableServiceSSL");
        CHECK(ssl && ssl->type == 6 && !ssl->bval, "bool false");
        size_t dl = 0;
        const unsigned char *db = aad_plist_get_data(aad_plist_get(p, "EscrowBag"), &dl);
        CHECK(db && dl == 6 && !memcmp(db, "abc123", 6), "data roundtrip");
        const aad_plist *tzp = aad_plist_get(p, "Timezone");
        CHECK(tzp && tzp->type == 2 && tzp->count == 1, "array");
        CHECK(aad_plist_get_string(p, "Escaped", NULL) && !strcmp(aad_plist_get_string(p, "Escaped", NULL), "a<b>&c\"d"), "xml escape roundtrip");
        aad_plist_free(p);
    }
    free(xml);
    aad_plist_free(d);
}

/* Apple / libimobiledevice wire shape: bare key/string, no <value>. */
static void test_libimobiledevice_shape(void)
{
    aad_plist *d = aad_plist_new_dict();
    aad_plist_dict_set(d, "Request", aad_plist_new_string("GetValue"));
    aad_plist_dict_set(d, "Key", aad_plist_new_string("ProductVersion"));
    size_t len = 0;
    char *xml = aad_plist_render(d, &len);
    const char *want =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
        "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" "
        "\"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n"
        "<plist version=\"1.0\">"
        "<dict>"
        "<key>Request</key><string>GetValue</string>"
        "<key>Key</key><string>ProductVersion</string>"
        "</dict>"
        "</plist>\n";
    CHECK(xml && len == strlen(want) && !memcmp(xml, want, len),
          "shape mismatch (len %zu vs %zu)\n--got--\n%s\n--want--\n%s",
          len, strlen(want), xml ? xml : "", want);
    free(xml);
    aad_plist_free(d);
}

int main(void)
{
    test_b64_known();
    test_pem();
    test_plist_roundtrip();
    test_libimobiledevice_shape();

    if (g_fail) {
        printf("\n%d FAILURES\n", g_fail);
        return 1;
    }
    printf("all tests passed\n");
    return 0;
}
