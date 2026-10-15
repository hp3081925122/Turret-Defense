# 炮塔方块模型与贴图生成器：输出塔头完整模型、六向支座、可旋转炮管、底座与基地核心
# 用法：python gen_turrets.py <assets/turretdefense 目录> [预览输出目录]
import json
import math
import random
import sys
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(sys.argv[1])
PREVIEW = Path(sys.argv[2]) if len(sys.argv) > 2 else None
NS = 'turretdefense'
TEX_DIR = ROOT / 'textures/block/turret'
MODEL_DIR = ROOT / 'models/block'
FACES = ('down', 'up', 'north', 'south', 'west', 'east')
NORMALS = {'down': (0, -1, 0), 'up': (0, 1, 0), 'north': (0, 0, -1), 'south': (0, 0, 1), 'west': (-1, 0, 0), 'east': (1, 0, 0)}

# 等级材质主色：机身、饰边、炮管
TIER_METAL = ['#8b919a', '#6f7d8c', '#8a8176', '#4f5661', '#302b36']
TIER_TRIM = ['#5b6069', '#c26c3e', '#e3b34c', '#3fc4be', '#f0c64a']
TIER_BARREL = ['#6c717a', '#5a6474', '#6b645c', '#3e444e', '#26222c']

# 各炮塔发光色
KIND_GLOW = {
    'pulse': '#34d6f0', 'particle': '#b45cf0', 'grenade': '#ff9a2e', 'laser': '#ff4040', 'arc': '#8fd8ff',
    'gravity': '#8e5cff', 'railgun': '#3f7dff', 'phase': '#2fe3b0', 'sniper': '#6aff6a', 'arbiter': '#fff0a8',
    'fusion': '#ffb02e', 'acid': '#9cff3a', 'cryo': '#a8f0ff', 'emp': '#ffe23a', 'graviton': '#d83cff',
}

# 基地核心各级能量色
CORE_GLOW = ['#34d6f0', '#4cff8a', '#ffd23a', '#b45cf0', '#ffffff']


def rgb(h):
    h = h.lstrip('#')
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def mul(c, k):
    return tuple(max(0, min(255, int(round(v * k)))) for v in c)


def mix(a, b, t):
    return tuple(int(round(a[i] * (1 - t) + b[i] * t)) for i in range(3))


# ---------------- 贴图 ----------------

def img16():
    return Image.new('RGBA', (16, 16), (0, 0, 0, 255))


def tex_metal(base, seed):
    # 拉丝金属：行间轻微明暗 + 随机颗粒 + 两颗铆钉
    r = random.Random(seed)
    c = rgb(base)
    im = img16()
    rows = [r.uniform(0.96, 1.04) for _ in range(16)]
    for y in range(16):
        for x in range(16):
            im.putpixel((x, y), mul(c, rows[y] * r.uniform(0.95, 1.04)) + (255,))
    for x, y in ((3, 3), (12, 11)):
        im.putpixel((x, y), mul(c, 1.25) + (255,))
        im.putpixel((x + 1, y + 1), mul(c, 0.7) + (255,))
    return im


def tex_trim(base, seed):
    # 饰边：斜向高光渐变 + 颗粒
    r = random.Random(seed)
    c = rgb(base)
    im = img16()
    for y in range(16):
        for x in range(16):
            k = 0.9 + 0.18 * (((x + y) % 8) < 2) + r.uniform(-0.04, 0.04)
            im.putpixel((x, y), mul(c, k) + (255,))
    return im


def tex_barrel(base, seed):
    # 炮管：每 4 行一道环形箍
    r = random.Random(seed)
    c = rgb(base)
    im = img16()
    for y in range(16):
        for x in range(16):
            k = r.uniform(0.94, 1.03)
            if y % 4 == 0:
                k = 0.68
            elif y % 4 == 1:
                k = 1.18
            im.putpixel((x, y), mul(c, k) + (255,))
    return im


def tex_glow(base, seed):
    # 发光件：亮底 + 白色亮斑 + 竖向能量纹
    r = random.Random(seed)
    c = rgb(base)
    im = img16()
    for y in range(16):
        for x in range(16):
            k = r.uniform(0.86, 1.08)
            col = mul(c, k)
            if (x + y * 3) % 7 == 0:
                col = mix(col, (255, 255, 255), 0.45)
            if r.random() < 0.06:
                col = mix(col, (255, 255, 255), 0.75)
            im.putpixel((x, y), col + (255,))
    return im


def tex_flat(base, seed, amp=0.05):
    # 通用平面材质
    r = random.Random(seed)
    c = rgb(base)
    im = img16()
    for y in range(16):
        for x in range(16):
            im.putpixel((x, y), mul(c, r.uniform(1 - amp, 1 + amp)) + (255,))
    return im


