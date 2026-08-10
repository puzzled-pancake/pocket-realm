# RP6 OpenGL corruption research

Date: 2026-08-10  
Device: Retroid Pocket 6, arm64-v8a, 1920x1080 landscape  
Client: WoW 1.12.1 / build 5875 through Box64 + Wine + Gladio

## Executive result

There are two different rendering failures, not one:

1. The loading/world-transition frame had a hard horizontal corruption boundary at row 496. This was caused by the presentation `glReadPixels` path inheriting guest `GL_PACK_*` state and, potentially, a guest pixel-pack buffer. The Android-side readback now saves and restores that state while forcing a tightly packed RGBA read. A fresh post-fix frame no longer has the horizontal scanline corruption.
2. The remaining character/login scene is still visually wrong: black polygon cutouts, missing/stretched terrain and dark model passes remain. This is upstream Gladio/WineD3D compatibility work and is not fixed by the readback change.

No OpenGL acceptance claim is made.

## Reproduction evidence

The two pre-fix transition captures were byte-identical despite being 47 seconds apart:

- `tmp/rp6-world-now.png`
- `tmp/rp6-world-later.png`
- SHA-256: `275F03A7A17BF0FF946A19544AACD034C97B3B19E8DA42325164C3B3C325EE13`

Both showed intact loading artwork above approximately `y=496` and horizontal noise below it. The Android control overlay remained clean, which localizes the fault to the client framebuffer readback/upload path rather than the Android overlay compositor.

The post-fix capture was:

- `tmp/rp6-packfix-character.png`

It has no horizontal scanline band. It still shows black/stretched 3D geometry, so the two symptoms must remain separate in diagnosis.

## Confirmed readback defect

The presentation path calls `Texture.copyFromReadBuffer()` from the Wine/Gladio GLES context. Before the fix it directly called:

```java
GLES20.glReadPixels(0, 0, width, height,
        GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, readbackBuffer);
```

Gladio forwards guest `glPixelStorei` calls for `GL_PACK_ROW_LENGTH`, `GL_PACK_SKIP_PIXELS`, and `GL_PACK_SKIP_ROWS` to the real GLES context. The readback destination, however, is allocated as exactly `width * height * 4` tightly packed bytes. A nonzero row length/skip or a bound `GL_PIXEL_PACK_BUFFER` therefore makes the readback layout disagree with the Java buffer. A row-pitch mismatch produces precisely the observed sharp horizontal boundary.

The fix in `runtime/xserver-winlator/com/winlator/renderer/Texture.java`:

- saves `GL_PACK_ALIGNMENT`, `GL_PACK_ROW_LENGTH`, `GL_PACK_SKIP_PIXELS`, `GL_PACK_SKIP_ROWS`, and `GL_PIXEL_PACK_BUFFER_BINDING`;
- sets alignment to 1, row length/skips to zero, and binds no pixel-pack buffer;
- performs the tightly packed RGBA read;
- restores every saved value in `finally` so guest state is unchanged.

The same class also brackets `allocateTexture()` and `updateFromDrawable()`
with tight `GL_UNPACK_*` state and an unbound pixel-unpack buffer, restoring
the prior values afterward. This prevents a guest upload layout from
indexing through the tightly packed Java readback buffer.

This is intentionally narrower than changing presentation-source selection or replacing the whole ownership model.

## Upstream comparison

The pinned Winlator source uses `glCopyTexImage2D` for `Texture.copyFromReadBuffer`, which avoids CPU pack-layout interpretation:

- https://github.com/brunodev85/winlator-app/blob/ca3d735a60d653a787daf16d14fafef28d9c2c23/app/src/main/java/com/winlator/renderer/Texture.java#L129-L135

PocketRealm keeps its CPU-backed ownership path because Android surface/context recreation can replace the Java renderer context while Wine continues using its EGL share context. The pack-state isolation preserves that lifecycle design without adopting a context-unsafe GPU texture ownership change.

