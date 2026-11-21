// JNI bridge to antirez's Qwen3-ASR in C (ThirdParty/qwen-asr), for subtitles.
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include "qwen_asr.h"
#include "qwen_asr_kernels.h"

JNIEXPORT jlong JNICALL Java_yourec_SpeechModel_load(JNIEnv *env, jclass type, jstring folder, jint threads) {
    const char *path = (*env)->GetStringUTFChars(env, folder, NULL);
    if (!path) return 0;
    if (threads > 0) qwen_set_threads(threads);
    qwen_ctx_t *ctx = qwen_load(path);
    (*env)->ReleaseStringUTFChars(env, folder, path);
    return (jlong)(intptr_t)ctx;
}
JNIEXPORT void JNICALL Java_yourec_SpeechModel_free(JNIEnv *env, jclass type, jlong ctx) {
    if (ctx) qwen_free((qwen_ctx_t *)(intptr_t)ctx);
}
// Language: a name qwen-asr accepts ("Italian"), or null to detect it.
JNIEXPORT jboolean JNICALL Java_yourec_SpeechModel_language(JNIEnv *env, jclass type, jlong ctx, jstring language) {
    if (!ctx) return JNI_FALSE;
    if (!language) return qwen_set_force_language((qwen_ctx_t *)(intptr_t)ctx, NULL) == 0 ? JNI_TRUE : JNI_FALSE;
    const char *name = (*env)->GetStringUTFChars(env, language, NULL);
    if (!name) return JNI_FALSE;
    int status = qwen_set_force_language((qwen_ctx_t *)(intptr_t)ctx, name);
    (*env)->ReleaseStringUTFChars(env, language, name);
    return status == 0 ? JNI_TRUE : JNI_FALSE;
}
// 16 kHz mono float samples in, the text out (null on failure).
JNIEXPORT jstring JNICALL Java_yourec_SpeechModel_transcribe(JNIEnv *env, jclass type, jlong ctx, jfloatArray samples) {
    if (!ctx || !samples) return NULL;
    jsize count = (*env)->GetArrayLength(env, samples);
    jfloat *values = (*env)->GetFloatArrayElements(env, samples, NULL);
    if (!values) return NULL;
    char *text = qwen_transcribe_audio((qwen_ctx_t *)(intptr_t)ctx, values, count);
    (*env)->ReleaseFloatArrayElements(env, samples, values, JNI_ABORT);
    if (!text) return NULL;
    jstring result = (*env)->NewStringUTF(env, text);
    free(text);
    return result;
}
JNIEXPORT jstring JNICALL Java_yourec_SpeechModel_languages(JNIEnv *env, jclass type) {
    return (*env)->NewStringUTF(env, qwen_supported_languages_csv());
}
