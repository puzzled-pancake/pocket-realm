cmake_minimum_required(VERSION 3.16)
project(pocket_sqlite_seam C)

# The repo-pinned SQLite amalgamation directory (sqlite3.c + sqlite3.h),
# staged by tools/stage_sqlite_amalgamation.py. Fail loud when absent:
# this project never falls back to a system SQLite.
set(SQLITE_AMALGAMATION "" CACHE PATH "pinned sqlite-amalgamation-3460100 directory")
if(NOT EXISTS "${SQLITE_AMALGAMATION}/sqlite3.c")
    message(FATAL_ERROR
        "SQLITE_AMALGAMATION does not contain sqlite3.c "
        "(stage it first: python tools/stage_sqlite_amalgamation.py)")
endif()

# JNI headers come from the JDK (find_package(JNI) resolves JAVA_HOME /
# the registry on Windows, the same discovery the realm-runtime lane
# relies on).
find_package(JNI REQUIRED)

# The PRODUCTION define set — byte-for-byte the set the fidelity
# harness (tools/sqlite_exec_file.c) compiles with, pinned by
# tests/test_sqlite_seeding.py PRODUCTION_DEFINES and re-pinned for
# this lane by tests/test_win_sqlite_seam.py. SQLITE_THREADSAFE=2:
# connections are single-thread by contract (see the JNI source header).
add_library(pocket_sqlite SHARED
    src/desktop_sqlite_jni.c
    ${SQLITE_AMALGAMATION}/sqlite3.c
)
target_include_directories(pocket_sqlite PRIVATE
    ${SQLITE_AMALGAMATION}
    ${JNI_INCLUDE_DIRS}
)
target_compile_definitions(pocket_sqlite PRIVATE
    SQLITE_ENABLE_FTS5
    SQLITE_ENABLE_RTREE
    SQLITE_ENABLE_COLUMN_METADATA
    SQLITE_ENABLE_JSON1
    SQLITE_DEFAULT_MEMSTATUS=0
    SQLITE_LIKE_DOESNT_MATCH_BLOBS
    SQLITE_OMIT_DEPRECATED
    SQLITE_USE_URI=1
    SQLITE_ENABLE_DBSTAT_VTAB
    SQLITE_DEFAULT_WAL_SYNCHRONOUS=2
    SQLITE_THREADSAFE=2
)
if(MSVC)
    # /MD (the Release default) matches the x64-windows-static-md CRT
    # posture of the whole Windows native lane.
    target_compile_options(pocket_sqlite PRIVATE /utf-8 /W3)
endif()
set_target_properties(pocket_sqlite PROPERTIES OUTPUT_NAME pocket_sqlite)
