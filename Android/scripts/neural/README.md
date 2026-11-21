# LavaSR and VoiceFixer for Android

The iPhone app bundles both networks as Core ML, converted from pinned
upstream checkpoints by `../../../scripts/convert-lavasr.py` and
`../../../scripts/convert-voicefixer.py`. These scripts make the same modules
with the same fixed shapes into ONNX for ONNX Runtime instead, from the same
checkpoints, checked by the same SHA-256 before loading:

- `convert-lavasr.py`: LavaSR v2 (https://github.com/ysharma3501/LavaSR,
  weights https://huggingface.co/YatharthS/LavaSR `enhancer_v2`, SHA-256
  `d100db96…6de12`), Apache-2.0. Spectral network only: mel [1, 80, 375] →
  real and imaginary [1, 1025, 375]. The DSP around it is the iPhone app's
  `ThirdParty/LavaSR/LavaDSP.cpp`, built by the NDK.
- `convert-voicefixer.py`: VoiceFixer (https://github.com/haoheliu/voicefixer
  at `aae2253c`, code MIT; weights https://doi.org/10.5281/zenodo.5600188,
  **CC BY 4.0**: attribution is shown with the filter). Analysis network and
  vocoder: mel [1, 1, 256, 128] → 114660 samples at 44.1 kHz. Weights are
  stored as float16 and cast to float32 when the graph loads, so arithmetic
  stays float32 (about 207 MB instead of 414 MB; the iPhone conversion is
  mixed FP16/FP32). Float32 weights measured 92.7 dB against PyTorch; the
  script requires more than 40 dB with half-precision weights. Features: the iPhone app's
  `ThirdParty/VoiceFixer/VoiceFixerDSP.cpp` with its checked mel bank.

Each script checks ONNX Runtime against PyTorch on a fixed input and fails
beyond its tolerance. `run-studio.sh` then runs both filters through the app's
Kotlin pipeline with the iPhone app's DSP built for the host, on 12 s of a
synthetic voice in noise, and fails unless the output keeps the voice. CI runs them (job `neural-models`, cached on these
files) and the build copies the graphs into the APK's assets. A local build
without `build/neural-models/` has no LavaSR or VoiceFixer filter; to make
them locally:

```sh
python3.12 -m venv .venv && . .venv/bin/activate
pip install torch==2.7.0 torchaudio==2.7.0 --index-url https://download.pytorch.org/whl/cpu
pip install -r scripts/neural/requirements.txt
python scripts/neural/convert-lavasr.py /tmp/lavasr build/neural-models/LavaSRv2.onnx
python scripts/neural/convert-voicefixer.py /tmp/voicefixer build/neural-models/VoiceFixer.onnx
```

The ONNX files are generated, not committed. Projects keep the iPhone's model
identities (`lavasr-v2-33ac0408`, `voicefixer-aae2253c`), so either app opens
the other's chains; Android's runtime is part of the processed-audio cache key
only, and its output is close to, not identical with, Core ML's.

## Download on request

A build carries the networks when CI made them. Otherwise, and also to replace
a damaged copy, the app can download them on request from a public Hugging
Face repository, checked against the sizes and SHA-256 pinned in
`YouRec/StudioModelPack.kt`. CI prepares the repository's contents as the
artifact `huggingface-upload` (the two graphs, a model card with licences and
attribution, `SHA256SUMS`, `conversion.json`). To publish it:

1. Create a public model repository on Hugging Face and upload the artifact's
   files to its top level.
2. Put the repository (`owner/name`) and the commit of that upload in
   `StudioModelPack.repository` and `StudioModelPack.revision`, and check that
   the pinned hashes match `SHA256SUMS`.

Until then the app offers only what its build carries.

## Measured (CI, run 51)

- LavaSR: 55970417 bytes, SHA-256 `50b59eae…1d65a` (the same in every conversion
  so far); ONNX Runtime vs PyTorch max error 0.00073 on outputs up to 13.6 (the
  iPhone's Core ML conversion: 0.00073). End to end through the app's pipeline:
  correlation 0.999 with the clean voice, level unchanged, 0.7 s for 12 s of sound.
- VoiceFixer with float16 weights: 207547354 bytes, SHA-256 `81c95a29…0cee52`;
  49.9 dB against PyTorch (float32 weights: 92.7 dB). End to end against
  upstream's own `VoiceFixer.restore_inmem` on the same noisy input: log-mel
  similarity 0.966 (the noisy input itself: 0.536), mean difference 3.2 dB,
  level 0.028 vs 0.025. 23 s for 12 s of sound on a 4-core CI machine.
