# Benchmark and correctness check for the shared llama engine core (core/autobot_llama.cpp),
# the code both apps run. Builds llama.cpp for the machine it runs on (Metal + CPU on a Mac,
# CPU elsewhere) and links the core statically. Not part of the app builds:
#   cmake -S engine/llama/bench -B <build> -G Ninja -DCMAKE_BUILD_TYPE=Release && cmake --build <build>
#   <build>/llama_bench model.gguf --backend gpu|cpu [--threads N] [--quick]
# With the NDK toolchain it builds the phone's configuration instead (shared, dynamically loaded
# CPU variants + Adreno OpenCL, as engine/llama/src/main/cpp does); android.sh builds, pushes
# and runs it on a connected phone together with the app's pinned Hexagon libraries.
cmake_minimum_required(VERSION 3.22)
project(autobot_llama_bench LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(REPO ${CMAKE_CURRENT_LIST_DIR}/../../..)
set(LLAMA_SRC ${REPO}/third_party/llama.cpp)
# Another checkout of core/ can be benchmarked against this one: -DAUTOBOT_CORE_DIR=<dir>.
set(AUTOBOT_CORE_DIR ${REPO}/engine/llama/src/main/cpp/core CACHE PATH "engine core sources")
set(CORE_DIR ${AUTOBOT_CORE_DIR})

if(ANDROID)
    set(BUILD_SHARED_LIBS    ON  CACHE BOOL "" FORCE)
else()
    set(BUILD_SHARED_LIBS    OFF CACHE BOOL "" FORCE)
endif()
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

if(ANDROID)
    # The app's native configuration (see engine/llama/src/main/cpp/CMakeLists.txt).
    set(APP_CPP ${REPO}/engine/llama/src/main/cpp)
    set(GGML_NATIVE           OFF CACHE BOOL "" FORCE)
    set(GGML_BACKEND_DL       ON  CACHE BOOL "" FORCE)
    set(GGML_CPU_ALL_VARIANTS ON  CACHE BOOL "" FORCE)
    set(AUTOBOT_OPENCL_HEADERS ${REPO}/third_party/OpenCL-Headers)
    set(OPENCL_ICD_LOADER_HEADERS_DIR ${AUTOBOT_OPENCL_HEADERS} CACHE PATH "" FORCE)
    set(OPENCL_ICD_LOADER_BUILD_TESTING OFF CACHE BOOL "" FORCE)
    set(ENABLE_OPENCL_LAYERS OFF CACHE BOOL "" FORCE)
    set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
    add_subdirectory(${REPO}/third_party/OpenCL-ICD-Loader build-opencl-icd)
    list(PREPEND CMAKE_MODULE_PATH ${APP_CPP}/cmake)
    set(GGML_OPENCL                    ON CACHE BOOL "" FORCE)
    set(GGML_OPENCL_EMBED_KERNELS      ON CACHE BOOL "" FORCE)
    set(GGML_OPENCL_USE_ADRENO_KERNELS ON CACHE BOOL "" FORCE)
endif()

add_subdirectory(${LLAMA_SRC} build-llama)

add_executable(llama_bench llama_bench.cpp ${CORE_DIR}/autobot_llama.cpp)
target_include_directories(llama_bench PRIVATE ${CORE_DIR} ${LLAMA_SRC}/common ${LLAMA_SRC}/include ${LLAMA_SRC}/tools/mtmd)
target_link_libraries(llama_bench PRIVATE llama llama-common mtmd)
if(ANDROID)
    target_link_libraries(llama_bench PRIVATE log)
endif()
if(APPLE)
    # Newer core entry points are weak in the bench, so an older core still links.
    target_link_options(llama_bench PRIVATE "-Wl,-U,_autobot_llama_load_abortable")
endif()
