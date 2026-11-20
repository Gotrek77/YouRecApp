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