def tex_turret_base(t, seed):
    # 炮塔底座：外框 + 饰边 + 中央安装座 + 等级细节
    r = random.Random(seed)
    metal, trim = rgb(TIER_METAL[t - 1]), rgb(TIER_TRIM[t - 1])
    dark = rgb('#1c1d22')
    im = img16()
    for y in range(16):
        for x in range(16):
            e = min(x, y, 15 - x, 15 - y)
            col = mul(metal, r.uniform(0.95, 1.04))
            if e == 0:
                col = mul(metal, 0.55)
            elif e == 1:
                col = mul(trim, 1.05 if x + y < 15 else 0.85)
            cx, cy = abs(x - 7.5), abs(y - 7.5)
            if max(cx, cy) <= 3.5:
                col = mul(metal, 0.8)
            if max(cx, cy) <= 2.5:
                col = dark
            if max(cx, cy) <= 1.5:
                col = mul(trim, 0.9)
            if max(cx, cy) <= 0.5:
                col = mix(trim, (255, 255, 255), 0.5)
            im.putpixel((x, y), col + (255,))
    if t >= 2:
        for x, y in ((3, 3), (12, 3), (3, 12), (12, 12)):
            im.putpixel((x, y), mul(trim, 1.25) + (255,))
    if t >= 3:
        for i in range(4, 12):
            if i < 4 or i > 11:
                continue
            for x, y in ((i, 2), (2, i), (i, 13), (13, i)):
                if abs(x - 7.5) > 3.5 or abs(y - 7.5) > 3.5:
                    im.putpixel((x, y), mul(metal, 0.72) + (255,))
    if t >= 4:
        g = rgb(CORE_GLOW[t - 1])
        for i in (6, 9):
            for x, y in ((i, 1), (1, i), (i, 14), (14, i)):
                im.putpixel((x, y), g + (255,))
    if t >= 5:
        for x, y in ((2, 2), (13, 2), (2, 13), (13, 13)):
            im.putpixel((x, y), (255, 255, 255, 255))
    return im


def tex_core_glow(t, seed):
    # 核心能量：中心亮、向外渐暗的网格纹
    r = random.Random(seed)
    c = rgb(CORE_GLOW[t - 1])
    im = img16()
    for y in range(16):
        for x in range(16):
            d = max(abs(x - 7.5), abs(y - 7.5)) / 7.5
            k = 1.1 - 0.45 * d + r.uniform(-0.05, 0.05)
            col = mul(c, k)
            if x % 4 == 0 or y % 4 == 0:
                col = mul(col, 0.75)
            if d < 0.3:
                col = mix(col, (255, 255, 255), 0.5)
            im.putpixel((x, y), col + (255,))
    return im


# ---------------- 模型几何 ----------------

class Model:
    # 元素列表：(起点, 终点, 材质, 单面覆盖)
    def __init__(self):
        self.els = []

    def box(self, a, b, mat, faces=None):
        self.els.append(([float(v) for v in a], [float(v) for v in b], mat, dict(faces or {})))

    def cen(self, hx, y1, y2, hz, mat, faces=None, cx=8.0, cz=8.0):
        self.box((cx - hx, y1, cz - hz), (cx + hx, y2, cz + hz), mat, faces)

    def sym4(self, a, b, mat, faces=None):
        # 绕 Y 轴旋转 0/90/180/270 度各放一份（仅允许 up/down 单面覆盖）
        pts = [(a[0], a[2]), (b[0], b[2])]
        for k in range(4):
            xs = [p[0] for p in pts]
            zs = [p[1] for p in pts]
            self.box((min(xs), a[1], min(zs)), (max(xs), b[1], max(zs)), mat, faces)
            pts = [(16 - z, x) for x, z in pts]

    def ring(self, y, r, h, mat):
        # 由四根风车式拼接横杆组成的方环，无重叠
        self.sym4((8 - r, y, 8 - r), (8 + r - h, y + h, 8 - r + h), mat)


# 六向支座的坐标变换：安装面从 down 转到目标朝向
ROT = {
    'down': lambda x, y, z: (x, y, z),
    'up': lambda x, y, z: (x, 16 - y, 16 - z),
    'north': lambda x, y, z: (x, 16 - z, y),
    'south': lambda x, y, z: (x, z, 16 - y),
    'west': lambda x, y, z: (y, 16 - x, z),
    'east': lambda x, y, z: (16 - y, x, z),
}


def rot_dir(facing, d):
    # 法线方向同样做线性部分变换
    f = ROT[facing]
    o = f(0, 0, 0)
    p = f(*d)
    v = tuple(int(round(p[i] - o[i])) for i in range(3))
    for k, n in NORMALS.items():
        if n == v:
            return k
    raise ValueError(v)


def rotate_els(els, facing):
    out = []
    for a, b, mat, faces in els:
        p, q = ROT[facing](*a), ROT[facing](*b)
        na = [min(p[i], q[i]) for i in range(3)]
        nb = [max(p[i], q[i]) for i in range(3)]
        nf = {rot_dir(facing, NORMALS[k]): v for k, v in faces.items()}
        out.append((na, nb, mat, nf))
    return out


def span(lo, hi):
    # 把面映射到 0..16 的 UV 区间内，过长时整体压缩
    w = hi - lo
    if w >= 16:
        return 0.0, 16.0
    s = lo % 16
    if s + w > 16:
        s = 16 - w
    return s, s + w


def face_uv(a, b, f):
    x1, y1, z1 = a
    x2, y2, z2 = b
    if f in ('up', 'down'):
        u, v = span(x1, x2), span(z1, z2)
    elif f == 'north':
        u, v = span(16 - x2, 16 - x1), span(16 - y2, 16 - y1)
    elif f == 'south':
        u, v = span(x1, x2), span(16 - y2, 16 - y1)
    elif f == 'west':
        u, v = span(z1, z2), span(16 - y2, 16 - y1)
    else:
        u, v = span(16 - z2, 16 - z1), span(16 - y2, 16 - y1)
    return [round(u[0], 3), round(v[0], 3), round(u[1], 3), round(v[1], 3)]


