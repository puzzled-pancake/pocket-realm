# Pocket Realm — Third-Party Notices

Pocket Realm is distributed in the hope that it will be useful, but WITHOUT ANY
WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
PARTICULAR PURPOSE. See each license below for details.

This file accompanies every Pocket Realm release package. The complete
corresponding source for everything distributed here is public: the Pocket
Realm repository at the release tag named on this release, plus the pinned
submodule commits and provider archives recorded in `schemas/sources.json`.
For at least three years after each release we will provide that exact source
to anyone who asks (GPL-2.0 section 3(b) written offer).

## Pocket Realm original code

- License: GNU General Public License v3.0 (GPL-3.0). See the repository root
  `LICENSE`.

## Server core and content

- CMaNGOS (mangos-classic) — GPL-2.0-or-later. `native/cmangos`, pinned
  commit recorded in `schemas/sources.json`. Built as Android native libraries
  and linked into the app.
- Playerbots — GPL-2.0-or-later (inherits CMaNGOS licensing). `native/playerbots`.
- classic-db schemas — GPL-3.0-or-later (code); the shipped game content is
  Blizzard copyrighted material redistributed only as the public upstream
  project publishes it. `native/classic-db`.

## Database

- MariaDB (server, x86_64 glibc build and ARM64 Bionic conversion) —
  GPL-2.0-only. MariaDB runs as an isolated process behind an app-private
  Unix socket; it is never linked into the app or Pocket Realm's GPL-3.0 code.
- MariaDB Connector/C — LGPL-2.1-or-later, statically linked into the isolated
  realm-server components. Because the complete combined-work source and the
  full build toolchain are public at the release tag, relinking users can
  rebuild those components from source; this notice and the public repository
  together serve as the LGPL source offer.
- libstdc++ (GCC 16.1.0, from the pinned builder image) — GPL-3.0-or-later
  WITH GCC Runtime Library Exception.

## Wine runtime (x86_64)

- Wine 11.14 (Kron4ek provider build + Pocket Realm's source-rebuilt
  16 KB dispatcher pair) — LGPL-2.1-or-later. Build scripts: MIT
  (Copyright Kron4ek).
- glibc 2.43 — GPL-3.0-or-later / LGPL-3.0-or-later (build scripts MIT).
- libgcc/libstdc++ runtime — GPL-3.0-or-later with GCC Runtime Library
  Exception.
- freetype — GPL-2.0 (FTL also available upstream).
- libpng 1.6.47 — Libpng-2.0 (mandatory transitive dependency of freetype).
- libX11/libxcb/libXau/libXdmcp/libXext, fontconfig, and remaining glibc-side
  X11/font closure — per-package MIT/X11, BSD-3-Clause, HPND, and ISC as
  recorded in `schemas/wine-runtime-lockfile.json`.
- proot (GPL-2.0-or-later) and talloc (LGPL-3.0-or-later) are built only for
  the fallback exec path and are absent from this package unless the release
  notes say otherwise.
- alsa-lib (LGPL-2.1) is built only for the optional audio mode and is absent
  from this package unless the release notes say otherwise.

## ARM64 translated runtime (Box64 rootfs trio)

- Winlator ARM runtime archives (rootfs.tzst, rootfs_patches.tzst,
  container_pattern.tzst) — redistributed AS RECEIVED from the public
  winlator-app provider at the pinned commit recorded in
  `schemas/sources.json`. The image is Ubuntu-based and contains:
  - Box64 0.4.0 — MIT (Copyright ptitSeb).
  - Wine 10.10 — LGPL-2.1.
  - An Ubuntu userspace under its recorded per-package licenses
    (glibc LGPL, freetype GPL-2.0, and others; see
    `schemas/wine-runtime-lockfile.json` for the recorded set).
  The provider repository and commit are the corresponding-source reference
  for the image assembly.

## Graphics stack

- DXVK (2.4.1 / 1.10.3) — zlib license.
- Turnip (Mesa Vulkan driver, 26.1.0) — MIT (Mesa/X11 style, Copyright the
  Mesa/X11 contributors as recorded in the driver package).
- virglrenderer — MIT (Copyright (C) 2014 Red Hat Inc.; per-file permission
  notices retained in the vendored source at
  `native/xserver-winlator/cpp/virglrenderer/`).
- Mesa custom virpipe client (23.1.9) — MIT and component licenses recorded
  by Mesa upstream.
- Gladio (GLX client/server pair) — LGPL-2.1 (Winlator-lineage; adapted
  source retained in-tree).
