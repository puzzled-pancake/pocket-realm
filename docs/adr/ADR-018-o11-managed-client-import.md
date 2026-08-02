# ADR-018: O11 resumable managed-client import and prepared-data publication

Status: accepted and qualified (2026-08-03)

## Decision

Pocket Realm treats a user-selected WoW installation as an immutable, read-only
source. A persisted SAF read grant enters a dedicated `:import` foreground
process. The fast classifier reads only `WoW.exe` and the immediate `Data`
layout first; only x86 PE32 version 1.12.1 build 5875 may proceed. Runtime and
data preparation consume a hash-verified app-private generation, never the SAF
tree.

Full traversal is deterministic and bounded. Paths are NFKC-normalized, case-
fold collision checked, relative-only, depth/component/entry/size bounded, and
virtual or special documents are rejected. Storage preflight includes the new
managed copy, extracted data, database, Wine prefix/cache, a snapshot allowance,
and `max(2 GiB, 20%)` working headroom.

## Durable copy and publication

The schema-2 SQLite journal uses WAL plus `synchronous=FULL`. Each file records
normalized path, provider document ID, expected size/mtime, state, copied bytes,
partial name, SHA-256, attempt, error, and fsync marker. A copy becomes verified
only after its partial is fsynced, renamed within the generation, and its parent
directory is fsynced. Resume rehashes every previously verified target; a
missing or corrupted target is recopied. The complete source inventory is
fingerprinted again before publication.

Publication writes and fsyncs a manifest, renames the staging directory to its
UUID generation, fsyncs the generations directory, then atomically replaces an
active pointer containing the manifest digest. Recovery validates the renamed
generation if death occurs before pointer activation. Re-import always creates
a new generation. Current and previous generations are retained; older and
abandoned staging generations are retired so storage use remains bounded.

App-owned `realmlist.wtf` and safe-mode `WTF/Config.wtf` are written only into
the managed copy. Unrecognized root executables and injected DLLs are excluded
from the runtime generation. No proprietary executable, MPQ, or generated realm
data is committed as project evidence.

## Prepared realm data

Four fixed-purpose x86_64 Android/Bionic PIEs—DBC/map extractor, VMAP extractor,
VMAP assembler, and MoveMapGen—ship through `nativeLibraryDir`. Their source
commit, source-patch hash, artifact SHA-256 values, dependency closure, and
16 KiB LOAD alignment are pinned by `schemas/o11-extractor-lockfile.json`.
`tools/build_o11_extractors.py` materializes the exact clean CMaNGOS commit and
applies `native/patches/o11-cmangos-safe-mpq-listfile.patch`; it never treats the
mutable build directory as provenance.

The patch was required by real-client evidence. Upstream passed an MPQ
`(listfile)` buffer without a trailing NUL to `strtok`, causing SIGSEGV at the
end of VMAP extraction on Android. The transferred-length-bounded parser fixed
the crash, and attempt 3 resumed over already-created raw inputs rather than
recopying the 5.39 GB client.

DBC/maps, VMAP extraction, VMAP assembly, and each of 22 MMAP map IDs are
separate durable checkpoints. Generated intermediates are removed before final
publication. The normal-data manifest contains hashes for every runtime file,
dataset counts, build/family identity, source-client generation, exact generator
commit and binary hashes, ABI/API/page size, and `mode=NORMAL`. Its active
pointer is written only after all stages verify.

`PreparedDataStore` is the fail-closed normal-play reader. It validates the
active-pointer digest, compatible complete manifest, minimum required counts,
safe paths, sizes, and every file hash. `ServerRuntimeFiles.worldConfigNormal()`
enables VMAP/MMAP only through that reader; missing or damaged O11 data cannot
silently enter normal play. The earlier O09 no-navigation baseline remains a
separate explicit test path until O12 attaches the integrated client lifecycle.

## Qualification

The read-only debug SAF provider passed the complete interruption test on both
API-35 x86_64 lanes. Each lane rejects a wrong build, repairs deliberate target
corruption, survives ten `:import` deaths, covers death before publish and death
after rename/before activation, publishes exactly one complete generation, and
proves re-import uses a new generation while retention remains bounded to two.
The 4 KiB lane completed in 15.191 seconds and the 16 KiB lane in 19.878 seconds.

The real user-owned 149-file, 5,389,935,386-byte build-5875 client completed on
the 4 KiB large-storage API-35 lane. The immutable data generation contains 158
DBC files, 2,429 map tiles, 43 VMAP trees, 1,249 VMAP tiles, 22 MMAP maps, and
1,815 MMAP tiles: 9,204 hash-recorded files totaling 2,280,526,960 bytes. The
data-manifest SHA-256 is
`be6478859781dd8cda5e85cd47d98ae84b66216a6f392f616a07b89ba482e41c`;
the active pointer matches it and no staging generation remains.

Authoritative evidence:

- `tests/avd/AVD-Modern-x86_64-v1/evidence/managedImport-o11-interruptions-20260803.PASS.json`
- `tests/avd/AVD-16K-x86_64-v1/evidence/managedImport-o11-interruptions-20260803.PASS.json`
- `tests/avd/O11-Large-x86_64/evidence/managedImport-o11-real-build5875-20260803.PASS.json`
- `tests/avd/O11-Large-x86_64/evidence/managedImport-o11-real-build5875-20260803.png`
