# The iPhone app's RNNoise, built unchanged from ThirdParty/RNNoise, with a
# small JNI bridge. It answers one question for Pauses: is this 10 ms speech?
cmake_minimum_required(VERSION 3.22.1)
project(yourec C)
set(RNNOISE ${CMAKE_CURRENT_SOURCE_DIR}/../../ThirdParty/RNNoise)
add_library(yourec SHARED
    yourec_jni.c
    ${RNNOISE}/YouRecDenoiser.c
    ${RNNOISE}/src/denoise.c ${RNNOISE}/src/rnn.c ${RNNOISE}/src/rnn_data.c
    ${RNNOISE}/src/pitch.c ${RNNOISE}/src/kiss_fft.c ${RNNOISE}/src/celt_lpc.c)
target_include_directories(yourec PRIVATE ${RNNOISE} ${RNNOISE}/include ${RNNOISE}/src)
target_compile_options(yourec PRIVATE -O2 -Wno-unused-parameter)
target_link_libraries(yourec m)
