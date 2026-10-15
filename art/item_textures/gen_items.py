import math
import sys
from pathlib import Path
from PIL import Image

OUT = Path(sys.argv[1])
PREVIEW = Path(sys.argv[2])

RAMPS = {
    'iron': ['#24262e', '#4b4f5c', '#787d8b', '#a7acb8', '#dde1e8'],
    'brass': ['#3b220e', '#7a4a1a', '#b47c2a', '#e0b24c', '#f8e490'],
    'copper': ['#3a1a10', '#7c3620', '#b65a38', '#de8a58', '#f4c294'],
    'cyan': ['#0c2e3e', '#107088', '#20b6ce', '#68e6f2', '#e0fcff'],
    'purple': ['#28103e', '#5a2280', '#9a40c8', '#cf7cf0', '#f6d8ff'],
    'red': ['#3a0c10', '#7c1a1e', '#c2322e', '#ee6a50', '#ffc4a4'],
    'green': ['#12301a', '#1f6a30', '#38a64a', '#7ad86a', '#d6ffc4'],
    'olive': ['#20260e', '#44501e', '#6a7a32', '#96a650', '#c8d488'],
    'yellow': ['#3e2808', '#9a6a10', '#e0a820', '#f8d850', '#fff6c2'],
    'blue': ['#0e1840', '#1c3a8a', '#2f68d0', '#6aa4f0', '#d0e6ff'],
    'obsidian': ['#0c0812', '#1c1626', '#30263c', '#4e4064', '#8676a6'],
    'white': ['#4e5060', '#8e90a0', '#c8cad6', '#e8e9f0', '#ffffff'],
    'orange': ['#3c1606', '#8a3410', '#d8601a', '#f89a3a', '#ffe2a2'],
    'star': ['#463868', '#8676a8', '#cbc2e6', '#f2eeff', '#ffffff'],
}

SLOTS = {'0': 0, '1': 0, '2': 0, '3': 0, '4': 0}


