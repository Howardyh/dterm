#!/usr/bin/env python3
"""Dterm launcher icon: a blue "D" terminal window with the prompt and the
Android mascot inside, on a soft light background.

Pure stdlib rasterizer (4x supersampling) for the legacy PNG mipmaps, plus
generated VectorDrawables for the adaptive foreground and the Android 13
monochrome layer, so both stay visually identical.
"""
import math
import os
import struct
import zlib

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.environ.get("OUT_DIR", os.path.join(ROOT, "previews"))
SS = 4

BG = (0xE9, 0xEF, 0xFA)
BLUE = (0x1F, 0x4D, 0xFF)
GREEN = (0x22, 0xC5, 0x5E)
RED = (0xEF, 0x44, 0x44)
AMBER = (0xFB, 0xBF, 0x24)

DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}


def inside_rrect(x, y, x0, y0, x1, y1, rtl, rtr, rbr, rbl):
    if x < x0 or x > x1 or y < y0 or y > y1:
        return False
    if x < x0 + rtl and y < y0 + rtl:
        return (x - (x0 + rtl)) ** 2 + (y - (y0 + rtl)) ** 2 <= rtl * rtl
    if x > x1 - rtr and y < y0 + rtr:
        return (x - (x1 - rtr)) ** 2 + (y - (y0 + rtr)) ** 2 <= rtr * rtr
    if x > x1 - rbr and y > y1 - rbr:
        return (x - (x1 - rbr)) ** 2 + (y - (y1 - rbr)) ** 2 <= rbr * rbr
    if x < x0 + rbl and y > y1 - rbl:
        return (x - (x0 + rbl)) ** 2 + (y - (y1 - rbl)) ** 2 <= rbl * rbl
    return True


def seg_dist(px, py, ax, ay, bx, by):
    vx, vy = bx - ax, by - ay
    wx, wy = px - ax, py - ay
    d = vx * vx + vy * vy
    t = 0.0 if d == 0 else max(0.0, min(1.0, (wx * vx + wy * vy) / d))
    return math.hypot(px - (ax + t * vx), py - (ay + t * vy))


# Geometry in a 108x108 viewport -------------------------------------------

def d_outer(x, y):
    return inside_rrect(x, y, 24, 24, 84, 84, 8, 30, 30, 8)


def d_counter(x, y):
    return inside_rrect(x, y, 40, 37, 73, 73, 4, 16, 14, 4)


def prompt_chevron(x, y):
    return min(seg_dist(x, y, 45, 42.5, 54, 51.5), seg_dist(x, y, 54, 51.5, 45, 60.5)) <= 2.2


def caret(x, y):
    return inside_rrect(x, y, 57, 54, 67, 58.2, 2.1, 2.1, 2.1, 2.1)


HEAD_CX, HEAD_CY, HEAD_R = 66.0, 73.0, 6.8


def android_head(x, y):
    if (x - HEAD_CX) ** 2 + (y - HEAD_CY) ** 2 <= HEAD_R ** 2:
        return True
    # antennae
    if seg_dist(x, y, HEAD_CX - 3.2, HEAD_CY - 5.6, HEAD_CX - 5.2, HEAD_CY - 8.4) <= 0.75:
        return True
    if seg_dist(x, y, HEAD_CX + 3.2, HEAD_CY - 5.6, HEAD_CX + 5.2, HEAD_CY - 8.4) <= 0.75:
        return True
    return False


def android_eye(x, y):
    return ((x - (HEAD_CX - 2.3)) ** 2 + (y - (HEAD_CY - 4.4)) ** 2 <= 0.85 ** 2 or
            (x - (HEAD_CX + 2.3)) ** 2 + (y - (HEAD_CY - 4.4)) ** 2 <= 0.85 ** 2)


def dots(x, y):
    for dx, color in ((40.0, RED), (49.0, AMBER), (58.0, GREEN)):
        if (x - dx) ** 2 + (y - 30.5) ** 2 <= 2.6 ** 2:
            return color
    return None


def sample(x, y):
    """Return an RGB colour or None for transparent."""
    rgb = None
    if d_outer(x, y):
        rgb = BLUE
    if d_counter(x, y):
        rgb = BG
        if prompt_chevron(x, y):
            rgb = BLUE
        elif caret(x, y):
            rgb = GREEN
        elif android_head(x, y):
            rgb = BG if android_eye(x, y) else GREEN
    dot = dots(x, y)
    if dot is not None and d_outer(x, y) and not d_counter(x, y):
        rgb = dot
    return rgb


def render(size, rounded_canvas):
    ss = size * SS
    grid = [[None] * ss for _ in range(ss)]
    for iy in range(ss):
        for ix in range(ss):
            x, y = (ix + 0.5) * 108.0 / ss, (iy + 0.5) * 108.0 / ss
            if rounded_canvas and not inside_rrect(x, y, 0, 0, 108, 108, 24, 24, 24, 24):
                continue
            if rounded_canvas:
                grid[iy][ix] = BG
            color = sample(x, y)
            if color is not None:
                grid[iy][ix] = color

    out = bytearray()
    block = SS * SS
    for oy in range(size):
        for ox in range(size):
            r = g = b = a = 0
            for dy in range(SS):
                row = grid[oy * SS + dy]
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


