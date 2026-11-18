"""로우 폴리 에어팟 리소스 생성기.

각 부품을 실루엣(shapely 도형) + 높이장(가짜 3D 곡면)으로 정의하고,
실루엣 안을 Delaunay 삼각분할한 뒤 면마다 법선을 구해 조명을 계산한다.
같은 기하에서 VectorDrawable(XML)과 PNG(미리보기/런처/로고)를 함께 만든다.
"""
import math
import os
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFont
from scipy.spatial import Delaunay
from shapely import affinity
from shapely.geometry import LineString, Point, Polygon, box
from shapely.ops import unary_union

RES = sys.argv[1] if len(sys.argv) > 1 else "out/res"
PREVIEW = os.environ.get("LOWPOLY_PREVIEW", "out/preview")

LIGHT = np.array([-0.42, -0.62, 0.66])  # 왼쪽 위에서 오는 빛 (y 아래가 +)
LIGHT /= np.linalg.norm(LIGHT)
HALF = LIGHT + np.array([0, 0, 1.0])
HALF /= np.linalg.norm(HALF)


# ---------------- 도형 ----------------

def ellipse(cx, cy, rx, ry, rot=0):
    e = affinity.scale(Point(0, 0).buffer(1, 64), rx, ry)
    return affinity.translate(affinity.rotate(e, rot, origin=(0, 0)), cx, cy)


def rrect(x0, y0, x1, y1, r):
    return box(x0 + r, y0 + r, x1 - r, y1 - r).buffer(r, 32)


def capsule(p0, p1, r0, r1=None):
    """두 끝 반지름이 다른 둥근 막대 (스템)."""
    r1 = r0 if r1 is None else r1
    return unary_union([Point(p0).buffer(r0, 32), Point(p1).buffer(r1, 32),
                        Polygon(_tangent_quad(p0, p1, r0, r1))]).convex_hull


def _tangent_quad(p0, p1, r0, r1):
    (x0, y0), (x1, y1) = p0, p1
    dx, dy = x1 - x0, y1 - y0
    n = math.hypot(dx, dy)
    nx, ny = -dy / n, dx / n
    return [(x0 + nx * r0, y0 + ny * r0), (x1 + nx * r1, y1 + ny * r1),
            (x1 - nx * r1, y1 - ny * r1), (x0 - nx * r0, y0 - ny * r0)]


# ---------------- 높이장 ----------------

def h_dome(cx, cy, rx, ry, depth=1.0):
    def f(x, y):
        t = 1 - ((x - cx) / rx) ** 2 - ((y - cy) / ry) ** 2
        return depth * min(rx, ry) * math.sqrt(max(t, 0.0))
    return f


def h_pillow(shape, edge, bulge=0.0):
    """가장자리가 둥글게 말리고 가운데는 거의 평평한 면 (케이스 본체 등)."""
    b = shape.boundary
    minx, miny, maxx, maxy = shape.bounds
    cx, cy = (minx + maxx) / 2, (miny + maxy) / 2
    sx, sy = (maxx - minx) / 2, (maxy - miny) / 2

    def f(x, y):
        d = b.distance(Point(x, y)) if shape.contains(Point(x, y)) else 0.0
        t = min(d / edge, 1.0)
        dome = 1 - ((x - cx) / sx) ** 2 - ((y - cy) / sy) ** 2
        return edge * math.sqrt(1 - (1 - t) ** 2) + bulge * max(dome, 0)
    return f


def h_rod(p0, p1, r0, r1=None):
    r1 = r0 if r1 is None else r1
    seg = LineString([p0, p1])

    def f(x, y):
        p = Point(x, y)
        t = seg.project(p, normalized=True)
        r = r0 + (r1 - r0) * t
        d = seg.distance(p)
        return r * math.sqrt(max(1 - (d / r) ** 2, 0.0))
    return f


# ---------------- 부품 / 삼각분할 ----------------