The OpenGL specification defines `glReadPixels` rows as framebuffer rows and defines `GL_PACK_*` state as controlling the destination layout:

- https://wikis.khronos.org/opengl/GLAPI/glReadPixels
- https://wikis.khronos.org/opengl/GLAPI/glPixelStore
- https://wikis.khronos.org/opengl/Pixel_Transfer

## Remaining 3D corruption

The remaining black/stretched scene is visible in:

- `tmp/rp6-packfix-character.png`
- `tmp/rp6-700-autologin-live2.png`

Current logs repeatedly report unsupported fixed-function/legacy state:

- `gladio:setCapabilityState: unimplemented cap c60/c61/c62` (`GL_TEXTURE_GEN_S/T/R`)
- `gladio: glTexGeni not implemented yet`
- `gladio: unimplemented asm option ARB_fog_linear`
- `gladio:setTexEnvParams: unimplemented pname 8862` (`GL_COORD_REPLACE`)

`glTexGen` is a per-vertex legacy operation. It generates texture coordinates from object/eye coordinates or reflection/sphere-map calculations; simply ignoring the enable and mode leaves the shader with stale or default coordinates. The Khronos explanation is:

- https://wikis.khronos.org/opengl/Mathematics_of_glTexGen

The ARB vertex-program path is also a known compatibility boundary. The Khronos ARB vertex-program specification makes vertex-program enablement independent of fragment-program enablement and assigns environment parameters to the GL context, not to only the program objects that existed when a setter was called:

- https://registry.khronos.org/OpenGL/extensions/ARB/ARB_vertex_program.txt

PocketRealm's prior bounded traces already found and addressed several Gladio defects in this area (context-global ARB environment values, independent VP/FP composition, SGE/SLT operand conversion and constant parsing). The current screenshots show that more scene-specific state remains; no new shader or texgen change is claimed without a binary device result.

## Why this is not currently an Adreno-only presentation failure

The same final frame can contain a clean WoW logo, readable UI and a recognizable character while the 3D environment is malformed. That selective failure is upstream of the final Android presentation surface. The post-fix removal of the row-corruption band further separates presentation readback from the remaining geometry/material problem.

## Post-fix device result

The clean retry on the RP6 used the rebuilt arm64 OpenGL runtime and the
same `mobile-lively-b700-v1` auto-login flow. Auto-login completed and the
settled character-list frame (`tmp/rp6-packfix-retry-live.png`) showed the
dwarf model and UI without the horizontal readback band. This qualifies the
fix for that path only; it does not qualify the world path.

After sending Enter World, two captures (`tmp/rp6-packfix-world.png` and
`tmp/rp6-packfix-world-later.png`) were 57 seconds apart and had the same
SHA-256 (`773D1BB721746C9278309B016F8F92711AFD17849207F833D57E519A37FAC760`).
Both still contain the sharp horizontal corruption boundary. Therefore the
world transition is either using a different readback/context path or is
reusing a stale texture; the Java PACK and UNPACK brackets are not sufficient
for that path. The remaining 3D black/stretched geometry is
also present independently on character select.

## Next bounded validation

1. Re-run with a valid auto-login and capture a settled character-list frame plus a settled in-world frame after the readback fix.
2. If horizontal corruption returns, log the actual readback FBO completeness and attachment dimensions immediately before `glReadPixels`; reject any candidate smaller than the 1920x1080 display.
3. For the remaining 3D corruption, capture one character-scene draw trace for the dominant VP/FP tuples and record whether texture-generation enables/modes are active on those draws. Implement only the active mode (object/eye linear or sphere map) and add a focused shader/state test.
4. Keep the strict chromatic/diverse visual gate; a non-black pixel count alone is insufficient because a grayscale/depth-like intermediate buffer can pass it.

Until those steps pass on the RP6, the OpenGL renderer remains pending.
