#!/usr/bin/env python3
"""Assemble the final APK: aapt2 base + classes.dex, zip-aligned, ready to sign.

Python's zipfile cannot control data offsets, and the x86_64 zipalign binary in
the Android build-tools cannot run inside this aarch64 container, so the
archive is written by hand here. Uncompressed entries are 4-byte aligned, which
is what Android requires for resources.arsc on API 30+.
"""
import struct
import sys
import time
import zipfile
import zlib

ALIGN = 4
EXTRA_ID = 0xD935  # Android's zipalign padding extra field


def deflate(data):
    compressor = zlib.compressobj(9, zlib.DEFLATED, -15)
    return compressor.compress(data) + compressor.flush()


def load_entries(base_apk, dex_path, dex_name="classes.dex"):
    entries = []
    with zipfile.ZipFile(base_apk) as archive:
        for info in archive.infolist():
            if info.is_dir():
                continue
            data = archive.read(info.filename)
            stored = info.compress_type == zipfile.ZIP_STORED
            if info.filename == "resources.arsc":
                stored = True  # must stay uncompressed and aligned
            entries.append({
                "name": info.filename,
                "data": data,
                "stored": stored,
                "time": info.date_time,
            })
    if dex_path:
        with open(dex_path, "rb") as handle:
            dex = handle.read()
        entries.append({"name": dex_name, "data": dex, "stored": False, "time": time.localtime()[:6]})
    return entries


def dos_time(struct_time):
    year = max(1980, struct_time[0])
    return (
        (struct_time[3] << 11) | (struct_time[4] << 5) | (struct_time[5] // 2),
        ((year - 1980) << 9) | (struct_time[1] << 5) | struct_time[2],
    )


def write_zip(entries, out_path):
    central = []
    offset = 0
    with open(out_path, "wb") as out:
        for entry in entries:
            name = entry["name"].encode("utf-8")
            raw = entry["data"]
            crc = zlib.crc32(raw) & 0xFFFFFFFF
            if entry["stored"]:
                method, payload, usize = 0, raw, len(raw)
            else:
                compressed = deflate(raw)
                if len(compressed) < len(raw):
                    method, payload = 8, compressed
                else:
                    method, payload = 0, raw
                usize = len(raw)
            if method == 0:
                pad = (ALIGN - ((offset + 30 + len(name) + 4) % ALIGN)) % ALIGN
                extra = struct.pack("<HH", EXTRA_ID, pad) + b"\x00" * pad
            else:
                extra = b""
            mtime, mdate = dos_time(entry["time"])
            local = struct.pack(
                "<IHHHHHIIIHH",
                0x04034B50, 20, 0, method, mtime, mdate,
                crc, len(payload), usize, len(name), len(extra),
            )
            out.write(local)
            out.write(name)
            out.write(extra)
            out.write(payload)
            central.append((name, method, mtime, mdate, crc, len(payload), usize, offset, extra))
            offset += len(local) + len(name) + len(extra) + len(payload)

        central_start = offset
        for name, method, mtime, mdate, crc, csize, usize, local_offset, extra in central:
            out.write(struct.pack(
                "<IHHHHHHIIIHHHHHII",
                0x02014B50, 20, 20, 0, method, mtime, mdate,
                crc, csize, usize, len(name), len(extra), 0, 0, 0, 0, local_offset,
            ))
            out.write(name)
            out.write(extra)
            offset += 46 + len(name) + len(extra)

        out.write(struct.pack(
            "<IHHHHIIH",
            0x06054B50, 0, 0, len(central), len(central),
            offset - central_start, central_start, 0,
        ))


def main():
    if len(sys.argv) < 3:
        print("usage: assemble.py <base.apk> <classes.dex> [output.apk]", file=sys.stderr)
        return 2
    base_apk, dex_path = sys.argv[1], sys.argv[2]
    out_path = sys.argv[3] if len(sys.argv) > 3 else "unsigned.apk"
    write_zip(load_entries(base_apk, dex_path), out_path)
    print("assembled", out_path)
    return 0


if __name__ == "__main__":
    sys.exit(main())
