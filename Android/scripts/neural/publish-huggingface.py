"""Upload the prepared folder (CI artifact huggingface-upload) to a public
Hugging Face model repository as one commit, then read it back and check every
file's size and SHA-256 against SHA256SUMS. Prints the commit to pin in
YouRec/StudioModelPack.kt. Needs HF_TOKEN with write access.
Usage: publish-huggingface.py <owner/name> <folder>"""
import hashlib, json, os, sys
from pathlib import Path
from huggingface_hub import HfApi, hf_hub_download

repository, folder = sys.argv[1], Path(sys.argv[2])
token = os.environ.get("HF_TOKEN")
if not token: sys.exit("HF_TOKEN is not set: add it as a repository secret")
api = HfApi(token=token)
api.create_repo(repository, repo_type="model", private=False, exist_ok=True)
if api.model_info(repository).private: sys.exit(repository + " is private: the app can only download from a public repository")
commit = api.upload_folder(repo_id=repository, repo_type="model", folder_path=str(folder),
                           commit_message="YouRec for Android: LavaSR and VoiceFixer as ONNX")
revision = commit.oid
sums = dict(reversed(line.split()) for line in (folder / "SHA256SUMS").read_text().splitlines())
for name, expected in sums.items():
    path = Path(hf_hub_download(repository, name, revision=revision, cache_dir="work/hf-check"))
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    print(json.dumps({"file": name, "bytes": path.stat().st_size, "sha256": digest}))
    if digest != expected: sys.exit("published " + name + " differs from the prepared file")
print(json.dumps({"published": repository, "revision": revision}))
