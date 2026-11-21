---
license: other
license_name: lavasr-apache-2.0-and-voicefixer-cc-by-4.0
tags:
- audio
- speech-enhancement
- onnx
---

# YouRec for Android: LavaSR and VoiceFixer as ONNX

Two speech-restoration networks converted to ONNX for the Android port of
[YouRec](https://github.com/antirez/YouRec). The app runs them in ONNX Runtime on
the device, with its own DSP around them; it downloads these files only when
the user asks for the filter and checks each against its SHA-256
(`SHA256SUMS`). No audio is ever sent anywhere.

These are conversions, not new models. No retraining.

## LavaSRv2.onnx: speech detail

- Model: LavaSR v2 by Yatharth Sharma, https://github.com/ysharma3501/LavaSR,
  weights https://huggingface.co/YatharthS/LavaSR (`enhancer_v2/pytorch_model.bin`,
  SHA-256 `d100db961b2c125d77a52a12215c689e44cd9926a72f117513395b7e25e6de12`).
- License: **Apache-2.0**.
- Converted part: the Vocos backbone and spectral head. Input `mel` [1, 80, 375],
  outputs `real` and `imaginary` [1, 1025, 375]. Float32.

## VoiceFixer.onnx: studio restoration

- Model: VoiceFixer by Haohe Liu et al., code https://github.com/haoheliu/voicefixer
  (commit `aae2253c85f97a87b844b6832384de249c23ab37`, MIT); weights: Haohe Liu,
  *VoiceFixer Model CheckPoint*, v3, https://doi.org/10.5281/zenodo.5600188
  (`vf.ckpt`, `model.ckpt-1490000_trimed.pt`).
- License of the weights and of this conversion: **CC BY 4.0**,
  https://creativecommons.org/licenses/by/4.0/. Attribution: VoiceFixer, Haohe
  Liu et al.
- Converted part: analysis network and neural vocoder. Input `mel`
  [1, 1, 256, 128], output `audio` [1, 114660] at 44.1 kHz. Weights stored as
  float16 and cast to float32 in the graph; arithmetic is float32.
- Changes: ONNX export at a fixed 256-frame input, half-precision weight storage.

## How they were made

By `scripts/neural/convert-lavasr.py` and `convert-voicefixer.py` of the port,
which check every upstream checkpoint's SHA-256 before loading it and compare
ONNX Runtime with PyTorch on a fixed input. `conversion.json` records the
measured differences.
