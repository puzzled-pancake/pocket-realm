GL -&gt; GLES Translation for World of Warcraft Vanilla 1.12.1

Build 5875 on Android / Gladio-class client-server bridge

Research findings, exhaustive issue inventory, repair specifications, validation plan, and Phase 2 file request

Field

Value

Research date

15 August 2026

Pinned baseline

gladio-eaa2a8d / package box64-gladio-eaa2a8d

Target

Android GLES 3.2; qualified 800x600, production ceiling 1920x1080

Evidence basis

User-supplied verified dossier, attached gl_calls.c, primary specifications, upstream source/commit history, historical Wine/WoW evidence

Attached source SHA-256

412b179876f5399548ddb3d4f6470624bf61c31a5677cff87b09ee0587a48ec0

VERIFIED (scope). This paper is restricted to the legacy OpenGL translation lane. It does not recommend replacing that lane with DXVK/Turnip, and it treats the dossier as system ground truth.

HYPOTHESIS. No compilation, game execution, APK inspection or device test was possible. Every conclusion that depends on the exact build-5875 call stream is therefore paired with a reproducible experiment.

# Contents

VERIFIED. Executive summary

VERIFIED. Evidence model and research method

VERIFIED. 1. Build 5875 OpenGL usage profile

VERIFIED. 2. Upstream Gladio delta after eaa2a8d

VERIFIED. 3. Transferable GL4ES techniques

VERIFIED. 4. GLES 3.2 / mobile GPU realities

VERIFIED. 5. CPU-overhead and protocol engineering

VERIFIED. 6. Failure-mode taxonomy and long-tail APIs

VERIFIED. 7. Exhaustive issue inventory

VERIFIED. 8. Fix specifications: Phase A correctness

VERIFIED. 9. Fix specifications: Phase B hardening

VERIFIED. 10. Fix specifications: Phase C CPU overhead

VERIFIED. 11. Validation plan

VERIFIED. 12. Risk register

VERIFIED. 13. Files needed for Phase 2

VERIFIED. Bibliography and code-reference index

# Executive summary

VERIFIED (dossier). The dominant correctness risk is not a single shader bug. It is an interaction between legacy texture formats, per-texture-unit fixed-function state, lighting/material emulation, client-memory arrays, and a protocol that currently serializes some pointer-sized values as 32-bit integers.

VERIFIED (source audit). The attached client contains 161 explicit unimplemented debug stubs, forwards every DrawArrays/DrawElements/DrawRangeElements through writeUnboundVertexArrays, exposes one process-global GL call mutex, splits glEnd across two ring writes, and serializes at least two pointer/offset fields through ArrayBuffer_putInt. [C01-C05][C13]

VERIFIED [S01-S09]. The public Gladio history has a short, high-value post-snapshot chain. The direct child of eaa2a8d repairs texture format conversion; subsequent commits add indexed client-array handling, correct client texture-unit selection, legacy alpha/luminance swizzles, ARB-program/generic-attrib handling, per-unit texture state and bounds hardening. These are candidate backports, not proof of WoW validation.

HYPOTHESIS. The most likely visual blockers are A01/A02/A07/A09/A12-A19. The most likely CPU blocker is C01: repeated client-array range copying under Box64. B01-B03 are release blockers because corruption can masquerade as intermittent rendering defects.

Rank / action

Correctness gain 0-10

Risk 1-10

Gain/risk

Expected outcome

Delivery note

1. Backport indexed client-array and client-active texture fixes (A09)

9

2

4.5

Restores correct UV-unit descriptors; removes two known stubs.

Client-only code first; protocol unchanged.

2. Backport texture format + swizzle corrections (A01/A02)

10

3

3.3

Eliminates black/single-channel legacy textures and invalid RGB/depth combinations.

Server texture path; matched package rebuild.

3. Add client/server no-op state filtering (C06)

4

2

2.0

Cuts redundant request and GLES traffic while preserving semantics.

Start only with validated idempotent states.

4. Replace pointer/size wire fields with protocol-v2 u64 scalars (B01/B09/B10)

8

4

2.0

Prevents truncation/corruption on large offsets and removes ABI-dependent long.

Both ends; mandatory build-ID/hash bump.

5. Complete texture-env combine/crossbar/dot3 (A07/A08)

10

5

2.0

Corrects multi-layer terrain, detail maps and legacy material blending.

Trace exact token combinations before pruning.

6. Coherently backport ARB program/attrib mapping and bounds (A10/A11)

9

5

1.8

Restores program paths and prevents array-index corruption.

Backport whole shared-state cluster, not isolated hunks.

7. Complete 8-light fixed-function material pipeline (A12-A17)

10

6

1.7

Correct dynamic lamps, scaled normals, two-sided and separate specular.

Uniform-budget variants; high regression surface.

8. Publish each ring record atomically once (B02/B03/C04)

8

5

1.6

Eliminates partial logical records and halves producer publications.

Both ends; sequence/length validation.

9. Exploit LockArrays/cache-ID server VBO path (C01/C02)

6

5

1.2

Removes repeated array copies for locked/static geometry.

New requests; retain inline fallback.

10. Bridge-owned shared array slabs with generations (C11)

5

8

0.6

Largest long-run reduction in copied vertex bytes.

Later phase; explicit ownership/completion required.

HYPOTHESIS. After Phase A, the expected outcome is zero black/missing legacy textures, correct terrain combine, foliage alpha-test, fog and the complete logical lighting model in all scripted scenes. After Phase C, a static scene should reduce inline client-array bytes per frame by at least 80% after warmup; this threshold must be measured rather than assumed.

# Evidence model and research method

Label

Meaning

VERIFIED (dossier)

Supplied system facts accepted as ground truth.

VERIFIED (source audit)

Direct observation in attached gl_calls.c.

VERIFIED [Sxx]

External claim supported by source register.

HYPOTHESIS

Reasoned conclusion requiring trace, micro-test or device confirmation.

VERIFIED [S26]. apitrace can record OpenGL calls, replay them, inspect state, textures and framebuffers, and profile traces. It is therefore the primary method for converting unknown build-5875 behavior into verified usage data.

VERIFIED [S27]. Historical evidence directly names World of Warcraft build 5875 and a Wine stack ending in wine_glDrawRangeElementsEXT and Mesa _tnl_DrawRangeElements. The same thread documents OpenGL mode, dual-TMU concerns and a period workaround disabling GL_ARB_vertex_buffer_object.

HYPOTHESIS. Public web sources did not expose a complete native-OpenGL trace or per-call histogram for build 5875. Assertions about glLockArraysEXT, display-list frequency, exact texture-env tokens, ARB program roles and maximum enabled lights are deliberately not upgraded to VERIFIED.

# 1. Build 5875 OpenGL usage profile

VERIFIED [S27]. Build 5875 executed the OpenGL DrawRangeElementsEXT path under Wine in 2007. This establishes indexed-range drawing as a real path, not merely a bridge API surface.

VERIFIED (dossier). The target deployment’s main WoW path uses client-memory arrays rather than VBOs, and the bridge copies enabled array ranges into every draw request. The attached wrappers confirm the copy hook on DrawArrays, DrawElements and DrawRangeElements. [C04]

VERIFIED [S27]. Historical Wine guidance disabled GL_ARB_vertex_buffer_object for compatibility and discussed dual-TMU support. This is consistent with, but does not independently prove, the dossier’s client-array and multitexture profile.

VERIFIED [S28][S29]. WoW BLP assets can contain indexed 256-color palette data with independent alpha, DXT1/DXT3/DXT5 data and precomputed mip chains. A correct GL lane must preserve channel order, alpha interpretation and all supplied levels.

API/feature

Status

Evidence

Required experiment

glDrawRangeElementsEXT

VERIFIED

Direct build-5875 Wine stack. [S27]

Count calls, modes, index types and start/end validity.

Client-memory vertex arrays

VERIFIED (dossier/source)

Per-draw writeUnboundVertexArrays in attached client. [C04]

Record descriptors, copied bytes and pointer reuse.

Multitexture / two or more units

VERIFIED historical + dossier

Dual-TMU history; active/client-active state present. [S27][C01]

Log active units and enabled texcoord arrays.

glLockArraysEXT/glUnlockArraysEXT

HYPOTHESIS

Era-appropriate compiled-array extension, but no direct build-5875 trace found. [S17]

Add wrappers/counters; capture lock ranges and writes between lock/unlock.

Display lists / glCallLists

HYPOTHESIS

Bridge exposes lists; glCallLists is stubbed. No build-5875 trace found. [C02]

Count NewList/EndList/CallList/CallLists and list byte sizes.

Texture-env combine/crossbar/dot3

HYPOTHESIS - high priority

Visual goals and desktop-era terrain pipeline make it likely; exact tokens unknown. [S18-S20]

Dump all TexEnv pnames/values per unit in forest/terrain scenes.

ColorMaterial/material

HYPOTHESIS - high priority

Bridge exposes calls and goals require correctness; exact usage unknown.

Counter + state timeline around character and lamp draws.

Secondary color / separate specular

HYPOTHESIS - high priority

Bridge has current call but pointer stub; desktop lighting supports path. [S16][C10]

Counter current and pointer APIs; inspect light model color control.

NORMALIZE/RESCALE_NORMAL

HYPOTHESIS - high priority

Common with scaled models; exact toggles unknown.

Log enables and modelview determinant/scale.

