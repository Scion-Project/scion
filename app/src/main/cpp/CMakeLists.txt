# The AVF display's frame copy (scfb_present.c), the AVF camera's (sccam_put.c), the AVF GPU's device
# (scgpu.c: gfxstream, libgfxstream_backend.so, loaded at run time) and the AVF sound's player (scsnd.c,
# AAudio). Built by Gradle's externalNativeBuild.
cmake_minimum_required(VERSION 3.22)
project(scfb C)
add_library(scfb SHARED scfb_present.c sccam_put.c scgpu.c scsnd.c)
target_compile_options(scfb PRIVATE -O2 -Wall -Wextra -Werror)
target_link_options(scfb PRIVATE -Wl,-z,max-page-size=16384)
target_link_libraries(scfb android log dl aaudio m)
