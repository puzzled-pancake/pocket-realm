# Gladio Phase 2 Engineering Report — exact deployed bundle review

## 1. Source identity and validation status

- **VERIFIED:** The uploaded bundle identifies the deployed client as `gladio-eaa2a8d-arm64-glibc-gles-v5` and the deployed server as `gladio-eaa2a8d-android-gles-server-1ffa75ce`.
- **VERIFIED:** The attached deployed-v5 `gl_calls.c` hashes to `614693d16ae2cc20c5d78c6ed4073172124b3d05580ac934d1ac9c93266296f9`; all documented v5 self-check markers are present.
- **VERIFIED:** The client and server `request_codes.h` files have different raw hashes only because one uses LF and the other CRLF. Their 895 normalized lines are byte-for-byte semantically identical; no request-code mismatch was found.
- **VERIFIED:** The combined production patch changes 8 exact source files with 376 insertions and 87 deletions.
- **VERIFIED:** Every patch dry-runs and applies against the exact attached files, and the applied output is byte-identical to the supplied corrected tree.
- **VERIFIED:** The corrected client ring implementation passes `gcc -std=gnu11 -Wall -Wextra -Werror -fsyntax-only` and a local executable test covering three-part atomic publication and uint32 wrap from `0xfffffff8` to `0x00000008`.
- **VERIFIED:** No complete client/server compilation, Android NDK link, APK assembly, Wine/Box64 run, GLES driver run, or WoW run was possible.

## 2. Most important new finding: the first 4 GiB transport wrap is timing-compatible with run 3

- **VERIFIED:** Run 3 recorded 2,084,864 consumed requests and 1,723 presented frames. At a 30 fps cap, that is at least 57.43 seconds, 1,210.02 requests/frame, and at most 36,301 requests/second averaged over the minimum possible duration.
- **VERIFIED:** A 32-bit cumulative ring position wraps after 4,294,967,296 bytes. Reaching the first wrap by the minimum run-3 duration requires 71.32 MiB/s, or only 2,060.07 bytes per consumed request on average.
- **HYPOTHESIS:** That byte rate is plausible because draw requests inline client-array data. Therefore, run 3 is timing-compatible with the first ring-position wrap, although the logs do not prove the exact byte count.
- **VERIFIED:** Both attached ring headers commit macro reads/writes only when the post-operation `ringHead`/`ringTail` is greater than zero (`04_client_ring_buffer.h:39` and `19_server_ring_buffer.h_SHARED:49`). If a macro operation ends exactly at uint32 zero, it skips the commit.
- **HYPOTHESIS:** A streamed texture upload whose raw-data read lands on zero can leave those bytes logically unconsumed, causing the next six-byte request header to be decoded from texture data. This is a concrete deterministic-ish freeze mechanism compatible with “server stops receiving valid requests” and is fixed by P2-T02 (and the corresponding client macro hardening in P2-T01).

## 3. Production patch specifications

### P2-T01 — atomic client transport (`B02`, `B03`, `B06`, `B13`, `C04`)

- **VERIFIED:** Original `gl_send` at `02_client_gladio.h:111` performs two independent ring publications: six-byte header, then payload.
- **VERIFIED:** Original `GL_SEND_TEXIMAGE` at `02_client_gladio.h:219` publishes request metadata and later performs an unchecked raw-data write.
- **VERIFIED:** Original `glEnd` at `05_client_gl_calls.c_DEPLOYED_V5:1747` publishes metadata/client arrays, then performs a separate unchecked command-stream write.
- **VERIFIED:** Original `RingBuffer_create` at `03_client_ring_buffer.c:58` maps `sharedData` but never assigns it to `ring->sharedData`; consequently `RingBuffer_free` at line 138 cannot set `EXIT` or unmap that client mapping.
- **VERIFIED:** P2-T01 adds `RingBuffer_writeParts`, waits for the complete logical record, copies all spans, and advances tail once with release semantics. The on-wire bytes remain header → payload → trailing bytes.
- **VERIFIED:** P2-T01 makes texture uploads and immediate-mode `glEnd` all-or-nothing at publication time; an oversized record fails before exposing a header.
- **VERIFIED:** P2-T01 stores the mapping pointer, validates power-of-two capacity and reply lengths, fixes unaligned wire-header loads/stores, activates peer-socket death detection, and reports the first send/ring failure with request and occupancy data.
- **HYPOTHESIS:** This can remove a partial-publication/parser-desynchronization stall and prevents infinite ring waits after socket death. It does not prove that host `glFinish`, an abandoned mutex, or another live-peer sync wedge is absent.
- **Protocol:** none. **Build:** client bump. **Risk:** medium because the hot transport path changes.

### P2-T02 — server ring hardening (`B03`, `B06`)

- **VERIFIED:** The server ring implementation already contains peer-fd polling, but `createGLContext` at `09_server_gl_context.c_DEPLOYED:515` never supplies `clientFd`; P2-T02 activates that existing path for both rings.
- **VERIFIED:** P2-T02 replaces zero-valued head/tail sentinels with explicit booleans and marks the ring `EXIT` on wrap-buffer allocation failure.
- **HYPOTHESIS:** This is the highest gain/risk stall correction because it directly removes a wrap-specific loss of progress without changing request semantics.
- **Protocol:** none. **Build:** server bump. **Risk:** low.

