#!/usr/bin/env python3
"""Checks the app's video effect shader (YouRec/VideoEffectShader.kt) against
an independent NumPy transcription of the iPhone app's Metal kernels
(YouRec/VideoEffects/VideoEffects.metal, ve_effect). The GLSL runs in WebGL 1
(the same GLSL ES 1.0 the app compiles on OpenGL ES 2) through headless
Chromium: python3 check-shaders.py [work folder]. Pictures between passes are
8-bit display values in the app, so each reference starts and ends there too.
"""
import json, os, re, subprocess, sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
SOURCE = os.path.join(HERE, "..", "..", "YouRec", "VideoEffectShader.kt")
WORK = sys.argv[1] if len(sys.argv) > 1 else "/tmp/yourec-effects"
os.makedirs(WORK, exist_ok=True)

kotlin = open(SOURCE).read()
parameters = int(re.search(r"const val PARAMETERS = (\d+)", kotlin).group(1))
lattice = int(re.search(r"const val latticeSize = (\d+)", open(os.path.join(HERE, "..", "..", "YouRec", "VideoEffectRendering.kt")).read()).group(1))
def source(name):
    text = re.search(r'val %s = (?:HEADER \+ )?"""(.*?)"""' % name, kotlin, re.S).group(1)
    text = text.replace("$PARAMETERS", str(parameters)).replace("${VideoEffectRendering.latticeSize}", str(lattice))
    assert "$" not in text, "unresolved template in " + name
    return text
HEADER = source("HEADER")
fragment, REDUCE, HIGHLIGHTS, BLUR = HEADER + source("FRAGMENT"), HEADER + source("REDUCE"), HEADER + source("HIGHLIGHTS"), HEADER + source("BLUR")

W, H = 96, 64
LUMA = np.array([0.2126, 0.7152, 0.0722])

def picture():
    """Hues across, saturation and value down, a grey ramp and skin tones."""
    y, x = np.mgrid[0:H, 0:W].astype(float)
    h = x / W; s = np.clip(1.2 - y / (H * 0.6), 0, 1); v = np.clip(0.15 + y / H, 0, 1)
    i = np.floor(h * 6) % 6; f = h * 6 - np.floor(h * 6)
    p, q, t = v * (1 - s), v * (1 - s * f), v * (1 - s * (1 - f))
    rgb = np.select([i[..., None] == k for k in range(6)], [np.stack(a, -1) for a in [(v, t, p), (q, v, p), (p, v, t), (p, q, v), (t, p, v), (v, p, q)]])
    rgb[H - 8:, :] = (x[H - 8:, :, None] / (W - 1))
    rgb[:8, :24] = [0.87, 0.64, 0.52]; rgb[:8, 24:48] = [0.55, 0.36, 0.26]
    return np.round(np.clip(rgb, 0, 1) * 255).astype(np.uint8)

def srgb(c): return np.where(c > 0.0031308, 1.055 * np.power(np.maximum(c, 0), 1 / 2.4) - 0.055, c * 12.92)
def lin(c): return np.where(c > 0.04045, np.power(np.maximum((c + 0.055) / 1.055, 0), 2.4), c / 12.92)
def luma(c): return c @ LUMA
def smoothstep(a, b, x): t = np.clip((x - a) / (b - a), 0, 1); return t * t * (3 - 2 * t)
def mix(a, b, t): return a + (b - a) * t
def lab(c):
    m1 = np.array([[.4122214708, .5363325363, .0514459929], [.2119034982, .6806995451, .1073969566], [.0883024619, .2817188376, .6299787005]])
    m2 = np.array([[.2104542553, .793617785, -.0040720468], [1.9779984951, -2.428592205, .4505937099], [.0259040371, .7827717662, -.808675766]])
    return np.cbrt(np.maximum(c @ m1.T, 0)) @ m2.T
def unlab(c):
    m1 = np.array([[1, .3963377774, .2158037573], [1, -.1055613458, -.0638541728], [1, -.0894841775, -1.291485548]])
    m2 = np.array([[4.0767416621, -3.3077115913, .2309699292], [-1.2684380046, 2.6097574011, -.3413193965], [-.0041960863, -.7034186147, 1.707614701]])
    return ((c @ m1.T) ** 3) @ m2.T