EXP2 fog

HYPOTHESIS - high priority

Goal names EXP2; exact mode must be captured.

Log Fog mode/density/start/end/color and fog source.

Alpha test

HYPOTHESIS - high priority

Likely for foliage/UI cutouts; exact func/ref unknown.

Log enable/func/ref and correlate to foliage draws.

ARB vertex/fragment programs

HYPOTHESIS

Bridge has implementation and upstream fixes; water/skinning role not verified. [S31]

Dump ProgramStringARB source/hash, binds and enabled arrays.

Maximum simultaneous lights

HYPOTHESIS

Bridge cap 4 conflicts with desktop minimum 8; WoW demand unknown. [S16]

Record enabled-light bitmask per draw; report max and duration.

## Exact trace experiment for unresolved usage

VERIFIED [S26]. Capture on a desktop/Wine reference lane with the same unmodified WoW.exe build 5875 and data set. Use apitrace’s OpenGL interception, then export a machine-readable call stream and representative frame state.

For each scenario, capture 600 stable frames after warmup:1. login/character select2. forest with alpha-tested foliage3. city with dense UI and geometry4. night scene with lamps and spell lights5. four-layer terrain transition6. water and minimap/offscreen updatePost-process:- call count and argument histogram per GL entry point- enabled client arrays and pointer/stride/type reuse- texture unit/env state per draw- enabled light bitmask and material state per draw- sync calls and readbacks- display-list compile/replay sizes- ARB program source hashes and bindings

HYPOTHESIS. Treat an API as “unused for qualified scope” only after all six scenarios, two character models, day/night transitions and both resolutions show zero calls. Keep a diagnostic capability switch rather than deleting code.

# 2. Upstream Gladio delta after eaa2a8d

VERIFIED [S01]. The public Gladio repository describes a Winlator OpenGL-through-GLES client whose server performs OpenGL 1.x emulation, shader conversion and texture decompression. At research time the repository displayed 11 commits.

VERIFIED [S02]. Commit c8183aa is the direct child of eaa2a8d, so later commits form a concrete candidate backport chain rather than unrelated code archaeology.

Commit

Files/area

Behavioral delta

Issues

Backport judgement

c8183aa

texture_utils.h

RGB5-&gt;RGB565; unsized RGB-&gt;RGB8; depth32-&gt;depth24/u32

A02

Backport first; low structural risk. [S02]

806f439

gl_calls.c, gladio.h, texture_utils.h

Indexed client-state APIs; correct MultiTexCoordPointer client unit; half-float size; depth24 type; renderbuffer getter cluster

A02/A09/A30

Client A09 hunks can be isolated; getter requires server handler. [S03]

875969f

client state, VAO, main, gl_calls

ARB program tracking; legacy/generic array mapping; larger attribute space

A10/A11

Backport coherently with shared headers and server mapping. [S04]

efe6dd1

ring allocation

Avoid allocation leak when mmap setup fails

B06

Low-risk lifecycle cleanup after ring files reviewed. [S08]

7044e42

texture_utils.h

Alpha/luminance/luminance-alpha represented with R/RG plus swizzle

A01

High visual value; verify texture-object swizzle restoration. [S05]

a9daf98

limits/finish/texture rectangle

Later generic-attrib and target changes; optional finish skip

A11/A27/C10

Do not default-enable finish skipping; cherry-pick only proven parts. [S09]

9176231

shared headers

Per-unit/shared-state follow-up

A08/A10

Apply with its parent cluster; inspect exact server snapshot. [S06]

116c0d1

attrib bounds

Reject/guard out-of-range generic attributes

A11/B13

Backport after final attribute numbering is fixed. [S07]

HYPOTHESIS. Upstream changes are not automatically correct for the pinned client/server package because shared enums, array indices and request decoders may have evolved together. The safe unit of backport is a behavior cluster with both endpoints and shared headers, followed by trace replay.

# 3. Transferable GL4ES techniques

VERIFIED [S10]. GL4ES explicitly targets OpenGL 1.5/2.x compatibility and speed on GLES hardware. Its value here is architectural precedent, not drop-in code: Gladio’s client/server boundary changes where each technique belongs.

Technique

Evidence

Gladio mapping

Side/protocol

Issues

Compiled-array/VBO conversion

GL4ES converts locked arrays to buffers and reuses them on later draws. [S11][S17]

Client identifies lock range; both ends create cache ID; server owns VBO.

Both; new requests

C01/C02

Pointer/stride array identity

GL4ES render-list metadata tracks real base, size and stride. [S12]

Client cache descriptor, but require generation/hash; pointer alone unsafe.

Client + protocol

C01/C11

Display-list capture/replay

GL4ES render lists capture commands and converted array data. [S12]

Best first implementation is server-side decoded command capture.

Server; CallLists may need both

A20/C07

Whole FPE-state shader cache

GL4ES hashes the entire canonical state and compares by memcmp. [S13]

Server shader_material structural key; numeric values stay uniforms.

Server

C08

Per-state change suppression

GL4ES light/material code returns early when values do not change. [S14]

Client suppress transport; server suppress GLES for replay/unmirrored paths.

Both, no protocol

C06

Two-sided/separate specular

GL4ES keeps explicit state and distinct front/back/specular varyings. [S14][S15]

Server shader_material and renderer current/array secondary color.

Server/both

A16/A17

NORMALIZE/RESCALE

GL4ES emits shader normal adjustment. [S15]

Server eye-space normal path keyed by enable state.

Server

A14

Highp lighting/fog

GL4ES uses highp for light products, positions, shininess and fog-sensitive values. [S15]

Probe precision, use highp where supported; keep mediump texture colors.

Server

A19

Alpha-test discard

Programmable fragment shader can discard by alpha comparison. [S23][S15]

Server fragment-shader structural variant + uniform ref.

Server

A18

BGRA fast conversion

GL4ES exposes/emulates BGRA and has optimized conversion paths. [S10-S12]

Server conversion utility; optional NEON only after scalar golden tests.

Server

A03

Draw batching

GL4ES can merge subsequent draws until a state change. [S11]

Server context-local batch after correctness and ordering instrumentation.

Server

C07

HYPOTHESIS. The highest-value transfer is not “cache by pointer.” It is a two-level design: an exact LockArraysEXT contract for deterministic reuse, followed by optional generation/hash caching for unlocked arrays. The server must never assume that a raw Wine/Box64 pointer is stable, shareable or immutable.

# 4. GLES 3.2 / mobile GPU realities

VERIFIED [S22]. ETC2/EAC is guaranteed by GLES 3.0+, while S3TC is less widely available and must be detected through extension strings; some devices expose only DXT1. Therefore the bridge must choose a per-format path at runtime.

HYPOTHESIS. On target Adreno/Turnip-class devices, direct S3TC upload may be available, but the qualified implementation cannot depend on it without logging the exact extension set. Correctness fallback is DXT decode to RGBA8. Runtime DXT-to-ETC2 encoding is deferred because it adds CPU cost and another lossy transform.

VERIFIED [S23]. Fragment shaders can discard fragments and expose gl_FrontFacing, enabling exact alpha-test comparison and two-sided color selection. Shader precision is implementation-dependent and should be probed.

HYPOTHESIS. Use highp for eye-space positions, fog exponent arguments, normal matrices, attenuation and light accumulation. Keep sampler results/material colors at mediump only if screenshot and banding tests pass.

Reality

Required behavior

Validation

Failure if wrong

S3TC/DXT

Extension-gated direct upload; decode unsupported variants to RGBA8.

Log extension and bytes/path per texture.

Black textures; CPU stalls.

Legacy alpha/luminance

R8/RG8 plus object swizzle.

Golden 1x1/2x2 channel tests.

Wrong alpha/masks.

BGRA

Use extension when valid; otherwise packed-type-aware repack.

Color bars for all types and pixel-store values.

Red/blue swap.

Line width &gt;1

Native only within queried range; geometry expansion if observed.

UI line scene + range log.

Thin/missing UI.

Point size

Write gl_PointSize and verify range/clipping.

Particle/UI point scene.

Missing points.

Lights/uniform budget

Eight logical lights; select compact shader/multipass by queried limits.

Compile 0..8-light variants at startup test.

Missing lights or link failure.

Precision

highp for lighting/fog critical path.

Precision query + dusk/night screenshot delta.

Banding/flicker.

# 5. CPU-overhead and protocol engineering

VERIFIED (dossier/source audit). The client executes under Box64, takes a global recursive mutex, repacks most calls into a process-global ArrayBuffer and copies client arrays per draw. Box64’s dynamic translator is designed to execute x86-64 applications on ARM-class hosts, so every avoidable wrapper instruction remains relevant even though no universal overhead multiplier can be claimed. [S30][C01][C04]

VERIFIED [S24]. A lockless circular buffer requires a single producer and single consumer, careful handling of variable-size wrap, and release/acquire publication so payload bytes become visible before the producer head. Multiple producers must be serialized.

VERIFIED [S25]. AF_XDP provides a useful ownership analogy: SPSC descriptor rings refer to offsets in a registered shared memory region, and completion returns ownership. It is not a drop-in implementation, but it demonstrates the safety properties required for bridge-owned shared array slabs.

Technique

Measurement equation

Engineering judgement

Current inline arrays

bytes/frame = sum(enabled-array slice bytes for every draw)