def els_json(els):
    out = []
    for a, b, mat, faces in els:
        for v in a + b:
            if v < -16 or v > 32:
                raise ValueError(f'坐标越界 {a} {b}')
        fj = {f: {'uv': face_uv(a, b, f), 'texture': '#' + faces.get(f, mat)} for f in FACES}
        out.append({'from': [round(v, 3) for v in a], 'to': [round(v, 3) for v in b], 'faces': fj})
    return out


def coplanar_issues(els):
    # 检查同向共面且有重叠面积的面（会产生 Z 冲突闪烁）
    issues = []
    axes = {'down': 1, 'up': 1, 'north': 2, 'south': 2, 'west': 0, 'east': 0}
    for f, ax in axes.items():
        hi = f in ('up', 'south', 'east')
        planes = []
        for i, (a, b, _, _) in enumerate(els):
            pos = b[ax] if hi else a[ax]
            o = [k for k in range(3) if k != ax]
            planes.append((pos, (a[o[0]], b[o[0]], a[o[1]], b[o[1]]), i))
        for i in range(len(planes)):
            for j in range(i + 1, len(planes)):
                p, r1, ii = planes[i]
                q, r2, jj = planes[j]
                if abs(p - q) > 1e-6 or (f == 'down' and abs(p - 12) < 1e-6):
                    continue
                w = min(r1[1], r2[1]) - max(r1[0], r2[0])
                h = min(r1[3], r2[3]) - max(r1[2], r2[2])
                if w > 1e-6 and h > 1e-6:
                    issues.append((f, ii, jj))
    return issues


# ---------------- 通用支座与炮台转轴 ----------------

def support(m, t):
    # 底板、转轴颈、轴承环
    m.cen(6, 0, 2, 6, 'metal')
    m.cen(2.5, 2, 5, 2.5, 'barrel')
    m.cen(3.5, 4, 6, 3.5, 'trim')
    # T2：四角螺栓
    if t >= 2:
        m.sym4((2, 2, 2), (4, 3, 4), 'trim')
    # T3：四向加强肋
    if t >= 3:
        m.sym4((7, 2, 3), (9, 4, 5.5), 'metal')
    # T4：底板侧面发光条
    if t >= 4:
        m.sym4((5, 0.5, 1.75), (11, 1.5, 2), 'glow')
    # T5：四角立柱与灯
    if t >= 5:
        m.sym4((2, 3, 2), (4, 6.5, 4), 'metal')
        m.sym4((2.5, 6.5, 2.5), (3.5, 7, 3.5), 'glow')


def hub(m, t, east=True, west=True):
    # 炮台主体：以 (8,9,8) 为旋转中心的 6x6x6 方块
    m.cen(3, 6, 12, 3, 'metal')
    # T2：左右侧装甲
    if t >= 2:
        if west:
            m.box((4, 7, 5.5), (5, 11, 10.5), 'trim')
        if east:
            m.box((11, 7, 5.5), (12, 11, 10.5), 'trim')
    # T3：前后装甲
    if t >= 3:
        m.box((5.5, 7, 4), (10.5, 11, 5), 'trim')
        m.box((5.5, 7, 11), (10.5, 11, 12), 'trim')
    # T4：前后装甲发光条
    if t >= 4:
        m.box((6, 8, 3.75), (10, 10, 4), 'glow')
        m.box((6, 8, 12), (10, 10, 12.25), 'glow')
    # T5：左右装甲发光条
    if t >= 5:
        if west:
            m.box((3.75, 8, 6), (4, 10, 10), 'glow')
        if east:
            m.box((12, 8, 6), (12.25, 10, 10), 'glow')


# ---------------- 各炮塔炮管设计（沿 +Y，从 y=12 起） ----------------

def k_pulse(m, t):
    hub(m, t)
    top = 12 + 8 + t
    xs = [8.0] if t < 4 else [6.5, 9.5]
    for cx in xs:
        # 方形炮管，T3 起加枪口
        m.cen(1, 12, top - 1 if t >= 3 else top, 1, 'barrel', {'up': 'dark'}, cx=cx)
        if t >= 3:
            m.cen(1.5, top - 2, top, 1.5, 'trim', {'up': 'dark'}, cx=cx)
    # 加速线圈
    ys = [13.5, 16] + ([18.5] if t >= 2 else []) + ([21] if t >= 5 else [])
    for y in ys:
        if len(xs) == 2:
            m.box((4.75, y, 6.25), (11.25, y + 1, 9.75), 'glow')
        else:
            m.cen(1.75, y, y + 1, 1.75, 'glow')


def k_particle(m, t):
    hub(m, t)
    top = 12 + 8 + t
    pos = {1: [(8, 8)], 2: [(7, 8), (9, 8)], 3: [(7, 7), (9, 7), (8, 9)], 4: [(7, 7), (9, 7), (7, 9), (9, 9)], 5: [(7, 7), (9, 7), (7, 9), (9, 9)]}[t]
    # T2 起加旋转鼓
    start = 12
    if t >= 2:
        m.cen(2.5, 12, 14, 2.5, 'metal')
        start = 14
    # 细炮管簇
    for cx, cz in pos:
        m.cen(0.5, start, top, 0.5, 'barrel', {'up': 'dark'}, cx=cx, cz=cz)
    # 炮管箍
    h = 1.0 if t == 1 else 2.0
    for y in [top - 2] + ([16.5] if t >= 3 else []):
        m.cen(h, y, y + 1, h, 'trim')
    # T5：中央能量棒
    if t >= 5:
        m.cen(0.5, 14, top - 0.5, 0.5, 'glow')


