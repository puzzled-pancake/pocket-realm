# native/packaging/CMakeLists.txt
#
# G0 packaging experiments (O05 / report §8.4 PKG-01/02/06). Built standalone
# against the pinned NDK; outputs are staged into the Android APK by the Gradle
# stageNativeLibs task.
#
#   libpocketpkgtest.so       JNI shim library (PKG-02/06 from :pkg child)
#   libpocket_pkg_launcher.so PIE executable renamed to .so so AGP extracts it
#                             into nativeLibraryDir (PKG-01 experiment variant)
#
# Both link only libc/libdl/liblog (platform libs supplied by Android). No
# platform .so is ever staged. 16 KB page-size alignment matches mangosd.
cmake_minimum_required(VERSION 3.16)
project(pocket_pkg LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

add_library(pocket_pkg_include INTERFACE)
target_include_directories(pocket_pkg_include INTERFACE ${CMAKE_CURRENT_SOURCE_DIR}/include)

# ---- libpocketpkgtest.so: the JNI shim + crash + dlopen helper --------------
add_library(pocketpkgtest SHARED
    ${CMAKE_CURRENT_SOURCE_DIR}/src/jni_shim.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/src/realm_so.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/src/page_size.c
    ${CMAKE_CURRENT_SOURCE_DIR}/src/crash.c
)
target_link_libraries(pocketpkgtest PRIVATE pocket_pkg_include log dl)
set_target_properties(pocketpkgtest PROPERTIES
    LINK_FLAGS "-Wl,-z,max-page-size=0x4000"
)

# ---- libpocket_pkg_launcher.so: a PIE executable (PKG-01) -------------------
# Output name is a .so so AGP ships it under lib/<abi>/ and, under the
# experiment variant (useLegacyPackaging=true), extracts it with the +x bit
# into nativeLibraryDir. It is a real ELF executable (has a main), not a dlopen
# target. Built as a MODULE-free PIE.
add_executable(pocket_pkg_launcher ${CMAKE_CURRENT_SOURCE_DIR}/src/launcher.c)
set_target_properties(pocket_pkg_launcher PROPERTIES
    OUTPUT_NAME "pocket_pkg_launcher"
    # Position-independent executable so it loads at a 16 KB-aligned base.
    POSITION_INDEPENDENT_CODE ON
    LINK_FLAGS "-Wl,-z,max-page-size=0x4000 -pie"
)
target_link_libraries(pocket_pkg_launcher PRIVATE pocket_pkg_include dl log)
