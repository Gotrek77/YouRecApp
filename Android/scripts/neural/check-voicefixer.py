"""The app's VoiceFixer against upstream's own VoiceFixer.restore_inmem on the
same input: both log-mel spectrograms, compared. A vocoder does not keep the
input's waveform, so the comparison is spectral.
Usage: check-voicefixer.py <voicefixer work folder> <noisy.f32 (48 kHz mono)> <VoiceFixer.f32 (app, 48 kHz stereo)>"""
import os, sys
from pathlib import Path
import numpy as np
from scipy.signal import resample_poly

work, noisy_path, app_path = Path(sys.argv[1]).resolve(), sys.argv[2], sys.argv[3]
expand = os.path.expanduser
os.path.expanduser = lambda p: str(work) if str(p) == "~" else expand(p)
sys.path.insert(0, str(work / "upstream"))
import librosa
import torch
from voicefixer import VoiceFixer

torch.set_num_threads(4)
noisy = resample_poly(np.fromfile(noisy_path, np.float32), 147, 160).astype(np.float32)
app = resample_poly(np.fromfile(app_path, np.float32)[0::2], 147, 160).astype(np.float32)
upstream = VoiceFixer().restore_inmem(noisy, cuda=False, mode=0).astype(np.float32).reshape(-1)
n = min(len(app), len(upstream), len(noisy))
def logmel(x):
    m = librosa.feature.melspectrogram(y=x[:n], sr=44100, n_fft=2048, hop_length=441, n_mels=128)
    return np.maximum(10 * np.log10(m + 1e-10), -80)
def similarity(a, b): return float(np.corrcoef(a.reshape(-1), b.reshape(-1))[0, 1])
reference, ours, original = logmel(upstream), logmel(app), logmel(noisy)
rms = lambda x: float(np.sqrt(np.mean(x[:n] ** 2)))
print("log-mel similarity: app vs upstream %.3f; noisy vs upstream %.3f; mean |difference| app vs upstream %.1f dB; level app %.4f upstream %.4f"
      % (similarity(ours, reference), similarity(original, reference), float(np.mean(np.abs(ours - reference))), rms(app), rms(upstream)))
assert similarity(ours, reference) > 0.9, "the app's VoiceFixer differs from upstream's"
assert 0.5 < rms(app) / rms(upstream) < 2, "the app's VoiceFixer level differs from upstream's"
print("PASS: VoiceFixer matches upstream's restore")
