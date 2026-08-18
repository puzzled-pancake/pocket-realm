#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "vortek_serializer.h"

#include "vortek_decoder_fuzz_dispatch.inc"

/* The fuzzer deliberately uses a permissive fake authority so mutations can
 * reach structure decoding beyond handle fields.  Authority rejection paths
 * are tested with the strict mock in vortek_decode_test.c. */
static bool fuzz_resolve_handle(
        void* userdata,
        const VtDecodeHandleRequest* request,
        uint64_t* host_value) {
    (void)userdata;
    if (!request || !host_value || request->context_generation != 1 ||
            request->instance_owner != 1 || request->object_type == 0) {
        return false;
    }
    if ((request->owner_requirements & VT_DECODE_OWNER_DEVICE) != 0 &&
            request->device_owner != 2) {
        return false;
    }
    if (request->wire_token == 0) {
        if (request->nullability == VT_DECODE_NULL_NEVER) return false;
        *host_value = 0;
        return true;
    }
    if (request->role == VT_DECODE_HANDLE_ROLE_WINDOW_ID) {
        if (request->wire_token > UINT32_MAX) return false;
        *host_value = request->wire_token;
        return true;
    }
    if (request->role != VT_DECODE_HANDLE_ROLE_VULKAN &&
            request->role != VT_DECODE_HANDLE_ROLE_RESOURCE_MEMORY_DEVICE_MEMORY) {
        return false;
    }
    *host_value = request->wire_token ^ UINT64_C(0x8000000000000000);
    return true;
}

static void fuzz_pool_cleanup(MemoryPool* pool) {
    if (!pool) return;
    for (int i = 0; i < pool->allocationList.size; i++) {
        free(pool->allocationList.elements[i]);
    }
    free(pool->allocationList.elements);
    pool->allocationList.elements = NULL;
    pool->allocationList.size = 0;
    pool->allocationList.capacity = 0;
}

int LLVMFuzzerTestOneInput(const uint8_t* data, size_t size) {
    if (!data || size < 2) return 0;
    uint32_t decoder_index =
            ((uint32_t)data[0] | ((uint32_t)data[1] << 8)) %
            VT_FUZZ_DECODER_COUNT;
    data += 2;
    size -= 2;

    /* vt_decode_cursor_init rejects oversize input before pointer arithmetic. */
    if (size > VT_DECODE_MAX_REQUEST_BYTES) return 0;
    max_align_t inline_pool[(65536u + sizeof(max_align_t) - 1u) /
            sizeof(max_align_t)];
    MemoryPool pool = {
        .data = inline_pool,
        .size = 0,
        .allocationList = {0},
    };
    VtDecodeState state;
    VtDecodeCursor cursor;
    if (!vt_decode_cursor_init(&cursor, &state, data, size, &pool)) return 0;
    vt_decode_set_handle_resolver(&state, fuzz_resolve_handle, NULL, 1);
    vt_decode_set_handle_scope(&state, 1, 2);

    if (vt_fuzz_dispatch_decoder(decoder_index, &cursor, &pool)) {
        (void)vt_decode_finished(&cursor);
    }
    fuzz_pool_cleanup(&pool);
    return 0;
}

#ifdef VT_FUZZ_STANDALONE
int main(void) {
    static const uint8_t boundary_payloads[][18] = {
        {0},
        {0, 0, 0xff, 0xff, 0xff, 0xff},
        {0, 0, 1, 0, 1, 0}, /* VT_DECODE_MAX_ELEMENTS + 1 */
        {0, 0, 0xff, 0xff, 0xff, 0x7f},
        {0, 0, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff},
    };
    for (uint32_t decoder = 0; decoder < VT_FUZZ_DECODER_COUNT; decoder++) {
        for (size_t seed = 0; seed < sizeof(boundary_payloads) /
                sizeof(boundary_payloads[0]); seed++) {
            uint8_t input[sizeof(boundary_payloads[0])] = {0};
            memcpy(input, boundary_payloads[seed], sizeof(input));
            input[0] = (uint8_t)(decoder & 0xffu);
            input[1] = (uint8_t)(decoder >> 8);
            LLVMFuzzerTestOneInput(input, sizeof(input));
            for (size_t byte = 2; byte < sizeof(input); byte++) {
                input[byte] ^= (uint8_t)(0xa5u + byte);
                LLVMFuzzerTestOneInput(input, sizeof(input));
            }
        }
    }
    return 0;
}
#endif
