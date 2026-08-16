Phase 2 - gl_calls.c Corrections

Safe client-only unified diff against the attached 7,408-line source

Field

Value

Input

/mnt/data/gl_calls.c

Input SHA-256

412b179876f5399548ddb3d4f6470624bf61c31a5677cff87b09ee0587a48ec0

Patch

gl_calls.phase2-safe.patch

Protocol change

None

Required deployment action

Rebuild/re-hash client libGL.so.1; bump matched package build ID for traceability

Verification performed

Patch dry-run, apply and byte-for-byte comparison to generated corrected file

Not performed

Compilation, link, game run or device validation

# Included corrections

Issue

Function(s)

Exact behavior

Protocol

A09

glDisable/EnableClientStateIndexedEXT/iEXT

Backport upstream behavior: switch client-active texture coordinate unit, invoke canonical enable/disable, restore prior unit.

No

A09

glMultiTexCoordPointerEXT

Use glClientActiveTexture, not GL_DSA active server texture state, while setting the pointer.

No

A14/A17/A35

glColor*, glNormal*, glSecondaryColor* integer variants

Apply OpenGL 2.1 signed formula (2c+1)/(2^b-1) and unsigned formula c/(2^b-1), with double intermediate for GLint.

No

A30

glAreTexturesResident

Populate every output residence with GL_TRUE to match the existing always-resident return policy.

No

A30/B13

glGetProgramInfoLog / glGetShaderInfoLog

Clamp server length to remaining reply and caller bufSize-1; null-terminate only when buffer exists.

No

# Why the integer conversion is a correctness fix

VERIFIED [S16]. Desktop OpenGL maps signed b-bit color and normal components using (2c+1)/(2^b-1), producing exactly -1 at the minimum and +1 at the maximum. Dividing signed values by INT*_MAX is not equivalent for negative values, and raw-casting integer normals is incorrect.

static inline GLfloat gladioSignedNormalizedToFloat(int64_t value, uint64_t unsignedMax) {    return (GLfloat)(((2.0 * (double)value) + 1.0) / (double)unsignedMax);}static inline GLfloat gladioUnsignedNormalizedToFloat(uint64_t value, uint64_t unsignedMax) {    return (GLfloat)((double)value / (double)unsignedMax);}

# Assumptions

HYPOTHESIS. GL_CALL_LOCK is recursive, so the upstream indexed-client-state pattern may call glClientActiveTexture and glEnable/DisableClientState while held.

HYPOTHESIS. activeTexCoord is a zero-based client texture coordinate unit.

HYPOTHESIS. glClientActiveTexture is the canonical state-mirror + server update path.

HYPOTHESIS. int64_t, uint64_t and UINT*_MAX are included by the existing project headers.

HYPOTHESIS. The server already receives the same requests; no request decoder change is needed for these hunks.

# Deliberately blocked until dependent files are attached

Issues

Blocked patch

Required dependency

B01/B09/B10

64-bit size/offset/pointer/GLsync wire fixes

Needs request_codes.h, gladio.h, client/server decoders and ring schema.

B02/B03

Atomic glEnd and one-publication records

Needs both ring_buffer implementations and server glEnd parser.

A01/A02/A04-A07

Texture formats, compression and combine

Needs server gl_texture/gl_formats/compressed_texture/request_handler/shader state.

A12-A19

Lighting, material, secondary arrays, alpha, fog

Needs gl_renderer, shader_material, shader_converter and main.c framing.

C01/C11

Array cache/shared-memory references

Needs main.c, server gl_context renderer parser, request codes and rings.

FILE ATTACHMENT REQUEST. Attach server request_handler.c and client main.c first, followed by gladio.h, request_codes.h and both ring_buffer implementations. Those files unlock the highest-severity both-end corrections without inventing unseen code.