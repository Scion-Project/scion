# The AVF display's frame copy (scfb_present.c). Built by Gradle's externalNativeBuild.
cmake_minimum_required(VERSION 3.22)
project(scfb C)
add_library(scfb SHARED scfb_present.c)
target_compile_options(scfb PRIVATE -O2 -Wall -Wextra -Werror)
target_link_options(scfb PRIVATE -Wl,-z,max-page-size=16384)
target_link_libraries(scfb android log)
