/*
 * plist.c — minimal Apple binary-free (XML) plist reader/writer.
 *
 * Supports the node types lockdown uses: dict, array, string, data(base64),
 * integer, real, true/false. Deliberately small and allocation-friendly.
 * Input is assumed to be well-formed plist XML (lockdown produces/consumes it).
 */
#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <ctype.h>

#include "aad.h"

/* struct aad_plist is defined in aad.h (shared with lockdown.c) */

int aad_plist_is_dict(const aad_plist *pl) { return pl && pl->type == 1; }
int aad_plist_dict_size(const aad_plist *pl) { return pl ? pl->count : 0; }
const aad_plist *aad_plist_get(const aad_plist *dict, const char *key)
{
    if (!aad_plist_is_dict(dict)) return NULL;
    for (int i = 0; i < dict->count; i++)
        if (dict->items[i]->key && !strcmp(dict->items[i]->key, key))
            return dict->items[i];
    return NULL;
}
const char *aad_plist_get_string(const aad_plist *dict, const char *key, size_t *out_len)
{
    const aad_plist *v = aad_plist_get(dict, key);
    if (!v || v->type != 3) return NULL;
    if (out_len) *out_len = strlen(v->str);
    return v->str;
}
int aad_plist_get_int(const aad_plist *dict, const char *key, int *out)
{
    const aad_plist *v = aad_plist_get(dict, key);
    if (!v) return -1;
    if (v->type == 5) { *out = (int)v->ival; return 0; }
    if (v->type == 3) { *out = (int)strtol(v->str, NULL, 10); return 0; }
    return -1;
}

/* ---------------- builders ---------------- */

static aad_plist *node_new(int type)
{
    aad_plist *pl = calloc(1, sizeof(*pl));
    if (pl) pl->type = type;
    return pl;
}

aad_plist *aad_plist_new_dict(void) { return node_new(1); }
aad_plist *aad_plist_new_string(const char *s)
{
    aad_plist *pl = node_new(3);
    if (pl) pl->str = strdup(s ? s : "");
    return pl;
}
aad_plist *aad_plist_new_int(int v)
{
    aad_plist *pl = node_new(5);
    if (pl) pl->ival = v;
    return pl;
}
aad_plist *aad_plist_new_bool(int v)
{
    aad_plist *pl = node_new(6);
    if (pl) pl->bval = v;
    return pl;
}
aad_plist *aad_plist_new_data(const void *d, size_t len)
{
    aad_plist *pl = node_new(4);
    if (pl) {
        pl->data = malloc(len ? len : 1);
        memcpy(pl->data, d, len);
        pl->data_len = len;
    }
    return pl;
}

static aad_plist *array_new(void) { return node_new(2); }

aad_plist *aad_plist_new_array(void) { return array_new(); }

void aad_plist_dict_set(aad_plist *dict, const char *key, aad_plist *value)
{
    if (!dict || dict->type != 1 || !value) { aad_plist_free(value); return; }
    value->key = strdup(key);
    if (dict->count >= dict->cap) {
        dict->cap = dict->cap ? dict->cap * 2 : 8;
        dict->items = realloc(dict->items, dict->cap * sizeof(*dict->items));
    }
    dict->items[dict->count++] = value;
}

void aad_plist_array_append(aad_plist *arr, aad_plist *value)
{
    if (!arr || arr->type != 2 || !value) { aad_plist_free(value); return; }
    value->key = NULL;
    if (arr->count >= arr->cap) {
        arr->cap = arr->cap ? arr->cap * 2 : 8;
        arr->items = realloc(arr->items, arr->cap * sizeof(*arr->items));
    }
    arr->items[arr->count++] = value;
}

const unsigned char *aad_plist_get_data(const aad_plist *pl, size_t *out_len)
{
    if (!pl || pl->type != 4) return NULL;
    if (out_len) *out_len = pl->data_len;
    return pl->data;
}

void aad_plist_free(aad_plist *pl)
{
    if (!pl) return;
    for (int i = 0; i < pl->count; i++) aad_plist_free(pl->items[i]);
    free(pl->items);
    free(pl->key);
    free(pl->str);
    free(pl->data);
    free(pl);
}

/* ---------------- parser ---------------- */

struct pctx {
    const char *p;
    const char *end;
    int ok;
};

