# DeepFilterNet3 for Android

The network is upstream's own ONNX export, unmodified:

- Source: https://github.com/Rikorose/DeepFilterNet, commit
  `7b14b895fe7fc42cab72b4a4c1e49c982bd6404b`, file `models/DeepFilterNet3_onnx.tar.gz`
  (`tmp/export/{enc,erb_dec,df_dec}.onnx`, ONNX opset 12).
- SHA-256: `enc.onnx` `7c5399d3da8a50ebef1c1a0ae421b33376aa5e45d0e92df16da7e83c9c131916`,
  `erb_dec.onnx` `ab669a1d10afe20911728b33053a452071042317a90581092b325da7b2f9d895`,
  `df_dec.onnx` `23114ce3b0f6464b763ee62f7bb8aab6b2a129a21eabd5bcfe59413db05f278a`.
  The app refuses graphs with other hashes.
- License: code MIT or Apache-2.0 (`LICENSE-MIT`, `LICENSE-APACHE`); model
  MIT, copyright Hendrik Schröter (`MODEL-LICENSE-MIT`).

The DSP data are the iPhone app's (`../../ThirdParty/DeepFilterNet3/DeepFilterNet3-DSP.json`,
read in place at build time and checked against its SHA-256 at run time), and
the DSP in `YouRec/DeepFilterNet.kt` follows the iPhone app's
`DeepFilterNetEngine.swift`. Runtime: ONNX Runtime 1.31.1 for Android
(Maven Central `com.microsoft.onnxruntime:onnxruntime-android`, MIT), CPU only.

## Differences from the iPhone app

The iPhone app runs a stateful per-hop Core ML conversion (iky1e/DeepFilterNet3-Streaming-CoreML).
Upstream's ONNX graphs take a whole sequence with no recurrent state in or out, so
Android runs them on 15-second chunks, each after 3 seconds of context that bring
the recurrent state up to speed. Same weights and DSP; the output is close to,
not identical with, the iPhone's. A project keeps the iPhone's model identity
(`deepfilternet3-dfc12319`), so either app opens the other's chains; the
Android runtime is part of the processed-audio cache key only.

## Checks

`scripts/dfn3-parity/run.sh` (run by CI) compares the app's hop-by-hop DSP with an
independent whole-sequence formulation written after upstream's PyTorch
`DfNet.forward`, both with these graphs, on 40 s of a synthetic voice in white noise:

- app with full context vs reference: 130 dB (float rounding);
- app as shipped (chunks) vs reference: 41.5 dB;
- voice-to-error: noisy 8.9 dB, reference 26.8 dB, app 27.1 dB.

About 11× real time per channel on a desktop CPU. These verify the
implementation, not perceived quality on real recordings.
