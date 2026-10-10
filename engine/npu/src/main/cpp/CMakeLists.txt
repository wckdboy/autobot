cmake_minimum_required(VERSION 3.22)
project(autobot_npu LANGUAGES C CXX ASM)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(THIRD_PARTY ${CMAKE_CURRENT_LIST_DIR}/../../../../../third_party)

if (NOT EXISTS "${QAIRT_INCLUDE}/QnnInterface.h")
    message(FATAL_ERROR "QNN headers not found in '${QAIRT_INCLUDE}' (run :engine:npu:fetchQairtHeaders)")
endif ()

# MNN (pinned submodule): text encoders of NPU packages and the GPU (OpenCL) diffusion path.
# Built static with hidden symbols into libautobot_npu.so; OpenCL is opened with dlopen at
# runtime, so phones without it fall back to the CPU.
set(MNN_BUILD_SHARED_LIBS        OFF CACHE BOOL "" FORCE)
set(MNN_SEP_BUILD                OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_TOOLS              OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_CONVERTER          OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_TEST               OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_DEMO               OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_BENCHMARK          OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_QUANTOOLS          OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_LLM                OFF CACHE BOOL "" FORCE)
set(MNN_OPENCL                   ON  CACHE BOOL "" FORCE)
set(MNN_USE_SYSTEM_LIB           OFF CACHE BOOL "" FORCE)
set(MNN_ARM82                    ON  CACHE BOOL "" FORCE)
set(MNN_LOW_MEMORY               ON  CACHE BOOL "" FORCE)
set(MNN_SUPPORT_TRANSFORMER_FUSE ON  CACHE BOOL "" FORCE)
set(MNN_OPENMP                   OFF CACHE BOOL "" FORCE)
set(MNN_USE_THREAD_POOL          ON  CACHE BOOL "" FORCE)
set(MNN_USE_LOGCAT               ON  CACHE BOOL "" FORCE)
set(MNN_HIDDEN                   ON  CACHE BOOL "" FORCE)
add_subdirectory(${THIRD_PARTY}/MNN build-mnn)

add_library(autobot_npu SHARED
    clip_tokenizer.cpp
    mnn_model.cpp
    npu_jni.cpp
    qnn_runtime.cpp
    scheduler.cpp
    sd_pipeline.cpp
)
target_include_directories(autobot_npu PRIVATE ${QAIRT_INCLUDE} ${THIRD_PARTY}/MNN/include ${THIRD_PARTY}/MNN/3rd_party)
target_link_libraries(autobot_npu PRIVATE MNN android log dl)
target_compile_options(autobot_npu PRIVATE -O3 -fvisibility=hidden)
# Only the JNI entry points are exported; MNN stays private to this library.
target_link_options(autobot_npu PRIVATE "-Wl,--exclude-libs,ALL" "-Wl,--whole-archive" "$<TARGET_FILE:MNN>" "-Wl,--no-whole-archive")