static void skipws(struct pctx *c)
{
    while (c->p < c->end && isspace((unsigned char)*c->p)) c->p++;
}

static void expect(struct pctx *c, const char *s)
{
    size_t n = strlen(s);
    if (c->end - c->p < (ssize_t)n || memcmp(c->p, s, n) != 0) c->ok = 0;
    else c->p += n;
}

/* parse a quoted string (double-quoted XML string) */
static char *parse_quoted(struct pctx *c)
{
    if (c->p + 1 < c->end && c->p[0] == '<' && c->p[1] == '/') {
        /* <tag></tag>: empty element. Return "" and leave the pointer at the
         * closing tag — the caller's leaf-skip loop consumes it. */
        return strdup("");
    }
    if (*c->p != '"') { c->ok = 0; return NULL; }
    c->p++;
    const char *start = c->p;
    size_t cap = 64, len = 0;
    char *buf = malloc(cap);
    while (c->p < c->end && *c->p != '"') {
        if (len + 8 > cap) { cap *= 2; buf = realloc(buf, cap); }
        /* keep entities minimal */
        if (*c->p == '&') {
            if (strncmp(c->p, "&amp;", 5) == 0) { buf[len++] = '&'; c->p += 5; }
            else if (strncmp(c->p, "&lt;", 4) == 0) { buf[len++] = '<'; c->p += 4; }
            else if (strncmp(c->p, "&gt;", 4) == 0) { buf[len++] = '>'; c->p += 4; }
            else if (strncmp(c->p, "&quot;", 6) == 0) { buf[len++] = '"'; c->p += 6; }
            else if (strncmp(c->p, "&apos;", 6) == 0) { buf[len++] = '\''; c->p += 6; }
            else { buf[len++] = *c->p++; }
        } else {
            buf[len++] = *c->p++;
        }
    }
    (void)start;
    if (c->p >= c->end) { c->ok = 0; free(buf); return NULL; }
    c->p++; /* closing quote */
    buf[len] = 0;
    char *out = malloc(len + 1);
    memcpy(out, buf, len + 1);
    free(buf);
    return out;
}

/* decode base64 body text into a node's data field */
int aad_b64_decode(const char *in, size_t inlen, unsigned char **out, size_t *outlen)
{
    static const int T[256] = {
        ['A']=0,['B']=1,['C']=2,['D']=3,['E']=4,['F']=5,['G']=6,['H']=7,
        ['I']=8,['J']=9,['K']=10,['L']=11,['M']=12,['N']=13,['O']=14,['P']=15,
        ['Q']=16,['R']=17,['S']=18,['T']=19,['U']=20,['V']=21,['W']=22,
        ['X']=23,['Y']=24,['Z']=25,
        ['a']=26,['b']=27,['c']=28,['d']=29,['e']=30,['f']=31,['g']=32,
        ['h']=33,['i']=34,['j']=35,['k']=36,['l']=37,['m']=38,['n']=39,
        ['o']=40,['p']=41,['q']=42,['r']=43,['s']=44,['t']=45,['u']=46,
        ['v']=47,['w']=48,['x']=49,['y']=50,['z']=51,
        ['0']=52,['1']=53,['2']=54,['3']=55,['4']=56,['5']=57,['6']=58,
        ['7']=59,['8']=60,['9']=61,['+']=62,['/']=63,
    };
    /* strip whitespace first so 4-char group alignment never breaks */
    char *s = malloc(inlen + 1);
    size_t sl = 0;
    for (size_t i = 0; i < inlen; i++)
        if (!isspace((unsigned char)in[i])) s[sl++] = in[i];
    size_t cap = sl / 4 * 3 + 3;
    unsigned char *buf = malloc(cap ? cap : 1);
    size_t n = 0, i = 0;
    while (i < sl) {
        /* gather one 4-char group: d data chars + pad padding chars */
        int acc = 0, d = 0, pad = 0, bad = 0;
        while (i < sl && d + pad < 4) {
            char ch = s[i++];
            if (ch == '=') { pad++; continue; }
            int v = T[(unsigned char)ch];
            if (v == 0 && ch != 'A') { bad = 1; break; }
            acc = (acc << 6) | v;
            d++;
        }
        if (bad || d == 0) break;
        if (d + pad < 4 && pad == 0) break; /* unpadded partial tail: stop */
        int shift = 6 * d;          /* acc holds 6d bits, first char top */
        int m = (6 * d) / 8;        /* d=2->1, d=3->2, d=4->3 bytes */
        for (int k = 0; k < m; k++)
            buf[n++] = (unsigned char)((acc >> (shift - 8 * (k + 1))) & 0xff);
    }
    free(s);
    *out = buf;
    *outlen = n;
    return 0;
}

