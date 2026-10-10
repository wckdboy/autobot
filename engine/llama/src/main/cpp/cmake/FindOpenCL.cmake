# Link ggml-opencl against the Khronos ICD loader built from source (headers + import library
# only). The loader itself is not packaged: at runtime the phone's own vendor libOpenCL.so
# (Adreno driver) is used, declared with <uses-native-library> in the manifest.
set(OpenCL_FOUND TRUE)
set(OpenCL_VERSION_STRING "3.0")
set(OpenCL_INCLUDE_DIRS "${AUTOBOT_OPENCL_HEADERS}")
set(OpenCL_INCLUDE_DIR "${AUTOBOT_OPENCL_HEADERS}")
set(OpenCL_LIBRARIES OpenCL)
set(OpenCL_LIBRARY OpenCL)
if (NOT TARGET OpenCL::OpenCL)
    add_library(OpenCL::OpenCL ALIAS OpenCL)
endif ()