class Sprite:
    # 初始化 16x16 画布与材质槽
    def __init__(self, m='iron', s='red', t='brass', f='yellow', noout=()):
        self.g = [[None] * 16 for _ in range(16)]
        self.slot = {'M': m, 'S': s, 'T': t, 'F': f}
        self.noout = set(noout)

    # 字符映射到 (材质, 明度)
    def tok(self, ch):
        if ch in '01234':
            return (self.slot['M'], int(ch))
        if ch in 'ABCDE':
            return (self.slot['S'], ord(ch) - 65)
        if ch in 'fghij':
            return (self.slot['T'], ord(ch) - 102)
        if ch in 'pqrst':
            return (self.slot['F'], ord(ch) - 112)
        raise ValueError(ch)

    # 按行贴图，'.' 跳过，'_' 擦除
    def rows(self, rows, x0=0, y0=0):
        for dy, row in enumerate(rows):
            if any(len(r) == 16 for r in rows) and len(row) != 16:
                raise ValueError(f'行长度错误: {row!r}')
            for dx, ch in enumerate(row):
                x, y = x0 + dx, y0 + dy
                if ch == '.':
                    continue
                if not (0 <= x < 16 and 0 <= y < 16):
                    raise ValueError('越界')
                if ch == '_':
                    self.g[y][x] = None
                else:
                    self.g[y][x] = self.tok(ch)
        return self

    # 单像素
    def px(self, pts, ch):
        for x, y in pts:
            self.g[y][x] = self.tok(ch)
        return self

    # 圆盘填充，带左上光照
    def disk(self, cx, cy, r, slot, mode='sphere', base=2):
        ramp = self.slot[slot]
        for y in range(16):
            for x in range(16):
                dx, dy = x + 0.5 - cx, y + 0.5 - cy
                d = math.hypot(dx, dy)
                if d > r:
                    continue
                l = -(dx + dy) / (r * 1.4142 + 1e-6)
                if mode == 'sphere':
                    if l > 0.42:
                        lv = 4
                    elif l > 0.08:
                        lv = 3
                    elif l > -0.32:
                        lv = 2
                    else:
                        lv = 1
                else:
                    lv = base
                    if d > r - 1.3:
                        if l > 0.15:
                            lv = min(4, base + 1)
                        elif l < -0.15:
                            lv = max(1, base - 1)
                self.g[y][x] = (ramp, lv)
        return self

    # 圆盘擦除
    def carve(self, cx, cy, r):
        for y in range(16):
            for x in range(16):
                if math.hypot(x + 0.5 - cx, y + 0.5 - cy) <= r:
                    self.g[y][x] = None
        return self

    # 齿轮
    def gear(self, cx, cy, rb, rt, teeth, slot, hole=0.0, phase=0.0, tw=1.15):
        ramp = self.slot[slot]
        for y in range(16):
            for x in range(16):
                dx, dy = x + 0.5 - cx, y + 0.5 - cy
                d = math.hypot(dx, dy)
                tooth = False
                for k in range(teeth):
                    ang = 2 * math.pi * (k / teeth + phase / teeth)
                    ax, ay = math.cos(ang), math.sin(ang)
                    if dx * ax + dy * ay > 0 and abs(-dx * ay + dy * ax) <= tw:
                        tooth = True
                inside = d <= rb or (d <= rt and tooth)
                if not inside or d < hole:
                    continue
                l = -(dx + dy) / (rt * 1.4142)
                lv = 3
                if d > rb - 1.0:
                    if l > 0.1:
                        lv = 4
                    elif l < -0.1:
                        lv = 1
                    else:
                        lv = 2
                elif d < hole + 1.2 and hole > 0:
                    if l > 0.1:
                        lv = 1
                    else:
                        lv = 4
                elif l < -0.2:
                    lv = 2
                self.g[y][x] = (ramp, lv)
        return self

    # 粗线段（胶囊），沿轴两侧分明暗
    def capsule(self, x1, y1, x2, y2, r, slot, hi=3, lo=1, mid=2):
        ramp = self.slot[slot]
        vx, vy = x2 - x1, y2 - y1
        ln = math.hypot(vx, vy)
        nx, ny = -vy / ln, vx / ln
        if nx + ny > 0:
            nx, ny = -nx, -ny
        for y in range(16):
            for x in range(16):
                px_, py_ = x + 0.5, y + 0.5
                t = max(0.0, min(1.0, ((px_ - x1) * vx + (py_ - y1) * vy) / (ln * ln)))
                qx, qy = x1 + vx * t, y1 + vy * t
                if math.hypot(px_ - qx, py_ - qy) > r:
                    continue
                side = (px_ - qx) * nx + (py_ - qy) * ny
                if side > 0.35:
                    lv = hi
                elif side < -0.35:
                    lv = lo
                else:
                    lv = mid
                self.g[y][x] = (ramp, lv)
        return self

    # 圆环段（信号波）
    def arc(self, cx, cy, r0, r1, a0, a1, ch):
        tok = self.tok(ch)
        for y in range(16):
            for x in range(16):
                dx, dy = x + 0.5 - cx, y + 0.5 - cy
                d = math.hypot(dx, dy)
                a = math.degrees(math.atan2(-dy, dx)) % 360
                ok = a0 <= a <= a1
                if a0 > a1:
                    ok = a >= a0 or a <= a1
                if r0 <= d <= r1 and ok:
                    self.g[y][x] = tok
        return self

    # 按遮罩填色，'#' 为填充，'_' 为擦除
    def mask(self, rows, x0, y0, slot, lv=3):
        ramp = self.slot[slot]
        for dy, row in enumerate(rows):
            for dx, ch in enumerate(row):
                if ch == '#':
                    self.g[y0 + dy][x0 + dx] = (ramp, lv)
                elif ch == '_':
                    self.g[y0 + dy][x0 + dx] = None
        return self

    # 倒角：靠近左上边缘提亮，靠近右下边缘压暗
    def bevel(self, slot):
        ramp = self.slot[slot]

        def edge(x, y):
            if not (0 <= x < 16 and 0 <= y < 16) or self.g[y][x] is None:
                return True
            for ox, oy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                nx, ny = x + ox, y + oy
                if not (0 <= nx < 16 and 0 <= ny < 16) or self.g[ny][nx] is None:
                    return True
            return False
        res = {}
        for y in range(16):
            for x in range(16):
                c = self.g[y][x]
                if c is None or c[0] != ramp or edge(x, y):
                    continue
                if edge(x - 1, y) or edge(x, y - 1):
                    res[(x, y)] = 4
                elif edge(x + 1, y) or edge(x, y + 1):
                    res[(x, y)] = 2
        for (x, y), lv in res.items():
            self.g[y][x] = (ramp, lv)
        return self

    # 自动描边：贴近透明的像素改为同材质最暗色
    def render(self):
        img = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
        noout = {self.slot[k] for k in self.noout}
        for y in range(16):
            for x in range(16):
                c = self.g[y][x]
                if c is None:
                    continue
                ramp, lv = c
                edge = False
                for ox, oy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    nx, ny = x + ox, y + oy
                    if not (0 <= nx < 16 and 0 <= ny < 16) or self.g[ny][nx] is None:
                        edge = True
                if edge and ramp not in noout:
                    lv = 0
                h = RAMPS[ramp][lv].lstrip('#')
                img.putpixel((x, y), tuple(int(h[i:i + 2], 16) for i in (0, 2, 4)) + (255,))
        return img


