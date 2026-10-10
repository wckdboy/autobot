cmake_minimum_required(VERSION 3.22)
project(autobot_whisper LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(WHISPER_SRC ${CMAKE_CURRENT_LIST_DIR}/../../../../../third_party/whisper.cpp)

# Static with hidden symbols: whisper.cpp's ggml never meets llama.cpp's (other process anyway).
set(BUILD_SHARED_LIBS      OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(WHISPER_ALL_WARNINGS   OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE            OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL        OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP            OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE         OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI      OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ARM_ARCH      "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)

add_subdirectory(${WHISPER_SRC} build-whisper)

add_library(autobot_whisper SHARED whisper_jni.cpp)
target_link_libraries(autobot_whisper PRIVATE whisper android log)
target_compile_options(autobot_whisper PRIVATE -O3 -fvisibility=hidden)
target_link_options(autobot_whisper PRIVATE "-Wl,--exclude-libs,ALL")