class Part:
    def __init__(self, shape, height, color, step=7.0, seed=1, gloss=0.35, amb=0.62, jitter=0.03, axis=None):
        self.axis = axis  # (p0, p1, r0, r1): 막대면 원통처럼 세로 띠로 나눈다
        self.shape, self.height, self.color = shape, height, np.array(color, float)
        self.step, self.seed, self.gloss, self.amb, self.jitter = step, seed, gloss, amb, jitter


def hexrgb(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def triangulate(part):
    rng = np.random.default_rng(part.seed)
    s = part.step
    shape = part.shape
    if part.axis:
        return _strip(part, rng)
    pts = []
    ring = shape.exterior
    n = max(8, int(ring.length / (s * 0.85)))
    for i in range(n):
        p = ring.interpolate(i / n, normalized=True)
        pts.append((p.x, p.y))
    inner = shape.buffer(-s * 0.45)
    minx, miny, maxx, maxy = shape.bounds
    y = miny
    row = 0
    while y <= maxy:
        x = minx + (s / 2 if row % 2 else 0)
        while x <= maxx:
            jx, jy = rng.uniform(-0.32, 0.32, 2) * s
            q = Point(x + jx, y + jy)
            if not inner.is_empty and inner.contains(q):
                pts.append((q.x, q.y))
            x += s
        y += s * 0.87
        row += 1
    pts = np.array(pts)
    tri = Delaunay(pts)
    out = []
    for simplex in tri.simplices:
        t = pts[simplex]
        c = t.mean(axis=0)
        if not shape.contains(Point(c)):
            continue
        poly = Polygon(t)
        if poly.area < 0.05:
            continue
        clipped = poly.intersection(shape)
        if clipped.is_empty or clipped.geom_type != "Polygon":
            clipped = poly
        out.append((t, list(clipped.exterior.coords)[:-1]))
    return out


def _strip(part, rng):
    """원통 막대: 축을 따라 마디마다 단면 4점 → 길쭉한 면들이 띠처럼 이어진다."""
    (x0, y0), (x1, y1), r0, r1 = part.axis
    L = math.hypot(x1 - x0, y1 - y0)
    ux, uy = (x1 - x0) / L, (y1 - y0) / L
    nx, ny = -uy, ux
    n = max(2, round(L / part.step))
    pts = []
    for i in range(n + 1):
        t = i / n
        cx, cy = x0 + (x1 - x0) * t, y0 + (y1 - y0) * t
        r = (r0 + (r1 - r0) * t) * 0.999
        shift = (rng.uniform(-0.25, 0.25) * part.step) if 0 < i < n else 0
        for k in (-1.0, -0.3, 0.38, 1.0):
            pts.append((cx + nx * r * k + ux * shift * (abs(k) < 1),
                        cy + ny * r * k + uy * shift * (abs(k) < 1)))
    ring = part.shape.exterior  # 둥근 끝
    m = max(8, int(ring.length / (part.step * 0.6)))
    seg = LineString([(x0, y0), (x1, y1)])
    for i in range(m):
        q = ring.interpolate(i / m, normalized=True)
        t = seg.project(q, normalized=True)
        if t <= 0.001 or t >= 0.999:
            pts.append((q.x, q.y))
    pts = np.array(pts)
    out = []
    for simplex in Delaunay(pts).simplices:
        t = pts[simplex]
        if part.shape.buffer(0.05).contains(Point(t.mean(axis=0))) and Polygon(t).area > 0.05:
            out.append((t, [tuple(v) for v in t]))
    return out


def shade(part, tri, rng):
    p3 = np.array([[x, y, part.height(x, y)] for x, y in tri])
    nrm = np.cross(p3[1] - p3[0], p3[2] - p3[0])
    if nrm[2] < 0:
        nrm = -nrm
    nrm /= np.linalg.norm(nrm) + 1e-9
    diff = max(float(nrm @ LIGHT), 0.0)
    spec = max(float(nrm @ HALF), 0.0) ** 24
    k = part.amb + (1 - part.amb) * diff * 1.05
    k += rng.uniform(-part.jitter, part.jitter)
    c = part.color * k + 255 * part.gloss * spec
    return tuple(int(max(0, min(255, v))) for v in c)


def render_parts(parts):
    """→ [(색, [다각형 좌표들])] 그리기 순서대로. 같은 부품 안에서 같은 색끼리 묶는다."""
    layers = []
    for part in parts:
        rng = np.random.default_rng(part.seed + 99)
        groups = {}
        for tri, poly in triangulate(part):
            col = shade(part, tri, rng)
            col = tuple((v // 3) * 3 for v in col)  # 경로 수를 줄이려 살짝 양자화
            groups.setdefault(col, []).append(poly)
        for col, polys in groups.items():
            layers.append((col, polys))
    return layers


# ---------------- 출력: VectorDrawable ----------------

def to_vector(layers, size, path, alpha=None, seam=0.45, mono=False):
    lines = ['<?xml version="1.0" encoding="utf-8"?>',
             '<!-- tools/lowpoly/gen.py 로 생성한 로우 폴리 일러스트. 직접 고치지 말고 생성기를 수정할 것. -->',
             '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
             f'    android:width="{size}dp" android:height="{size}dp"',
             f'    android:viewportWidth="{size}" android:viewportHeight="{size}"' +
             (f'\n    android:alpha="{alpha}"' if alpha else '') + '>']
    for col, polys in layers:
        d = " ".join("M" + " L".join(f"{x:.2f},{y:.2f}" for x, y in p) + " Z" for p in polys)
        if mono:  # 테마 아이콘: 흰색 + 밝기를 알파로
            a = sum(col) / (3 * 255)
            hexc = "#FFFFFF"
            extra = f' android:fillAlpha="{0.45 + 0.55 * a:.2f}"'
        else:
            hexc = "#%02X%02X%02X" % col
            extra = ""
        # 이웃 면 사이 안티앨리어싱 틈을 같은 색 선으로 메운다
        stroke = "" if mono else (f' android:strokeColor="{hexc}" android:strokeWidth="{seam}"'
                                  ' android:strokeLineJoin="round"')
        lines.append(f'    <path android:fillColor="{hexc}"{extra}{stroke}\n        android:pathData="{d}" />')
    lines.append("</vector>")
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")


# ---------------- 출력: PNG ----------------

def to_png(layers, size, px, path=None, bg=None, ss=4, offset=(0, 0), scale=1.0, base=None):
    W = px * ss
    img = base if base is not None else Image.new("RGBA", (W, W), bg or (0, 0, 0, 0))
    dr = ImageDraw.Draw(img)
    k = W / size * scale
    ox, oy = offset[0] * ss, offset[1] * ss
    for col, polys in layers:
        for p in polys:
            pts = [(ox + x * k, oy + y * k) for x, y in p]
            dr.polygon(pts, fill=col + (255,), outline=col + (255,), width=max(1, int(0.45 * k)))
    if base is not None:
        return img
    out = img.resize((px, px), Image.LANCZOS)
    if path:
        out.save(path)
    return out


def disconnected(layers):
    """미연결: 회색으로 빼고 밝게 (원래처럼 루트 알파 0.5 를 함께 씀)."""
    res = []
    for col, polys in layers:
        g = 0.30 * col[0] + 0.59 * col[1] + 0.11 * col[2]
        g = 150 + (g / 255) * 95
        res.append(((int(g),) * 3, polys))
    return res


# ---------------- 색 ----------------

WHITE = hexrgb("#F7F8FA")
TIP = hexrgb("#E2E4E9")
MESH = hexrgb("#5E626B")    # 그릴/센서: 검정이 아니라 진회색 (작게 보여도 '눈'처럼 튀지 않게)
SILVER = hexrgb("#B4B8C1")
LED = hexrgb("#30D158")
ALU = hexrgb("#D6D9DF")
CUSH = hexrgb("#ECEDF1")


def detail(shape, color, seed, step=3.2, amb=0.78):
    return Part(shape, lambda x, y: 1.0 + 0.3 * math.sin(x * 0.9 + y * 1.3), color, step=step,
                seed=seed, gloss=0.2, amb=amb, jitter=0.05)


def body(shape, seed, step=8.0, edge=12.0, bulge=6.0, color=WHITE, amb=0.5, gloss=0.35):
    return Part(shape, h_pillow(shape, edge, bulge), color, step=step, seed=seed, amb=amb, gloss=gloss, jitter=0.025)


def rod(p0, p1, r0, r1, seed, color=WHITE, step=6.0):
    return Part(capsule(p0, p1, r0, r1), h_rod(p0, p1, r0, r1), color, step=step, seed=seed,
                amb=0.5, jitter=0.02, axis=(p0, p1, r0, r1))


# ---------------- 모델 (96x96, 왼쪽 유닛 = 안쪽에서 본 모습) ----------------

def airpod_left(seed=1):
    # 머리와 스피커 노즐은 한 덩어리 (달걀 모양 머리에서 귀 쪽으로 비스듬히 튀어나온 주둥이)
    head = unary_union([ellipse(34, 30, 21, 20, -10), ellipse(55, 40, 9.5, 7.5, -32)]).convex_hull
    return [
        rod((34, 44), (28, 86), 7.2, 6.0, seed),
        body(head, seed + 1, step=8.5, edge=14, bulge=7),
        detail(ellipse(59.5, 43.0, 4.6, 3.0, -32), MESH, seed + 3),           # 스피커 그릴
        detail(ellipse(24, 23, 1.9, 1.9), MESH, seed + 4, step=1.6),          # 근접 센서
        detail(ellipse(28.2, 85.6, 5.0, 2.4, 8), SILVER, seed + 5, step=2.4),  # 마이크 캡
    ]


def airpodpro_left(seed=11):
    head = unary_union([ellipse(34, 30, 23, 19, -8), ellipse(53, 35, 9, 9)]).convex_hull
    tip = ellipse(61.5, 36, 10, 10.5, -15)
    return [
        rod((34, 42), (31, 77), 6.8, 6.2, seed),
        body(head, seed + 1, step=8.5, edge=14, bulge=7),
        Part(tip, h_dome(61.5, 36, 10, 10.5, 1.1), TIP, step=5.5, seed=seed + 2, gloss=0.12, amb=0.5, jitter=0.03),
        detail(ellipse(66, 37, 3.6, 4.2, -15), hexrgb("#8C9099"), seed + 3, step=2.4),  # 팁 안쪽 망
        detail(ellipse(21, 20, 4.2, 1.5, -35), MESH, seed + 4, step=1.8),  # 상단 벤트
        detail(capsule((31.6, 70.5), (31.3, 75), 1.8), MESH, seed + 6, step=1.6),  # 스템 마이크
    ]


def airpod_front(seed=21):
    head = ellipse(48, 32, 21, 21)
    return [
        rod((48, 50), (48, 88), 7.0, 6.2, seed),
        body(head, seed + 1, step=8.5, edge=16, bulge=6),
        detail(ellipse(48, 27, 6.0, 4.2), MESH, seed + 2, step=2.4),
        detail(ellipse(48, 87.5, 5.2, 2.3), SILVER, seed + 3, step=2.2),
    ]


def airpodpro_front(seed=31):
    head = ellipse(46, 34, 24, 20)
    tip = ellipse(62, 40, 11.5, 11.5)
    return [
        rod((46, 50), (46, 82), 6.8, 6.2, seed),
        body(head, seed + 1, step=8.5, edge=15, bulge=6),
        Part(tip, h_dome(62, 40, 11.5, 11.5, 1.1), TIP, step=5.5, seed=seed + 2, gloss=0.12, amb=0.5, jitter=0.03),
        detail(ellipse(62, 40, 4.4, 4.4), hexrgb("#8C9099"), seed + 3, step=2.2),
        detail(capsule((46, 76), (46, 80.5), 1.8), MESH, seed + 4, step=1.6),
    ]


def case_parts(x0, y0, x1, y1, r, seam, led_y, seed, gap=1.2):
    full = rrect(x0, y0, x1, y1, r)
    lid = full.intersection(box(x0 - 1, y0 - 1, x1 + 1, seam - gap / 2))
    bod = full.intersection(box(x0 - 1, seam + gap / 2, x1 + 1, y1 + 1))
    hf = h_pillow(full, edge=r * 0.95, bulge=6.0)
    return [
        Part(bod, hf, WHITE, step=10.0, seed=seed, amb=0.5, jitter=0.02),
        Part(lid, hf, WHITE, step=8.5, seed=seed + 1, amb=0.5, jitter=0.02),
        detail(ellipse((x0 + x1) / 2, led_y, 2.0, 2.0), LED, seed + 2, step=1.4, amb=0.9),
    ]


def airpod_case(seed=41):   # AirPods 케이스: 세로로 긴 모양
    return case_parts(20, 12, 76, 86, 15, 30, 46, seed)


def airpodpro_case(seed=51):  # Pro 케이스: 가로로 넓은 모양
    parts = case_parts(6, 20, 90, 80, 17, 36, 54, seed)
    parts.append(detail(rrect(86.5, 64, 91.5, 72, 2.2), SILVER, seed + 5, step=2.0))  # 스트랩 고리
    return parts


def airpods_max(seed=61):
    canopy = ellipse(48, 52, 38, 40).difference(ellipse(48, 52, 31, 33)).intersection(box(0, 0, 96, 46))
    cup_l = rrect(6, 52, 28, 90, 9)
    cup_r = rrect(68, 52, 90, 90, 9)
    cush_l = rrect(26, 55, 33, 87, 3.4)
    cush_r = rrect(63, 55, 70, 87, 3.4)
    return [
        Part(canopy, h_pillow(canopy, 3.0), CUSH, step=4.5, seed=seed, gloss=0.1, amb=0.55),
        rod((14.5, 40), (16, 54), 2.0, 2.0, seed + 1, SILVER, step=3.5),
        rod((81.5, 40), (80, 54), 2.0, 2.0, seed + 2, SILVER, step=3.5),
        Part(cush_l, h_pillow(cush_l, 3.0), CUSH, step=4.0, seed=seed + 3, gloss=0.05, amb=0.55),
        Part(cush_r, h_pillow(cush_r, 3.0), CUSH, step=4.0, seed=seed + 4, gloss=0.05, amb=0.55),
        body(cup_l, seed + 5, step=7.0, edge=8.0, bulge=3.0, color=ALU),
        body(cup_r, seed + 6, step=7.0, edge=8.0, bulge=3.0, color=ALU),
        detail(ellipse(13, 50.5, 2.6, 1.8), SILVER, seed + 7, step=1.6),  # 디지털 크라운
    ]


def mirror(parts, size=96):
    res = []
    for p in parts:
        ax = None
        if p.axis:
            (a0, a1), (b0, b1), r0, r1 = p.axis
            ax = ((size - a0, a1), (size - b0, b1), r0, r1)
        q = Part(affinity.scale(p.shape, -1, 1, origin=(size / 2, 0)),
                 (lambda f: (lambda x, y: f(size - x, y)))(p.height),
                 p.color, p.step, p.seed + 500, p.gloss, p.amb, p.jitter, ax)
        res.append(q)
    return res


# ---------------- 런처 / 로고용 구성: 뚜껑 열린 케이스 + 유닛 두 개 ----------------

def pod_pair(seed=71):
    """108 뷰포트: 에어팟 한 쌍 (스피커가 서로 마주 봄). 안전 영역(가운데 66) 안에 들어가게 축소·배치."""
    def place(parts, dx, dy, k):
        res = []
        for p in parts:
            sh = affinity.translate(affinity.scale(p.shape, k, k, origin=(0, 0)), dx, dy)
            h = (lambda f: (lambda x, y: f((x - dx) / k, (y - dy) / k) * k))(p.height)
            ax = None
            if p.axis:
                (a0, a1), (b0, b1), r0, r1 = p.axis
                ax = ((a0 * k + dx, a1 * k + dy), (b0 * k + dx, b1 * k + dy), r0 * k, r1 * k)
            res.append(Part(sh, h, p.color, p.step * k * 1.15, p.seed, p.gloss, p.amb, p.jitter, ax))
        return res
    k = 0.62
    left = place(airpod_left(seed), 11, 26, k)            # 왼쪽 유닛: 스피커가 오른쪽(가운데)을 향함
    right = place(mirror(airpod_left(seed + 40)), 37, 31, k)  # 오른쪽 유닛: 살짝 아래로 엇갈리게
    return left + right


def blue_bg(seed=81):
    sq = box(0, 0, 108, 108)
    hf = lambda x, y: 10 * math.sin(x * 0.07 + 0.4) * math.cos(y * 0.06) + 0.12 * (108 - y)
    return [Part(sq, hf, hexrgb("#0A84FF"), step=13, seed=seed, gloss=0.08, amb=0.72, jitter=0.05)]


# ---------------- 실행 ----------------

def main():
    os.makedirs(PREVIEW, exist_ok=True)
    dr = os.path.join(RES, "drawable")
    os.makedirs(dr, exist_ok=True)

    models = {
        "pod_left": airpod_left(),
        "pod_right": mirror(airpod_left(101)),
        "pod_case": airpod_case(),
        "podpro_left": airpodpro_left(),
        "podpro_right": mirror(airpodpro_left(111)),
        "podpro_case": airpodpro_case(),
        "pod": airpod_front(),
        "podpro": airpodpro_front(),
        "podmax": airpods_max(),
    }
    sheet = Image.new("RGBA", (9 * 200, 2 * 200), (36, 36, 40, 255))
    for i, (name, parts) in enumerate(models.items()):
        layers = render_parts(parts)
        to_vector(layers, 96, os.path.join(dr, name + ".xml"))
        dl = disconnected(layers)
        to_vector(dl, 96, os.path.join(dr, name + "_disconnected.xml"), alpha="0.5")
        sheet.alpha_composite(to_png(layers, 96, 200), (i * 200, 0))
        d = to_png(dl, 96, 200)
        d.putalpha(d.getchannel("A").point(lambda a: a // 2))
        sheet.alpha_composite(d, (i * 200, 200))
        print(name, sum(len(p) for _, p in layers), "faces")
    sheet.save(os.path.join(PREVIEW, "illustrations.png"))

    # 런처 아이콘 (적응형): 배경/전경/테마용 단색
    fg = render_parts(pod_pair())
    bg = render_parts(blue_bg())
    to_vector(fg, 108, os.path.join(dr, "ic_launcher_foreground.xml"))
    to_vector(bg, 108, os.path.join(dr, "ic_launcher_background.xml"), seam=0.6)
    to_vector(fg, 108, os.path.join(dr, "ic_launcher_monochrome.xml"), mono=True)

    # 레거시 런처 PNG (API 26 미만용이지만 minSdk 26 — 일부 런처가 그래도 씀)
    for dens, px in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)):
        d = os.path.join(RES, "mipmap-" + dens)
        os.makedirs(d, exist_ok=True)
        ss = 4
        W = px * ss
        full = Image.new("RGBA", (W, W), (0, 0, 0, 0))
        to_png(bg, 108, px, base=full, ss=ss)
        to_png(fg, 108, px, base=full, ss=ss)
        # 가장자리 18dp 씩 잘라 보이는 영역만 (적응형 아이콘과 같은 비율)
        crop = full.crop((int(W * 18 / 108), int(W * 18 / 108), int(W * 90 / 108), int(W * 90 / 108))).resize((W, W), Image.LANCZOS)
        for name, mask_fn in (("ic_launcher", lambda m: m.rounded_rectangle((0, 0, W - 1, W - 1), radius=W * 0.22, fill=255)),
                              ("ic_launcher_round", lambda m: m.ellipse((0, 0, W - 1, W - 1), fill=255))):
            mask = Image.new("L", (W, W), 0)
            mask_fn(ImageDraw.Draw(mask))
            icon = Image.new("RGBA", (W, W), (0, 0, 0, 0))
            icon.paste(crop, (0, 0), mask)
            icon.resize((px, px), Image.LANCZOS).save(os.path.join(d, name + ".png"))
    Image.open(os.path.join(RES, "mipmap-xxxhdpi", "ic_launcher.png")).save(os.path.join(PREVIEW, "launcher.png"))

    # 인트로 아이콘 512 (투명 배경)
    big = to_png(fg, 108, 1024)
    obj = big.crop(big.getbbox())
    k = 470 / max(obj.size)
    obj = obj.resize((int(obj.width * k), int(obj.height * k)), Image.LANCZOS)
    sq = Image.new("RGBA", (512, 512), (0, 0, 0, 0))
    sq.alpha_composite(obj, ((512 - obj.width) // 2, (512 - obj.height) // 2))
    sq.save(os.path.join(dr, "icon.png"))

    # 로고 1536x512: 아이콘 + 앱 이름
    logo = Image.new("RGBA", (1536, 512), (0, 0, 0, 0))
    o2 = obj.resize((int(obj.width * 440 / obj.height), 440), Image.LANCZOS)
    logo.alpha_composite(o2, (60, 36))
    font = ImageFont.truetype("/System/Library/Fonts/AppleSDGothicNeo.ttc", 92, index=8)
    text = "갤럭시도 에어팟을\n쓰고싶어"
    t = ImageDraw.Draw(logo)
    tb = t.multiline_textbbox((0, 0), text, font=font, spacing=26)
    x = 60 + o2.width + 70
    t.multiline_text((x, (512 - (tb[3] - tb[1])) // 2 - tb[1]), text, font=font, fill=(28, 28, 32, 255), spacing=26)
    logo.save(os.path.join(dr, "logo.png"))

    prev = Image.new("RGBA", (1536 + 512, 512), (255, 255, 255, 255))
    prev.alpha_composite(logo, (0, 0))
    prev.alpha_composite(sq, (1536, 0))
    prev.save(os.path.join(PREVIEW, "logo_icon.png"))

    # 알림 아이콘 (24dp 단색): 로우 폴리 실루엣 — 모서리를 깎은 케이스 + 뚜껑 이음선 + LED
    with open(os.path.join(dr, "ic_notification.xml"), "w", encoding="utf-8") as f:
        f.write('''<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24"
    android:tint="#FFFFFF">
    <!-- 로우 폴리 케이스 실루엣: 둥근 모서리 대신 깎은 면, 이음선과 LED 는 구멍 -->
    <path
        android:fillColor="#FFFFFF"
        android:fillType="evenOdd"
        android:pathData="M8,3 L16,3 L19.4,4.4 L21,8 L21,16.5 L19.4,19.8 L16,21 L8,21 L4.6,19.8 L3,16.5 L3,8 L4.6,4.4 Z M3,9.1 L21,9.1 L21,10.3 L3,10.3 Z M12,13.2 L13.2,14.4 L12,15.6 L10.8,14.4 Z" />
</vector>
''')


if __name__ == "__main__":
    main()