def hsv(c):
    r, g, b = c[..., 0], c[..., 1], c[..., 2]
    hi = c.max(-1); lo = c.min(-1); d = hi - lo; safe = np.where(d > 1e-6, d, 1)
    h = np.where(hi == r, np.mod((g - b) / safe + 6, 6), np.where(hi == g, (b - r) / safe + 2, (r - g) / safe + 4))
    h = np.where(d > 1e-6, h, 0)
    return np.stack([h / 6, np.where(hi > 1e-6, d / np.where(hi > 1e-6, hi, 1), 0), hi], -1)
def hue_distance(a, b): d = np.abs(a - b); return np.minimum(d, 1 - d)
def scurve(y, k): y = np.clip(y, 0, 1); a = y ** k; b = (1 - y) ** k; return a / np.maximum(a + b, 1e-6)
def curve(y, k): return lin(np.clip((srgb(y) - .5) * k + .5, 0, 1))

def coordinates():
    y, x = np.mgrid[0:H, 0:W].astype(float)
    return np.stack([(x + .5) / W, (y + .5) / H], -1)

def sample(encoded, uv):
    """Bilinear, clamped to the edge, on the 8-bit display values; then linear."""
    h, w = encoded.shape[:2]
    x = np.clip(uv[..., 0] * w - .5, 0, w - 1); y = np.clip(uv[..., 1] * h - .5, 0, h - 1)
    x0 = np.floor(x).astype(int); y0 = np.floor(y).astype(int); x1 = np.minimum(x0 + 1, w - 1); y1 = np.minimum(y0 + 1, h - 1)
    fx = (x - x0)[..., None]; fy = (y - y0)[..., None]; e = encoded / 255.0
    value = mix(mix(e[y0, x0], e[y0, x1], fx), mix(e[y1, x0], e[y1, x1], fx), fy)
    return lin(value)

def grid(w, h):
    y, x = np.mgrid[0:h, 0:w].astype(float)
    return x, y

def gate(uv, radius):
    d = np.abs(uv - .5) - (.5 - radius)
    outside = np.hypot(np.maximum(d[..., 0], 0), np.maximum(d[..., 1], 0)) + np.minimum(np.maximum(d[..., 0], d[..., 1]), 0)
    return 1 - smoothstep(radius - .004, radius + .004, outside)

def gaussian_weights(sigma):
    sigma = max(0.25, sigma); radius = min(48, int(np.ceil(sigma * 3)))
    raw = [np.exp(-(i * i) / (2 * sigma * sigma)) for i in range(radius + 1)]; total = raw[0] + 2 * sum(raw[1:])
    pairs = []
    for i in range(1, radius + 1, 2):
        a = raw[i]; b = raw[i + 1] if i + 1 <= radius else 0
        pairs.append((i + b / (a + b), (a + b) / total))
    return raw[0] / total, pairs

def area(encoded, w, h, threshold=None):
    """ve_reduce and ve_highlights: per-sample (thresholded) average over each output pixel."""
    H0, W0 = encoded.shape[:2]; cx = max(1, int(np.ceil(W0 / w - 1e-4))); cy = max(1, int(np.ceil(H0 / h - 1e-4)))
    x, y = grid(w, h); total = 0
    for j in range(cy):
        for i in range(cx):
            c = sample(encoded, np.stack([(x + (i + .5) / cx) / w, (y + (j + .5) / cy) / h], -1))
            if threshold: c = c * smoothstep(threshold[0] - threshold[1], threshold[0] + threshold[1], luma(c))[..., None]
            total = total + c
    return srgb(np.clip(total / (cx * cy), 0, 1))

def bilateral(encoded, sigma, direction):
    h, w = encoded.shape[:2]; x, y = grid(w, h); uv = np.stack([(x + .5) / w, (y + .5) / h], -1)
    centre = sample(encoded, uv); s = max(.5, sigma); radius = min(10, int(np.ceil(s * 2)))
    result = 0; total = 0
    for i in range(-radius, radius + 1):
        c = sample(encoded, uv + np.array(direction) * i / np.array([w, h])); d = srgb(c) - srgb(centre)
        wgt = np.exp(-i * i / (2 * s * s) - (d * d).sum(-1) / .02)[..., None]; result = result + wgt * c; total = total + wgt
    return srgb(np.clip(result / np.maximum(total, 1e-6), 0, 1))

