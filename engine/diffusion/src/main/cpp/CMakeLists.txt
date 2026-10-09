cmake_minimum_required(VERSION 3.22)
project(autobot_sd LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Pinned stable-diffusion.cpp checkout (git submodule with its own patched ggml).
set(SD_SRC ${CMAKE_CURRENT_LIST_DIR}/../../../../../third_party/stable-diffusion.cpp)

# Everything is linked statically into libautobot_sd.so with hidden symbols, so this ggml can
# never clash with the llama.cpp engine's ggml (different version, other process anyway).
set(BUILD_SHARED_LIBS        OFF CACHE BOOL "" FORCE)
set(SD_BUILD_SHARED_LIBS     OFF CACHE BOOL "" FORCE)
set(SD_BUILD_SHARED_GGML_LIB OFF CACHE BOOL "" FORCE)
set(SD_BUILD_EXAMPLES        OFF CACHE BOOL "" FORCE)
set(SD_WEBP                  OFF CACHE BOOL "" FORCE)
set(SD_WEBM                  OFF CACHE BOOL "" FORCE)

# One CPU baseline: ARMv8.2 with dot-product and fp16 arithmetic (every Snapdragon since the
# 845). The app checks /proc/cpuinfo before offering the engine.
set(GGML_NATIVE       OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL   OFF CACHE BOOL "" FORCE)
# ggml's own pthread pool instead of OpenMP: libomp aborts on some Android builds (thread fork
# under native bridges) and would add another shared library.
set(GGML_OPENMP       OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE    OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)

add_subdirectory(${SD_SRC} build-sd)

add_library(autobot_sd SHARED sd_jni.cpp)
target_include_directories(autobot_sd PRIVATE ${SD_SRC}/include)
target_link_libraries(autobot_sd PRIVATE stable-diffusion android log)
target_link_options(autobot_sd PRIVATE "-Wl,--exclude-libs,ALL")
