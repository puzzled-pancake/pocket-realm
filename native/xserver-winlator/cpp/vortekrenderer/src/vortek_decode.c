#include "vortek_decode.h"

#include <limits.h>
#include <stdalign.h>
#include <stdlib.h>
#include <string.h>

/* Keep the decoder runtime host-testable without Android/Vulkan headers.  This
 * private layout exactly mirrors Vortek's MemoryPool and ArrayList fields; the
 * public API intentionally accepts void* so the generated serializer remains
 * the only layer coupled to those upstream types. */
#define VT_DECODE_INLINE_POOL_BYTES 65536u
typedef struct VtDecodeArrayListView {
    int size;
    int capacity;
    void** elements;
} VtDecodeArrayListView;

typedef struct VtDecodeMemoryPoolView {
    void* data;
    int size;
    VtDecodeArrayListView allocation_list;
} VtDecodeMemoryPoolView;

typedef union VtDecodeMaxAlign {
    long double floating;
    void* pointer;
    uint64_t integer;
} VtDecodeMaxAlign;

static bool range_at(
        VtDecodeCursor* cursor,
        size_t offset,
        size_t size,
        const uint8_t** result) {
    if (!cursor || !cursor->state || cursor->state->error != VT_DECODE_ERROR_NONE) {
        return false;
    }
    size_t available = (size_t)(cursor->end - cursor->base);
    if (offset > available || size > available - offset) {
        return vt_decode_fail(cursor, VT_DECODE_ERROR_TRUNCATED);
    }
    if (result) *result = cursor->base + offset;
    return true;
}

bool vt_decode_fail(VtDecodeCursor* cursor, VtDecodeError error) {
    if (cursor && cursor->state && cursor->state->error == VT_DECODE_ERROR_NONE) {
        cursor->state->error = error;
    }
    return false;
}

bool vt_decode_cursor_init(
        VtDecodeCursor* cursor,
        VtDecodeState* state,
        const void* data,
        size_t size,
        void* memory_pool) {
    if (!cursor || !state || (!data && size != 0)) return false;
    memset(state, 0, sizeof(*state));
    memset(cursor, 0, sizeof(*cursor));
    if (size > VT_DECODE_MAX_REQUEST_BYTES) {
        state->error = VT_DECODE_ERROR_LIMIT;
        cursor->state = state;
        return false;
    }
    state->request_base = data ? data : (const uint8_t*)state;
    state->request_end = state->request_base + size;
    state->memory_pool = memory_pool;
    state->fail_allocation_after = SIZE_MAX;
    cursor->base = state->request_base;
    cursor->ptr = state->request_base;
    cursor->end = state->request_end;
    cursor->request_end = state->request_end;
    cursor->state = state;
    cursor->expected_pnext_type = -1;
    return true;
}

bool vt_decode_child_at(
        VtDecodeCursor* parent,
        size_t offset,
        size_t size,
        VtDecodeCursor* child) {
    const uint8_t* start = NULL;
    if (!child || !range_at(parent, offset, size, &start)) return false;
    if (parent->depth >= VT_DECODE_MAX_DEPTH) {
        return vt_decode_fail(parent, VT_DECODE_ERROR_DEPTH);
    }
    if (parent->state->nodes >= VT_DECODE_MAX_NODES) {
        return vt_decode_fail(parent, VT_DECODE_ERROR_LIMIT);
    }
    parent->state->nodes++;
    memset(child, 0, sizeof(*child));
    child->base = start;
    child->ptr = start;
    child->end = start + size;
    child->request_end = parent->request_end;
    child->state = parent->state;
    child->depth = parent->depth + 1;
    child->expected_pnext_type = -1;

    if (parent->expected_pnext_type >= 0) {
        int32_t decoded_type = -1;
        parent->expected_pnext_type = -1;
        if (!vt_decode_i32_at(child, 0, &decoded_type)) return false;
        if (decoded_type < 0 || decoded_type != parent->pnext_types[parent->pnext_count - 1]) {
            return vt_decode_fail(parent, VT_DECODE_ERROR_PNEXT_TYPE);
        }
    }
    return true;
}

bool vt_decode_finished(VtDecodeCursor* cursor) {
    if (!vt_decode_ok(cursor)) return false;
    if (cursor->ptr != cursor->end) {
        return vt_decode_fail(cursor, VT_DECODE_ERROR_TRAILING_BYTES);
    }
    if (cursor->expected_pnext_type >= 0) {
        return vt_decode_fail(cursor, VT_DECODE_ERROR_PNEXT_TYPE);
    }
    return true;
}

