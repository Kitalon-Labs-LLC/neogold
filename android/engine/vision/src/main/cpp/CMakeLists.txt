cmake_minimum_required(VERSION 3.22.1)
project("apriltag_jni" C)

# Release gate S4 (doc 8 Section 6.1): 16 KB page alignment for 64-bit Android ABIs
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")

add_library(apriltag_jni SHARED
    apriltag_jni.c
)

find_library(log-lib log)

target_link_libraries(apriltag_jni
    ${log-lib}
    m
)