def bayer(qx, qy, size):
    value = np.zeros_like(qx); scale = 1
    while scale < size:
        bx = np.floor(qx / scale) % 2; by = np.floor(qy / scale) % 2
        value = value * 4 + np.abs(bx - by) * 2 + by; scale *= 2
    return (value + .5) / (size * size)

QUANT = {"palette": [(.1, .05, .2), (.8, .2, .1), (.2, .6, .9), (.95, .9, .7)], "bands": [.05, .2, .45]}
def quant_uniforms():
    labs = np.zeros((16, 4)); bands = np.zeros(15)
    for i, c in enumerate(QUANT["palette"]): labs[i, :3] = lab(lin(np.array(c))); labs[i, 3] = 1
    bands[:len(QUANT["bands"])] = QUANT["bands"]
    return [{"name": "paletteLab", "type": "4fv", "value": labs.flatten().tolist()}, {"name": "bands", "type": "1fv", "value": bands.tolist()},
            {"name": "colorCount", "type": "1f", "value": len(QUANT["palette"])}]

def blurred(encoded, sigma, horizontal):
    h, w = encoded.shape[:2]; x, y = grid(w, h); uv = np.stack([(x + .5) / w, (y + .5) / h], -1)
    centre, pairs = gaussian_weights(sigma); d = np.array([1 / w, 0]) if horizontal else np.array([0, 1 / h])
    result = sample(encoded, uv) * centre
    for offset, weight in pairs: result = result + (sample(encoded, uv + d * offset) + sample(encoded, uv - d * offset)) * weight
    return srgb(np.clip(result, 0, 1))

def gamut(c):
    y = np.clip(luma(c), 0, 1)[..., None]; d = c - y
    limit = np.where(d > 0, (1 - y) / np.where(d > 0, d, 1), np.where(d < 0, -y / np.where(d < 0, d, -1), 1))
    return y + d * np.maximum(0, np.minimum(1, limit.min(-1)))[..., None]
def shoulder(v, maximum):
    if maximum <= 1: return v
    a = (maximum - .8) / .2; t = (v - .8) / (maximum - .8)
    return np.where(v <= .8, v, .8 + .2 * a * t / (1 + (a - 1) * t))

def color_correction(c, p):
    exposure, contrast, shadows, highlights, blacks, whites, pivot, temperature, tint, saturation, vibrance, hue = p
    gains = np.exp2(np.array([.45 * temperature + .2 * tint, -.2 * tint, -.45 * temperature + .2 * tint]))
    c = c * gains / (gains @ LUMA) * 2 ** exposure
    before = luma(c); v = srgb(np.maximum(before, 0))
    v = shoulder(v, float(srgb(np.array(2.0 ** exposure))))
    if contrast != 1:
        inside = (v > 0) & (v < 1); safe = np.where(inside, v, .5)
        v = np.where(inside, 1 / (1 + (pivot / (1 - pivot)) ** (contrast - 1) * ((1 - safe) / safe) ** contrast), v)
    v = v + shadows * v * (1 - v) ** 2
    v = v + highlights * v * v * (1 - v)
    v = v + .12 * blacks * np.maximum(1 - v, 0) ** 4 + .12 * whites * np.maximum(v, 0) ** 4
    v = shoulder(v, 1 + .12 * whites)
    v = 1 - shoulder(1 - v, 1 - .12 * blacks)
    target = lin(v)
    c = np.where((before > 1e-7)[..., None], c * (target / np.where(before > 1e-7, before, 1))[..., None], target[..., None])
    perceptual = lab(np.maximum(c, 0)); chroma = np.hypot(perceptual[..., 1], perceptual[..., 2])
    h = hsv(srgb(np.maximum(c, 0)))
    skin = smoothstep(.025, .055, h[..., 0]) * (1 - smoothstep(.12, .16, h[..., 0])) * smoothstep(.08, .2, h[..., 1])
    vib = vibrance * (1 - np.clip(chroma / .25, 0, 1))
    if vibrance > 0: vib = vib * (1 - .6 * skin)
    if hue != 0:
        a = np.radians(hue); cs, sn = np.cos(a), np.sin(a)
        rotated = np.stack([perceptual[..., 0], cs * perceptual[..., 1] - sn * perceptual[..., 2], sn * perceptual[..., 1] + cs * perceptual[..., 2]], -1)
        turned = unlab(rotated); turned = turned * (target / np.maximum(luma(turned), 1e-7))[..., None]
        c = np.where((chroma > 1e-5)[..., None], turned, c)
    c = gamut(c)
    gain = saturation * (1 + vib); t = target[..., None]; delta = c - t
    ratio = np.where(delta > 0, delta / np.maximum(1 - t, 1e-7), -delta / np.maximum(t, 1e-7)).max(-1)
    gain = np.where(gain > 1, gain / np.sqrt(1 + (gain * gain - 1) * ratio * ratio), gain)
    return gamut(t + (c - t) * gain[..., None])

