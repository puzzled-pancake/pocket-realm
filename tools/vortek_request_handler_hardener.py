#!/usr/bin/env python3
"""Deterministically harden Vortek's pinned ca3d735 request dispatcher.

The Vortek 2.1 wire is intentionally unchanged.  This transformer only changes
the server-side consumer: every generated decoder is given a bounded cursor,
its boolean result and exact consumption are checked, request-sized stack/heap
storage is charged to the request arena, and command batches are fully decoded
before their first command is executed.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "native/xserver-winlator/cpp/vortekrenderer/generated/request_handler_upstream_ca3d735.c"
SERIALIZER = ROOT / "native/xserver-winlator/cpp/vortekrenderer/include/vortek_serializer.h"
OUTPUT = ROOT / "native/xserver-winlator/cpp/vortekrenderer/src/request_handler.c"
MANIFEST = ROOT / "native/xserver-winlator/cpp/vortekrenderer/generated/request_handler_manifest.json"
SOURCE_SHA256 = "96050e259454c406ab26cb11a77aa0a4b0d4c019559617aecb5445adc502e019"

HANDLER = re.compile(r"^void (?P<name>vt_handle_[A-Za-z0-9_]+)\(VkContext\* context\) \{", re.M)
DECODER = re.compile(r"\bvt_unserialize_[A-Za-z0-9_]+\(")


def normalized(path: Path) -> str:
    return path.read_text(encoding="utf-8").replace("\r\n", "\n")


def brace_end(text: str, start: int) -> int:
    depth = 1
    pos = start
    while pos < len(text) and depth:
        if text[pos] == "{":
            depth += 1
        elif text[pos] == "}":
            depth -= 1
        pos += 1
    if depth:
        raise ValueError("unterminated handler")
    return pos


def call_end(text: str, start: int) -> int:
    open_paren = text.index("(", start)
    depth = 1
    pos = open_paren + 1
    while pos < len(text) and depth:
        if text[pos] == "(":
            depth += 1
        elif text[pos] == ")":
            depth -= 1
        pos += 1
    if depth or pos >= len(text) or text[pos] != ";":
        raise ValueError(f"unterminated decoder call at {start}")
    return pos + 1


def split_arguments(arguments: str) -> list[str]:
    parts: list[str] = []
    depth = 0
    start = 0
    for pos, char in enumerate(arguments):
        if char in "([":
            depth += 1
        elif char in ")]":
            depth -= 1
        elif char == "," and depth == 0:
            parts.append(arguments[start:pos].strip())
            start = pos + 1
    parts.append(arguments[start:].strip())
    return parts


def checked_decoder_calls(body: str) -> tuple[str, int]:
    matches: list[tuple[int, int, str]] = []
    position = 0
    while match := DECODER.search(body, position):
        end = call_end(body, match.start())
        call = body[match.start():end - 1]
        open_paren = call.index("(")
        args = split_arguments(call[open_paren + 1:-1])
        if len(args) < 2 or args[-2] != "context->inputBuffer" or args[-1] != "&context->memoryPool":
            raise ValueError(f"unexpected request decoder tail: {call[-160:]}")
        args[-2] = "&_vt_cursor"
        replacement = f"VT_REQUEST_DECODE({call[:open_paren + 1]}{', '.join(args)}))"
        matches.append((match.start(), end, replacement + ";"))
        position = end
    for start, end, replacement in reversed(matches):
        body = body[:start] + replacement + body[end:]
    return body, len(matches)


def replace_request_storage(body: str) -> tuple[str, int, int]:
    vla_count = 0
    allocation_count = 0

    dynamic = re.compile(
        r"^(?P<indent>\s{4,})(?P<type>(?:const )?[A-Za-z_][A-Za-z0-9_ ]*(?:\*\s*)?) "
        r"(?P<name>[A-Za-z_]\w*)\[(?P<count>[A-Za-z_][^\]]*)\];$", re.M
    )

    def vla(match: re.Match[str]) -> str:
        nonlocal vla_count
        vla_count += 1
        item_type = match.group("type").strip()
        name = match.group("name")
        count = match.group("count").strip()
        if name == "sampleMask" and count == "samples":
            count = "(((uint32_t)samples + 31u) / 32u)"
        macro = "VT_REQUEST_BYTES" if item_type == "char" else "VT_REQUEST_ARRAY"
        return f'{match.group("indent")}{macro}({item_type}, {name}, {count});'

    body = dynamic.sub(vla, body)

    heap = re.compile(
        r"^(?P<indent>\s{4,})(?P<type>[A-Za-z_][A-Za-z0-9_ ]*)\* (?P<name>\w+) = "
        r"(?P<count>[^;\n]+?) > 0 \? calloc\((?P=count), sizeof\([^;]+?\)\) : NULL;$", re.M
    )

    def arena(match: re.Match[str]) -> str:
        nonlocal allocation_count
        allocation_count += 1
        return (f'{match.group("indent")}VT_REQUEST_ARRAY({match.group("type").strip()}, '
                f'{match.group("name")}, {match.group("count").strip()});')

    body = heap.sub(arena, body)
    body = re.sub(r"^\s*MEMFREE\((\w+)\);\s*$", "", body, flags=re.M)
    return body, vla_count, allocation_count


ROOT_HANDLE_TYPES: dict[str, tuple[str, str, bool, bool]] = {
    "VkInstance": ("VK_OBJECT_TYPE_INSTANCE", "VORTEK_HANDLE_ROLE_VULKAN", False, False),
    "VkPhysicalDevice": ("VK_OBJECT_TYPE_PHYSICAL_DEVICE", "VORTEK_HANDLE_ROLE_VULKAN", True, False),
    "VkDevice": ("VK_OBJECT_TYPE_DEVICE", "VORTEK_HANDLE_ROLE_VULKAN", True, False),
    "VkQueue": ("VK_OBJECT_TYPE_QUEUE", "VORTEK_HANDLE_ROLE_VULKAN", True, True),
    "VkCommandBuffer": ("VK_OBJECT_TYPE_COMMAND_BUFFER", "VORTEK_HANDLE_ROLE_VULKAN", True, True),
    "VkBuffer": ("VK_OBJECT_TYPE_BUFFER", "VORTEK_HANDLE_ROLE_VULKAN", True, True),
    "VkImage": ("VK_OBJECT_TYPE_IMAGE", "VORTEK_HANDLE_ROLE_VULKAN", True, True),
    "VkQueryPool": ("VK_OBJECT_TYPE_QUERY_POOL", "VORTEK_HANDLE_ROLE_VULKAN", True, True),
    "VkEvent": ("VK_OBJECT_TYPE_EVENT", "VORTEK_HANDLE_ROLE_VULKAN", True, True),
    "VkFence": ("VK_OBJECT_TYPE_FENCE", "VORTEK_HANDLE_ROLE_VULKAN", True, True),
    "VkSemaphore": ("VK_OBJECT_TYPE_SEMAPHORE", "VORTEK_HANDLE_ROLE_VULKAN", True, True),
    "VkPipelineLayout": ("VK_OBJECT_TYPE_PIPELINE_LAYOUT", "VORTEK_HANDLE_ROLE_VULKAN", True, True),
    "VkCommandPool": ("VK_OBJECT_TYPE_COMMAND_POOL", "VORTEK_HANDLE_ROLE_VULKAN", True, True),
    "VkPipelineCache": ("VK_OBJECT_TYPE_PIPELINE_CACHE", "VORTEK_HANDLE_ROLE_VULKAN", True, True),
    "VkDescriptorPool": ("VK_OBJECT_TYPE_DESCRIPTOR_POOL", "VORTEK_HANDLE_ROLE_VULKAN", True, True),
    "VkPipeline": ("VK_OBJECT_TYPE_PIPELINE", "VORTEK_HANDLE_ROLE_VULKAN", True, True),
    "VkRenderPass": ("VK_OBJECT_TYPE_RENDER_PASS", "VORTEK_HANDLE_ROLE_VULKAN", True, True),
    "VkSamplerYcbcrConversion": ("VK_OBJECT_TYPE_SAMPLER_YCBCR_CONVERSION", "VORTEK_HANDLE_ROLE_VULKAN", True, True),
    "VkImageView": ("VK_OBJECT_TYPE_IMAGE_VIEW", "VORTEK_HANDLE_ROLE_VULKAN", True, True),
    "VkBufferView": ("VK_OBJECT_TYPE_BUFFER_VIEW", "VORTEK_HANDLE_ROLE_VULKAN", True, True),
    "VkSampler": ("VK_OBJECT_TYPE_SAMPLER", "VORTEK_HANDLE_ROLE_VULKAN", True, True),
    "VkFramebuffer": ("VK_OBJECT_TYPE_FRAMEBUFFER", "VORTEK_HANDLE_ROLE_VULKAN", True, True),
    "VkDescriptorSetLayout": ("VK_OBJECT_TYPE_DESCRIPTOR_SET_LAYOUT", "VORTEK_HANDLE_ROLE_VULKAN", True, True),
    "ResourceMemory*": ("VK_OBJECT_TYPE_UNKNOWN", "VORTEK_HANDLE_ROLE_RESOURCE_MEMORY", True, True),
    "ShaderModule*": ("VK_OBJECT_TYPE_UNKNOWN", "VORTEK_HANDLE_ROLE_SHADER_MODULE", True, True),
    "XWindowSwapchain*": ("VK_OBJECT_TYPE_UNKNOWN", "VORTEK_HANDLE_ROLE_XWINDOW_SWAPCHAIN", True, True),
}

NULLABLE_ROOT_HANDLES = {
    ("vt_handle_vkQueueSubmit", "fenceId"),
    ("vt_handle_vkQueueSubmit2", "fenceId"),
    ("vt_handle_vkQueueBindSparse", "fenceId"),
    ("vt_handle_vkAcquireNextImageKHR", "semaphoreId"),
    ("vt_handle_vkAcquireNextImageKHR", "fenceId"),
    ("vt_handle_vkFreeMemory", "memoryId"),
    ("vt_handle_vkCreateGraphicsPipelines", "pipelineCacheId"),
    ("vt_handle_vkCreateComputePipelines", "pipelineCacheId"),
}

SCOPE_ONLY_HANDLERS: dict[str, tuple[str, str]] = {
    "vt_handle_vkGetPhysicalDeviceSurfaceCapabilitiesKHR": (
        "VK_OBJECT_TYPE_PHYSICAL_DEVICE", "VORTEK_HANDLE_ROLE_VULKAN"),
    "vt_handle_vkGetPhysicalDeviceSurfaceFormatsKHR": (
        "VK_OBJECT_TYPE_PHYSICAL_DEVICE", "VORTEK_HANDLE_ROLE_VULKAN"),
    "vt_handle_vkGetPhysicalDeviceSurfacePresentModesKHR": (
        "VK_OBJECT_TYPE_PHYSICAL_DEVICE", "VORTEK_HANDLE_ROLE_VULKAN"),
    "vt_handle_vkGetPhysicalDevicePresentRectanglesKHR": (
        "VK_OBJECT_TYPE_PHYSICAL_DEVICE", "VORTEK_HANDLE_ROLE_VULKAN"),
}

ROOT_HANDLE_ASSIGNMENT = re.compile(
    r"(?m)^(?P<indent>\s{4,})(?P<type>(?:Vk[A-Za-z0-9_]+|"
    r"ResourceMemory\*|ShaderModule\*|XWindowSwapchain\*)) "
    r"(?P<name>[A-Za-z_]\w*) = VkObject_fromId\((?P<token>[A-Za-z_]\w*)\);$"
)


def replace_root_handles(body: str, handler: str) -> tuple[str, int]:
    matches = list(ROOT_HANDLE_ASSIGNMENT.finditer(body))
    if not matches:
        if handler in SCOPE_ONLY_HANDLERS:
            object_type, role = SCOPE_ONLY_HANDLERS[handler]
            begin = "\n    VT_REQUEST_BEGIN(context);"
            if begin not in body:
                raise ValueError(f"scope-only handler lacks request begin: {handler}")
            body = body.replace(begin, begin +
                    f"\n    VT_REQUEST_SCOPE_COMMAND({object_type}, {role});", 1)
        return body, 0

    first = matches[0]
    first_type = first.group("type")
    if first_type not in ROOT_HANDLE_TYPES:
        raise ValueError(f"missing root handle policy for {handler}: {first_type}")
    object_type, role, _, _ = ROOT_HANDLE_TYPES[first_type]

    # Generated vkFoo command decoders prefix the first handle with a presence
    # byte; standalone VkFoo decoders are exactly the eight-byte token.  The
    # hand-written EndCommandBuffer replacement also starts with the raw token.
    decoder = re.search(r"VT_REQUEST_DECODE\(vt_unserialize_([A-Za-z0-9_]+)\(", body)
    if handler == "vt_handle_vkEndCommandBuffer":
        scope_macro = "VT_REQUEST_SCOPE_SIMPLE"
    elif decoder:
        scope_macro = "VT_REQUEST_SCOPE_COMMAND" if decoder.group(1).startswith("vk") \
                else "VT_REQUEST_SCOPE_SIMPLE"
    else:
        raise ValueError(f"cannot locate first decoder for root scope: {handler}")
    begin = "\n    VT_REQUEST_BEGIN(context);"
    if begin not in body:
        raise ValueError(f"root handle handler lacks request begin: {handler}")
    body = body.replace(begin, begin +
            f"\n    {scope_macro}({object_type}, {role});", 1)

    def replacement(match: re.Match[str]) -> str:
        handle_type = match.group("type")
        try:
            vk_type, handle_role, require_instance, require_device = \
                    ROOT_HANDLE_TYPES[handle_type]
        except KeyError as exc:
            raise ValueError(
                f"missing root handle policy for {handler}: {handle_type}") from exc
        allow_null = (handler, match.group("token")) in NULLABLE_ROOT_HANDLES
        if handler.startswith("vt_handle_vkDestroy") and handle_type not in {
                "VkInstance", "VkDevice"} and match.group("name") != "device":
            allow_null = True
        retire = (
            (handler == "vt_handle_vkDestroyInstance" and match.group("name") == "instance") or
            (handler == "vt_handle_vkDestroyDevice" and match.group("name") == "device") or
            (handler.startswith("vt_handle_vkDestroy") and
             match.group("name") != "device") or
            (handler == "vt_handle_vkFreeMemory" and
             match.group("name") == "resourceMemory")
        )
        macro = "VT_REQUEST_RETIRED_HANDLE" if retire else "VT_REQUEST_HANDLE"
        return (
            f'{match.group("indent")}{macro}({handle_type}, '
            f'{match.group("name")}, {match.group("token")}, {vk_type}, '
            f'{handle_role}, {str(require_instance).lower()}, '
            f'{str(require_device).lower()}, {str(allow_null).lower()});'
        )

    return ROOT_HANDLE_ASSIGNMENT.sub(replacement, body), len(matches)


def decoder_signatures(serializer: str, commands_only: bool = True) -> dict[str, int]:
    name_pattern = r"vkCmd\w+" if commands_only else r"[A-Za-z0-9_]+"
    pattern = re.compile(rf"static inline bool vt_unserialize_({name_pattern})\((.*?)\) \{{", re.S)
    result: dict[str, int] = {}
    for match in pattern.finditer(serializer):
        args = split_arguments(match.group(2))
        if len(args) < 2 or "VtDecodeCursor*" not in args[-2]:
            raise ValueError(f"unexpected serializer signature: {match.group(1)}")
        result[match.group(1)] = len(args) - 2
    return result


def preflight_function(serializer: str) -> str:
    signatures = decoder_signatures(serializer)
    lines = [
        "static bool vt_request_preflight_command(",
        "        HandleRequestFunc handler, VtRequestDecode* request) {",
        "    VtDecodeCursor _vt_cursor;",
        "    if (!vt_request_decode_pass_begin(request, &_vt_cursor)) return false;",
    ]
    for name, count in sorted(signatures.items()):
        zeros = ", ".join(["0"] * count)
        comma = ", " if zeros else ""
        lines.extend([
            f"    if (handler == vt_handle_{name}) {{",
            f"        return vt_unserialize_{name}({zeros}{comma}&_vt_cursor, request->memoryPool) &&",
            "                vt_decode_finished(&_vt_cursor);",
            "    }",
        ])
    lines.extend(["    return false;", "}", ""])
    return "\n".join(lines)


END_HANDLER = r'''void vt_handle_vkEndCommandBuffer(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    const uint8_t* inputBuffer = (const uint8_t*)context->inputBuffer;
    size_t inputSize = (size_t)context->inputBufferSize;
    if (inputSize < VK_HANDLE_BYTE_COUNT) {
        vt_request_protocol_error(context, VT_DECODE_ERROR_TRUNCATED);
        return;
    }

    uint64_t commandBufferId = 0;
    memcpy(&commandBufferId, inputBuffer, sizeof(commandBufferId));
    size_t position = VK_HANDLE_BYTE_COUNT;

    /* Pass one validates every frame and fully decodes every payload. */
    VtRequestBatchChunk chunkInfo;
    VtRequestBatchStep step;
    while ((step = vt_request_batch_next(inputBuffer, inputSize, &position, &chunkInfo))
            == VT_REQUEST_BATCH_CHUNK) {
        HandleRequestFunc handler = getHandleRequestFunc((short)chunkInfo.requestCode);
        VtRequestDecode chunk = _vt_request;
        chunk.data = chunkInfo.data;
        chunk.size = chunkInfo.size;
        if (!handler || !vt_request_preflight_command(handler, &chunk)) {
            vt_request_protocol_error(context, handler
                    ? vt_decode_error(&(VtDecodeCursor){.state = chunk.state})
                    : VT_DECODE_ERROR_ARGUMENT);
            return;
        }
    }
    if (step != VT_REQUEST_BATCH_DONE) {
        vt_request_protocol_error(context, VT_DECODE_ERROR_TRUNCATED);
        return;
    }

    /* Pass two is unreachable until every command payload is known-good. */
    VtRequestDecode* previousBatch = vt_active_batch_request;
    vt_active_batch_request = &_vt_request;
    char* savedBuffer = context->inputBuffer;
    int savedSize = context->inputBufferSize;
    position = VK_HANDLE_BYTE_COUNT;
    while (position < inputSize && !VkContext_isClosing(context)) {
        int32_t requestCode = 0;
        int32_t payloadSize = 0;
        memcpy(&requestCode, inputBuffer + position, sizeof(requestCode));
        memcpy(&payloadSize, inputBuffer + position + sizeof(requestCode), sizeof(payloadSize));
        context->inputBuffer = (char*)inputBuffer + position + HEADER_SIZE;
        context->inputBufferSize = payloadSize;
        getHandleRequestFunc((short)requestCode)(context);
        position += HEADER_SIZE + (size_t)payloadSize;
    }
    context->inputBuffer = savedBuffer;
    context->inputBufferSize = savedSize;
    vt_active_batch_request = previousBatch;
    if (VkContext_isClosing(context)) return;

    VkCommandBuffer commandBuffer = VkObject_fromId(commandBufferId);
    vulkanWrapper.vkEndCommandBuffer(commandBuffer);
}'''


RUNTIME = r'''#include <limits.h>
#include <stdint.h>

static _Thread_local VtRequestDecode* vt_active_batch_request;

static void vt_handle_authority_incomplete(VkContext* context) {
    /* The legacy bodies remain generated for compile-time migration, but no
     * guest token may reach them until every root input/output is authoritative. */
    VkContext_requestStop(context, VK_ERROR_DEVICE_LOST);
}

static bool vt_request_authority_lookup(
        VtRequestDecode* decode,
        uint64_t wireToken,
        VkObjectType objectType,
        VortekHandleRole role,
        bool requireInstanceOwner,
        bool requireDeviceOwner,
        bool allowNull,
        bool establishScope,
        VortekHandleValue* value) {
    if (!decode || !decode->state || !decode->context || !value) return false;
    VkContext* context = decode->context;
    if (!context->handleAuthority ||
            context->contextGeneration != decode->state->context_generation) return false;

    const bool missingInstance = requireInstanceOwner &&
            decode->state->instance_owner == 0;
    const bool missingDevice = requireDeviceOwner &&
            decode->state->device_owner == 0;
    VortekHandleExpectation expectation = {
        .contextGeneration = context->contextGeneration,
        .role = role,
        .vulkanType = objectType,
        .owner = {
            .instance = decode->state->instance_owner,
            .device = decode->state->device_owner,
        },
        .requireInstanceOwner = requireInstanceOwner && !missingInstance,
        .requireDeviceOwner = requireDeviceOwner && !missingDevice,
        .allowNull = allowNull,
    };
    VortekHandleStatus status = VkObjectAuthority_resolve(
            context->handleAuthority, wireToken, &expectation, value);
    if (status != VORTEK_HANDLE_OK) return false;
    if (wireToken == 0) return allowNull;

    if (establishScope) {
        uint64_t instanceOwner = decode->state->instance_owner;
        uint64_t deviceOwner = decode->state->device_owner;
        if (instanceOwner == 0) {
            instanceOwner = objectType == VK_OBJECT_TYPE_INSTANCE &&
                    role == VORTEK_HANDLE_ROLE_VULKAN
                    ? wireToken : value->owner.instance;
        }
        if (deviceOwner == 0) {
            deviceOwner = objectType == VK_OBJECT_TYPE_DEVICE &&
                    role == VORTEK_HANDLE_ROLE_VULKAN
                    ? wireToken : value->owner.device;
        }
        vt_decode_set_handle_scope(decode->state, instanceOwner, deviceOwner);
    }
    if (requireInstanceOwner && (decode->state->instance_owner == 0 ||
            value->owner.instance != decode->state->instance_owner)) return false;
    if (requireDeviceOwner && (decode->state->device_owner == 0 ||
            value->owner.device != decode->state->device_owner)) return false;
    if (decode->state->device_owner != 0) {
        VortekHandleExpectation deviceExpectation = {
            .contextGeneration = context->contextGeneration,
            .role = VORTEK_HANDLE_ROLE_VULKAN,
            .vulkanType = VK_OBJECT_TYPE_DEVICE,
            .owner = {.instance = decode->state->instance_owner},
            .requireInstanceOwner = decode->state->instance_owner != 0,
            .allowNull = false,
        };
        VortekHandleValue deviceValue = {0};
        if (VkObjectAuthority_resolve(context->handleAuthority,
                decode->state->device_owner, &deviceExpectation,
                &deviceValue) != VORTEK_HANDLE_OK) return false;
        vt_decode_set_null_descriptor_enabled(
                decode->state, deviceValue.nullDescriptorEnabled);
    }
    else vt_decode_set_null_descriptor_enabled(decode->state, false);
    return true;
}

static bool vt_request_resolve_handle(
        void* userdata,
        const VtDecodeHandleRequest* request,
        uint64_t* hostValue) {
    VtRequestDecode* decode = userdata;
    VkContext* context = decode ? decode->context : NULL;
    if (!decode || !context || !request || !hostValue ||
            request->context_generation != context->contextGeneration) return false;
    *hostValue = 0;

    if (request->role == VT_DECODE_HANDLE_ROLE_WINDOW_ID) {
        uint32_t windowId = 0;
        VortekHandleStatus status = VortekHandleRegistry_validateWindowId(
                context->handleAuthority, request->wire_token,
                request->context_generation, request->instance_owner,
                request->nullability != VT_DECODE_NULL_NEVER, &windowId);
        if (status != VORTEK_HANDLE_OK && status != VORTEK_HANDLE_NULL) return false;
        *hostValue = windowId;
        return true;
    }

    VortekHandleValue value = {0};
    VortekHandleRole authorityRole = VORTEK_HANDLE_ROLE_VULKAN;
    if (request->role == VT_DECODE_HANDLE_ROLE_RESOURCE_MEMORY_DEVICE_MEMORY)
        authorityRole = VORTEK_HANDLE_ROLE_RESOURCE_MEMORY;
    else if (request->role == VT_DECODE_HANDLE_ROLE_SHADER_MODULE_WRAPPER)
        authorityRole = VORTEK_HANDLE_ROLE_SHADER_MODULE;
    else if (request->role == VT_DECODE_HANDLE_ROLE_XWINDOW_SWAPCHAIN_WRAPPER)
        authorityRole = VORTEK_HANDLE_ROLE_XWINDOW_SWAPCHAIN;
    if (!vt_request_authority_lookup(decode, request->wire_token,
            (VkObjectType)request->object_type,
            authorityRole,
            (request->owner_requirements & VT_DECODE_OWNER_INSTANCE) != 0,
            (request->owner_requirements & VT_DECODE_OWNER_DEVICE) != 0,
            request->nullability != VT_DECODE_NULL_NEVER, true, &value)) return false;
    if (request->wire_token == 0) return true;
    if (request->role == VT_DECODE_HANDLE_ROLE_RESOURCE_MEMORY_DEVICE_MEMORY) {
        ResourceMemory* memory = (ResourceMemory*)(uintptr_t)value.hostValue;
        if (!memory) return false;
        *hostValue = (uint64_t)(uintptr_t)memory->memory;
    }
    else *hostValue = value.hostValue;
    return true;
}

bool vt_request_decode_begin(VtRequestDecode* request, VkContext* context) {
    if (!request || !context || context->inputBufferSize < 0 ||
            (!context->inputBuffer && context->inputBufferSize != 0)) return false;
    memset(request, 0, sizeof(*request));
    request->context = context;
    request->data = (const uint8_t*)context->inputBuffer;
    request->size = (size_t)context->inputBufferSize;
    request->memoryPool = &context->memoryPool;
    if (vt_active_batch_request) {
        const uint8_t* outerBegin = vt_active_batch_request->state->request_base;
        const uint8_t* outerEnd = vt_active_batch_request->state->request_end;
        if (request->data < outerBegin || request->data > outerEnd ||
                request->size > (size_t)(outerEnd - request->data)) return false;
        request->state = vt_active_batch_request->state;
        return true;
    }
    VtDecodeCursor initial;
    request->state = &request->ownedState;
    if (!vt_decode_cursor_init(&initial, request->state, request->data,
            request->size, request->memoryPool)) return false;
    vt_decode_set_handle_resolver(request->state, vt_request_resolve_handle,
            request, context->contextGeneration);
    vt_decode_set_null_descriptor_enabled(request->state, false);
    return true;
}

bool vt_request_decode_pass_begin(VtRequestDecode* request, VtDecodeCursor* cursor) {
    if (!request || !request->state || !cursor || request->state->error != VT_DECODE_ERROR_NONE)
        return false;
    memset(cursor, 0, sizeof(*cursor));
    cursor->base = request->data;
    cursor->ptr = request->data;
    cursor->end = request->data + request->size;
    cursor->request_end = request->state->request_end;
    cursor->state = request->state;
    cursor->expected_pnext_type = -1;
    return true;
}

void* vt_request_decode_alloc(VtRequestDecode* request, size_t count, size_t elementSize) {
    VtDecodeCursor cursor;
    if (!vt_request_decode_pass_begin(request, &cursor)) return NULL;
    return vt_decode_alloc(&cursor, request->memoryPool, count, elementSize);
}

void* vt_request_output_alloc(VtRequestDecode* request, size_t size) {
    if (size == 0) return NULL;
    if (!request || !request->state || !request->memoryPool ||
            !request->context || !request->context->clientRing ||
            !vt_transport_size_fits(size,
                    request->context->clientRing->bufferSize,
                    CLIENT_RING_BUFFER_SIZE - HEADER_SIZE) ||
            size > (size_t)INT_MAX) {
        if (request && request->state) {
            VtDecodeCursor cursor = {.state = request->state};
            (void)vt_decode_fail(&cursor, VT_DECODE_ERROR_LIMIT);
        }
        return NULL;
    }
    void* result = vt_alloc(request->memoryPool, (int)size);
    if (!result) {
        VtDecodeCursor cursor = {.state = request->state};
        (void)vt_decode_fail(&cursor, VT_DECODE_ERROR_OUT_OF_MEMORY);
    }
    return result;
}

static bool vt_request_track_single_publication(
        VtRequestDecode* request, VkObjectType objectType,
        VortekHandleRole role, VortekHandleOwner owner,
        uint64_t hostValue, uint64_t hostDeviceValue,
        uint64_t wireToken) {
    if (!request || request->publication.active || wireToken == 0 ||
            hostValue == 0) return false;
    request->publication = (VtRequestPublication) {
        .cleanup = VT_REQUEST_PUBLICATION_SINGLE,
        .objectType = objectType,
        .role = role,
        .owner = owner,
        .hostDeviceValue = hostDeviceValue,
        .hostValue = hostValue,
        .wireToken = wireToken,
        .active = true,
    };
    return true;
}

bool vt_request_seed_handle_scope(
        VtRequestDecode* request, size_t tokenOffset,
        VkObjectType objectType, VortekHandleRole role) {
    VtDecodeCursor cursor;
    uint64_t wireToken = 0;
    VortekHandleValue value = {0};
    if (!vt_request_decode_pass_begin(request, &cursor) ||
            !vt_decode_read_at(&cursor, tokenOffset, &wireToken, sizeof(wireToken))) {
        return false;
    }
    return vt_request_authority_lookup(request, wireToken, objectType, role,
            false, false, false, true, &value);
}

bool vt_request_resolve_root_handle(
        VtRequestDecode* request, uint64_t wireToken,
        VkObjectType objectType, VortekHandleRole role,
        bool requireInstanceOwner, bool requireDeviceOwner,
        bool allowNull, uint64_t* hostValue) {
    VortekHandleValue value = {0};
    if (!hostValue || !vt_request_authority_lookup(request, wireToken,
            objectType, role, requireInstanceOwner, requireDeviceOwner,
            allowNull, true, &value)) return false;
    *hostValue = value.hostValue;
    return true;
}

bool vt_request_retire_root_handle(
        VtRequestDecode* request, uint64_t wireToken,
        VkObjectType objectType, VortekHandleRole role,
        bool requireInstanceOwner, bool requireDeviceOwner,
        bool allowNull, uint64_t* hostValue) {
    if (!request || !request->state || !request->context || !hostValue) return false;
    *hostValue = 0;
    if (wireToken == 0) return allowNull;
    VortekHandleExpectation expectation = {
        .contextGeneration = request->context->contextGeneration,
        .role = role,
        .vulkanType = objectType,
        .owner = {
            .instance = request->state->instance_owner,
            .device = request->state->device_owner,
        },
        .requireInstanceOwner = requireInstanceOwner,
        .requireDeviceOwner = requireDeviceOwner,
        .allowNull = false,
    };
    VortekHandleValue value = {0};
    VortekHandleStatus status = VkObjectAuthority_tombstone(
            request->context->handleAuthority, wireToken, &expectation, &value);
    if (status != VORTEK_HANDLE_OK) return false;
    *hostValue = value.hostValue;
    return true;
}

bool vt_request_publish_handle(
        VtRequestDecode* request, VkObjectType objectType,
        VortekHandleRole role, uint64_t hostValue,
        uint64_t instanceOwner, uint64_t deviceOwner,
        uint64_t hostDeviceValue,
        uint64_t* wireToken) {
    if (!request || !request->context || !request->context->handleAuthority ||
            !wireToken) return false;
    *wireToken = 0;
    const bool isDevice = role == VORTEK_HANDLE_ROLE_VULKAN &&
            objectType == VK_OBJECT_TYPE_DEVICE;
    const bool nullDescriptorEnabled = isDevice &&
            request->context->nullDescriptorEnabled;
    if (isDevice) request->context->nullDescriptorEnabled = false;
    /* Every call site reaches publication only after a successful create or
     * a required identity query.  A null host output is therefore invalid. */
    if (hostValue == 0) return false;
    VortekHandleOwner owner = {
        .instance = instanceOwner,
        .device = deviceOwner,
    };
    VortekHandleStatus status = role == VORTEK_HANDLE_ROLE_VULKAN
            ? VkObjectAuthority_publishVulkan(request->context->handleAuthority,
                    objectType, hostValue, owner, wireToken)
            : VkObjectAuthority_publishWrapper(request->context->handleAuthority,
                    role, (const void*)(uintptr_t)hostValue, owner, wireToken);
    if (status == VORTEK_HANDLE_OK && isDevice) {
        status = VkObjectAuthority_setDeviceNullDescriptor(
                request->context->handleAuthority, *wireToken,
                nullDescriptorEnabled);
        if (status != VORTEK_HANDLE_OK) {
            VortekHandleExpectation expectation = {
                .contextGeneration = request->context->contextGeneration,
                .role = VORTEK_HANDLE_ROLE_VULKAN,
                .vulkanType = VK_OBJECT_TYPE_DEVICE,
                .owner = {.instance = instanceOwner},
                .requireInstanceOwner = instanceOwner != 0,
                .allowNull = false,
            };
            (void)VkObjectAuthority_tombstone(
                    request->context->handleAuthority, *wireToken,
                    &expectation, NULL);
            *wireToken = 0;
        }
    }
    if (status == VORTEK_HANDLE_OK && !vt_request_track_single_publication(
            request, objectType, role, owner, hostValue,
            hostDeviceValue, *wireToken)) {
        VortekHandleExpectation expectation = {
            .contextGeneration = request->context->contextGeneration,
            .role = role,
            .vulkanType = objectType,
            .owner = owner,
            .requireInstanceOwner = owner.instance != 0,
            .requireDeviceOwner = owner.device != 0,
            .requireParentOwner = owner.parent != 0,
            .allowNull = false,
        };
        (void)VkObjectAuthority_tombstone(
                request->context->handleAuthority, *wireToken,
                &expectation, NULL);
        *wireToken = 0;
        return false;
    }
    return status == VORTEK_HANDLE_OK;
}

void vt_request_rollback_output(
        VtRequestDecode* request, VkObjectType objectType,
        VortekHandleRole role, uint64_t hostValue, uint64_t hostDeviceValue) {
    if (!request || !request->context) return;
    if (role == VORTEK_HANDLE_ROLE_VULKAN &&
            objectType == VK_OBJECT_TYPE_DEVICE) {
        request->context->nullDescriptorEnabled = false;
    }
    if (hostValue == 0) return;
    VkDevice device = (VkDevice)(uintptr_t)hostDeviceValue;
    switch (role) {
        case VORTEK_HANDLE_ROLE_VULKAN:
            /* Queues are queried device-owned identities, not created objects. */
#if ENABLE_VALIDATION_LAYER
            if (objectType == VK_OBJECT_TYPE_INSTANCE &&
                    request->context->debugReportCallback) {
                vulkanWrapper.vkDestroyDebugReportCallback(
                        (VkInstance)(uintptr_t)hostValue,
                        request->context->debugReportCallback, NULL);
                request->context->debugReportCallback = VK_NULL_HANDLE;
            }
#endif
            if (objectType == VK_OBJECT_TYPE_IMAGE &&
                    request->context->textureDecoder &&
                    TextureDecoder_containsImage(
                            request->context->textureDecoder,
                            (VkImage)(uintptr_t)hostValue)) {
                TextureDecoder_destroyImage(request->context->textureDecoder,
                        device, (VkImage)(uintptr_t)hostValue);
            }
            else if (objectType != VK_OBJECT_TYPE_QUEUE)
                destroyVkObject(objectType, device, (void*)(uintptr_t)hostValue);
            break;
        case VORTEK_HANDLE_ROLE_RESOURCE_MEMORY:
            ResourceMemory_free(request->context, device,
                    (ResourceMemory*)(uintptr_t)hostValue);
            break;
        case VORTEK_HANDLE_ROLE_SHADER_MODULE:
            destroyVkObject(VK_OBJECT_TYPE_SHADER_MODULE, device,
                    (void*)(uintptr_t)hostValue);
            break;
        case VORTEK_HANDLE_ROLE_XWINDOW_SWAPCHAIN:
            XWindowSwapchain_destroy(device,
                    (XWindowSwapchain*)(uintptr_t)hostValue);
            break;
        case VORTEK_HANDLE_ROLE_WINDOW_ID:
            break;
    }
}

bool vt_request_publish_vulkan_batch(
        VtRequestDecode* request, VkObjectType objectType,
        void* hostHandlesInOut, size_t handleSize, size_t count,
        uint64_t instanceOwner, uint64_t deviceOwner, uint64_t parentOwner,
        VtRequestPublicationCleanup cleanup,
        uint64_t hostDeviceValue, uint64_t hostParentValue) {
    if (!request || !request->context || !request->context->handleAuthority ||
            (count != 0 && !hostHandlesInOut) || handleSize != sizeof(uint64_t)) {
        return false;
    }
    if (count == 0) return true;
    if (request->publication.active ||
            count > VT_DECODE_MAX_ELEMENTS ||
            count > SIZE_MAX / sizeof(uint64_t)) return false;
    uint64_t* hostBits = vt_request_output_alloc(
            request, count * sizeof(*hostBits));
    uint64_t* wireTokens = vt_request_output_alloc(
            request, count * sizeof(*wireTokens));
    if (!hostBits || !wireTokens) return false;
    for (size_t index = 0; index < count; ++index) {
        memcpy(&hostBits[index],
                (uint8_t*)hostHandlesInOut + index * handleSize, handleSize);
    }
    VortekHandleOwner owner = {
        .instance = instanceOwner,
        .device = deviceOwner,
        .parent = parentOwner,
    };
    VortekHandleStatus status = VkObjectAuthority_publishVulkanBatch(
            request->context->handleAuthority, objectType, hostBits,
            count, owner, wireTokens);
    if (status != VORTEK_HANDLE_OK) return false;
    request->publication = (VtRequestPublication) {
        .cleanup = cleanup,
        .objectType = objectType,
        .role = VORTEK_HANDLE_ROLE_VULKAN,
        .owner = owner,
        .hostDeviceValue = hostDeviceValue,
        .hostParentValue = hostParentValue,
        .hostValues = hostBits,
        .wireTokens = wireTokens,
        .count = count,
        .active = true,
    };
    for (size_t index = 0; index < count; ++index) {
        memcpy((uint8_t*)hostHandlesInOut + index * handleSize,
                &wireTokens[index], handleSize);
    }
    return true;
}

static void vt_request_commit_response(VtRequestDecode* request) {
    if (!request) return;
    memset(&request->publication, 0, sizeof(request->publication));
}

void vt_request_response_abort(
        VtRequestDecode* request, VtDecodeError error) {
    if (!request || !request->context) return;
    VtRequestPublication publication = request->publication;
    memset(&request->publication, 0, sizeof(request->publication));
    bool retired = false;
    if (publication.active && request->context->handleAuthority) {
        VortekHandleExpectation expectation = {
            .contextGeneration = request->context->contextGeneration,
            .role = publication.role,
            .vulkanType = publication.objectType,
            .owner = publication.owner,
            .requireInstanceOwner = publication.owner.instance != 0,
            .requireDeviceOwner = publication.owner.device != 0,
            .requireParentOwner = publication.owner.parent != 0,
            .allowNull = false,
        };
        if (publication.cleanup == VT_REQUEST_PUBLICATION_SINGLE) {
            retired = VkObjectAuthority_tombstone(
                    request->context->handleAuthority,
                    publication.wireToken, &expectation, NULL) ==
                    VORTEK_HANDLE_OK;
        }
        else {
            retired = VkObjectAuthority_tombstoneBatch(
                    request->context->handleAuthority,
                    publication.wireTokens, publication.count,
                    &expectation) == VORTEK_HANDLE_OK;
        }
    }
    if (retired) {
        VkDevice device = (VkDevice)(uintptr_t)publication.hostDeviceValue;
        if (publication.cleanup == VT_REQUEST_PUBLICATION_SINGLE) {
            vt_request_rollback_output(request, publication.objectType,
                    publication.role, publication.hostValue,
                    publication.hostDeviceValue);
        }
        else if (publication.cleanup == VT_REQUEST_PUBLICATION_DESCRIPTOR_SETS) {
            (void)vulkanWrapper.vkFreeDescriptorSets(device,
                    (VkDescriptorPool)(uintptr_t)publication.hostParentValue,
                    (uint32_t)publication.count,
                    (const VkDescriptorSet*)publication.hostValues);
        }
        else if (publication.cleanup == VT_REQUEST_PUBLICATION_COMMAND_BUFFERS) {
            vulkanWrapper.vkFreeCommandBuffers(device,
                    (VkCommandPool)(uintptr_t)publication.hostParentValue,
                    (uint32_t)publication.count,
                    (const VkCommandBuffer*)publication.hostValues);
        }
    }
    vt_request_protocol_error(request->context, error);
}

bool vt_request_send_response(
        VtRequestDecode* request, int requestCode,
        const void* data, int size) {
    if (!request || !request->context || !request->context->clientRing ||
            (size > 0 && !data) ||
            !vt_transport_payload_fits(size,
                    request->context->clientRing->bufferSize,
                    CLIENT_RING_BUFFER_SIZE - HEADER_SIZE)) {
        vt_request_response_abort(request, VT_DECODE_ERROR_LIMIT);
        return false;
    }
    uint8_t header[8];
    memcpy(header, &requestCode, sizeof(requestCode));
    memcpy(header + sizeof(requestCode), &size, sizeof(size));
    if (!RingBuffer_writeFrame(request->context->clientRing,
            header, HEADER_SIZE, data, (uint32_t)size)) {
        vt_request_response_abort(request, VT_DECODE_ERROR_ARGUMENT);
        return false;
    }
    vt_request_commit_response(request);
    return true;
}

static bool vt_request_send_fds_response(
        VtRequestDecode* request, int* fds, int fdCount,
        const void* data, int size) {
    if (!request || !request->context || fdCount < 0 || fdCount > MAX_FDS ||
            (fdCount > 0 && !fds) || size < 0 || (size > 0 && !data)) {
        vt_request_response_abort(request, VT_DECODE_ERROR_LIMIT);
        return false;
    }
    const int expected = size > 0 ? size : 1;
    if (send_fds(request->context->clientFd, fds, fdCount,
            (void*)data, size) != expected) {
        vt_request_response_abort(request, VT_DECODE_ERROR_ARGUMENT);
        return false;
    }
    vt_request_commit_response(request);
    return true;
}

bool vt_request_capture_begin(
        VtRequestDecode* request, VkObjectType objectType,
        uint64_t* wireTokens, size_t capacity) {
    if (!request || !request->state ||
            (capacity != 0 && !wireTokens)) return false;
    vt_decode_capture_handle_tokens(
            request->state, (uint32_t)objectType, wireTokens, capacity);
    return true;
}

bool vt_request_capture_complete(
        VtRequestDecode* request, size_t expectedCount) {
    if (!request || !request->state) return false;
    const bool complete =
            vt_decode_captured_handle_count(request->state) == expectedCount;
    vt_decode_capture_handle_tokens(request->state, 0, NULL, 0);
    return complete;
}

bool vt_request_tombstone_batch(
        VtRequestDecode* request, const uint64_t* wireTokens, size_t count,
        VkObjectType objectType, uint64_t parentOwner) {
    if (!request || !request->state || !request->context ||
            !request->context->handleAuthority ||
            (count != 0 && !wireTokens)) return false;
    VortekHandleExpectation expectation = {
        .contextGeneration = request->context->contextGeneration,
        .role = VORTEK_HANDLE_ROLE_VULKAN,
        .vulkanType = objectType,
        .owner = {
            .instance = request->state->instance_owner,
            .device = request->state->device_owner,
            .parent = parentOwner,
        },
        .requireInstanceOwner = true,
        .requireDeviceOwner = true,
        .requireParentOwner = parentOwner != 0,
        .allowNull = false,
    };
    return VkObjectAuthority_tombstoneBatch(
            request->context->handleAuthority, wireTokens,
            count, &expectation) == VORTEK_HANDLE_OK;
}

bool vt_request_validate_batch(
        VtRequestDecode* request, const uint64_t* wireTokens, size_t count,
        VkObjectType objectType, uint64_t parentOwner) {
    if (!request || !request->state || !request->context ||
            !request->context->handleAuthority ||
            (count != 0 && !wireTokens)) return false;
    VortekHandleExpectation expectation = {
        .contextGeneration = request->context->contextGeneration,
        .role = VORTEK_HANDLE_ROLE_VULKAN,
        .vulkanType = objectType,
        .owner = {
            .instance = request->state->instance_owner,
            .device = request->state->device_owner,
            .parent = parentOwner,
        },
        .requireInstanceOwner = true,
        .requireDeviceOwner = true,
        .requireParentOwner = parentOwner != 0,
        .allowNull = false,
    };
    return VkObjectAuthority_validateBatch(
            request->context->handleAuthority, wireTokens,
            count, &expectation) == VORTEK_HANDLE_OK;
}

bool vt_request_tombstone_children(
        VtRequestDecode* request, uint64_t parentOwner) {
    return request && request->context && request->context->handleAuthority &&
            VkObjectAuthority_tombstoneChildren(
                    request->context->handleAuthority, parentOwner) ==
                    VORTEK_HANDLE_OK;
}

void vt_request_protocol_error(VkContext* context, VtDecodeError error) {
    (void)error;
    VkContext_requestStop(context, VK_ERROR_DEVICE_LOST);
}

uint32_t vt_request_query_copy_count(uint32_t guestCapacity, uint32_t serverActual) {
    return guestCapacity < serverActual ? guestCapacity : serverActual;
}

VkResult vt_request_query_result(
        VkResult serverResult, bool guestArray,
        uint32_t guestCapacity, uint32_t serverActual) {
    if (serverResult < 0) return serverResult;
    return guestArray && guestCapacity < serverActual ? VK_INCOMPLETE : serverResult;
}
'''


def replace_handler(text: str, name: str, replacement: str) -> str:
    match = re.search(rf"^void {re.escape(name)}\(VkContext\* context\) \{{", text, re.M)
    if not match:
        raise ValueError(f"missing handler {name}")
    end = brace_end(text, match.end())
    return text[:match.start()] + replacement + text[end:]


def harden_query_capacities(text: str) -> str:
    replacements = {
"""    VT_REQUEST_ARRAY(VkPhysicalDevice, physicalDevices, physicalDeviceCount);
    VkResult result = vulanWrapper.vkEnumeratePhysicalDevices(instance, &physicalDeviceCount, physicalDevices);

    VT_SERIALIZE_CMD(vkEnumeratePhysicalDevices, VK_NULL_HANDLE, &physicalDeviceCount, physicalDevices);""".replace("vulanWrapper", "vulkanWrapper"): """    const uint32_t guestCapacity = physicalDeviceCount;
    uint32_t serverActual = 0;
    VkResult result = vulkanWrapper.vkEnumeratePhysicalDevices(instance, &serverActual, NULL);
    const uint32_t returnedCount = guestCapacity > 0
            ? vt_request_query_copy_count(guestCapacity, serverActual) : 0;
    VT_REQUEST_ARRAY(VkPhysicalDevice, physicalDevices, returnedCount);
    uint32_t hostCount = returnedCount;
    if (physicalDevices) result = vulkanWrapper.vkEnumeratePhysicalDevices(
            instance, &hostCount, physicalDevices);
    physicalDeviceCount = guestCapacity == 0 ? serverActual :
            vt_request_query_copy_count(guestCapacity, hostCount);
    result = vt_request_query_result(result, guestCapacity != 0, guestCapacity, serverActual);

    VT_SERIALIZE_CMD(vkEnumeratePhysicalDevices, VK_NULL_HANDLE, &physicalDeviceCount, physicalDevices);""",
"""    VT_REQUEST_ARRAY(VkQueueFamilyProperties, queueFamilyProperties, queueFamilyPropertyCount);
    vulkanWrapper.vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, &queueFamilyPropertyCount, queueFamilyProperties);

    VT_SERIALIZE_CMD(vkGetPhysicalDeviceQueueFamilyProperties, NULL, &queueFamilyPropertyCount, queueFamilyProperties);""": """    const uint32_t guestCapacity = queueFamilyPropertyCount;
    uint32_t serverActual = 0;
    vulkanWrapper.vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, &serverActual, NULL);
    VT_REQUEST_ARRAY(VkQueueFamilyProperties, queueFamilyProperties,
            guestCapacity > 0 ? serverActual : 0);
    uint32_t hostCount = serverActual;
    if (queueFamilyProperties) vulkanWrapper.vkGetPhysicalDeviceQueueFamilyProperties(
            physicalDevice, &hostCount, queueFamilyProperties);
    queueFamilyPropertyCount = guestCapacity == 0 ? serverActual :
            vt_request_query_copy_count(guestCapacity, hostCount);

    VT_SERIALIZE_CMD(vkGetPhysicalDeviceQueueFamilyProperties, NULL, &queueFamilyPropertyCount, queueFamilyProperties);""",
"""    VT_REQUEST_ARRAY(VkSparseImageMemoryRequirements, requirements, requirementCount);
    vulkanWrapper.vkGetImageSparseMemoryRequirements(device, image, &requirementCount, requirements);

    VT_SERIALIZE_CMD(vkGetImageSparseMemoryRequirements, VK_NULL_HANDLE, VK_NULL_HANDLE, &requirementCount, requirements);""": """    const uint32_t guestCapacity = requirementCount;
    uint32_t serverActual = 0;
    vulkanWrapper.vkGetImageSparseMemoryRequirements(device, image, &serverActual, NULL);
    VT_REQUEST_ARRAY(VkSparseImageMemoryRequirements, requirements,
            guestCapacity > 0 ? serverActual : 0);
    uint32_t hostCount = serverActual;
    if (requirements) vulkanWrapper.vkGetImageSparseMemoryRequirements(
            device, image, &hostCount, requirements);
    requirementCount = guestCapacity == 0 ? serverActual :
            vt_request_query_copy_count(guestCapacity, hostCount);

    VT_SERIALIZE_CMD(vkGetImageSparseMemoryRequirements, VK_NULL_HANDLE, VK_NULL_HANDLE, &requirementCount, requirements);""",
"""    VT_REQUEST_ARRAY(VkSparseImageFormatProperties, properties, propertyCount);
    vulkanWrapper.vkGetPhysicalDeviceSparseImageFormatProperties(physicalDevice, format, type, samples, usage, tiling, &propertyCount, properties);

    VT_SERIALIZE_CMD(vkGetPhysicalDeviceSparseImageFormatProperties, VK_NULL_HANDLE, format, type, samples, usage, tiling, &propertyCount, properties);""": """    const uint32_t guestCapacity = propertyCount;
    uint32_t serverActual = 0;
    vulkanWrapper.vkGetPhysicalDeviceSparseImageFormatProperties(
            physicalDevice, format, type, samples, usage, tiling, &serverActual, NULL);
    VT_REQUEST_ARRAY(VkSparseImageFormatProperties, properties,
            guestCapacity > 0 ? serverActual : 0);
    uint32_t hostCount = serverActual;
    if (properties) vulkanWrapper.vkGetPhysicalDeviceSparseImageFormatProperties(
            physicalDevice, format, type, samples, usage, tiling, &hostCount, properties);
    propertyCount = guestCapacity == 0 ? serverActual :
            vt_request_query_copy_count(guestCapacity, hostCount);

    VT_SERIALIZE_CMD(vkGetPhysicalDeviceSparseImageFormatProperties, VK_NULL_HANDLE, format, type, samples, usage, tiling, &propertyCount, properties);""",
"""    VT_REQUEST_ARRAY(VkQueueFamilyProperties2, queueFamilyProperties, queueFamilyPropertyCount);
    if (queueFamilyProperties) VT_REQUEST_DECODE(vt_unserialize_vkGetPhysicalDeviceQueueFamilyProperties2(VK_NULL_HANDLE, NULL, queueFamilyProperties, &_vt_cursor, &context->memoryPool));

    vulkanWrapper.vkGetPhysicalDeviceQueueFamilyProperties2(physicalDevice, &queueFamilyPropertyCount, queueFamilyProperties);

    VT_SERIALIZE_CMD(vkGetPhysicalDeviceQueueFamilyProperties2, VK_NULL_HANDLE, &queueFamilyPropertyCount, queueFamilyProperties);""": """    const uint32_t guestCapacity = queueFamilyPropertyCount;
    uint32_t serverActual = 0;
    vulkanWrapper.vkGetPhysicalDeviceQueueFamilyProperties2(physicalDevice, &serverActual, NULL);
    VT_REQUEST_ARRAY(VkQueueFamilyProperties2, queueFamilyProperties,
            vt_request_query_storage_count_inline(
                    guestCapacity, serverActual));
    if (queueFamilyProperties) VT_REQUEST_DECODE(vt_unserialize_vkGetPhysicalDeviceQueueFamilyProperties2(VK_NULL_HANDLE, NULL, queueFamilyProperties, &_vt_cursor, &context->memoryPool));
    if (queueFamilyProperties) {
        for (uint32_t i = guestCapacity; i < serverActual; i++)
            queueFamilyProperties[i].sType = VK_STRUCTURE_TYPE_QUEUE_FAMILY_PROPERTIES_2;
    }
    uint32_t hostCount = serverActual;
    if (queueFamilyProperties) vulkanWrapper.vkGetPhysicalDeviceQueueFamilyProperties2(
            physicalDevice, &hostCount, queueFamilyProperties);
    queueFamilyPropertyCount = guestCapacity == 0 ? serverActual :
            vt_request_query_copy_count(guestCapacity, hostCount);

    VT_SERIALIZE_CMD(vkGetPhysicalDeviceQueueFamilyProperties2, VK_NULL_HANDLE, &queueFamilyPropertyCount, queueFamilyProperties);""",
"""    VT_REQUEST_ARRAY(VkSparseImageFormatProperties2, properties, propertyCount);
    if (properties) VT_REQUEST_DECODE(vt_unserialize_vkGetPhysicalDeviceSparseImageFormatProperties2(VK_NULL_HANDLE, NULL, NULL, properties, &_vt_cursor, &context->memoryPool));
    
    vulkanWrapper.vkGetPhysicalDeviceSparseImageFormatProperties2(physicalDevice, &formatInfo, &propertyCount, properties);

    VT_SERIALIZE_CMD(vkGetPhysicalDeviceSparseImageFormatProperties2, VK_NULL_HANDLE, NULL, &propertyCount, properties);""": """    const uint32_t guestCapacity = propertyCount;
    uint32_t serverActual = 0;
    vulkanWrapper.vkGetPhysicalDeviceSparseImageFormatProperties2(
            physicalDevice, &formatInfo, &serverActual, NULL);
    VT_REQUEST_ARRAY(VkSparseImageFormatProperties2, properties,
            vt_request_query_storage_count_inline(
                    guestCapacity, serverActual));
    if (properties) VT_REQUEST_DECODE(vt_unserialize_vkGetPhysicalDeviceSparseImageFormatProperties2(VK_NULL_HANDLE, NULL, NULL, properties, &_vt_cursor, &context->memoryPool));
    if (properties) {
        for (uint32_t i = guestCapacity; i < serverActual; i++)
            properties[i].sType = VK_STRUCTURE_TYPE_SPARSE_IMAGE_FORMAT_PROPERTIES_2;
    }
    uint32_t hostCount = serverActual;
    if (properties) vulkanWrapper.vkGetPhysicalDeviceSparseImageFormatProperties2(
            physicalDevice, &formatInfo, &hostCount, properties);
    propertyCount = guestCapacity == 0 ? serverActual :
            vt_request_query_copy_count(guestCapacity, hostCount);

    VT_SERIALIZE_CMD(vkGetPhysicalDeviceSparseImageFormatProperties2, VK_NULL_HANDLE, NULL, &propertyCount, properties);""",
    }
    for old, new in replacements.items():
        if old not in text:
            raise ValueError(f"query capacity pattern drifted: {old[:80]!r}")
        text = text.replace(old, new, 1)

    text = text.replace(
"""    VT_REQUEST_ARRAY(VkPhysicalDeviceGroupProperties, physicalDeviceGroupProperties, physicalDeviceGroupCount);

    if (physicalDeviceGroupProperties) {
        for (int i = 0; i < physicalDeviceGroupCount; i++) physicalDeviceGroupProperties[i].sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_GROUP_PROPERTIES;
    }

    VkResult result = vulkanWrapper.vkEnumeratePhysicalDeviceGroups(instance, &physicalDeviceGroupCount, physicalDeviceGroupProperties);

    VT_SERIALIZE_CMD(vkEnumeratePhysicalDeviceGroups, VK_NULL_HANDLE, &physicalDeviceGroupCount, physicalDeviceGroupProperties);""",
"""    const uint32_t guestCapacity = physicalDeviceGroupCount;
    uint32_t serverActual = 0;
    VkResult result = vulkanWrapper.vkEnumeratePhysicalDeviceGroups(instance, &serverActual, NULL);
    const uint32_t returnedGroupCount = guestCapacity > 0
            ? vt_request_query_copy_count(guestCapacity, serverActual) : 0;
    VT_REQUEST_ARRAY(VkPhysicalDeviceGroupProperties,
            physicalDeviceGroupProperties, returnedGroupCount);
    for (uint32_t i = 0; i < returnedGroupCount; i++)
        physicalDeviceGroupProperties[i].sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_GROUP_PROPERTIES;
    uint32_t hostCount = returnedGroupCount;
    if (physicalDeviceGroupProperties) result = vulkanWrapper.vkEnumeratePhysicalDeviceGroups(
            instance, &hostCount, physicalDeviceGroupProperties);
    physicalDeviceGroupCount = guestCapacity == 0 ? serverActual :
            vt_request_query_copy_count(guestCapacity, hostCount);
    result = vt_request_query_result(result, guestCapacity != 0, guestCapacity, serverActual);

    VT_SERIALIZE_CMD(vkEnumeratePhysicalDeviceGroups, VK_NULL_HANDLE, &physicalDeviceGroupCount, physicalDeviceGroupProperties);""", 1)

    sparse2 = [
        ("vkGetImageSparseMemoryRequirements2", "device, &requirementsInfo"),
        ("vkGetDeviceImageSparseMemoryRequirements", "device, &requirementsInfo"),
    ]
    for command, host_args in sparse2:
        old = f"""    VT_REQUEST_ARRAY(VkSparseImageMemoryRequirements2, requirements, requirementCount);
    if (requirements) VT_REQUEST_DECODE(vt_unserialize_{command}(VK_NULL_HANDLE, NULL, NULL, requirements, &_vt_cursor, &context->memoryPool));

    vulkanWrapper.{command}({host_args}, &requirementCount, requirements);

    VT_SERIALIZE_CMD({command}, VK_NULL_HANDLE, NULL, &requirementCount, requirements);"""
        new = f"""    const uint32_t guestCapacity = requirementCount;
    uint32_t serverActual = 0;
    vulkanWrapper.{command}({host_args}, &serverActual, NULL);
    VT_REQUEST_ARRAY(VkSparseImageMemoryRequirements2, requirements,
            vt_request_query_storage_count_inline(
                    guestCapacity, serverActual));
    if (requirements) VT_REQUEST_DECODE(vt_unserialize_{command}(VK_NULL_HANDLE, NULL, NULL, requirements, &_vt_cursor, &context->memoryPool));
    if (requirements) {{
        for (uint32_t i = guestCapacity; i < serverActual; i++)
            requirements[i].sType = VK_STRUCTURE_TYPE_SPARSE_IMAGE_MEMORY_REQUIREMENTS_2;
    }}
    uint32_t hostCount = serverActual;
    if (requirements) vulkanWrapper.{command}({host_args}, &hostCount, requirements);
    requirementCount = guestCapacity == 0 ? serverActual :
            vt_request_query_copy_count(guestCapacity, hostCount);

    VT_SERIALIZE_CMD({command}, VK_NULL_HANDLE, NULL, &requirementCount, requirements);"""
        if old not in text:
            raise ValueError(f"query capacity pattern drifted: {command}")
        text = text.replace(old, new, 1)

    text = text.replace(
"""    VT_REQUEST_ARRAY(VkTimeDomainKHR, timeDomains, timeDomainCount);
    VkResult result = vulkanWrapper.vkGetPhysicalDeviceCalibrateableTimeDomains(physicalDevice, &timeDomainCount, timeDomains);

    VT_SERIALIZE_CMD(vkGetPhysicalDeviceCalibrateableTimeDomainsKHR, VK_NULL_HANDLE, &timeDomainCount, timeDomains);
    vt_send(context->clientRing, VK_SUCCESS, outputBuffer, bufferSize);""",
"""    const uint32_t guestCapacity = timeDomainCount;
    uint32_t serverActual = 0;
    VkResult result = vulkanWrapper.vkGetPhysicalDeviceCalibrateableTimeDomains(
            physicalDevice, &serverActual, NULL);
    VT_REQUEST_ARRAY(VkTimeDomainKHR, timeDomains, guestCapacity > 0 ? serverActual : 0);
    uint32_t hostCount = serverActual;
    if (timeDomains) result = vulkanWrapper.vkGetPhysicalDeviceCalibrateableTimeDomains(
            physicalDevice, &hostCount, timeDomains);
    timeDomainCount = guestCapacity == 0 ? serverActual :
            vt_request_query_copy_count(guestCapacity, hostCount);
    result = vt_request_query_result(result, guestCapacity != 0, guestCapacity, serverActual);

    VT_SERIALIZE_CMD(vkGetPhysicalDeviceCalibrateableTimeDomainsKHR, VK_NULL_HANDLE, &timeDomainCount, timeDomains);
    vt_send(context->clientRing, result, outputBuffer, bufferSize);""", 1)

    text = text.replace(
"""    VkResult result;

    uint32_t exposedExtensionCount;
    result = vulkanWrapper.vkEnumerateInstanceExtensionProperties(NULL, &exposedExtensionCount, NULL);

    VkExtensionProperties* exposedExtensions = vt_request_output_alloc(&_vt_request, (size_t)(propertyCount * sizeof(VkExtensionProperties)));
    result = vulkanWrapper.vkEnumerateInstanceExtensionProperties(NULL, &exposedExtensionCount, exposedExtensions);""",
"""    const uint32_t guestCapacity = propertyCount;
    uint32_t exposedExtensionCount = 0;
    VkResult result = vulkanWrapper.vkEnumerateInstanceExtensionProperties(
            NULL, &exposedExtensionCount, NULL);
    VkExtensionProperties* exposedExtensions = vt_request_output_alloc(
            &_vt_request, (size_t)exposedExtensionCount * sizeof(*exposedExtensions));
    if (result == VK_SUCCESS && exposedExtensionCount > 0 && !exposedExtensions) {
        vt_request_protocol_error(context, VT_DECODE_ERROR_OUT_OF_MEMORY);
        return;
    }
    if (result == VK_SUCCESS && exposedExtensions) result =
            vulkanWrapper.vkEnumerateInstanceExtensionProperties(
                    NULL, &exposedExtensionCount, exposedExtensions);""", 1)
    text = text.replace(
"""    bool nullProperties = propertyCount == 0;
    VkExtensionProperties* properties = !nullProperties ? exposedExtensions : NULL;
    if (nullProperties) propertyCount = exposedExtensionCount;

    VT_SERIALIZE_CMD(vkEnumerateInstanceExtensionProperties, NULL, &propertyCount, properties);""",
"""    VkExtensionProperties* properties = guestCapacity > 0 ? exposedExtensions : NULL;
    propertyCount = guestCapacity == 0 ? exposedExtensionCount :
            vt_request_query_copy_count(guestCapacity, exposedExtensionCount);
    result = vt_request_query_result(
            result, guestCapacity != 0, guestCapacity, exposedExtensionCount);

    VT_SERIALIZE_CMD(vkEnumerateInstanceExtensionProperties, NULL, &propertyCount, properties);""", 1)

    text = text.replace(
"""    bool nullProperties = propertyCount == 0;
    VkExtensionProperties* properties = getExposedDeviceExtensionProperties(context, physicalDevice, &propertyCount);
    VkResult result = properties ? VK_SUCCESS : VK_ERROR_OUT_OF_HOST_MEMORY;

    VT_SERIALIZE_CMD(vkEnumerateDeviceExtensionProperties, NULL, NULL, &propertyCount, !nullProperties ? properties : NULL);""",
"""    const uint32_t guestCapacity = propertyCount;
    uint32_t serverActual = 0;
    VkExtensionProperties* properties = getExposedDeviceExtensionProperties(
            context, physicalDevice, &serverActual);
    VkResult result = properties ? VK_SUCCESS : VK_ERROR_OUT_OF_HOST_MEMORY;
    propertyCount = guestCapacity == 0 ? serverActual :
            vt_request_query_copy_count(guestCapacity, serverActual);
    result = vt_request_query_result(result, guestCapacity != 0, guestCapacity, serverActual);

    VT_SERIALIZE_CMD(vkEnumerateDeviceExtensionProperties, NULL, NULL, &propertyCount, guestCapacity > 0 ? properties : NULL);""", 1)

    text = text.replace(
"""    VkImage* swapchainImages = swapchainImageCount > 0 ? vt_request_output_alloc(&_vt_request, (size_t)swapchain->imageCount * sizeof(*swapchainImages)) : NULL;
    if (swapchainImages) {
        for (int i = 0; i < swapchain->imageCount; i++) {
            swapchainImages[i] = swapchain->images[i].image;
        }
    }
    else swapchainImageCount = swapchain->imageCount;

    VT_SERIALIZE_CMD(vkGetSwapchainImagesKHR, NULL, VK_NULL_HANDLE, &swapchainImageCount, swapchainImages);
    vt_send(context->clientRing, VK_SUCCESS, outputBuffer, bufferSize);""",
"""    const uint32_t guestCapacity = swapchainImageCount;
    const uint32_t serverActual = (uint32_t)swapchain->imageCount;
    const uint32_t returnedCount = guestCapacity > 0
            ? vt_request_query_copy_count(guestCapacity, serverActual) : 0;
    VT_REQUEST_ARRAY(VkImage, swapchainImages, returnedCount);
    for (uint32_t i = 0; i < returnedCount; i++)
        swapchainImages[i] = swapchain->images[i].image;
    swapchainImageCount = guestCapacity == 0 ? serverActual :
            vt_request_query_copy_count(guestCapacity, serverActual);
    VkResult result = vt_request_query_result(
            VK_SUCCESS, guestCapacity != 0, guestCapacity, serverActual);

    VT_SERIALIZE_CMD(vkGetSwapchainImagesKHR, NULL, VK_NULL_HANDLE, &swapchainImageCount, swapchainImages);
    vt_send(context->clientRing, result, outputBuffer, bufferSize);""", 1)
    return text


OUTPUT_HANDLE_TYPES: dict[str, tuple[str, str]] = {
    "VkInstance": ("VK_OBJECT_TYPE_INSTANCE", "VORTEK_HANDLE_ROLE_VULKAN"),
    "VkDevice": ("VK_OBJECT_TYPE_DEVICE", "VORTEK_HANDLE_ROLE_VULKAN"),
    "VkQueue": ("VK_OBJECT_TYPE_QUEUE", "VORTEK_HANDLE_ROLE_VULKAN"),
    "VkDeviceMemory": ("VK_OBJECT_TYPE_UNKNOWN", "VORTEK_HANDLE_ROLE_RESOURCE_MEMORY"),
    "VkFence": ("VK_OBJECT_TYPE_FENCE", "VORTEK_HANDLE_ROLE_VULKAN"),
    "VkSemaphore": ("VK_OBJECT_TYPE_SEMAPHORE", "VORTEK_HANDLE_ROLE_VULKAN"),
    "VkEvent": ("VK_OBJECT_TYPE_EVENT", "VORTEK_HANDLE_ROLE_VULKAN"),
    "VkQueryPool": ("VK_OBJECT_TYPE_QUERY_POOL", "VORTEK_HANDLE_ROLE_VULKAN"),
    "VkBuffer": ("VK_OBJECT_TYPE_BUFFER", "VORTEK_HANDLE_ROLE_VULKAN"),
    "VkBufferView": ("VK_OBJECT_TYPE_BUFFER_VIEW", "VORTEK_HANDLE_ROLE_VULKAN"),
    "VkImage": ("VK_OBJECT_TYPE_IMAGE", "VORTEK_HANDLE_ROLE_VULKAN"),
    "VkImageView": ("VK_OBJECT_TYPE_IMAGE_VIEW", "VORTEK_HANDLE_ROLE_VULKAN"),
    "VkShaderModule": ("VK_OBJECT_TYPE_UNKNOWN", "VORTEK_HANDLE_ROLE_SHADER_MODULE"),
    "VkPipelineCache": ("VK_OBJECT_TYPE_PIPELINE_CACHE", "VORTEK_HANDLE_ROLE_VULKAN"),
    "VkPipelineLayout": ("VK_OBJECT_TYPE_PIPELINE_LAYOUT", "VORTEK_HANDLE_ROLE_VULKAN"),
    "VkSampler": ("VK_OBJECT_TYPE_SAMPLER", "VORTEK_HANDLE_ROLE_VULKAN"),
    "VkDescriptorSetLayout": ("VK_OBJECT_TYPE_DESCRIPTOR_SET_LAYOUT", "VORTEK_HANDLE_ROLE_VULKAN"),
    "VkDescriptorPool": ("VK_OBJECT_TYPE_DESCRIPTOR_POOL", "VORTEK_HANDLE_ROLE_VULKAN"),
    "VkFramebuffer": ("VK_OBJECT_TYPE_FRAMEBUFFER", "VORTEK_HANDLE_ROLE_VULKAN"),
    "VkRenderPass": ("VK_OBJECT_TYPE_RENDER_PASS", "VORTEK_HANDLE_ROLE_VULKAN"),
    "VkCommandPool": ("VK_OBJECT_TYPE_COMMAND_POOL", "VORTEK_HANDLE_ROLE_VULKAN"),
    "VkSwapchainKHR": ("VK_OBJECT_TYPE_UNKNOWN", "VORTEK_HANDLE_ROLE_XWINDOW_SWAPCHAIN"),
    "VkSamplerYcbcrConversion": ("VK_OBJECT_TYPE_SAMPLER_YCBCR_CONVERSION", "VORTEK_HANDLE_ROLE_VULKAN"),
}

DIRECT_HANDLE_OUTPUT = re.compile(
    r"(?m)^(?P<indent>\s*)VT_SERIALIZE_CMD\("
    r"(?P<type>Vk[A-Za-z0-9_]+), (?P<value>[^;\n]+)\);$"
)


def harden_handle_outputs(text: str) -> tuple[str, int]:
    count = 0
    for match in reversed(list(HANDLER.finditer(text))):
        end = brace_end(text, match.end())
        body = text[match.end():end - 1]
        outputs = [site for site in DIRECT_HANDLE_OUTPUT.finditer(body)
                   if site.group("type") in OUTPUT_HANDLE_TYPES]
        if not outputs:
            continue
        if len(outputs) != 1:
            raise ValueError(f"unexpected direct output count in {match.group('name')}")
        output = outputs[0]
        wire_type = output.group("type")
        object_type, role = OUTPUT_HANDLE_TYPES[wire_type]
        if wire_type == "VkInstance":
            instance_owner, device_owner = "0", "0"
            host_device = "VK_NULL_HANDLE"
        elif wire_type == "VkDevice":
            instance_owner, device_owner = "_vt_request.state->instance_owner", "0"
            host_device = "VK_NULL_HANDLE"
        else:
            if "uint64_t deviceId;" not in body:
                raise ValueError(
                    f"device-owned output lacks device token in {match.group('name')}")
            instance_owner = "_vt_request.state->instance_owner"
            device_owner = "deviceId"
            host_device = "device"
        value = output.group("value")
        value_names = re.findall(r"[A-Za-z_]\w*", value)
        value_name = value_names[-1] if value_names else ""
        plain_declaration = f"    {wire_type} {value_name};"
        if plain_declaration in body:
            body = body.replace(plain_declaration,
                    f"    {wire_type} {value_name} = VK_NULL_HANDLE;", 1)
            # Re-locate the output after changing text ahead of it.
            output = next(site for site in DIRECT_HANDLE_OUTPUT.finditer(body)
                          if site.group("type") == wire_type)
        elif wire_type == "VkShaderModule":
            wrapper_declaration = f"    ShaderModule* {value_name};"
            if wrapper_declaration in body:
                body = body.replace(wrapper_declaration,
                        f"    ShaderModule* {value_name} = NULL;", 1)
                output = next(site for site in DIRECT_HANDLE_OUTPUT.finditer(body)
                              if site.group("type") == wire_type)
        publish_host = value
        if match.group("name") == "vt_handle_vkAllocateMemory":
            publish_host = "resourceMemory"
        publish_macro = "VT_REQUEST_PUBLISH_RESULT" if "VkResult result" in body \
                else "VT_REQUEST_PUBLISH"
        result_argument = "result, " if publish_macro.endswith("_RESULT") else ""
        replacement = (
            f'{output.group("indent")}uint64_t _vt_wire_output = 0;\n'
            f'{output.group("indent")}{publish_macro}({result_argument}'
            f'{object_type}, {role}, '
            f'{publish_host}, {instance_owner}, {device_owner}, '
            f'{host_device}, _vt_wire_output);\n'
            f'{output.group("indent")}VT_SERIALIZE_CMD({wire_type}, '
            f'({wire_type})(uintptr_t)_vt_wire_output);'
        )
        body = body[:output.start()] + replacement + body[output.end():]
        text = text[:match.end()] + body + text[end - 1:]
        count += 1

    # Enumeration and allocation commands serialize handle arrays rather than
    # a direct VkFoo value.  Publish every returned element in place so the
    # pinned serializers continue emitting the exact same eight-byte fields.
    replacements = {
        "    VT_SERIALIZE_CMD(vkEnumeratePhysicalDevices, VK_NULL_HANDLE, &physicalDeviceCount, physicalDevices);":
        """    const uint32_t publishedCount = physicalDevices
            ? physicalDeviceCount : 0;
    if (result >= 0 && !vt_request_publish_vulkan_batch(&_vt_request,
            VK_OBJECT_TYPE_PHYSICAL_DEVICE, physicalDevices,
            sizeof(*physicalDevices), publishedCount, instanceId, 0, 0,
            VT_REQUEST_PUBLICATION_NONE, 0, 0)) {
        vt_request_protocol_error(context, VT_DECODE_ERROR_HANDLE_REJECTED);
        return;
    }
    if (result < 0 && physicalDevices)
        memset(physicalDevices, 0, physicalDeviceCount * sizeof(*physicalDevices));
    VT_SERIALIZE_CMD(vkEnumeratePhysicalDevices, VK_NULL_HANDLE, &physicalDeviceCount, physicalDevices);""",
        "    VT_SERIALIZE_CMD(vkAllocateCommandBuffers, VK_NULL_HANDLE, &allocateInfo, commandBuffers);":
        """    if (result == VK_SUCCESS && !vt_request_publish_vulkan_batch(&_vt_request,
            VK_OBJECT_TYPE_COMMAND_BUFFER, commandBuffers,
            sizeof(*commandBuffers), allocateInfo.commandBufferCount,
            _vt_request.state->instance_owner, deviceId, commandPoolToken,
            VT_REQUEST_PUBLICATION_COMMAND_BUFFERS,
            (uint64_t)(uintptr_t)device,
            (uint64_t)(uintptr_t)allocateInfo.commandPool)) {
        vulkanWrapper.vkFreeCommandBuffers(device, allocateInfo.commandPool,
                allocateInfo.commandBufferCount, commandBuffers);
        vt_request_protocol_error(context, VT_DECODE_ERROR_HANDLE_REJECTED);
        return;
    }
    if (result != VK_SUCCESS && commandBuffers)
        memset(commandBuffers, 0,
                allocateInfo.commandBufferCount * sizeof(*commandBuffers));
    VT_SERIALIZE_CMD(vkAllocateCommandBuffers, VK_NULL_HANDLE, &allocateInfo, commandBuffers);""",
        "    VT_SERIALIZE_CMD(vkGetSwapchainImagesKHR, NULL, VK_NULL_HANDLE, &swapchainImageCount, swapchainImages);":
        """    if (!vt_request_publish_vulkan_batch(&_vt_request,
            VK_OBJECT_TYPE_IMAGE, swapchainImages, sizeof(*swapchainImages),
            returnedCount, _vt_request.state->instance_owner,
            deviceId, swapchainId,
            VT_REQUEST_PUBLICATION_NONE, 0, 0)) {
        vt_request_protocol_error(context, VT_DECODE_ERROR_HANDLE_REJECTED);
        return;
    }
    VT_SERIALIZE_CMD(vkGetSwapchainImagesKHR, NULL, VK_NULL_HANDLE, &swapchainImageCount, swapchainImages);""",
        "    VT_SERIALIZE_CMD(vkEnumeratePhysicalDeviceGroups, VK_NULL_HANDLE, &physicalDeviceGroupCount, physicalDeviceGroupProperties);":
        """    const uint32_t publishedGroupCount =
            result >= 0 && physicalDeviceGroupProperties
            ? physicalDeviceGroupCount : 0;
    size_t totalPhysicalDevices = 0;
    for (uint32_t group = 0; group < publishedGroupCount; group++) {
        VkPhysicalDeviceGroupProperties* properties = &physicalDeviceGroupProperties[group];
        if (properties->physicalDeviceCount > VT_DECODE_MAX_ELEMENTS - totalPhysicalDevices) {
            vt_request_protocol_error(context, VT_DECODE_ERROR_LIMIT);
            return;
        }
        totalPhysicalDevices += properties->physicalDeviceCount;
    }
    VT_REQUEST_ARRAY(VkPhysicalDevice, allPhysicalDevices, totalPhysicalDevices);
    size_t physicalDeviceIndex = 0;
    for (uint32_t group = 0; group < publishedGroupCount; group++) {
        VkPhysicalDeviceGroupProperties* properties = &physicalDeviceGroupProperties[group];
        for (uint32_t i = 0; i < properties->physicalDeviceCount; i++)
            allPhysicalDevices[physicalDeviceIndex++] = properties->physicalDevices[i];
    }
    if (result >= 0 && !vt_request_publish_vulkan_batch(&_vt_request,
            VK_OBJECT_TYPE_PHYSICAL_DEVICE, allPhysicalDevices,
            sizeof(*allPhysicalDevices), totalPhysicalDevices, instanceId, 0, 0,
            VT_REQUEST_PUBLICATION_NONE, 0, 0)) {
        vt_request_protocol_error(context, VT_DECODE_ERROR_HANDLE_REJECTED);
        return;
    }
    physicalDeviceIndex = 0;
    for (uint32_t group = 0; group < publishedGroupCount; group++) {
        VkPhysicalDeviceGroupProperties* properties = &physicalDeviceGroupProperties[group];
        for (uint32_t i = 0; i < properties->physicalDeviceCount; i++)
            properties->physicalDevices[i] = result >= 0
                    ? allPhysicalDevices[physicalDeviceIndex++] : VK_NULL_HANDLE;
    }
    if (result < 0 && physicalDeviceGroupProperties)
        memset(physicalDeviceGroupProperties, 0,
                physicalDeviceGroupCount * sizeof(*physicalDeviceGroupProperties));
    VT_SERIALIZE_CMD(vkEnumeratePhysicalDeviceGroups, VK_NULL_HANDLE, &physicalDeviceGroupCount, physicalDeviceGroupProperties);""",
    }
    for old, new in replacements.items():
        if old not in text:
            raise ValueError(f"handle output pattern drifted: {old[:80]!r}")
        text = text.replace(old, new, 1)
        count += 1

    descriptor_old = """    int bufferSize = allocateInfo.descriptorSetCount * VK_HANDLE_BYTE_COUNT;
    char* outputBuffer = vt_request_output_alloc(&_vt_request, (size_t)(bufferSize));
    for (int i = 0, j = 0; i < allocateInfo.descriptorSetCount; i++, j += VK_HANDLE_BYTE_COUNT) {
        vt_serialize_VkDescriptorSet(descriptorSets[i], outputBuffer + j);
    }"""
    descriptor_new = """    int bufferSize = (int)((size_t)allocateInfo.descriptorSetCount * VK_HANDLE_BYTE_COUNT);
    if (result == VK_SUCCESS && !vt_request_publish_vulkan_batch(&_vt_request,
            VK_OBJECT_TYPE_DESCRIPTOR_SET, descriptorSets,
            sizeof(*descriptorSets), allocateInfo.descriptorSetCount,
            _vt_request.state->instance_owner, deviceId, descriptorPoolToken,
            VT_REQUEST_PUBLICATION_DESCRIPTOR_SETS,
            (uint64_t)(uintptr_t)device,
            (uint64_t)(uintptr_t)allocateInfo.descriptorPool)) {
        (void)vulkanWrapper.vkFreeDescriptorSets(device, allocateInfo.descriptorPool,
                allocateInfo.descriptorSetCount, descriptorSets);
        vt_request_protocol_error(context, VT_DECODE_ERROR_HANDLE_REJECTED);
        return;
    }
    char* outputBuffer = bufferSize > 0
            ? vt_request_output_alloc(&_vt_request, (size_t)bufferSize) : NULL;
    if (bufferSize > 0 && !outputBuffer) {
        vt_request_response_abort(&_vt_request,
                vt_decode_error(&(VtDecodeCursor){.state = _vt_request.state}));
        return;
    }
    if (result != VK_SUCCESS && descriptorSets)
        memset(descriptorSets, 0,
                allocateInfo.descriptorSetCount * sizeof(*descriptorSets));
    for (uint32_t i = 0; i < allocateInfo.descriptorSetCount; i++) {
        vt_serialize_VkDescriptorSet(descriptorSets[i],
                outputBuffer + (size_t)i * VK_HANDLE_BYTE_COUNT);
    }"""
    if descriptor_old not in text:
        raise ValueError("descriptor-set output pattern drifted")
    text = text.replace(descriptor_old, descriptor_new, 1)
    count += 1
    descriptor_preflight_old = """    VT_REQUEST_ARRAY(VkDescriptorSet, descriptorSets, allocateInfo.descriptorSetCount);
    VkResult result = vulkanWrapper.vkAllocateDescriptorSets(device, &allocateInfo, descriptorSets);"""
    descriptor_preflight_new = """    VT_REQUEST_ARRAY(VkDescriptorSet, descriptorSets, allocateInfo.descriptorSetCount);
    const size_t descriptorResponseSize =
            (size_t)allocateInfo.descriptorSetCount * VK_HANDLE_BYTE_COUNT;
    if (!vt_transport_size_fits(descriptorResponseSize,
            context->clientRing ? context->clientRing->bufferSize : 0u,
            CLIENT_RING_BUFFER_SIZE - HEADER_SIZE)) {
        vt_request_protocol_error(context, VT_DECODE_ERROR_LIMIT);
        return;
    }
    VkResult result = vulkanWrapper.vkAllocateDescriptorSets(device, &allocateInfo, descriptorSets);"""
    if descriptor_preflight_old not in text:
        raise ValueError("descriptor-set response preflight pattern drifted")
    text = text.replace(descriptor_preflight_old, descriptor_preflight_new, 1)
    return text, count


def harden_child_lifetimes(text: str) -> str:
    replacements = {
"""    VT_REQUEST_RETIRED_HANDLE(VkInstance, instance, instanceId, VK_OBJECT_TYPE_INSTANCE, VORTEK_HANDLE_ROLE_VULKAN, false, false, false);""":
"""    VT_REQUEST_AUTHORITY(VkContext_beginInstanceRetirement(
            context, instanceId));
    VT_REQUEST_HANDLE(VkInstance, instance, instanceId, VK_OBJECT_TYPE_INSTANCE, VORTEK_HANDLE_ROLE_VULKAN, false, false, false);""",
"""    VkCommandBufferAllocateInfo allocateInfo = {0};

    VT_REQUEST_DECODE(vt_unserialize_vkAllocateCommandBuffers((VkDevice)&deviceId, &allocateInfo, NULL, &_vt_cursor, &context->memoryPool));""":
"""    VkCommandBufferAllocateInfo allocateInfo = {0};
    uint64_t commandPoolToken = 0;

    VT_REQUEST_AUTHORITY(vt_request_capture_begin(&_vt_request,
            VK_OBJECT_TYPE_COMMAND_POOL, &commandPoolToken, 1));
    VT_REQUEST_DECODE(vt_unserialize_vkAllocateCommandBuffers((VkDevice)&deviceId, &allocateInfo, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_AUTHORITY(vt_request_capture_complete(&_vt_request, 1));""",
"""    VkDescriptorSetAllocateInfo allocateInfo = {0};

    VT_REQUEST_DECODE(vt_unserialize_vkAllocateDescriptorSets((VkDevice)&deviceId, &allocateInfo, NULL, &_vt_cursor, &context->memoryPool));""":
"""    VkDescriptorSetAllocateInfo allocateInfo = {0};
    uint64_t descriptorPoolToken = 0;

    VT_REQUEST_AUTHORITY(vt_request_capture_begin(&_vt_request,
            VK_OBJECT_TYPE_DESCRIPTOR_POOL, &descriptorPoolToken, 1));
    VT_REQUEST_DECODE(vt_unserialize_vkAllocateDescriptorSets((VkDevice)&deviceId, &allocateInfo, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_AUTHORITY(vt_request_capture_complete(&_vt_request, 1));""",
"""    VT_REQUEST_ARRAY(VkDescriptorSet, descriptorSets, descriptorSetCount);
    VT_REQUEST_DECODE(vt_unserialize_vkFreeDescriptorSets(VK_NULL_HANDLE, VK_NULL_HANDLE, NULL, descriptorSets, &_vt_cursor, &context->memoryPool));

    vulkanWrapper.vkFreeDescriptorSets(device, descriptorPool, descriptorSetCount, descriptorSets);""":
"""    VT_REQUEST_ARRAY(VkDescriptorSet, descriptorSets, descriptorSetCount);
    VT_REQUEST_ARRAY(uint64_t, descriptorSetTokens, descriptorSetCount);
    VT_REQUEST_AUTHORITY(vt_request_capture_begin(&_vt_request,
            VK_OBJECT_TYPE_DESCRIPTOR_SET, descriptorSetTokens,
            descriptorSetCount));
    VT_REQUEST_DECODE(vt_unserialize_vkFreeDescriptorSets(VK_NULL_HANDLE, VK_NULL_HANDLE, NULL, descriptorSets, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_AUTHORITY(vt_request_capture_complete(
            &_vt_request, descriptorSetCount));
    VT_REQUEST_AUTHORITY(vt_request_validate_batch(&_vt_request,
            descriptorSetTokens, descriptorSetCount,
            VK_OBJECT_TYPE_DESCRIPTOR_SET, descriptorPoolId));

    VkResult result = vulkanWrapper.vkFreeDescriptorSets(
            device, descriptorPool, descriptorSetCount, descriptorSets);
    if (result == VK_SUCCESS)
        VT_REQUEST_AUTHORITY(vt_request_tombstone_batch(&_vt_request,
                descriptorSetTokens, descriptorSetCount,
                VK_OBJECT_TYPE_DESCRIPTOR_SET, descriptorPoolId));""",
"""    VT_REQUEST_ARRAY(VkCommandBuffer, commandBuffers, commandBufferCount);
    VT_REQUEST_DECODE(vt_unserialize_vkFreeCommandBuffers(VK_NULL_HANDLE, VK_NULL_HANDLE, NULL, commandBuffers, &_vt_cursor, &context->memoryPool));

    vulkanWrapper.vkFreeCommandBuffers(device, commandPool, commandBufferCount, commandBuffers);""":
"""    VT_REQUEST_ARRAY(VkCommandBuffer, commandBuffers, commandBufferCount);
    VT_REQUEST_ARRAY(uint64_t, commandBufferTokens, commandBufferCount);
    VT_REQUEST_AUTHORITY(vt_request_capture_begin(&_vt_request,
            VK_OBJECT_TYPE_COMMAND_BUFFER, commandBufferTokens,
            commandBufferCount));
    VT_REQUEST_DECODE(vt_unserialize_vkFreeCommandBuffers(VK_NULL_HANDLE, VK_NULL_HANDLE, NULL, commandBuffers, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_AUTHORITY(vt_request_capture_complete(
            &_vt_request, commandBufferCount));
    VT_REQUEST_AUTHORITY(vt_request_tombstone_batch(&_vt_request,
            commandBufferTokens, commandBufferCount,
            VK_OBJECT_TYPE_COMMAND_BUFFER, commandPoolId));

    vulkanWrapper.vkFreeCommandBuffers(
            device, commandPool, commandBufferCount, commandBuffers);""",
"""    vulkanWrapper.vkDestroyDescriptorPool(device, descriptorPool, NULL);""":
"""    VT_REQUEST_AUTHORITY(vt_request_tombstone_children(
            &_vt_request, descriptorPoolId));
    vulkanWrapper.vkDestroyDescriptorPool(device, descriptorPool, NULL);""",
"""    vulkanWrapper.vkResetDescriptorPool(device, descriptorPool, flags);""":
"""    VkResult result = vulkanWrapper.vkResetDescriptorPool(
            device, descriptorPool, flags);
    if (result == VK_SUCCESS)
        VT_REQUEST_AUTHORITY(vt_request_tombstone_children(
                &_vt_request, descriptorPoolId));""",
"""    vulkanWrapper.vkDestroyCommandPool(device, commandPool, NULL);""":
"""    VT_REQUEST_AUTHORITY(vt_request_tombstone_children(
            &_vt_request, commandPoolId));
    vulkanWrapper.vkDestroyCommandPool(device, commandPool, NULL);""",
"""    vulkanWrapper.vkResetCommandPool(device, commandPool, flags);""":
"""    VkResult result = vulkanWrapper.vkResetCommandPool(
            device, commandPool, flags);
    if (result == VK_SUCCESS)
        VT_REQUEST_AUTHORITY(vt_request_tombstone_children(
                &_vt_request, commandPoolId));""",
"""    XWindowSwapchain_destroy(device, swapchain);""":
"""    VT_REQUEST_AUTHORITY(vt_request_tombstone_children(
            &_vt_request, swapchainId));
    XWindowSwapchain_destroy(device, swapchain);""",
"""    VT_REQUEST_RETIRED_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);

    vulkanWrapper.vkDestroyDevice(device, NULL);""":
"""    VT_REQUEST_AUTHORITY(VkContext_beginDeviceRetirement(
            context, deviceId));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);

    VT_REQUEST_AUTHORITY(VkContext_reclaimAuthority(
            context, VORTEK_HANDLE_DRAIN_DEVICE, deviceId));
    (void)device;""",
"""    vulkanWrapper.vkDestroyInstance(instance, NULL);""":
"""    VT_REQUEST_AUTHORITY(VkContext_reclaimAuthority(
            context, VORTEK_HANDLE_DRAIN_INSTANCE, instanceId));
    if (!VkContext_releaseWindowInstanceAuthority(context, instanceId))
        VkContext_requestStop(context, VK_ERROR_DEVICE_LOST);
    (void)instance;""",
    }
    for old, new in replacements.items():
        occurrences = text.count(old)
        if occurrences != 1:
            raise ValueError(
                f"child lifetime pattern expected once, found {occurrences}: {old[:80]!r}")
        text = text.replace(old, new, 1)
    return text


def harden_response_transport(text: str) -> tuple[str, int]:
    query_pool_old = """    void* data = vt_request_output_alloc(&_vt_request, (size_t)(dataSize));
    VkResult result = vulkanWrapper.vkGetQueryPoolResults(device, queryPool, firstQuery, queryCount, dataSize, data, stride, flags);"""
    query_pool_new = """    void* data = dataSize > 0
            ? vt_request_output_alloc(&_vt_request, (size_t)dataSize) : NULL;
    if (dataSize > 0 && !data) {
        vt_request_response_abort(&_vt_request,
                vt_decode_error(&(VtDecodeCursor){.state = _vt_request.state}));
        return;
    }
    VkResult result = vulkanWrapper.vkGetQueryPoolResults(device, queryPool, firstQuery, queryCount, dataSize, data, stride, flags);"""
    pipeline_old = """    void* data = dataSize > 0 ? vt_request_output_alloc(&_vt_request, (size_t)(dataSize)) : NULL;
    VkResult result = vulkanWrapper.vkGetPipelineCacheData(device, pipelineCache, &dataSize, data);"""
    pipeline_new = """    void* data = dataSize > 0
            ? vt_request_output_alloc(&_vt_request, (size_t)dataSize) : NULL;
    if (dataSize > 0 && !data) {
        vt_request_response_abort(&_vt_request,
                vt_decode_error(&(VtDecodeCursor){.state = _vt_request.state}));
        return;
    }
    VkResult result = vulkanWrapper.vkGetPipelineCacheData(device, pipelineCache, &dataSize, data);"""
    for old, new, label in (
            (query_pool_old, query_pool_new, "query-pool output"),
            (pipeline_old, pipeline_new, "pipeline-cache output")):
        if old not in text:
            raise ValueError(f"{label} pattern drifted")
        text = text.replace(old, new, 1)

    memory_fd = """    send_fds(context->clientFd, &resourceMemory->fd, 1, &result, sizeof(VkResult));"""
    memory_fd_checked = """    if (!vt_request_send_fds_response(&_vt_request,
            &resourceMemory->fd, result == VK_SUCCESS ? 1 : 0,
            &result, sizeof(result))) return;"""
    if text.count(memory_fd) != 2:
        raise ValueError("resource-memory fd response pattern drifted")
    text = text.replace(memory_fd, memory_fd_checked)

    wait_old = """    if (timeout != 0) {
        VkResult result = VK_SUCCESS;
        VT_REQUEST_ARRAY(int, fds, fenceCount);
        for (int i = 0; i < fenceCount; i++) {
            VkFenceGetFdInfoKHR getFdInfo = {0};
            getFdInfo.sType = VK_STRUCTURE_TYPE_FENCE_GET_FD_INFO_KHR;
            getFdInfo.fence = fences[i];
            getFdInfo.handleType = VK_EXTERNAL_FENCE_HANDLE_TYPE_SYNC_FD_BIT;

            result = vulkanWrapper.vkGetFenceFd(device, &getFdInfo, &fds[i]);
            if (result != VK_SUCCESS) break;
        }

        send_fds(context->clientFd, fds, fenceCount, &result, sizeof(VkResult));
        for (int i = 0; i < fenceCount; i++) CLOSEFD(fds[i]);
    }"""
    wait_new = """    if (timeout != 0) {
        if (fenceCount > MAX_FDS) {
            vt_request_protocol_error(context, VT_DECODE_ERROR_LIMIT);
            return;
        }
        VkResult result = VK_SUCCESS;
        VT_REQUEST_ARRAY(int, fds, fenceCount);
        for (uint32_t i = 0; i < fenceCount; i++) fds[i] = -1;
        uint32_t acquiredFdCount = 0;
        for (uint32_t i = 0; i < fenceCount; i++) {
            VkFenceGetFdInfoKHR getFdInfo = {0};
            getFdInfo.sType = VK_STRUCTURE_TYPE_FENCE_GET_FD_INFO_KHR;
            getFdInfo.fence = fences[i];
            getFdInfo.handleType = VK_EXTERNAL_FENCE_HANDLE_TYPE_SYNC_FD_BIT;

            result = vulkanWrapper.vkGetFenceFd(device, &getFdInfo, &fds[i]);
            if (result != VK_SUCCESS) break;
            acquiredFdCount++;
        }

        const bool sent = vt_request_send_fds_response(&_vt_request, fds,
                result == VK_SUCCESS ? (int)acquiredFdCount : 0,
                &result, sizeof(result));
        for (uint32_t i = 0; i < acquiredFdCount; i++) CLOSEFD(fds[i]);
        if (!sent) return;
    }"""
    if wait_old not in text:
        raise ValueError("fence fd response pattern drifted")
    text = text.replace(wait_old, wait_new, 1)

    for command in ("Semaphore", "Fence"):
        fd_old = f"""    int fd;
    VkResult result = vulkanWrapper.vkGet{command}Fd(device, &getFdInfo, &fd);
    send_fds(context->clientFd, &fd, 1, &result, sizeof(VkResult));
    CLOSEFD(fd);"""
        fd_new = f"""    int fd = -1;
    VkResult result = vulkanWrapper.vkGet{command}Fd(device, &getFdInfo, &fd);
    const bool sent = vt_request_send_fds_response(&_vt_request, &fd,
            result == VK_SUCCESS ? 1 : 0, &result, sizeof(result));
    CLOSEFD(fd);
    if (!sent) return;"""
        if fd_old not in text:
            raise ValueError(f"{command.lower()} fd response pattern drifted")
        text = text.replace(fd_old, fd_new, 1)

    if "send_fds(context->clientFd" in text:
        raise ValueError("raw synchronous fd response survived")

    raw = "vt_send(context->clientRing, "
    count = text.count(raw)
    if count == 0:
        raise ValueError("no synchronous response sends found")
    text = text.replace(raw, "VT_REQUEST_SEND(")
    if raw in text:
        raise ValueError("raw synchronous response send survived")
    return text, count


def transform(source: str, serializer: str) -> tuple[str, dict[str, int]]:
    text = source
    text = text.replace('#include "sysvshared_memory.h"\n', '#include "sysvshared_memory.h"\n' + RUNTIME + "\n")
    text = text.replace("void vt_handle_vkEndCommandBuffer(VkContext* context) {", preflight_function(serializer) + "void vt_handle_vkEndCommandBuffer(VkContext* context) {", 1)
    text = replace_handler(text, "vt_handle_vkEndCommandBuffer", END_HANDLER)
    text = replace_handler(text, "vt_handle_vkEnumerateInstanceVersion", r'''void vt_handle_vkEnumerateInstanceVersion(VkContext* context) {
    uint32_t requestedVersion = 0;
    vt_unserialize_vkEnumerateInstanceVersion(&requestedVersion,
            context->inputBuffer, &context->memoryPool);
    (void)requestedVersion;
    vt_send(context->clientRing, VK_SUCCESS, &context->vkMaxVersion, 4);
}''')
    text = replace_handler(text, "vt_handle_vkUnmapMemory", r'''void vt_handle_vkUnmapMemory(VkContext* context) {
    uint64_t deviceId;
    uint64_t memoryId;
    vt_unserialize_vkUnmapMemory((VkDevice)&deviceId,
            (VkDeviceMemory)&memoryId,
            context->inputBuffer, &context->memoryPool);
    VkDevice device = VkObject_fromId(deviceId);
    ResourceMemory* resourceMemory = VkObject_fromId(memoryId);
    /* ResourceMemory mappings are persistent fd-backed mappings; unmap is a
     * transport no-op, but both authorities must still be exact and live. */
    (void)device;
    (void)resourceMemory;
}''')
    text = replace_handler(text, "vt_handle_vkCreateGraphicsPipelines", r'''void vt_handle_vkCreateGraphicsPipelines(VkContext* context) {
    uint64_t deviceId = 0;
    uint64_t pipelineCacheId = 0;
    uint32_t createInfoCount = 0;
    vt_unserialize_vkCreateGraphicsPipelines(
            (VkDevice)&deviceId, (VkPipelineCache)&pipelineCacheId,
            &createInfoCount, NULL, NULL, NULL,
            context->inputBuffer, &context->memoryPool);
    VkDevice device = VkObject_fromId(deviceId);
    VkPipelineCache pipelineCache = VkObject_fromId(pipelineCacheId);
    if (!AsyncPipelineCreator_create(context, PIPELINE_TYPE_GRAPHICS,
            &_vt_request, deviceId, pipelineCacheId, createInfoCount,
            device, pipelineCache)) {
        vt_request_protocol_error(context, VT_DECODE_ERROR_HANDLE_REJECTED);
        return;
    }
}''')
    text = replace_handler(text, "vt_handle_vkCreateComputePipelines", r'''void vt_handle_vkCreateComputePipelines(VkContext* context) {
    uint64_t deviceId = 0;
    uint64_t pipelineCacheId = 0;
    uint32_t createInfoCount = 0;
    vt_unserialize_vkCreateComputePipelines(
            (VkDevice)&deviceId, (VkPipelineCache)&pipelineCacheId,
            &createInfoCount, NULL, NULL, NULL,
            context->inputBuffer, &context->memoryPool);
    VkDevice device = VkObject_fromId(deviceId);
    VkPipelineCache pipelineCache = VkObject_fromId(pipelineCacheId);
    if (!AsyncPipelineCreator_create(context, PIPELINE_TYPE_COMPUTE,
            &_vt_request, deviceId, pipelineCacheId, createInfoCount,
            device, pipelineCache)) {
        vt_request_protocol_error(context, VT_DECODE_ERROR_HANDLE_REJECTED);
        return;
    }
}''')
    text = replace_handler(text, "vt_handle_vkWaitSemaphores", r'''void vt_handle_vkWaitSemaphores(VkContext* context) {
    uint64_t deviceId = 0;
    uint64_t timeout = 0;
    vt_unserialize_vkWaitSemaphores((VkDevice)&deviceId, NULL, &timeout,
            context->inputBuffer, &context->memoryPool);
    VkDevice device = VkObject_fromId(deviceId);
    if (!TimelineSemaphore_asyncWait(
            context, &_vt_request, deviceId, device, timeout)) {
        vt_request_protocol_error(context, VT_DECODE_ERROR_HANDLE_REJECTED);
        return;
    }
}''')
    text = replace_handler(text, "vt_handle_vkGetPhysicalDeviceSurfaceCapabilitiesKHR", r'''void vt_handle_vkGetPhysicalDeviceSurfaceCapabilitiesKHR(VkContext* context) {
    uint64_t physicalDeviceId;
    uint64_t windowId;
    vt_unserialize_vkGetPhysicalDeviceSurfaceCapabilitiesKHR(
            (VkPhysicalDevice)&physicalDeviceId, (VkSurfaceKHR)&windowId,
            NULL, context->inputBuffer, &context->memoryPool);

    VkExtent2D windowSize = {0};
    if (!getWindowExtent(&context->jmethods, context->contextGeneration,
            _vt_request.state->instance_owner, (int)windowId, &windowSize)) {
        vt_send(context->clientRing, VK_ERROR_SURFACE_LOST_KHR, NULL, 0);
        return;
    }

    VkSurfaceCapabilitiesKHR surfaceCapabilities = {0};
    surfaceCapabilities.minImageCount = getSurfaceMinImageCount();
    surfaceCapabilities.maxImageCount = surfaceCapabilities.minImageCount == 1 ? 2 : 0;
    surfaceCapabilities.currentExtent = windowSize;
    surfaceCapabilities.minImageExtent = windowSize;
    surfaceCapabilities.maxImageExtent = windowSize;
    surfaceCapabilities.maxImageArrayLayers = 1;
    surfaceCapabilities.supportedTransforms = VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;
    surfaceCapabilities.currentTransform = VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;
    surfaceCapabilities.supportedCompositeAlpha = VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR |
                                                  VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR;
    surfaceCapabilities.supportedUsageFlags = VK_IMAGE_USAGE_TRANSFER_SRC_BIT |
                                              VK_IMAGE_USAGE_SAMPLED_BIT |
                                              VK_IMAGE_USAGE_TRANSFER_DST_BIT |
                                              VK_IMAGE_USAGE_STORAGE_BIT |
                                              VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT |
                                              VK_IMAGE_USAGE_INPUT_ATTACHMENT_BIT;

    VT_SERIALIZE_CMD(VkSurfaceCapabilitiesKHR, &surfaceCapabilities);
    vt_send(context->clientRing, VK_SUCCESS, outputBuffer, bufferSize);
}''')
    text = replace_handler(text, "vt_handle_vkGetPhysicalDeviceSurfaceFormatsKHR", r'''void vt_handle_vkGetPhysicalDeviceSurfaceFormatsKHR(VkContext* context) {
    uint64_t physicalDeviceId;
    uint64_t windowId;
    uint32_t surfaceFormatCount;
    vt_unserialize_vkGetPhysicalDeviceSurfaceFormatsKHR(
            (VkPhysicalDevice)&physicalDeviceId, (VkSurfaceKHR)&windowId,
            &surfaceFormatCount, NULL,
            context->inputBuffer, &context->memoryPool);

    const uint32_t guestCapacity = surfaceFormatCount;
    uint32_t serverActual = 0;
    VkSurfaceFormatKHR* supportedFormats = getSurfaceFormats(&serverActual);
    surfaceFormatCount = guestCapacity == 0 ? serverActual :
            vt_request_query_copy_count(guestCapacity, serverActual);
    VkResult result = vt_request_query_result(
            VK_SUCCESS, guestCapacity != 0, guestCapacity, serverActual);
    VkSurfaceFormatKHR* surfaceFormats = guestCapacity > 0
            ? supportedFormats : NULL;

    VT_SERIALIZE_CMD(vkGetPhysicalDeviceSurfaceFormatsKHR,
            VK_NULL_HANDLE, NULL, &surfaceFormatCount, surfaceFormats);
    vt_send(context->clientRing, result, outputBuffer, bufferSize);
}''')
    text = replace_handler(text, "vt_handle_vkGetPhysicalDeviceSurfacePresentModesKHR", r'''void vt_handle_vkGetPhysicalDeviceSurfacePresentModesKHR(VkContext* context) {
    static VkPresentModeKHR supportedPresentModes[] = {
        VK_PRESENT_MODE_IMMEDIATE_KHR,
        VK_PRESENT_MODE_MAILBOX_KHR,
        VK_PRESENT_MODE_FIFO_KHR,
        VK_PRESENT_MODE_FIFO_RELAXED_KHR,
    };
    uint64_t physicalDeviceId;
    uint64_t windowId;
    uint32_t presentModeCount;
    vt_unserialize_vkGetPhysicalDeviceSurfacePresentModesKHR(
            (VkPhysicalDevice)&physicalDeviceId, (VkSurfaceKHR)&windowId,
            &presentModeCount, NULL,
            context->inputBuffer, &context->memoryPool);

    const uint32_t guestCapacity = presentModeCount;
    const uint32_t serverActual = ARRAY_SIZE(supportedPresentModes);
    presentModeCount = guestCapacity == 0 ? serverActual :
            vt_request_query_copy_count(guestCapacity, serverActual);
    VkResult result = vt_request_query_result(
            VK_SUCCESS, guestCapacity != 0, guestCapacity, serverActual);
    VkPresentModeKHR* presentModes = guestCapacity > 0
            ? supportedPresentModes : NULL;

    VT_SERIALIZE_CMD(vkGetPhysicalDeviceSurfacePresentModesKHR,
            VK_NULL_HANDLE, NULL, &presentModeCount, presentModes);
    vt_send(context->clientRing, result, outputBuffer, bufferSize);
}''')
    text = replace_handler(text, "vt_handle_vkCreateSwapchainKHR", r'''void vt_handle_vkCreateSwapchainKHR(VkContext* context) {
    uint64_t deviceId;
    VkSwapchainCreateInfoKHR createInfo = {0};
    uint64_t windowId = 0;
    createInfo.surface = (VkSurfaceKHR)&windowId;

    vt_unserialize_vkCreateSwapchainKHR((VkDevice)&deviceId, &createInfo,
            NULL, NULL, context->inputBuffer, &context->memoryPool);
    VkDevice device = VkObject_fromId(deviceId);

    VkExtent2D windowSize = {0};
    XWindowSwapchain* swapchain = NULL;
    VkResult result = VK_ERROR_SURFACE_LOST_KHR;
    if (getWindowExtent(&context->jmethods, context->contextGeneration,
            _vt_request.state->instance_owner, (int)windowId, &windowSize) &&
            createInfo.imageExtent.width == windowSize.width &&
            createInfo.imageExtent.height == windowSize.height) {
        result = XWindowSwapchain_create(device, context->graphicsQueueIndex,
                &createInfo, &context->jmethods, context->contextGeneration,
                _vt_request.state->instance_owner, (int)windowId, &swapchain);
    }

    VT_SERIALIZE_CMD(VkSwapchainKHR, swapchain);
    vt_send(context->clientRing, result, outputBuffer, bufferSize);
}''')
    text = replace_handler(text, "vt_handle_vkQueuePresentKHR", r'''void vt_handle_vkQueuePresentKHR(VkContext* context) {
    VkPresentInfoKHR presentInfo = {0};
    vt_unserialize_VkPresentInfoKHR(
            &presentInfo, context->inputBuffer, &context->memoryPool);

    for (uint32_t i = 0; i < presentInfo.swapchainCount; i++) {
        if (!XWindowSwapchain_presentImage(
                (XWindowSwapchain*)presentInfo.pSwapchains[i])) {
            vt_request_protocol_error(context, VT_DECODE_ERROR_HANDLE_REJECTED);
            return;
        }
    }
}''')
    text = replace_handler(text, "vt_handle_vkGetPhysicalDevicePresentRectanglesKHR", r'''void vt_handle_vkGetPhysicalDevicePresentRectanglesKHR(VkContext* context) {
    uint64_t physicalDeviceId;
    uint64_t windowId;
    uint32_t rectCount;
    vt_unserialize_vkGetPhysicalDevicePresentRectanglesKHR(
            (VkPhysicalDevice)&physicalDeviceId, (VkSurfaceKHR)&windowId,
            &rectCount, NULL, context->inputBuffer, &context->memoryPool);

    const uint32_t guestCapacity = rectCount;
    VkRect2D rect = {0};
    if (guestCapacity > 0 && !getWindowExtent(
            &context->jmethods, context->contextGeneration,
            _vt_request.state->instance_owner, (int)windowId, &rect.extent)) {
        vt_send(context->clientRing, VK_ERROR_SURFACE_LOST_KHR, NULL, 0);
        return;
    }
    rectCount = 1;
    VkRect2D* rects = guestCapacity > 0 ? &rect : NULL;

    VT_SERIALIZE_CMD(vkGetPhysicalDevicePresentRectanglesKHR,
            VK_NULL_HANDLE, VK_NULL_HANDLE, &rectCount, rects);
    vt_send(context->clientRing, VK_SUCCESS, outputBuffer, bufferSize);
}''')

    total_calls = total_vlas = total_allocations = total_root_handles = 0
    matches = list(HANDLER.finditer(text))
    for match in reversed(matches):
        end = brace_end(text, match.end())
        body = text[match.end():end - 1]
        body, calls = checked_decoder_calls(body)
        body, vlas, allocations = replace_request_storage(body)
        total_calls += calls
        total_vlas += vlas
        total_allocations += allocations
        if calls and not body.startswith("\n    VT_REQUEST_BEGIN(context);"):
            body = "\n    VT_REQUEST_BEGIN(context);" + body
        body, root_handles = replace_root_handles(body, match.group("name"))
        total_root_handles += root_handles
        text = text[:match.end()] + body + text[end - 1:]

    text = re.sub(
        r"vt_alloc\(&context->memoryPool,\s*([^;\n]+)\)",
        r"vt_request_output_alloc(&_vt_request, (size_t)(\1))", text,
    )
    text = text.replace(
        "disableUnsupportedDeviceFeatures(physicalDevice, &createInfo);",
        "disableUnsupportedDeviceFeatures(context, physicalDevice, &createInfo);",
        1,
    )
    semaphore_leak = "vt_send(context->clientRing, result, &semaphore, sizeof(uint64_t));"
    if text.count(semaphore_leak) != 1:
        raise ValueError("semaphore counter response pattern drifted")
    text = text.replace(semaphore_leak,
        "vt_send(context->clientRing, result, &value, sizeof(value));", 1)
    text = text.replace(
        "VkImage* swapchainImages = swapchainImageCount > 0 ? calloc(swapchain->imageCount, sizeof(XWindowSwapchain)) : NULL;",
        "VkImage* swapchainImages = swapchainImageCount > 0 ? vt_request_output_alloc(&_vt_request, (size_t)swapchain->imageCount * sizeof(*swapchainImages)) : NULL;",
    )
    text = text.replace(
        "VkRect2D* rects = rectCount > 0 ? calloc(1, sizeof(VkPhysicalDeviceGroupProperties)) : NULL;",
        "VkRect2D* rects = rectCount > 0 ? vt_request_output_alloc(&_vt_request, (size_t)rectCount * sizeof(*rects)) : NULL;",
    )

    # A device is usable only if post-create capability initialization succeeds.
    old = """    VkDevice device;\n    VkResult result = vulkanWrapper.vkCreateDevice(physicalDevice, &createInfo, NULL, &device);\n    if (result == VK_SUCCESS) initVulkanDevice(context, physicalDevice, device);\n\n    VT_SERIALIZE_CMD(VkDevice, device);"""
    new = """    VkDevice device = VK_NULL_HANDLE;\n    VkResult result = vulkanWrapper.vkCreateDevice(physicalDevice, &createInfo, NULL, &device);\n    if (result == VK_SUCCESS) {\n        result = initVulkanDevice(context, physicalDevice, device);\n        if (result != VK_SUCCESS) {\n            vulkanWrapper.vkDestroyDevice(device, NULL);\n            device = VK_NULL_HANDLE;\n        }\n    }\n\n    VT_SERIALIZE_CMD(VkDevice, device);"""
    if old not in text:
        raise ValueError("CreateDevice capability gate drifted")
    text = text.replace(old, new, 1)

    text = harden_query_capacities(text)
    text, handle_outputs = harden_handle_outputs(text)
    text = harden_child_lifetimes(text)
    text, checked_response_sends = harden_response_transport(text)

    old_dispatch = """HandleRequestFunc getHandleRequestFunc(short requestCode) {
    int index = requestCode - REQUEST_CODE_VK_CALL_START;
    return index >= 0 && index < REQUEST_CODE_VK_CALL_COUNT ? handleRequestFuncs[index] : NULL;
}"""
    new_dispatch = """HandleRequestFunc getHandleRequestFunc(short requestCode) {
#if !VORTEK_REQUEST_HANDLE_AUTHORITY_COMPLETE
    (void)requestCode;
    return vt_handle_authority_incomplete;
#else
    int index = requestCode - REQUEST_CODE_VK_CALL_START;
    return index >= 0 && index < REQUEST_CODE_VK_CALL_COUNT ? handleRequestFuncs[index] : NULL;
#endif
}"""
    if old_dispatch not in text:
        raise ValueError("request dispatch gate drifted")
    text = text.replace(old_dispatch, new_dispatch, 1)

    return text, {"decoder_calls": total_calls, "request_vlas_replaced": total_vlas,
                  "request_heap_allocations_replaced": total_allocations,
                  "root_handle_conversions_replaced": total_root_handles,
                  "handle_outputs_published": handle_outputs,
                  "checked_response_sends": checked_response_sends}


def residuals(text: str) -> dict[str, int]:
    return {
        "raw_char_decoder_calls": len(re.findall(r"vt_unserialize_\w+\([^;]*context->inputBuffer", text)),
        "ignored_decoder_returns": len(re.findall(r"(?m)^\s*vt_unserialize_\w+\(", text)),
        "request_vlas": len(re.findall(r"(?m)^\s+[A-Za-z_]\w*(?:\s+\w+)*\s+\w+\[[A-Za-z_]", text)),
        "unchecked_request_calloc": len(re.findall(r"\bcalloc\(", text)),
        "unchecked_request_vt_alloc": len(re.findall(r"\bvt_alloc\(&context->memoryPool", text)),
        "unchecked_response_sends": text.count("vt_send(context->clientRing, "),
    }


def legacy_raw_handle_sites(text: str) -> dict[str, int]:
    sites: dict[str, int] = {}
    for match in HANDLER.finditer(text):
        end = brace_end(text, match.end())
        count = text[match.end():end].count("VkObject_fromId(")
        if count:
            sites[match.group("name")] = count
    return sites


RAW_HANDLE_OUTPUT = re.compile(
    r"VT_SERIALIZE_CMD\((?:Vk(?:Instance|PhysicalDevice|Device|Queue|Semaphore|"
    r"CommandBuffer|DeviceMemory|Buffer|Image|Event|QueryPool|BufferView|ImageView|"
    r"ShaderModule|PipelineCache|Pipeline|PipelineLayout|Sampler|DescriptorSetLayout|"
    r"DescriptorPool|DescriptorSet|Framebuffer|RenderPass|CommandPool|SwapchainKHR|"
    r"SamplerYcbcrConversion)|vk(?:EnumeratePhysicalDevices|EnumeratePhysicalDeviceGroups|"
    r"AllocateCommandBuffers|AllocateDescriptorSets|GetSwapchainImagesKHR))\b"
)


def legacy_raw_output_sites(text: str) -> dict[str, int]:
    sites: dict[str, int] = {}
    aggregate_serializers = {
        "vkEnumeratePhysicalDevices",
        "vkEnumeratePhysicalDeviceGroups",
        "vkAllocateCommandBuffers",
        "vkGetSwapchainImagesKHR",
    }
    for match in HANDLER.finditer(text):
        end = brace_end(text, match.end())
        body = text[match.end():end]
        residual = sum(
            1 for output in DIRECT_HANDLE_OUTPUT.finditer(body)
            if output.group("type") in OUTPUT_HANDLE_TYPES and
            "_vt_wire_output" not in output.group("value")
        )
        for serializer in aggregate_serializers:
            if f"VT_SERIALIZE_CMD({serializer}," in body and \
                    "vt_request_publish_vulkan_batch(" not in body:
                residual += 1
        if "vt_serialize_VkDescriptorSet(" in body and \
                "vt_request_publish_vulkan_batch(" not in body:
            residual += 1
        if residual:
            sites[match.group("name")] = residual
    return sites


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    source = normalized(SOURCE)
    source_hash = hashlib.sha256(source.encode()).hexdigest()
    if source_hash != SOURCE_SHA256:
        raise SystemExit(f"pinned request handler hash mismatch: {source_hash}")
    output, metrics = transform(source, normalized(SERIALIZER))
    metrics["handlers"] = len(HANDLER.findall(output))
    metrics["preflight_commands"] = len(decoder_signatures(normalized(SERIALIZER)))
    residual = residuals(output)
    manifest = {
        "upstream_commit": "ca3d735a60d653a787daf16d14fafef28d9c2c23",
        "source_normalized_sha256": source_hash,
        "output_normalized_sha256": hashlib.sha256(output.encode()).hexdigest(),
        "wire_format_changed": False,
        "authority_gate_default_closed": True,
        "active_raw_root_handle_conversions": 0,
        "active_raw_handle_output_serializations": 0,
        "legacy_raw_root_handle_sites": legacy_raw_handle_sites(output),
        "legacy_raw_handle_output_sites": legacy_raw_output_sites(output),
        "metrics": metrics,
        "residuals": residual,
    }
    rendered_manifest = json.dumps(manifest, indent=2, sort_keys=True) + "\n"
    if args.check:
        ok = OUTPUT.exists() and normalized(OUTPUT) == output
        ok = ok and MANIFEST.exists() and normalized(MANIFEST) == rendered_manifest
        ok = ok and all(value == 0 for value in residual.values())
        return 0 if ok else 1
    OUTPUT.write_text(output, encoding="utf-8", newline="\n")
    MANIFEST.write_text(rendered_manifest, encoding="utf-8", newline="\n")
    if any(residual.values()):
        raise SystemExit(f"non-zero hardening residuals: {residual}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