def chip(emblem, s, pins='lr', body='iron', t='brass'):
    # 芯片外壳 + 引脚 + 中央图案
    sp = Sprite(m=body, s=s, t=t, noout=('T',))
    sp.rows([
        '...0000000000...',
        '...0444444410...',
    ], 0, 2)
    for i in range(8):
        sp.rows(['...04' + emblem[i] + '10...'], 0, 4 + i)
    sp.rows(['...0411111110...', '...0000000000...'], 0, 12)
    if 'l' in pins:
        sp.px([(1, y) for y in (4, 6, 8, 10, 12)], 'g')
        sp.px([(2, y) for y in (4, 6, 8, 10, 12)], 'i')
    if 'r' in pins:
        sp.px([(13, y) for y in (4, 6, 8, 10, 12)], 'h')
        sp.px([(14, y) for y in (4, 6, 8, 10, 12)], 'g')
    if 't' in pins:
        sp.px([(x, 1) for x in (5, 7, 9, 11)], 'i')
    if 'b' in pins:
        sp.px([(x, 14) for x in (4, 6, 8, 10)], 'h')
        sp.px([(x, 15) for x in (4, 6, 8, 10)], 'g')
    return sp


def bolt(sp, x, y, ch_hi='t', ch_lo='s'):
    # 小型闪电
    sp.px([(x + 2, y), (x + 1, y + 1), (x, y + 2), (x + 1, y + 2), (x + 2, y + 2), (x + 1, y + 3), (x, y + 4)], ch_hi)
    sp.px([(x + 3, y), (x + 2, y + 1), (x + 3, y + 2), (x + 2, y + 3), (x + 1, y + 4)], ch_lo)


S = {}

# 铁质弹丸
S['ammo_iron_slug'] = Sprite('iron', 'brass').rows([
    '................',
    '.......00.......',
    '......0430......',
    '......0430......',
    '.....043210.....',
    '.....043210.....',
    '.....AEDCBA.....',
    '.....AEDCBA.....',
    '.....ABBBBA.....',
    '.....AEDCBA.....',
    '.....AEDCBA.....',
    '.....AEDCBA.....',
    '.....AEDCBA.....',
    '....ADDDCCBA....',
    '....AAAAAAAA....',
    '................',
])

# 脉冲弹芯
S['ammo_pulse_core'] = Sprite('iron', 'cyan').rows([
    '................',
    '......0000......',
    '.....043210.....',
    '.....043210.....',
    '.....ADECBA.....',
    '.....AEEDCA.....',
    '....00432100....',
    '.....ADECBA.....',
    '.....AEEDCA.....',
    '....00432100....',
    '.....ADECBA.....',
    '.....AEEDCA.....',
    '.....043210.....',
    '.....043210.....',
    '......0000......',
    '................',
])

