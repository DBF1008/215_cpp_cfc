cmake_minimum_required(VERSION 3.10)
project(cfc_cpp_test)

set (SOURCES
	test.cpp
	JniSessionStateTest.cpp
)

include_directories(
	${libcimbar_SOURCE_DIR}/test
	${CMAKE_CURRENT_SOURCE_DIR}/..
)

add_executable(cfc_cpp_test ${SOURCES})
add_test(cfc_cpp_test cfc_cpp_test)
