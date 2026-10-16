# Host benchmark and correctness check for the shared llama engine core (core/autobot_llama.cpp),
# the code both apps run. Builds llama.cpp for the machine it runs on (Metal + CPU on a Mac,
# CPU elsewhere) and links the core statically. Not part of the app builds; see run.sh.
cmake_minimum_required(VERSION 3.22)
project(autobot_llama_bench LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(REPO ${CMAKE_CURRENT_LIST_DIR}/../../..)
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
set(GGML_OPENMP              OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE           OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI        OFF CACHE BOOL "" FORCE)
if(APPLE)
    set(GGML_METAL               ON  CACHE BOOL "" FORCE)
    set(GGML_METAL_EMBED_LIBRARY ON  CACHE BOOL "" FORCE)
    set(GGML_ACCELERATE          ON  CACHE BOOL "" FORCE)
    set(GGML_BLAS                ON  CACHE BOOL "" FORCE)
    set(GGML_BLAS_VENDOR         "Apple" CACHE STRING "" FORCE)
endif()

add_subdirectory(${LLAMA_SRC} build-llama)

add_executable(llama_bench llama_bench.cpp ${CORE_DIR}/autobot_llama.cpp)
target_include_directories(llama_bench PRIVATE ${CORE_DIR} ${LLAMA_SRC}/common ${LLAMA_SRC}/include ${LLAMA_SRC}/tools/mtmd)
target_link_libraries(llama_bench PRIVATE llama llama-common mtmd)