Baseline correctness; highest client CPU/bandwidth cost.

LockArrays cache

upload once per lock generation + indices/references per draw

Deterministic first optimization; expected high hit rate if WoW uses extension.

Descriptor/hash cache

upload on descriptor or content-generation change

Works for unlocked static data but dirty detection costs CPU and pointer identity is insufficient.

Shared slab

copy changed bytes once into bridge-owned shm; reference by offset/generation

Best long-run path; highest protocol/lifecycle risk.

No-op state filter

requests_saved = attempted_idempotent_calls - changed_calls

Low-risk once validation/error rules are preserved.

Fixed request structs

packing_work_saved = hot fixed-size ArrayBuffer appends replaced

Measure client cycles/request; protocol v2 prerequisite for clean schema.

One publish per record

one reserve/commit rather than header write + payload write

Removes extra publication/check/wakeup and prevents partial logical visibility.

Per-context lock

contention_saved depends on concurrent GL callers

May be small for single render thread; still removes cross-context serialization.

HYPOTHESIS. Do not publish a fixed millisecond saving before instrumentation. Report absolute copied bytes/frame, requests/frame, client CPU nanoseconds/request, ring wait time and shader compiles. The defensible savings equation is baseline inline-array bytes minus cache uploads, indices and reference records.

# 6. Failure-mode taxonomy and long-tail APIs

Class

Failure

Required invariant

Record visibility

Header/payload/command stream become visible separately

Length + sequence validation, one release-store commit, padding record on wrap.

Capacity/overflow

Record larger than contiguous/free ring space

Checked reserve; bounded wait; oversize slow path; no partial commit.

Context ordering

Worker pool executes same-context records concurrently

Per-context executor or next-sequence gate.

Destruction

Rings/context freed while requests or replies in flight

Closing state, fence/ack, worker refcount and drain before unmap.

Migration

Thread-local current context changes owners

Ownership handshake; flush/drain; migrate scratch and cache pointer.

ABI width

sizeiptr/pointer/sync sent as int/long

Fixed-width protocol v2; static schema and decode bounds.

Endian/alignment

Native struct or unaligned loads differ

Little-endian helpers; memcpy decode; static assertions; version handshake.

False success

Stub prints and returns without GL error/output

Deterministic error or correct fallback; capability advertisement gate.

Precision

double narrowed globally

Targeted f64 variants only where reference delta exceeds threshold.

Static cache scope

Process-static strings/extensions leak contexts

Key by context/share group/name and invalidate on destroy.

HYPOTHESIS. Selection/feedback, shared palette, borders, logic operations, pixel transfer, 1D/3D textures, occlusion queries, multisample and WGL extensions should be treated as “instrument, then implement only observed semantics.” The current bridge should not advertise a feature whose state-affecting entry points silently succeed.

# 7. Exhaustive issue inventory

VERIFIED. Size is engineering effort (S/M/L/XL), not calendar time. Risk combines protocol breadth, regression surface and failure severity. “Evidence” distinguishes a direct code/spec mismatch from a use hypothesis.

ID

Area

WoW-visible symptom

Root cause / evidence

Evidence strength

Fix

Risk

A01

server/both

Black or single-channel UI/terrain masks

VERIFIED: legacy GL_ALPHA/GL_LUMINANCE/LUMINANCE_ALPHA need channel swizzle when represented with GLES R/RG textures; later Gladio adds it. [S05]

Strong

S

Low-Med

A02

server

RGB textures rejected or color/depth attachments incomplete

VERIFIED: later Gladio maps RGB5 to RGB565, unsized RGB to RGB8, and depth32 to depth24/UNSIGNED_INT. [S02][S03]

Strong

S

Low-Med

A03

both

Blue/red swapped icons, minimap or screenshots

HYPOTHESIS: BGRA uploads/arrays/readback need an explicit extension path or byte/packed-type conversion; audit all format/type pairs against GLES. [S16]

Medium

M

Med

A04

server

DXT terrain or character textures black/corrupt; load stalls

VERIFIED: GLES 3 guarantees ETC2, not S3TC; S3TC must be extension-gated. [S21][S22]

Strong

M

Med

A05

server

Paletted BLP textures show wrong colors/alpha

VERIFIED: WoW BLP supports indexed palette data with independent alpha, plus DXT variants and mip chains. [S28][S29]

Strong

M

Med

A06

server

Shimmering or black distant terrain/missing mip levels

VERIFIED: BLP may carry up to 16 mipmaps; bridge must preserve level dimensions and completeness. [S28]

Strong

M

Med

A07

server

Terrain layers replace rather than blend; wrong detail maps

HYPOTHESIS: incomplete ARB/EXT texture-env combine, crossbar, dot3 or operand/scales; exact build-5875 token stream needs trace. [S18][S19][S20]

Medium

L

Med-High

A08

both

Texture from another unit appears on terrain/UI

VERIFIED: later Gladio expands texture binding state per texture unit; pinned state is at risk of unit aliasing. [S04][S06]

Strong

M

Med

A09

client

Missing second UV set or wrong terrain layer

VERIFIED: pinned client stubs indexed client-state calls and uses server-active rather than client-active texture in MultiTexCoordPointer; upstream fixes both. [S03][C08]

Strong

S

Low

A10

both

Water/skinning/effects missing when ARB programs selected

VERIFIED: later Gladio tracks ARB programs and repairs legacy/generic attribute mapping. [S04][S31][S32]

Strong

M

Med

A11

both

Random attribute corruption or out-of-bounds state

VERIFIED: later Gladio increases generic attribute capacity and adds bounds checks. [S04][S07]

Strong

S-M

Low-Med

A12

server

Night lamps/dynamic lights have wrong ambient or direction

VERIFIED: client has LightModel vector stubs; correct fixed-function lighting requires global ambient/local-viewer/two-side/separate-specular state. [S16][C07]

Strong

M

Med

A13

both

Fifth through eighth nearby lights disappear or flicker

VERIFIED: dossier cap is 4 while desktop GL 2.1 minimum is 8; actual WoW simultaneous-light demand remains HYPOTHESIS until trace. [S16]

Strong for mismatch

M-L

Med-High

A14

both

Lighting changes under scale; crushed or inverted normals

VERIFIED: integer normal conversion and NORMALIZE/RESCALE_NORMAL semantics are defined by OpenGL; pinned integer current-normal casts are wrong. [S16][C09]

Strong

M

Med

A15

server

Armor/characters ignore vertex colors or material updates

HYPOTHESIS: ColorMaterial/material face/mode state or shader-key invalidation is incomplete; gl4es treats it as explicit FPE state. [S14][S15]

Medium

M

Med

A16

server

Back faces black; specular attached to texture or absent

VERIFIED: two-sided lighting and separate specular require distinct front/back and secondary-color handling. [S16][S15]

Strong

M-L

Med-High

A17

both

Specular tint wrong; secondary color arrays absent

VERIFIED: pinned SecondaryColorPointer is stubbed and integer current-secondary-color conversion is unnormalized. [C10][S16]

Strong

M

Med

A18

server

Foliage has opaque rectangles or vanishes

VERIFIED: fixed alpha test is absent in programmable GLES and should be shader discard keyed by func/ref. [S16][S23][S15]

Strong

M

Med

A19

server

Fog bands, wrong horizon, night color mismatch

VERIFIED: OpenGL defines LINEAR/EXP/EXP2 equations and fog source; highp avoids visible distance-lighting artifacts where supported. [S16][S15][S23]

Strong

M

Med

A20

both

Static geometry/UI glyphs missing; repeated list calls cost CPU

VERIFIED: glCallList is forwarded but glCallLists is a client stub; exact WoW list use is HYPOTHESIS. [C02][S16]

Strong for stub

M-L

Med

A21

server

Minimap/offscreen effects or copied UI regions stale/black

HYPOTHESIS: copy-texture, read/draw-buffer GL_NONE, FBO and format conversion paths need trace-driven tests. [S16]

Low-Med

M

Med

A22

server

UI clipping leaks; terrain decals z-fight

VERIFIED: scissor and polygon-offset are client-forwarded; server coordinate/target semantics need reference comparison. [C12][S16]

Medium

S-M

Low-Med

A23

server

Thick UI lines/points render thin or disappear

VERIFIED: desktop requests may exceed implementation line-width support; GLES only guarantees a limited range. [S16]

Medium

M

Med

A24

server

Animated texture coordinates drift or wrong unit matrix used

HYPOTHESIS: all texture matrix stacks, active unit selection and shader-key uniforms need focused tests. [S16][S10]

Medium

M

Med

A25

both

State leaks after UI batches or display-list replay

HYPOTHESIS: Push/PopClientAttrib must restore client-side array descriptors, active client unit and bindings, not only server state. [S16][C11]

Medium

M-L

Med-High

A26

both

Mouse picking/selection, feedback or legacy pixel effects fail

VERIFIED: many selection/feedback/pixel functions exist but several are stubs or pointer-copy designs; WoW usage is unknown. [C01][S16]

Strong for bridge

L

High unless traced

A27

server

Rare texture target fails: 1D/3D/rectangle/border

VERIFIED: several 1D/3D calls are coerced to 2D or ignore depth/zoffset in pinned client; WoW use unknown. [C01]

Strong

M-L

Med-High

A28

server

Visibility queries return wrong result or stall

HYPOTHESIS: query ordering and result-width conversion require trace; client truncates 64-bit query results to 32-bit. [C01][S16]

