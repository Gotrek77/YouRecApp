"""LavaSR v2's spectral network as ONNX, after the iPhone app's
scripts/convert-lavasr.py (same checkpoint, same module, same fixed shape);
the DSP stays outside, in the iPhone app's LavaDSP.cpp.

Python 3.12, torch 2.7.0 (CPU), vocos 0.1.0, onnx, onnxruntime, huggingface_hub.
Usage: python convert-lavasr.py <work folder> <output LavaSRv2.onnx>
The checkpoint is found in YatharthS/LavaSR enhancer_v2 by its SHA-256 and
checked before loading (weights_only=True)."""
import hashlib, json, sys
from pathlib import Path

import numpy as np
import onnxruntime
import torch
import yaml
from huggingface_hub import HfApi, hf_hub_download
from vocos import Vocos

work, destination = Path(sys.argv[1]), Path(sys.argv[2])
work.mkdir(parents=True, exist_ok=True)
expected = "d100db961b2c125d77a52a12215c689e44cd9926a72f117513395b7e25e6de12"
repository = "YatharthS/LavaSR"
api = HfApi()
revision = api.model_info(repository).sha
files = [f for f in api.list_repo_files(repository, revision=revision) if f.startswith("enhancer_v2/")]
print(json.dumps({"repository": repository, "revision": revision, "files": files}))
weights = config = None
for name in files:
    path = Path(hf_hub_download(repository, name, revision=revision, cache_dir=work))
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    print(json.dumps({"file": name, "bytes": path.stat().st_size, "sha256": digest}))
    if digest == expected: weights = path
    if name.endswith((".yaml", ".yml")): config = path
assert weights is not None, "no enhancer_v2 file has the checkpoint's published SHA-256"
assert config is not None, "no configuration in enhancer_v2"

torch.set_num_threads(4)
torch.manual_seed(0)
configuration = yaml.safe_load(config.read_text())
for key in ["f_min", "f_max", "norm", "mel_scale"]:
    configuration["feature_extractor"]["init_args"].pop(key, None)
compatible = work / "conversion-config.yaml"
compatible.write_text(yaml.safe_dump(configuration))
vocos = Vocos.from_hparams(str(compatible))
vocos.load_state_dict(torch.load(weights, map_location="cpu", weights_only=True))


class SpectralNetwork(torch.nn.Module):
    def __init__(self):
        super().__init__()
        self.backbone = vocos.backbone
        self.head = vocos.head.out

    def forward(self, mel):
        spectrum = self.head(self.backbone(mel)).transpose(1, 2)
        magnitude, phase = spectrum.chunk(2, dim=1)
        magnitude = torch.clamp(torch.exp(magnitude), max=1000)
        return magnitude * torch.cos(phase), magnitude * torch.sin(phase)


network = SpectralNetwork().eval()
example = torch.randn(1, 80, 375) - 5
with torch.inference_mode():
    reference = network(example)
torch.onnx.export(network, (example,), str(destination), input_names=["mel"], output_names=["real", "imaginary"],
                  opset_version=17, dynamo=False, do_constant_folding=True)
session = onnxruntime.InferenceSession(str(destination), providers=["CPUExecutionProvider"])
prediction = session.run(None, {"mel": example.numpy()})
errors = {name: float(np.max(np.abs(p - r.numpy()))) for name, p, r in zip(["real", "imaginary"], prediction, reference)}
scale = float(max(np.max(np.abs(r.numpy())) for r in reference))
print(json.dumps({"conversion": "LavaSR", "bytes": destination.stat().st_size,
                  "sha256": hashlib.sha256(destination.read_bytes()).hexdigest(), "max_absolute_error": errors, "output_scale": scale}))
assert max(errors.values()) < 1e-3 * max(1.0, scale), "ONNX Runtime differs from PyTorch"