# Vector output ------------------------------------------------------------

def rrect_path(x0, y0, x1, y1, rtl, rtr, rbr, rbl):
    return (f"M{x0 + rtl:.2f},{y0:.2f} H{x1 - rtr:.2f} "
            f"A{rtr:.2f},{rtr:.2f} 0 0 1 {x1:.2f},{y0 + rtr:.2f} "
            f"V{y1 - rbr:.2f} A{rbr:.2f},{rbr:.2f} 0 0 1 {x1 - rbr:.2f},{y1:.2f} "
            f"H{x0 + rbl:.2f} A{rbl:.2f},{rbl:.2f} 0 0 1 {x0:.2f},{y1 - rbl:.2f} "
            f"V{y0 + rtl:.2f} A{rtl:.2f},{rtl:.2f} 0 0 1 {x0 + rtl:.2f},{y0:.2f} Z")


def hexc(c):
    return "#FF%02X%02X%02X" % c


def vector_foreground():
    outer = rrect_path(24, 24, 84, 84, 8, 30, 30, 8)
    counter = rrect_path(40, 37, 73, 73, 4, 16, 14, 4)
    caret_path = rrect_path(57, 54, 67, 58.2, 2.1, 2.1, 2.1, 2.1)
    parts = [
        f'    <path android:fillColor="{hexc(BLUE)}" android:pathData="{outer}" />',
        f'    <path android:fillColor="{hexc(BG)}" android:pathData="{counter}" />',
        f'    <path android:fillColor="#00000000" android:strokeColor="{hexc(BLUE)}" '
        f'android:strokeWidth="4.8" android:strokeLineCap="round" android:strokeLineJoin="round" '
        f'android:pathData="M45,42.5 L54,51.5 L45,60.5" />',
        f'    <path android:fillColor="{hexc(GREEN)}" android:pathData="{caret_path}" />',
        f'    <path android:fillColor="{hexc(RED)}" android:pathData="M40,28.5 a2.2,2.2 0 1,0 4.4,0 a2.2,2.2 0 1,0 -4.4,0" />',
        f'    <path android:fillColor="{hexc(AMBER)}" android:pathData="M49,28.5 a2.2,2.2 0 1,0 4.4,0 a2.2,2.2 0 1,0 -4.4,0" />',
        f'    <path android:fillColor="{hexc(GREEN)}" android:pathData="M58,28.5 a2.2,2.2 0 1,0 4.4,0 a2.2,2.2 0 1,0 -4.4,0" />',
        # Android head: dome + antennae, clipped visibly by the counter edge
        f'    <path android:fillColor="{hexc(GREEN)}" android:pathData="M{HEAD_CX - HEAD_R},73 '
        f'A{HEAD_R},{HEAD_R} 0 0 1 {HEAD_CX + HEAD_R},73 Z" />',
        f'    <path android:fillColor="#00000000" android:strokeColor="{hexc(GREEN)}" android:strokeWidth="1.6" '
        f'android:strokeLineCap="round" android:pathData="M62.8,67.4 L60.8,64.6 M69.2,67.4 L71.2,64.6" />',
        f'    <path android:fillColor="{hexc(BG)}" android:pathData="M62.8,68.6 a0.85,0.85 0 1,0 1.7,0 a0.85,0.85 0 1,0 -1.7,0" />',
        f'    <path android:fillColor="{hexc(BG)}" android:pathData="M67.5,68.6 a0.85,0.85 0 1,0 1.7,0 a0.85,0.85 0 1,0 -1.7,0" />',
    ]
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    android:width="108dp"\n'
            '    android:height="108dp"\n'
            '    android:viewportWidth="108"\n'
            '    android:viewportHeight="108">\n'
            + "\n".join(parts) + "\n</vector>\n")


def vector_monochrome():
    outer = rrect_path(24, 24, 84, 84, 8, 30, 30, 8)
    counter = rrect_path(40, 37, 73, 73, 4, 16, 14, 4)
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    android:width="108dp"\n'
            '    android:height="108dp"\n'
            '    android:viewportWidth="108"\n'
            '    android:viewportHeight="108">\n'
            f'    <path android:fillColor="#FFFFFFFF" android:fillType="evenOdd" '
            f'android:pathData="{outer} {counter}" />\n'
            '</vector>\n')


def main():
    os.makedirs(OUT, exist_ok=True)
    for size in (192, 512):
        write_png(os.path.join(OUT, f"dterm-{size}.png"), size, render(size, True))
    for density, size in DENSITIES.items():
        folder = os.path.join(ROOT, "res", "mipmap-" + density)
        os.makedirs(folder, exist_ok=True)
        write_png(os.path.join(folder, "ic_launcher.png"), size, render(size, True))
    with open(os.path.join(ROOT, "res", "drawable", "ic_launcher_foreground.xml"), "w", encoding="utf-8") as fh:
        fh.write(vector_foreground())
    with open(os.path.join(ROOT, "res", "drawable", "ic_launcher_monochrome.xml"), "w", encoding="utf-8") as fh:
        fh.write(vector_monochrome())
    print("Dterm icon written")


if __name__ == "__main__":
    main()
