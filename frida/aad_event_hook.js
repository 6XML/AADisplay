/*
 * AADisplay CarPlay — 事件通道明文抓取（Frida，越狱 iPhone 端）
 *
 * 目标符号（在目标进程所有模块里搜索导出，不绑死模块名）：
 *   ccchacha20poly1305_encrypt(key, nonce, aad, aad_len, m, m_len, c, t)   // 手机 → 车机
 *   ccchacha20poly1305_decrypt(key, nonce, aad, aad_len, c, c_len, m, t)   // 车机 → 手机
 *
 * 过滤策略：
 *   - AAD 长度 == 2 的帧（事件/控制通道）：完整记录明文 + hex
 *   - 其它 AAD 长度（如 128 = 画面流）：不 dump 载荷，只计数；
 *     每 5s 汇总一次，便于定位「画面流开始 / 画面出现」时间点
 *
 * 分类标签（对 AAD=2 明文）：
 *   RTSP    含 "POST /command" 或 "iAPSendMessage"（重点）
 *   IAP?    文本且含 "iAP"（次重点）
 *   DETECT  前缀 ff 55 02 00 ee 10（iAP2 检测帧，重点）
 *   LINK    前缀 ff 5a（链路 SYN/ACK，重点）
 *   TEXT    其它可打印文本
 *   OTHER   其它二进制
 *
 * 用法（Mac 端，iPhone 经 USB 连 Mac，iPhone 再无线连车机）：
 *   frida -U airplayd -l aad_event_hook.js -o iphone-event.txt
 * 若 airplayd 未导出符号，先跑 ./find_host.sh 找真正宿主进程，再用 -p <pid> 指定。
 * 手机操作时间线：上车 → 连无线 CarPlay → 等 MMI 出画 → 出画后停 20s → Ctrl-C。
 */
"use strict";

var NAME_ENCRYPT = "ccchacha20poly1305_encrypt";
var NAME_DECRYPT = "ccchacha20poly1305_decrypt";

var EVENT_AAD_LEN = 2;
var MAX_DUMP_BYTES = 1024;
var SUMMARY_INTERVAL_MS = 5000;

var t0 = Date.now();
// Frida JS 运行时单线程，无需真锁；iOS 也无 Lock 类，用 no-op 占位
var lock = { acquire: function () { }, release: function () { } };
var eventCount = 0;
var skippedCount = 0;
var otherLenCount = {};
var encTotal = 0;
var decTotal = 0;

/* ---------------- 基础工具 ---------------- */

function relTs() {
    var ms = Date.now() - t0;
    var s = Math.floor(ms / 1000);
    var mm = Math.floor(s / 60);
    var ss = (s % 60).toFixed(3);
    while (ss.length < 7) ss = "0" + ss;
    var d = new Date();
    var hhmmss = ("0" + d.getHours()).slice(-2) + ":" +
        ("0" + d.getMinutes()).slice(-2) + ":" +
        ("0" + d.getSeconds()).slice(-2);
    return "[T+" + mm + ":" + ss + " " + hhmmss + "]";
}

function toHex(bytes) {
    var u = new Uint8Array(bytes);
    var out = [];
    for (var i = 0; i < u.length; i++) {
        out.push((u[i] < 16 ? "0" : "") + u[i].toString(16));
    }
    return out.join(" ");
}

function hexBlock(bytes) {
    var u = new Uint8Array(bytes);
    var lines = [];
    for (var off = 0; off < u.length; off += 16) {
        var end = Math.min(off + 16, u.length);
        var hx = "", as = "";
        for (var i = off; i < off + 16; i++) {
            if (i < end) {
                var b = u[i];
                hx += (b < 16 ? "0" : "") + b.toString(16) + " ";
                as += (b >= 0x20 && b < 0x7f) ? String.fromCharCode(b) : ".";
            } else {
                hx += "   ";
            }
            if (i - off === 7) hx += " ";
        }
        var offh = off.toString(16);
        while (offh.length < 8) offh = "0" + offh;
        lines.push(offh + "  " + hx + " |" + as + "|");
    }
    return lines.join("\n");
}

function looksText(u) {
    var n = u.length;
    if (n === 0) return false;
    var bad = 0;
    for (var i = 0; i < n; i++) {
        var b = u[i];
        if (b === 9 || b === 10 || b === 13) continue;
        if (b < 0x20 || b >= 0x7f) bad++;
    }
    return bad <= Math.max(2, Math.floor(n / 16));
}

