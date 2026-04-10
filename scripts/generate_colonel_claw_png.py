#!/usr/bin/env python3
"""Generate assets/colonel-claw.png without external dependencies.

This keeps PRs text-only when binary uploads are restricted.
"""

import struct
import zlib
from pathlib import Path

W = H = 320


def main() -> None:
    pix = [[(18, 24, 36, 255) for _ in range(W)] for __ in range(H)]

    def put(x, y, c):
        if 0 <= x < W and 0 <= y < H:
            pix[y][x] = c

    def fill_circle(cx, cy, r, c):
        r2 = r * r
        for y in range(cy - r, cy + r + 1):
            dy = y - cy
            for x in range(cx - r, cx + r + 1):
                dx = x - cx
                if dx * dx + dy * dy <= r2:
                    put(x, y, c)

    def fill_ellipse(cx, cy, rx, ry, c):
        rx2, ry2 = rx * rx, ry * ry
        lim = rx2 * ry2
        for y in range(cy - ry, cy + ry + 1):
            dy = y - cy
            for x in range(cx - rx, cx + rx + 1):
                dx = x - cx
                if dx * dx * ry2 + dy * dy * rx2 <= lim:
                    put(x, y, c)

    def fill_rect(x0, y0, x1, y1, c):
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                put(x, y, c)

    def fill_poly(pts, c):
        ys = [p[1] for p in pts]
        for y in range(min(ys), max(ys) + 1):
            xs = []
            for i in range(len(pts)):
                x1, y1 = pts[i]
                x2, y2 = pts[(i + 1) % len(pts)]
                if y1 == y2:
                    continue
                if min(y1, y2) <= y < max(y1, y2):
                    t = (y - y1) / (y2 - y1)
                    xs.append(int(x1 + t * (x2 - x1)))
            xs.sort()
            for i in range(0, len(xs), 2):
                if i + 1 < len(xs):
                    for x in range(xs[i], xs[i + 1] + 1):
                        put(x, y, c)

    def outline(mask_color, stroke):
        to_outline = []
        for y in range(1, H - 1):
            for x in range(1, W - 1):
                if pix[y][x] == mask_color:
                    for nx, ny in ((x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1)):
                        if pix[ny][nx] != mask_color:
                            to_outline.append((nx, ny))
        for x, y in to_outline:
            put(x, y, stroke)

    # Paint mascot
    fill_circle(160, 160, 130, (34, 50, 74, 255))
    body = (239, 229, 205, 255)
    wing = (225, 214, 189, 255)
    face = (247, 238, 216, 255)
    fill_ellipse(160, 186, 78, 95, body)
    fill_ellipse(111, 191, 30, 42, wing)
    fill_ellipse(209, 191, 30, 42, wing)
    fill_ellipse(160, 128, 62, 54, face)

    green = (40, 88, 66, 255)
    fill_rect(98, 70, 222, 94, green)
    fill_rect(128, 45, 192, 72, green)
    fill_circle(160, 59, 6, (244, 209, 90, 255))

    red = (214, 63, 63, 255)
    fill_circle(138, 68, 11, red)
    fill_circle(160, 64, 12, red)
    fill_circle(182, 68, 11, red)

    white = (250, 250, 250, 255)
    black = (20, 20, 20, 255)
    fill_circle(141, 128, 12, white)
    fill_circle(179, 128, 12, white)
    fill_circle(141, 131, 4, black)
    fill_circle(179, 131, 4, black)

    orange = (236, 170, 58, 255)
    fill_poly([(160, 142), (176, 152), (160, 168), (144, 152)], orange)

    fill_poly([(160, 188), (149, 209), (171, 209)], (190, 44, 50, 255))

    claw = (216, 160, 64, 255)
    fill_poly([(121, 234), (134, 244), (123, 258), (109, 252)], claw)
    fill_poly([(199, 234), (212, 244), (201, 258), (187, 252)], claw)

    fill_rect(36, 276, 284, 306, (13, 17, 25, 255))

    for x in range(52, 92):
        for y in range(283, 299):
            if x < 58 or y < 287 or y > 295:
                put(x, y, (235, 235, 235, 255))
    for x in range(98, 138):
        for y in range(283, 299):
            if x < 104 or y < 287 or y > 295:
                put(x, y, (235, 235, 235, 255))

    for col in [body, wing, face, green, red, white, orange, claw]:
        outline(col, (28, 28, 28, 255))

    raw = b""
    for row in pix:
        raw += b"\x00" + b"".join(bytes(px) for px in row)

    comp = zlib.compress(raw, 9)

    def chunk(tag, data):
        return (
            struct.pack("!I", len(data))
            + tag
            + data
            + struct.pack("!I", zlib.crc32(tag + data) & 0xFFFFFFFF)
        )

    png = (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack("!IIBBBBB", W, H, 8, 6, 0, 0, 0))
        + chunk(b"IDAT", comp)
        + chunk(b"IEND", b"")
    )

    out = Path("assets/colonel-claw.png")
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_bytes(png)
    print(f"Wrote {out} ({len(png)} bytes)")


if __name__ == "__main__":
    main()
