cmake_minimum_required(VERSION 3.16)

set(POCKET_SERVER_ROOT ${CMAKE_CURRENT_SOURCE_DIR})
set(CMANGOS_SRC ${CMAKE_SOURCE_DIR}/src)

if(WIN32)
    # Windows-only dependency discovery, BEFORE the target definitions below
    # (the loop attaches JNI_INCLUDE_DIRS). The Android lane resolves `z`/`dl`
    # from the NDK sysroot; the Windows lane links vcpkg's zlib explicitly
    # and has no dl. SQLite3 and OpenSSL are found here too: the cmangos root
    # only discovers them under if(UNIX ...), and the fail-loud backend
    # selection still gates the DO_SQLITE define. JNI comes from the JDK on
    # Windows (the NDK ships it on Android).
    find_package(ZLIB REQUIRED)
    find_package(SQLite3 REQUIRED)
    find_package(OpenSSL REQUIRED)
    find_package(JNI REQUIRED)
    # OpenSSL's static libcrypto needs the Windows system shops: CAPI store
    # access (crypt32), sockets (ws2_32), registry/crypto (advapi32, user32).
    set(POCKET_PLATFORM_LIBS ZLIB::ZLIB crypt32 ws2_32 advapi32 user32)
else()
    set(POCKET_PLATFORM_LIBS z dl)
endif()

set(POCKET_COMMON
    ${POCKET_SERVER_ROOT}/src/server_common.cpp
)
if(WIN32)
    # Satisfies out-of-line boost::throw_exception references that MSVC PCH
    # objects can carry; aborts loudly if ever actually reached.
    list(APPEND POCKET_COMMON ${POCKET_SERVER_ROOT}/src/win_exception_shim.cpp)
endif()

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
    )
    if(WIN32)
        target_include_directories(${target} PRIVATE ${JNI_INCLUDE_DIRS})
        # The cmangos MSVC platform settings apply to its own targets only;
        # without /EHsc these TUs compile in the exception-disabled mode that
        # emits out-of-line boost::throw_exception calls (unresolved at link).
        target_compile_options(${target} PRIVATE /EHsc /utf-8 /bigobj /permissive-)
    endif()
    if(NOT WIN32)
        # ELF staging gates: 16 KiB max-page-size for Android 15+ and symbol
        # hiding of the statically linked dependencies. Both are meaningless
        # on PE/MSVC (no page-size relocation contract, no symbol
        # interposition between DLLs).
        target_link_options(${target} PRIVATE
            "-Wl,-z,max-page-size=0x4000"
            "-Wl,--exclude-libs,ALL"
        )
    endif()
    set_target_properties(${target} PROPERTIES POSITION_INDEPENDENT_CODE ON)
endforeach()

# Database backend link: exactly one engine's client library. Under SQLITE
# the in-tree backend links the pinned amalgamation static library and the
# MariaDB connector must not appear anywhere in the link (fail-loud backend
# selection); MySQL is the else-default.
if(SQLITE)
    set(POCKET_DB_LIBS SQLite::SQLite3)
else()
    set(POCKET_DB_LIBS ${MYSQL_LIBRARY})
    # Connector headers are a MySQL-lane input; under SQLITE the imported
    # SQLite3 target carries the include path and no MariaDB header may
    # enter the compile.
    target_include_directories(pocket_realmd_runtime PRIVATE ${MYSQL_INCLUDE_DIR})
    target_include_directories(pocket_world_runtime PRIVATE ${MYSQL_INCLUDE_DIR})
endif()

target_link_libraries(pocket_realmd_runtime PRIVATE
    shared ${POCKET_DB_LIBS} OpenSSL::SSL OpenSSL::Crypto ${POCKET_PLATFORM_LIBS}
)
target_link_libraries(pocket_world_runtime PRIVATE
    game shared gsoap ${POCKET_DB_LIBS} OpenSSL::SSL OpenSSL::Crypto ${POCKET_PLATFORM_LIBS}
)
if(BUILD_PLAYERBOTS)
    # CMaNGOS applies this directory-locally to game/mangosd, so the embedded
    # facade must opt in explicitly even though libplayerbots is linked through
    # game. Without it, the typed bridge compiles out silently.
    target_compile_definitions(pocket_world_runtime PRIVATE ENABLE_PLAYERBOTS)
    target_include_directories(pocket_world_runtime PRIVATE
        ${CMAKE_SOURCE_DIR}/dep/g3dlite
        ${CMAKE_SOURCE_DIR}/dep/recastnavigation
        ${CMAKE_SOURCE_DIR}/dep/recastnavigation/Detour/Include
    )
endif()

# In-process llama.cpp backend for playerbot LLM chat. The playerbots sources
# compile against the vendored headers (the static target is created by the
# CMaNGOS build from the pristine-submodule mirror); the prebuilt shared
# libraries link only into the final world runtime so the symbols resolve at
# the last possible link step. arm64-v8a only: the backend is compiled out on
# every other ABI and falls back to the HTTP path.
if(POCKETREALM_LLAMA)
    if(NOT CMAKE_SYSTEM_PROCESSOR MATCHES "aarch64")
        message(FATAL_ERROR "POCKETREALM_LLAMA is only supported on aarch64 builds")
    endif()
    if(NOT EXISTS "${POCKETREALM_LLAMA_PREBUILT}/libllama.so")
        message(FATAL_ERROR "POCKETREALM_LLAMA_PREBUILT does not contain the vendored llama libraries")
    endif()
    if(NOT EXISTS "${POCKETREALM_LLAMA_INCLUDE}/llama.h")
        message(FATAL_ERROR "POCKETREALM_LLAMA_INCLUDE does not contain llama.h")
    endif()
    target_compile_definitions(playerbots PRIVATE POCKETREALM_LLAMA)
    target_include_directories(playerbots PRIVATE ${POCKETREALM_LLAMA_INCLUDE})
    target_link_directories(pocket_world_runtime PRIVATE ${POCKETREALM_LLAMA_PREBUILT})
    target_link_libraries(pocket_world_runtime PRIVATE
        llama
        llama-common
        ggml
        ggml-base
        ggml-cpu
    )
endif()

set_target_properties(pocket_realmd_runtime PROPERTIES OUTPUT_NAME pocket_realmd_runtime)
set_target_properties(pocket_world_runtime PROPERTIES OUTPUT_NAME pocket_world_runtime)