# 粒子弹夹
S['ammo_particle_clip'] = Sprite('iron', 'purple').rows([
    '................',
    '.....0DEDC0.....',
    '.....0CDCB0.....',
    '....00000000....',
    '....04333210....',
    '....04BDCB10....',
    '....04BEDB10....',
    '....04BDCB10....',
    '....04BEDB10....',
    '....04BDCB10....',
    '....04BEDB10....',
    '....04333210....',
    '....04222110....',
    '...0000000000...',
    '...0433332110...',
    '...0000000000...',
])

# 高压粒子弹夹
S['ammo_hv_particle_clip'] = Sprite('iron', 'purple', f='yellow').rows([
    '................',
    '....0DE00DE0....',
    '....0CD00CD0....',
    '...0000000000...',
    '...0443333210...',
    '...04BD22BD10...',
    '...04CE22CE10...',
    '...04BD22BD10...',
    '...0tptptptp0...',
    '...04CE22CE10...',
    '...04BD22BD10...',
    '...04CE22CE10...',
    '...0422222110...',
    '..000000000000..',
    '..043333332110..',
    '..000000000000..',
])

# 榴弹
S['ammo_grenade'] = Sprite('olive', 'brass', 'yellow').rows([
    '................',
    '.......00.......',
    '......0430......',
    '.....034210.....',
    '....03442110....',
    '....04432110....',
    '....04332110....',
    '....04332110....',
    '....fjiihhgf....',
    '....04332110....',
    '....00000000....',
    '....ADDCCBBA....',
    '....AEDCCBBA....',
    '....AEDCCBBA....',
    '....AAAAAAAA....',
    '................',
])

# 高爆榴弹
S['ammo_he_grenade'] = Sprite('red', 'iron', 'yellow').rows([
    '................',
    '.......AA.......',
    '......ADCA......',
    '.....034210.....',
    '....03442110....',
    '...0344321100...',
    '...0343221100...',
    '...fjfjfjfjfj...',
    '...0343221100...',
    '....03322110....',
    '.....032110.....',
    '......ADCA......',
    '.....ADDCBA.....',
    '....ADCDCBBA....',
    '...AAAAAAAAAA...',
    '................',
])

# 电弧弹
S['ammo_arc_cell'] = Sprite('blue', 'copper', 'yellow', noout=('T',)).rows([
    '................',
    '................',
    '......ADCA......',
    '....00000000....',
    '....04332110....',
    '....0433jj10....',
    '....043jj110....',
    '....04jjjj10....',
    '....043ji110....',
    '....04ji2110....',
    '....04332110....',
    '....04222110....',
    '....00000000....',
    '....ADDCCBBA....',
    '....AAAAAAAA....',
    '................',
])

# 高压电弧弹
S['ammo_hv_arc_cell'] = Sprite('iron', 'copper', 'cyan', noout=('T',)).rows([
    '................',
    '....AD....DA....',
    '....ADjiijDA....',
    '....AD.hh.DA....',
    '...0000000000...',
    '...0443332110...',
    '...ADDDCCCBBA...',
    '...0443332110...',
    '...04hijjih10...',
    '...04ijjjji10...',
    '...04hijjih10...',
    '...0443332110...',
    '...ADDDCCCBBA...',
    '...0443332110...',
    '...0000000000...',
    '................',
])

# 裁决弹
S['ammo_decider'] = Sprite('white', 'brass').rows([
    '................',
    '.......00.......',
    '......0430......',
    '......0430......',
    '.....043210.....',
    '.....043210.....',
    '.....043210.....',
    '....AEEDDCBA....',
    '....ADCCCBBA....',
    '.....AEDCBA.....',
    '.....AEBCBA.....',
    '.....AEBCBA.....',
    '.....AEDCBA.....',
    '....AEEDDCBA....',
    '....AAAAAAAA....',
    '................',
])

# 终焉弹
S['ammo_finality'] = Sprite('obsidian', 'red', noout=('S',)).rows([
    '................',
    '......0000......',
    '.....034210.....',
    '....03442110....',
    '....0432E110....',
    '....043ED110....',
    '....04E32D10....',
    '...0CDEEEEDC0...',
    '...0BCDDDDCB0...',
    '....0432D110....',
    '....04D32110....',
    '....04332E10....',
    '....04332110....',
    '...0000000000...',
    '...0433332110...',
    '...0000000000...',
])