Medium

M

Med

A29

GLX/both

Black window, bad offscreen surface, pacing or swap interval

HYPOTHESIS: Wine WGL pixel formats/pbuffers/swap control translated through GLX/EGL require exact glx_calls.c audit.

Low-Med

M-L

High

A30

both

GL error storms, bad capability probing, memory overwrite

VERIFIED: 161 explicit unimplemented stubs were counted in attached client; several getters return nothing/zero and info-log copies trust server lengths. [C01]

Strong

M-L

Med

A31

server

Shared-palette textures wrong

HYPOTHESIS: EXT_shared_texture_palette is unlikely but must be measured; cheapest fallback is CPU palette expansion to RGBA8.

Low

M

Low if gated

A32

server

One-pixel seams or incomplete textures using borders

HYPOTHESIS: desktop texture borders are not native GLES; emulate by border expansion or reject only when trace proves unused. [S16]

Low

L

High

A33

server/GLX

MSAA mismatch, incomplete FBO or resolve artifacts

HYPOTHESIS: clamp samples to host limits and test resolve/blit; vanilla use needs trace. [S16]

Low-Med

M

Med

A34

server

Software-generated mipmaps differ or stall

HYPOTHESIS: GLU runs client-side and ultimately emits TexImage levels; validate pixel-store and level framing rather than special-case GLU.

Medium

S-M

Low-Med

A35

client

Current colors/normals wrong for signed integer entry points

VERIFIED: pinned code divides signed colors by signed max and raw-casts integer normals/secondary colors, contrary to OpenGL conversion rules. [S16][C03][C09][C10]

Strong

S

Low

B01

protocol/both

Large buffers/PBO offsets corrupt memory or draw wrong region

VERIFIED: GLintptr/GLsizeiptr/pointers are serialized with 32-bit putInt in multiple attached functions. [C13]

Strong

L

High

B02

client/protocol/server

Immediate-mode draw occasionally decodes garbage or hangs

VERIFIED: glEnd sends metadata through gl_send then command bytes with a separate raw ring write. [C05]

Strong

M

High

B03

protocol

Ring overflow, partial record or wrap causes dropped/misframed calls

VERIFIED design risk: variable records require reservation and publish ordering; current exact ring code unseen. [S24]

Medium

M-L

High

B04

client

Unrelated contexts serialize; scratch buffer races if lock removed

VERIFIED: one process-global mutex and output buffer are used by all calls. [C01]

Strong

M

Med

B05

server

One context executes requests out of order through 4-worker pool

HYPOTHESIS: pool scheduling could violate stream order unless each context is pinned or sequenced; gl_context.c required.

Medium

M

High

B06

both

Use-after-free during context/ring destruction

HYPOTHESIS: async requests and shared objects require drain/fence/refcount before unmap/free; lifecycle code unseen.

Medium

M

High

B07

both

Cross-thread MakeCurrent loses state or writes wrong ring

HYPOTHESIS: thread-local current context plus migration requires ownership transfer and in-flight drain.

Medium

M

High

B08

client

Checked macro silently returns/drops a draw after transport failure

HYPOTHESIS: macro bodies and error paths must be audited for deterministic local GL error and context-lost state.

Medium

M

High

B09

protocol

Client/server disagree on sizes, alignment or endian

VERIFIED design risk: ABI-native writes are unsafe across builds; protocol lacks an explicit versioned scalar schema in dossier.

Strong

L

High

B10

protocol

GLsync handles truncate or differ by ABI

VERIFIED: pinned client serializes GLsync using long/sizeof(long). [C01]

Strong

M

High

B11

client/server

Large matrices/clip planes subtly drift

VERIFIED: many double entry points narrow to float; most are acceptable for game transforms, but clip planes and projection must be compared. [C01][S16]

Strong

S-M

Low-Med

B12

client

Cached extension/string state leaks across contexts

VERIFIED: glGetStringi uses a process-static list not keyed by context/name. [C01]

Strong

S-M

Med

B13

both

Malformed length/count causes overflow or desync

VERIFIED: many n*element-size copies and reply copies lack visible overflow/remaining-byte validation. [C01]

Strong

M-L

High

C01

client/protocol/server

High CPU and bandwidth every draw

VERIFIED: DrawArrays/Elements/RangeElements append unbound arrays every draw. [C04]

Strong

L

Med-High

C02

client

Index range scanning repeats per draw

VERIFIED dossier behavior; cache range or exploit supplied start/end in DrawRangeElements.

Strong

M

Med

C03

client

Box64 thread time lost in mutex operations

VERIFIED: global mutex exists; Box64 dynamically translates x86-64 on ARM. [C01][S30]

Strong

M

Med

C04

protocol

Two ring head publications/checks per GL call

VERIFIED dossier for gl_send; contiguous records can publish once. [S24]

Strong

M

Med

C05

client

Per-call ArrayBuffer rewind/append overhead under emulation

VERIFIED source pattern across thousands of wrappers. [C01]

Strong

M

Low-Med

C06

client/server

Redundant enable/bind/material calls consume transport/GLES CPU

VERIFIED technique: GL4ES performs state comparisons and whole-FPE-state caching. [S13][S14]

Strong

M

Low-Med

C07

server

Repeated small draws/list replay dominate

VERIFIED technique: GL4ES render lists batch draws and cache converted arrays/VBOs. [S11][S12]

Strong

L

Med-High

C08

server

Shader compile hitch when state toggles

VERIFIED technique: GL4ES hashes full FPE state and caches generated programs. [S13][S15]

Strong

M

Med

C09

server

DXT decode/re-encode spikes and doubles quality loss

VERIFIED: S3TC is optional while ETC2 is guaranteed; runtime transcode policy must branch on extensions. [S21][S22]

Strong

M

Med

C10

both

Frame stalls from Finish/Get/Error/ReadPixels

VERIFIED: synchronous wrappers exist; exact frequency needs counters. [C06][C01]

Strong

M

Med

C11

protocol/both

Cannot reuse client arrays without copying

HYPOTHESIS: bridge-owned shared memory with offsets/generations can transfer ownership like descriptor-to-UMEM designs; arbitrary Wine pointers cannot be shared safely. [S25]

Medium

XL

High

C12

both

Optimization proceeds without proof or regression localization

VERIFIED engineering gap: add per-code counters, bytes, occupancy, sequences, cache hits and shader compile metrics before major changes.

Strong

M

Low

# 8-10. Fix specifications

VERIFIED (deployment constraint). Any request code, framing, shared enum/index or wire-width change lands on both client and server, produces new client/server build IDs, and updates the package’s SHA-256 pair. Protocol-neutral client or server changes still require an unambiguous package build ID and new component hashes.

# Phase A - correctness blockers

## A-FS01 - issues A01, A02, A03

VERIFIED target. server: gl_formats.c, gl_texture.c, request_handler.c texture-image handlers; client: texture payload helpers

REQUIRED CHANGE. Create one table-driven desktop-format -&gt; GLES internalformat/format/type conversion. Apply target-specific swizzles after binding. Add explicit BGRA packed-type conversions when EXT_texture_format_BGRA8888 is unavailable.

converted = convert_tex_format(target, internal, format, type)if converted.swizzle_changed: apply_swizzle(bound_texture, converted.swizzle)if converted.cpu_repack: repack_to_RGBA8_or_RGB565(payload)glTexImage*(target, level, converted.internal, ..., converted.format, converted.type, data)

PROTOCOL. No new request code if server converts existing payload. Build-pair ID/hash bump still required.

INTERACTIONS. Texture object state must remember swizzle and restore it after redefinition; avoid mutating global active unit.

ROLLBACK. Feature flag GLADIO_LEGACY_FORMAT_V2; rollback to old converter per texture if validation mismatch.

## A-FS02 - issues A04, A05, A06

VERIFIED target. server: compressed_texture.c, gl_texture.c, gl_formats.c

REQUIRED CHANGE. Probe S3TC variants once per EGL context. Upload DXT1/3/5 directly only when corresponding extension support exists. Otherwise decode to RGBA8; preserve every supplied mip level. Implement paletted BLP only at the GL format boundary if raw indexed data reaches GL; otherwise validate that WoW has already expanded it.

if s3tc_supports(format): glCompressedTexImage2D(..., original_blocks)else: rgba = decode_dxt(format, blocks); glTexImage2D(..., GL_RGBA8, GL_RGBA, GL_UNSIGNED_BYTE, rgba)assert(level_dims == max(1, base &gt;&gt; level))

PROTOCOL. None for existing texture requests. Optional metrics fields only.

INTERACTIONS. Do not runtime-encode ETC2 in Phase A; it adds CPU and a second lossy stage. Respect pixel-store and null-data allocation.

ROLLBACK. Per-format fallback switch: direct / RGBA8 decode / reject-with-error.

## A-FS03 - issues A07, A08, A09

VERIFIED target. client: gl_calls.c, gl_client_state.h; server: gl_renderer.c, shader_material.c, gl_texture.c

REQUIRED CHANGE. Backport indexed client-array functions and client-active texture pointer repair. Store bindings, enable state, env mode, combine RGB/alpha, sources, operands, scales and env color per texture unit. Generate shader code for REPLACE/MODULATE/ADD/ADD_SIGNED/INTERPOLATE/SUBTRACT/DOT3 and crossbar references.