function classify(u) {
    if (u.length >= 6 &&
        u[0] === 0xff && u[1] === 0x55 && u[2] === 0x02 && u[3] === 0x00 &&
        u[4] === 0xee && u[5] === 0x10) {
        return "DETECT";
    }
    if (u.length >= 2 && u[0] === 0xff && u[1] === 0x5a) {
        return "LINK";
    }
    if (looksText(u)) {
        var s = "";
        try { s = String.fromCharCode.apply(null, u); } catch (e) { return "TEXT"; }
        if (s.indexOf("POST /command") >= 0 || s.indexOf("iAPSendMessage") >= 0) return "RTSP";
        if (s.indexOf("iAP") >= 0) return "IAP?";
        return "TEXT";
    }
    return "OTHER";
}

/* ---------------- 符号定位 ---------------- */

function stripPtr(p) {
    // iOS arm64e：Frida 17 取出的导出地址高位是 PAC，不在可执行映射里，
    // Interceptor 挂上去也不会进 onEnter。低 36 位才是用户态地址。
    if (!p) return p;
    try { return ptr(p).and(ptr("0xfffffffff")); } catch (e) { return p; }
}

function findExport(name) {
    // libSystem 只是转发桩。进程内部直接调 libcorecrypto，挂桩上 enc 一直是 0。
    // Frida 17 的 Module.findExportByName(module, name) 不再按模块名解析，
    // 只能枚举模块后用实例方法。
    var mods = Process.enumerateModules();
    var fallback = null;
    for (var i = 0; i < mods.length; i++) {
        try {
            var p = mods[i].findExportByName(name);
            if (!p) continue;
            var hit = { module: mods[i].name, addr: stripPtr(p) };
            if (mods[i].name.indexOf("corecrypto") >= 0) return hit;
            if (!fallback) fallback = hit;
        } catch (e) { /* 单模块枚举异常忽略 */ }
    }
    if (fallback) return fallback;
    try {
        var g = Module.getGlobalExportByName(name);
        if (g) return { module: "<global>", addr: g };
    } catch (e) { }
    return null;
}

/* ---------------- 事件记录 ---------------- */

function logFrame(dir, aadBytes, aadLen, bodyPtr, bodyLen) {
    if (bodyLen <= 0) return;

    lock.acquire();
    try {
        var dumpLen = bodyLen < MAX_DUMP_BYTES ? bodyLen : MAX_DUMP_BYTES;
        var bodyBytes;
        try {
            bodyBytes = bodyPtr.readByteArray(dumpLen);
        } catch (e) {
            lock.release();
            return;
        }
        var u = new Uint8Array(bodyBytes);
        var tag = classify(u);

        eventCount++;
        console.log(relTs() + " " + dir + " aadlen=" + aadLen + " tag=" + tag +
            " len=" + bodyLen + (bodyLen > MAX_DUMP_BYTES ? " (truncated)" : "") +
            " aad=[" + toHex(aadBytes) + "]");

        if (dumpLen < bodyLen) {
            console.log(hexBlock(bodyBytes) + "\n    ... (+" + (bodyLen - dumpLen) + " bytes omitted)");
        } else if (tag === "RTSP" || tag === "IAP?" || tag === "TEXT") {
            try {
                var s = String.fromCharCode.apply(null, u);
                console.log("    text: " + s.replace(/[\r\n]+/g, " | "));
            } catch (e) {
                console.log(hexBlock(bodyBytes));
            }
        } else {
            console.log(hexBlock(bodyBytes));
        }
    } finally {
        lock.release();
    }
}

function countSkipped(aadLen) {
    skippedCount++;
    var k = "" + aadLen;
    otherLenCount[k] = (otherLenCount[k] || 0) + 1;
}

/* ---------------- hook 安装 ---------------- */

var enc = findExport(NAME_ENCRYPT);
var dec = findExport(NAME_DECRYPT);

console.log("=== AAD event-channel hook ===");
console.log("pid=" + Process.id + " proc=" + Process.name +
    " arch=" + Process.arch + " platform=" + Process.platform +
    " wall=" + new Date().toISOString());

if (!enc && !dec) {
    console.log("!! 当前进程未导出 " + NAME_ENCRYPT + " / " + NAME_DECRYPT +
        " —— 可能不是事件通道宿主。Mac 端跑 ./find_host.sh 找宿主，再用 -p <pid> 重跑。");
}

