"""生成一张合成导航地图PNG,用来在叠加层里顶替真实地图流。

为什么不用 AI 生成图:尺寸必须正好是编码流 1920x720(ClusterLayout.STREAM_*),
而生成模型的输出比例不可控,还得二次裁剪。程序化生成给的是**已知尺寸、
已知配色**的图,更符合"模拟仪表流"的需要。

为什么不用 Pillow:环境里没有(PIL 不在解释器里)。纯 Python 写 PNG 只需
zlib + struct,依赖为零,几十行就够 —— 这张图是直线和色块,不需要抗锯齿。

图的内容刻意做成深色夜间导航风格(底 #16202e,路#3c4a5e,路线 #2f7fff),
因为 ClusterLayout.dark() 为 true 时卡片配色是浅字深底;底图太亮会掩盖
卡片对比度问题,那正是这套截图要检查的东西。
"""

import struct
import sys
import zlib

W, H = 1920, 720
BG = (0x16, 0x20, 0x2E)
PARK = (0x1B, 0x2E, 0x28)
ROAD = (0x3C, 0x4A, 0x5E)
ROAD_MINOR = (0x2C, 0x37, 0x46)
ROUTE = (0x2F, 0x7F, 0xFF)
MARKER = (0xFF, 0xFF, 0xFF)


def make_canvas():
    # 行优先:每行一个 bytearray,便于后续按行上色。
    return [bytearray(BG * W) for _ in range(H)]


def px(buf, x, y, color):
    if 0 <= x < W and 0 <= y < H:
        i = x * 3
        buf[y][i] = color[0]
        buf[y][i + 1] = color[1]
        buf[y][i + 2] = color[2]


def fill_rect(buf, x0, y0, x1, y1, color):
    x0, x1 = max(0, min(x0, x1)), min(W, max(x0, x1))
    y0, y1 = max(0, min(y0, y1)), min(H, max(y0, y1))
    for y in range(y0, y1):
        row = buf[y]
        for x in range(x0, x1):
            i = x * 3
            row[i] = color[0]
            row[i + 1] = color[1]
            row[i + 2] = color[2]


def fill_circle(buf, cx, cy, r, color):
    r2 = r * r
    for y in range(cy - r, cy + r + 1):
        dy2 = (y - cy) ** 2
        if dy2 > r2:
            continue
        dx = int((r2 - dy2) ** 0.5)
        fill_rect(buf, cx - dx, y, cx + dx + 1, y + 1, color)


def stroke_polyline(buf, points, color, width):
    """粗线段:每个点画一个实心方块,相邻段用矩形补齐。"""
    half = width // 2
    for i in range(len(points) - 1):
        x0, y0 = points[i]
        x1, y1 = points[i + 1]
        if abs(x1 - x0) >= abs(y1 - y0):
            lo, hi = sorted((x0, x1))
            for x in range(lo - half, hi + half + 1):
                # 斜率较小时垂直跨度有限,直接按 dy/dx 算,避免整列填充。
                span = half if x1 != x0 else 0
                t = 0.0 if x1 == x0 else (x - x0) / float(x1 - x0)
                yc = int(y0 + (y1 - y0) * t)
                fill_rect(buf, x, yc - span, x + 1, yc + span + 1, color)
        else:
            lo, hi = sorted((y0, y1))
            for y in range(lo - half, hi + half + 1):
                span = half if y1 != y0 else 0
                t = 0.0 if y1 == y0 else (y - y0) / float(y1 - y0)
                xc = int(x0 + (x1 - x0) * t)
                fill_rect(buf, xc - span, y, xc + span + 1, y + 1, color)
    for x, y in points:
        fill_rect(buf, x - half, y - half, x + half + 1, y + half + 1, color)


def draw_marker(buf, cx, cy):
    """当前车位置:白圆 + 深色内芯,模拟常见的目标点。"""
    fill_circle(buf, cx, cy, 18, MARKER)
    fill_circle(buf, cx, cy, 8, BG)
    fill_circle(buf, cx, cy, 5, MARKER)


def write_png(path, rows):
    raw = bytearray()
    for row in rows:
        raw.append(0)  # filter type 0
        raw.extend(row)

    def chunk(tag, data):
        out = struct.pack(">I", len(data)) + tag + data
        return out + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    header = struct.pack(">IIBBBBB", W, H, 8, 2, 0, 0, 0)
    png = (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", header)
        + chunk(b"IDAT", zlib.compress(bytes(raw), 9))
        + chunk(b"IEND", b"")
    )
    with open(path, "wb") as fh:
        fh.write(png)


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else "sim-map.png"
    buf = make_canvas()

    # 绿地块:让画面不是纯路网,更接近真实地图的纹理密度。
    fill_rect(buf, 150, 120, 430, 300, PARK)
    fill_rect(buf, 1180, 430, 1520, 640, PARK)
    fill_rect(buf, 700, 60, 900, 180, PARK)

    # 主干路:竖向
    for x in (120, 420, 700, 980, 1300, 1600, 1850):
        fill_rect(buf, x - 9, 0, x + 9, H, ROAD)
    # 主干路:横向
    for y in (90, 260, 430, 600):
        fill_rect(buf, 0, y - 9, W, y + 9, ROAD)
    # 次干路
    for x in (270, 560, 840, 1140, 1450):
        fill_rect(buf, x - 5, 0, x + 5, H, ROAD_MINOR)
    for y in (175, 345, 515):
        fill_rect(buf, 0, y - 5, W, y + 5, ROAD_MINOR)

    # 路线:竖向走 → 横向走 → 竖向走,形成典型导航折线。
    #
    # ★ 横段必须落在**源图中央**(x≈760..1160)。卡片模式取的是居中源块
    #   x=451..1469,早先把折线放在 420→1300 时横段虽然也覆盖中央,但竖边
    #   都压在源块边缘外,中栏里看着只有一条横线、两侧空荡(实测截图)。
    #   三个 x 都落在 451..1469 内,中栏才会呈现完整的"进弯路"。
    stroke_polyline(
        buf,
        [(760, H), (760, 300), (1180, 300), (1180, 0)],
        ROUTE,
        22,
    )
    draw_marker(buf, 760, 610)
    # 目的地旗标,让"往哪走"一眼可读。
    draw_marker(buf, 1180, 60)

    write_png(out, buf)
    print(f"已生成 {out} {W}x{H}")


if __name__ == "__main__":
    main()