cmake_minimum_required(VERSION 3.10)

project(concurrent_test)

set (SOURCES
	test.cpp
	thread_poolTest.cpp
)

include_directories(
	${CMAKE_CURRENT_SOURCE_DIR}/../../libcimbar/test
	${CMAKE_CURRENT_SOURCE_DIR}/../../libcimbar/src/third_party_lib
	${CMAKE_CURRENT_SOURCE_DIR}/..
)

add_executable (
	concurrent_test
	${SOURCES}
)

add_test(concurrent_test concurrent_test)

find_package(Threads REQUIRED)
target_link_libraries(concurrent_test
	Threads::Threads
)
