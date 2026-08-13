#ifndef VORTEK_TRANSPORT_CONTRACT_H
#define VORTEK_TRANSPORT_CONTRACT_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

/* The Vortek 2.1 frame header remains two little-endian 32-bit words.  These
 * checks are local trust-boundary rules and do not change its wire bytes. */
#define VT_TRANSPORT_HEADER_SIZE 8u

static inline bool vt_transport_payload_fits(
        int32_t payloadSize, uint32_t ringCapacity, size_t payloadLimit) {
    if (payloadSize < 0 || ringCapacity < VT_TRANSPORT_HEADER_SIZE) return false;
    const size_t payload = (size_t)(uint32_t)payloadSize;
    return payload <= payloadLimit &&
            payload <= (size_t)ringCapacity - VT_TRANSPORT_HEADER_SIZE;
}

static inline bool vt_transport_size_fits(
        size_t payloadSize, uint32_t ringCapacity, size_t payloadLimit) {
    return payloadSize <= (size_t)INT32_MAX &&
            vt_transport_payload_fits(
                    (int32_t)payloadSize, ringCapacity, payloadLimit);
}

#endif