bool vt_decode_ok(const VtDecodeCursor* cursor) {
    return cursor && cursor->state && cursor->state->error == VT_DECODE_ERROR_NONE;
}

size_t vt_decode_remaining(const VtDecodeCursor* cursor) {
    if (!cursor || !cursor->ptr || !cursor->end || cursor->ptr > cursor->end) return 0;
    return (size_t)(cursor->end - cursor->ptr);
}

VtDecodeError vt_decode_error(const VtDecodeCursor* cursor) {
    return cursor && cursor->state ? cursor->state->error : VT_DECODE_ERROR_ARGUMENT;
}

void vt_decode_fail_allocation_after(
        VtDecodeState* state,
        size_t successful_allocations) {
    if (state) state->fail_allocation_after = successful_allocations;
}

void vt_decode_set_handle_resolver(
        VtDecodeState* state,
        VtDecodeHandleResolver resolver,
        void* userdata,
        uint64_t context_generation) {
    if (!state) return;
    state->handle_resolver = resolver;
    state->handle_resolver_userdata = userdata;
    state->context_generation = context_generation;
}

void vt_decode_set_handle_scope(
        VtDecodeState* state,
        uint64_t instance_owner,
        uint64_t device_owner) {
    if (!state) return;
    state->instance_owner = instance_owner;
    state->device_owner = device_owner;
}

void vt_decode_set_null_descriptor_enabled(
        VtDecodeState* state, bool enabled) {
    if (state) state->null_descriptor_enabled = enabled;
}

void vt_decode_capture_handle_tokens(
        VtDecodeState* state,
        uint32_t object_type,
        uint64_t* tokens,
        size_t capacity) {
    if (!state) return;
    state->capture_object_type = object_type;
    state->captured_handle_tokens = tokens;
    state->captured_handle_capacity = capacity;
    state->captured_handle_count = 0;
}

size_t vt_decode_captured_handle_count(const VtDecodeState* state) {
    return state ? state->captured_handle_count : 0;
}

bool vt_decode_resolve_handle(
        VtDecodeCursor* cursor,
        uint64_t wire_token,
        uint32_t object_type,
        VtDecodeHandleRole role,
        uint32_t owner_requirements,
        VtDecodeNullability nullability,
        uint64_t* host_value) {
    if (!cursor || !cursor->state || !host_value) {
        return vt_decode_fail(cursor, VT_DECODE_ERROR_ARGUMENT);
    }
    VtDecodeState* state = cursor->state;
    *host_value = 0;
    if (!state->handle_resolver) {
        return vt_decode_fail(cursor, VT_DECODE_ERROR_HANDLE_RESOLVER);
    }
    if ((owner_requirements & ~(uint32_t)(
            VT_DECODE_OWNER_INSTANCE | VT_DECODE_OWNER_DEVICE)) != 0) {
        return vt_decode_fail(cursor, VT_DECODE_ERROR_ARGUMENT);
    }
    /* The first authoritative handle may establish the root owner scope.  The
     * resolver must still validate its generation/type/liveness and derive
     * owner tokens from the registry before any later handle is accepted. */
    if (nullability != VT_DECODE_NULL_NEVER &&
            nullability != VT_DECODE_NULL_VULKAN &&
            nullability != VT_DECODE_NULL_DESCRIPTOR_FEATURE) {
        return vt_decode_fail(cursor, VT_DECODE_ERROR_ARGUMENT);
    }
    if (wire_token == 0 && (nullability == VT_DECODE_NULL_NEVER ||
            (nullability == VT_DECODE_NULL_DESCRIPTOR_FEATURE &&
             !state->null_descriptor_enabled))) {
        return vt_decode_fail(cursor, VT_DECODE_ERROR_HANDLE_REJECTED);
    }

    VtDecodeHandleRequest request = {
        .wire_token = wire_token,
        .context_generation = state->context_generation,
        .instance_owner = state->instance_owner,
        .device_owner = state->device_owner,
        .object_type = object_type,
        .owner_requirements = owner_requirements,
        .role = role,
        .nullability = nullability,
    };
    uint64_t resolved = 0;
    if (!state->handle_resolver(
            state->handle_resolver_userdata, &request, &resolved) ||
            (wire_token == 0) != (resolved == 0)) {
        return vt_decode_fail(cursor, VT_DECODE_ERROR_HANDLE_REJECTED);
    }
    if (state->captured_handle_tokens &&
            state->capture_object_type == object_type) {
        if (state->captured_handle_count >= state->captured_handle_capacity) {
            return vt_decode_fail(cursor, VT_DECODE_ERROR_LIMIT);
        }
        state->captured_handle_tokens[state->captured_handle_count++] = wire_token;
    }
    *host_value = resolved;
    return true;
}