unit = active_texturestate.tex[unit].combine[pname] = valueshader_key.texenv[unit] = canonicalize(state.tex[unit])color = apply_texenv(unit, previous, texture[unit], primary, constant)

PROTOCOL. Client-only A09 backport is protocol-neutral. Server state/shader changes are protocol-neutral if existing TexEnv requests already carry all pnames.

INTERACTIONS. Shader key must include all operands/scales; getters return mirrored state; active texture and client-active texture are distinct.

ROLLBACK. Disable combine extension advertisement or force conservative MODULATE if an unsupported token is observed.

## A-FS04 - issues A10, A11

VERIFIED target. both: gl_calls.c, main.c, gl_vao.c/h, gl_client_state.h, request_handler.c, arb_program.c

REQUIRED CHANGE. Coherently backport ARB program tracking, legacy-to-generic attribute remapping, expanded generic attribute capacity and index validation. Never mix only one side of the shared header changes.

mapped = program_active ? legacy_to_generic(array_index) : array_indexif mapped &gt;= VERTEX_ATTRIB_COUNT: set_error(GL_INVALID_VALUE); returnvao.attrib[mapped] = descriptor

PROTOCOL. Likely no request-code change, but shared structure/attribute numbering changes require matched client/server build.

INTERACTIONS. Immediate current attributes, enabled array mask and POCKET_DRAW_ATTR indices must use the same mapping.

ROLLBACK. Runtime switch GLADIO_ARB_ATTRIB_V2; retain old mapping for bisect.

## A-FS05 - issues A12, A13, A15

VERIFIED target. server: gl_renderer.c, shader_material.c; shared limits in gladio.h; request_handler.c Light/Material handlers

REQUIRED CHANGE. Raise logical MAX_LIGHTS to 8, then query host vertex-uniform limits and select one of: full 8-light shader, compact enabled-light loop, or multipass fallback. Implement LightModelfv/iv including global ambient, local viewer and two-side. Canonicalize ColorMaterial face/mode into shader state.

logical_lights = 8budget = query_uniform_budget()variant.max_enabled = min(8, budgeted_lights)for each enabled logical light: upload transformed parametersif more than variant capacity: multipass additive lighting

PROTOCOL. No protocol change if existing request codes carry parameters; shared MAX_LIGHTS layout change requires matched build and build-ID bump.

INTERACTIONS. Positions/directions are transformed at specification time; material alpha and color-material replacement rules must match desktop GL.

ROLLBACK. Cap to 4 behind env switch while collecting traces; fallback to nearest/first enabled 4 only as diagnostic, not final correctness.

## A-FS06 - issues A14, A16, A17, A35

VERIFIED target. client gl_calls.c; server gl_renderer.c/shader_material.c; main.c array framing

REQUIRED CHANGE. Use OpenGL integer normalization for current colors/normals/secondary colors. Add secondary-color array descriptor/framing. In FPE shader, compute front/back colors, flip normal or select gl_FrontFacing result correctly, and add specular after texturing when separate-specular mode is enabled. Apply NORMALIZE or RESCALE_NORMAL in eye space.

N = normal_matrix * object_normalif NORMALIZE: N = normalize(N)elif RESCALE_NORMAL: N *= inverse_uniform_scalefront, back = light_two_sided(N)primary = facing ? front.primary : back.primarysecondary = facing ? front.specular : back.specularfinal = texture_env(primary) + secondary

PROTOCOL. Secondary array support may reuse POCKET_DRAW_ATTR framing if a stable index exists; otherwise both-end framing/version bump. Client conversion patch is protocol-neutral.

INTERACTIONS. ColorMaterial affects which material channels come from primary color. Flat shading/provoking vertex can change interpolation.

ROLLBACK. Feature flags for secondary array and two-sided shader; retain current scalar secondary path.

## A-FS07 - issues A18, A19

VERIFIED target. server: shader_material.c/gl_renderer.c/shader_converter.c

REQUIRED CHANGE. Represent alpha func/ref and fog state in canonical shader key/uniforms. Emit fragment discard for alpha test. Implement LINEAR/EXP/EXP2 exactly, selecting eye-coordinate depth or explicit fog coordinate. Use highp for eye distance, exponent and light accumulation when fragment highp exists.

if alpha_test &amp;&amp; !compare(alpha, ref, func): discardif fog_mode==EXP2: f=exp2(-1.442695 * density*density*z*z)final.rgb=mix(fog.rgb, final.rgb, clamp(f,0,1))

PROTOCOL. No protocol change if existing AlphaFunc/Fog requests are complete. FogCoordPointer requires new array support or a defined fallback.

INTERACTIONS. Alpha test order is before later framebuffer tests; account for alpha quantization of fixed-point targets.

ROLLBACK. Disable fogcoord extension advertisement until array path exists; force highp off only for driver workaround.

## A-FS08 - issues A20

VERIFIED target. server: request_handler.c, gl_renderer.c, new display_list.c; client gl_calls.c for glCallLists packing

REQUIRED CHANGE. Capture decoded GL commands and data into immutable server-side display-list objects during NewList/EndList. CallList replays in-order on the owning context/share group. Implement CallLists by expanding typed indices plus ListBase client-side into one array of GLuint IDs or a compact typed protocol record.

on NewList(id, mode): recorder.begin(id, mode)on request: if recording capture(command,payload); if COMPILE_AND_EXECUTE also executeon CallList(id): replay(recorder[id])

PROTOCOL. Implementing glCallLists needs either repeated existing CALL_LIST sends (client-only, slow but compatible) or a new batch request on both ends. Start with repeated sends for correctness.

INTERACTIONS. Pointers/pixel data/arrays must be copied at compile time per GL rules; recursive lists need cycle/depth guard.

ROLLBACK. Disable display-list advertisement or fall back to immediate replay; maintain old CallList server path behind switch.

## A-FS09 - issues A21, A22

VERIFIED target. server: gl_framebuffer.c, gl_texture.c, request_handler.c

REQUIRED CHANGE. Add table-driven tests and conversions for CopyTex(Sub)Image, Read/DrawBuffer including GL_NONE, scissor coordinate orientation and polygon offset targets. Preserve bound read/draw framebuffer separately.

state.read_buffer = map_read_buffer(src)state.draw_buffers = map_draw_buffers(bufs)copy_path = direct_blit_if_compatible_else_readback_convert_uploadscissor = drawable_to_surface_coords(x,y,w,h)

PROTOCOL. No protocol change unless framebuffer binding distinctions are missing.

INTERACTIONS. X drawable Y orientation and compositor blit can invert scissor/copy rectangles.

ROLLBACK. Per-operation slow fallback (readback+upload) with counter, then optimize.

## A-FS10 - issues A23

VERIFIED target. server: gl_renderer.c/shader_material.c

REQUIRED CHANGE. Query aliased line-width range. Use native width only when supported. For wider lines/points required by trace, expand to screen-space quads in a compatibility geometry path or draw instanced quads; point size comes from vertex shader gl_PointSize.

if width within native_range: glLineWidth(width)else: expand_line_segments_to_quads(width, viewport)

PROTOCOL. Protocol-neutral; server receives width already.

INTERACTIONS. Line joins/caps and clipping affect pixel exactness; only enable expansion for observed UI modes.

ROLLBACK. Clamp-to-1 switch for emergency compatibility.

## A-FS11 - issues A24, A25

VERIFIED target. both: gl_renderer.c, attrib_stack.c, gl_client_state.h, gl_calls.c

REQUIRED CHANGE. Maintain one texture matrix stack per unit and include top matrices in shader uniforms. Implement PushClientAttrib/PopClientAttrib on the client mirror for array enables/descriptors, active client unit, VBO/VAO and pixel-store groups; server push/pop alone is insufficient.

push(mask): deep_copy(selected_client_groups)pop(): restore mirror; emit minimal server deltas or restore server snapshottexcoord_out[i] = texture_matrix[i] * texcoord_in[i]

PROTOCOL. Could remain protocol-neutral but client/server snapshots must stay paired; a restore-batch request would be a later optimization.

INTERACTIONS. Pointer descriptors are process addresses and must not be copied across contexts. Stack overflow/underflow sets GL errors.

ROLLBACK. Bounded stack with old pass-through mode switch.

## A-FS12 - issues A26-A34

VERIFIED target. target files by subsystem: gl_query.c, gl_texture.c, gl_framebuffer.c, glx_calls.c, pixel handlers

REQUIRED CHANGE. Instrument first. For every observed call, implement the cheapest correct fallback: CPU selection/feedback, palette expansion, border expansion, sample clamp+resolve, 1D as 2D height=1 only when target semantics preserved, and explicit 3D upload rather than dropping depth/zoffset. Unobserved paths should return spec error, not silently succeed.

if counter[legacy_call] == 0 during full scenario suite: keep gatedelse: add golden micro-test and implement fallbacknever print-and-return-success for state-affecting calls

PROTOCOL. Case-specific. New pointer-bearing result paths require explicit reply sizes and 64-bit-safe framing.

INTERACTIONS. Avoid spending risk on unobserved APIs; wrong silent success is worse than deterministic GL_INVALID_OPERATION.

ROLLBACK. Capability advertisement gate per feature.

## A-FS13 - issues A30

VERIFIED target. client gl_calls.c; server request_handler.c; shared capability strings

