"""Colour statistics of a region of a PNG screenshot, without third-party modules.

Usage: png_stats.py <file.png> <left> <top> <right> <bottom>   (fractions of width and height)
Prints: "<distinct colours> <share of the most common colour>"
"""

import struct
import sys
import zlib


def read_png(path):
    with open(path, "rb") as f:
        data = f.read()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("not a PNG")
    pos, idat, width, height, colour_type, depth = 8, b"", 0, 0, 0, 0
    while pos < len(data):
        (length,) = struct.unpack(">I", data[pos : pos + 4])
        kind = data[pos + 4 : pos + 8]
        body = data[pos + 8 : pos + 8 + length]
        pos += 12 + length
        if kind == b"IHDR":
            width, height, depth, colour_type = struct.unpack(">IIBB", body[:10])
        elif kind == b"IDAT":
            idat += body
        elif kind == b"IEND":
            break
    if depth != 8 or colour_type not in (2, 6):
        raise ValueError(f"unsupported PNG: depth {depth}, colour type {colour_type}")
    bpp = 4 if colour_type == 6 else 3
    raw = zlib.decompress(idat)
    stride = width * bpp
    rows, prev, i = [], bytearray(stride), 0
    for _ in range(height):
        filt = raw[i]
        line = bytearray(raw[i + 1 : i + 1 + stride])
        i += 1 + stride
        for x in range(stride):
            a = line[x - bpp] if x >= bpp else 0
            b = prev[x]
            c = prev[x - bpp] if x >= bpp else 0
            if filt == 1:
                line[x] = (line[x] + a) & 0xFF
            elif filt == 2:
                line[x] = (line[x] + b) & 0xFF
            elif filt == 3:
                line[x] = (line[x] + ((a + b) >> 1)) & 0xFF
            elif filt == 4:
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                pred = a if pa <= pb and pa <= pc else (b if pb <= pc else c)
                line[x] = (line[x] + pred) & 0xFF
        rows.append(bytes(line))
        prev = line
    return width, height, bpp, rows


def main():
    path = sys.argv[1]
    left, top, right, bottom = (float(v) for v in sys.argv[2:6])
    width, height, bpp, rows = read_png(path)
    counts = {}
    for y in range(int(top * height), int(bottom * height), 2):
        row = rows[y]
        for x in range(int(left * width), int(right * width), 2):
            px = row[x * bpp : x * bpp + 3]
            counts[px] = counts.get(px, 0) + 1
    total = sum(counts.values())
    print(len(counts), f"{max(counts.values()) / total:.3f}")


if __name__ == "__main__":
    main()
