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
# Note: tmp_shim.c is intentionally NOT compiled here. It was a draft LD_PRELOAD
# /tmp redirect for the glibc namespace; it is incomplete (missing chdir() hook,
# and would need to be built against the glibc toolchain, not the NDK, to be
# usable as an LD_PRELOAD in the glibc namespace). It is removed from the tree
# to avoid implying it is wired up. The /tmp blocker is handled by the namespace
# mechanism selected in Phase 1.6c (S-5), not by a Bionic-compiled shim.
add_library(wine_spike SHARED
    ${CMAKE_CURRENT_SOURCE_DIR}/src/jni_shim.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/src/wine_launcher.c
    ${CMAKE_CURRENT_SOURCE_DIR}/src/wine_probe.c
    ${CMAKE_CURRENT_SOURCE_DIR}/src/pe_cache.c
    ${CMAKE_CURRENT_SOURCE_DIR}/src/symlink_tree.c
    ${CMAKE_CURRENT_SOURCE_DIR}/src/err_str.c
    ${CMAKE_CURRENT_SOURCE_DIR}/src/sigsys_diag.c
    ${CMAKE_CURRENT_SOURCE_DIR}/src/trampoline_launcher.c
    ${CMAKE_CURRENT_SOURCE_DIR}/src/proot_launcher.c
    ${CMAKE_CURRENT_SOURCE_DIR}/src/proot_run.c
)
target_include_directories(wine_spike PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/include)
target_link_libraries(wine_spike PRIVATE log dl)
set_target_properties(wine_spike PROPERTIES
    OUTPUT_NAME "wine_spike"
    LINK_FLAGS "-Wl,-z,max-page-size=0x4000"
)

# ---- libwine_trampoline.so: S-5(a) Bionic trampoline PIE -------------------
# A standalone PIE program (not a library). Named lib*.so so AGP extracts it
# into nativeLibraryDir with the +x bit. The trampoline re-execve's the glibc
# loader; see trampoline/wine_trampoline.c.
add_executable(wine_trampoline
    ${CMAKE_CURRENT_SOURCE_DIR}/trampoline/wine_trampoline.c
)
target_link_libraries(wine_trampoline PRIVATE log)
set_target_properties(wine_trampoline PROPERTIES
    OUTPUT_NAME "wine_trampoline"
    # PIE is the default for executables on modern NDK; force it explicit.
    LINK_FLAGS "-Wl,-z,max-page-size=0x4000 -pie"
)
# Produce the output as libwine_trampoline.so so AGP packages it as a jniLib.
# We rename via a post-build copy because CMake can't name an executable with .so.
add_custom_command(TARGET wine_trampoline POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E copy
            $<TARGET_FILE:wine_trampoline>
            ${CMAKE_CURRENT_BINARY_DIR}/libwine_trampoline.so
    COMMENT "Packaging wine_trampoline PIE as libwine_trampoline.so for AGP"
)
