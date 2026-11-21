"""Download the networks exactly as the app does (repository, revision, sizes
and SHA-256 read from YouRec/StudioModelPack.kt) and check every byte.
Usage: check-download.py [YouRec/StudioModelPack.kt]"""
import hashlib, re, sys, urllib.request
source = open(sys.argv[1] if len(sys.argv) > 1 else "YouRec/StudioModelPack.kt").read()
repository = re.search(r'val repository: String\? = "([^"]+)"', source).group(1)
revision = re.search(r'val revision: String\? = "([0-9a-f]{40})"', source).group(1)
entries = re.findall(r'Entry\("([^"]+)", (\d+), "([0-9a-f]{64})"\)', source)
assert entries, "no pinned files"
for asset, size, digest in entries:
    url = f"https://huggingface.co/{repository}/resolve/{revision}/{asset.rsplit('/', 1)[-1]}"
    hasher, count = hashlib.sha256(), 0
    with urllib.request.urlopen(url, timeout=60) as response:
        while chunk := response.read(1 << 20):
            hasher.update(chunk); count += len(chunk)
    ok = count == int(size) and hasher.hexdigest() == digest
    print(f"{asset}: {count} bytes, sha256 {hasher.hexdigest()} -> {'ok' if ok else 'MISMATCH'}")
    assert ok, url + " differs from the pinned file"
print(f"PASS: {repository}@{revision[:10]} serves the pinned networks")
