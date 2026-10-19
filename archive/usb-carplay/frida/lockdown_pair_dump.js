/*
 * Dump lockdown Pair-related plists (device side). Frida 17 + arm64e PAC-safe.
 *
 *   ./run_pair_after_plug.sh
 * Force a fresh Pair after hook ready:
 *   idevicepair unpair && idevicepair pair
 */
'use strict';

function stripPac(p) {
  if (!p || p.isNull()) return p;
  try {
    return ptr(p).and(ptr('0xfffffffff'));
  } catch (_) {
    return p;
  }
}

function findExport(name) {
  var mods = Process.enumerateModules();
  for (var i = 0; i < mods.length; i++) {
    try {
      var p = mods[i].findExportByName(name);
      if (p) return stripPac(p);
    } catch (_) {}
  }
  try {
    return stripPac(Module.getGlobalExportByName(name));
  } catch (_) {
    return null;
  }
}

var cfCalls = 0;
var dumped = 0;

function looksInteresting(text, u8) {
  if (!text) text = '';
  if (
    text.indexOf('Pair') >= 0 ||
    text.indexOf('PairRecord') >= 0 ||
    text.indexOf('EscrowBag') >= 0 ||
    text.indexOf('DeviceCertificate') >= 0 ||
    text.indexOf('HostCertificate') >= 0 ||
    text.indexOf('RootCertificate') >= 0 ||
    text.indexOf('DevicePublicKey') >= 0 ||
    text.indexOf('StartSession') >= 0 ||
    text.indexOf('ValidatePair') >= 0 ||
    text.indexOf('Request') >= 0 ||
    text.indexOf('lockdown') >= 0
  )
    return true;
  // any XML / binary plist of reasonable size
  if (text.indexOf('<?xml') >= 0 || text.indexOf('<dict') >= 0) return true;
  if (u8 && u8.length >= 8 && u8[0] === 0x62 && u8[1] === 0x70) return true; // bplist
  return false;
}

function dumpPlistData(ptrBuf, len, tag) {
  if (!ptrBuf || ptrBuf.isNull() || len <= 0 || len > 2 * 1024 * 1024) return;
  var text = '';
  try {
    text = ptrBuf.readUtf8String(Math.min(len, 256 * 1024)) || '';
  } catch (_) {
    text = '';
  }
  var raw = ptrBuf.readByteArray(Math.min(len, 64));
  var u8 = raw ? new Uint8Array(raw) : null;
  if (!looksInteresting(text, u8)) return;

  dumped++;
  var head = [];
  if (u8) {
    for (var i = 0; i < Math.min(16, u8.length); i++)
      head.push(('0' + u8[i].toString(16)).slice(-2));
  }
  console.log(
    '\n==== ' + tag + ' len=' + len + ' dump#' + dumped + ' head=' + head.join(' ') + ' ===='
  );
  if (text.indexOf('<?xml') >= 0 || text.indexOf('<dict') >= 0 || text.indexOf('bplist') === 0) {
    console.log(text.length > 120000 ? text.slice(0, 120000) + '\n...truncated...' : text);
  } else {
    console.log(hexdump(ptrBuf, { length: Math.min(len, 4096), ansi: false }));
  }
}

function hookCF() {
  function attachCreateData(sym) {
    var addr = findExport(sym);
    if (!addr) {
      console.log('!! no ' + sym);
      return;
    }
    Interceptor.attach(addr, {
      onLeave: function (retval) {
        cfCalls++;
        if (retval.isNull()) return;
        try {
          var data = new ObjC.Object(retval);
          dumpPlistData(data.bytes(), data.length(), sym + '(out)');
        } catch (e) {
          if (cfCalls <= 5) console.log(sym + ' leave err ' + e);
        }
      },
    });
    console.log('hook ' + sym + ' ok @ ' + addr);
  }

  function attachCreateWith(sym) {
    var addr = findExport(sym);
    if (!addr) {
      console.log('!! no ' + sym);
      return;
    }
    Interceptor.attach(addr, {
      onEnter: function (args) {
        this.data = args[1];
      },
      onLeave: function () {
        cfCalls++;
        if (!this.data || this.data.isNull()) return;
        try {
          var data = new ObjC.Object(this.data);
          dumpPlistData(data.bytes(), data.length(), sym + '(in)');
        } catch (_) {}
      },
    });
    console.log('hook ' + sym + ' ok @ ' + addr);
  }

  attachCreateData('CFPropertyListCreateData');
  attachCreateWith('CFPropertyListCreateWithData');
  attachCreateWith('CFPropertyListCreateFromXMLData');
  attachCreateData('CFPropertyListCreateXMLData');
}

function hookIO() {
  // Plain lockdown frames often go over raw sockets before/without SSL.
  ['write', 'send', 'sendto'].forEach(function (name) {
    var addr = findExport(name);
    if (!addr) return;
    Interceptor.attach(addr, {
      onEnter: function (args) {
        var buf = args[1];
        var len = args[2].toInt32();
        if (len < 8 || len > 512 * 1024) return;
        try {
          dumpPlistData(buf, len, name);
        } catch (_) {}
      },
    });
    console.log('hook ' + name + ' ok @ ' + addr);
  });
  ['read', 'recv', 'recvfrom'].forEach(function (name) {
    var addr = findExport(name);
    if (!addr) return;
    Interceptor.attach(addr, {
      onEnter: function (args) {
        this.buf = args[1];
      },
      onLeave: function (retval) {
        var n = retval.toInt32();
        if (n < 8) return;
        try {
          dumpPlistData(this.buf, n, name);
        } catch (_) {}
      },
    });
    console.log('hook ' + name + ' ok @ ' + addr);
  });
}

setInterval(function () {
  console.log('[heartbeat] cfCalls=' + cfCalls + ' dumped=' + dumped);
}, 5000);

console.log('lockdown_pair_dump: pid=' + Process.id + ' arch=' + Process.arch);
hookCF();
hookIO();
console.log('hook ready — FORCE re-pair now: idevicepair unpair && idevicepair pair');
