/*
 * com.winlator.sysvshm.SysVSharedMemory — STUB for the O06 S-3 spike.
 *
 * Source: brunodev85/winlator-app ca3d735 (LGPL-2.1). Upstream is a JNI wrapper
 * around libwinlator.so's System V shared memory (used by DRI3 + MIT-SHM). The
 * spike does not build that native module, so these are no-op stubs that return
 * null/empty buffers. The S-3 path (window create+map+GLES render) does not
 * require SysV SHM. See docs/patches/wine-provider-provenance.md.
 */
package com.winlator.sysvshm;

import java.nio.ByteBuffer;

public class SysVSharedMemory {
    /** No-op stub. Upstream attaches to an existing SysV SHM segment; the spike
     *  returns null (no SHM segments are created during window create+map). */
    public ByteBuffer attach(int shmid) { return null; }
    public void detach(ByteBuffer data) { }
    public static ByteBuffer mapSHMSegment(int fd, long size, long offset, boolean readOnly) { return null; }
    public static void unmapSHMSegment(ByteBuffer data, long size) { }
}