REQUIRED CHANGE. Replace relevant stubs with mirror-backed getters or real round trips. Fill output arrays on all success returns. Bound every reply copy by caller capacity and remaining bytes. Build a generated audit list comparing exported symbols, stub bodies, request codes and server handlers.

manifest = parse_exports_and_handlers()for entry: assert(entry.alias || entry.local_impl || entry.request_handler || entry.intentional_error)reply_read(dst, requested, remaining)

PROTOCOL. Some getters need new request/reply codes on both ends. Build-ID bump mandatory for any protocol addition.

INTERACTIONS. glGetError cache/kill-switch must not mask bridge validation builds.

ROLLBACK. Keep GLADIO_NO_ERROR only as explicit production diagnostic; CI runs with real errors.

# Phase B - protocol and lifecycle hardening

## B-FS01 - issues B01, B09, B10

VERIFIED target. shared request_codes.h/gladio.h; client wrappers; server decoders

REQUIRED CHANGE. Introduce protocol v2 with fixed-width little-endian u16/u32/u64/i64/f32/f64 encoding, a version handshake and payload length. Replace all pointer-sized/sizeiptr/GLsync wire fields with u64 tokens/offsets. Reject mixed versions before context creation.

record_header = {magic,u16 version,u16 code,u32 length,u64 sequence}put_u64((uint64_t)(uintptr_t)offset_or_token)server: value = get_u64_checked()

PROTOCOL. Both ends, new package build IDs and hashes, no backward compatibility inside one ring pair.

INTERACTIONS. A Wine pointer is meaningful only as an offset for a bound buffer or as client identity; never dereference on server.

ROLLBACK. Keep v1 package selectable as whole pair; no mixed fallback.

## B-FS02 - issues B02, B03, C04

VERIFIED target. client/server ring_buffer.c/h, gl_send, glEnd, server parser

REQUIRED CHANGE. Reserve one contiguous logical record, write header+payload+command stream while unpublished, then release-store head once. If physical wrap would split it, emit padding record or support two physical spans hidden behind one publish. Validate length and sequence before dispatch.

res = ring_reserve(total)write_header(res.ptr, code, total, seq)write_payload(res.ptr+H, payload, command_stream)ring_commit_release(res, total)

PROTOCOL. Both ends; record header/framing change and build-ID bump.

INTERACTIONS. Backpressure must not hold context-destruction locks. Oversize records need slow path or chunk protocol with atomic logical framing.

ROLLBACK. Retain v1 ring implementation as package-level rollback.

## B-FS03 - issues B04-B07, B12

VERIFIED target. client gl_context.*, main.c; server gl_context.c/thread pool

REQUIRED CHANGE. Give each context its own request scratch buffer and producer lock; keep a small global lifecycle/share-group lock. Pin each context stream to one ordered executor or attach monotonically increasing sequence and reorder barrier. MakeCurrent migration drains/handshakes ownership; destroy waits for last sequence and worker refs. Key string caches by share/context and name.

context.owner_thread = tid on MakeCurrentcontext.submit_seq++worker executes only next_seqdestroy: mark closing; enqueue fence; wait ack; unmap rings

PROTOCOL. May use existing synchronous MakeCurrent channel; adding sequence/close records affects both ends.

INTERACTIONS. Recursive entry and callbacks require careful lock hierarchy. Shared objects need share-group lifetime separate from context lifetime.

ROLLBACK. Env switch retains global mutex/one worker per context.

## B-FS04 - issues B08, B13

VERIFIED target. client macros/gl_send/ArrayBuffer; server decoder

REQUIRED CHANGE. Make every checked macro return a typed transport status. On failure, set one local sticky GL error/context-lost state, stop submitting ordinary commands, and allow only error/string/destroy paths. Add checked arithmetic and remaining-byte guards to all variable-count requests/replies.

if mul_overflow(n, elem_size, &amp;bytes) || bytes &gt; MAX_PAYLOAD: set_error(GL_OUT_OF_MEMORY); returnif !send_full(record): mark_transport_lost(EPIPE)

PROTOCOL. Protocol-neutral for local checks; an explicit CONTEXT_LOST reply/status would require both ends.

INTERACTIONS. Do not fabricate successful return values after transport loss. Logging must be rate-limited.

ROLLBACK. Compile-time strict mode and production-compatible mode during rollout.

## B-FS05 - issues B11

VERIFIED target. client narrowing wrappers; server matrix/clip code

REQUIRED CHANGE. Keep float narrowing for ordinary transforms but instrument and reference-test projection, clip plane and depth range. Preserve doubles in protocol/server only for functions where screenshot or numerical tests exceed tolerance.

error = max_abs(reference_double - translated_float)if error &gt; threshold for clip/projection: add f64 request variant

PROTOCOL. Only exceptional f64 variants require new request codes.

INTERACTIONS. Avoid global f64 expansion; bandwidth and shader inputs remain float.

ROLLBACK. Per-call f64 feature bit.

# Phase C - client CPU and transport overhead

## C-FS01 - issues C12

VERIFIED target. both: request dispatch, ring, draw/texture/shader paths

REQUIRED CHANGE. Add counters before optimization: per request count/bytes/CPU time, ring high-water/waits, sequence errors, sync round trips, client-array copied bytes, upload bytes, cache hits/misses, texture fallback bytes/time, shader-key hits/compiles, GL errors. Snapshot per frame and expose via log/JNI.

frame_stats[code].count++frame_stats[code].bytes += record_lenstats.array_copied += bytesstats.ring_hwm = max(hwm, used)

PROTOCOL. No wire change if counters local. Optional stats query request may be added later.

INTERACTIONS. Counters must be cheap and disabled/aggregated in production.

ROLLBACK. Single env toggle GLADIO_METRICS=1.

## C-FS02 - issues C06

VERIFIED target. client mirror and server GLES state cache

REQUIRED CHANGE. For idempotent calls, compare canonical value before send; only update mirror/send on change. Server independently suppresses redundant GLES state in case lists or unmirrored paths replay calls.

if state.depth_func == func: returnstate.depth_func = func; send(DEPTH_FUNC, func)

PROTOCOL. None.

INTERACTIONS. Do not suppress calls with error semantics until validation proves parameters valid first. List compilation records commands even when current state matches if GL semantics require it.

ROLLBACK. Per-state-class disable mask and counters of skipped calls.

## C-FS03 - issues C05

VERIFIED target. client gl_calls.c code generation / ArrayBuffer

REQUIRED CHANGE. Generate packed fixed-size request structs for hot calls with static_assert on offsets and explicit encoding; write directly into reserved ring space after state filtering. Keep ArrayBuffer for variable payloads.

struct ReqBlendFunc { le32 sfactor, dfactor; };static_assert(sizeof(...)==8);ring_send_fixed(code, &amp;req, sizeof req)

PROTOCOL. Best after protocol v2. Both ends share schema, not native structs.

INTERACTIONS. Never rely on compiler packing or host endian; encode fields explicitly.

ROLLBACK. Keep ArrayBuffer implementation selected at build/runtime.

## C-FS04 - issues C01, C02

VERIFIED target. client main.c/gl_calls.c/arrays; server gl_context.c/gl_renderer.c; request codes

REQUIRED CHANGE. Implement lock-array cache first. On LockArraysEXT(first,count), copy each enabled client array once and create/upload a server cache object keyed by explicit cache ID and descriptor. Draws send cache ID plus indices/range. Unlock invalidates. For unlocked arrays, optional hash/generation cache can follow, but pointer identity alone is unsafe.

LOCK_ARRAYS {cache_id, first,count, descriptors,data}DRAW_CACHED {cache_id, mode,count,index_type,indices}UNLOCK_ARRAYS {cache_id}server cache: VBOs + descriptor metadata

PROTOCOL. New request codes/framing on both ends; build-ID bump.

INTERACTIONS. Array pointer/stride/type changes invalidate; writes during lock are application error/undefined per extension contract. Shared contexts need cache ownership/refcounts.

ROLLBACK. Fallback per draw to current inline arrays on miss/unsupported layout.

## C-FS05 - issues C11

VERIFIED target. both protocol/ring, new shared_array_pool module

REQUIRED CHANGE. Allocate bridge-owned shared-memory slabs, pass FDs during context setup, and address data by 64-bit slab offset + immutable generation. Client copies changed array ranges once into a slab; draw records carry descriptors/references. Server returns completion generations before reuse.

publish = {slab_id, offset, length, generation, hash}draw = {array_ref[], index_ref_or_inline}completion = {generation_done}client reuses only after completion

PROTOCOL. Major both-end protocol change and FD lifecycle; new package ID/hashes.

INTERACTIONS. Do not expose arbitrary Wine memory. Define ownership, fences, wrap, out-of-memory, context loss and process death.

ROLLBACK. Feature-negotiated pool; fall back to inline arrays.

## C-FS06 - issues C07

VERIFIED target. server display_list/render batching

REQUIRED CHANGE. After correctness, merge compatible consecutive draw records within a context until a state barrier. Convert captured static arrays/list data to VBOs/IBOs; retain order and primitive semantics.

batch_key = {program, textures, blend, depth, arrays}if next.key==batch_key &amp;&amp; merge_safe: append_indiceselse: flush_batch()

PROTOCOL. No client protocol change for server-side batching.

INTERACTIONS. Do not merge across query, readback, list boundaries, matrix/state changes or primitive modes with strip continuity issues.

