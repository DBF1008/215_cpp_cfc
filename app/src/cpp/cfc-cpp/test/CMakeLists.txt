cmake_minimum_required(VERSION 3.10)

project(cfc_test)

set (SOURCES
	test.cpp
	ThreadPoolBackpressureTest.cpp
	MultiThreadedDecoderTest.cpp
)

include_directories(
	${libcimbar_SOURCE_DIR}/test
	${CMAKE_CURRENT_SOURCE_DIR}/..
	${CMAKE_CURRENT_SOURCE_DIR}/../../concurrent
	${libcimbar_SOURCE_DIR}/src/lib
	${libcimbar_SOURCE_DIR}/src/third_party_lib
)

add_executable (
	cfc_test
	${SOURCES}
)

add_test(cfc_test cfc_test)

target_link_libraries(cfc_test

	cimb_translator
	extractor

	correct_static
	wirehair
	zstd
	${OPENCV_LIBS}
	${CPPFILESYSTEM}
)