### P2-R01 — generic attribute enable mirror (`A11`)

- **VERIFIED:** `glDisableVertexAttribArray` updates the client VAO mirror, while `glEnableVertexAttribArray` at `05_client_gl_calls.c_DEPLOYED_V5:1724` only forwards. P2-R01 restores symmetry with the same mirror helper used by disable.
- **HYPOTHESIS:** This can prevent missing generic attribute payloads in ARB/GLSL paths. WoW build 5875 must be traced to establish whether the affected path contributes to the screenshots.
- **Protocol:** none. **Build:** client bump. **Risk:** low.

### P2-R02 — legacy luminance-alpha conversion (`A01`, `A02`)

- **VERIFIED:** The attached converter maps a legacy two-channel internal format to `GL_RG8`, but its main format switch at `17_server_texture_utils.h:249` lacks a `GL_LUMINANCE_ALPHA` case. Since both sides report two channels, the later channel-count fallback does not run, leaving the external format unchanged.
- **VERIFIED:** P2-R02 converts that external format to `GL_RG` and applies `R,G,B <- RED; A <- GREEN`, which preserves desktop luminance-alpha sampling semantics while using GLES-core tokens.
- **VERIFIED:** This exact conversion was added by upstream Gladio commit `7044e42`.
- **HYPOTHESIS:** It can repair black or alpha-wrong legacy two-channel textures, but the screenshots are too broad to attribute all black materials to this one format.
- **Protocol:** none. **Build:** server bump. **Risk:** low.

## 4. Diagnostic A/B patches

### D1 — skip host `glFinish` (`C10`)

- **VERIFIED:** D1 leaves the synchronous Gladio reply in place and skips only the host GLES `glFinish` call.
- **HYPOTHESIS:** Stall removed → host finish or preceding queued GPU work is implicated. Stall unchanged with client waiting for code 221 → reply/ring/mutex path remains. This patch must not be the production default.

### D2 — suppress `GL_ARB_fragment_program` (`A10`)

- **VERIFIED:** D2 removes only that extension token from `getGLExtensions` at `09_server_gl_context.c_DEPLOYED:942`.
- **HYPOTHESIS:** Black materials becoming textured, even with degraded water/fog, would isolate the ARB fragment-program converter/state path. No visual change would move texture data, primary color, and texture-env state ahead of it.

## 5. Recommended application and validation order

1. **VERIFIED:** Build/deploy the combined production candidate as a matched client/server pair, retaining the pending last-sent/last-consumed tails.
2. **VERIFIED:** Record per run: time to stall, presented frames, request count, request-ring head/tail, first observed uint32 wrap, send failures, ring `EXIT`, and last sync request/reply sequence.
3. **HYPOTHESIS:** A successful run beyond 10 minutes and at least 18,000 presented frames would strongly disfavor the prior approximately one-minute/wrap-linked failure mode.
4. **VERIFIED:** Run D1 and D2 separately; never combine them in the first comparison, because a transport fix and a capability-path change would confound attribution.
5. **VERIFIED:** For R02, log `(internalformat, format, type)` before/after conversion and count `GL_LUMINANCE_ALPHA` uploads; compare login, realm selection, character selection, and Northshire screenshots.

## 6. Acceptance thresholds

- **VERIFIED:** Stall acceptance: no request starvation or frozen present for 10 minutes at 30 fps; at least 18,000 presented frames; zero `send failed`, `invalid ring payload`, `corrupt occupancy`, or unexpected `RING_STATUS_EXIT` messages.
- **VERIFIED:** Ordering acceptance: client last-sent sequence equals server last-consumed sequence modulo requests intentionally awaiting a reply; no raw-data read begins without the full logical publication already committed.
- **VERIFIED:** Rendering acceptance: zero newly black textures; no regression in UI/minimap/fire; luminance-alpha conversion produces no GLES error immediately after upload.
- **HYPOTHESIS:** The combined patch should improve stall resilience and some legacy textures, but it is not sufficient evidence that ARB programs, per-texture-unit binding state, lighting, or texture-env combine are correct.

## 7. Deferred exact-source dependencies

- **VERIFIED:** A08 per-active-unit texture binding cannot be patched safely from this bundle because the exact server definitions of `GLClientState`, `GLTexture`, `GLRenderer`, and `MAX_TEXTURES` are absent. `14_server_gl_texture.c` visibly indexes only by target, but changing the state layout affects multiple unseen files.
- **VERIFIED:** A10/A11 client ARB-program mirror and legacy-array routing require exact client `gl_client_state.h`, `gl_vao.h`, and `gl_vao.c`.
- **VERIFIED:** The pending run-4 tail instrumentation is explicitly not in the deployed files and should be reviewed as a diff before it is combined with these patches.

## 8. External references

- Khronos, OpenGL ES 3.2 `glTexImage2D` reference: accepted external formats are the GLES-core set including `GL_RG`, not legacy `GL_LUMINANCE_ALPHA`.
- Gladio commit `7044e42`, “Add fallback formats and utility for textures”: adds the same luminance-alpha swizzle and `GL_RG8/GL_RG` conversion used in P2-R02.