def k_grenade(m, t):
    hub(m, t, east=False)
    top = 12 + 4 + t
    tubes = {1: [(8, 8, 2)], 2: [(8, 8, 2)], 3: [(8, 8, 2.25)], 4: [(6, 8, 1.75), (10, 8, 1.75)],
             5: [(6.25, 6.25, 1.5), (9.75, 6.25, 1.5), (6.25, 9.75, 1.5), (9.75, 9.75, 1.5)]}[t]
    # 发射管与管箍
    for cx, cz, h in tubes:
        m.cen(h, 12, top, h, 'barrel', {'up': 'dark'}, cx=cx, cz=cz)
        if t >= 3:
            m.cen(h + 0.25, top - 1.5, top - 0.75, h + 0.25, 'trim', cx=cx, cz=cz)
    # T2 起侧挂弹匣
    if t >= 2:
        m.box((12, 6.5, 6.5), (14.5, 11, 9.5), 'metal')
        m.box((12.25, 9, 6.25), (14.75, 9.75, 9.75), 'glow')
    # T5：发射架底板
    if t >= 5:
        m.cen(3.75, 12, 13, 3.75, 'trim')


def k_laser(m, t):
    hub(m, t)
    top = 12 + 8 + t
    # 发射筒与透镜
    m.cen(1.5, 12, top - 1, 1.5, 'barrel')
    m.cen(1, top - 1, top, 1, 'glow')
    # 聚焦环
    for y in [14] + ([17] if t >= 2 else []) + ([19.5] if t >= 4 else []):
        m.cen(2, y, y + 1, 2, 'trim')
    # T2 起侧面能量窗
    if t >= 2:
        m.box((6.25, 13, 7.5), (6.5, top - 2.5, 8.5), 'glow')
        m.box((9.5, 13, 7.5), (9.75, top - 2.5, 8.5), 'glow')
    # T3 起聚焦叉，T5 变四叉
    if t >= 5:
        m.sym4((7.5, top - 4, 5), (8.5, top + 0.5, 6), 'trim')
    elif t >= 3:
        m.box((7.5, top - 4, 5), (8.5, top + 0.5, 6), 'trim')
        m.box((7.5, top - 4, 10), (8.5, top + 0.5, 11), 'trim')


def k_arc(m, t):
    hub(m, t)
    top = 12 + 5 + t
    # 铜导杆与放电盘
    m.cen(0.75, 12, top - 2, 0.75, 'copper')
    n = 1 + t // 2
    for i in range(n):
        y = 13.5 + i * 2
        h = 2.5 - i * 0.4
        m.cen(h, y, y + 0.5, h, 'copper')
    # 放电球
    hb = 1.25 + 0.25 * t
    m.cen(hb, top - 1 - hb, top - 1 + hb, hb, 'glow')
    # T4 两侧放电叉，T5 四向
    if t >= 5:
        m.sym4((7.5, 12, 4.5), (8.5, 16, 5.5), 'copper')
        m.sym4((7.5, 16, 4.5), (8.5, 17, 5.5), 'glow')
    elif t >= 4:
        m.box((7.5, 12, 4.5), (8.5, 16, 5.5), 'copper')
        m.box((7.5, 12, 10.5), (8.5, 16, 11.5), 'copper')
        m.box((7.5, 16, 4.5), (8.5, 17, 5.5), 'glow')
        m.box((7.5, 16, 10.5), (8.5, 17, 11.5), 'glow')


def k_gravity(m, t):
    hub(m, t, east=t < 5, west=t < 5)
    top = 12 + 3 + t
    # 粗短迫击管
    m.cen(2.5, 12, top - 1 if t >= 4 else top, 2.5, 'barrel', {'up': 'dark'})
    # 引力环
    for y in [13.5] + ([top - 3] if t >= 3 else []):
        m.cen(2.75, y, y + 1, 2.75, 'glow')
    # T2 底座箍，T4 炮口罩
    if t >= 2:
        m.cen(3, 12, 13, 3, 'trim')
    if t >= 4:
        m.cen(3, top - 1, top, 3, 'trim', {'up': 'dark'})
    # T5：两侧配重
    if t >= 5:
        m.box((2.5, 7, 6.5), (4, 11, 9.5), 'metal')
        m.box((12, 7, 6.5), (13.5, 11, 9.5), 'metal')


