# Vortek renderer source provenance

These files are vendored from `brunodev85/winlator-app` commit
`ca3d735a60d653a787daf16d14fafef28d9c2c23`, directory
`app/src/main/cpp/vortekrenderer`, under the repository's LGPL-2.1 license.

The upstream `src/main.c` is intentionally omitted. Pocket Realm builds the
same protocol implementation with `../src/vortek_system_main.c`, a bounded JNI
adapter that opens Android's public `libvulkan.so` soname only. Packaged Turnip
uses its own guest ICD and is never loaded into the Android Vortek process.
