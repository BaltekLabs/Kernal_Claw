#!/usr/bin/env python3
"""Generate a crab-style Colonel Claw PNG without external dependencies."""

import struct
import zlib
from pathlib import Path

W = H = 320


def main() -> None:
    pix = [[(14, 20, 32, 255) for _ in range(W)] for __ in range(H)]

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

    def fill_rect(x0, y0, x1, y1, c):
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                put(x, y, c)

    def outline(mask_color, stroke):
        edge = []
        for y in range(1, H - 1):
            for x in range(1, W - 1):
                if pix[y][x] == mask_color:
                    for nx, ny in ((x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1)):
                        if pix[ny][nx] != mask_color:
                            edge.append((nx, ny))
        for x, y in edge:
            put(x, y, stroke)

    # Background halo
    fill_circle(160, 160, 132, (26, 45, 74, 255))

    shell = (206, 62, 66, 255)
    shell_dark = (176, 42, 50, 255)
    cream = (240, 223, 190, 255)
    eye_white = (247, 247, 247, 255)
    black = (20, 20, 20, 255)
    gold = (242, 203, 84, 255)
    uniform = (44, 94, 72, 255)

    # Body and belly
    fill_ellipse(160, 190, 88, 66, shell)
    fill_ellipse(160, 200, 54, 34, cream)

    # Claws
    fill_ellipse(88, 156, 34, 26, shell)
    fill_ellipse(232, 156, 34, 26, shell)
    fill_poly([(58, 152), (92, 138), (86, 166)], shell)
    fill_poly([(262, 152), (228, 138), (234, 166)], shell)

    # Legs
    fill_poly([(102, 212), (70, 234), (80, 244), (114, 222)], shell_dark)
    fill_poly([(126, 226), (96, 250), (108, 260), (136, 236)], shell_dark)
    fill_poly([(218, 212), (250, 234), (240, 244), (206, 222)], shell_dark)
    fill_poly([(194, 226), (224, 250), (212, 260), (184, 236)], shell_dark)

    # Eye stalks + eyes
    fill_rect(132, 108, 142, 152, shell)
    fill_rect(178, 108, 188, 152, shell)
    fill_circle(137, 102, 16, eye_white)
    fill_circle(183, 102, 16, eye_white)
    fill_circle(137, 106, 6, black)
    fill_circle(183, 106, 6, black)

    # Colonel cap
    fill_rect(102, 118, 218, 138, uniform)
    fill_rect(132, 90, 188, 116, uniform)
    fill_circle(160, 103, 6, gold)

    # Mouth + insignia bar
    fill_rect(132, 208, 188, 214, shell_dark)
    fill_rect(120, 236, 200, 250, (12, 16, 24, 255))
    fill_rect(128, 240, 138, 246, gold)
    fill_rect(144, 240, 154, 246, gold)

    for col in [shell, shell_dark, cream, eye_white, uniform, gold]:
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