def k_railgun(m, t):
    hub(m, t, east=t < 3, west=t < 3)
    top = 12 + 8 + t
    # 双导轨与中间能量通道
    m.box((5.5, 12, 7), (7, top, 9), 'barrel')
    m.box((9, 12, 7), (10.5, top, 9), 'barrel')
    m.box((7.25, 12, 7.75), (8.75, top - 1, 8.25), 'glow')
    # 导轨夹箍
    for y in [13, 16] + ([19] if t >= 3 else []) + ([22] if t >= 5 else []):
        m.box((5, y, 6.5), (11, y + 1, 7), 'trim')
        m.box((5, y, 9), (11, y + 1, 9.5), 'trim')
    # T2 导轨尖端
    if t >= 2:
        m.box((5.25, top - 1, 6.75), (7.25, top + 0.5, 9.25), 'trim')
        m.box((8.75, top - 1, 6.75), (10.75, top + 0.5, 9.25), 'trim')
    # T3 起两侧电容
    if t >= 3:
        m.box((3, 7, 6), (5, 11, 10), 'metal')
        m.box((11, 7, 6), (13, 11, 10), 'metal')
        m.box((2.75, 8, 7), (3, 10, 9), 'glow')
        m.box((13, 8, 7), (13.25, 10, 9), 'glow')


def k_phase(m, t):
    hub(m, t)
    n = 2 + (t - 1) // 2
    last = 12 + (n - 1) * 3 + 2
    # 分段炮管 + 贯穿能量芯
    m.cen(0.5, 12, last + 1, 0.5, 'glow')
    for i in range(n):
        y = 12 + i * 3
        m.cen(1.5, y, y + 2, 1.5, 'barrel')
        # T2 起段间悬浮环
        if t >= 2 and i < n - 1:
            m.cen(2.25, y + 2.25, y + 2.75, 2.25, 'trim')
    # T4 起首段稳定翼
    if t >= 4:
        m.sym4((7.5, 12, 5), (8.5, 14, 6.5), 'trim')


def k_sniper(m, t):
    hub(m, t)
    top = 12 + 9 + t
    # 细长炮管与制退器
    m.cen(0.75, 12, top - 1.5, 0.75, 'barrel')
    m.cen(1.25, top - 2, top, 1.25, 'trim', {'up': 'dark'})
    # T2 护套
    if t >= 2:
        m.cen(1.25, 12, 15, 1.25, 'metal')
    # 瞄准镜
    sc = 4 + (1 if t >= 3 else 0)
    m.box((10, 12.5, 7.25), (11.5, 12.5 + sc, 8.75), 'metal', {'up': 'glow', 'down': 'glow'})
    m.box((9.25, 13, 7.75), (10, 14, 8.25), 'trim')
    # T4 稳定翼
    if t >= 4:
        m.box((7.75, 12, 5), (8.25, 14, 6.75), 'trim')
        m.box((7.75, 12, 9.25), (8.25, 14, 11), 'trim')
    # T5 炮管能量线
    if t >= 5:
        m.box((7.75, 15, 7.0), (8.25, top - 2, 7.25), 'glow')


def k_arbiter(m, t):
    hub(m, t)
    top = 12 + 6 + t
    # 金色矛身与光尖
    m.cen(1, 12, top, 1, 'trim')
    m.cen(0.5, top, top + 1 + (0.5 if t >= 3 else 0), 0.5, 'glow')
    # 四向白色翼片
    fl = 2 + t * 0.5
    m.sym4((7.5, 12, 5), (8.5, 12 + fl, 7), 'white')
    # T3 起光环，T5 第二道
    if t >= 3:
        m.ring(15.5, 3.5, 0.5, 'glow')
    if t >= 5:
        m.ring(top - 3, 3.0, 0.5, 'glow')


def k_fusion(m, t):
    hub(m, t)
    top = 12 + 5 + t
    hc = 1.5 + 0.25 * (t - 1)
    # 聚变核心与支杆
    m.cen(1, 12, 15 - hc, 1, 'barrel')
    m.cen(hc, 15 - hc, 15 + hc, hc, 'glow')
    # 四向约束叉
    m.sym4((7.25, 12, 3.5), (8.75, top, 4.5), 'trim')
    if t >= 3:
        m.sym4((7.25, top - 1, 4.5), (8.75, top, 5.5), 'trim')
    # T4 核心束环
    if t >= 4:
        m.ring(14.75, hc + 0.5, 0.5, 'trim')
    # T5 斜向副叉
    if t >= 5:
        m.sym4((4.25, 12, 4.25), (5.25, top - 2, 5.25), 'metal')


def k_acid(m, t):
    hub(m, t, east=False, west=t < 4)
    top = 12 + 5 + t
    # 喷管与喷嘴
    m.cen(1, 12, top - 1.5, 1, 'barrel')
    m.cen(1.5, top - 1.5, top, 1.5, 'trim', {'up': 'dark'})

    # 酸液罐（东侧，T4 起西侧再加一个）
    def tank(x1, x2):
        m.box((x1, 6.5, 6), (x2, 11.5, 10), 'glow')
        m.box((x1 - 0.25, 11.5, 6.25), (x2 + 0.25, 12, 9.75), 'trim')
        m.box((x1 - 0.25, 6, 6.25), (x2 + 0.25, 6.5, 9.75), 'trim')
    tank(12, 14.5)
    if t >= 4:
        tank(1.5, 4)
    # T3 起输液管
    if t >= 3:
        m.box((12.75, 12, 7.5), (13.75, 13.5, 8.5), 'dark')
        m.box((9, 13.5, 7.5), (13.75, 14.5, 8.5), 'dark')
    # T5 喷嘴护片
    if t >= 5:
        m.box((7.5, top - 3, 5.5), (8.5, top - 1.5, 6.5), 'metal')
        m.box((7.5, top - 3, 9.5), (8.5, top - 1.5, 10.5), 'metal')


