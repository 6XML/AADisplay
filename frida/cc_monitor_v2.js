/* 广谱 chacha/poly1305 路线监测（只计数+采样，控制台输出；在用户终端里跑，连接稳定） */
(function () {
    var found = [];
    var mods = Process.enumerateModules();
    for (var i = 0; i < mods.length; i++) {
        var exports;
        try { exports = mods[i].enumerateExports(); } catch (e) { continue; }
        for (var j = 0; j < exports.length; j++) {
            var n = exports[j].name;
            if (/ccchacha20poly1305_(en|de)crypt$/.test(n) ||
                /CCEncryptorChaCha20Poly1305Oneshot(En|De)crypt/.test(n) ||
                /^CCEncryptor(Update|Final)$/.test(n) ||
                /^CCHmac(Init|Update)$/.test(n) ||
                /^CCCrypt\($/.test(n) ||
                /poly1305_(update|final|setad|reset)$/.test(n) ||
                /chacha_20_(init|update)$/.test(n)) {
                found.push({ n: n, p: exports[j].address });
            }
        }
    }
    console.log("=== wide probe pid=" + Process.id + " symbols=" + found.length + " ===");
    if (!found.length) return;

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
                if (len >= 0 && len <= 256) return { p: p, len: len };
            }
        } catch (e) { }
        return null;
    }

    var cnt = {};
    found.forEach(function (f) {
        var isOneshot = /Oneshot(En|De)crypt/.test(f.n);
        var isCCchacha = /ccchacha20poly1305/.test(f.n);
        var isUpd = f.n === "CCEncryptorUpdate" || f.n === "CCEncryptorFinal";
        var isHmac = /^CCHmac(Init|Update)$/.test(f.n);
        try {
            Interceptor.attach(f.p, {
                onEnter: function (args) {
                    var aad, len, k;
                    if (isOneshot || isCCchacha) { aad = args[3].toInt32(); len = args[5].toInt32(); }
                    else if (isHmac) { aad = args[2].toInt32(); len = args[3].toInt32(); }
                    else if (isUpd) {
                        var c = args[0];
                        if (f.n === "CCEncryptorUpdate") {
                            if (!cryptors[c.toString()]) cryptors[c.toString()] = sniffAad(c);
                            var info = cryptors[c.toString()];
                            aad = info ? info.len : -1;
                            len = args[2].toInt32();
                            this.cKey = c.toString();
                        } else {
                            var info2 = cryptors[args[0].toString()];
                            aad = info2 ? info2.len : -1;
                            len = -1;
                        }
                    }
                    if (aad < 0) return;
                    k = f.n + " aad" + aad + " len" + Math.min(len, 999999);
                    cnt[k] = (cnt[k] || 0) + 1;
                    // AAD=2 控制帧：打头 32 字节采样（限量防刷屏）
                    var payloadArg = (isOneshot || isCCchacha) ? 4 : (isHmac ? 4 : (f.n === "CCEncryptorUpdate" ? 1 : -1));
                    if (aad === 2 && len >= 0 && len < 4096 && cnt[k] <= 2 && payloadArg >= 0) {
                        try {
                            console.log("EV " + k + " head=" + toHex(args[payloadArg].readByteArray(Math.min(len, 32))));
                        } catch (e) { }
                    }
                }
            });
        } catch (e) { }
    });

    var t = 0;
    setInterval(function () {
        t += 10;
        var s = "T+" + t + "s: ", n = 0, total = 0;
        for (var k in cnt) { s += k + "=" + cnt[k] + " "; total += cnt[k]; if (++n > 14) { s += "..."; break; } }
        console.log(total ? s : "T+" + t + "s: (no calls)");
    }, 10000);
    console.log("wide probe armed.");
})();
