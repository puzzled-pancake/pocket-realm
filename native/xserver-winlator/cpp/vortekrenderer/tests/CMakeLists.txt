cmake_minimum_required(VERSION 3.16)
project(vortek_handle_authority_tests LANGUAGES C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
if(NOT WIN32)
    find_package(Threads REQUIRED)
endif()

add_executable(handle_registry_test
    ../src/handle_registry.c
    handle_registry_test.c
)
target_include_directories(handle_registry_test PRIVATE ../include)
if(NOT WIN32)
    target_link_libraries(handle_registry_test PRIVATE Threads::Threads)
endif()

add_executable(vortek_decode_test
    ../src/vortek_decode.c
    vortek_decode_test.c
)
target_include_directories(vortek_decode_test PRIVATE ../include)

enable_testing()
add_test(NAME handle_registry COMMAND handle_registry_test)
add_test(NAME vortek_decode COMMAND vortek_decode_test)

add_executable(request_handler_contract_test request_handler_contract_test.c)
target_include_directories(request_handler_contract_test PRIVATE ../include)
add_executable(xwindow_transaction_test xwindow_transaction_test.c)
target_include_directories(xwindow_transaction_test PRIVATE ../include)
add_executable(vortek_safe_lane_test
    ../src/vortek_safe_lane.c
    vortek_safe_lane_test.c
)
target_include_directories(vortek_safe_lane_test PRIVATE ../include)
if(CMAKE_C_COMPILER_ID MATCHES "Clang|GNU")
    foreach(target handle_registry_test vortek_decode_test request_handler_contract_test xwindow_transaction_test vortek_safe_lane_test)
        target_compile_options(${target} PRIVATE
            -Wall -Wextra -Werror -Wconversion -Wshadow
            -fsanitize=address,undefined
        )
        target_link_options(${target} PRIVATE
            -fsanitize=address,undefined
        )
    endforeach()
elseif(MSVC)
    foreach(target handle_registry_test vortek_decode_test request_handler_contract_test xwindow_transaction_test vortek_safe_lane_test)
        target_compile_options(${target} PRIVATE
            $<$<CONFIG:RelWithDebInfo>:/fsanitize=address>
        )
        target_link_options(${target} PRIVATE
            $<$<CONFIG:RelWithDebInfo>:/INCREMENTAL:NO>
        )
    endforeach()
endif()
add_test(NAME request_handler_contract COMMAND request_handler_contract_test)
add_test(NAME xwindow_transaction COMMAND xwindow_transaction_test)
add_test(NAME vortek_safe_lane COMMAND vortek_safe_lane_test)
