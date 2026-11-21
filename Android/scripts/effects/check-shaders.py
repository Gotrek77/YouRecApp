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
fragment = re.search(r'val FRAGMENT = """(.*?)"""', kotlin, re.S).group(1)
parameters = int(re.search(r"const val PARAMETERS = (\d+)", kotlin).group(1))
lattice = int(re.search(r"const val latticeSize = (\d+)", open(os.path.join(HERE, "..", "..", "YouRec", "VideoEffectRendering.kt")).read()).group(1))
fragment = fragment.replace("$PARAMETERS", str(parameters)).replace("${VideoEffectRendering.latticeSize}", str(lattice))
assert "$" not in fragment, "unresolved template in the shader"

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
    x = np.clip(uv[..., 0] * W - .5, 0, W - 1); y = np.clip(uv[..., 1] * H - .5, 0, H - 1)
    x0 = np.floor(x).astype(int); y0 = np.floor(y).astype(int); x1 = np.minimum(x0 + 1, W - 1); y1 = np.minimum(y0 + 1, H - 1)
    fx = (x - x0)[..., None]; fy = (y - y0)[..., None]; e = encoded / 255.0
    value = mix(mix(e[y0, x0], e[y0, x1], fx), mix(e[y1, x0], e[y1, x1], fx), fy)
    return lin(value)

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

def reference(kind, p, intensity, encoded):
    c = lin(encoded / 255.0); y = luma(c); uv = coordinates(); x = p[:, 0]
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
]

def main():
    image = picture()
    rgba = np.concatenate([image, np.full((H, W, 1), 255, np.uint8)], -1)
    source = os.path.join(WORK, "input.rgba"); rgba.tofile(source)
    spec = {"vertex": "attribute vec4 position; void main() { gl_Position = position; }", "cases": []}
    for name, kind, values, intensity in CASES:
        spec["cases"].append({"name": name, "fragment": "#define KIND %d\n" % kind + fragment, "width": W, "height": H, "input": source,
                              "output": os.path.join(WORK, "%s.rgba" % name.replace(" ", "-")), "p": params(values).flatten().tolist(),
                              "time": 0.5, "intensity": intensity})
    path = os.path.join(WORK, "cases.json"); json.dump(spec, open(path, "w"))
    for case in spec["cases"]:
        for leftover in (case["output"], case["output"] + ".error"):
            if os.path.exists(leftover): os.remove(leftover)
    subprocess.run(["node", os.path.join(HERE, "render-webgl.js"), path], check=True)
    failed = False
    for (name, kind, values, intensity), case in zip(CASES, spec["cases"]):
        if not os.path.exists(case["output"]):
            print("FAIL %-28s %s" % (name, open(case["output"] + ".error").read() if os.path.exists(case["output"] + ".error") else "no output")); failed = True; continue
        gpu = np.fromfile(case["output"], np.uint8).reshape(H, W, 4)[..., :3].astype(int)
        ref = np.round(reference(kind, params(values), intensity, image.astype(float)) * 255).astype(int)
        diff = np.abs(gpu - ref); worst = diff.max(); p99 = np.percentile(diff, 99.5); changed = np.abs(gpu - image).mean()
        # Shader float precision and 8-bit rounding: a level or two; a few pixels on hard edges may differ more.
        ok = p99 <= 2 and worst <= 12 and (kind != 20 or name != "Color Correction neutral" or worst <= 1)
        failed |= not ok
        print("%s %-28s max %3d  99.5%% %4.1f  mean change %5.1f" % ("ok  " if ok else "FAIL", name, worst, p99, changed))
    sys.exit(1 if failed else 0)

main()
