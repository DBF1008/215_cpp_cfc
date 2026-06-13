cmake_minimum_required(VERSION 3.4.1)

add_library(cfc-cpp
        SHARED

        jni.cpp
        MultiThreadedDecoder.h
)

find_library(log-lib

        log
)

target_link_libraries(cfc-cpp

        cimb_translator
        extractor

        correct_static
        wirehair
        zstd
        ${OPENCV_LIBS}
        ${log-lib}
)

target_compile_options(cfc-cpp  PUBLIC "-DZSTD_STATIC_LINKING_ONLY")

# Host-only unit tests for the JNI-independent session state. Skipped in the
# Android build, which sets DISABLE_TESTS.
if(NOT DISABLE_TESTS)
	add_subdirectory(test build/test)
endif()
