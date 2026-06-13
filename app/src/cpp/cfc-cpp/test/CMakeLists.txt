cmake_minimum_required(VERSION 3.10)

project(cfc_cpp_test)

set (SOURCES
	test.cpp
	AutoModeTest.cpp
)

include_directories(
	${CMAKE_CURRENT_SOURCE_DIR}/../../libcimbar/test
	${CMAKE_CURRENT_SOURCE_DIR}/..
)

add_executable (
	cfc_cpp_test
	${SOURCES}
)

add_test(cfc_cpp_test cfc_cpp_test)
