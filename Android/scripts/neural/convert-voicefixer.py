"""VoiceFixer's analysis network and vocoder as ONNX, after the iPhone app's
scripts/convert-voicefixer.py (same pinned code and checkpoints, same module,
same fixed 256-frame mel input). Float32 throughout.

Python 3.12, torch 2.7.0 (CPU), numpy, torchlibrosa 0.1.0, librosa, scipy,
matplotlib, progressbar 2.5, pyyaml, onnx, onnxruntime.
Usage: python convert-voicefixer.py <work folder> <output VoiceFixer.onnx>"""
import hashlib, json, os, subprocess, sys, urllib.request
from pathlib import Path

work, destination = Path(sys.argv[1]).resolve(), Path(sys.argv[2]).resolve()
work.mkdir(parents=True, exist_ok=True)
source = work / "upstream"
commit = "aae2253c85f97a87b844b6832384de249c23ab37"
if not source.exists():
    subprocess.run(["git", "clone", "https://github.com/haoheliu/voicefixer.git", str(source)], check=True)
subprocess.run(["git", "-C", str(source), "checkout", "--detach", commit], check=True)
files = [
    ("vf.ckpt", "analysis_module/checkpoints/vf.ckpt", "748411b70089cadf34a6c11054f95f3a454e614af562c23b13a82f6cb413109f"),
    ("model.ckpt-1490000_trimed.pt", "synthesis_module/44100/model.ckpt-1490000_trimed.pt", "9410d0b528c10a251ae947bd299d1939b0b3247df680c81c4164e94f5d87dc45")]
for name, relative, digest in files:
    path = work / ".cache/voicefixer" / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    if not path.exists():
        urllib.request.urlretrieve("https://zenodo.org/records/5600188/files/" + name + "?download=1", path)
    if hashlib.sha256(path.read_bytes()).hexdigest() != digest:
        raise RuntimeError("Invalid upstream checkpoint: " + name)
expand = os.path.expanduser
os.path.expanduser = lambda p: str(work) if str(p) == "~" else expand(p)
sys.path.insert(0, str(source))
import numpy as np
import onnxruntime
import torch
from voicefixer import VoiceFixer

torch.set_num_threads(4)
torch.manual_seed(0)
model = VoiceFixer().eval()


class Network(torch.nn.Module):
    def __init__(self):
        super().__init__()
        self.generator = model._model.generator
        self.vocoder = model._model.vocoder

    def forward(self, mel):
        restored = torch.pow(10, torch.clamp(self.generator(mel, mel)["mel"], max=5))
        return self.vocoder(restored).squeeze(1)


network = Network().eval()
example = torch.rand(1, 1, 256, 128)
with torch.inference_mode():
    reference = network(example).numpy()
torch.onnx.export(network, (example,), str(destination), input_names=["mel"], output_names=["audio"],
                  opset_version=17, dynamo=False, do_constant_folding=True)
session = onnxruntime.InferenceSession(str(destination), providers=["CPUExecutionProvider"])
prediction = session.run(None, {"mel": example.numpy()})[0]
error = float(np.max(np.abs(prediction - reference)))
snr = float(10 * np.log10((reference ** 2).sum() / max(((prediction - reference) ** 2).sum(), 1e-30)))
bank = model._model.mel.fb.detach().numpy().astype("<f4").tobytes()
print(json.dumps({"conversion": "VoiceFixer", "output_shape": list(prediction.shape), "bytes": destination.stat().st_size,
                  "sha256": hashlib.sha256(destination.read_bytes()).hexdigest(), "max_absolute_error": error, "snr_db": snr,
                  "mel_bank_sha256": hashlib.sha256(bank).hexdigest()}))
assert prediction.shape == (1, 260 * 441), "unexpected vocoder output length"
assert snr > 60, "ONNX Runtime differs from PyTorch"