bool vt_decode_read_at(
        VtDecodeCursor* cursor,
        size_t offset,
        void* output,
        size_t size) {
    const uint8_t* source = NULL;
    if (!output && size != 0) return vt_decode_fail(cursor, VT_DECODE_ERROR_ARGUMENT);
    if (!range_at(cursor, offset, size, &source)) return false;
    if (size != 0) memcpy(output, source, size);
    return true;
}

bool vt_decode_copy_at(
        VtDecodeCursor* cursor,
        size_t offset,
        void* output,
        size_t size) {
    return vt_decode_read_at(cursor, offset, output, size);
}

bool vt_decode_add_size(size_t left, size_t right, size_t* result) {
    if (!result || right > SIZE_MAX - left) return false;
    *result = left + right;
    return true;
}

bool vt_decode_multiply_size(size_t count, size_t element_size, size_t* result) {
    if (!result || (element_size != 0 && count > SIZE_MAX / element_size)) return false;
    *result = count * element_size;
    return true;
}

bool vt_decode_advance(VtDecodeCursor* cursor, size_t* offset, size_t amount) {
    size_t next = 0;
    if (!offset || !vt_decode_add_size(*offset, amount, &next)) {
        return vt_decode_fail(cursor, VT_DECODE_ERROR_INTEGER_OVERFLOW);
    }
    if (!range_at(cursor, *offset, amount, NULL)) return false;
    *offset = next;
    cursor->ptr = cursor->base + next;
    return true;
}

bool vt_decode_advance_array(
        VtDecodeCursor* cursor,
        size_t* offset,
        size_t count,
        size_t element_size) {
    size_t amount = 0;
    if (!vt_decode_multiply_size(count, element_size, &amount)) {
        return vt_decode_fail(cursor, VT_DECODE_ERROR_INTEGER_OVERFLOW);
    }
    return vt_decode_advance(cursor, offset, amount);
}

bool vt_decode_presence_at(VtDecodeCursor* cursor, size_t* offset, bool* present) {
    uint8_t value = 0;
    if (!offset || !present || !vt_decode_read_at(cursor, *offset, &value, sizeof(value)) ||
            !vt_decode_advance(cursor, offset, sizeof(value))) {
        return false;
    }
    if (value > 1) return vt_decode_fail(cursor, VT_DECODE_ERROR_INVALID_BOOLEAN);
    *present = value != 0;
    return true;
}

bool vt_decode_i32_at(VtDecodeCursor* cursor, size_t offset, int32_t* value) {
    return vt_decode_read_at(cursor, offset, value, sizeof(*value));
}

bool vt_decode_data_size_at(
        VtDecodeCursor* cursor,
        size_t offset,
        int32_t* value) {
    if (!value || !vt_decode_i32_at(cursor, offset, value)) return false;
    if (*value < 0) return vt_decode_fail(cursor, VT_DECODE_ERROR_NEGATIVE_LENGTH);
    size_t available = (size_t)(cursor->end - cursor->base);
    if (offset > available || sizeof(*value) > available - offset ||
            (size_t)*value > available - offset - sizeof(*value)) {
        return vt_decode_fail(cursor, VT_DECODE_ERROR_TRUNCATED);
    }
    return true;
}

bool vt_decode_length_i32_at(
        VtDecodeCursor* cursor,
        size_t offset,
        int32_t* value,
        size_t minimum_element_size) {
    if (!value || !vt_decode_i32_at(cursor, offset, value)) return false;
    if (*value < 0) return vt_decode_fail(cursor, VT_DECODE_ERROR_NEGATIVE_LENGTH);
    if ((uint32_t)*value > VT_DECODE_MAX_ELEMENTS) {
        return vt_decode_fail(cursor, VT_DECODE_ERROR_LIMIT);
    }
    if (minimum_element_size != 0) {
        size_t bytes = 0;
        if (!vt_decode_multiply_size((size_t)*value, minimum_element_size, &bytes)) {
            return vt_decode_fail(cursor, VT_DECODE_ERROR_INTEGER_OVERFLOW);
        }
        size_t available = (size_t)(cursor->end - cursor->base);
        if (offset > available || sizeof(*value) > available - offset ||
                bytes > available - offset - sizeof(*value)) {
            return vt_decode_fail(cursor, VT_DECODE_ERROR_TRUNCATED);
        }
    }
    return true;
}

