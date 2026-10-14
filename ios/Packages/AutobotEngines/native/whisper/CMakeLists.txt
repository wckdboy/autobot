# AutobotWhisper: whisper.cpp with Metal and the Core ML encoder (Apple Neural Engine when an
# `<model>-encoder.mlmodelc` sits next to the ggml model), linked statically into one dylib with
# the shared engine core (engine/speech/src/main/cpp/core). Only `autobot_whisper_*` is exported,
# so its private ggml copy never meets AutobotLlama's.
cmake_minimum_required(VERSION 3.28)
project(AutobotWhisper LANGUAGES C CXX OBJC OBJCXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(REPO ${CMAKE_CURRENT_LIST_DIR}/../../../../..)
set(WHISPER_SRC ${REPO}/third_party/whisper.cpp)
set(CORE_DIR ${REPO}/engine/speech/src/main/cpp/core)

set(BUILD_SHARED_LIBS              OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS            OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES         OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER           OFF CACHE BOOL "" FORCE)
set(WHISPER_ALL_WARNINGS           OFF CACHE BOOL "" FORCE)
set(WHISPER_COREML                 ON  CACHE BOOL "" FORCE)
set(WHISPER_COREML_ALLOW_FALLBACK  ON  CACHE BOOL "" FORCE)

set(GGML_NATIVE              OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL          OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP              OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE           OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI        OFF CACHE BOOL "" FORCE)
set(GGML_METAL               ON  CACHE BOOL "" FORCE)
set(GGML_METAL_EMBED_LIBRARY ON  CACHE BOOL "" FORCE)
set(GGML_METAL_NDEBUG        ON  CACHE BOOL "" FORCE)
set(GGML_METAL_TARGET_OS     "ios" CACHE STRING "" FORCE)
set(GGML_ACCELERATE          ON  CACHE BOOL "" FORCE)
set(GGML_BLAS                ON  CACHE BOOL "" FORCE)
set(GGML_BLAS_VENDOR         "Apple" CACHE STRING "" FORCE)

add_subdirectory(${WHISPER_SRC} build-whisper)

# Both submodules carry the same ggml (0.26.0), each hidden inside its own framework. Objective-C
# classes are global at runtime, though: rename ggml-metal's bundle-lookup class (only compiled
# without GGML_METAL_EMBED_LIBRARY) so it can never clash with AutobotLlama's.
target_compile_definitions(ggml-metal PRIVATE GGMLMetalClass=AutobotWhisperGGMLMetalClass)

add_library(AutobotWhisper SHARED ${CORE_DIR}/autobot_whisper.cpp)
target_include_directories(AutobotWhisper PRIVATE ${CORE_DIR})
target_link_libraries(AutobotWhisper PRIVATE whisper
    "-framework Foundation" "-framework Metal" "-framework MetalKit" "-framework Accelerate" "-framework CoreML")
target_link_options(AutobotWhisper PRIVATE "-Wl,-exported_symbol,_autobot_whisper_*" "-Wl,-dead_strip")
set_target_properties(AutobotWhisper PROPERTIES OUTPUT_NAME AutobotWhisper PREFIX "" SUFFIX ""
    INSTALL_NAME_DIR "@rpath/AutobotWhisper.framework" BUILD_WITH_INSTALL_NAME_DIR ON)
