#!/usr/bin/env python3
"""Generate a bounds-checked decoder without changing Vortek 2.1's wire.

The upstream project checks in only this generated serializer, not the source
generator.  This transformer therefore treats the normalized ca3d735 header as
the pinned input language and deliberately fails on any pattern it does not
understand.  Only vt_unserialize_* bodies/signatures are changed; sizeof and
serialize functions remain byte-for-byte identical apart from the decode
runtime include.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from dataclasses import dataclass, asdict
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "native/xserver-winlator/cpp/vortekrenderer/generated/vortek_serializer_upstream_ca3d735.h"
OUTPUT = ROOT / "native/xserver-winlator/cpp/vortekrenderer/include/vortek_serializer.h"
MANIFEST = ROOT / "native/xserver-winlator/cpp/vortekrenderer/generated/vortek_serializer_manifest.json"
HANDLE_POLICY = ROOT / "native/xserver-winlator/cpp/vortekrenderer/generated/vortek_handle_decode_policy.json"
FUZZ_DISPATCH = ROOT / "native/xserver-winlator/cpp/vortekrenderer/generated/vortek_decoder_fuzz_dispatch.inc"
NORMALIZED_SOURCE_SHA256 = "2761a1cad4c6812004e57c210b57507800b64b28ba33824b936d9daa37461253"

FUNCTION_START = re.compile(
    r"static inline void (?P<name>vt_unserialize_[A-Za-z0-9_]+)\((?P<args>.*?)\) \{",
    re.S,
)
NESTED_CALL = re.compile(
    r"(?P<indent>^[ \t]*)(?:(?:if \((?P<condition>[^\n]+?)\) )?)"
    r"(?P<call>vt_unserialize_[A-Za-z0-9_]+\([^;\n]*inputBuffer \+ bufferOffset[^;\n]*\));",
    re.M,
)
NEXT_ADVANCE = re.compile(r"bufferOffset \+= (?P<size>[^;]+);")
SCALAR_AT_OFFSET = re.compile(
    r"\*\((?P<type>[A-Za-z_][A-Za-z0-9_ \t]*?)\*\)\(inputBuffer \+ bufferOffset\)"
)
SCALAR_AT_START = re.compile(
    r"\*\((?P<type>[A-Za-z_][A-Za-z0-9_ \t]*?)\*\)\(inputBuffer\)"
)
RAW_COPY = re.compile(
    r"memcpy\((?P<dest>[^,;\n]+(?:\[[^\]]+\])?),\s*inputBuffer(?: \+ bufferOffset)?,\s*(?P<size>[^;\n]+)\)"
)
ALLOC = re.compile(r"vt_alloc\(memoryPool,\s*(?P<size>[^;\n]+)\)")
SERVER_HANDLE_ASSIGNMENT = re.compile(
    r"(?P<indent>^[ \t]*)(?P<lhs>[^=\n]+?) = (?P<rhs>[^\n;]*"
    r"VkObject_fromId\((?P<id>[A-Za-z_]\w*Id)\)[^\n;]*);",
    re.M,
)

# These are the only Vulkan handle fields in the pinned serializer whose null
# value is made legal by VK_EXT_robustness2::nullDescriptor.  Other nullable
# handles (old swapchains, optional fences, derivative pipelines, and so on)
# use Vulkan's unconditional nullability and must not be feature-gated.
NULL_DESCRIPTOR_POLICY_KEYS = {
    "vt_unserialize_VkDescriptorBufferInfo|bufferId|val->buffer",
    "vt_unserialize_VkDescriptorImageInfo|imageViewId|val->imageView",
    "vt_unserialize_VkDescriptorImageInfo|samplerId|val->sampler",
    "vt_unserialize_VkWriteDescriptorSet|texelBufferViewId|arrValues[i]",
}


@dataclass
class Metrics:
    decoders: int = 0
    scalar_reads: int = 0
    presence_reads: int = 0
    data_sizes: int = 0
    item_sizes: int = 0
    allocations: int = 0
    pnext_loops: int = 0
    nested_calls: int = 0
    raw_copies: int = 0


def load_handle_policy() -> dict[str, dict[str, object]]:
    document = json.loads(HANDLE_POLICY.read_text(encoding="utf-8"))
    if document.get("schema") != 1 or not isinstance(document.get("sites"), dict):
        raise ValueError("invalid handle decode policy manifest")
    sites = document["sites"]
    required = {"allow_null", "object_type", "owner", "role", "wire_type"}
    for key, policy in sites.items():
        if not isinstance(key, str) or not isinstance(policy, dict) or set(policy) != required:
            raise ValueError(f"invalid handle policy entry: {key}")
        if policy["owner"] not in {"instance", "device"}:
            raise ValueError(f"invalid handle owner policy: {key}")
        if policy["role"] not in {"vulkan", "resource_memory_device_memory", "window_id",
                                  "shader_module_wrapper", "xwindow_swapchain_wrapper"}:
            raise ValueError(f"invalid handle role policy: {key}")
        if not isinstance(policy["allow_null"], bool):
            raise ValueError(f"invalid handle null policy: {key}")
    for key in NULL_DESCRIPTOR_POLICY_KEYS:
        if key not in sites or sites[key]["allow_null"] is not True:
            raise ValueError(f"missing nullable descriptor policy: {key}")
    return sites


def object_type_for_wire_type(wire_type: str) -> str:
    if not wire_type.startswith("Vk"):
        raise ValueError(f"not a Vulkan handle type: {wire_type}")
    words = re.findall(r"[A-Z]+(?=[A-Z][a-z]|\d|$)|[A-Z]?[a-z]+|\d+", wire_type[2:])
    return "VK_OBJECT_TYPE_" + "_".join(word.upper() for word in words)


def handle_policy_key(function: str, wire_id: str, destination: str) -> str:
    return f"{function}|{wire_id}|{destination.strip()}"


def normalized_bytes(path: Path) -> bytes:
    return path.read_bytes().replace(b"\r\n", b"\n")


def matching_brace(text: str, body_start: int) -> int:
    depth = 1
    index = body_start
    while index < len(text) and depth:
        if text[index] == "{":
            depth += 1
        elif text[index] == "}":
            depth -= 1
        index += 1
    if depth:
        raise ValueError("unterminated decoder function")
    return index


def source_metrics(text: str) -> Metrics:
    bodies: list[str] = []
    for match in FUNCTION_START.finditer(text):
        end = matching_brace(text, match.end())
        bodies.append(text[match.start():end])
    joined = "\n".join(bodies)
    return Metrics(
        decoders=len(bodies),
        scalar_reads=len(SCALAR_AT_OFFSET.findall(joined)) + len(SCALAR_AT_START.findall(joined)),
        presence_reads=len(re.findall(r"inputBuffer\[bufferOffset\+\+\]", joined)),
        data_sizes=len(re.findall(r"\bint dataSize\d+\s*=", joined)),
        item_sizes=len(re.findall(r"\bint itemSize\s*=", joined)),
        allocations=len(re.findall(r"vt_alloc\(memoryPool,", joined)),
        pnext_loops=len(re.findall(r"while \(pNextType != -1\)", joined)),
        nested_calls=len(re.findall(
            r"\bvt_unserialize_[A-Za-z0-9_]+\([^;]*inputBuffer \+ bufferOffset", joined
        )),
        raw_copies=len(re.findall(r"memcpy\([^;]*inputBuffer", joined)),
    )


def split_product(expression: str) -> tuple[str, str] | None:
    depth = 0
    for index, char in enumerate(expression):
        if char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
        elif char == "*" and depth == 0:
            return expression[:index].strip(), expression[index + 1:].strip()
    return None


def bounded_size(expression: str) -> str:
    product = split_product(expression)
    if not product:
        return f"(size_t)({expression})"
    left, right = product
    return f"VT_DECODE_PRODUCT(cursor, ({left}), ({right}))"


def allocation(expression: str) -> str:
    product = split_product(expression)
    if product:
        count, element_size = product
    elif re.fullmatch(r"(?:dataSize\d+|strSize)", expression.strip()):
        return (
            "vt_decode_alloc_bytes(cursor, memoryPool, "
            f"(size_t)({expression.strip()}))"
        )
    else:
        count, element_size = "1", expression.strip()
    return (
        "vt_decode_alloc(cursor, memoryPool, "
        f"(size_t)({count}), (size_t)({element_size}))"
    )


def transform_server_handles(
        function: str,
        body: str,
        policies: dict[str, dict[str, object]],
        used_policies: set[str]) -> str:
    sequence = 0

    def replace(match: re.Match[str]) -> str:
        nonlocal sequence
        sequence += 1
        wire_id = match.group("id")
        destination = match.group("lhs").strip()
        key = handle_policy_key(function, wire_id, destination)
        if key not in policies:
            raise ValueError(f"unmapped server handle decode: {key}")
        if key in used_policies:
            raise ValueError(f"duplicate server handle decode policy: {key}")
        policy = policies[key]
        prefix = body[:match.start()]
        wire_types = re.findall(
            rf"vt_unserialize_(Vk[A-Za-z0-9_]+)\(\([^)]*\)&{re.escape(wire_id)}\b",
            prefix,
        )
        if not wire_types:
            raise ValueError(f"cannot infer wire handle type: {key}")
        wire_type = wire_types[-1]
        if policy["wire_type"] != wire_type:
            raise ValueError(
                f"handle wire type mismatch for {key}: {wire_type} != {policy['wire_type']}"
            )
        wrapper_role = str(policy["role"])
        expected_object_type = (
            "VK_OBJECT_TYPE_UNKNOWN"
            if wrapper_role in {"shader_module_wrapper", "xwindow_swapchain_wrapper"}
            else object_type_for_wire_type(wire_type)
        )
        if policy["object_type"] != expected_object_type:
            raise ValueError(
                f"handle object type mismatch for {key}: {expected_object_type} != "
                f"{policy['object_type']}"
            )

        role = {
            "vulkan": "VT_DECODE_HANDLE_ROLE_VULKAN",
            "resource_memory_device_memory":
                "VT_DECODE_HANDLE_ROLE_RESOURCE_MEMORY_DEVICE_MEMORY",
            "window_id": "VT_DECODE_HANDLE_ROLE_WINDOW_ID",
            "shader_module_wrapper":
                "VT_DECODE_HANDLE_ROLE_SHADER_MODULE_WRAPPER",
            "xwindow_swapchain_wrapper":
                "VT_DECODE_HANDLE_ROLE_XWINDOW_SWAPCHAIN_WRAPPER",
        }[str(policy["role"])]
        if "ResourceMemory" in match.group("rhs"):
            if policy["role"] != "resource_memory_device_memory" or wire_type != "VkDeviceMemory":
                raise ValueError(f"invalid ResourceMemory policy: {key}")
        elif policy["role"] == "shader_module_wrapper":
            if wire_type != "VkShaderModule":
                raise ValueError(f"invalid ShaderModule wrapper policy: {key}")
        elif policy["role"] == "xwindow_swapchain_wrapper":
            if wire_type != "VkSwapchainKHR":
                raise ValueError(f"invalid swapchain wrapper policy: {key}")
        elif policy["role"] != "vulkan":
            raise ValueError(f"unexpected non-Vulkan nested handle policy: {key}")
        owner = (
            "VT_DECODE_OWNER_INSTANCE"
            if policy["owner"] == "instance"
            else "VT_DECODE_OWNER_INSTANCE | VT_DECODE_OWNER_DEVICE"
        )
        nullability = (
            "VT_DECODE_NULL_DESCRIPTOR_FEATURE"
            if key in NULL_DESCRIPTOR_POLICY_KEYS
            else "VT_DECODE_NULL_VULKAN"
            if policy["allow_null"]
            else "VT_DECODE_NULL_NEVER"
        )
        resolved = f"vtResolvedHandle{sequence}"
        indent = match.group("indent")
        used_policies.add(key)
        return (
            f"{indent}uint64_t {resolved} = 0;\n"
            f"{indent}if (!vt_decode_resolve_handle(cursor, {wire_id}, "
            f"(uint32_t){expected_object_type}, {role}, {owner}, {nullability}, "
            f"&{resolved})) return false;\n"
            f"{indent}{destination} = ({wire_type})(uintptr_t){resolved};"
        )

    return SERVER_HANDLE_ASSIGNMENT.sub(replace, body)


def transform_surface_handle(
        function: str,
        body: str,
        policies: dict[str, dict[str, object]],
        used_policies: set[str]) -> str:
    if function != "vt_unserialize_VkSurfaceKHR":
        return body
    key = handle_policy_key(function, "surfaceId", "val")
    policy = policies.get(key)
    expected = {
        "allow_null": False,
        "object_type": "VK_OBJECT_TYPE_SURFACE_KHR",
        "owner": "instance",
        "role": "window_id",
        "wire_type": "VkSurfaceKHR",
    }
    if policy != expected:
        raise ValueError(f"invalid surface WINDOW_ID policy: {key}")
    needle = "*(uint64_t*)(val) = VT_DECODE_VALUE(cursor, 0, uint64_t);"
    if body.count(needle) != 1:
        raise ValueError("unexpected VkSurfaceKHR decoder body")
    used_policies.add(key)
    replacement = (
        "uint64_t surfaceId = VT_DECODE_VALUE(cursor, 0, uint64_t);\n"
        "#ifdef VT_SERVER\n"
        "    uint64_t vtResolvedSurface = 0;\n"
        "    if (!vt_decode_resolve_handle(cursor, surfaceId, "
        "(uint32_t)VK_OBJECT_TYPE_SURFACE_KHR, VT_DECODE_HANDLE_ROLE_WINDOW_ID, "
        "VT_DECODE_OWNER_INSTANCE, VT_DECODE_NULL_NEVER, "
        "&vtResolvedSurface)) return false;\n"
        "    *(uint64_t*)(val) = vtResolvedSurface;\n"
        "#else\n"
        "    *(uint64_t*)(val) = surfaceId;\n"
        "#endif"
    )
    return body.replace(needle, replacement, 1)


def transform_nested_calls(body: str) -> str:
    matches = list(NESTED_CALL.finditer(body))
    for match in reversed(matches):
        tail = body[match.end():]
        advance = NEXT_ADVANCE.search(tail)
        if not advance or advance.start() > 512:
            raise ValueError(f"no size envelope follows {match.group('call')}")
        size = advance.group("size").strip()
        size = re.sub(r"arrValues\[i\]", "VK_NULL_HANDLE", size)
        call = match.group("call").replace(
            "inputBuffer + bufferOffset", "&_vt_child", 1
        )
        call = call.replace("&arrValues[i]", "(arrValues ? &arrValues[i] : NULL)")
        checked = (
            f"VT_DECODE_CHILD_CALL(cursor, bufferOffset, {bounded_size(size)}, {call})"
        )
        condition = match.group("condition")
        if condition:
            # Complex child packets must still be parsed when callers request
            # only counts.  The generated Vk decoders install a local scratch
            # object when their destination is NULL.
            replacement = f"{match.group('indent')}if (!{checked}) return false;"
        else:
            replacement = f"{match.group('indent')}if (!{checked}) return false;"
        body = body[:match.start()] + replacement + body[match.end():]
    return body


def make_null_destinations_parse(body: str) -> str:
    # Generated array decoders commonly wrap their sole nested call in an
    # `if (arrValues)` block.  Count-only first passes still need to validate
    # every byte, so the nested Vk decoder receives NULL and uses its scratch
    # destination instead.
    body = re.sub(
        r"(?P<indent>^[ \t]*)if \(arrValues\) \{\n"
        r"(?P=indent)    (?P<call>if \(!VT_DECODE_CHILD_CALL\([^\n]+\)\) return false;)\n"
        r"(?P=indent)\}",
        lambda m: f"{m.group('indent')}{m.group('call')}",
        body,
        flags=re.M,
    )
    return body


def transform_presence(body: str) -> str:
    sequence = 0

    def replace(match: re.Match[str]) -> str:
        nonlocal sequence
        sequence += 1
        indent = match.group("indent")
        name = f"vtPresent{sequence}"
        return (
            f"{indent}bool {name} = false;\n"
            f"{indent}if (!vt_decode_presence_at(cursor, &bufferOffset, &{name})) return false;\n"
            f"{indent}if ({name}) {{"
        )

    return re.sub(
        r"(?P<indent>^[ \t]*)if \(inputBuffer\[bufferOffset\+\+\]\) \{",
        replace,
        body,
        flags=re.M,
    )


def transform_lengths(body: str) -> str:
    body = re.sub(
        r"(?P<indent>^[ \t]*)int (?P<name>(?:dataSize\d+|itemSize|strSize)) = "
        r"VT_DECODE_VALUE\(cursor, bufferOffset, int\);",
        lambda m: (
            f"{m.group('indent')}int32_t {m.group('name')} = 0;\n"
            f"{m.group('indent')}if (!vt_decode_data_size_at(cursor, bufferOffset, "
            f"&{m.group('name')})) return false;"
        ),
        body,
        flags=re.M,
    )
    body = re.sub(
        r"(?P<indent>^[ \t]*)for \(int i = 0; i < (?P<count>dataSize\d+); i\+\+\) \{",
        lambda m: (
            f"{m.group('indent')}if (!vt_decode_note_elements(cursor, "
            f"(size_t){m.group('count')})) return false;\n"
            f"{m.group('indent')}for (size_t i = 0; i < (size_t){m.group('count')}; i++) {{"
        ),
        body,
        flags=re.M,
    )
    body = re.sub(
        r"(?P<indent>^[ \t]*)for \(int i = 0; i < (?P<count>strSize); i\+\+\) \{",
        lambda m: (
            f"{m.group('indent')}if (!vt_decode_note_elements(cursor, "
            f"(size_t){m.group('count')})) return false;\n"
            f"{m.group('indent')}for (size_t i = 0; i < (size_t){m.group('count')}; i++) {{"
        ),
        body,
        flags=re.M,
    )
    return body


def transform_declared_counts(body: str) -> str:
    # Validate Vulkan count/capacity fields even on a first pass whose caller
    # supplied a NULL destination pointer.  dataSize/itemSize envelopes are
    # handled separately because some describe byte strings rather than item
    # counts.
    direct = re.compile(
        r"(?P<indent>^[ \t]*)(?P<lhs>(?:val->)?[A-Za-z_]\w*(?:Count|count)) = "
        r"VT_DECODE_VALUE\(cursor, bufferOffset, (?P<type>uint32_t|size_t)\);",
        re.M,
    )

    def direct_replacement(match: re.Match[str]) -> str:
        statement = match.group(0)
        lhs = match.group("lhs")
        return (
            f"{statement}\n{match.group('indent')}if (!vt_decode_validate_count("
            f"cursor, (uint64_t){lhs})) return false;"
        )

    body = direct.sub(direct_replacement, body)

    optional = re.compile(
        r"(?P<indent>^[ \t]*)if \((?P<ptr>[A-Za-z_]\w*(?:Count|count))\) "
        r"\*(?P=ptr) = VT_DECODE_VALUE\(cursor, bufferOffset, "
        r"(?P<type>uint32_t|size_t)\);",
        re.M,
    )

    def optional_replacement(match: re.Match[str]) -> str:
        indent = match.group("indent")
        ptr = match.group("ptr")
        value_type = match.group("type")
        local = f"vtDecoded{ptr[0].upper()}{ptr[1:]}"
        return (
            f"{indent}{value_type} {local} = VT_DECODE_VALUE(cursor, bufferOffset, "
            f"{value_type});\n"
            f"{indent}if (!vt_decode_validate_count(cursor, (uint64_t){local})) "
            "return false;\n"
            f"{indent}if ({ptr}) *{ptr} = {local};"
        )

    return optional.sub(optional_replacement, body)


def transform_count_correlations(body: str) -> str:
    # Vulkan structs serialize both their semantic Count member and a second
    # array envelope.  They must agree: otherwise callers would allocate/use
    # one count while the decoder initialized another number of elements.
    pattern = re.compile(
        r"(?P<lhs>val->[A-Za-z_]\w*(?:Count|count)) = "
        r"VT_DECODE_VALUE\(cursor, bufferOffset, (?:uint32_t|size_t)\);"
        r"(?P<middle>.{0,640}?)"
        r"int32_t (?P<data>dataSize\d+) = 0;\n"
        r"(?P<indent>[ \t]*)if \(!vt_decode_data_size_at\(cursor, bufferOffset, "
        r"&(?P=data)\)\) return false;",
        re.S,
    )

    def correlate(match: re.Match[str]) -> str:
        lhs = match.group("lhs")
        data = match.group("data")
        return (
            f"{lhs} = VT_DECODE_VALUE(cursor, bufferOffset, uint32_t);"
            f"{match.group('middle')}int32_t {data} = 0;\n"
            f"{match.group('indent')}if (!vt_decode_data_size_at(cursor, bufferOffset, "
            f"&{data})) return false;\n"
            f"{match.group('indent')}if ((uint64_t){lhs} != (uint64_t){data}) "
            "return vt_decode_fail(cursor, VT_DECODE_ERROR_ARGUMENT);"
        )

    # Some Vulkan arrays are optional, or are one arm of a descriptor union.
    # The positional heuristic cannot express those semantics and must not
    # install a false exact-count check for them; explicit policies below do.
    exact_exceptions = {
        "vt_unserialize_VkDescriptorSetLayoutBinding": {"dataSize5"},
        "vt_unserialize_VkFramebufferAttachmentImageInfo": {"dataSize9"},
        "vt_unserialize_VkRenderingInfo": {"dataSize8"},
        "vt_unserialize_VkWriteDescriptorSet": {"dataSize8"},
    }
    return pattern.sub(correlate, body)


def transform_required_count_correlations(function: str, body: str) -> str:
    exact: list[tuple[str, str]] = []
    optional: list[tuple[str, str]] = []
    if function == "vt_unserialize_VkSemaphoreWaitInfo":
        exact = [("dataSize6", "val->semaphoreCount")]
    elif function == "vt_unserialize_VkSubmitInfo":
        exact = [("dataSize5", "val->waitSemaphoreCount")]
    elif function == "vt_unserialize_VkSubpassDescription":
        optional = [("dataSize7", "val->colorAttachmentCount")]
    elif function == "vt_unserialize_VkSubpassDescription2":
        optional = [("dataSize10", "val->colorAttachmentCount")]
    elif function == "vt_unserialize_VkPresentInfoKHR":
        exact = [("dataSize7", "val->swapchainCount")]
        optional = [("dataSize8", "val->swapchainCount")]
    elif function == "vt_unserialize_VkDescriptorSetLayoutBinding":
        optional = [("dataSize5", "val->descriptorCount")]
    elif function == "vt_unserialize_VkRenderingInfo":
        exact = [("dataSize8", "val->colorAttachmentCount")]
    elif function == "vt_unserialize_VkFramebufferAttachmentImageInfo":
        exact = [("dataSize9", "val->viewFormatCount")]
    elif function in {
            "vt_unserialize_vkCreateGraphicsPipelines",
            "vt_unserialize_vkCreateComputePipelines"}:
        exact = [
            ("dataSize4", "vtDecodedCreateInfoCount"),
            ("dataSize6", "vtDecodedCreateInfoCount"),
        ]

    # Remove any positional check for a field covered by an explicit semantic
    # policy.  This is essential for optional arrays and descriptor unions.
    for data_size, _ in exact + optional:
        body = re.sub(
            rf"\n(?P<indent>[ \t]*)if \(\(uint64_t\)[^\n]+ != "
            rf"\(uint64_t\){data_size}\) return vt_decode_fail\(cursor, "
            r"VT_DECODE_ERROR_ARGUMENT\);",
            "",
            body,
            count=1,
        )

    def install(data_size: str, declared_count: str, allow_absent: bool) -> None:
        nonlocal body
        needle = (
            f"if (!vt_decode_data_size_at(cursor, bufferOffset, &{data_size})) "
            "return false;"
        )
        if body.count(needle) != 1:
            raise ValueError(
                f"{function}: required count envelope drifted: {data_size}")
        condition = (f"{data_size} != 0 && " if allow_absent else "") + \
                f"(uint64_t){declared_count} != (uint64_t){data_size}"
        replacement = needle + "\n    " + f"if ({condition}) " + \
                "return vt_decode_fail(cursor, VT_DECODE_ERROR_ARGUMENT);"
        body = body.replace(needle, replacement, 1)

    for data_size, declared_count in exact:
        install(data_size, declared_count, False)
    for data_size, declared_count in optional:
        install(data_size, declared_count, True)

    if function == "vt_unserialize_VkWriteDescriptorSet":
        checks = (
            "    const bool vtDescriptorImage =\n"
            "            val->descriptorType == VK_DESCRIPTOR_TYPE_SAMPLER ||\n"
            "            val->descriptorType == VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER ||\n"
            "            val->descriptorType == VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE ||\n"
            "            val->descriptorType == VK_DESCRIPTOR_TYPE_STORAGE_IMAGE ||\n"
            "            val->descriptorType == VK_DESCRIPTOR_TYPE_INPUT_ATTACHMENT;\n"
            "    const bool vtDescriptorBuffer =\n"
            "            val->descriptorType == VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER ||\n"
            "            val->descriptorType == VK_DESCRIPTOR_TYPE_STORAGE_BUFFER ||\n"
            "            val->descriptorType == VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC ||\n"
            "            val->descriptorType == VK_DESCRIPTOR_TYPE_STORAGE_BUFFER_DYNAMIC;\n"
            "    const bool vtDescriptorTexel =\n"
            "            val->descriptorType == VK_DESCRIPTOR_TYPE_UNIFORM_TEXEL_BUFFER ||\n"
            "            val->descriptorType == VK_DESCRIPTOR_TYPE_STORAGE_TEXEL_BUFFER;\n"
            "    if ((vtDescriptorImage ? dataSize8 : 0) !=\n"
            "                (vtDescriptorImage ? (int32_t)val->descriptorCount : dataSize8) ||\n"
            "            (vtDescriptorBuffer ? dataSize9 : 0) !=\n"
            "                (vtDescriptorBuffer ? (int32_t)val->descriptorCount : dataSize9) ||\n"
            "            (vtDescriptorTexel ? dataSize10 : 0) !=\n"
            "                (vtDescriptorTexel ? (int32_t)val->descriptorCount : dataSize10) ||\n"
            "            (!vtDescriptorImage && !vtDescriptorBuffer && !vtDescriptorTexel &&\n"
            "             (dataSize8 != 0 || dataSize9 != 0 || dataSize10 != 0)))\n"
            "        return vt_decode_fail(cursor, VT_DECODE_ERROR_ARGUMENT);\n"
        )
        body += "\n" + checks
    return body


def transform_strings(body: str) -> str:
    # A Vortek string envelope includes its terminating NUL.  Reject missing
    # terminators and embedded NULs; this keeps downstream strlen bounded.
    body = re.sub(
        r"\(vt_decode_copy_at\(cursor, bufferOffset, (?P<dest>arrValues), "
        r"\(size_t\)\((?P<size>dataSize\d+)\)\) \? \(void\)0 : \(void\)0\);\n"
        r"(?P<indent>[ \t]*)(?P<field>val->(?:pName|pApplicationName|pEngineName)|pLayerName) = arrValues;",
        lambda m: (
            f"if (!vt_decode_string_at(cursor, bufferOffset, (size_t){m.group('size')}, "
            "arrValues)) return false;\n"
            f"{m.group('indent')}{m.group('field')} = arrValues;"
        ),
        body,
    )
    body = re.sub(
        r"\(vt_decode_copy_at\(cursor, bufferOffset, arrValues\[i\], "
        r"\(size_t\)\(strSize\)\) \? \(void\)0 : \(void\)0\);",
        "if (!vt_decode_string_at(cursor, bufferOffset, (size_t)strSize, "
        "arrValues[i])) return false;",
        body,
    )
    return body


def transform_pnext(body: str) -> str:
    if "while (pNextType != -1)" not in body:
        return body

    # Every known case has the same itemSize envelope.  Recording it before
    # the child call rejects duplicate nodes and arms the sType cross-check.
    body = re.sub(
        r"(?P<advance>if \(!vt_decode_advance\(cursor, &bufferOffset, \(size_t\)\(4\)\)\) return false;)\n"
        r"(?P<indent>[ \t]*)if \(itemSize > 0\) \{",
        lambda m: (
            f"{m.group('advance')}\n{m.group('indent')}if (!vt_decode_pnext_known("
            "cursor, pNextType, itemSize)) return false;\n"
            f"{m.group('indent')}if (itemSize > 0) {{"
        ),
        body,
    )

    default_pattern = re.compile(
        r"(?P<indent>[ \t]*)default: \{\n"
        r"(?P=indent)    if \(!vt_decode_advance\(cursor, &bufferOffset, \(size_t\)\(4\)\)\) return false;\n"
        r"(?P=indent)    break;\n(?P=indent)\}"
    )

    def unknown(match: re.Match[str]) -> str:
        indent = match.group("indent")
        return (
            f"{indent}default: {{\n"
            f"{indent}    int32_t unknownItemSize = 0;\n"
            f"{indent}    if (!vt_decode_data_size_at(cursor, bufferOffset, "
            "&unknownItemSize)) return false;\n"
            f"{indent}    if (!vt_decode_advance(cursor, &bufferOffset, 4)) return false;\n"
            f"{indent}    if (!vt_decode_pnext_unknown(cursor, pNextType, "
            "unknownItemSize)) return false;\n"
            f"{indent}    break;\n{indent}}}"
        )

    body, defaults = default_pattern.subn(unknown, body)
    if defaults == 0:
        raise ValueError("pNext decoder has no recognized default envelope")
    body = body.replace(
        "\n    if (pNextIsNULL) val->pNext = invertVkStructuresChain(pNext);",
        "\n    if (!vt_decode_pnext_terminated(cursor, pNextType)) return false;"
        "\n    if (pNextIsNULL) val->pNext = invertVkStructuresChain(pNext);",
        1,
    )
    if "vt_decode_pnext_terminated" not in body:
        raise ValueError("pNext decoder terminator was not guarded")
    return body


def scratch_destination(name: str, args: str, body: str) -> str:
    short = name.removeprefix("vt_unserialize_")
    if not short.startswith("Vk"):
        return body
    first = args.split(",", 1)[0].strip()
    pointer = re.fullmatch(rf"{re.escape(short)}\*\s+(?P<var>[A-Za-z_]\w*)", first)
    if pointer:
        var = pointer.group("var")
        return (
            f"\n    {short} vtScratch = {{0}};\n"
            f"    if (!{var}) {var} = &vtScratch;" + body
        )
    # Dispatchable and non-dispatchable opaque handles use an address-like
    # argument in the serializer.  A local 64-bit sink preserves parsing when
    # a count-only pass supplies NULL.
    handle = re.fullmatch(rf"{re.escape(short)}\s+(?P<var>[A-Za-z_]\w*)", first)
    if handle:
        var = handle.group("var")
        return (
            "\n    uint64_t vtScratchHandle = 0;\n"
            f"    if (!{var}) {var} = ({short})&vtScratchHandle;" + body
        )
    return body


def transform_function(
        match: re.Match[str],
        source: str,
        policies: dict[str, dict[str, object]],
        used_policies: set[str]) -> tuple[str, int]:
    end = matching_brace(source, match.end())
    name = match.group("name")
    args = match.group("args")
    if "char* inputBuffer" not in args:
        raise ValueError(f"{name}: unexpected decoder signature")
    args = args.replace("char* inputBuffer", "VtDecodeCursor* cursor")
    body = source[match.end():end - 1]
    original_direct_copy = RAW_COPY.search(body) if "bufferOffset +=" not in body else None
    body = scratch_destination(name, args, body)
    if "int bufferOffset = 0;" in body:
        body = body.replace("int bufferOffset = 0;", "size_t bufferOffset = 0;", 1)
    else:
        body = "\n    size_t bufferOffset = 0;" + body

    body = transform_nested_calls(body)
    body = transform_server_handles(name, body, policies, used_policies)
    body = transform_presence(body)
    body = SCALAR_AT_OFFSET.sub(
        lambda m: f"VT_DECODE_VALUE(cursor, bufferOffset, {m.group('type').strip()})",
        body,
    )
    body = SCALAR_AT_START.sub(
        lambda m: f"VT_DECODE_VALUE(cursor, 0, {m.group('type').strip()})",
        body,
    )
    body = transform_surface_handle(name, body, policies, used_policies)
    body = transform_lengths(body)
    body = RAW_COPY.sub(
        lambda m: (
            "(vt_decode_copy_at(cursor, bufferOffset, "
            f"{m.group('dest').strip()}, {bounded_size(m.group('size').strip())}) "
            "? (void)0 : (void)0)"
        ),
        body,
    )
    body = ALLOC.sub(lambda m: allocation(m.group("size")), body)
    body = transform_strings(body)
    body = transform_declared_counts(body)
    body = transform_count_correlations(body)
    if name == "vt_unserialize_VkWriteDescriptorSet":
        body, removed = re.subn(
            r"\n[ \t]*if \(\(uint64_t\)val->descriptorCount != "
            r"\(uint64_t\)dataSize8\) return vt_decode_fail\(cursor, "
            r"VT_DECODE_ERROR_ARGUMENT\);",
            "",
            body,
            count=1,
        )
        if removed != 1:
            raise ValueError(f"{name}: positional descriptor check drifted")
    body = transform_required_count_correlations(name, body)
    body = make_null_destinations_parse(body)

    def advance(match: re.Match[str]) -> str:
        expression = match.group("size").strip()
        product = split_product(expression)
        if product:
            left, right = product
            return (
                "if (!vt_decode_advance_array(cursor, &bufferOffset, "
                f"(size_t)({left}), (size_t)({right}))) return false;"
            )
        return (
            "if (!vt_decode_advance(cursor, &bufferOffset, "
            f"(size_t)({expression}))) return false;"
        )

    body = NEXT_ADVANCE.sub(advance, body)
    body = transform_pnext(body)

    # A direct start read/copy has no generated bufferOffset increment.
    if "inputBuffer" not in body and "bufferOffset" in body and not re.search(
            r"vt_decode_advance(?:_array)?\(cursor", body):
        size_match = re.search(r"VT_DECODE_VALUE\(cursor, 0, (?P<type>[^)]+)\)", body)
        if size_match:
            body += (
                "\n    if (!vt_decode_advance(cursor, &bufferOffset, "
                f"sizeof({size_match.group('type')}))) return false;"
            )
        elif original_direct_copy:
            body += (
                "\n    if (!vt_decode_advance(cursor, &bufferOffset, "
                f"{bounded_size(original_direct_copy.group('size').strip())})) return false;"
            )

    body += "\n    return vt_decode_ok(cursor);\n"
    transformed = f"static inline bool {name}({args}) {{{body}}}"
    return transformed, end


def transform(text: str, policies: dict[str, dict[str, object]]) -> str:
    if '#include "vortek_decode.h"' in text:
        raise ValueError("input is already hardened")
    output: list[str] = []
    position = 0
    functions = 0
    used_policies: set[str] = set()
    while True:
        match = FUNCTION_START.search(text, position)
        if not match:
            output.append(text[position:])
            break
        output.append(text[position:match.start()])
        function, position = transform_function(
            match, text, policies, used_policies
        )
        output.append(function)
        functions += 1
    if functions != 630:
        raise ValueError(f"expected 630 decoders, found {functions}")
    unused_policies = set(policies) - used_policies
    if unused_policies:
        raise ValueError(
            "unused handle decode policies: " + ", ".join(sorted(unused_policies))
        )
    result = "".join(output).replace(
        '#include "vortek.h"', '#include "vortek.h"\n#include "vortek_decode.h"', 1
    )
    audit_output(result)
    return result


def decoder_bodies(text: str) -> str:
    start = re.compile(r"static inline bool vt_unserialize_[A-Za-z0-9_]+\(.*?\) \{", re.S)
    bodies: list[str] = []
    for match in start.finditer(text):
        bodies.append(text[match.start():matching_brace(text, match.end())])
    return "\n".join(bodies)


def generate_fuzz_dispatch(text: str) -> tuple[str, list[str]]:
    functions = list(re.finditer(
        r"static inline bool (?P<name>vt_unserialize_[A-Za-z0-9_]+)"
        r"\((?P<args>.*?)\) \{",
        text,
        re.S,
    ))
    if len(functions) != 630:
        raise ValueError(f"expected 630 fuzz decoder targets, found {len(functions)}")
    lines = [
        "/* Generated by tools/vortek_serializer_hardener.py; do not edit. */",
        "static bool vt_fuzz_dispatch_decoder(",
        "        uint32_t decoder_index,",
        "        VtDecodeCursor* cursor,",
        "        MemoryPool* memory_pool) {",
        "    switch (decoder_index) {",
    ]
    names: list[str] = []
    for index, match in enumerate(functions):
        name = match.group("name")
        parameters = [part.strip() for part in match.group("args").split(",")]
        if parameters[-2:] != [
                "VtDecodeCursor* cursor", "MemoryPool* memoryPool"]:
            raise ValueError(f"unexpected fuzz decoder signature: {name}")
        arguments = ["0"] * (len(parameters) - 2) + ["cursor", "memory_pool"]
        lines.append(
            f"        case {index}u: return {name}({', '.join(arguments)});"
        )
        names.append(name)
    lines.extend([
        "        default: return vt_decode_fail(cursor, VT_DECODE_ERROR_ARGUMENT);",
        "    }",
        "}",
        "",
        "#define VT_FUZZ_DECODER_COUNT 630u",
        "",
    ])
    return "\n".join(lines), names


def audit_output(text: str) -> None:
    bodies = decoder_bodies(text)
    assertions = {
        "decoder count": len(re.findall(r"static inline bool vt_unserialize_", bodies)) == 630,
        "raw inputBuffer": "inputBuffer" not in bodies,
        "unchecked decoder allocation": "vt_alloc(memoryPool" not in bodies,
        "raw decoder memcpy": not re.search(r"memcpy\([^;]*cursor", bodies),
        "unchecked bufferOffset add": "bufferOffset +=" not in bodies,
        "unchecked presence": "bufferOffset++]" not in bodies,
        "unbounded pNext loop": "while (pNextType != -1)" not in bodies or
            bodies.count("while (pNextType != -1)") == bodies.count("vt_decode_pnext_terminated"),
        "void decoder": "static inline void vt_unserialize_" not in text,
        "raw server handle conversion": "VkObject_fromId" not in bodies,
        "raw ResourceMemory cast": "ResourceMemory*)" not in bodies,
    }
    failed = [name for name, passed in assertions.items() if not passed]
    if failed:
        raise ValueError("generated decoder audit failed: " + ", ".join(failed))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()

    source_bytes = normalized_bytes(SOURCE)
    digest = hashlib.sha256(source_bytes).hexdigest()
    if digest != NORMALIZED_SOURCE_SHA256:
        raise SystemExit(f"pinned serializer hash mismatch: {digest}")
    source = source_bytes.decode("utf-8")
    metrics = source_metrics(source)
    if metrics.decoders != 630:
        raise SystemExit(f"unexpected decoder count: {metrics.decoders}")
    policies = load_handle_policy()
    generated = transform(source, policies)
    generated_bytes = generated.encode("utf-8")
    fuzz_dispatch, decoder_names = generate_fuzz_dispatch(generated)
    fuzz_dispatch_bytes = fuzz_dispatch.encode("utf-8")
    manifest = {
        "schema": 1,
        "source": "brunodev85/winlator-app@ca3d735a60d653a787daf16d14fafef28d9c2c23",
        "source_path": "app/src/main/cpp/vortekrenderer/include/vortek_serializer.h",
        "source_normalized_sha256": digest,
        "handle_policy_sha256": hashlib.sha256(normalized_bytes(HANDLE_POLICY)).hexdigest(),
        "handle_policy_sites": len(policies),
        "fuzz_dispatch_sha256": hashlib.sha256(fuzz_dispatch_bytes).hexdigest(),
        "fuzz_decoder_targets": len(decoder_names),
        "fuzz_request_decoder_targets": sum(
            name.startswith("vt_unserialize_vk") for name in decoder_names
        ),
        "output_normalized_sha256": hashlib.sha256(generated_bytes).hexdigest(),
        "wire_format_changed": False,
        "source_metrics": asdict(metrics),
    }
    manifest_text = json.dumps(manifest, indent=2, sort_keys=True) + "\n"

    if args.check:
        actual_output = normalized_bytes(OUTPUT)
        actual_manifest = normalized_bytes(MANIFEST)
        actual_fuzz_dispatch = normalized_bytes(FUZZ_DISPATCH)
        if actual_output != generated_bytes:
            print("generated serializer is stale", file=sys.stderr)
            return 1
        if actual_manifest != manifest_text.encode("utf-8"):
            print("generated serializer manifest is stale", file=sys.stderr)
            return 1
        if actual_fuzz_dispatch != fuzz_dispatch_bytes:
            print("generated fuzz dispatcher is stale", file=sys.stderr)
            return 1
        return 0

    OUTPUT.write_bytes(generated_bytes)
    FUZZ_DISPATCH.write_bytes(fuzz_dispatch_bytes)
    MANIFEST.write_text(manifest_text, encoding="utf-8", newline="\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
