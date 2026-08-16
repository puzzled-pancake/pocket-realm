# Deployed sources for Phase-2 stall analysis

These are the EXACT sources of the currently deployed matched pair on the
Retroid Pocket 6 test device:

- Client libGL.so.1: build id gladio-eaa2a8d-arm64-glibc-gles-v5,
  497808 bytes, sha256 1a634a5d9259a87188979a29d93b098edf09e8ee1639b7fb05e446e31327e865
- Server libgladiorenderer.so: build id gladio-eaa2a8d-android-gles-server-1ffa75ce,
  1309312 bytes, sha256 1ffa75ce4f2dd45b85feb83c5f5db5208a496d5a89ef7a434833cfb8a9d76a28

Client sources are regenerated deterministically from upstream eaa2a8d +
signature-locked patches; the regenerated gl_calls.c sha256
(614693d16ae2cc20c5d78c6ed4073172124b3d05580ac934d1ac9c93266296f9) matches the
deployed-v5 content lock, proving these files are the deployed sources.

Server ring buffer: the server does not have its own copy; it compiles the
SHARED xserver ring_buffer.c/.h (files 18/19) - the client tree carries its own
copy (files 03/04) which is byte-different (see .h). Both are included.

## NOT yet deployed (pending run-4 instrumentation, edited then reverted here)
- Client gladio.h/main.c: gladioTraceSend() logging the last 32 sent request
  codes to stderr once per second.
- Server gl_context.c: heartbeat extended to print the trailing 64 consumed
  codes.
File 09 was reverted to the deployed heartbeat-only version; ask for the
pending-instrumentation diff if needed.

## Context reminders
- request code 352 = REQUEST_CODE_GL_LOAD_MATRIXF (glLoadMatrixf).
- The stall: both prior instrumented runs ended their final 4096-request
  heartbeat window at last=352; server then starves silently; no crash, no
  OOM, no wine exception; in run 1 wine audio kept streaming after the freeze.
- Device: Retroid Pocket 6 / Adreno 0676.53 / Android 13 / no root.

01_client_gl_context.h: 3359 bytes, sha256[:12]=46d19bd3ac72
02_client_gladio.h: 8887 bytes, sha256[:12]=4ec07424b679
03_client_ring_buffer.c: 5156 bytes, sha256[:12]=b957fd488034
04_client_ring_buffer.h: 3599 bytes, sha256[:12]=375efa94f7f7
05_client_gl_calls.c_DEPLOYED_V5: 278565 bytes, sha256[:12]=614693d16ae2
06_client_main.c: 8056 bytes, sha256[:12]=9af8775e2cad
07_client_request_codes.h: 30146 bytes, sha256[:12]=829a969e19a8
08_server_request_handler.c: 155970 bytes, sha256[:12]=11710f5eb2de
10_server_arb_program.c: 40041 bytes, sha256[:12]=9327d47cbb69
11_server_gl_renderer.c: 72060 bytes, sha256[:12]=4513508c72c5
12_server_shader_material.c: 42009 bytes, sha256[:12]=2c194bef79a2
13_server_shader_converter.c: 83192 bytes, sha256[:12]=042bd51a8712
14_server_gl_texture.c: 2463 bytes, sha256[:12]=2068ce88f435
15_server_gl_formats.c: 11136 bytes, sha256[:12]=4f1f758b0491
16_server_compressed_texture.c: 2970 bytes, sha256[:12]=6a34c8f132b0
17_server_texture_utils.h: 13076 bytes, sha256[:12]=04fb06084cff
18_server_ring_buffer.c_SHARED: 8731 bytes, sha256[:12]=3572154827a4
19_server_ring_buffer.h_SHARED: 3986 bytes, sha256[:12]=96a7ce5041c6
09_server_gl_context.c_DEPLOYED: deployed heartbeat-only version (undeployed tail-ring edit reverted), sha256[:12]=7a2d07cd4a39
## Added after the original manifest (v2 of this folder)

20_server_request_codes.h  — the SERVER's own copy; it DIFFERS from the client copy
                              (07). Decode heartbeat/tail request codes against THIS
                              one (server dispatch table is indexed by it).
21_CASE_FILE.md            — full debugging case file: system, fixes applied, defect
                              evidence, hypotheses, questions for the analysis pass.
22_STALL_LOG_EXCERPTS.txt  — verbatim logcat excerpts: run-2/run-3 death windows,
                              lmkd-negative check, run-1 audio-continues evidence,
                              recovered stderr for run 1 (spam) and run 3 (clean),
                              VirGL OOM kill-storm sample, run-3 session record.
23..25_device_screenshot_* — on-device screenshots taken by the tester around the
                              15:17-15:22 stall window (glitch state).

All 01-19 remain as documented above (deployed sources, sha-verified).
