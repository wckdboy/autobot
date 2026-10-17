# Benchmark and correctness check for the shared whisper engine core (core/autobot_whisper.cpp),
# the code both apps run. Builds whisper.cpp for this machine's CPU (no Core ML, no Metal: the
# phones' dictation runs whisper on the CPU) and links the core statically. Not part of the app
# builds:
#   cmake -S engine/speech/bench -B <build> -G Ninja -DCMAKE_BUILD_TYPE=Release && cmake --build <build>
#   <build>/whisper_bench ggml-base-q5_1.bin speech.wav [--reps N] [--step-ms N] [--first-ms N]
cmake_minimum_required(VERSION 3.22)
project(autobot_whisper_bench LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(REPO ${CMAKE_CURRENT_LIST_DIR}/../../..)
set(WHISPER_SRC ${REPO}/third_party/whisper.cpp)
set(CORE_DIR ${REPO}/engine/speech/src/main/cpp/core)

set(BUILD_SHARED_LIBS      OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(WHISPER_ALL_WARNINGS   OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP            OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE         OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI      OFF CACHE BOOL "" FORCE)
set(GGML_METAL             OFF CACHE BOOL "" FORCE)
set(GGML_BLAS              OFF CACHE BOOL "" FORCE)

add_subdirectory(${WHISPER_SRC} build-whisper)

add_executable(whisper_bench whisper_bench.cpp ${CORE_DIR}/autobot_whisper.cpp)
target_include_directories(whisper_bench PRIVATE ${CORE_DIR})
target_link_libraries(whisper_bench PRIVATE whisper)
