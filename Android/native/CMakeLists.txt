# The iPhone app's native audio code, built unchanged from ../ThirdParty, with
# a small JNI bridge: RNNoise (noise reduction, and the speech check in
# Pauses) and the DSP around LavaSR and VoiceFixer. Also antirez's Qwen3-ASR
# in C for subtitles (ThirdParty/qwen-asr, unchanged), with a small SGEMM in
# place of a system BLAS (blas/).
cmake_minimum_required(VERSION 3.22.1)
project(yourec C CXX)
set(THIRD_PARTY ${CMAKE_CURRENT_SOURCE_DIR}/../../ThirdParty)
set(RNNOISE ${THIRD_PARTY}/RNNoise)
add_library(yourec SHARED
    yourec_jni.c
    ${RNNOISE}/YouRecDenoiser.c
    ${RNNOISE}/src/denoise.c ${RNNOISE}/src/rnn.c ${RNNOISE}/src/rnn_data.c
    ${RNNOISE}/src/pitch.c ${RNNOISE}/src/kiss_fft.c ${RNNOISE}/src/celt_lpc.c
    ${THIRD_PARTY}/LavaSR/LavaDSP.cpp
    ${THIRD_PARTY}/VoiceFixer/VoiceFixerDSP.cpp)
target_include_directories(yourec PRIVATE ${RNNOISE} ${RNNOISE}/include ${RNNOISE}/src ${THIRD_PARTY}/LavaSR ${THIRD_PARTY}/VoiceFixer)
set_target_properties(yourec PROPERTIES CXX_STANDARD 17 CXX_STANDARD_REQUIRED ON)
target_compile_options(yourec PRIVATE -O2 -Wno-unused-parameter)
target_link_libraries(yourec m)
# 16 KB pages (Android 15+ on recent Pixels): segments aligned so the system
# loads the library without its compatibility warning.
target_link_options(yourec PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")

set(QWEN ${CMAKE_CURRENT_SOURCE_DIR}/../ThirdParty/qwen-asr)
add_library(qwenasr STATIC
    ${QWEN}/qwen_asr.c ${QWEN}/qwen_asr_audio.c ${QWEN}/qwen_asr_decoder.c ${QWEN}/qwen_asr_encoder.c
    ${QWEN}/qwen_asr_kernels.c ${QWEN}/qwen_asr_kernels_generic.c ${QWEN}/qwen_asr_kernels_neon.c ${QWEN}/qwen_asr_kernels_avx.c
    ${QWEN}/qwen_asr_safetensors.c ${QWEN}/qwen_asr_tokenizer.c
    blas/yourec_sgemm.c)
target_include_directories(qwenasr PUBLIC ${QWEN} blas)
target_compile_definitions(qwenasr PRIVATE USE_BLAS)
# As qwen-asr's own Makefile builds it, without -march=native.
target_compile_options(qwenasr PRIVATE -O3 -ffast-math -Wno-unused-parameter -Wno-unused-function -Wno-sign-compare)
target_sources(yourec PRIVATE qwen_jni.c)
target_link_libraries(yourec qwenasr)