bool vt_decode_string_at(
        VtDecodeCursor* cursor,
        size_t offset,
        size_t size,
        char* output) {
    if (size == 0 || !vt_decode_copy_at(cursor, offset, output, size)) {
        return vt_decode_fail(cursor, VT_DECODE_ERROR_INVALID_STRING);
    }
    if (output[size - 1] != '\0' || memchr(output, '\0', size - 1) != NULL) {
        return vt_decode_fail(cursor, VT_DECODE_ERROR_INVALID_STRING);
    }
    return true;
}

bool vt_decode_validate_count(VtDecodeCursor* cursor, uint64_t count) {
    if (count > VT_DECODE_MAX_ELEMENTS) {
        return vt_decode_fail(cursor, VT_DECODE_ERROR_LIMIT);
    }
    return true;
}

bool vt_decode_note_elements(VtDecodeCursor* cursor, size_t count) {
    if (!vt_decode_validate_count(cursor, count) || !cursor || !cursor->state ||
            count > VT_DECODE_MAX_NODES - cursor->state->nodes) {
        return vt_decode_fail(cursor, VT_DECODE_ERROR_LIMIT);
    }
    cursor->state->nodes += (uint32_t)count;
    return true;
}

static bool pool_track_allocation(VtDecodeMemoryPoolView* pool, void* allocation) {
    if (!pool || !allocation) return false;
    VtDecodeArrayListView* list = &pool->allocation_list;
    if (list->size < 0 || list->capacity < 0 || list->size > list->capacity) return false;
    if (list->size == list->capacity) {
        size_t old_capacity = (size_t)list->capacity;
        size_t new_capacity = old_capacity < 4 ? 4 : old_capacity + old_capacity / 2;
        if (new_capacity <= old_capacity || new_capacity > (size_t)INT_MAX ||
                new_capacity > SIZE_MAX / sizeof(*list->elements)) {
            return false;
        }
        void** elements = realloc(list->elements, new_capacity * sizeof(*elements));
        if (!elements) return false;
        memset(elements + old_capacity, 0,
                (new_capacity - old_capacity) * sizeof(*elements));
        list->elements = elements;
        list->capacity = (int)new_capacity;
    }
    list->elements[list->size++] = allocation;
    return true;
}

void* vt_decode_alloc(
        VtDecodeCursor* cursor,
        void* memory_pool,
        size_t count,
        size_t element_size) {
    size_t bytes = 0;
    if (!cursor || !cursor->state || !memory_pool ||
            !vt_decode_validate_count(cursor, count)) {
        vt_decode_fail(cursor, VT_DECODE_ERROR_LIMIT);
        return NULL;
    }
    if (!vt_decode_multiply_size(count, element_size, &bytes)) {
        vt_decode_fail(cursor, VT_DECODE_ERROR_INTEGER_OVERFLOW);
        return NULL;
    }
    if (count > vt_decode_remaining(cursor)) {
        vt_decode_fail(cursor, VT_DECODE_ERROR_TRUNCATED);
        return NULL;
    }
    if (cursor->state->allocation_attempts++ >= cursor->state->fail_allocation_after) {
        vt_decode_fail(cursor, VT_DECODE_ERROR_OUT_OF_MEMORY);
        return NULL;
    }
    if (bytes == 0 || cursor->state->allocation_bytes > VT_DECODE_MAX_ALLOCATION_BYTES ||
            bytes > VT_DECODE_MAX_ALLOCATION_BYTES - cursor->state->allocation_bytes) {
        vt_decode_fail(cursor, VT_DECODE_ERROR_LIMIT);
        return NULL;
    }

    VtDecodeMemoryPoolView* pool = memory_pool;
    const size_t alignment = alignof(VtDecodeMaxAlign);
    size_t aligned_offset = 0;
    if (pool->size >= 0) {
        size_t raw_offset = (size_t)pool->size;
        size_t padding = (alignment - raw_offset % alignment) % alignment;
        if (vt_decode_add_size(raw_offset, padding, &aligned_offset) &&
                aligned_offset <= VT_DECODE_INLINE_POOL_BYTES &&
                bytes <= VT_DECODE_INLINE_POOL_BYTES - aligned_offset && pool->data) {
            void* result = (uint8_t*)pool->data + aligned_offset;
            memset(result, 0, bytes);
            pool->size = (int)(aligned_offset + bytes);
            cursor->state->allocation_bytes += bytes;
            return result;
        }
    }

    void* result = calloc(1, bytes);
    if (!result) {
        vt_decode_fail(cursor, VT_DECODE_ERROR_OUT_OF_MEMORY);
        return NULL;
    }
    if (!pool_track_allocation(pool, result)) {
        free(result);
        vt_decode_fail(cursor, VT_DECODE_ERROR_OUT_OF_MEMORY);
        return NULL;
    }
    cursor->state->allocation_bytes += bytes;
    return result;
}