def k_cryo(m, t):
    hub(m, t, east=t < 3, west=t < 3)
    top = 12 + 5 + t
    # 喷管与冰晶尖
    m.cen(1.25, 12, top - 1, 1.25, 'barrel')
    m.cen(0.75, top - 1, top + 0.5, 0.75, 'glow')
    # 散冷片
    n = 2 + (t - 1) // 2
    for i in range(n):
        y = 13 + i * 1.5
        m.cen(2.5, y, y + 0.5, 2.5, 'white')
    # T3 起两侧冷却罐
    if t >= 3:
        m.box((12, 7, 6.5), (14, 11, 9.5), 'glow')
        m.box((2, 7, 6.5), (4, 11, 9.5), 'glow')
        m.box((12, 11, 7), (13.5, 11.5, 9), 'white')
        m.box((2.5, 11, 7), (4, 11.5, 9), 'white')


def k_emp(m, t):
    hub(m, t)
    s = 15 + (1 if t >= 3 else 0)
    # 支杆与线圈
    m.cen(1, 12, s, 1, 'barrel')
    if t >= 2:
        m.cen(1.5, 12.5, 13.5, 1.5, 'copper')
    # 发射盘与边缘
    hd = 3 + 0.5 * (t - 1)
    m.cen(hd, s, s + 0.75, hd, 'trim')
    if t >= 3:
        m.ring(s + 0.75, hd, 0.75, 'metal')
    # 天线与端头
    at = s + 3 + t * 0.5
    m.cen(0.4, s + 0.75, at, 0.4, 'copper')
    m.cen(0.9, at, at + 1.5, 0.9, 'glow')
    if t >= 4:
        m.cen(1, s + 1.75, s + 2.25, 1, 'glow')


def k_graviton(m, t):
    hub(m, t)
    # 支杆与暗能核心
    m.cen(1.5, 12, 14, 1.5, 'barrel')
    hc = 1.25 + 0.25 * (t >= 3) + 0.25 * (t >= 5)
    m.cen(hc, 15.5 - hc, 15.5 + hc, hc, 'glow')
    # 约束环与支撑
    rings = {1: [(14.5, 3.5)], 2: [(14.5, 3.5)], 3: [(14, 3.5), (17, 3.0)], 4: [(14, 3.5), (17, 3.0)], 5: [(13.5, 3.75), (16, 3.25), (18.5, 2.75)]}[t]
    for y, r in rings:
        m.ring(y, r, 0.75, 'trim' if t < 2 else 'metal')
    y0, r0 = rings[0]
    m.sym4((7.75, y0, 8 - r0 + 0.75), (8.25, y0 + 0.5, 6.5), 'trim')
    # T2 起环内发光，T4 起顶端稳定针
    if t >= 2:
        m.ring(y0 + 0.75, r0 - 0.25, 0.25, 'glow')
    if t >= 4:
        m.cen(0.5, 15.5 + hc, 15.5 + hc + 2, 0.5, 'metal')


KINDS = {
    'pulse': k_pulse, 'particle': k_particle, 'grenade': k_grenade, 'laser': k_laser, 'arc': k_arc,
    'gravity': k_gravity, 'railgun': k_railgun, 'phase': k_phase, 'sniper': k_sniper, 'arbiter': k_arbiter,
    'fusion': k_fusion, 'acid': k_acid, 'cryo': k_cryo, 'emp': k_emp, 'graviton': k_graviton,
}


def core_model(t):
    # 基地核心：十二条框边 + 能量核心 + 等级附件
    m = Model()
    m.cen(5, 3, 13, 5, 'core')
    for y1, y2 in ((0, 2), (14, 16)):
        for z1, z2 in ((0, 2), (14, 16)):
            m.box((0, y1, z1), (16, y2, z2), 'frame')
    for x1, x2 in ((0, 2), (14, 16)):
        for z1, z2 in ((0, 2), (14, 16)):
            m.box((x1, 2, z1), (x2, 14, z2), 'frame')
    for x1, x2 in ((0, 2), (14, 16)):
        for y1, y2 in ((0, 2), (14, 16)):
            m.box((x1, y1, 2), (x2, y2, 14), 'frame')
    # 上下盖板
    m.box((2, 0.5, 2), (14, 1.5, 14), 'plate')
    m.box((2, 14.5, 2), (14, 15.5, 14), 'plate')
    # T2 核心支柱
    if t >= 2:
        m.cen(1, 1.5, 3, 1, 'plate')
        m.cen(1, 13, 14.5, 1, 'plate')
    # T3 侧面竖撑
    if t >= 3:
        m.box((7, 2, 0.5), (9, 14, 1.5), 'frame')
        m.box((7, 2, 14.5), (9, 14, 15.5), 'frame')
        m.box((0.5, 2, 7), (1.5, 14, 9), 'frame')
        m.box((14.5, 2, 7), (15.5, 14, 9), 'frame')
    # T4 核心腰环
    if t >= 4:
        m.ring(7.5, 5.5, 1, 'frame')
    # T5 顶部能量晶体
    if t >= 5:
        m.cen(1.5, 15.5, 17.5, 1.5, 'core')
        m.cen(0.75, 17.5, 18.5, 0.75, 'core')
    return m


