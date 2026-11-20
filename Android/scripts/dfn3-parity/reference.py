"""DeepFilterNet3 over a whole sequence, the way upstream's PyTorch model is
written (DfNet.forward: features shifted by the lookahead, deep filter by
unfolding), with numpy's FFT and the same ONNX graphs. An independent check
of the hop-by-hop Kotlin DSP: reference.py <graphs> <DSP json> <in.f32> <out.f32>"""
import json, sys
import numpy as np
import onnxruntime as ort

graphs, dsp_path, source, target = sys.argv[1:5]
dsp = json.load(open(dsp_path))
fb = np.array(dsp["erb_fb"], np.float32).reshape(481, 32)
inv = np.array(dsp["erb_inv_fb"], np.float32).reshape(32, 481)
x = np.fromfile(source, np.float32); L = len(x)
N, H = 960, 480
window = np.sin(0.5 * np.pi * np.sin(0.5 * np.pi * (np.arange(N) + 0.5) / (N // 2)) ** 2).astype(np.float32)
wnorm = np.float32(1.0 / (N * N) * (2 * H))
T = -(-L // H) + 3
padded = np.concatenate([np.zeros(H, np.float32), x, np.zeros(T * H - L, np.float32)])
X = np.fft.rfft(np.stack([padded[t * H:t * H + N] * window for t in range(T)]), axis=1).astype(np.complex64) * wnorm
erb = 10 * np.log10((np.abs(X) ** 2) @ fb + 1e-10)
mean = np.array(dsp["mean_norm_state"], np.float32); unit = np.array(dsp["unit_norm_state"], np.float32); a = 0.99
feat_erb = np.zeros_like(erb); feat_spec = np.zeros((T, 96), np.complex64)
for t in range(T):
    mean = erb[t] * (1 - a) + mean * a; feat_erb[t] = (erb[t] - mean) / 40
    unit = np.abs(X[t, :96]) * (1 - a) + unit * a; feat_spec[t] = X[t, :96] / np.sqrt(np.maximum(unit, 1e-10))
feat_erb = np.concatenate([feat_erb[2:], np.zeros((2, 32), np.float32)])
feat_spec = np.concatenate([feat_spec[2:], np.zeros((2, 96), np.complex64)])
session = lambda name: ort.InferenceSession(f"{graphs}/{name}.onnx", providers=["CPUExecutionProvider"])
e0, e1, e2, e3, emb, c0, _ = session("enc").run(None, {"feat_erb": feat_erb[None, None].astype(np.float32),
    "feat_spec": np.stack([feat_spec.real, feat_spec.imag])[None].astype(np.float32)})
mask = session("erb_dec").run(None, {"emb": emb, "e3": e3, "e2": e2, "e1": e1, "e0": e0})[0][0, 0]
coefs = session("df_dec").run(None, {"emb": emb, "c0": c0})[0][0].reshape(T, 96, 5, 2)
coefs = coefs[..., 0] + 1j * coefs[..., 1]
Y = X * (mask @ inv)
Xp = np.concatenate([np.zeros((2, 481), np.complex64), X, np.zeros((2, 481), np.complex64)])
Y[:, :96] = np.einsum("tnf,tfn->tf", np.stack([Xp[t:t + 5, :96] for t in range(T)]), coefs)
y = np.zeros((T + 1) * H, np.float32)
for t in range(T):
    y[t * H:t * H + N] += np.fft.irfft(Y[t] / wnorm, n=N).astype(np.float32) * window
y[H:H + L].astype(np.float32).tofile(target)