TINTS = [(.243137, .435294, .658824), (.85098, .643137, .254902), (.721569, .262745, .184314), (.431373, .560784, .305882), (.541176, .415686, .270588)]

def reference(kind, p, intensity, encoded, aux=None, values=None):
    c = lin(encoded / 255.0); y = luma(c); uv = coordinates(); x = p[:, 0]; aux = aux or {}
    values = np.zeros(8) if values is None else np.array(values); scale = min(W, H) / 1080.0
    def moved(uv): return (uv - .5) / values[2] + .5 + values[:2] * scale / np.array([W, H])
    if kind == 22:
        count = min(9, max(2, np.floor(x[0] + .5))); center = np.array([x[1], x[2]]); size = np.array([W, H])
        d = (uv - center) * size; axis = np.radians(x[3]) - np.pi / 2; span = 2 * np.pi / count
        angle = np.mod((np.arctan2(d[..., 1], d[..., 0]) - axis) / (2 * np.pi), 1) * 2 * np.pi
        sector = np.minimum(np.floor(angle / span), count - 1); local = angle - sector * span
        if count % 2 == 0: local = np.where(sector % 2 == 1, span - local, local)
        mapped = axis + local; r = np.hypot(d[..., 0], d[..., 1])[..., None]
        point = np.where(((sector != 0) & ((d ** 2).sum(-1) >= 1e-8))[..., None], center + r * np.stack([np.cos(mapped), np.sin(mapped)], -1) / size, uv)
        if x[6] != 0:
            a = -np.radians(x[6]); off = (point - center) * size
            point = center + np.stack([np.cos(a) * off[..., 0] - np.sin(a) * off[..., 1], np.sin(a) * off[..., 0] + np.cos(a) * off[..., 1]], -1) / size
        point = point - np.array([x[4], x[5]])
        inside = np.all((point >= 0) & (point <= 1), -1)[..., None]
        point = np.where(inside, point, 1 - np.abs(2 * np.mod(point * .5, 1) - 1))
        o = sample(encoded, point)
        return srgb(np.clip(o if intensity >= 1 else mix(c, o, intensity), 0, 1))
    if kind == 23:
        reach = .12 * min(W, H) / np.array([W, H])
        o = np.stack([sample(encoded, uv - np.array([x[2 * i], x[2 * i + 1]]) * reach)[..., i] for i in range(3)], -1)
    elif kind == 24: o = lin(1 - srgb(np.clip(c, 0, 1)))
    elif kind == 20: o = color_correction(c, x)
    elif kind == 0:
        tint = lin(np.array(TINTS[int(x[0] + .5)])); l = curve(y, x[2])[..., None]
        o = l * tint if x[1] < .5 else mix(tint * .15, 1, smoothstep(0, 1, l))
    elif kind == 1:
        h = hsv(srgb(c)); skin = (smoothstep(.03, .055, h[..., 0]) * (1 - smoothstep(.115, .15, h[..., 0])) * smoothstep(.12, .3, h[..., 1]))[..., None]
        separation = c - (c[..., [1, 2, 0]] + c[..., [2, 0, 1]]) * .5
        o = c + separation * (x[0] - 1) * (1 - .65 * skin)
        red = (1 - smoothstep(15 / 360, 25 / 360, hue_distance(h[..., 0], 0)))[..., None]
        o = o - np.array([0, 1, 1]) * red * x[1] * .15 * (1 - skin)
        l = luma(o)[..., None]; o = o + (l * l * (3 - 2 * l) - l) * .14
        o = o * np.array([1 + x[2] * .06, 1, 1 - x[2] * .06])
        high = o.max(-1)[..., None]; o = np.where(high > .9, .9 + (o - .9) / (1 + np.maximum(0, high - .9)), o)
        o = mix(o, c, skin * .35)
    elif kind == 2:
        yy = y[..., None]; overlay = np.where(yy < .5, 2 * c * yy, 1 - 2 * (1 - c) * (1 - yy))
        bleached = mix(yy, overlay, .5); bleached = mix(luma(bleached)[..., None], bleached, 1 - .3 * x[0]); o = mix(c, bleached, x[0])
    elif kind == 3:
        h = hsv(srgb(c)); dark = x[0]; yy = y[..., None]
        o = mix(yy, c, .4) * 2 ** (-3 * dark); o = o * mix(np.ones(3), np.array([.55, .7, 1]), x[1])
        sky = (1 - smoothstep(.25, .38, uv[..., 1])) * smoothstep(.5, .85, y) * (1 - smoothstep(.08, .18, hue_distance(h[..., 0], .61)))
        o = o * (1 - .5 * sky)[..., None]
        if x[2] > .5: o = mix(o, yy * 2 ** (-.7 * dark), (smoothstep(.94, .99, y) * .7)[..., None])
        o = o * (1 - .15 * smoothstep(.2, .75, np.hypot(uv[..., 0] - .5, uv[..., 1] - .5)))[..., None]
    elif kind == 5:
        l = scurve(srgb(c @ np.array([.4, .5, .1])), x[0]); l = lin(l * (1 - 2 * x[1] * (1 - l)))[..., None]
        o = np.repeat(l, 3, -1)
        if x[3] > .5: o = o * mix(np.array([.94, .97, 1]), 1, l)
    elif kind == 6:
        perceptual = srgb(y); l = scurve(perceptual, x[0]); l = lin(mix(l, smoothstep(.35, .65, perceptual), .4))
        d = (uv - .5) * 2
        if x[2] > .5: d[..., 0] *= W / H
        v = np.clip(1 - np.clip(np.hypot(d[..., 0], d[..., 1]) / 1.25, 0, 1) ** 2.5, 0, 1)
        o = np.repeat((l * mix(1, v, x[1]))[..., None], 3, -1)
        if x[3] > .5: o = o * lin(np.array([.82, .84, .52]))
    elif kind == 7:
        labs = np.array([lab(lin(np.array(col))) for col in QUANT["palette"]]); n = len(labs)
        cc = sample(aux["aux"], uv) if x[3] > 0 else c
        yy = luma(cc)[..., None]; cc = cc + .2 * (yy * yy * (3 - 2 * yy) - yy)
        v = lab(np.clip(cc, 0, 1))
        if x[4] > .5:
            spread = sum(np.linalg.norm(labs[i] - labs[i - 1]) for i in range(1, n)) / max(1, n - 1)
            qx, qy = grid(W, H)
            v = v.copy(); v[..., 0] += (bayer(qx, qy, 4 if x[4] < 1.5 else 8) - .5) * spread
        if x[1] > .5 or 1 <= x[2] <= 3:
            l = luma(np.clip(unlab(v), 0, 1)); best = sum((l >= b).astype(int) for b in QUANT["bands"][:n - 1])
            o = lin(np.repeat((best / max(1, n - 1))[..., None], 3, -1)) if x[1] > .5 else unlab(labs[best])
        else:
            d = ((v[..., None, :] - labs[None, None]) ** 2).sum(-1); o = unlab(labs[np.argmin(d, -1)])
    elif kind == 9:
        target = values[:2]; point = (uv - target) / values[2] + target
        o = sum(sample(encoded, point + (point - target) * values[3] * (i / 7 - .5)) for i in range(8)) / 8
    elif kind == 12:
        o = sample(encoded, moved(uv))
        if x[2] > .5:
            o = mix(luma(o)[..., None], o, .95); o = o * (1 - .12 * smoothstep(.25, .7, np.hypot(uv[..., 0] - .5, uv[..., 1] - .5)))[..., None]; o = np.clip(o, 0, 1)
    elif kind == 14:
        o = sample(encoded, moved(uv)) * values[3]
    elif kind == 11:
        ratio = [2.39, 2.20, 1.85, 1.33][int(x[0] + .5)]; aspect = W / H
        bar = (np.abs(uv[..., 1] - .5) > .5 * aspect / ratio) if aspect < ratio else (np.abs(uv[..., 0] - .5) > .5 * ratio / aspect)
        flare = [np.array([.35, .6, 1]), np.array([1, .6, .25]), np.ones(3)][int(x[3] + .5)]
        o = c + sample(aux["aux"], uv) * flare * x[2]
        o = np.where(bar[..., None], sample(aux["background"], uv) * .6 if x[1] > .5 else 0, o)
    elif kind == 15:
        flash = [np.ones(3), np.array([1, .8, .6]), np.array([.65, .8, 1])][int(x[3] + .5)]
        o = c + (sample(aux["aux"], uv) * .65 + sample(aux["extra"], uv) * .35) * x[0] + sample(aux["background"], uv)[..., :1] * np.array([1, .25, .1]) * x[1] + flash * x[2] * .08 * (1 - y)[..., None]
    elif kind == 16:
        point = moved(uv); o = mix(sample(encoded, point), sample(aux["aux"], point), x[2]); o = o / (1 + np.maximum(o - .85, 0) * .15)
        if x[5] > .5: o = np.repeat(luma(o)[..., None], 3, -1)
        o = np.clip(o, 0, 1)
        if x[4] > .5: o = o * gate(uv, .035)[..., None]
    elif kind == 17:
        point = moved(uv); offset = (uv - .5) * scale / np.array([W, H])
        o = np.stack([sample(aux["aux"], point + offset)[..., 0], sample(aux["aux"], point)[..., 1], sample(aux["aux"], point - offset)[..., 2]], -1)
        o = mix(o, .06 + o * .88, x[0]); o = o * np.array([1.06, 1, .92]); o[..., 0] += o[..., 1] * .025 * x[0]; o = mix(luma(o)[..., None], o, .8)
        o = np.clip(o, 0, 1)
        edge = uv[..., 0] if values[4] > .5 else 1 - uv[..., 0]
        o = o + np.array([1, .22, .035]) * (np.exp(-edge * 7) * values[3] * x[4])[..., None]
        if x[5] > .5: o = o * gate(uv, .055)[..., None]
    o = np.clip(o, 0, 1)
    return srgb(np.clip(o if intensity >= 1 else mix(c, o, intensity), 0, 1))