/* decode the body of a PEM blob (-----BEGIN ...-----END) to DER bytes */
int aad_pem_to_der(const char *pem, unsigned char **out, size_t *outlen)
{
    const char *begin = pem ? strstr(pem, "-----BEGIN") : NULL;
    const char *end = begin ? strstr(begin, "-----END") : NULL;
    if (!begin || !end) return -1;
    const char *body = begin + 10;
    while (*body && *body != '\n') body++;          /* skip label line */
    if (*body == '\n') body++;
    return aad_b64_decode(body, (size_t)(end - body), out, outlen);
}

static aad_plist *parse_dict(struct pctx *c);

/* parse an element body until its closing tag; builds the node of that type */
static aad_plist *parse_element(struct pctx *c)
{
    skipws(c);
    if (*c->p != '<') { c->ok = 0; return NULL; }
    c->p++; /* < */
    if (*c->p == '/') { c->ok = 0; return NULL; } /* </ at element start: malformed */
    const char *tag_start = c->p;
    while (c->p < c->end && !isspace((unsigned char)*c->p) && *c->p != '>' && *c->p != '/') c->p++;
    size_t taglen = (size_t)(c->p - tag_start);
    char tag[64] = {0};
    if (taglen >= sizeof(tag)) taglen = sizeof(tag) - 1;
    memcpy(tag, tag_start, taglen);

    /* attributes: scan to the end of the opening tag. A "/>" completes the
     * element (no body, no closing tag) — detect it HERE, before the ">",
     * because once ">" is consumed the self-close is no longer visible. */
    int self_close = 0;
    while (c->p < c->end) {
        if (*c->p == '<') { c->ok = 0; return NULL; }
        if (*c->p == '>') { c->p++; break; }
        if (*c->p == '/' && c->p + 1 < c->end && c->p[1] == '>') {
            self_close = 1;
            c->p += 2; /* past "/>" */
            break;
        }
        c->p++;
    }
    if (c->p >= c->end) { c->ok = 0; return NULL; }

    /* self-closing element: done, nothing to skip */
    skipws(c);
    if (self_close) {
        if (!strcmp(tag, "dict")) return aad_plist_new_dict();
        if (!strcmp(tag, "array")) return array_new();
        if (!strcmp(tag, "string")) return aad_plist_new_string("");
        if (!strcmp(tag, "integer")) return aad_plist_new_int(0);
        if (!strcmp(tag, "true")) return aad_plist_new_bool(1);
        if (!strcmp(tag, "false")) return aad_plist_new_bool(0);
        if (!strcmp(tag, "data")) return aad_plist_new_data(NULL, 0);
        c->ok = 0;
        return NULL;
    }
    skipws(c);

    if (!strcmp(tag, "dict")) {
        /* dict children are <key>/<value> pairs — delegate */
        return parse_dict(c);
    }

    aad_plist *pl = NULL;
    int closed = 0; /* set when the branch consumes the element's own close tag */
    if (!strcmp(tag, "array")) {
        pl = array_new();
    } else if (!strcmp(tag, "string")) {
        skipws(c);
        char *s = parse_quoted(c);
        if (!s) { c->ok = 0; aad_plist_free(pl); return NULL; }
        pl = node_new(3);
        pl->str = s;
    } else if (!strcmp(tag, "integer")) {
        /* Apple plists render integers unquoted: <integer>50001</integer> */
        skipws(c);
        long v = 0;
        int neg = 0, any = 0;
        if (*c->p == '-') { neg = 1; c->p++; }
        while (c->p < c->end && *c->p != '<') {
            char ch = *c->p;
            if (ch >= '0' && ch <= '9') { v = v * 10 + (ch - '0'); any = 1; }
            c->p++;
        }
        if (!any) c->ok = 0;
        pl = aad_plist_new_int(neg ? (int)-v : (int)v);
    } else if (!strcmp(tag, "true") || !strcmp(tag, "false")) {
        pl = aad_plist_new_bool(!strcmp(tag, "true"));
    } else if (!strcmp(tag, "data")) {
        /* gather text until </data>, strip whitespace, base64 decode */
        skipws(c);
        size_t cap = 256, n = 0;
        char *body = malloc(cap);
        while (c->p < c->end) {
            if (c->p + 1 < c->end && c->p[0] == '<' && c->p[1] == '/') {
                if (strncmp(c->p, "</data>", 7) == 0) { c->p += 7; closed = 1; break; }
                c->ok = 0; free(body); return NULL;
            }
            char ch = *c->p++;
            if (!isspace((unsigned char)ch)) {
                if (n + 1 > cap) { cap *= 2; body = realloc(body, cap); }
                body[n++] = ch;
            }
        }
        closed = 1; /* close tag consumed above (or EOF: caller will fail) */
        unsigned char *data;
        size_t dlen;
        aad_b64_decode(body, n, &data, &dlen);
        free(body);
        pl = aad_plist_new_data(data, dlen);
        free(data);
    } else if (!strcmp(tag, "real")) {
        /* treat as int 0 — never used in lockdown */
        pl = aad_plist_new_int(0);
    } else {
        /* unknown element: skip body, return empty string node */
        pl = aad_plist_new_string("");
    }
    if (!pl) { c->ok = 0; return NULL; }

    /* children (dict/array) */
    if (pl->type == 1 || pl->type == 2) {
        for (;;) {
            skipws(c);
            if (c->p + 1 < c->end && c->p[0] == '<' && c->p[1] == '/') {
                /* closing tag: must match */
                c->p += 2;
                const char *ct = c->p;
                while (c->p < c->end && *c->p != '>') c->p++;
                if (c->p >= c->end) { c->ok = 0; aad_plist_free(pl); return NULL; }
                size_t cl = (size_t)(c->p - ct); /* name length, BEFORE pasting the ">" */
                if (cl != taglen || memcmp(ct, tag, taglen) != 0) c->ok = 0;
                c->p++;
                break;
            }
            if (c->p >= c->end || *c->p != '<') { c->ok = 0; aad_plist_free(pl); return NULL; }
            aad_plist *child = parse_element(c);
            if (!c->ok) { aad_plist_free(pl); aad_plist_free(child); return NULL; }
            if (!child) break;
            if (pl->count >= pl->cap) {
                pl->cap = pl->cap ? pl->cap * 2 : 8;
                pl->items = realloc(pl->items, pl->cap * sizeof(*pl->items));
            }
            pl->items[pl->count++] = child;
        }
    } else if (!closed) {
        /* leaf: skip to its closing tag (already consumed by the branch when
         * closed is set, e.g. <data>) */
        for (;;) {
            if (c->p >= c->end) { c->ok = 0; aad_plist_free(pl); return NULL; }
            if (c->p + 1 < c->end && c->p[0] == '<' && c->p[1] == '/') {
                c->p += 2;
                while (c->p < c->end && *c->p != '>') c->p++;
                if (c->p < c->end) c->p++;
                break;
            }
            c->p++;
        }
    }
    return pl;
}

