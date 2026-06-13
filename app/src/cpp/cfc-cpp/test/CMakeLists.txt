cmake_minimum_required(VERSION 3.10)

project(cfc_cpp_test)

set (SOURCES
	test.cpp
	MultiThreadedDecoderTest.cpp
)

include_directories(
	${libcimbar_SOURCE_DIR}/test
	${libcimbar_SOURCE_DIR}/test/lib
	${CMAKE_CURRENT_SOURCE_DIR}/..
	${CMAKE_CURRENT_SOURCE_DIR}/../..
	${libcimbar_SOURCE_DIR}/src/lib
	${libcimbar_SOURCE_DIR}/src/third_party_lib
)

add_executable (
	cfc_cpp_test
	${SOURCES}
)

add_test(cfc_cpp_test cfc_cpp_test)

target_link_libraries(cfc_cpp_test

	cimb_translator
	extractor

	correct_static
	wirehair
	zstd
	${OPENCV_LIBS}
	${CPPFILESYSTEM}
)
