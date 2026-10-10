"""生成一张合成音乐封面 PNG,用来在叠加层里顶替真实封面流。

为什么单独一个脚本而不是在 make-sim-map.py 里加个参数:两张图的**尺寸约束
完全不同** —— 地图必须正好 1920x720(编码流坐标,ClusterLayout.STREAM_*),
封面只要正方形(卡片会自己裁成圆角方形)。混在一个脚本里会让"这张图按谁的
尺寸算"这种前提在代码里含糊起来。

和地图图一样的选择:纯 Python 写 PNG(zlib + struct,零依赖),因为环境里
没有 Pillow。这张图只需要色块和直线,不需要抗锯齿。

配色刻意做得**和卡片明显不同**(暖橙紫渐层 + 大圆形音符),因为要检查的
正是"封面有没有被正确裁成圆角、有没有和深色玻璃卡片分得开" —— 底色跟卡片
太接近就看不出边界了。
"""

import struct
import sys
import zlib

S = 512  # 正方形。卡片里按 min(宽,高) 缩放,分辨率大于仪表栏位即可。
# 由暖到紫的竖向渐层:和深色玻璃卡片(冷灰黑)形成明显色相差。
TOP = (0xF2, 0x7B, 0x3C)
BOTTOM = (0x6B, 0x2E, 0x8C)
# 深色音符,压在渐层上。
INK = (0x1A, 0x12, 0x22)


def make_canvas():
    return [bytearray(INK * S) for _ in range(S)]


def fill_rect(buf, x0, y0, x1, y1, color):
    x0, x1 = max(0, min(x0, x1)), min(S, max(x0, x1))
    y0, y1 = max(0, min(y0, y1)), min(S, max(y0, y1))
    for y in range(y0, y1):
        row = buf[y]
        for x in range(x0, x1):
            i = x * 3
            row[i] = color[0]
            row[i + 1] = color[1]
            row[i + 2] = color[2]


def blend(buf, x, y, color, alpha):
    """按 alpha 把 color 混到已有像素上(就地,不返回新值)。"""
    if not (0 <= x < S and 0 <= y < S):
        return
    i = x * 3
    row = buf[y]
    for c in range(3):
        row[i + c] = int(row[i + c] * (1 - alpha) + color[c] * alpha)


def fill_circle(buf, cx, cy, r, color, alpha=1.0):
    """抗锯齿圆:边缘 1px 按到圆心的距离做 alpha 过渡。

    不做抗锯齿的话圆边在 512px 上看不出问题,但卡片里被缩到 60px 左右后
    阶梯会很明显 —— 而"封面圆角裁剪是否干净"正是要检查的东西之一。

    边缘的过渡系数必须**乘上** alpha 而不是替换它:早先写成
    `a = alpha if d <= r-1 else ramp(...)`,结果 r 附近直接变成不透明,
    半透明的装饰圆在渐层上留下一圈硬白边(实测)。
    """
    for y in range(cy - r - 1, cy + r + 2):
        dy = y - cy
        for x in range(cx - r - 1, cx + r + 2):
            dx = x - cx
            d = (dx * dx + dy * dy) ** 0.5
            if d > r + 1:
                continue
            # d<=r-1 完全实心,r-1..r+1 线性淡出到 0。
            coverage = 1.0 if d <= r - 1 else max(0.0, min(1.0, r + 0.5 - d))
            a = alpha * coverage
            if a > 0:
                blend(buf, x, y, color, a)


def stroke_line(buf, x0, y0, x1, y1, width, color, alpha=1.0):
    """粗线段(用于音符的符干与符尾)。沿主轴逐格推进,每格画一个 width×width 的方块。

    两端各延后半宽,否则线段端头是平的、与符头看起来是"贴上去"而不是连着长出来。

    ★ 主轴要按 dx/dy 中较大的那个选,不能一律按 x 插值:竖直线(x0==x1)
      早先写成 steps = max(|dx|,|dy|) 却仍用 x 插值,结果每步 x 不变、dy 的
      方块被摊到不同 y 上 —— 等效成 1px 细线(实测符干只有 1px)。
      竖直时改成沿 y 推进,方块才真正叠成 width 宽的竖条。
    """
    half = width // 2
    dx, dy = x1 - x0, y1 - y0
    steps = max(abs(dx), abs(dy), 1)
    vertical = abs(dy) >= abs(dx)
    for i in range(-half, steps + half + 1):
        t = max(0.0, min(1.0, i / float(steps)))
        x = int(x0 + dx * t)
        y = int(y0 + dy * t)
        for ox in range(-half, half + 1):
            for oy in range(-half, half + 1):
                blend(buf, x + ox, y + oy, color, alpha)