# 伤害增幅器
S['module_damage_amp_1'] = chip([
    '22EE22', '2DEED2', 'DEEEED', '22ED22', '22ED22', '22DC22', '22CB22', '222222'], 'red', 'lr')

# 射速加速器
S['module_fire_rate_1'] = Sprite('iron', 'yellow', 'copper').rows([
    '..ff...ff...ff..',
    '.0ji444ji444ji0.',
    '.0ihEE3ihEE3ih0.',
    '.0ih3EEih3EEih0.',
    '.0hg2DDhg2DDhg0.',
    '.0hgDD1hgDD1hg0.',
    '.0gf111gf111gf0.',
    '..ff...ff...ff..',
], 0, 4)

# 护盾增幅器
S['module_shield_boost_1'] = chip([
    'CDEEDC', 'CDEDDC', 'CDDDCC', 'BCDDCB', '2BCCB2', '22BB22', '222222', '222222'], 'blue', 'lr')

# 装甲板
S['module_armor_plate'] = Sprite('iron').rows([
    '..000000000000..',
    '.04444444444420.',
    '.04333333333210.',
    '.04333333333210.',
    '.01111111111110.',
    '.04444444444420.',
    '.04333333333210.',
    '.04333333333210.',
    '.02222222222110.',
    '..000000000000..',
], 0, 3).px([(3, 5), (12, 5), (3, 9), (12, 9)], '4').px([(4, 6), (13, 6), (4, 10), (13, 10)], '1')

# 目标优先级芯片
S['module_target_priority'] = chip([
    '22DD22', '2DDDD2', 'DD22DD', 'D2EE2D', 'D2EE2D', 'DD22DD', '2DDDD2', '22DD22'], 'red', 'lrtb')

# 节能芯片
S['module_energy_saver_1'] = chip([
    '22CC22', '2BBBB2', '2BEEB2', '2BDDB2', '2BDDB2', '2BCCB2', '2BCCB2', '2BBBB2'], 'green', 'b')

# 自毁模块
S['module_self_destruct'] = Sprite('iron', 'red', 'yellow').rows([
    '................',
    '................',
    '......ADCA......',
    '.....AEDDCA.....',
    '..000000000000..',
    '..044444444210..',
    '..0jjffjjffjj0..',
    '..0jffjjffjjf0..',
    '..0ffjjffjjff0..',
    '..043333333210..',
    '..0432BDDB3210..',
    '..043333333210..',
    '..022222222110..',
    '..000000000000..',
    '................',
    '................',
])

# 过载核心
S['module_overload_core'] = Sprite('iron', 'orange', noout=('S',)).rows([
    '................',
    '.....000000.....',
    '....04443210....',
    '...0000000000...',
    '...04.CDDC.10...',
    '...04CDEEDC10...',
    '...04DEEEDC10...',
    '...04DEEDDB10...',
    '...04CDDDCB10...',
    '...04BCDCBA10...',
    '...04.BCCB.10...',
    '...0000000000...',
    '....04443210....',
    '.....000000.....',
    '................',
    '................',
], 0, 1)

# 炮塔核心
S['turret_core'] = Sprite('iron', 'cyan', 'white', noout=('S', 'T')).rows([
    '....00000000....',
    '...0444444420...',
    '..044333333210..',
    '..043333333210..',
    '..043333333210..',
    '..043333333210..',
    '..043333333210..',
    '..043333333210..',
    '..043333333210..',
    '..042222222110..',
    '...0221111110...',
    '....00000000....',
], 0, 2).rows([
    '.0000.',
    '0BCDC0',
    '0CEjD0',
    '0CDDC0',
    '0BCCB0',
    '.0000.',
], 5, 5).px([(4, 4), (11, 4), (4, 11), (11, 11)], '1').px([(3, 3), (12, 3), (3, 12), (12, 12)], '4')