ROLLBACK. Maximum batch size/time and disable env switch.

## C-FS07 - issues C08

VERIFIED target. server shader_material.c/shader_converter.c

REQUIRED CHANGE. Canonicalize state to a packed key; hash+memcmp as GL4ES does. Cache successful programs and negative compile results with diagnostic source. Separate structural key from uniform values so material/light numeric changes do not recompile.

key = canonical_structural_fpe_state(state)program = cache.lookup(key) or compile(key)update_uniforms_if_generation_changed()

PROTOCOL. None.

INTERACTIONS. Driver program cache lifetime and share-group deletion; cap memory with LRU and never evict bound program.

ROLLBACK. Cache-off switch; dump key/source on mismatch.

## C-FS08 - issues C09

VERIFIED target. server compressed_texture.c

REQUIRED CHANGE. Choose direct compressed upload per extension, otherwise one decode to RGBA8. Cache decoded immutable mip data by content hash only when memory budget allows. Never DXT-&gt;ETC2 at runtime in initial implementation.

path = DIRECT_S3TC if supported else DECODE_RGBA8stats.texture_decode_ns += ...

PROTOCOL. None.

INTERACTIONS. Content hashing itself costs CPU; use only for repeated identical payloads or asset-level IDs.

ROLLBACK. Disable cache; preserve direct/decode policy.

## C-FS09 - issues C10

VERIFIED target. client sync wrappers, glx swap; server present

REQUIRED CHANGE. Count sync calls and measure wait time. Remove only provably redundant barriers: cache immutable strings/capabilities; avoid double Finish+Swap wait; keep ReadPixels/GetError semantics. Coalesce error polling into debug checkpoints only when application does not call it.

if swap_already_fences_present &amp;&amp; immediately_preceded_by_internal_finish: remove_internal_finishnever skip application glFinish without explicit compatibility flag

PROTOCOL. Usually none; present-ack changes may affect both ends.

INTERACTIONS. Blind SKIP_GL_FINISH can create use-after-present/resource races; do not backport as default.

ROLLBACK. Per-barrier flags and trace comparison.

## C-FS10 - issues C03

VERIFIED target. client locks/context scratch

REQUIRED CHANGE. After B-FS03 per-context ownership, replace global GL call mutex with per-context recursive lock and thread-local scratch. Calls without current context use global bootstrap lock.

ctx = currentGLContextlock(ctx ? ctx-&gt;call_lock : bootstrap_lock)use ctx-&gt;output_buffer

PROTOCOL. None if protocol unchanged.

INTERACTIONS. WoW may be mostly single-threaded GL; expected gain must be measured. Context migration requires lock handoff.

ROLLBACK. Global-lock fallback env flag.

# 11. Validation plan

VERIFIED [S26]. Use a reference Wine/OpenGL apitrace as the semantic oracle and bridge-local metrics as the transport/performance oracle. Replaying only the translated stream is insufficient if the original call stream is unknown.

## 11.1 Instrumentation to build first

Instrument

Fields

Request counters

count and payload bytes by request code; p50/p95/p99 payload; failed sends

Ring

current/max occupancy, reserve waits and wait ns, wrap/padding records, oversize path, sequence gap/duplicate

Arrays

copied bytes/frame, index bytes, min/max scan ns, cache uploads/refs, hit/miss/invalidation, VBO upload bytes

Sync

round trips/frame and wait ns for Finish, GetError, GetString, ReadPixels, map/unmap, MakeCurrent, Swap/ack

Shaders

canonical key hash, hit/miss, compile/link count and ns, source hash, negative-cache count, resident program bytes

Textures

format/internal/type histogram, direct S3TC/decode/repack path, bytes and ns, mip completeness failures, swizzle changes

Lighting

enabled logical-light mask histogram, maximum enabled, two-sided/separate/color-material/normalize/rescale toggles

Errors

local vs server GL errors, unimplemented call counters, malformed request/reply counters, context-lost reason

Presentation

submitted/presented frame IDs, compositor drop/repeat, swap wait and drawable dimensions

## 11.2 Reference capture and comparison protocol

VERIFIED [S26]. Capture native OpenGL under Wine for build 5875 with the same settings and scene script.

VERIFIED. Record all GL arguments, state snapshots at selected draws, textures and framebuffer images.

VERIFIED. Add a trace-side normalizer that groups ARB/EXT aliases to canonical calls but preserves ordering and values.

HYPOTHESIS. Replay representative frames on desktop GL and on the translated Android lane; compare post-compositor screenshots only after matching viewport/gamma/UI scale.

VERIFIED. For each mismatch, binary-search draw call ranges and dump texture/FPE state for the first divergent draw.

## 11.3 Screenshot scenario matrix

Scene

Primary coverage

Protocol

Login/character select

palette/DXT/UI alpha, scissor, character material

Day + dark background; two races/models

Forest

foliage alpha test, fog, terrain layers

Stationary and camera pan; near/far foliage

City

many materials, UI clipping, line/point paths

Dense players/NPCs; open multiple UI panels

Night + lamps

dynamic lights, attenuation, specular, highp

Walk through overlapping lights; cast bright spell

Terrain blend seam

combine/crossbar/dot3, mipmaps

Four-layer transition at oblique angle and distance

Water/minimap

ARB program, copy/subimage/FBO/read-draw buffers

Move camera and resize/update minimap

VERIFIED (dossier). Run the complete matrix at 800x600 on the validation AVD. Repeat correctness and performance summaries at 1920x1080 on production hardware.

## 11.4 Acceptance thresholds

Domain

Status

Threshold

Rendering

HYPOTHESIS

0 missing/black textures; no channel swaps; correct terrain layers, foliage cutouts, fog and all scripted dynamic lights.

GL errors

HYPOTHESIS

0 unexpected errors after a 120-frame warmup; every remaining expected error is call-site classified.

Protocol

VERIFIED requirement

0 malformed, partial, duplicate or out-of-order records across 10,000 frames and forced ring-wrap stress.

Ring

HYPOTHESIS

p99 occupancy &lt;75%; 0 waits &gt;1 ms in steady-state qualified scene; no oversize fallback in normal play.

Array traffic

HYPOTHESIS

&gt;=80% reduction in client-array bytes/frame after warmup in static forest/city scenes; report absolute before/after.

Shader cache

HYPOTHESIS

0 steady-state compilations after 120 frames per scene; cache hit rate reported by structural key.

Sync

HYPOTHESIS

&lt;=1 bridge round trip/frame in scenes without explicit readback; application-requested Finish remains honored.

Image delta

HYPOTHESIS

After temporal stabilization: UI RMS &lt;=1/255; world RMS &lt;=2/255 or SSIM &gt;=0.995 against reference, with documented gamma tolerance.

## 11.5 Focused micro-tests

Test

Coverage

Format grid

1x1/2x2 textures for ALPHA, LUMINANCE, LA, RGB5, RGB, BGRA packed types, depth24/32; all pixel-store alignments.

Texture env

One quad per combine mode/source/operand/scale/crossbar/dot3 combination observed in trace.

Lighting

0..8 lights, directional/positional/spot, attenuation, local viewer, two side, color material, normalize/rescale.

Arrays

All scalar types/strides, client arrays, bound VBO offsets &gt;4 GiB synthetic, indexed units 0..7, lock/unlock invalidation.

Ring

Record wrap at every header/payload boundary, forced full ring, oversize record, producer/consumer delay, process death.

Lifecycle

MakeCurrent migration, shared-context deletion, destroy with queued work, socket/ring failure and server restart.

# 12. Risk register

ID

Phase

Risk

Probability

Impact

Mitigation

Rollback

A-R1

Phase A

Partial upstream cherry-pick changes attribute numbering on one side

Med

High

Backport behavior cluster; compile shared manifest; trace array indices

Package rollback to eaa2a8d pair

A-R2

Phase A

Texture swizzle persists across redefinition/bind and contaminates another format

Med

High

Per-object swizzle state + format-grid tests

Disable legacy swizzle v2

A-R3

Phase A

Eight-light shader exceeds driver uniform/instruction budget

Med

High

Startup compile variants; compact enabled lights; multipass fallback

4-light diagnostic cap

A-R4

Phase A

Alpha/fog precision changes create driver-specific shader failures

Low-Med

Med

Precision query; source dump; per-driver override

Mediump/old shader switch

B-R1

Phase B

Protocol v2 mixed client/server bricks context creation

Med

Critical

Handshake before rings; reject mismatch; matched hashes

Ship v1 pair as separate package

B-R2

Phase B

Ring one-publish implementation deadlocks under full/oversize record

Med

Critical

Stress wrap/full; bounded waits; slow path; watchdog metrics

v1 ring package

B-R3

Phase B

Context ordering/lifetime fix introduces lock inversion

Med

Critical

Document lock order; TSAN-capable host tests; sequence/drain assertions

Single worker/global lock switch

C-R1

Phase C

Array cache reuses modified client memory

High

High

LockArrays contract first; generation/hash; invalidate on pointer/state change

Inline-array fallback

C-R2

Phase C

Shared slab reused before GPU/worker completes

Med

Critical

Completion generations/fences/refcounts; poison freed slabs in debug

Disable shared slab

C-R3

Phase C

State suppression removes call that should generate error or enter display list

Med

High

Validate arguments before compare; separate list-compilation semantics

Per-state suppression mask

C-R4

Phase C