# ---------------- 预览渲染（软件 Z 缓冲） ----------------

def render(els, texmap, size=200, pivot_rot=None, view_scale=None):
    # 等轴视角：可见面 up/east/south，带 MC 风格面明暗
    W = H = size
    zb = np.full((H, W), -1e9)
    col = np.zeros((H, W, 3), dtype=np.uint8)
    col[:] = (58, 62, 70)
    sc = view_scale or size / 34.0
    shade = {'up': 1.0, 'down': 0.5, 'north': 0.8, 'south': 0.8, 'west': 0.6, 'east': 0.6}
    for a, b, mat, faces, rotq in els:
        for f in FACES:
            x1, y1, z1 = a
            x2, y2, z2 = b
            dx, dy, dz = x2 - x1, y2 - y1, z2 - z1
            o, U, V = {
                'up': ((x1, y2, z1), (dx, 0, 0), (0, 0, dz)),
                'down': ((x1, y1, z2), (dx, 0, 0), (0, 0, -dz)),
                'north': ((x2, y2, z1), (-dx, 0, 0), (0, -dy, 0)),
                'south': ((x1, y2, z2), (dx, 0, 0), (0, -dy, 0)),
                'west': ((x1, y2, z1), (0, 0, dz), (0, -dy, 0)),
                'east': ((x2, y2, z2), (0, 0, -dz), (0, -dy, 0)),
            }[f]
            nrm = np.array(NORMALS[f], dtype=float)
            if rotq is not None:
                nrm = rotq @ nrm
            if nrm @ np.array([1.0, 1.0, 1.0]) <= 0:
                continue
            uv = face_uv(a, b, f)
            img = texmap[faces.get(f, mat)]
            lu = math.hypot(*U)
            lv = math.hypot(*V)
            nu = max(2, int(lu * 6))
            nv = max(2, int(lv * 6))
            s = (np.arange(nu) + 0.5) / nu
            tt = (np.arange(nv) + 0.5) / nv
            S, T = np.meshgrid(s, tt)
            P = np.stack([o[i] + S * U[i] + T * V[i] for i in range(3)], -1).reshape(-1, 3)
            if rotq is not None:
                P = (P - np.array([8, 9, 8])) @ rotq.T + np.array([8, 9, 8])
            tu = np.clip((uv[0] + S * (uv[2] - uv[0])).astype(int), 0, 15).reshape(-1)
            tv = np.clip((uv[1] + T * (uv[3] - uv[1])).astype(int), 0, 15).reshape(-1)
            c = img[tv, tu] * shade[f]
            sx = (P[:, 0] - P[:, 2]) * 0.866 * sc + W / 2
            sy = ((P[:, 0] + P[:, 2]) * 0.5 - P[:, 1]) * sc + H * 0.66
            d = P[:, 0] + P[:, 1] + P[:, 2]
            ix, iy = sx.astype(int), sy.astype(int)
            ok = (ix >= 0) & (ix < W) & (iy >= 0) & (iy < H)
            for ox in (0, 1):
                for oy in (0, 1):
                    jx, jy = np.clip(ix + ox, 0, W - 1), np.clip(iy + oy, 0, H - 1)
                    order = np.argsort(d)
                    for k in (order,):
                        sel = k[ok[k]]
                        cur = zb[jy[sel], jx[sel]]
                        better = d[sel] >= cur
                        sel = sel[better]
                        zb[jy[sel], jx[sel]] = d[sel]
                        col[jy[sel], jx[sel]] = c[sel].astype(np.uint8)
    return Image.fromarray(col)


def rot_x(deg):
    r = math.radians(deg)
    return np.array([[1, 0, 0], [0, math.cos(r), -math.sin(r)], [0, math.sin(r), math.cos(r)]])


# ---------------- 主流程 ----------------