def write_png(path, rows):
    raw = bytearray()
    for row in rows:
        raw.append(0)  # filter type 0
        raw.extend(row)

    def chunk(tag, data):
        out = struct.pack(">I", len(data)) + tag + data
        return out + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    header = struct.pack(">IIBBBBB", S, S, 8, 2, 0, 0, 0)
    png = (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", header)
        + chunk(b"IDAT", zlib.compress(bytes(raw), 9))
        + chunk(b"IEND", b"")
    )
    with open(path, "wb") as fh:
        fh.write(png)


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else "sim-cover.png"
    buf = make_canvas()

    # 竖向渐层。逐行算一次颜色,再整行填充。
    for y in range(S):
        t = y / float(S - 1)
        color = (
            int(TOP[0] * (1 - t) + BOTTOM[0] * t),
            int(TOP[1] * (1 - t) + BOTTOM[1] * t),
            int(TOP[2] * (1 - t) + BOTTOM[2] * t),
        )
        fill_rect(buf, 0, y, S, y + 1, color)

    # 两个半透明大圆:制造"唱片/光斑"的层次感,顺便让渐层不那么平。
    fill_circle(buf, int(S * 0.28), int(S * 0.30), int(S * 0.34), (255, 255, 255), 0.07)
    fill_circle(buf, int(S * 0.74), int(S * 0.72), int(S * 0.42), (0, 0, 0), 0.10)

    # 音符(八分音符 ♪):符头实心椭圆 + 竖直符干 + 顶部符尾。
    #
    # 比例为什么这样定:符干宽取符头短轴的 ~0.45(0.042S vs 0.085S),
    # 再细就会像一根线而不是"符干";符干长度约等于符头宽的 2.4 倍,
    # 这是八分音符的常规观感。整块以 (0.46S, 0.47S) 为视觉重心居中 ——
    # 音符偏到右下角会在卡片里看着像"贴了个角标"。
    # 符干下端必须**埋进符头内部**并让符头后画,否则符干与符头像两截(实测)。
    head_cx, head_cy = int(S * 0.42), int(S * 0.615)
    head_rx, head_ry = int(S * 0.125), int(S * 0.090)
    stem_w = int(S * 0.042)
    stem_x = head_cx + head_rx - stem_w // 3
    stem_bottom = head_cy - int(head_ry * 0.45)  # 埋进符头,确保连上
    stem_top = int(S * 0.245)
    stroke_line(buf, stem_x, stem_bottom, stem_x, stem_top, stem_w, INK)
    # 符尾:符干顶端向右下的两段折线,近似 eighth-note 的旗。
    flag_w = int(S * 0.036)
    flag_mid_x = stem_x + int(S * 0.105)
    flag_mid_y = stem_top + int(S * 0.052)
    stroke_line(buf, stem_x, stem_top, flag_mid_x, flag_mid_y, flag_w, INK)
    stroke_line(
        buf, flag_mid_x, flag_mid_y,
        flag_mid_x + int(S * 0.062), flag_mid_y + int(S * 0.098), flag_w, INK,
    )

    # 符头最后画,盖住符干下端 —— 先画符头再画符干的话符干会横穿符头。
    for y in range(head_cy - head_ry - 1, head_cy + head_ry + 2):
        for x in range(head_cx - head_rx - 1, head_cx + head_rx + 2):
            dx = (x - head_cx) / float(head_rx)
            dy = (y - head_cy) / float(head_ry)
            d = (dx * dx + dy * dy) ** 0.5
            if d <= 1.0:
                fill_circle_alpha_ellipse(buf, x, y, d, INK)

    write_png(out, buf)
    print(f"已生成 {out} {S}x{S}")


def fill_circle_alpha_ellipse(buf, x, y, normalized_distance, color):
    """椭圆内按归一化距离做边缘抗锯齿。"""
    a = 1.0 if normalized_distance <= 0.94 else max(0.0, min(1.0, 1.0 - normalized_distance) * 16.0)
    if a <= 0:
        return
    blend(buf, x, y, color, a)


if __name__ == "__main__":
    main()
