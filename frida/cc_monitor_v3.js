/* 全路线加密监测 v3：chacha / GCM / CCFamily 全家 + corecrypto 内部导出；10s 一个计数 tick */
(function () {
    var re = /ccchacha|chacha_20|poly1305|^CCEncryptor|CCCryptorGCM|^CCCrypt\(|^CCHmac|aes_gcm|aes_cc|crypto_cc_/;
    var found = [];
    var mods = Process.enumerateModules();
    for (var i = 0; i < mods.length; i++) {
        var exports;
        try { exports = mods[i].enumerateExports(); } catch (e) { continue; }
        for (var j = 0; j < exports.length; j++) {
            var n = exports[j].name;
            if (re.test(n)) found.push({ n: n, p: exports[j].address });
        }
    }
    console.log("v3 probe pid=" + Process.id + " proc=" + (Process.name || "?") + " symbols=" + found.length);
    if (!found.length) { console.log("v3 NO symbols"); return; }

    function toHex(bytes) {
        var u = new Uint8Array(bytes), out = [];
        for (var i = 0; i < u.length; i++) out.push((u[i] < 16 ? "0" : "") + u[i].toString(16));
        return out.join(" ");
    }

    var cryptors = {};
    function sniffAad(c) {
        try {
            for (var i = 0; i < 8; i++) {
                var p = c.add(i * 8).readPointer();
                if (p.isNull() || p.compare(ptr("0x10000")) < 0) continue;
                var len = c.add(i * 8 + 8).toInt32();
                if (len >= 0 && len <= 256) return { len: len };
            }
        } catch (e) { }
        return null;
    }

    var cnt = {};
    found.forEach(function (f) {
        var isOneshot = /Oneshot(En|De)crypt/.test(f.n);
        var isCCchacha = /ccchacha20poly1305/.test(f.n);
        var isUpd = /CCEncryptor(Update|Final)$/.test(f.n);
        var isHmac = /^CCHmac(Init|Update)$/.test(f.n);
        var isGCM = /GCM(Encrypt|Decrypt|AddAAD|AddADD|SetIV)$/.test(f.n);
        try {
            Interceptor.attach(f.p, {
                onEnter: function (args) {
                    var aad = -1, len = -1;
                    if (isOneshot || isCCchacha) { aad = args[3].toInt32(); len = args[5].toInt32(); }
                    else if (isHmac) { aad = args[2].toInt32(); len = args[3].toInt32(); }
                    else if (isGCM) {
                        var c = args[0];
                        if (!cryptors[c.toString()]) cryptors[c.toString()] = sniffAad(c);
                        aad = cryptors[c.toString()] ? cryptors[c.toString()].len : -1;
                        if (/Update/.test(f.n) || /Encrypt$/.test(f.n)) len = (isGCM ? args[2] : args[2]).toInt32();
                    }
                    else if (isUpd) {
                        var c2 = args[0];
                        if (!cryptors[c2.toString()]) cryptors[c2.toString()] = sniffAad(c2);
                        aad = cryptors[c2.toString()] ? cryptors[c2.toString()].len : -1;
                        len = args[2].toInt32();
                    }
                    if (aad < 0) return;
                    var k = f.n + " aad" + aad + " len" + Math.min(len, 999999);
                    cnt[k] = (cnt[k] || 0) + 1;
                    var pArg = (isOneshot || isCCchacha) ? 4 : (isHmac ? 4 : 1);
                    if (aad === 2 && len >= 0 && len < 4096 && cnt[k] <= 2) {
                        try { console.log("EV " + k + " head=" + toHex(args[pArg].readByteArray(Math.min(len, 32)))); } catch (e) { }
                    }
                }
            });
        } catch (e) { }
    });

    var t = 0;
    setInterval(function () {
        t += 10;
        var s = "T+" + t + "s: ", n = 0, total = 0;
        for (var k in cnt) { s += k + "=" + cnt[k] + " "; total += cnt[k]; if (++n > 16) { s += "..."; break; } }
        console.log(total ? s : "T+" + t + "s: (no calls)");
    }, 10000);
    console.log("v3 armed");
})();
