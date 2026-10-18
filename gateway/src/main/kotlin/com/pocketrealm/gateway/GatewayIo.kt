package com.pocketrealm.gateway

import com.pocketrealm.discovery.iroh.AuthStreamFilter
import computer.iroh.BiStream
import java.net.Socket

/**
 * W6-N3 raw byte pumps. The D92 transport finding ("lock-step echo frames
 * at 64 KiB stall — the gateway echo must read concurrently or keep
 * lock-step frames ~1 KiB") is honored by construction: every direction is
 * its OWN coroutine doing small chunked copies (16 KiB reads, immediate
 * writes, per-chunk flush) — reads and writes are never in lock step, and
 * there is NO framing after the mux header (raw game bytes both ways).
 */
internal object GatewayIo {

    /** Read chunk size (each read returns whatever arrived, up to this). */
    internal const val CHUNK: Int = 16 * 1024

    /**
     * iroh → socket: copy until the iroh stream ends, then half-close the
     * socket so the client sees a clean end of that direction.
     */
    internal suspend fun pumpIrohToSocket(bi: BiStream, socket: Socket) {
        val input = bi.recv()
        val output = socket.getOutputStream()
        val buffer = ByteArray(CHUNK)
        while (true) {
            val chunk = input.read(CHUNK.toUInt())
            if (chunk.isEmpty()) break
            output.write(chunk)
            output.flush()
        }
        runCatching { socket.shutdownOutput() }
    }

    /**
     * iroh → socket WITH the realm-list filter (the 3724 lane): chunks are
     * reassembled into realmd frames and every SMSG_REALM_LIST is rebuilt;
     * a parse failure throws out of [AuthStreamFilter.feed] (the loud exit
     * is the caller's).
     */
    internal suspend fun pumpIrohToSocketFiltered(
        bi: BiStream,
        socket: Socket,
        filter: AuthStreamFilter,
    ) {
        val input = bi.recv()
        val output = socket.getOutputStream()
        val buffer = ByteArray(CHUNK)
        while (true) {
            val chunk = input.read(CHUNK.toUInt())
            val eof = chunk.isEmpty()
            val frames = filter.feed(chunk, eof = eof)
            for (frame in frames) {
                output.write(frame)
            }
            output.flush()
            if (eof) break
        }
        runCatching { socket.shutdownOutput() }
    }

    /** socket → iroh: copy until the client half-closes, then finish the
     * send half (iroh's EOF to the host side). */
    internal suspend fun pumpSocketToIroh(socket: Socket, bi: BiStream) {
        val input = socket.getInputStream()
        val output = bi.send()
        val buffer = ByteArray(CHUNK)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (n > 0) output.writeAll(buffer.copyOf(n))
        }
        output.finish()
    }
}
