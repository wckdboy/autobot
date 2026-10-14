# AutobotLlama: llama.cpp (+ common, mtmd) with Metal and Accelerate, linked statically into one
# dylib together with the shared engine core (engine/llama/src/main/cpp/core). Only the
# `autobot_llama_*` C ABI is exported. Built per slice by scripts/build-xcframeworks.sh.
cmake_minimum_required(VERSION 3.28)
project(AutobotLlama LANGUAGES C CXX OBJC)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(REPO ${CMAKE_CURRENT_LIST_DIR}/../../../../..)
set(LLAMA_SRC ${REPO}/third_party/llama.cpp)
set(CORE_DIR ${REPO}/engine/llama/src/main/cpp/core)

set(BUILD_SHARED_LIBS        OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON       ON  CACHE BOOL "" FORCE)
set(LLAMA_BUILD_MTMD         ON  CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS        OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS        OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER       OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP          OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_UI           OFF CACHE BOOL "" FORCE)
set(LLAMA_USE_PREBUILT_UI    OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL            OFF CACHE BOOL "" FORCE)
set(LLAMA_SUBPROCESS         OFF CACHE BOOL "" FORCE)
set(LLAMA_ALL_WARNINGS       OFF CACHE BOOL "" FORCE)

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

add_subdirectory(${LLAMA_SRC} build-llama)

add_library(AutobotLlama SHARED ${CORE_DIR}/autobot_llama.cpp)
target_include_directories(AutobotLlama PRIVATE ${CORE_DIR} ${LLAMA_SRC}/common ${LLAMA_SRC}/include ${LLAMA_SRC}/tools/mtmd)
target_link_libraries(AutobotLlama PRIVATE llama llama-common mtmd
    "-framework Foundation" "-framework Metal" "-framework MetalKit" "-framework Accelerate")
target_link_options(AutobotLlama PRIVATE "-Wl,-exported_symbol,_autobot_llama_*" "-Wl,-dead_strip")
set_target_properties(AutobotLlama PROPERTIES OUTPUT_NAME AutobotLlama PREFIX "" SUFFIX ""
    INSTALL_NAME_DIR "@rpath/AutobotLlama.framework" BUILD_WITH_INSTALL_NAME_DIR ON)
