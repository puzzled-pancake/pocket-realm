# native/xserver-winlator/cpp/CMakeLists.txt
#
# libwinlator.so — the in-app X-server native transport for the O06 Wine
# feasibility spike (S-3). Vendored source-matched from Winlator ca3d735
# (app/src/main/cpp/winlator/). See docs/patches/wine-provider-provenance.md.
#
# This is the PINNED native implementation — not a Java rewrite. It provides:
#   - xconnector_epoll.c : epoll accept loop + multi-threaded client poll
#   - xinput_stream.c    : buffered X11 input + SCM_RIGHTS fd-passing read
#   - xoutput_stream.c   : buffered X11 output + SCM_RIGHTS fd-passing write
#   - drawable.c         : BGRA Drawable ops reached by the GDI path
#   - arrays.c / ring_buffer.c : helpers used by the streams
#   - sysvshared_memory.c : SysV SHM (referenced by xinput/xoutput; the Java
#                           side stubs it for GDI-only, but JNI symbols must
#                           resolve so the lib links cleanly)
#   - gpu_image.c / gpu_helper.c : EGL/GLES texture helpers used by the renderer
#   - wine_registry_editor.c : prefix registry edits (used at wineboot init)
#
# The JNI method names are Java_com_winlator_xconnector_XConnectorEpoll_* /
# XInputStream_* / XOutputStream_* and Java_com_winlator_xserver_Drawable_* —
# they match the vendored Java classes' package paths exactly, so this lib is a
# drop-in for System.loadLibrary("winlator").
#
# Adaptations vs the upstream CMakeLists.txt:
#   - pinned to min CMake 3.16 (matches native/wine-spike + native/packaging)
#   - 16 KB max-page-size alignment for the 16K AVD lane
#   - explicit PIC + C99 (the sources are C99)
# The source list is UNCHANGED from the pinned commit (source correspondence).
cmake_minimum_required(VERSION 3.16)
project(pocket_winlator_xserver LANGUAGES C)

set(CMAKE_C_STANDARD 99)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Match the upstream build flags; suppress unused-function/implicit-decl noise
# (the sources compile clean upstream with these). The NDK toolchain builds with
# a hardened feature profile (_FORTIFY_SOURCE + strict C99); the Winlator
# sources use POSIX functions (clock_gettime, nanosleep) and strtod from
# <stdlib.h> that need _DEFAULT_SOURCE/_POSIX_C_SOURCE to be declared under
# strict C99. Upstream's Android Studio build sets these implicitly; we set
# them explicitly so the vendored sources compile UNMODIFIED (source match).
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O2 -Wno-unused-function -D_DEFAULT_SOURCE -D_POSIX_C_SOURCE=200809L -D_GNU_SOURCE")

# Force-include the NDK compat header so the vendored sources compile UNMODIFIED
# (see include/pocket_ndk_compat.h). Keeps the source-match provenance intact.
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -include ${CMAKE_CURRENT_SOURCE_DIR}/include/pocket_ndk_compat.h")

include_directories(${CMAKE_CURRENT_SOURCE_DIR}/include)

add_library(winlator SHARED
    ${CMAKE_CURRENT_SOURCE_DIR}/src/drawable.c
    ${CMAKE_CURRENT_SOURCE_DIR}/src/gpu_image.c
    ${CMAKE_CURRENT_SOURCE_DIR}/src/gpu_helper.c
    ${CMAKE_CURRENT_SOURCE_DIR}/src/sysvshared_memory.c
    ${CMAKE_CURRENT_SOURCE_DIR}/src/xconnector_epoll.c
    ${CMAKE_CURRENT_SOURCE_DIR}/src/xinput_stream.c
    ${CMAKE_CURRENT_SOURCE_DIR}/src/xoutput_stream.c
    ${CMAKE_CURRENT_SOURCE_DIR}/src/arrays.c
    ${CMAKE_CURRENT_SOURCE_DIR}/src/ring_buffer.c
    ${CMAKE_CURRENT_SOURCE_DIR}/src/wine_registry_editor.c
)

# O07 restores the source-matched Winlator GLX/OpenGL renderer required by
# WineD3D. The 38 files below are byte-identical to
# app/src/main/cpp/gladiorenderer at the pinned ca3d735 source commit.
set(GLADIO_DIR ${CMAKE_CURRENT_SOURCE_DIR}/gladiorenderer)
add_library(gladiorenderer SHARED
    ${GLADIO_DIR}/src/main.c
    ${GLADIO_DIR}/src/gl_context.c
    ${GLADIO_DIR}/src/gl_renderer.c
    ${GLADIO_DIR}/src/request_handler.c
    ${GLADIO_DIR}/src/shader_material.c
    ${GLADIO_DIR}/src/shader_converter.c
    ${GLADIO_DIR}/src/gl_formats.c
    ${GLADIO_DIR}/src/gl_texture.c
    ${GLADIO_DIR}/src/attrib_stack.c
    ${GLADIO_DIR}/src/gl_query.c
    ${GLADIO_DIR}/src/compressed_texture.c
    ${GLADIO_DIR}/src/arb_program.c
    ${GLADIO_DIR}/src/gl_buffer.c
    ${GLADIO_DIR}/src/gl_vao.c
    ${GLADIO_DIR}/src/gl_framebuffer.c
)
set_property(TARGET gladiorenderer PROPERTY C_STANDARD 23)
target_include_directories(gladiorenderer PRIVATE
    ${GLADIO_DIR}/include
    ${CMAKE_CURRENT_SOURCE_DIR}/include
)

target_link_libraries(winlator
    log
    android
    jnigraphics
    EGL
    GLESv2
    GLESv3
)

target_link_libraries(gladiorenderer
    winlator
    log
    android
    EGL
    GLESv2
    GLESv3
)

set_target_properties(winlator PROPERTIES
    OUTPUT_NAME "winlator"
    LINK_FLAGS "-Wl,-z,max-page-size=0x4000"
)

set_target_properties(gladiorenderer PROPERTIES
    OUTPUT_NAME "gladiorenderer"
    LINK_FLAGS "-Wl,-z,max-page-size=0x4000"
)
