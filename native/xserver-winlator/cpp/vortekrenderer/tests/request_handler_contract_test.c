#define VORTEK_REQUEST_HANDLER_CONTRACT_ONLY 1
#include "request_handler.h"
#include "vortek_transport_contract.h"

#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static void put_i32(uint8_t* output, int32_t value) {
    memcpy(output, &value, sizeof(value));
}

static int preflight_calls;
static int execution_calls;

static bool decode_payload(const VtRequestBatchChunk* chunk) {
    preflight_calls++;
    return chunk->size == 1 && chunk->data[0] == 0x5a;
}

static bool preflight_then_execute(const uint8_t* data, size_t size) {
    size_t position = 8;
    VtRequestBatchChunk chunk;
    VtRequestBatchStep step;
    while ((step = vt_request_batch_next(data, size, &position, &chunk)) ==
            VT_REQUEST_BATCH_CHUNK) {
        if (!decode_payload(&chunk)) return false;
    }
    if (step != VT_REQUEST_BATCH_DONE) return false;
    position = 8;
    while (vt_request_batch_next(data, size, &position, &chunk) ==
            VT_REQUEST_BATCH_CHUNK) execution_calls++;
    return true;
}

static void test_capacities(void) {
    assert(vt_request_query_copy_count_inline(0, 3) == 0);
    assert(vt_request_query_copy_count_inline(1, 3) == 1);
    assert(vt_request_query_copy_count_inline(3, 3) == 3);
    assert(vt_request_query_copy_count_inline(2, 3) == 2);
    assert(vt_request_query_copy_count_inline(UINT32_MAX, 3) == 3);

    assert(vt_request_query_storage_count_inline(0, 3) == 0);
    assert(vt_request_query_storage_count_inline(1, 3) == 3);
    assert(vt_request_query_storage_count_inline(3, 3) == 3);
    assert(vt_request_query_storage_count_inline(5, 3) == 5);
    assert(vt_request_query_storage_count_inline(UINT32_MAX, 3) == UINT32_MAX);

    /* Model the four pNext-bearing query handlers with canaries.  The same
     * storage rule is used for each handler; ASan verifies both the decoded
     * guest prefix and host-initialized suffix remain inside the allocation. */
    const uint32_t capacities[][2] = {{0, 3}, {1, 3}, {3, 3}, {5, 3}};
    for (size_t handler = 0; handler < 4; ++handler) {
        for (size_t test = 0; test < sizeof(capacities) / sizeof(capacities[0]);
                ++test) {
            const uint32_t guest = capacities[test][0];
            const uint32_t server = capacities[test][1];
            const uint32_t count =
                    vt_request_query_storage_count_inline(guest, server);
            uint32_t* allocation = calloc((size_t)count + 2u, sizeof(uint32_t));
            assert(allocation != NULL);
            allocation[0] = UINT32_C(0xa5a5a5a5);
            allocation[(size_t)count + 1u] = UINT32_C(0x5a5a5a5a);
            uint32_t* values = count == 0 ? NULL : allocation + 1;
            for (uint32_t index = 0; index < guest; ++index)
                values[index] = UINT32_C(0x11);
            if (values) {
                for (uint32_t index = guest; index < server; ++index)
                    values[index] = UINT32_C(0x22);
            }
            assert(allocation[0] == UINT32_C(0xa5a5a5a5));
            assert(allocation[(size_t)count + 1u] == UINT32_C(0x5a5a5a5a));
            free(allocation);
        }
    }
}

static void test_transport_frame_bounds(void) {
    const uint32_t capacity = 256u;
    assert(vt_transport_payload_fits(0, capacity, 248u));
    assert(vt_transport_payload_fits(248, capacity, 248u));
    assert(!vt_transport_payload_fits(249, capacity, 248u));
    assert(!vt_transport_payload_fits(-1, capacity, 248u));
    assert(!vt_transport_payload_fits(0, 7u, 248u));
    assert(vt_transport_size_fits(248u, capacity, 248u));
    assert(!vt_transport_size_fits(249u, capacity, 248u));
    assert(!vt_transport_size_fits((size_t)INT32_MAX + 1u,
            UINT32_MAX, SIZE_MAX));

    /* 65536 descriptor tokens require 524288 bytes and cannot be published
     * through the 262144-byte server-to-client ring. */
    const size_t descriptorBytes = UINT64_C(65536) * sizeof(uint64_t);
    assert(!vt_transport_size_fits(
            descriptorBytes, UINT32_C(262144), UINT32_C(262136)));
}

static void test_malformed_last_chunk_executes_nothing(void) {
    uint8_t batch[8 + 9 + 8] = {0};
    size_t position = 8;
    put_i32(batch + position, 200);
    put_i32(batch + position + 4, 1);
    batch[position + 8] = 0x5a;
    position += 9;
    put_i32(batch + position, 201);
    put_i32(batch + position + 4, 4); /* Claims four absent bytes. */

    preflight_calls = 0;
    execution_calls = 0;
    assert(!preflight_then_execute(batch, sizeof(batch)));
    assert(preflight_calls == 1);
    assert(execution_calls == 0);
}

static void test_negative_and_truncated_frames(void) {
    uint8_t negative[16] = {0};
    put_i32(negative + 8, 200);
    put_i32(negative + 12, -1);
    assert(!preflight_then_execute(negative, sizeof(negative)));

    uint8_t short_header[12] = {0};
    assert(!preflight_then_execute(short_header, sizeof(short_header)));
}

int main(void) {
    test_capacities();
    test_transport_frame_bounds();
    test_malformed_last_chunk_executes_nothing();
    test_negative_and_truncated_frames();
    puts("request_handler_contract_test: PASS");
    return 0;
}