- Winlator X-server layer (Java + native transport) — LGPL-2.1. Adapted
  source retained in-tree at `runtime/xserver-winlator/` and
  `native/xserver-winlator/cpp/` (see `runtime/xserver-winlator/LICENSE`);
  Pocket Realm's original additions in that tree are offered under the same
  LGPL-2.1 terms.

## Other components

- vanilla-tweaks — MIT (Copyright (c) 2022 brndd). `native/vanilla-tweaks`.
- OpenSSL (libcrypto.so.3, libssl.so.3, ARM64 closure) — Apache-2.0
  (determined from the Termux package records the provider archives are built
  from).
- libc++ (libc++_shared.so, ARM64 closure) — Apache-2.0 WITH LLVM-exception
  (determined from the Termux/LLVM upstream records).
- ncurses — MIT/X11-style. libedit — BSD-3-Clause. pcre2 — BSD-3-Clause.
- libcrypt — BSD-style (NetBSD/OpenBSD-derived crypt(3) code; determined from
  the Termux package records the provider archives are built from).
- libandroid-support — GPL-3.0 (as declared by the Termux package; the code
  additionally embeds BSD-licensed NetBSD-origin routines).
- libarchive 3.8.1 (libarchive-jni.so, archive client import) — BSD-2-Clause
  and other permissive terms per its COPYING file; the bundled RAR/RAR5
  readers (`archive_read_support_format_rar.c`, Tim Kientzle & Andres Mejia;
  `archive_read_support_format_rar5.c`, Grzegorz Antoniak) are clean-room
  implementations with no UnRAR-derived code. Consumed via
  `me.zhanghai.android.libarchive:library:1.1.6` (Apache-2.0 Java/JNI
  bindings), which also bundles mbed TLS (Apache-2.0), xz for Java
  (public domain, 0BSD), zstd (BSD-3-Clause) and bzip2 (BSD-style) builds.
- xz for Java 1.10 (org.tukaani:xz) — public domain / 0BSD (7z LZMA entry
  streams via commons-compress).
- Inno Setup installer payload reader (com.pocketrealm.importer.inno) — an independent Kotlin
  implementation of the publicly documented Inno Setup 5.x data format, written against the
  zlib-licensed innoextract 1.9 by Daniel Scharrer as the format reference (no innoextract code
  is included). LZMA1 decoding uses xz for Java, credited above.

## AI bot LLM runtime

- llama.cpp (llama-server, libllama-server-impl.so, libmtmd.so and the
  staged llama/ggml closure) — MIT, Copyright (c) 2016-2026 Georgi Gerganov
  and the llama.cpp contributors (github.com/ggml-org/llama.cpp), revision
  6d05498 with one local SIGTERM-during-load patch (see
  `android/pocketrealm-llm/prebuilt/README.md`). MIT text:

  Permission is hereby granted, free of charge, to any person obtaining a
  copy of this software and associated documentation files (the "Software"),
  to deal in the Software without restriction, including without limitation
  the rights to use, copy, modify, merge, publish, distribute, sublicense,
  and/or sell copies of the Software, and to permit persons to whom the
  Software is furnished to do so, subject to the following conditions: The
  above copyright notice and this permission notice shall be included in all
  copies or substantial portions of the Software. THE SOFTWARE IS PROVIDED
  "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT
  NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A
  PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
  COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
  WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF
  OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
  SOFTWARE.

- Qualcomm GenieX Hexagon NPU backend (libggmlhex.so, geniex v0.5.0) and
  the HTP DSP skeletons (libggml-htp-v73/v75/v79/v81.so) — BSD-3-Clause,
  Copyright (c) 2024-2026, Qualcomm Technologies, Inc. and/or its
  subsidiaries. All rights reserved. Source:
  github.com/qualcomm/GenieX. Use is also subject to Qualcomm's Terms of
  Use as published by the upstream project. BSD-3-Clause text:

  Redistribution and use in source and binary forms, with or without
  modification, are permitted provided that the following conditions are
  met: 1. Redistributions of source code must retain the above copyright
  notice, this list of conditions and the following disclaimer. 2.
  Redistributions in binary form must reproduce the above copyright notice,
  this list of conditions and the following disclaimer in the documentation
  and/or other materials provided with the distribution. 3. Neither the
  name of the copyright holder nor the names of its contributors may be
  used to endorse or promote products derived from this software without
  specific prior written permission. THIS SOFTWARE IS PROVIDED BY THE
  COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED
  WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
  MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN
  NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY
  DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
  DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS
  OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
  HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
  STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN
  ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
  POSSIBILITY OF SUCH DAMAGE.
