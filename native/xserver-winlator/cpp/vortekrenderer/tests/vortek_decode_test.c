#include "vortek_decode.h"

#include <limits.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

typedef struct TestList {
    int size;
    int capacity;
    void** elements;
} TestList;

typedef struct TestPool {
    void* data;
    int size;
    TestList allocation_list;
} TestPool;

static int failures;

#define EXPECT(condition) \
    do { \
        if (!(condition)) { \
            fprintf(stderr, "%s:%d: expectation failed: %s\n", \
                    __FILE__, __LINE__, #condition); \
            failures++; \
        } \
    } while (0)

static void reset_pool(TestPool* pool) {
    for (int i = 0; i < pool->allocation_list.size; i++) {
        free(pool->allocation_list.elements[i]);
    }
    free(pool->allocation_list.elements);
    free(pool->data);
    memset(pool, 0, sizeof(*pool));
}

static void init_pool(TestPool* pool) {
    memset(pool, 0, sizeof(*pool));
    pool->data = calloc(65536, 1);
    EXPECT(pool->data != NULL);
}

static void test_cursor_bounds(void) {
    const uint8_t bytes[] = {1, 2, 3, 4};
    VtDecodeState state;
    VtDecodeCursor cursor;
    uint32_t value = 0;
    size_t offset = 0;

    EXPECT(vt_decode_cursor_init(&cursor, &state, bytes, sizeof(bytes), NULL));
    EXPECT(vt_decode_read_at(&cursor, 0, &value, sizeof(value)));
    EXPECT(vt_decode_advance(&cursor, &offset, sizeof(value)));
    EXPECT(vt_decode_finished(&cursor));

    EXPECT(vt_decode_cursor_init(&cursor, &state, bytes, sizeof(bytes) - 1, NULL));
    EXPECT(!vt_decode_read_at(&cursor, 0, &value, sizeof(value)));
    EXPECT(vt_decode_error(&cursor) == VT_DECODE_ERROR_TRUNCATED);

    EXPECT(vt_decode_cursor_init(&cursor, &state, bytes, sizeof(bytes), NULL));
    EXPECT(!vt_decode_finished(&cursor));
    EXPECT(vt_decode_error(&cursor) == VT_DECODE_ERROR_TRAILING_BYTES);

    EXPECT(!vt_decode_cursor_init(
            &cursor, &state, bytes, (size_t)VT_DECODE_MAX_REQUEST_BYTES + 1, NULL));
    EXPECT(state.error == VT_DECODE_ERROR_LIMIT);
    EXPECT(!vt_decode_add_size(SIZE_MAX, 1, &offset));
    EXPECT(!vt_decode_multiply_size(SIZE_MAX, 2, &offset));
}

static void test_presence_and_lengths(void) {
    const uint8_t invalid_presence[] = {2};
    const uint8_t negative[] = {0xff, 0xff, 0xff, 0xff};
    const uint8_t truncated[] = {4, 0, 0, 0, 1, 2};
    const uint8_t max_plus_one[] = {1, 0, 1, 0};
    VtDecodeState state;
    VtDecodeCursor cursor;
    size_t offset = 0;
    bool present = false;
    int32_t length = 0;

    EXPECT(vt_decode_cursor_init(&cursor, &state, invalid_presence,
            sizeof(invalid_presence), NULL));
    EXPECT(!vt_decode_presence_at(&cursor, &offset, &present));
    EXPECT(vt_decode_error(&cursor) == VT_DECODE_ERROR_INVALID_BOOLEAN);

    EXPECT(vt_decode_cursor_init(&cursor, &state, negative, sizeof(negative), NULL));
    EXPECT(!vt_decode_data_size_at(&cursor, 0, &length));
    EXPECT(vt_decode_error(&cursor) == VT_DECODE_ERROR_NEGATIVE_LENGTH);

    EXPECT(vt_decode_cursor_init(&cursor, &state, truncated, sizeof(truncated), NULL));
    EXPECT(!vt_decode_data_size_at(&cursor, 0, &length));
    EXPECT(vt_decode_error(&cursor) == VT_DECODE_ERROR_TRUNCATED);

    EXPECT(vt_decode_cursor_init(&cursor, &state, max_plus_one,
            sizeof(max_plus_one), NULL));
    EXPECT(!vt_decode_length_i32_at(&cursor, 0, &length, 1));
    EXPECT(vt_decode_error(&cursor) == VT_DECODE_ERROR_LIMIT);
}

static void test_children(void) {
    const uint8_t bytes[] = {1, 2, 3, 4, 5};
    VtDecodeState state;
    VtDecodeCursor parent;
    VtDecodeCursor child;
    size_t offset = 0;

    EXPECT(vt_decode_cursor_init(&parent, &state, bytes, sizeof(bytes), NULL));
    EXPECT(vt_decode_child_at(&parent, 1, 3, &child));
    EXPECT(vt_decode_advance(&child, &offset, 3));
    EXPECT(vt_decode_finished(&child));

    EXPECT(vt_decode_cursor_init(&parent, &state, bytes, sizeof(bytes), NULL));
    EXPECT(!vt_decode_child_at(&parent, 3, 3, &child));
    EXPECT(vt_decode_error(&parent) == VT_DECODE_ERROR_TRUNCATED);

    EXPECT(vt_decode_cursor_init(&parent, &state, bytes, sizeof(bytes), NULL));
    VtDecodeCursor chain[VT_DECODE_MAX_DEPTH + 1];
    chain[0] = parent;
    for (uint32_t i = 0; i < VT_DECODE_MAX_DEPTH; i++) {
        EXPECT(vt_decode_child_at(&chain[i], 0, 1, &chain[i + 1]));
    }
    EXPECT(!vt_decode_child_at(&chain[VT_DECODE_MAX_DEPTH], 0, 1, &child));
    EXPECT(state.error == VT_DECODE_ERROR_DEPTH);
}

static void test_strings(void) {
    const char valid[] = "main";
    const char missing_nul[] = {'m', 'a', 'i', 'n'};
    const char embedded_nul[] = {'m', '\0', 'x', '\0'};
    char output[sizeof(valid)] = {0};
    VtDecodeState state;
    VtDecodeCursor cursor;

    EXPECT(vt_decode_cursor_init(&cursor, &state, valid, sizeof(valid), NULL));
    EXPECT(vt_decode_string_at(&cursor, 0, sizeof(valid), output));

    EXPECT(vt_decode_cursor_init(&cursor, &state, missing_nul,
            sizeof(missing_nul), NULL));
    EXPECT(!vt_decode_string_at(&cursor, 0, sizeof(missing_nul), output));
    EXPECT(vt_decode_error(&cursor) == VT_DECODE_ERROR_INVALID_STRING);

    EXPECT(vt_decode_cursor_init(&cursor, &state, embedded_nul,
            sizeof(embedded_nul), NULL));
    EXPECT(!vt_decode_string_at(&cursor, 0, sizeof(embedded_nul), output));
    EXPECT(vt_decode_error(&cursor) == VT_DECODE_ERROR_INVALID_STRING);
}

static void test_allocations(void) {
    uint8_t bytes[8] = {0};
    VtDecodeState state;
    VtDecodeCursor cursor;
    TestPool pool;
    init_pool(&pool);

    EXPECT(vt_decode_cursor_init(&cursor, &state, bytes, sizeof(bytes), &pool));
    void* first = vt_decode_alloc(&cursor, &pool, 1, 1);
    void* aligned = vt_decode_alloc(&cursor, &pool, 1, sizeof(uint64_t));
    EXPECT(first != NULL);
    EXPECT(aligned != NULL);
    EXPECT(((uintptr_t)aligned % _Alignof(void*)) == 0);

    EXPECT(vt_decode_cursor_init(&cursor, &state, bytes, sizeof(bytes), &pool));
    EXPECT(vt_decode_alloc(&cursor, &pool, VT_DECODE_MAX_ELEMENTS + 1u, 1) == NULL);
    EXPECT(vt_decode_error(&cursor) == VT_DECODE_ERROR_LIMIT);

    EXPECT(vt_decode_cursor_init(&cursor, &state, bytes, sizeof(bytes), &pool));
    vt_decode_fail_allocation_after(&state, 0);
    EXPECT(vt_decode_alloc_bytes(&cursor, &pool, 8) == NULL);
    EXPECT(vt_decode_error(&cursor) == VT_DECODE_ERROR_OUT_OF_MEMORY);
    reset_pool(&pool);
}

static void test_pnext(void) {
    const int32_t matching_type = 42;
    VtDecodeState state;
    VtDecodeCursor parent;
    VtDecodeCursor child;

    EXPECT(vt_decode_cursor_init(&parent, &state, &matching_type,
            sizeof(matching_type), NULL));
    EXPECT(vt_decode_pnext_known(&parent, matching_type, sizeof(matching_type)));
    EXPECT(vt_decode_child_at(&parent, 0, sizeof(matching_type), &child));

    EXPECT(vt_decode_cursor_init(&parent, &state, &matching_type,
            sizeof(matching_type), NULL));
    EXPECT(vt_decode_pnext_known(&parent, matching_type, sizeof(matching_type)));
    EXPECT(!vt_decode_pnext_known(&parent, matching_type, sizeof(matching_type)));
    EXPECT(vt_decode_error(&parent) == VT_DECODE_ERROR_PNEXT_DUPLICATE);

    EXPECT(vt_decode_cursor_init(&parent, &state, &matching_type,
            sizeof(matching_type), NULL));
    EXPECT(vt_decode_pnext_unknown(&parent, matching_type, 0));
    EXPECT(vt_decode_pnext_terminated(&parent, -1));

    EXPECT(vt_decode_cursor_init(&parent, &state, &matching_type,
            sizeof(matching_type), NULL));
    EXPECT(!vt_decode_pnext_unknown(&parent, matching_type, 4));
    EXPECT(vt_decode_error(&parent) == VT_DECODE_ERROR_PNEXT_UNKNOWN);

    EXPECT(vt_decode_cursor_init(&parent, &state, &matching_type,
            sizeof(matching_type), NULL));
    EXPECT(!vt_decode_pnext_terminated(&parent, 0));
    EXPECT(vt_decode_error(&parent) == VT_DECODE_ERROR_PNEXT_TERMINATOR);
}

typedef struct MockAuthority {
    uint64_t token;
    uint64_t host_value;
    uint64_t generation;
    uint64_t instance_owner;
    uint64_t device_owner;
    uint32_t object_type;
    uint32_t owner_requirements;
    VtDecodeHandleRole role;
    bool live;
    unsigned calls;
} MockAuthority;

static bool mock_resolve_handle(
        void* userdata,
        const VtDecodeHandleRequest* request,
        uint64_t* host_value) {
    MockAuthority* authority = userdata;
    authority->calls++;
    if (request->wire_token == 0 &&
            request->nullability != VT_DECODE_NULL_NEVER) {
        *host_value = 0;
        return true;
    }
    if (!authority->live || request->wire_token != authority->token ||
            request->context_generation != authority->generation ||
            request->instance_owner != authority->instance_owner ||
            request->device_owner != authority->device_owner ||
            request->object_type != authority->object_type ||
            request->owner_requirements != authority->owner_requirements ||
            request->role != authority->role) {
        return false;
    }
    *host_value = authority->host_value;
    return true;
}

static void configure_mock(
        VtDecodeState* state,
        MockAuthority* authority) {
    vt_decode_set_handle_resolver(
            state, mock_resolve_handle, authority, authority->generation);
    vt_decode_set_handle_scope(
            state, authority->instance_owner, authority->device_owner);
}

static void test_handle_authority(void) {
    const uint8_t byte = 0;
    VtDecodeState state;
    VtDecodeCursor cursor;
    VtDecodeCursor child;
    uint64_t host_value = 0;
    MockAuthority authority = {
        .token = 41,
        .host_value = UINT64_C(0x12345678),
        .generation = 7,
        .instance_owner = 11,
        .device_owner = 17,
        .object_type = 9,
        .owner_requirements = VT_DECODE_OWNER_INSTANCE | VT_DECODE_OWNER_DEVICE,
        .role = VT_DECODE_HANDLE_ROLE_VULKAN,
        .live = true,
    };

    EXPECT(vt_decode_cursor_init(&cursor, &state, &byte, sizeof(byte), NULL));
    EXPECT(!vt_decode_resolve_handle(&cursor, authority.token,
            authority.object_type, authority.role, authority.owner_requirements,
            VT_DECODE_NULL_NEVER, &host_value));
    EXPECT(vt_decode_error(&cursor) == VT_DECODE_ERROR_HANDLE_RESOLVER);

    EXPECT(vt_decode_cursor_init(&cursor, &state, &byte, sizeof(byte), NULL));
    configure_mock(&state, &authority);
    EXPECT(vt_decode_resolve_handle(&cursor, authority.token,
            authority.object_type, authority.role, authority.owner_requirements,
            VT_DECODE_NULL_NEVER, &host_value));
    EXPECT(host_value == authority.host_value);

    /* Child decoders share exactly the root authority and owner scope. */
    EXPECT(vt_decode_child_at(&cursor, 0, sizeof(byte), &child));
    EXPECT(vt_decode_resolve_handle(&child, authority.token,
            authority.object_type, authority.role, authority.owner_requirements,
            VT_DECODE_NULL_NEVER, &host_value));

    EXPECT(vt_decode_cursor_init(&cursor, &state, &byte, sizeof(byte), NULL));
    configure_mock(&state, &authority);
    EXPECT(!vt_decode_resolve_handle(&cursor, authority.token,
            authority.object_type + 1, authority.role, authority.owner_requirements,
            VT_DECODE_NULL_NEVER, &host_value));
    EXPECT(vt_decode_error(&cursor) == VT_DECODE_ERROR_HANDLE_REJECTED);

    EXPECT(vt_decode_cursor_init(&cursor, &state, &byte, sizeof(byte), NULL));
    configure_mock(&state, &authority);
    EXPECT(!vt_decode_resolve_handle(&cursor, authority.token,
            authority.object_type, VT_DECODE_HANDLE_ROLE_RESOURCE_MEMORY_DEVICE_MEMORY,
            authority.owner_requirements, VT_DECODE_NULL_NEVER, &host_value));
    EXPECT(vt_decode_error(&cursor) == VT_DECODE_ERROR_HANDLE_REJECTED);

    EXPECT(vt_decode_cursor_init(&cursor, &state, &byte, sizeof(byte), NULL));
    configure_mock(&state, &authority);
    state.device_owner++;
    EXPECT(!vt_decode_resolve_handle(&cursor, authority.token,
            authority.object_type, authority.role, authority.owner_requirements,
            VT_DECODE_NULL_NEVER, &host_value));
    EXPECT(vt_decode_error(&cursor) == VT_DECODE_ERROR_HANDLE_REJECTED);

    EXPECT(vt_decode_cursor_init(&cursor, &state, &byte, sizeof(byte), NULL));
    configure_mock(&state, &authority);
    authority.live = false;
    EXPECT(!vt_decode_resolve_handle(&cursor, authority.token,
            authority.object_type, authority.role, authority.owner_requirements,
            VT_DECODE_NULL_NEVER, &host_value));
    EXPECT(vt_decode_error(&cursor) == VT_DECODE_ERROR_HANDLE_REJECTED);
    authority.live = true;

    EXPECT(vt_decode_cursor_init(&cursor, &state, &byte, sizeof(byte), NULL));
    configure_mock(&state, &authority);
    EXPECT(!vt_decode_resolve_handle(&cursor, 0, authority.object_type,
            authority.role, authority.owner_requirements,
            VT_DECODE_NULL_NEVER, &host_value));
    EXPECT(vt_decode_error(&cursor) == VT_DECODE_ERROR_HANDLE_REJECTED);

    EXPECT(vt_decode_cursor_init(&cursor, &state, &byte, sizeof(byte), NULL));
    configure_mock(&state, &authority);
    EXPECT(vt_decode_resolve_handle(&cursor, 0, authority.object_type,
            authority.role, authority.owner_requirements,
            VT_DECODE_NULL_VULKAN, &host_value));
    EXPECT(host_value == 0);

    EXPECT(vt_decode_cursor_init(&cursor, &state, &byte, sizeof(byte), NULL));
    configure_mock(&state, &authority);
    EXPECT(!vt_decode_resolve_handle(&cursor, 0, authority.object_type,
            authority.role, authority.owner_requirements,
            VT_DECODE_NULL_DESCRIPTOR_FEATURE, &host_value));
    EXPECT(vt_decode_error(&cursor) == VT_DECODE_ERROR_HANDLE_REJECTED);

    EXPECT(vt_decode_cursor_init(&cursor, &state, &byte, sizeof(byte), NULL));
    configure_mock(&state, &authority);
    vt_decode_set_null_descriptor_enabled(&state, true);
    EXPECT(vt_decode_resolve_handle(&cursor, 0, authority.object_type,
            authority.role, authority.owner_requirements,
            VT_DECODE_NULL_DESCRIPTOR_FEATURE, &host_value));
    EXPECT(host_value == 0);

    uint64_t captured[2] = {0};
    EXPECT(vt_decode_cursor_init(&cursor, &state, &byte, sizeof(byte), NULL));
    configure_mock(&state, &authority);
    vt_decode_capture_handle_tokens(
            &state, authority.object_type, captured, 2);
    EXPECT(vt_decode_resolve_handle(&cursor, authority.token,
            authority.object_type, authority.role, authority.owner_requirements,
            VT_DECODE_NULL_NEVER, &host_value));
    EXPECT(vt_decode_resolve_handle(&cursor, authority.token,
            authority.object_type, authority.role, authority.owner_requirements,
            VT_DECODE_NULL_NEVER, &host_value));
    EXPECT(vt_decode_captured_handle_count(&state) == 2);
    EXPECT(captured[0] == authority.token && captured[1] == authority.token);

    EXPECT(vt_decode_cursor_init(&cursor, &state, &byte, sizeof(byte), NULL));
    configure_mock(&state, &authority);
    vt_decode_capture_handle_tokens(
            &state, authority.object_type, captured, 1);
    EXPECT(vt_decode_resolve_handle(&cursor, authority.token,
            authority.object_type, authority.role, authority.owner_requirements,
            VT_DECODE_NULL_NEVER, &host_value));
    EXPECT(!vt_decode_resolve_handle(&cursor, authority.token,
            authority.object_type, authority.role, authority.owner_requirements,
            VT_DECODE_NULL_NEVER, &host_value));
    EXPECT(vt_decode_error(&cursor) == VT_DECODE_ERROR_LIMIT);

    EXPECT(vt_decode_cursor_init(&cursor, &state, &byte, sizeof(byte), NULL));
    configure_mock(&state, &authority);
    vt_decode_set_handle_scope(&state, authority.instance_owner, 0);
    EXPECT(!vt_decode_resolve_handle(&cursor, authority.token,
            authority.object_type, authority.role, authority.owner_requirements,
            VT_DECODE_NULL_NEVER, &host_value));
    EXPECT(vt_decode_error(&cursor) == VT_DECODE_ERROR_HANDLE_REJECTED);
}

int main(void) {
    test_cursor_bounds();
    test_presence_and_lengths();
    test_children();
    test_strings();
    test_allocations();
    test_pnext();
    test_handle_authority();
    if (failures != 0) {
        fprintf(stderr, "vortek_decode_test: %d failure(s)\n", failures);
        return 1;
    }
    puts("vortek_decode_test: PASS");
    return 0;
}
