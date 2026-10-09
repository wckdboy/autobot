cmake_minimum_required(VERSION 3.22)
project(autobot_llama LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Pinned llama.cpp checkout (git submodule, see /third_party).
set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/../../../../../third_party/llama.cpp)

# Only the library and `common` (chat templates, tool-call parsing, sampling).
set(BUILD_SHARED_LIBS     ON  CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON    ON  CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES  OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP       OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_UI        OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL         OFF CACHE BOOL "" FORCE)
set(LLAMA_SUBPROCESS      OFF CACHE BOOL "" FORCE)
set(LLAMA_USE_PREBUILT_UI OFF CACHE BOOL "" FORCE)

# CPU: every ARM feature level is built as its own backend and the best one for the phone is
# picked at runtime (no SIGILL on older cores, i8mm/SVE on newer ones). KleidiAI is off because
# it is fetched from the network at configure time, which breaks hermetic builds.
set(GGML_NATIVE           OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL       ON  CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS ON  CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE        OFF CACHE BOOL "" FORCE)
# ggml's own pthread pool instead of OpenMP: libomp aborts on some Android builds (thread fork
# under native bridges) and would add another shared library.
set(GGML_OPENMP           OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI     OFF CACHE BOOL "" FORCE)

add_subdirectory(${LLAMA_SRC} build-llama)

add_library(autobot_llama SHARED llama_jni.cpp)
target_include_directories(autobot_llama PRIVATE ${LLAMA_SRC}/common ${LLAMA_SRC}/include)
target_link_libraries(autobot_llama PRIVATE llama llama-common android log)
# Only the JNI entry points are exported.
target_link_options(autobot_llama PRIVATE "-Wl,--exclude-libs,ALL")