# 下界之星碎片
S['nether_star_fragment'] = Sprite('star', 'white', noout=('S',)).rows([
    '................',
    '...........0....',
    '..........040...',
    '.........0441...',
    '........04431...',
    '.......044321...',
    '......0443321...',
    '.....0443321....',
    '....04433210....',
    '...04433211.....',
    '...0443221......',
    '..04332210......',
    '...043210.......',
    '....0110........',
    '................',
    '................',
]).px([(10, 4), (9, 5), (8, 6), (7, 7)], '4').px([(13, 6)], 'E').px([(14, 7), (12, 7)], 'D')

# 能量扩展模块
S['core_module_capacity'] = Sprite('iron', 'cyan').rows([
    '..00...00...00..',
    '.0000.0000.0000.',
    '.AEDA.AEDA.AEDA.',
    '.AEDA.AEDA.AEDA.',
    '.ADCA.ADCA.ADCA.',
    '.ADCA.ADCA.ADCA.',
    '.ACBA.ACBA.ACBA.',
    '.ACBA.ACBA.ACBA.',
    '.0000.0000.0000.',
    '.00000000000000.',
    '.04444333332210.',
    '.02222221111110.',
    '.00000000000000.',
], 0, 1)

# 发电效率模块 I
gen1 = Sprite('iron', 'red', 'brass', 'yellow', noout=('F',)).mask([
    '......####......',
    '..###.####.###..',
    '..###.####.###..',
    '...##########...',
    '...##########...',
    '.##############.',
    '.##############.',
    '.##############.',
    '.##############.',
    '...##########...',
    '...##########...',
    '..###.####.###..',
    '..###.####.###..',
    '......####......',
], 0, 1, 'M').bevel('M')
gen1.disk(8, 8, 3.2, 'T', mode='flat', base=2)
bolt(gen1, 6, 6)
S['core_module_generation_1'] = gen1

# 发电效率模块 II
gen2 = Sprite('iron', 'red', 'brass', 'yellow', noout=('F',)).mask([
    '...##...',
    '.######.',
    '.######.',
    '########',
    '########',
    '.######.',
    '.######.',
    '...##...',
], 8, 0, 'M').bevel('M')
gen2.mask([
    '....####....',
    '.##.####.##.',
    '.##########.',
    '..########..',
    '############',
    '############',
    '############',
    '############',
    '..########..',
    '.##########.',
    '.##.####.##.',
    '....####....',
], 0, 4, 'M').bevel('M')
gen2.disk(6, 10, 2.9, 'T', mode='flat', base=2)
bolt(gen2, 4, 8)
S['core_module_generation_2'] = gen2

# 范围扩展模块 I
r1 = Sprite('iron', 'cyan', 'iron', noout=('S',))
r1.disk(7.5, 8.0, 5.8, 'M', mode='sphere').carve(10.4, 5.1, 4.6)
r1.capsule(7.0, 9.0, 10.8, 5.2, 0.6, 'M', 3, 2, 3)
r1.px([(11, 4)], 'E').px([(12, 3)], 'D')
r1.rows([
    '......000.......',
    '......040.......',
    '....0000000.....',
    '....0443210.....',
    '....0000000.....',
], 0, 11)
S['core_module_range_1'] = r1

# 范围扩展模块 II
r2 = Sprite('iron', 'cyan', 'iron', noout=('S',))
r2.disk(7, 8.5, 6.8, 'M', mode='sphere').carve(10.0, 5.5, 5.4)
r2.capsule(6.5, 9.5, 10.3, 5.7, 0.6, 'M', 3, 2, 3)
r2.px([(11, 5)], 'E')
r2.arc(11.5, 5.5, 2.4, 3.3, 10, 80, 'D')
r2.arc(11.5, 5.5, 4.4, 5.3, 15, 75, 'C')
r2.rows([
    '.....000........',
    '....04410.......',
    '..00000000000...',
    '..04443332110...',
], 0, 12)
S['core_module_range_2'] = r2

