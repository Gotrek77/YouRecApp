#!/bin/bash
# Antirez's Qwen3-ASR through the app's JNI bridge and SGEMM, on this
# computer, against qwen-asr's own build (with OpenBLAS) and its reference
# transcript, on samples/jfk.wav.
# Usage: scripts/speech/run-speech.sh <model folder> <qwen-asr checkout> [work folder]
set -euo pipefail
cd "$(dirname "$0")/../.."
MODEL=$(cd "$1" && pwd); UPSTREAM=$(cd "$2" && pwd)
WORK=${3:-$(mktemp -d "${TMPDIR:-/tmp}/speech.XXXXXX")}; mkdir -p "$WORK/native"
SAMPLE=ThirdParty/qwen-asr/samples/jfk.wav
T=../ThirdParty; Q=ThirdParty/qwen-asr
JAVA_INCLUDE=${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(which javac)")")")}/include
# The app's native library, for this computer: qwen-asr with the options of
# its own Makefile (but no -march=native), the rest as the app builds it.
mkdir -p "$WORK/native/qwen"
for f in $Q/qwen_asr*.c native/blas/yourec_sgemm.c; do
    gcc -O3 -ffast-math -fPIC -DUSE_BLAS -Inative/blas -I$Q -c "$f" -o "$WORK/native/qwen/$(basename "$f" .c).o"
done
gcc -O2 -fPIC -shared -o "$WORK/native/libyourec.so" native/yourec_jni.c native/qwen_jni.c "$WORK/native/qwen/"*.o \
    $T/RNNoise/YouRecDenoiser.c $T/RNNoise/src/{denoise,rnn,rnn_data,pitch,kiss_fft,celt_lpc}.c \
    -x c++ $T/LavaSR/LavaDSP.cpp $T/VoiceFixer/VoiceFixerDSP.cpp -x none \
    -I"$JAVA_INCLUDE" -I"$JAVA_INCLUDE/linux" -Inative/blas -I$Q -I$T/RNNoise -I$T/RNNoise/include -I$T/RNNoise/src -I$T/LavaSR -I$T/VoiceFixer \
    -lm -lpthread -lstdc++
CORE="YouRec/JSON.kt YouRec/TrackTimeline.kt YouRec/Project.kt YouRec/ProjectCoding.kt YouRec/TimelineEditing.kt YouRec/ZoomTransition.kt YouRec/RenderPlan.kt YouRec/TakeJournal.kt YouRec/VideoTools.kt YouRec/ColorGrading.kt YouRec/TimelinePaste.kt YouRec/ProjectArchive.kt YouRec/CoverFramePool.kt YouRec/AudioCensor.kt YouRec/AudioDSP.kt YouRec/OfflineAudioPipeline.kt YouRec/DeepFilterNet.kt YouRec/StudioAudio.kt YouRec/StudioModelPack.kt YouRec/Subtitles.kt YouRec/PauseCuts.kt YouRec/Transitions.kt YouRec/HorizonLevel.kt YouRec/SpeechDetector.kt YouRec/SpeechModel.kt"
${KOTLINC:-kotlinc} -nowarn $CORE scripts/speech/SpeechHarness.kt -include-runtime -d "$WORK/speech.jar"
java -Djava.library.path="$WORK/native" -cp "$WORK/speech.jar" yourec.SpeechHarnessKt "$MODEL" $SAMPLE > "$WORK/app.txt"
# qwen-asr as its author builds it.
make -C "$UPSTREAM" blas >/dev/null
"$UPSTREAM/qwen_asr" -d "$MODEL" -i $SAMPLE --silent > "$WORK/upstream.txt"
normal() { tr -s '[:space:]' ' ' < "$1" | sed 's/^ *//; s/ *$//'; }
echo "app:       $(normal "$WORK/app.txt")"
echo "upstream:  $(normal "$WORK/upstream.txt")"
echo "reference: $(normal ThirdParty/qwen-asr/samples/jfk.txt)"
[ "$(normal "$WORK/app.txt")" = "$(normal "$WORK/upstream.txt")" ] || { echo "FAIL: the app's transcription differs from qwen-asr's own build"; exit 1; }
[ "$(normal "$WORK/app.txt")" = "$(normal ThirdParty/qwen-asr/samples/jfk.txt)" ] || { echo "FAIL: the transcription differs from the reference"; exit 1; }
echo "PASS: Qwen3-ASR matches qwen-asr's own build and its reference transcript"
