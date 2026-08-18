cmake_minimum_required(VERSION 3.16)

set(POCKET_SERVER_ROOT ${CMAKE_CURRENT_SOURCE_DIR})
set(CMANGOS_SRC ${CMAKE_SOURCE_DIR}/src)

set(POCKET_COMMON
    ${POCKET_SERVER_ROOT}/src/server_common.cpp
)

add_library(pocket_realmd_runtime SHARED
    ${POCKET_COMMON}
    ${POCKET_SERVER_ROOT}/src/realmd_runtime.cpp
    ${CMANGOS_SRC}/realmd/AuthSocket.cpp
    ${CMANGOS_SRC}/realmd/RealmList.cpp
)

add_library(pocket_world_runtime SHARED
    ${POCKET_COMMON}
    ${POCKET_SERVER_ROOT}/src/world_runtime.cpp
    ${POCKET_SERVER_ROOT}/src/world_runnable.cpp
    ${CMANGOS_SRC}/mangosd/Main.cpp
    ${CMANGOS_SRC}/mangosd/Master.cpp
    ${CMANGOS_SRC}/mangosd/CliRunnable.cpp
)

foreach(target pocket_realmd_runtime pocket_world_runtime)
    target_compile_definitions(${target} PRIVATE POCKET_EMBEDDED POCKET_SERVER_RUNTIME)
    target_include_directories(${target} PRIVATE
        ${POCKET_SERVER_ROOT}/include
        ${CMAKE_SOURCE_DIR}/../../schemas/abi
        ${CMAKE_SOURCE_DIR}/src
        ${CMAKE_SOURCE_DIR}/src/shared
        ${CMAKE_SOURCE_DIR}/src/framework
        ${CMAKE_SOURCE_DIR}/src/game
        ${CMAKE_SOURCE_DIR}/src/mangosd
        ${CMAKE_SOURCE_DIR}/src/realmd
        ${CMAKE_SOURCE_DIR}/dep/include/gsoap
        ${MYSQL_INCLUDE_DIR}
    )
    target_link_options(${target} PRIVATE
        "-Wl,-z,max-page-size=0x4000"
        "-Wl,--exclude-libs,ALL"
    )
    set_target_properties(${target} PROPERTIES POSITION_INDEPENDENT_CODE ON)
endforeach()

target_link_libraries(pocket_realmd_runtime PRIVATE
    shared ${MYSQL_LIBRARY} OpenSSL::SSL OpenSSL::Crypto z dl
)
target_link_libraries(pocket_world_runtime PRIVATE
    game shared gsoap ${MYSQL_LIBRARY} OpenSSL::SSL OpenSSL::Crypto z dl
)
if(BUILD_PLAYERBOTS)
    # CMaNGOS applies this directory-locally to game/mangosd, so the embedded
    # facade must opt in explicitly even though libplayerbots is linked through
    # game. Without it, the typed O13 bridge compiles out silently.
    target_compile_definitions(pocket_world_runtime PRIVATE ENABLE_PLAYERBOTS)
    target_include_directories(pocket_world_runtime PRIVATE
        ${CMAKE_SOURCE_DIR}/dep/g3dlite
        ${CMAKE_SOURCE_DIR}/dep/recastnavigation
        ${CMAKE_SOURCE_DIR}/dep/recastnavigation/Detour/Include
    )
endif()

set_target_properties(pocket_realmd_runtime PROPERTIES OUTPUT_NAME pocket_realmd_runtime)
set_target_properties(pocket_world_runtime PROPERTIES OUTPUT_NAME pocket_world_runtime)
