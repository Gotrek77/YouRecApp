// JNI bridge to the iPhone app's RNNoise adapter (ThirdParty/RNNoise/YouRecDenoiser.c).
#include <jni.h>
#include <stdint.h>
#include "YouRecDenoiser.h"

JNIEXPORT jlong JNICALL Java_yourec_SpeechDetector_create(JNIEnv *env, jclass type) {
    return (jlong)(intptr_t)yr_denoiser_create();
}
JNIEXPORT void JNICALL Java_yourec_SpeechDetector_destroy(JNIEnv *env, jclass type, jlong state) {
    yr_denoiser_destroy((void *)(intptr_t)state);
}
// Speech probability of one channel of a 10 ms interleaved frame: 480 samples
// starting at `offset`, every `stride` floats.
JNIEXPORT jfloat JNICALL Java_yourec_SpeechDetector_probability(JNIEnv *env, jclass type, jlong state, jfloatArray samples, jint offset, jint stride) {
    if (!state || offset < 0 || stride < 1 || (*env)->GetArrayLength(env, samples) < offset + 479 * stride + 1) return 0;
    jfloat *values = (*env)->GetFloatArrayElements(env, samples, NULL);
    if (!values) return 0;
    float p = yr_speech_probability((void *)(intptr_t)state, values + offset, stride);
    (*env)->ReleaseFloatArrayElements(env, samples, values, JNI_ABORT);
    return p;
}
// Denoise one 480-sample mono frame (RNNoise output is one frame late).
JNIEXPORT void JNICALL Java_yourec_RNNoiseDenoiser_process(JNIEnv *env, jclass type, jlong state, jfloatArray input, jfloatArray output) {
    if (!state || (*env)->GetArrayLength(env, input) < 480 || (*env)->GetArrayLength(env, output) < 480) return;
    jfloat in[480], out[480];
    (*env)->GetFloatArrayRegion(env, input, 0, 480, in);
    yr_denoiser_process((void *)(intptr_t)state, in, out);
    (*env)->SetFloatArrayRegion(env, output, 0, 480, out);
}

// LavaSR and VoiceFixer DSP (ThirdParty/LavaSR/LavaDSP.cpp, ThirdParty/VoiceFixer/VoiceFixerDSP.cpp).
#include "LavaDSP.h"
#include "VoiceFixerDSP.h"
#include <stdlib.h>

static int sized(JNIEnv *env, jfloatArray array, jsize count) { return array && (*env)->GetArrayLength(env, array) >= count; }

JNIEXPORT jboolean JNICALL Java_yourec_NativeStudioDSP_lavaFeatures(JNIEnv *env, jclass type, jfloatArray audio, jfloatArray mel) {
    if (!sized(env, audio, 192000) || !sized(env, mel, 80 * 375)) return JNI_FALSE;
    jfloat *a = (*env)->GetFloatArrayElements(env, audio, NULL), *m = (*env)->GetFloatArrayElements(env, mel, NULL);
    int ok = a && m && yr_lava_features(a, m);
    if (m) (*env)->ReleaseFloatArrayElements(env, mel, m, 0);
    if (a) (*env)->ReleaseFloatArrayElements(env, audio, a, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT jboolean JNICALL Java_yourec_NativeStudioDSP_lavaReconstruct(JNIEnv *env, jclass type, jfloatArray real, jfloatArray imaginary, jfloatArray audio) {
    if (!sized(env, real, 1025 * 375) || !sized(env, imaginary, 1025 * 375) || !sized(env, audio, 192000)) return JNI_FALSE;
    jfloat *r = (*env)->GetFloatArrayElements(env, real, NULL), *i = (*env)->GetFloatArrayElements(env, imaginary, NULL), *a = (*env)->GetFloatArrayElements(env, audio, NULL);
    int ok = r && i && a && yr_lava_reconstruct(r, i, a);
    if (a) (*env)->ReleaseFloatArrayElements(env, audio, a, 0);
    if (i) (*env)->ReleaseFloatArrayElements(env, imaginary, i, JNI_ABORT);
    if (r) (*env)->ReleaseFloatArrayElements(env, real, r, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT jboolean JNICALL Java_yourec_NativeStudioDSP_lavaMerge(JNIEnv *env, jclass type, jfloatArray original, jfloatArray restored, jfloatArray output) {
    if (!sized(env, original, 192000) || !sized(env, restored, 192000) || !sized(env, output, 192000)) return JNI_FALSE;
    jfloat *o = (*env)->GetFloatArrayElements(env, original, NULL), *r = (*env)->GetFloatArrayElements(env, restored, NULL), *out = (*env)->GetFloatArrayElements(env, output, NULL);
    int ok = o && r && out && yr_lava_merge(o, r, out);
    if (out) (*env)->ReleaseFloatArrayElements(env, output, out, 0);
    if (r) (*env)->ReleaseFloatArrayElements(env, restored, r, JNI_ABORT);
    if (o) (*env)->ReleaseFloatArrayElements(env, original, o, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT jboolean JNICALL Java_yourec_NativeStudioDSP_voiceFixerFeatures(JNIEnv *env, jclass type, jfloatArray audio, jfloatArray bank, jfloatArray mel) {
    if (!sized(env, audio, 112455) || !sized(env, bank, 1025 * 128) || !sized(env, mel, 256 * 128)) return JNI_FALSE;
    jfloat *a = (*env)->GetFloatArrayElements(env, audio, NULL), *b = (*env)->GetFloatArrayElements(env, bank, NULL), *m = (*env)->GetFloatArrayElements(env, mel, NULL);
    int ok = a && b && m && yr_voicefixer_features(a, b, m);
    if (m) (*env)->ReleaseFloatArrayElements(env, mel, m, 0);
    if (b) (*env)->ReleaseFloatArrayElements(env, bank, b, JNI_ABORT);
    if (a) (*env)->ReleaseFloatArrayElements(env, audio, a, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}
