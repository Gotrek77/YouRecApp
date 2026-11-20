#!/bin/bash
# DeepFilterNet3 on Android against an independent whole-sequence reference,
# both with upstream's ONNX graphs. Needs a JDK, kotlinc, Python 3 with numpy
# and onnxruntime, and network access to Maven Central for ONNX Runtime's jar.
set -euo pipefail
cd "$(dirname "$0")/../.."
WORK=${1:-$(mktemp -d "${TMPDIR:-/tmp}/dfn3-parity.XXXXXX")}; mkdir -p "$WORK"
ORT=$WORK/onnxruntime-1.31.1.jar
[ -f "$ORT" ] || curl -sSfL -o "$ORT" https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime/1.31.1/onnxruntime-1.31.1.jar
DSP=../ThirdParty/DeepFilterNet3/DeepFilterNet3-DSP.json
CORE="YouRec/JSON.kt YouRec/TrackTimeline.kt YouRec/Project.kt YouRec/ProjectCoding.kt YouRec/TimelineEditing.kt YouRec/ZoomTransition.kt YouRec/RenderPlan.kt YouRec/TakeJournal.kt YouRec/VideoTools.kt YouRec/ColorGrading.kt YouRec/TimelinePaste.kt YouRec/ProjectArchive.kt YouRec/CoverFramePool.kt YouRec/AudioCensor.kt YouRec/AudioDSP.kt YouRec/OfflineAudioPipeline.kt YouRec/DeepFilterNet.kt YouRec/DeepFilterOnnx.kt YouRec/PauseCuts.kt YouRec/Transitions.kt YouRec/HorizonLevel.kt"
${KOTLINC:-kotlinc} -nowarn $CORE scripts/dfn3-parity/Harness.kt -cp "$ORT" -include-runtime -d "$WORK/harness.jar"
python3 - "$WORK" <<'PY'
import sys, numpy as np
sr = 48000; t = np.arange(40 * sr) / sr; rng = np.random.default_rng(2)
f0 = 150 + 40 * np.sin(2 * np.pi * 0.2 * t); phase = 2 * np.pi * np.cumsum(f0) / sr
envelope = (0.5 + 0.5 * np.sin(2 * np.pi * 2.5 * t)) * (np.sin(2 * np.pi * 0.13 * t) > -0.3)
voice = envelope * (0.3 * np.sin(phase) + 0.12 * np.sin(2 * phase) + 0.06 * np.sin(3 * phase))
voice.astype(np.float32).tofile(sys.argv[1] + "/voice.f32")
(voice + 0.04 * rng.standard_normal(len(t))).astype(np.float32).tofile(sys.argv[1] + "/noisy.f32")
PY
python3 scripts/dfn3-parity/reference.py ThirdParty/DeepFilterNet3 $DSP "$WORK/noisy.f32" "$WORK/reference.f32"
java -cp "$WORK/harness.jar:$ORT" yourec.HarnessKt ThirdParty/DeepFilterNet3 $DSP 5000 5000 "$WORK/noisy.f32" "$WORK/whole.f32"
java -cp "$WORK/harness.jar:$ORT" yourec.HarnessKt ThirdParty/DeepFilterNet3 $DSP 1500 300 "$WORK/noisy.f32" "$WORK/chunked.f32"
python3 - "$WORK" <<'PY'
import sys, numpy as np
d = sys.argv[1]; load = lambda n: np.fromfile(f"{d}/{n}.f32", np.float32)
voice, noisy, reference = load("voice"), load("noisy"), load("reference")
db = lambda signal, error: 10 * np.log10((signal ** 2).sum() / max((error ** 2).sum(), 1e-30))
whole, chunked = load("whole"), load("chunked")
print("noisy %.1f dB, reference %.1f dB, app %.1f dB (voice to error)" % (db(voice, noisy - voice), db(voice, reference - voice), db(voice, chunked - voice)))
print("app with whole context vs reference: %.1f dB; app as shipped vs reference: %.1f dB" % (db(reference, whole - reference), db(reference, chunked - reference)))
assert db(reference, whole - reference) > 100, "the hop-by-hop DSP must match the whole-sequence reference"
assert db(reference, chunked - reference) > 30, "chunking must stay close to the whole-sequence result"
assert db(voice, chunked - voice) > db(voice, noisy - voice) + 10, "DeepFilterNet3 must remove most of the noise"
print("PASS: DeepFilterNet3 parity")
PY