void* vt_decode_alloc_bytes(
        VtDecodeCursor* cursor,
        void* memory_pool,
        size_t bytes) {
    if (!cursor || !cursor->state || !memory_pool || bytes == 0 ||
            cursor->state->allocation_bytes > VT_DECODE_MAX_ALLOCATION_BYTES ||
            bytes > VT_DECODE_MAX_ALLOCATION_BYTES - cursor->state->allocation_bytes) {
        vt_decode_fail(cursor, VT_DECODE_ERROR_LIMIT);
        return NULL;
    }
    if (bytes > vt_decode_remaining(cursor)) {
        vt_decode_fail(cursor, VT_DECODE_ERROR_TRUNCATED);
        return NULL;
    }
    if (cursor->state->allocation_attempts++ >= cursor->state->fail_allocation_after) {
        vt_decode_fail(cursor, VT_DECODE_ERROR_OUT_OF_MEMORY);
        return NULL;
    }

    VtDecodeMemoryPoolView* pool = memory_pool;
    const size_t alignment = alignof(VtDecodeMaxAlign);
    size_t aligned_offset = 0;
    if (pool->size >= 0) {
        size_t raw_offset = (size_t)pool->size;
        size_t padding = (alignment - raw_offset % alignment) % alignment;
        if (vt_decode_add_size(raw_offset, padding, &aligned_offset) &&
                aligned_offset <= VT_DECODE_INLINE_POOL_BYTES &&
                bytes <= VT_DECODE_INLINE_POOL_BYTES - aligned_offset && pool->data) {
            void* result = (uint8_t*)pool->data + aligned_offset;
            memset(result, 0, bytes);
            pool->size = (int)(aligned_offset + bytes);
            cursor->state->allocation_bytes += bytes;
            return result;
        }
    }

    void* result = calloc(1, bytes);
    if (!result) {
        vt_decode_fail(cursor, VT_DECODE_ERROR_OUT_OF_MEMORY);
        return NULL;
    }
    if (!pool_track_allocation(pool, result)) {
        free(result);
        vt_decode_fail(cursor, VT_DECODE_ERROR_OUT_OF_MEMORY);
        return NULL;
    }
    cursor->state->allocation_bytes += bytes;
    return result;
}

static bool record_pnext(VtDecodeCursor* cursor, int32_t structure_type) {
    if (structure_type < 0) return vt_decode_fail(cursor, VT_DECODE_ERROR_PNEXT_TYPE);
    for (uint32_t i = 0; i < cursor->pnext_count; i++) {
        if (cursor->pnext_types[i] == structure_type) {
            return vt_decode_fail(cursor, VT_DECODE_ERROR_PNEXT_DUPLICATE);
        }
    }
    if (cursor->pnext_count >= VT_DECODE_MAX_PNEXT_NODES) {
        return vt_decode_fail(cursor, VT_DECODE_ERROR_LIMIT);
    }
    cursor->pnext_types[cursor->pnext_count++] = structure_type;
    return true;
}

bool vt_decode_pnext_known(
        VtDecodeCursor* cursor,
        int32_t structure_type,
        int32_t item_size) {
    if (item_size <= 0) return vt_decode_fail(cursor, VT_DECODE_ERROR_PNEXT_TYPE);
    if (!record_pnext(cursor, structure_type)) return false;
    cursor->expected_pnext_type = structure_type;
    return true;
}

bool vt_decode_pnext_unknown(
        VtDecodeCursor* cursor,
        int32_t structure_type,
        int32_t item_size) {
    if (item_size < 0) return vt_decode_fail(cursor, VT_DECODE_ERROR_NEGATIVE_LENGTH);
    if (!record_pnext(cursor, structure_type)) return false;
    if (item_size != 0) return vt_decode_fail(cursor, VT_DECODE_ERROR_PNEXT_UNKNOWN);
    return true;
}

bool vt_decode_pnext_terminated(VtDecodeCursor* cursor, int32_t structure_type) {
    if (structure_type != -1) {
        return vt_decode_fail(cursor, VT_DECODE_ERROR_PNEXT_TERMINATOR);
    }
    return true;
}
