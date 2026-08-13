cmake_minimum_required(VERSION 3.16)
project(vortek_lifecycle_tests LANGUAGES C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

find_package(Threads REQUIRED)
add_executable(thread_pool_lifecycle_test thread_pool_lifecycle_test.c)
target_include_directories(thread_pool_lifecycle_test PRIVATE ../include)
target_compile_definitions(thread_pool_lifecycle_test PRIVATE THREAD_POOL_TESTING=1)
target_link_libraries(thread_pool_lifecycle_test PRIVATE Threads::Threads)

enable_testing()
add_test(NAME thread_pool_lifecycle COMMAND thread_pool_lifecycle_test)