/* dict with explicit <key>..</key><value>..</value> pairs */
static aad_plist *parse_dict(struct pctx *c)
{
    aad_plist *dict = aad_plist_new_dict();
    for (;;) {
        skipws(c);
        if (c->p + 1 < c->end && c->p[0] == '<' && c->p[1] == '/') {
            /* closing tag: must be </dict> */
            c->p += 2;
            const char *ct = c->p;
            while (c->p < c->end && *c->p != '>') c->p++;
            if (c->p >= c->end) { c->ok = 0; aad_plist_free(dict); return NULL; }
            size_t cl = (size_t)(c->p - ct);
            if (cl != 4 || memcmp(ct, "dict", 4) != 0) c->ok = 0;
            c->p++;
            break;
        }
        if (c->p >= c->end) { c->ok = 0; aad_plist_free(dict); return NULL; }
        if (strncmp(c->p, "<key>", 5) != 0) { c->ok = 0; aad_plist_free(dict); return NULL; }
        c->p += 5;
        skipws(c);
        char *key = parse_quoted(c);
        skipws(c);
        expect(c, "</key>");
        if (!c->ok || !key) { aad_plist_free(dict); free(key); return NULL; }
        skipws(c);
        if (strncmp(c->p, "<value>", 7) != 0) { c->ok = 0; aad_plist_free(dict); free(key); return NULL; }
        c->p += 7;
        skipws(c);
        aad_plist *val = parse_element(c);
        if (!c->ok) { aad_plist_free(dict); free(key); aad_plist_free(val); return NULL; }
        /* the pair's value element must be closed; parse_element stopped
         * after the inner element's own close tag */
        skipws(c);
        expect(c, "</value>");
        if (!c->ok) { aad_plist_free(dict); free(key); aad_plist_free(val); return NULL; }
        val->key = key;
        if (dict->count >= dict->cap) {
            dict->cap = dict->cap ? dict->cap * 2 : 8;
            dict->items = realloc(dict->items, dict->cap * sizeof(*dict->items));
        }
        dict->items[dict->count++] = val;
    }
    return dict;
}