Batching changes primitive/order/query/readback semantics

Med

High

Strict barriers and golden trace; small batch cap

Batching off

C-R5

All

Metrics perturb performance or overflow counters

Low

Med

64-bit saturating counters; sampling; compile/runtime off

Disable metrics

# 13. Files needed for Phase 2

VERIFIED. gl_calls.c has been received and a safe client-only patch is supplied separately. The following files are ranked by the next highest-confidence correction they unlock.

Priority

Exact file(s)

Phase 2 work

1

server request_handler.c

Match every request payload, implement/validate TexEnv, lighting/getter handlers, audit decoder bounds and 64-bit fields.

2

client main.c

Patch writeUnboundVertexArrays, range scanning, POCKET_DRAW_ATTR descriptors and cache-ID/shared-array design.

3

shared gladio.h

Audit macros, limits, ArrayBuffer scalar helpers, attribute indices, texture/light caps and checked error paths.

4

shared request_codes.h

Create request manifest; specify protocol v2 codes, fixed-width fields and version handshake.

5

client+server ring_buffer.c and headers

Implement one-reservation/one-publication records, wrap padding, barriers, occupancy and sequence metrics.

6

client gl_context.h/.c and gl_client_state.h

Move scratch/locks per context, key caches correctly, add lifecycle/ownership state and client attrib snapshots.

7

server gl_context.c

Prove or fix per-context ordering through the four-thread pool; destruction/migration/drain; parse array framing.

8

server gl_renderer.c

Correct fixed-function array/current attributes, draw setup, alpha/fog/lighting hooks and GLES state cache.

9

server shader_material.c

Implement complete material/light/texenv/alpha/fog structural key and shader cache.

10

server shader_converter.c

Audit GLSL desktop-&gt;ES built-ins, precision, varyings, front-facing and fog/secondary output conversion.

11

server gl_texture.c + gl_formats.c

Backport format/swizzle fixes, BGRA matrix, pixel-store, copy/subimage and mip completeness.

12

server compressed_texture.c + stb_dxt.h wrapper

Direct S3TC capability path, DXT decode fallback and instrumentation.

13

client+server gl_buffer.c

Audit sizeiptr/offset widths, mapped shared memory, dirty ranges and PBO semantics.

14

client+server gl_vao.c/h and arrays.c/h

Coherent ARB/legacy/generic mapping, secondary/fog arrays, attrib bounds and cache descriptors.

15

client glx_calls.c + server GLX/EGL bridge

MakeCurrent ownership, pixel formats, pbuffers, swap/present synchronization and swap control.

16

server attrib_stack.c

Client/server Push/PopAttrib and Push/PopClientAttrib parity, depth and error behavior.

17

server arb_program.c

Program parsing/bind state, attribute/result mapping, water/skinning trace compatibility.

18

server gl_framebuffer.c, gl_query.c

GL_NONE/read-draw buffers, copy/blit, query ordering and result widths.

FILE ATTACHMENT REQUEST. Attach priorities 1-5 next: server request_handler.c, client main.c, shared gladio.h, shared request_codes.h, and both ring_buffer.c/header implementations. If attachment count is constrained, send request_handler.c and main.c first.

# Bibliography and code-reference index

[S01] Gladio repository README and current history. https://github.com/brunodev85/gladio

[S02] Gladio c8183aa - texture format corrections; direct child of eaa2a8d. https://github.com/brunodev85/gladio/commit/c8183aa

[S03] Gladio 806f439 - indexed client arrays, client texture unit pointer fix, half-float and depth handling. https://github.com/brunodev85/gladio/commit/806f439

[S04] Gladio 875969f - ARB program and generic vertex-attrib state improvements. https://github.com/brunodev85/gladio/commit/875969f

[S05] Gladio 7044e42 - legacy alpha/luminance format fallback and texture swizzle. https://github.com/brunodev85/gladio/commit/7044e427c9ad203d0aaa62bca2641a0557da6ef3

[S06] Gladio 9176231 - shared header/state follow-up. https://github.com/brunodev85/gladio/commit/9176231

[S07] Gladio 116c0d1 - vertex attribute bounds hardening. https://github.com/brunodev85/gladio/commit/116c0d1

[S08] Gladio efe6dd1 - ring allocation/mmap cleanup. https://github.com/brunodev85/gladio/commit/efe6dd1

[S09] Gladio a9daf98 - later limits/finish/texture-rectangle changes. https://github.com/brunodev85/gladio/commit/a9daf98

[S10] GL4ES repository and documented feature scope. https://github.com/ptitSeb/gl4es

[S11] GL4ES drawing.c - array locking, conversion, draw setup and batching. https://raw.githubusercontent.com/ptitSeb/gl4es/refs/heads/master/src/gl/drawing.c

[S12] GL4ES listdraw.c - render-list/display-list array and VBO caching. https://raw.githubusercontent.com/ptitSeb/gl4es/refs/heads/master/src/gl/listdraw.c

[S13] GL4ES fpe_cache.c - whole fixed-function-state shader cache. https://raw.githubusercontent.com/ptitSeb/gl4es/refs/heads/master/src/gl/fpe_cache.c

[S14] GL4ES light.c - lighting state transformations and change suppression. https://raw.githubusercontent.com/ptitSeb/gl4es/refs/heads/master/src/gl/light.c

[S15] GL4ES fpe_shader.c - highp lighting, two-sided, specular, fog and alpha-test shader generation. https://raw.githubusercontent.com/ptitSeb/gl4es/refs/heads/master/src/gl/fpe_shader.c

[S16] OpenGL 2.1 specification. https://registry.khronos.org/OpenGL/specs/gl/glspec21.pdf

[S17] EXT_compiled_vertex_array specification. https://registry.khronos.org/OpenGL/extensions/EXT/EXT_compiled_vertex_array.txt

[S18] ARB_texture_env_combine specification. https://registry.khronos.org/OpenGL/extensions/ARB/ARB_texture_env_combine.txt

[S19] ARB_texture_env_crossbar specification. https://registry.khronos.org/OpenGL/extensions/ARB/ARB_texture_env_crossbar.txt

[S20] ARB_texture_env_dot3 specification. https://registry.khronos.org/OpenGL/extensions/ARB/ARB_texture_env_dot3.txt

[S21] EXT_texture_compression_s3tc specification. https://registry.khronos.org/OpenGL/extensions/EXT/EXT_texture_compression_s3tc.txt

[S22] Android OpenGL ES texture-compression guidance. https://developer.android.com/develop/ui/views/graphics/opengl/about-opengl

[S23] OpenGL ES Shading Language 3.20 specification. https://registry.khronos.org/OpenGL/specs/es/3.2/GLSL_ES_Specification_3.20.html

[S24] Linux kernel circular-buffer memory-ordering documentation. https://docs.kernel.org/6.2/core-api/circular-buffers.html

[S25] Linux kernel AF_XDP rings/UMEM documentation. https://docs.kernel.org/networking/af_xdp.html

[S26] apitrace project: trace/replay/state/texture/framebuffer inspection. https://apitrace.github.io/

[S27] Arch Linux 2007 WoW/Wine thread containing build 5875 DrawRangeElementsEXT stack and OpenGL/VBO workaround. https://bbs.archlinux.org/viewtopic.php?id=29150

[S28] Pillow BLP decoder documentation/source. https://github.com/python-pillow/Pillow/blob/main/src/PIL/BlpImagePlugin.py

[S29] Community BLP format overview: indexed palette, alpha depths, DXT and mipmaps. https://www.wowmodding.net/topic/1567-12-detailed-description-of-wow-file-types/

[S30] Box64 repository and dynamic-recompiler description. https://github.com/ptitSeb/box64

[S31] ARB_vertex_program specification. https://registry.khronos.org/OpenGL/extensions/ARB/ARB_vertex_program.txt

[S32] ARB_vertex_shader specification. https://registry.khronos.org/OpenGL/extensions/ARB/ARB_vertex_shader.txt

## Attached-code reference index

Ref

Location / observation

C01

gl_calls.c lines 1-7409: complete attached client source; 161 explicit stub bodies counted by static scan.

C02

lines 543-550: glCallList forwarded; glCallLists stubbed.

C03

lines 696-831: current color integer conversions.

C04

lines 1456-1625: draw wrappers call writeUnboundVertexArrays every draw.

C05

lines 1702-1731: glEnd sends metadata and command stream in separate writes.

C06

lines 1867-1877 and 2393-2403: Finish sync, Flush async, GetError sync/kill-switch.

C07

lines 3397-3420: LightModelfv/iv stubs.

C08

lines 1393-1405, 1656-1668, 4082-4085: indexed client state stubs and MultiTexCoordPointer implementation.

C09

lines 4305-4357: integer current normals raw-cast to float.

C10

lines 5159-5298: secondary color conversion and pointer stub.

C11

lines 4577-4801: attrib/client-attrib stack forwarding.

C12

lines 5148-5156 and 4560-4566: scissor and polygon offset forwarding.

C13

lines 175-183, 1027-1035, 1880-1893, 4942-4962, 7210-7238: pointer/size fields written with putInt.

HYPOTHESIS. The ordering of work in this paper is intentionally conservative: prove the real build-5875 stream, fix deterministic correctness mismatches, harden the wire/lifecycle, then optimize array transport. This minimizes the chance that a faster bridge merely hides or randomizes a rendering bug.