def params(values):
    p = np.zeros((parameters, 4)); p[:len(values), 0] = values; return p

CASES = [
    ("Color Correction neutral", 20, [0, 1, 0, 0, 0, 0, .5, 0, 0, 1, 0, 0], 1),
    ("Color Correction graded", 20, [.6, 1.4, .3, -.4, .5, -.3, .45, .4, -.3, 1.5, .5, 25], 1),
    ("Color Correction muted", 20, [-.5, .7, -.2, .3, -.6, .4, .6, -.5, .2, .4, -.3, -60], .7),
    ("Silent Tint", 0, [1, 0, 1.3], 1), ("Silent Tint lifted", 0, [3, 1, .8], .6),
    ("Three-Strip", 1, [1.5, .6, .5], 1), ("Bleach Bypass", 2, [.8], 1), ("Day for Night", 3, [.6, .7, 1], 1),
    ("Noir", 5, [1.8, .25, 0, 1], 1), ("Expressionism", 6, [2.2, .7, 1, 1], 1),
    ("RGB Offset", 23, [.1, 0, -.05, .08, 0, -.12], 1), ("Negative", 24, [], 1),
    ("Kaleidoscope 6", 22, [6, .5, .5, 0, 0, 0, 0], 1), ("Kaleidoscope 5 turned", 22, [5, .4, .6, 30, .05, -.03, 20], 1),
    ("Widescreen black bars", 11, [0, 0, .6, 1, 0], 1), ("Widescreen blur fill", 11, [3, 1, .4, 0, 0], .8),
    ("Halation", 15, [.7, .6, .4, 1], 1), ("16mm", 16, [.5, 1, .4, .5, 1, 0], 1, [3.0, -2.0, 1.006, 0, 0, 0, 0, 0]),
    ("16mm B&W", 16, [.5, 2, .0, .5, 0, 1], .7, [-1.5, 4.0, 1.006, 0, 0, 0, 0, 0]),
    ("Quantization scene", 7, [4, 0, 0, 0, 0], 1), ("Quantization Pop bands dither", 7, [4, 0, 1, 0, 1], 1),
    ("Quantization B&W 8x8 smoothed", 7, [4, 1, 0, .5, 2], .8),
    ("Crash zoom", 9, [2.5, .18, 1, 0, .6, .3, .6], 1, [.3, .6, 1.8, .05, 0, 0, 0, 0]),
    ("Freeze print", 12, [3, .05, 1], 1, [0, 0, 1.03, 0, 0, 0, 0, 0]),
    ("Undercranking", 14, [16, 1.25, .5, .4], 1, [2.5, -3.0, 1.01, 1.05, 0, 0, 0, 0]),
    ("Super 8 leak", 17, [.5, .7, .5, 1, .8, 1], 1, [2.0, 3.0, 1.012, .8, 1, 0, 0, 0]),
]