/* top-level: <?xml?> ... <plist ...> <dict> ... </dict> </plist> */
aad_plist *aad_plist_parse(const char *data, size_t len)
{
    struct pctx c = { data, data + len, 1 };
    skipws(&c);
    if (strncmp(c.p, "<?xml", 5) == 0) {
        while (c.p < c.end && (c.p[0] != '>' || c.p[-1] != '?')) c.p++;
        if (c.p < c.end) c.p++;
    }
    skipws(&c);
    if (strncmp(c.p, "<!DOCTYPE", 9) == 0) {
        /* Apple plist header; the DTD string contains no '>' */
        while (c.p < c.end && *c.p != '>') c.p++;
        if (c.p < c.end) c.p++;
    }
    skipws(&c);
    if (strncmp(c.p, "<plist", 6) == 0) {
        while (c.p < c.end && *c.p != '>') c.p++;
        if (c.p < c.end) c.p++;
    }
    skipws(&c);
    if (strncmp(c.p, "<dict", 5) != 0) { c.ok = 0; return NULL; }
    /* consume the whole opening tag (<dict> or <dict ...>) */
    while (c.p < c.end && *c.p != '>') c.p++;
    if (c.p >= c.end) { c.ok = 0; return NULL; }
    c.p++;
    aad_plist *dict = parse_dict(&c);
    if (!c.ok) { aad_plist_free(dict); return NULL; }
    skipws(&c);
    /* trailing </dict> was consumed by parse_dict; then </plist> */
    if (strncmp(c.p, "</plist>", 8) == 0) c.p += 8;
    return dict;
}

/* ---------------- renderer ---------------- */

static void b64_encode(const unsigned char *in, size_t len, char **out, size_t *outlen)
{
    static const char T[] =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    size_t n = (len + 2) / 3 * 4 + 1;
    char *buf = malloc(n + 1);
    size_t i = 0, o = 0;
    while (i + 3 <= len) {
        uint32_t v = (in[i] << 16) | (in[i+1] << 8) | in[i+2];
        buf[o++] = T[(v >> 18) & 63]; buf[o++] = T[(v >> 12) & 63];
        buf[o++] = T[(v >> 6) & 63];  buf[o++] = T[v & 63];
        i += 3;
    }
    if (len - i == 1) {
        uint32_t v = in[i] << 16;
        buf[o++] = T[(v >> 18) & 63]; buf[o++] = T[(v >> 12) & 63];
        buf[o++] = '='; buf[o++] = '=';
    } else if (len - i == 2) {
        uint32_t v = (in[i] << 16) | (in[i+1] << 8);
        buf[o++] = T[(v >> 18) & 63]; buf[o++] = T[(v >> 12) & 63];
        buf[o++] = T[(v >> 6) & 63]; buf[o++] = '=';
    }
    buf[o] = 0;
    *out = buf;
    *outlen = o;
}

