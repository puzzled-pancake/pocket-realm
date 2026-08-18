# native/pocket-runtime/CMakeLists.txt
#
# Builds libpocketrealm.so: the embeddable realm lifecycle C ABI facade that
# wraps CMaNGOS/Playerbots for in-process control by the Android supervisor
# (and, later, the connected Rust Realm Kernel).
#
# This target is the ONLY consumer of POCKET_EMBEDDED. It links the same
# game/shared/playerbots static libraries as the standalone mangosd, but with
# POCKET_EMBEDDED defined so the startup exit(1) gates route to POCKET_FATAL
# (throw) instead of killing the host process.
#
# POCKET_RUNTIME_DIR is passed by build_native.py (the absolute path to this
# directory) so the cmangos src/CMakeLists.txt hook can add_subdirectory it
# regardless of where the submodule is checked out.
cmake_minimum_required(VERSION 3.16)

# The facade source set.
set(POCKET_RUNTIME_SRCS
    ${CMAKE_CURRENT_SOURCE_DIR}/src/abi.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/src/embed.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/src/realm.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/src/lifecycle.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/src/lifecycle_realmd.cpp
)

# Upstream mangosd/realmd sources that are normally compiled ONLY into the
# standalone executables (they're not in libgame/libshared). The embedded build
# compiles them here with POCKET_EMBEDDED defined so their main()/signal hooks/
# conflicting globals are gated out (#ifndef POCKET_EMBEDDED), leaving the
# process-global definitions (WorldDatabase, LoginDatabase, realmID, etc.) and
# the classes the facade drives (Master, WorldRunnable, AuthSocket, RealmList).
#
# We deliberately EXCLUDE CliRunnable, RASocket, MaNGOSsoap, soapC/soapServer:
# they implement console/RA/SOAP control channels that the embedded path does
# not use (no console-only control). Compiling them would pull
# stdin/signal dependencies back in.
set(CMANGOS_DIR ${CMAKE_SOURCE_DIR}/src)
list(APPEND POCKET_RUNTIME_SRCS
    ${CMANGOS_DIR}/mangosd/Main.cpp          # globals: WorldDatabase/LoginDatabase/realmID (main gated out)
    ${CMANGOS_DIR}/mangosd/Master.cpp        # Master::StartDatabasesEmbedded/InitWorldEmbedded/...
    ${CMANGOS_DIR}/mangosd/WorldRunnable.cpp # the world update loop the facade launches
    ${CMANGOS_DIR}/mangosd/CliRunnable.cpp   # ChatHandler command impls referenced by libgame (stdin run() gated out)
    ${CMANGOS_DIR}/realmd/AuthSocket.cpp     # the auth protocol handler
    ${CMANGOS_DIR}/realmd/RealmList.cpp      # sRealmList
)

add_library(pocketrealm SHARED ${POCKET_RUNTIME_SRCS})

target_include_directories(pocketrealm
    PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/include
    PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/src
)

# The embedded build: route exit() -> throw at the boundary.
target_compile_definitions(pocketrealm PRIVATE POCKET_EMBEDDED)

# Reuse the CMaNGOS static libraries already built by the parent project.
# These targets exist because BUILD_GAME_SERVER + BUILD_LOGIN_SERVER are ON.
target_link_libraries(pocketrealm
    PRIVATE
        game            # the world server logic (sWorld, ObjectMgr, DBCStores...)
        shared          # databases, config, log, network
        gsoap           # Master.cpp includes MaNGOSsoap.h -> stdsoap2.h symbols
)

# playerbots is linked into mangosd via the modules path; link it here too so
# the embedded bring-up initializes the bot subsystem (BOT_SUBSYSTEM_INITIALIZED).
if(TARGET playerbots)
    target_link_libraries(pocketrealm PRIVATE playerbots)
endif()

# realmd's AuthSocket/RealmList are compiled under BUILD_LOGIN_SERVER (shared lib).
target_link_libraries(pocketrealm PRIVATE shared)

# Pull in the CMaNGOS include roots (game, shared, framework) so lifecycle.cpp
# can find World.h, Master.h, DatabaseEnv.h, etc. These match the include dirs
# the game/shared targets export, but we add them explicitly because pocketrealm
# is not under src/.
get_target_property(_game_inc game INTERFACE_INCLUDE_DIRECTORIES)
if(_game_inc)
    target_include_directories(pocketrealm PRIVATE ${_game_inc})
endif()
get_target_property(_shared_inc shared INTERFACE_INCLUDE_DIRECTORIES)
if(_shared_inc)
    target_include_directories(pocketrealm PRIVATE ${_shared_inc})
endif()
# The mangosd dir (Master.h, RASocket.h) and realmd dir (AuthSocket.h, RealmList.h)
target_include_directories(pocketrealm PRIVATE
    ${CMAKE_SOURCE_DIR}/src/mangosd
    ${CMAKE_SOURCE_DIR}/src/realmd
    ${CMAKE_SOURCE_DIR}/src
    # Master.cpp pulls in MaNGOSsoap.h -> stdsoap2.h (the embedded path never
    # starts the SOAP thread, but the TU still compiles the include).
    ${CMAKE_SOURCE_DIR}/dep/include/gsoap
)

# 16 KB page-size alignment (product requirement; matches mangosd/realmd).
set_target_properties(pocketrealm PROPERTIES
    LINK_FLAGS "-Wl,-z,max-page-size=0x4000"
    POSITION_INDEPENDENT_CODE ON
)

# ---------------------------------------------------------------------------
# Native lifecycle test runner. A tiny main() that exercises the full C ABI
# (create/start/health/save/stop/destroy x2) and exits 0 on PASS, non-zero on
# FAIL. Built as a separate executable so smoke_native.py can push & run it on
# the x86_64 emulator.
# ---------------------------------------------------------------------------
if(BUILD_POCKET_RUNTIME_TESTS)
    enable_testing()
    add_executable(pocket_lifecycle_test ${CMAKE_CURRENT_SOURCE_DIR}/tests/test_lifecycle.cpp)
    target_link_libraries(pocket_lifecycle_test PRIVATE pocketrealm)
    target_compile_definitions(pocket_lifecycle_test PRIVATE POCKET_EMBEDDED)
    add_test(NAME pocket_lifecycle COMMAND pocket_lifecycle_test)
endif()
