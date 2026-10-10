# ggml-opencl only needs Python to turn each kernel into a C++ string literal. This shim does the
# same with CMake itself, so building does not require a Python install.
set(Python3_FOUND TRUE)
set(Python3_Interpreter_FOUND TRUE)
set(Python3_EXECUTABLE "${CMAKE_COMMAND}" -P "${CMAKE_CURRENT_LIST_DIR}/embed_kernel.cmake")