static void xml_escape(const char *s, size_t len, char **out, size_t *outlen)
{
    size_t cap = len * 3 + 8, n = 0;
    char *buf = malloc(cap);
    for (size_t i = 0; i < len; i++) {
        char ch = s[i], *rep = NULL;
        if (ch == '&') rep = "&amp;";
        else if (ch == '<') rep = "&lt;";
        else if (ch == '>') rep = "&gt;";
        else if (ch == '"') rep = "&quot;";
        if (rep) {
            size_t rl = strlen(rep);
            if (n + rl > cap) { cap *= 2; buf = realloc(buf, cap); }
            memcpy(buf + n, rep, rl);
            n += rl;
        } else {
            if (n + 1 > cap) { cap *= 2; buf = realloc(buf, cap); }
            buf[n++] = ch;
        }
    }
    buf[n] = 0;
    *out = buf;
    *outlen = n;
}

struct rend {
    char *buf;
    size_t len, cap;
};

static void rend_put(struct rend *r, const char *s, size_t n)
{
    if (r->len + n + 1 > r->cap) {
        r->cap = (r->len + n + 1) * 2 + 256;
        r->buf = realloc(r->buf, r->cap);
    }
    memcpy(r->buf + r->len, s, n);
    r->len += n;
    r->buf[r->len] = 0;
}

static void render_node(struct rend *r, const aad_plist *pl, int in_dict);

static void render_node(struct rend *r, const aad_plist *pl, int in_dict)
{
    if (in_dict && pl->key) {
        char *k; size_t kl;
        xml_escape(pl->key, strlen(pl->key), &k, &kl);
        rend_put(r, "<key>", 5);
        rend_put(r, "\"", 1);
        rend_put(r, k, kl);
        rend_put(r, "\"</key>", 7);
        free(k);
    }
    /* Apple format: <value> wrappers exist only for dict pairs */
    if (in_dict) rend_put(r, "<value>", 7);
    switch (pl->type) {
    case 1: {
        rend_put(r, "<dict>", 6);
        for (int i = 0; i < pl->count; i++) render_node(r, pl->items[i], 1);
        rend_put(r, "</dict>", 7);
        break;
    }
    case 2: {
        rend_put(r, "<array>", 7);
        for (int i = 0; i < pl->count; i++) render_node(r, pl->items[i], 0);
        rend_put(r, "</array>", 8);
        break;
    }
    case 3: {
        char *s; size_t sl;
        xml_escape(pl->str ? pl->str : "", pl->str ? strlen(pl->str) : 0, &s, &sl);
        rend_put(r, "<string>", 8);
        rend_put(r, "\"", 1);
        rend_put(r, s, sl);
        rend_put(r, "\"</string>", 10);
        free(s);
        break;
    }
    case 4: {
        char *b; size_t bl;
        b64_encode(pl->data, pl->data_len, &b, &bl);
        rend_put(r, "<data>", 6);
        rend_put(r, b, bl);
        rend_put(r, "</data>", 7);
        free(b);
        break;
    }
    case 5: {
        char tmp[24];
        int n = snprintf(tmp, sizeof(tmp), "%lld", (long long)pl->ival);
        rend_put(r, "<integer>", 9);
        rend_put(r, tmp, n);
        rend_put(r, "</integer>", 10);
        break;
    }
    case 6:
        rend_put(r, pl->bval ? "<true/>" : "<false/>", pl->bval ? 7 : 8);
        break;
    default:
        break;
    }
    if (in_dict) rend_put(r, "</value>", 8);
}

char *aad_plist_render(const aad_plist *pl, size_t *out_len)
{
    struct rend r = {0};
    r.cap = 256;
    r.buf = malloc(r.cap);
    static const char hdr[] = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                              "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" "
                              "\"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n"
                              "<plist version=\"1.0\">";
    rend_put(&r, hdr, strlen(hdr));
    /* root node: Apple format has no <value> wrapper around the root dict */
    if (pl->type == 1) {
        rend_put(&r, "<dict>", 6);
        for (int i = 0; i < pl->count; i++) render_node(&r, pl->items[i], 1);
        rend_put(&r, "</dict>", 7);
    } else if (pl->type == 2) {
        rend_put(&r, "<array>", 7);
        for (int i = 0; i < pl->count; i++) render_node(&r, pl->items[i], 0);
        rend_put(&r, "</array>", 8);
    } else {
        render_node(&r, pl, 0);
    }
    rend_put(&r, "</plist>\n", 9);
    if (out_len) *out_len = r.len;
    return r.buf;
}
