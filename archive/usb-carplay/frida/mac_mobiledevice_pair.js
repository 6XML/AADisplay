/*
 * Mac-side: dump MobileDevice Pair / pairing material while CarPlay Simulator runs.
 * Survives iPhone plug/unplug because Frida attaches to the Mac process, not USB.
 *
 *   frida -n "CarPlay Simulator" -l mac_mobiledevice_pair.js -o pair-mac.txt
 *   # if name differs:
 *   frida-ps -aia | grep -i carplay
 *   frida -p <pid> -l mac_mobiledevice_pair.js -o pair-mac.txt
 */
'use strict';

function findExport(name) {
  var mods = Process.enumerateModules();
  for (var i = 0; i < mods.length; i++) {
    try {
      var p = mods[i].findExportByName(name);
      if (p) return { name: mods[i].name, addr: p };
    } catch (_) {}
  }
  try {
    var g = Module.getGlobalExportByName(name);
    if (g) return { name: '<global>', addr: g };
  } catch (_) {}
  return null;
}

function dumpBuf(ptr, len, tag) {
  if (!ptr || ptr.isNull() || len <= 0 || len > 4 * 1024 * 1024) return;
  var text = '';
  try {
    text = ptr.readUtf8String(Math.min(len, 512 * 1024)) || '';
  } catch (_) {
    text = '';
  }
  var hit =
    text.indexOf('Pair') >= 0 ||
    text.indexOf('pairing') >= 0 ||
    text.indexOf('PairRecord') >= 0 ||
    text.indexOf('DeviceCertificate') >= 0 ||
    text.indexOf('HostCertificate') >= 0 ||
    text.indexOf('RootCertificate') >= 0 ||
    text.indexOf('EscrowBag') >= 0 ||
    text.indexOf('AMDevice') >= 0;
  if (!hit && tag.indexOf('Pair') < 0) return;
  console.log('\n==== ' + tag + ' len=' + len + ' ====');
  if (text.indexOf('<?xml') >= 0 || text.indexOf('<dict') >= 0 || text.indexOf('bplist') >= 0) {
    console.log(text.length > 100000 ? text.slice(0, 100000) + '\n...truncated...' : text);
  } else {
    console.log(hexdump(ptr, { length: Math.min(len, 2048), ansi: false }));
  }
}

function hookCF() {
  var createData = findExport('CFPropertyListCreateData');
  if (createData) {
    Interceptor.attach(createData.addr, {
      onLeave: function (retval) {
        if (retval.isNull()) return;
        try {
          var data = new ObjC.Object(retval);
          dumpBuf(data.bytes(), data.length(), 'CFCreateData');
        } catch (_) {}
      },
    });
    console.log('hook CFPropertyListCreateData @ ' + createData.name);
  }
  var createWith = findExport('CFPropertyListCreateWithData');
  if (createWith) {
    Interceptor.attach(createWith.addr, {
      onEnter: function (args) {
        this.data = args[1];
      },
      onLeave: function () {
        if (!this.data || this.data.isNull()) return;
        try {
          var data = new ObjC.Object(this.data);
          dumpBuf(data.bytes(), data.length(), 'CFCreateWithData');
        } catch (_) {}
      },
    });
    console.log('hook CFPropertyListCreateWithData @ ' + createWith.name);
  }
}

function hookAMDevice() {
  var names = [
    'AMDevicePair',
    'AMDevicePairWithCallback',
    'AMDeviceValidatePairing',
    'AMDeviceConnect',
    'AMDeviceStartSession',
    'AMDeviceSecurePair',
  ];
  names.forEach(function (n) {
    var hit = findExport(n);
    if (!hit) {
      console.log('skip ' + n);
      return;
    }
    Interceptor.attach(hit.addr, {
      onEnter: function (args) {
        console.log('\n>> ' + n + ' enter @ ' + hit.name);
        this.n = n;
      },
      onLeave: function (retval) {
        console.log('<< ' + this.n + ' => ' + retval);
      },
    });
    console.log('hook ' + n + ' @ ' + hit.name);
  });
}

console.log('mac_mobiledevice_pair pid=' + Process.id + ' ' + Process.enumerateModules()[0].name);
hookCF();
hookAMDevice();
console.log('hook ready — plug iPhone / let Simulator pair');