# 无线稳压模块
wl = Sprite('iron', 'cyan', noout=('S',))
wl.arc(7.5, 3.5, 2.6, 3.5, 300, 60, 'D').arc(7.5, 3.5, 2.6, 3.5, 120, 240, 'D')
wl.arc(7.5, 3.5, 4.8, 5.7, 315, 45, 'C').arc(7.5, 3.5, 4.8, 5.7, 135, 225, 'C')
wl.rows([
    '......AEDA......',
    '......ADCA......',
    '.......00.......',
    '.......41.......',
    '.......41.......',
    '.......41.......',
    '.......41.......',
    '......0410......',
    '....00000000....',
    '....04443210....',
    '....04DEC210....',
    '....04222110....',
    '....00000000....',
], 0, 2)
S['core_module_wireless'] = wl

# 终焉裁决强化模块
jd = Sprite('red', 'brass', 'white')
for y in range(16):
    for x in range(16):
        dx, dy = x + 0.5 - 8, y + 0.5 - 8
        m = abs(dx) + abs(dy)
        if m > 7.6:
            continue
        lit = -(dx + dy)
        if m > 5.6:
            lv = 3
            if lit > 1:
                lv = 4
            elif lit < -1:
                lv = 1
            jd.g[y][x] = ('brass', lv)
        else:
            lv = 2
            if lit > 2:
                lv = 3
            elif lit < -2:
                lv = 1
            jd.g[y][x] = ('red', lv)
jd.rows([
    '.......BC.......',
    '.......AB.......',
    '.....BDDDCA.....',
    '.......ji.......',
    '.......ji.......',
    '.......ji.......',
    '.......ji.......',
    '.......ih.......',
    '.......h........',
], 0, 3)
S['core_module_judgment'] = jd

# 防空强化模块 I
S['core_module_antiair_1'] = Sprite('white', 'red', 'iron').rows([
    '................',
    '.......CC.......',
    '......DDCB......',
    '.....EDDCBA.....',
    '.....443321.....',
    '.....443321.....',
    '.....BBBBBB.....',
    '.....443321.....',
    '.....443321.....',
    '.....443321.....',
    '....i443321h....',
    '...ii443321hh...',
    '..iih443321hhg..',
    '..hhhggggggggg..',
    '......gggg......',
    '................',
])

MISSILE = [
    '..DC..',
    '.EDCB.',
    '.4321.',
    '.4321.',
    '.BCCB.',
    '.4321.',
    'ih32hg',
    'ih..hg',
]

# 防空强化模块 II
aa2 = Sprite('white', 'red', 'iron').rows(MISSILE, 1, 1).rows(MISSILE, 9, 1)
aa2.rows([
    '.ffffffffffffff.',
    '.fiiiiihhhhhgff.',
    '.fhhhhhggggggff.',
    '.ffffffffffffff.',
], 0, 9)
aa2.rows(['..gg......gg....', '..ff......ff....'], 1, 13)
S['core_module_antiair_2'] = aa2

# 防空强化模块 III
S['core_module_antiair_3'] = Sprite('iron', 'red', 'white', 'yellow').rows([
    '................',
    '................',
    '...CC..CC..CC...',
    '..EDCBEDCBEDCB..',
    '..jihgjihgjihg..',
    '.00000000000000.',
    '.04444444444420.',
    '.04tptptptpt210.',
    '.04333333333210.',
    '.04311311311210.',
    '.04333333333210.',
    '.04333333333210.',
    '.02222222221110.',
    '.00000000000000.',
    '..00........00..',
    '................',
])

# 核心护盾强化模块 I
sh1 = Sprite('iron', 'blue', 'white')
sh1.disk(8, 8, 6.6, 'M', mode='flat', base=3)
sh1.disk(8, 8, 5.0, 'S', mode='sphere')
sh1.disk(8, 8, 1.8, 'M', mode='sphere')
S['core_module_shield_1'] = sh1

# 核心护盾强化模块 II
S['core_module_shield_2'] = Sprite('iron', 'blue', 'white').rows([
    '................',
    '..000000000000..',
    '..043333333210..',
    '..04jjDDCCjj10..',
    '..04DjjDCjjB10..',
    '..04DDjjjjCB10..',
    '..04DDDCCCBB10..',
    '..04DDDCCCBB10..',
    '..04DDDCCCBB10..',
    '...04DDCCCB10...',
    '...04DCCCBB10...',
    '....04DCCB10....',
    '.....04CB10.....',
    '......0110......',
    '.......00.......',
    '................',
])

