# prebuilt/

Vendored llama-server runtime pieces staged into the :llm process jniLibs.
The shared llama.cpp closure (libllama.so, libllama-common.so, libggml.so,
libggml-base.so, libggml-cpu.so) is NOT vendored here: the app already stages
that exact set (native/.build-o09 -> staged-jniLibs, byte-verified against
native/llm/lockfile-arm64-v8a.json), and those hashes are byte-identical to
the accelerated (KleidiAI + dotprod) build these files came from — the server
resolves them from nativeLibraryDir at runtime. DO NOT add second copies of
those names here: duplicate sonames fail the APK merge.

Contents (arm64-v8a):
- llama-server               thin exec stub (packaged as libllamaserver.so)
- libllama-server-impl.so    actual server implementation
- libmtmd.so                 multimodal lib the impl links
- hexagon/libggmlhex.so      geniex v0.5.0 Hexagon NPU backend (llama.cpp @873e5d),
                             renamed so ggml's exe-dir scan never auto-loads it;
                             the runtime service sets GGML_BACKEND_PATH only in
                             NPU mode after HexagonProbe passes
- hexagon/dsp/*.so           HTP DSP skels v73/75/79/81, shipped as assets and
                             extracted to filesDir/dsp for ADSP_LIBRARY_PATH
- build-info/                staged as assets/runtime-build-info.properties
                             (variant/rev provenance of the vendored binaries,
                             staged here for parity)

ONE LOCAL PATCH on top of llama.cpp 6d05498: the server's SIGTERM/SIGINT
handler is installed BEFORE load_model, and a stop requested during the
load is honored AFTER it completes by going straight to the normal
clean-up path (tools/server/server.cpp, search "PocketRealm patch").
Both halves matter: upstream registers the handler only after the load
(a mid-load signal would hard-kill the process, leaking the Hexagon DSP
fastrpc session until reboot), and a bare terminate() queued during the
load would be silently reset by start_loop(). Rebuilt + llvm-stripped
from tools/llama-cpp (build-droid-kai); impl lib sha256
006f463dbc8198d9b2e94871ab3519e1d73af3ac0267837b2b03fc9889265332.

COUPLING: llama-server/libllama-server-impl/libmtmd are from llama.cpp master
6d05498 (accelerated build) — the same revision as the app's vendored closure. If
native/llm/lockfile-arm64-v8a.json ever changes revision, re-vendor these
files from a matching build (or the server may load a mismatched
libllama at runtime).

LICENSES: llama.cpp (llama-server, libllama-server-impl.so, libmtmd.so and
the app-side llama closure) is MIT: https://github.com/ggml-org/llama.cpp .
The Hexagon NPU backend (hexagon/libggmlhex.so) and the HTP DSP skels
(hexagon/dsp/*.so) come from Qualcomm's GenieX distribution
(github.com/qualcomm/GenieX, v0.5.0), which is BSD-3-Clause
(Copyright (c) 2024-2026, Qualcomm Technologies, Inc. and/or its
subsidiaries); that license and Qualcomm's Terms of Use are acknowledged in
android/app/src/main/assets/THIRD_PARTY_NOTICES.md, which must ride any
distribution of these binaries.