def rgba(image):
    return np.concatenate([image.astype(np.uint8), np.full(image.shape[:2] + (1,), 255, np.uint8)], -1)

def smooth(w, h, seed):
    """A smooth colourful picture for auxiliary inputs."""
    x, y = grid(w, h); rng = np.random.default_rng(seed); phase = rng.uniform(0, 6, 3)
    return np.round(np.stack([.5 + .45 * np.sin(x / w * 5 + phase[i] + y / h * 3 * (i + 1)) for i in range(3)], -1) * 255)

def main():
    image = picture()
    files = {}
    def store(name, data):
        path = os.path.join(WORK, name + ".rgba"); rgba(data).tofile(path); files[name] = (path, data.shape[1], data.shape[0]); return path
    store("input", image)
    auxiliary = {"aux": smooth(W // 4, H // 4, 1), "background": smooth(W // 4, H // 4, 2), "extra": smooth(W // 4, H // 4, 3)}
    for name, data in auxiliary.items(): store(name, data)
    # A flat grain tile (every channel, alpha included, at zero): grain is left out of the comparison.
    lattice_path = os.path.join(WORK, "lattice.rgba"); np.full((2, 2, 4), 128, np.uint8).tofile(lattice_path); files["lattice"] = (lattice_path, 2, 2)
    spec = {"vertex": "attribute vec4 position; void main() { gl_Position = position; }", "cases": []}
    checks = []
    def texture(name, as_name=None, repeat=False):
        path, w, h = files[name]; return {"name": as_name or name, "file": path, "width": w, "height": h, "repeat": repeat}
    for case in CASES:
        name, kind, values, intensity = case[:4]; frame = case[4] if len(case) > 4 else [0, 0, 1, 0, 0, 0, 0, 0]
        textures = [texture("input", "image"), texture("lattice", repeat=True)] + [texture(n) for n in auxiliary]
        uniforms = [{"name": "size", "type": "2f", "value": [W, H]}, {"name": "p", "type": "4fv", "value": params(values).flatten().tolist()},
                    {"name": "time", "type": "1f", "value": .5}, {"name": "intensity", "type": "1f", "value": intensity},
                    {"name": "latticeOffset", "type": "2f", "value": [0, 0]}, {"name": "frameValues", "type": "4fv", "value": frame}]
        if kind == 7: uniforms += quant_uniforms()
        out = os.path.join(WORK, "%s.rgba" % name.replace(" ", "-"))
        spec["cases"].append({"name": name, "fragment": "#define KIND %d\n" % kind + fragment, "width": W, "height": H, "textures": textures, "uniforms": uniforms, "output": out})
        checks.append((name, out, W, H, lambda kind=kind, values=values, intensity=intensity, frame=frame:
                       np.round(reference(kind, params(values), intensity, image.astype(float), auxiliary, frame) * 255), kind == 20 and "neutral" in name))
    # The auxiliary passes.
    w, h = W // 4, H // 4
    for name, shader, extra, ref in [
        ("Reduce", REDUCE, [], lambda: area(image.astype(float), w, h)),
        ("Highlights", HIGHLIGHTS, [{"name": "threshold", "type": "2f", "value": [.6, .1]}], lambda: area(image.astype(float), w, h, (.6, .1)))]:
        out = os.path.join(WORK, name + ".rgba")
        spec["cases"].append({"name": name, "fragment": shader, "width": w, "height": h, "textures": [texture("input", "image")],
                              "uniforms": [{"name": "size", "type": "2f", "value": [w, h]}, {"name": "sourceSize", "type": "2f", "value": [W, H]}] + extra, "output": out})
        checks.append((name, out, w, h, lambda ref=ref: np.round(ref() * 255), False))
    BILATERAL = HEADER + source("BILATERAL")
    for name, sigma, direction in [("Bilateral horizontal 1.5", 1.5, (1, 0)), ("Bilateral vertical 4", 4.0, (0, 1))]:
        out = os.path.join(WORK, name.replace(" ", "-") + ".rgba")
        spec["cases"].append({"name": name, "fragment": BILATERAL, "width": W, "height": H, "textures": [texture("input", "image")],
                              "uniforms": [{"name": "size", "type": "2f", "value": [W, H]}, {"name": "bilateral", "type": "4f", "value": [sigma, direction[0], direction[1], 0]}], "output": out})
        checks.append((name, out, W, H, lambda sigma=sigma, direction=direction: np.round(bilateral(image.astype(float), sigma, direction) * 255), False))
    for name, sigma, horizontal in [("Blur horizontal 2.5", 2.5, True), ("Blur vertical 7", 7.0, False), ("Blur 0.6", .6, True)]:
        centre, pairs = gaussian_weights(sigma); flat = [v for pair in pairs for v in pair]; flat += [0] * (48 - len(flat))
        out = os.path.join(WORK, name.replace(" ", "-") + ".rgba")
        spec["cases"].append({"name": name, "fragment": BLUR, "width": W, "height": H, "textures": [texture("input", "image")],
                              "uniforms": [{"name": "size", "type": "2f", "value": [W, H]}, {"name": "header", "type": "4f", "value": [centre, len(pairs), 1 if horizontal else 0, 0 if horizontal else 1]},
                                           {"name": "pairs", "type": "2fv", "value": flat}], "output": out})
        checks.append((name, out, W, H, lambda sigma=sigma, horizontal=horizontal: np.round(blurred(image.astype(float), sigma, horizontal) * 255), False))
    path = os.path.join(WORK, "cases.json"); json.dump(spec, open(path, "w"))
    for case in spec["cases"]:
        for leftover in (case["output"], case["output"] + ".error"):
            if os.path.exists(leftover): os.remove(leftover)
    subprocess.run(["node", os.path.join(HERE, "render-webgl.js"), path], check=True)
    failed = False
    for name, out, w, h, ref, exact in checks:
        if not os.path.exists(out):
            print("FAIL %-28s %s" % (name, open(out + ".error").read() if os.path.exists(out + ".error") else "no output")); failed = True; continue
        gpu = np.fromfile(out, np.uint8).reshape(h, w, 4)[..., :3].astype(int)
        expected = ref().astype(int)
        diff = np.abs(gpu - expected); worst = diff.max(); p99 = np.percentile(diff, 99.5)
        # Shader float precision and 8-bit rounding: a level or two; a few pixels on hard edges may differ more.
        ok = p99 <= 2 and worst <= 12 and (not exact or worst <= 1)
        failed |= not ok
        print("%s %-28s max %3d  99.5%% %4.1f  mean level %5.1f" % ("ok  " if ok else "FAIL", name, worst, p99, gpu.mean()))
    sys.exit(1 if failed else 0)

main()