# 核心护盾强化模块 III
S['core_module_shield_3'] = Sprite('brass', 'blue', 'cyan', noout=('T',)).rows([
    '.00000000000000.',
    '.04443333332210.',
    '.04DDDDDCCCCB10.',
    '.04DDDDihCCCB10.',
    '.04DDDijihCCB10.',
    '.04DDijjiihCB10.',
    '.04DDijiihhCB10.',
    '.04DDDiihhCCB10.',
    '.04DDDDhhCCCB10.',
    '.04DDDDCCCCBB10.',
    '..04DDDCCCBB10..',
    '...04DCCCBB10...',
    '....04DCCB10....',
    '.....04CB10.....',
    '......0410......',
    '.......00.......',
])

# 核心修复强化模块 I
rp1 = Sprite('iron', 'green')
rp1.capsule(3.5, 12.5, 10.5, 5.5, 1.3, 'M', 3, 1, 2)
rp1.disk(11.4, 4.6, 3.6, 'M', mode='flat', base=3).carve(13.0, 3.0, 1.5)
rp1.disk(3.2, 12.8, 2.3, 'M', mode='flat', base=3).carve(3.2, 12.8, 0.8)
S['core_module_repair_1'] = rp1

# 核心修复强化模块 II
rp2 = Sprite('iron', 'green')
rp2.rows([
    '...BBB....',
    '...BEC....',
    'BBBBEDBBB.',
    'BEEEEDDDC.',
    'BCCCDCCCB.',
    '...BCB....',
    '...BCB....',
    '...BBB....',
], 0, 0)
rp2.capsule(4.5, 13.5, 11.5, 6.5, 1.3, 'M', 3, 1, 2)
rp2.disk(12.2, 5.6, 3.5, 'M', mode='flat', base=3).carve(13.8, 4.0, 1.5)
rp2.disk(4.0, 13.4, 2.2, 'M', mode='flat', base=3).carve(4.0, 13.4, 0.8)
S['core_module_repair_2'] = rp2

# 核心修复强化模块 III
rp3 = Sprite('iron', 'green', 'copper')
rp3.capsule(3.5, 3.5, 12.5, 12.5, 0.9, 'T', 3, 1, 2)
rp3.capsule(0.8, 6.0, 6.0, 0.8, 1.6, 'M', 4, 2, 3)
rp3.capsule(3.5, 12.5, 10.5, 5.5, 1.3, 'M', 3, 1, 2)
rp3.disk(11.4, 4.6, 3.6, 'M', mode='flat', base=3).carve(13.0, 3.0, 1.5)
rp3.disk(3.2, 12.8, 2.3, 'M', mode='flat', base=3).carve(3.2, 12.8, 0.8)
rp3.rows([
    '..BBB..',
    '..BEB..',
    'BBBEBBB',
    'BEEEDDB',
    'BBBDBBB',
    '..BDB..',
    '..BBB..',
], 9, 9)
S['core_module_repair_3'] = rp3

OUT.mkdir(parents=True, exist_ok=True)
imgs = []
for name, sp in S.items():
    img = sp.render()
    img.save(OUT / f'{name}.png')
    imgs.append((name, img))

# 预览图：每格放大 8 倍
cols = 6
scale = 8
pad = 4
rows_n = (len(imgs) + cols - 1) // cols
sheet = Image.new('RGBA', (cols * (16 * scale + pad) + pad, rows_n * (16 * scale + pad) + pad), (58, 62, 70, 255))
for i, (name, img) in enumerate(imgs):
    cx = pad + (i % cols) * (16 * scale + pad)
    cy = pad + (i // cols) * (16 * scale + pad)
    sheet.alpha_composite(img.resize((16 * scale, 16 * scale), Image.NEAREST), (cx, cy))
sheet.save(PREVIEW)
print(len(imgs), 'sprites')
