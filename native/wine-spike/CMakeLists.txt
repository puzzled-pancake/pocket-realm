# native/wine-spike/CMakeLists.txt
#
# O06 Phase-1 Wine feasibility spike. Built standalone against the pinned NDK
# (same toolchain as native/packaging). Outputs are staged into the Android APK
# by the Gradle stageNativeLibs task.
#
#   libwine_spike.so      JNI shim library: builds the symlink tree, launches
#                         Wine via the APK-managed glibc loader, probes
#                         /proc/<pid>/maps, materializes the PE cache. Loaded
#                         by System.loadLibrary("wine_spike") in the spike runner.
#
# Links only Android/Bionic libs (log, dl). The glibc/Wine ELFs are NOT linked
# here — they are execve'd at runtime via the APK-managed loader. This library
# runs in the Android/Bionic namespace; Wine runs in the Linux/glibc namespace.
# The two namespaces are deliberately separate (per the approved plan).
#
# 16 KB page-size alignment for the 16K AVD lane.
cmake_minimum_required(VERSION 3.16)
project(pocket_wine_spike LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# ---- libwine_spike.so: the on-device spike helper --------------------------
add_library(wine_spike SHARED
    ${CMAKE_CURRENT_SOURCE_DIR}/src/jni_shim.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/src/wine_launcher.c
    ${CMAKE_CURRENT_SOURCE_DIR}/src/wine_probe.c
    ${CMAKE_CURRENT_SOURCE_DIR}/src/pe_cache.c
    ${CMAKE_CURRENT_SOURCE_DIR}/src/symlink_tree.c
    ${CMAKE_CURRENT_SOURCE_DIR}/src/err_str.c
)
target_include_directories(wine_spike PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/include)
target_link_libraries(wine_spike PRIVATE log dl)
set_target_properties(wine_spike PROPERTIES
    OUTPUT_NAME "wine_spike"
    LINK_FLAGS "-Wl,-z,max-page-size=0x4000"
)
