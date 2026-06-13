cmake_minimum_required(VERSION 3.10)

project(cfc_cpp_test)

set (SOURCES
	test.cpp
	DecoderSessionTest.cpp
)

include_directories(
	${CMAKE_CURRENT_SOURCE_DIR}/..
	${CMAKE_CURRENT_SOURCE_DIR}/../../libcimbar/test
)

add_executable (
	cfc_cpp_test
	${SOURCES}
)

add_test(cfc_cpp_test cfc_cpp_test)

find_package(Threads REQUIRED)
target_link_libraries(cfc_cpp_test
	Threads::Threads
)
