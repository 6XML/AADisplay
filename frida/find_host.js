/* 单进程探测：该进程是否导出 cc-chacha 符号（配合 find_host.sh 对每个 pid 执行） */
"use strict";
var hits = [];
var mods = Process.enumerateModules();
for (var i = 0; i < mods.length; i++) {
    try {
        var e = mods[i].findExportByName("ccchacha20poly1305_encrypt");
        var d = mods[i].findExportByName("ccchacha20poly1305_decrypt");
        if (e || d) hits.push((e ? "enc" : "") + (d ? "+dec" : "") + " in " + mods[i].name);
    } catch (err) { }
}
if (hits.length) {
    send("HIT " + Process.id + " " + Process.name + " " + hits.join("; "));
} else {
    send("NO");
}
