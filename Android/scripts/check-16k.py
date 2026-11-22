#!/usr/bin/env python3
"""Every native library in an APK must load on 16 KB pages: each PT_LOAD
segment aligned to at least 16 KB. python3 check-16k.py app.apk"""
import struct, sys, zipfile
failed = False
with zipfile.ZipFile(sys.argv[1]) as apk:
    for info in apk.infolist():
        if not info.filename.endswith(".so") or "/arm64-v8a/" not in info.filename and "/x86_64/" not in info.filename:
            continue
        data = apk.read(info.filename)
        assert data[:4] == b"\x7fELF" and data[4] == 2, info.filename
        phoff, = struct.unpack_from("<Q", data, 0x20); phentsize, phnum = struct.unpack_from("<HH", data, 0x36)
        aligns = [struct.unpack_from("<Q", data, phoff + i * phentsize + 0x30)[0] for i in range(phnum) if struct.unpack_from("<I", data, phoff + i * phentsize)[0] == 1]
        ok = min(aligns) >= 16384
        # Stored uncompressed libraries must also start on a 16 KB boundary in the APK.
        stored = info.compress_type == zipfile.ZIP_STORED
        # The data starts after the local header, whose extra field (zipalign's padding) can differ from the central one.
        with open(sys.argv[1], "rb") as raw:
            raw.seek(info.header_offset + 26); name_length, extra_length = struct.unpack("<HH", raw.read(4))
        offset = info.header_offset + 30 + name_length + extra_length
        if stored and offset % 16384: ok = False
        failed |= not ok
        print("%s %-55s LOAD align %s%s" % ("ok  " if ok else "FAIL", info.filename, hex(min(aligns)), " offset %d" % offset if stored else ""))
sys.exit(1 if failed else 0)
