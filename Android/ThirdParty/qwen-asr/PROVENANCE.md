# qwen-asr for Android

Antirez's Qwen3-ASR inference in C, the speech engine for subtitles:

- Source: https://github.com/antirez/qwen-asr, commit
  `924694251d9e0f18e5d86bbd06aa3ab5f870002d`. MIT, Salvatore Sanfilippo (`LICENSE`).
- Files: the CPU engine only (`qwen_asr*.c/h`), copied unchanged, and
  `samples/jfk.wav` with its reference transcript `samples/jfk.txt`. Not
  included: `main.c`, the Makefile, the CUDA and ROCm backends, tests.
- Built by the NDK (`native/CMakeLists.txt`) with `USE_BLAS`, the options of its
  Makefile except `-march=native`. Android has no system BLAS: the one call the
  engine makes, `cblas_sgemm`, is `native/blas/yourec_sgemm.c`.
- JNI: `native/qwen_jni.c`; Kotlin: `YouRec/SpeechModel.kt`.

Weights are not bundled: the app downloads the official Qwen/Qwen3-ASR-0.6B
(Apache-2.0) on request, the files qwen-asr's `download_model.sh` fetches,
pinned by revision, size and SHA-256 (`YouRec/SubtitleModelPack.kt`). About
1.75 GiB; about 2.8 GiB of memory while transcribing (qwen-asr's README).

The iPhone app runs the same model through MLX with a 4-bit conversion
(aufklarer/Qwen3-ASR-0.6B-MLX-4bit). Transcripts can differ slightly; projects
store words and times, not model output, so either app edits the other's subtitles.

CI (`scripts/speech/run-speech.sh`) builds this engine for the host as the app
builds it, transcribes `samples/jfk.wav` through the app's JNI bridge, and
requires the same text as qwen-asr's own build (OpenBLAS) and as `jfk.txt`.
