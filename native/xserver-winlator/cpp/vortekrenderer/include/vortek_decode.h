#ifndef VORTEK_DECODE_H
#define VORTEK_DECODE_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

/*
 * These limits are part of the server's trust boundary, not the Vortek 2.1
 * wire format.  Valid packets keep their existing byte representation.
 */
#define VT_DECODE_MAX_REQUEST_BYTES (64u * 1024u * 1024u)
#define VT_DECODE_MAX_ALLOCATION_BYTES (128u * 1024u * 1024u)
#define VT_DECODE_MAX_ELEMENTS 65536u
#define VT_DECODE_MAX_NODES 262144u
#define VT_DECODE_MAX_DEPTH 32u
#define VT_DECODE_MAX_PNEXT_NODES 32u

typedef enum VtDecodeError {
    VT_DECODE_ERROR_NONE = 0,
    VT_DECODE_ERROR_ARGUMENT,
    VT_DECODE_ERROR_TRUNCATED,
    VT_DECODE_ERROR_TRAILING_BYTES,
    VT_DECODE_ERROR_INTEGER_OVERFLOW,
    VT_DECODE_ERROR_LIMIT,
    VT_DECODE_ERROR_NEGATIVE_LENGTH,
    VT_DECODE_ERROR_OUT_OF_MEMORY,
    VT_DECODE_ERROR_INVALID_BOOLEAN,
    VT_DECODE_ERROR_INVALID_STRING,
    VT_DECODE_ERROR_DEPTH,
    VT_DECODE_ERROR_PNEXT_DUPLICATE,
    VT_DECODE_ERROR_PNEXT_UNKNOWN,
    VT_DECODE_ERROR_PNEXT_TYPE,
    VT_DECODE_ERROR_PNEXT_TERMINATOR,
    VT_DECODE_ERROR_HANDLE_RESOLVER,
    VT_DECODE_ERROR_HANDLE_SCOPE,
    VT_DECODE_ERROR_HANDLE_REJECTED
} VtDecodeError;

/* These values deliberately describe decoder intent rather than depending on
 * the server's handle-registry headers.  The request integration layer maps
 * them to VortekHandleRole and the exact VkObjectType supplied by generated
 * code. */
typedef enum VtDecodeHandleRole {
    VT_DECODE_HANDLE_ROLE_VULKAN = 1,
    VT_DECODE_HANDLE_ROLE_RESOURCE_MEMORY_DEVICE_MEMORY = 2,
    VT_DECODE_HANDLE_ROLE_WINDOW_ID = 3,
    VT_DECODE_HANDLE_ROLE_SHADER_MODULE_WRAPPER = 4,
    VT_DECODE_HANDLE_ROLE_XWINDOW_SWAPCHAIN_WRAPPER = 5
} VtDecodeHandleRole;

typedef enum VtDecodeNullability {
    VT_DECODE_NULL_NEVER = 0,
    VT_DECODE_NULL_VULKAN = 1,
    VT_DECODE_NULL_DESCRIPTOR_FEATURE = 2,
} VtDecodeNullability;

typedef enum VtDecodeOwnerRequirement {
    VT_DECODE_OWNER_NONE = 0,
    VT_DECODE_OWNER_INSTANCE = 1u << 0,
    VT_DECODE_OWNER_DEVICE = 1u << 1
} VtDecodeOwnerRequirement;

typedef struct VtDecodeHandleRequest {
    uint64_t wire_token;
    uint64_t context_generation;
    uint64_t instance_owner;
    uint64_t device_owner;
    uint32_t object_type;
    uint32_t owner_requirements;
    VtDecodeHandleRole role;
    VtDecodeNullability nullability;
} VtDecodeHandleRequest;

/* Returns only already-authorized host bits.  Implementations must perform
 * exact type, role, generation, liveness and owner checks.  WINDOW_ID must be
 * routed through the X-window validator; ResourceMemory must return its
 * validated VkDeviceMemory rather than a wrapper pointer. */
typedef bool (*VtDecodeHandleResolver)(
        void* userdata,
        const VtDecodeHandleRequest* request,
        uint64_t* host_value);

typedef struct VtDecodeState {
    const uint8_t* request_base;
    const uint8_t* request_end;
    void* memory_pool;
    size_t allocation_bytes;
    size_t allocation_attempts;
    size_t fail_allocation_after;
    uint32_t nodes;
    VtDecodeError error;
    VtDecodeHandleResolver handle_resolver;
    void* handle_resolver_userdata;
    uint64_t context_generation;
    uint64_t instance_owner;
    uint64_t device_owner;
    bool null_descriptor_enabled;
    uint32_t capture_object_type;
    uint64_t* captured_handle_tokens;
    size_t captured_handle_capacity;
    size_t captured_handle_count;
} VtDecodeState;

typedef struct VtDecodeCursor {
    const uint8_t* base;
    const uint8_t* ptr;
    const uint8_t* end;
    const uint8_t* request_end;
    VtDecodeState* state;
    uint32_t depth;
    uint32_t pnext_count;
    int32_t pnext_types[VT_DECODE_MAX_PNEXT_NODES];
    int32_t expected_pnext_type;
} VtDecodeCursor;

bool vt_decode_cursor_init(
        VtDecodeCursor* cursor,
        VtDecodeState* state,
        const void* data,
        size_t size,
        void* memory_pool);