if (enc) {
    console.log("hook encrypt @" + enc.addr + " in " + enc.module);
    Interceptor.attach(enc.addr, {
        onEnter: function (args) {
            encTotal++;
            var aadLen = args[3].toInt32();
            if (aadLen !== EVENT_AAD_LEN) {
                countSkipped(aadLen);
                return;
            }
            // 加密输入即明文；趁函数执行前快照拷贝（onLeave 时原 buffer 可能已回收/复用）
            var mLen = args[5].toInt32();
            var mBytes = null;
            try { mBytes = mLen > 0 ? args[4].readByteArray(mLen) : null; } catch (e) { }
            this.pending = {
                aad: args[2].readByteArray(aadLen),
                aadLen: aadLen,
                mBytes: mBytes,
                mLen: mLen
            };
        },
        onLeave: function (ret) {
            var p = this.pending;
            if (!p) return;
            if (ret.toInt32() !== 1) {
                console.log(relTs() + " ENCRYPT aadlen=" + p.aadLen + " FAILED ret=" +
                    ret.toInt32() + " len=" + p.mLen);
                return;
            }
            if (!p.mBytes) return;
            var buf = Memory.alloc(p.mLen);
            buf.writeByteArray(p.mBytes);
            logFrame("ENCRYPT ->HU", p.aad, p.aadLen, buf, p.mLen);
        }
    });
}

if (dec) {
    console.log("hook decrypt @" + dec.addr + " in " + dec.module);
    Interceptor.attach(dec.addr, {
        onEnter: function (args) {
            decTotal++;
            var aadLen = args[3].toInt32();
            if (aadLen !== EVENT_AAD_LEN) {
                countSkipped(aadLen);
                return;
            }
            this.aad = args[2].readByteArray(aadLen); // 预拷贝（2 字节）
            this.aadLen = aadLen;
            this.mOut = args[6];   // 解密结果（明文），onLeave 里读
            this.mLen = args[5].toInt32();
        },
        onLeave: function (ret) {
            var r = ret.toInt32();
            if (r !== 1) {
                console.log(relTs() + " DECRYPT <-HU aadlen=" + this.aadLen +
                    " FAILED ret=" + r + " len=" + this.mLen);
                return;
            }
            logFrame("DECRYPT <-HU", this.aad, this.aadLen, this.mOut, this.mLen);
        }
    });
}

// oneshot(info, key, iv, aad, aad_nbytes, ptext, ptext_nbytes, ctext, tag)
// AirPlay 短帧走这条，不走增量 encrypt。
var encOne = findExport("ccchacha20poly1305_encrypt_oneshot");
var decOne = findExport("ccchacha20poly1305_decrypt_oneshot");
if (encOne) {
    console.log("hook encrypt_oneshot @" + encOne.addr + " in " + encOne.module);
    Interceptor.attach(encOne.addr, {
        onEnter: function (args) {
            encTotal++;
            var aadLen = args[4].toInt32();
            if (aadLen !== EVENT_AAD_LEN) {
                countSkipped(aadLen);
                return;
            }
            var mLen = args[6].toInt32();
            var mBytes = null;
            try { mBytes = mLen > 0 ? args[5].readByteArray(mLen) : null; } catch (e) { }
            this.pending = {
                aad: args[3].readByteArray(aadLen),
                aadLen: aadLen,
                mBytes: mBytes,
                mLen: mLen
            };
        },
        onLeave: function (ret) {
            var p = this.pending;
            if (!p || !p.mBytes) return;
            if (ret.toInt32() !== 0 && ret.toInt32() !== 1) return;
            var buf = Memory.alloc(p.mLen);
            buf.writeByteArray(p.mBytes);
            logFrame("ENCRYPT ->HU", p.aad, p.aadLen, buf, p.mLen);
        }
    });
}
if (decOne) {
    console.log("hook decrypt_oneshot @" + decOne.addr + " in " + decOne.module);
    Interceptor.attach(decOne.addr, {
        onEnter: function (args) {
            decTotal++;
            var aadLen = args[4].toInt32();
            if (aadLen !== EVENT_AAD_LEN) {
                countSkipped(aadLen);
                return;
            }
            this.aad = args[3].readByteArray(aadLen);
            this.aadLen = aadLen;
            this.mOut = args[7];
            this.mLen = args[6].toInt32();
        },
        onLeave: function (ret) {
            var r = ret.toInt32();
            if (r !== 0 && r !== 1) return;
            logFrame("DECRYPT <-HU", this.aad, this.aadLen, this.mOut, this.mLen);
        }
    });
}

console.log("hook ready; waiting for CarPlay session... " +
    "(操作手机连车机，等 MMI 出画后停 20s 再 Ctrl-C)");

/* ---------------- 周期汇总（含画面流开始时间点） ---------------- */

var lastStream = 0;
setInterval(function () {
    lock.acquire();
    try {
        var other = "";
        for (var k in otherLenCount) other += " aad" + k + "=" + otherLenCount[k] + " ";
        var delta = skippedCount - lastStream;
        lastStream = skippedCount;
        console.log(relTs() + " SUMMARY enc=" + encTotal + " dec=" + decTotal +
            " events(aad=2)=" + eventCount +
            " skippedTotal=" + skippedCount + "(+" + delta + "/5s)" +
            (other ? " {" + other + "}" : ""));
    } finally {
        lock.release();
    }
}, SUMMARY_INTERVAL_MS);
