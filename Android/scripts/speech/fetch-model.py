"""Download Qwen/Qwen3-ASR-0.6B as qwen-asr's download_model.sh does, at a
pinned revision when given, and print every file's size and SHA-256: the
values YouRec/SubtitleModelPack.kt pins. Usage: fetch-model.py <folder> [revision]"""
import hashlib, json, sys
from pathlib import Path
from huggingface_hub import HfApi, hf_hub_download
folder = Path(sys.argv[1]); folder.mkdir(parents=True, exist_ok=True)
repository = "Qwen/Qwen3-ASR-0.6B"
revision = sys.argv[2] if len(sys.argv) > 2 else HfApi().model_info(repository).sha
print(json.dumps({"repository": repository, "revision": revision}))
for name in ["config.json", "generation_config.json", "model.safetensors", "vocab.json", "merges.txt"]:
    path = Path(hf_hub_download(repository, name, revision=revision, local_dir=folder))
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        while chunk := handle.read(1 << 20): digest.update(chunk)
    print(json.dumps({"file": name, "bytes": path.stat().st_size, "sha256": digest.hexdigest()}))