bool vt_decode_child_at(
        VtDecodeCursor* parent,
        size_t offset,
        size_t size,
        VtDecodeCursor* child);
bool vt_decode_finished(VtDecodeCursor* cursor);
bool vt_decode_ok(const VtDecodeCursor* cursor);
size_t vt_decode_remaining(const VtDecodeCursor* cursor);
VtDecodeError vt_decode_error(const VtDecodeCursor* cursor);
bool vt_decode_fail(VtDecodeCursor* cursor, VtDecodeError error);
void vt_decode_fail_allocation_after(VtDecodeState* state, size_t successful_allocations);
void vt_decode_set_handle_resolver(
        VtDecodeState* state,
        VtDecodeHandleResolver resolver,
        void* userdata,
        uint64_t context_generation);
void vt_decode_set_handle_scope(
        VtDecodeState* state,
        uint64_t instance_owner,
        uint64_t device_owner);
void vt_decode_set_null_descriptor_enabled(
        VtDecodeState* state, bool enabled);
void vt_decode_capture_handle_tokens(
        VtDecodeState* state,
        uint32_t object_type,
        uint64_t* tokens,
        size_t capacity);
size_t vt_decode_captured_handle_count(const VtDecodeState* state);
bool vt_decode_resolve_handle(
        VtDecodeCursor* cursor,
        uint64_t wire_token,
        uint32_t object_type,
        VtDecodeHandleRole role,
        uint32_t owner_requirements,
        VtDecodeNullability nullability,
        uint64_t* host_value);

bool vt_decode_read_at(
        VtDecodeCursor* cursor,
        size_t offset,
        void* output,
        size_t size);
bool vt_decode_copy_at(
        VtDecodeCursor* cursor,
        size_t offset,
        void* output,
        size_t size);
bool vt_decode_advance(VtDecodeCursor* cursor, size_t* offset, size_t amount);
bool vt_decode_advance_array(
        VtDecodeCursor* cursor,
        size_t* offset,
        size_t count,
        size_t element_size);
bool vt_decode_presence_at(VtDecodeCursor* cursor, size_t* offset, bool* present);
bool vt_decode_i32_at(VtDecodeCursor* cursor, size_t offset, int32_t* value);
bool vt_decode_data_size_at(
        VtDecodeCursor* cursor,
        size_t offset,
        int32_t* value);
bool vt_decode_length_i32_at(
        VtDecodeCursor* cursor,
        size_t offset,
        int32_t* value,
        size_t minimum_element_size);
bool vt_decode_string_at(
        VtDecodeCursor* cursor,
        size_t offset,
        size_t size,
        char* output);
bool vt_decode_validate_count(VtDecodeCursor* cursor, uint64_t count);
bool vt_decode_note_elements(VtDecodeCursor* cursor, size_t count);

bool vt_decode_add_size(size_t left, size_t right, size_t* result);
bool vt_decode_multiply_size(size_t count, size_t element_size, size_t* result);
void* vt_decode_alloc(
        VtDecodeCursor* cursor,
        void* memory_pool,
        size_t count,
        size_t element_size);
void* vt_decode_alloc_bytes(
        VtDecodeCursor* cursor,
        void* memory_pool,
        size_t bytes);

bool vt_decode_pnext_known(
        VtDecodeCursor* cursor,
        int32_t structure_type,
        int32_t item_size);
bool vt_decode_pnext_unknown(
        VtDecodeCursor* cursor,
        int32_t structure_type,
        int32_t item_size);
bool vt_decode_pnext_terminated(VtDecodeCursor* cursor, int32_t structure_type);

#if defined(__GNUC__) || defined(__clang__)
#define VT_DECODE_VALUE(cursor, offset, type) \
    __extension__ ({ \
        type _vt_decode_value = (type){0}; \
        (void)vt_decode_read_at((cursor), (size_t)(offset), \
                &_vt_decode_value, sizeof(_vt_decode_value)); \
        _vt_decode_value; \
    })

#define VT_DECODE_PRODUCT(cursor, count, element_size) \
    __extension__ ({ \
        size_t _vt_product = 0; \
        if (!vt_decode_multiply_size((size_t)(count), (size_t)(element_size), \
                &_vt_product)) { \
            (void)vt_decode_fail((cursor), VT_DECODE_ERROR_INTEGER_OVERFLOW); \
        } \
        _vt_product; \
    })
#else
#define VT_DECODE_VALUE(...) VT_DECODE_REQUIRES_CLANG_OR_GCC
#define VT_DECODE_PRODUCT(...) VT_DECODE_REQUIRES_CLANG_OR_GCC
#endif

#if defined(__GNUC__) || defined(__clang__)
#define VT_DECODE_CHILD_CALL(parent, offset, size, call) \
    __extension__ ({ \
        VtDecodeCursor _vt_child; \
        bool _vt_child_ok = vt_decode_child_at((parent), (size_t)(offset), \
                (size_t)(size), &_vt_child); \
        if (_vt_child_ok) _vt_child_ok = (call); \
        if (_vt_child_ok) _vt_child_ok = vt_decode_finished(&_vt_child); \
        _vt_child_ok; \
    })
#else
#define VT_DECODE_CHILD_CALL(...) VT_DECODE_REQUIRES_CLANG_OR_GCC
#endif

#endif