def main():
    TEX_DIR.mkdir(parents=True, exist_ok=True)
    (MODEL_DIR / 'aim').mkdir(parents=True, exist_ok=True)
    texs = {}

    def save(name, im):
        im.save(TEX_DIR / f'{name}.png')
        texs[name] = np.array(im.convert('RGB'))

    # 写出全部贴图
    for t in range(1, 6):
        save(f'metal_t{t}', tex_metal(TIER_METAL[t - 1], 10 + t))
        save(f'trim_t{t}', tex_trim(TIER_TRIM[t - 1], 20 + t))
        save(f'barrel_t{t}', tex_barrel(TIER_BARREL[t - 1], 30 + t))
        save(f'turret_base_t{t}', tex_turret_base(t, 40 + t))
        save(f'core_glow_t{t}', tex_core_glow(t, 50 + t))
    save('dark', tex_flat('#1b1c22', 1, 0.08))
    save('copper', tex_trim('#c87040', 2))
    save('white', tex_metal('#e2e4ea', 3))
    for i, (k, c) in enumerate(KIND_GLOW.items()):
        save(f'glow_{k}', tex_glow(c, 60 + i))

    issues_total = 0
    previews = {}
    for kind, fn in KINDS.items():
        for t in range(1, 6):
            tex = {
                'metal': f'metal_t{t}', 'trim': f'trim_t{t}', 'barrel': f'barrel_t{t}', 'dark': 'dark',
                'glow': f'glow_{kind}', 'copper': 'copper', 'white': 'white',
            }
            sup, bar = Model(), Model()
            support(sup, t)
            fn(bar, t)
            used = {e[2] for e in sup.els + bar.els} | {v for e in sup.els + bar.els for v in e[3].values()}
            tj = {k: f'{NS}:block/turret/{tex[k]}' for k in sorted(used)}
            tj['particle'] = f'{NS}:block/turret/metal_t{t}'
            # 同向共面检查
            iss = coplanar_issues(sup.els + bar.els)
            if iss:
                issues_total += len(iss)
                print(f'[warn] {kind} t{t} coplanar', [(f, (sup.els + bar.els)[i][:2], (sup.els + bar.els)[j][:2]) for f, i, j in iss][:4])
            name = f'{kind}_head_t{t}'
            complete = {
                'parent': 'minecraft:block/block',
                'textures': tj,
                'elements': els_json(sup.els + bar.els),
                'display': {
                    'gui': {'rotation': [30, 225, 0], 'translation': [0, -2.5, 0], 'scale': [0.45, 0.45, 0.45]},
                    'ground': {'rotation': [0, 0, 0], 'translation': [0, 2, 0], 'scale': [0.2, 0.2, 0.2]},
                    'fixed': {'rotation': [0, 0, 0], 'translation': [0, -2, 0], 'scale': [0.4, 0.4, 0.4]},
                    'thirdperson_righthand': {'rotation': [75, 45, 0], 'translation': [0, 2.5, 0], 'scale': [0.3, 0.3, 0.3]},
                    'firstperson_righthand': {'rotation': [0, 45, 0], 'translation': [0, 0, 0], 'scale': [0.3, 0.3, 0.3]},
                },
            }
            (MODEL_DIR / f'{name}.json').write_text(json.dumps(complete, indent=2), encoding='utf-8')
            btj = {k: v for k, v in tj.items() if k == 'particle' or any(e[2] == k or k in e[3].values() for e in bar.els)}
            (MODEL_DIR / f'aim/{name}_barrel.json').write_text(json.dumps({'textures': btj, 'elements': els_json(bar.els)}, indent=2), encoding='utf-8')
            stj = {k: v for k, v in tj.items() if k == 'particle' or any(e[2] == k for e in sup.els)}
            for facing in ROT:
                (MODEL_DIR / f'aim/{name}_support_{facing}.json').write_text(
                    json.dumps({'textures': stj, 'elements': els_json(rotate_els(sup.els, facing))}, indent=2), encoding='utf-8')
            tm = {k: texs[v] for k, v in tex.items()}
            previews[(kind, t)] = (sup.els, bar.els, tm)

    # 炮塔底座
    for t in range(1, 6):
        (MODEL_DIR / f'turret_base_t{t}.json').write_text(json.dumps({
            'parent': 'minecraft:block/cube_all',
            'textures': {'all': f'{NS}:block/turret/turret_base_t{t}'},
        }, indent=2), encoding='utf-8')

    # 基地核心
    for t in range(1, 6):
        m = core_model(t)
        tex = {'core': f'core_glow_t{t}', 'frame': f'trim_t{t}', 'plate': f'metal_t{t}'}
        iss = coplanar_issues(m.els)
        if iss:
            issues_total += len(iss)
            print(f'[warn] core t{t} coplanar', len(iss))
        (MODEL_DIR / f'base_core_t{t}.json').write_text(json.dumps({
            'parent': 'minecraft:block/block',
            'textures': dict({k: f'{NS}:block/turret/{v}' for k, v in tex.items()}, particle=f'{NS}:block/turret/trim_t{t}'),
            'elements': els_json(m.els),
            'display': {'gui': {'rotation': [30, 225, 0], 'translation': [0, -0.5, 0], 'scale': [0.58, 0.58, 0.58]}},
        }, indent=2), encoding='utf-8')
        previews[('core', t)] = ([], m.els, {k: texs[v] for k, v in tex.items()})
    print('coplanar issues:', issues_total)

    if PREVIEW is None:
        return
    PREVIEW.mkdir(parents=True, exist_ok=True)
    tile = 200
    groups = [list(KINDS)[0:5], list(KINDS)[5:10], list(KINDS)[10:15], ['core']]
    for gi, g in enumerate(groups):
        sheet = Image.new('RGB', (tile * 6, tile * len(g)), (40, 42, 48))
        for r_, kind in enumerate(g):
            for t in range(1, 6):
                sup, bar, tm = previews[(kind, t)]
                els = [(a, b, mt, f, None) for a, b, mt, f in sup + bar]
                sheet.paste(render(els, tm, tile), (t * tile - tile, r_ * tile))
            if kind != 'core':
                sup, bar, tm = previews[(kind, 5)]
                els = [(a, b, mt, f, None) for a, b, mt, f in sup] + [(a, b, mt, f, rot_x(-60)) for a, b, mt, f in bar]
                sheet.paste(render(els, tm, tile), (5 * tile, r_ * tile))
        sheet.save(PREVIEW / f'turrets_{gi}.png')
    base = Image.new('RGB', (tile * 5, tile), (40, 42, 48))
    for t in range(1, 6):
        els = [((0, 0, 0), (16, 16, 16), 'b', {}, None)]
        base.paste(render(els, {'b': texs[f'turret_base_t{t}']}, tile), ((t - 1) * tile, 0))
    base.save(PREVIEW / 'turret_base.png')


main()
