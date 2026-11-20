# The iPhone app's native audio code, built unchanged from ../ThirdParty, with
# a small JNI bridge: RNNoise (noise reduction, and the speech check in
# Pauses) and the DSP around LavaSR and VoiceFixer.
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
