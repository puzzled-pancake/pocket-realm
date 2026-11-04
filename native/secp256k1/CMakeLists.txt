cmake_minimum_required(VERSION 3.22)
project(pocketrealm_secp256k1_jni LANGUAGES C)

if(NOT ANDROID OR NOT SECP256K1_KMP_SOURCE_DIR)
    message(FATAL_ERROR "Set the Android toolchain and SECP256K1_KMP_SOURCE_DIR")
endif()

set(BUILD_SHARED_LIBS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(SECP256K1_ENABLE_MODULE_RECOVERY ON)
set(SECP256K1_BUILD_BENCHMARK OFF)
set(SECP256K1_BUILD_TESTS OFF)
set(SECP256K1_BUILD_EXHAUSTIVE_TESTS OFF)
set(SECP256K1_BUILD_CTIME_TESTS OFF)
add_subdirectory("${SECP256K1_KMP_SOURCE_DIR}/native/secp256k1" secp256k1)

add_library(secp256k1-jni SHARED
    "${SECP256K1_KMP_SOURCE_DIR}/jni/c/src/fr_acinq_secp256k1_Secp256k1CFunctions.c")
target_include_directories(secp256k1-jni PRIVATE
    "${SECP256K1_KMP_SOURCE_DIR}/jni/c/headers/java"
    "${SECP256K1_KMP_SOURCE_DIR}/native/secp256k1")
target_link_libraries(secp256k1-jni PRIVATE secp256k1)
target_link_options(secp256k1-jni PRIVATE "-Wl,-z,max-page-size=16384")
