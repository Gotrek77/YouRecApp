#!/bin/bash
# Compile the platform-independent core with its checks into one program and
# run it. No Android SDK, Gradle or device: only a JDK and kotlinc.
set -euo pipefail
cd "$(dirname "$0")/.."
TEST_DIRECTORY=${YOUREC_TEST_DIRECTORY:-$(mktemp -d "${TMPDIR:-/tmp}/yourec-android-tests.XXXXXX")}
mkdir -p "$TEST_DIRECTORY/work"
if [ -z "${YOUREC_TEST_DIRECTORY:-}" ]; then trap 'rm -rf "$TEST_DIRECTORY"' EXIT; fi
CORE="YouRec/JSON.kt YouRec/TrackTimeline.kt YouRec/Project.kt YouRec/ProjectCoding.kt YouRec/TimelineEditing.kt YouRec/ZoomTransition.kt YouRec/RenderPlan.kt YouRec/TakeJournal.kt YouRec/VideoTools.kt YouRec/ColorGrading.kt YouRec/TimelinePaste.kt YouRec/ProjectArchive.kt YouRec/CoverFramePool.kt YouRec/AudioCensor.kt YouRec/AudioDSP.kt YouRec/OfflineAudioPipeline.kt YouRec/DeepFilterNet.kt YouRec/StudioAudio.kt YouRec/StudioModelPack.kt YouRec/Subtitles.kt YouRec/SubtitleModelPack.kt YouRec/SubtitleTranscriber.kt YouRec/PauseCuts.kt YouRec/Transitions.kt YouRec/HorizonLevel.kt"
${KOTLINC:-kotlinc} -nowarn $CORE Tests/*.kt -include-runtime -d "$TEST_DIRECTORY/core-tests.jar"
java -cp "$TEST_DIRECTORY/core-tests.jar" yourec.CoreTestsKt "$TEST_DIRECTORY/work"
