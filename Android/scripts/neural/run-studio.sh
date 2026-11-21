#!/bin/bash
# LavaSR and VoiceFixer end to end on this computer: the app's Kotlin pipeline,
# the iPhone app's DSP built here, the converted networks in ONNX Runtime.
# Usage: scripts/neural/run-studio.sh <neural-models folder> [work folder]
set -euo pipefail
cd "$(dirname "$0")/../.."
MODELS=$(cd "$1" && pwd)
WORK=${2:-$(mktemp -d "${TMPDIR:-/tmp}/studio.XXXXXX")}; mkdir -p "$WORK"
ORT=$WORK/onnxruntime-1.31.1.jar
[ -f "$ORT" ] || curl -sSfL -o "$ORT" https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime/1.31.1/onnxruntime-1.31.1.jar
T=../ThirdParty
JAVA_INCLUDE=${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(which javac)")")")}/include
mkdir -p "$WORK/native"
gcc -O2 -fPIC -c native/yourec_jni.c -I"$JAVA_INCLUDE" -I"$JAVA_INCLUDE/linux" -I$T/RNNoise -I$T/LavaSR -I$T/VoiceFixer -o "$WORK/native/jni.o"
g++ -O2 -fPIC -std=c++17 -c $T/LavaSR/LavaDSP.cpp -o "$WORK/native/lava.o"
g++ -O2 -fPIC -std=c++17 -c $T/VoiceFixer/VoiceFixerDSP.cpp -o "$WORK/native/voicefixer.o"
gcc -O2 -fPIC -shared -o "$WORK/native/libyourec.so" "$WORK/native/"*.o $T/RNNoise/YouRecDenoiser.c \
    $T/RNNoise/src/{denoise,rnn,rnn_data,pitch,kiss_fft,celt_lpc}.c -I$T/RNNoise -I$T/RNNoise/include -I$T/RNNoise/src -lm -lstdc++
CORE="YouRec/JSON.kt YouRec/TrackTimeline.kt YouRec/Project.kt YouRec/ProjectCoding.kt YouRec/TimelineEditing.kt YouRec/ZoomTransition.kt YouRec/RenderPlan.kt YouRec/TakeJournal.kt YouRec/VideoTools.kt YouRec/ColorGrading.kt YouRec/TimelinePaste.kt YouRec/ProjectArchive.kt YouRec/CoverFramePool.kt YouRec/AudioCensor.kt YouRec/AudioDSP.kt YouRec/OfflineAudioPipeline.kt YouRec/DeepFilterNet.kt YouRec/StudioAudio.kt YouRec/StudioModelPack.kt YouRec/Subtitles.kt YouRec/DeepFilterOnnx.kt YouRec/StudioOnnx.kt YouRec/SpeechDetector.kt YouRec/PauseCuts.kt YouRec/Transitions.kt YouRec/HorizonLevel.kt"
${KOTLINC:-kotlinc} -nowarn $CORE scripts/neural/StudioHarness.kt -cp "$ORT" -include-runtime -d "$WORK/studio.jar"
java -Djava.library.path="$WORK/native" -cp "$WORK/studio.jar:$ORT" yourec.StudioHarnessKt "$MODELS" "$WORK"
