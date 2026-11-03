#!/usr/bin/env python3
"""Generate launcher-shortcut icons for the Termux:Widget entries.

Termux:Widget shows a script's icon only when that script is published as a
launcher/dynamic shortcut (icons live in ~/.shortcuts/icons/<script_name>.png);
the list widget itself is plain text. These three icons keep the DSH look:
dark rounded square + one accent glyph per action.
"""
import math
import os
import struct
import zlib

OUT = os.environ.get("OUT_DIR", "/home/dsh/workspace/输出/DSH-图标")
SS = 4

BG = (0x0F, 0x11, 0x15)
OPEN = (0x99, 0xC8, 0xFF)
SYNC = (0x7E, 0xE0, 0xB0)
STOP = (0xFF, 0x8A, 0x8A)


def inside_round_rect(x, y, size, radius):
    dx = abs(x - size / 2) - (size / 2 - radius)
    dy = abs(y - size / 2) - (size / 2 - radius)
    return math.hypot(max(dx, 0), max(dy, 0)) + min(max(dx, dy), 0) <= radius


def seg_dist(px, py, ax, ay, bx, by):
    vx, vy = bx - ax, by - ay
    wx, wy = px - ax, py - ay
    d = vx * vx + vy * vy
    t = 0.0 if d == 0 else max(0.0, min(1.0, (wx * vx + wy * vy) / d))
    return math.hypot(px - (ax + t * vx), py - (ay + t * vy))


def in_triangle(px, py, a, b, c):
    def sign(p, q, r):
        return (p[0] - r[0]) * (q[1] - r[1]) - (q[0] - r[0]) * (p[1] - r[1])
    d1 = sign((px, py), a, b)
    d2 = sign((px, py), b, c)
    d3 = sign((px, py), c, a)
    neg = d1 < 0 or d2 < 0 or d3 < 0
    pos = d1 > 0 or d2 > 0 or d3 > 0
    return not (neg and pos)


def glyph_open(x, y, s):
    pts = [(0.34, 0.30), (0.52, 0.50), (0.34, 0.70)]
    stroke = 0.095 * s
    d = min(seg_dist(x, y, pts[0][0] * s, pts[0][1] * s, pts[1][0] * s, pts[1][1] * s),
            seg_dist(x, y, pts[1][0] * s, pts[1][1] * s, pts[2][0] * s, pts[2][1] * s))
    under = seg_dist(x, y, 0.58 * s, 0.70 * s, 0.74 * s, 0.70 * s) <= stroke / 2
    return d <= stroke / 2 or under


def glyph_sync(x, y, s):
    stroke = 0.075 * s
    top = seg_dist(x, y, 0.30 * s, 0.42 * s, 0.64 * s, 0.42 * s) <= stroke / 2
    head_r = in_triangle(x, y, (0.62 * s, 0.33 * s), (0.62 * s, 0.51 * s), (0.76 * s, 0.42 * s))
    bottom = seg_dist(x, y, 0.70 * s, 0.60 * s, 0.36 * s, 0.60 * s) <= stroke / 2
    head_l = in_triangle(x, y, (0.38 * s, 0.51 * s), (0.38 * s, 0.69 * s), (0.24 * s, 0.60 * s))
    return top or head_r or bottom or head_l


def glyph_stop(x, y, s):
    return inside_round_rect_glyph(x, y, s)


def inside_round_rect_glyph(x, y, s):
    left, right = 0.33 * s, 0.67 * s
    top, bottom = 0.33 * s, 0.67 * s
    r = 0.06 * s
    cx = max(left + r, min(x, right - r))
    cy = max(top + r, min(y, bottom - r))
    return math.hypot(x - cx, y - cy) <= r


# File names must match the renamed shortcut scripts exactly: Termux:Widget
# looks up ~/.shortcuts/icons/<script_name>.png for launcher shortcuts.
ICONS = {
    "① 打开 DSH": (OPEN, glyph_open),
    "② 同步文件": (SYNC, glyph_sync),
    "③ 停止 DSH": (STOP, glyph_stop),
}


def render(size, accent, glyph):
    ss = size * SS
    radius = 0.22 * ss
    inner = ss  # glyph coordinates are unit-relative to the full canvas
    pixels = []
    for iy in range(ss):
        row = []
        for ix in range(ss):
            x, y = ix + 0.5, iy + 0.5
            if not inside_round_rect(x, y, ss, radius):
                row.append(None)
            elif glyph(x, y, inner):
                row.append(accent)
            else:
                row.append(BG)
        pixels.append(row)

    out = bytearray()
    block = SS * SS
    for oy in range(size):
        for ox in range(size):
            r = g = b = a = 0
            for dy in range(SS):
                row = pixels[oy * SS + dy]
                for dx in range(SS):
                    c = row[ox * SS + dx]
                    if c is None:
                        continue
                    r += c[0]; g += c[1]; b += c[2]; a += 255
            if a == 0:
                out += b"\x00\x00\x00\x00"
            else:
                out += bytes((r * 255 // a, g * 255 // a, b * 255 // a, a // block))
    return bytes(out)


def write_png(path, size, rgba):
    raw = bytearray()
    stride = size * 4
    for y in range(size):
        raw.append(0)
        raw += rgba[y * stride:(y + 1) * stride]

    def chunk(tag, data):
        return (struct.pack(">I", len(data)) + tag + data
                + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))

    png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(bytes(raw), 9))
    png += chunk(b"IEND", b"")
    with open(path, "wb") as fh:
        fh.write(png)


def main():
    os.makedirs(OUT, exist_ok=True)
    for name, (accent, glyph) in ICONS.items():
        for size in (144,):
            path = os.path.join(OUT, f"{name}.png")
            write_png(path, size, render(size, accent, glyph))
            print("wrote", path)


if __name__ == "__main__":
    main()
