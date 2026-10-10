# Invoked as: cmake -P embed_kernel.cmake <embed_kernel.py> <kernel.cl> <kernel.cl.h>
# Writes the kernel source as one raw string literal (what embed_kernel.py produces line by line).
set(src "${CMAKE_ARGV4}")
set(dst "${CMAKE_ARGV5}")
file(READ "${src}" body)
string(FIND "${body}" ")autobot_cl\"" clash)
if (NOT clash EQUAL -1)
    message(FATAL_ERROR "kernel ${src} contains the raw string delimiter")
endif ()
file(WRITE "${dst}" "R\"autobot_cl(${body})autobot_cl\"\n")
