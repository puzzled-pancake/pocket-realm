from __future__ import annotations

import hashlib
import json
import re
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "native/xserver-winlator/cpp/vortekrenderer/generated/vortek_serializer_upstream_ca3d735.h"
OUTPUT = ROOT / "native/xserver-winlator/cpp/vortekrenderer/include/vortek_serializer.h"
MANIFEST = ROOT / "native/xserver-winlator/cpp/vortekrenderer/generated/vortek_serializer_manifest.json"
HANDLE_POLICY = ROOT / "native/xserver-winlator/cpp/vortekrenderer/generated/vortek_handle_decode_policy.json"
FUZZ_DISPATCH = ROOT / "native/xserver-winlator/cpp/vortekrenderer/generated/vortek_decoder_fuzz_dispatch.inc"
TOOL = ROOT / "tools/vortek_serializer_hardener.py"


def decoder_bodies(text: str) -> str:
    start = re.compile(r"static inline bool vt_unserialize_\w+\(.*?\) \{", re.S)
    bodies: list[str] = []
    for match in start.finditer(text):
        depth = 1
        cursor = match.end()
        while depth:
            if text[cursor] == "{":
                depth += 1
            elif text[cursor] == "}":
                depth -= 1
            cursor += 1
        bodies.append(text[match.start():cursor])
    return "\n".join(bodies)


def without_decoders(text: str, return_type: str) -> str:
    start = re.compile(
        rf"static inline {return_type} vt_unserialize_\w+\(.*?\) \{{", re.S
    )
    chunks: list[str] = []
    position = 0
    while match := start.search(text, position):
        chunks.append(text[position:match.start()])
        depth = 1
        cursor = match.end()
        while depth:
            if text[cursor] == "{":
                depth += 1
            elif text[cursor] == "}":
                depth -= 1
            cursor += 1
        position = cursor
    chunks.append(text[position:])
    return "".join(chunks)


def test_generated_serializer_is_deterministic() -> None:
    subprocess.run([sys.executable, str(TOOL), "--check"], cwd=ROOT, check=True)


def test_pinned_source_and_manifest() -> None:
    source = SOURCE.read_bytes().replace(b"\r\n", b"\n")
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    assert hashlib.sha256(source).hexdigest() == manifest["source_normalized_sha256"]
    assert manifest["source_normalized_sha256"] == (
        "2761a1cad4c6812004e57c210b57507800b64b28ba33824b936d9daa37461253"
    )
    assert manifest["wire_format_changed"] is False
    policy_bytes = HANDLE_POLICY.read_bytes().replace(b"\r\n", b"\n")
    policy = json.loads(policy_bytes)
    assert manifest["handle_policy_sha256"] == hashlib.sha256(policy_bytes).hexdigest()
    assert manifest["handle_policy_sites"] == 111
    assert len(policy["sites"]) == 111
    assert sum(site["role"] == "resource_memory_device_memory"
               for site in policy["sites"].values()) == 7
    assert sum(site["role"] == "window_id"
               for site in policy["sites"].values()) == 1
    assert sum(site["role"] == "shader_module_wrapper"
               for site in policy["sites"].values()) == 1
    assert sum(site["role"] == "xwindow_swapchain_wrapper"
               for site in policy["sites"].values()) == 5
    fuzz_dispatch = FUZZ_DISPATCH.read_bytes().replace(b"\r\n", b"\n")
    assert manifest["fuzz_dispatch_sha256"] == hashlib.sha256(fuzz_dispatch).hexdigest()
    assert manifest["fuzz_decoder_targets"] == 630
    assert manifest["fuzz_request_decoder_targets"] == 254
    fuzz_text = fuzz_dispatch.decode("utf-8")
    assert len(re.findall(r"^        case \d+u: return vt_unserialize_", fuzz_text,
                          re.MULTILINE)) == 630
    assert set(map(int, re.findall(r"^        case (\d+)u:", fuzz_text,
                                   re.MULTILINE))) == set(range(630))
    assert manifest["source_metrics"] == {
        "allocations": 362,
        "data_sizes": 353,
        "decoders": 630,
        "item_sizes": 325,
        "nested_calls": 1012,
        "pnext_loops": 49,
        "presence_reads": 379,
        "raw_copies": 99,
        "scalar_reads": 2606,
    }


def test_all_decoder_reads_and_allocations_are_checked() -> None:
    text = OUTPUT.read_text(encoding="utf-8")
    bodies = decoder_bodies(text)
    assert bodies.count("static inline bool vt_unserialize_") == 630
    assert "inputBuffer" not in bodies
    assert "vt_alloc(memoryPool" not in bodies
    assert "bufferOffset +=" not in bodies
    assert "bufferOffset++]" not in bodies
    assert "static inline void vt_unserialize_" not in text
    assert bodies.count("while (pNextType != -1)") == 49
    assert bodies.count("vt_decode_pnext_terminated") == 49
    assert bodies.count("vt_decode_pnext_unknown") == 49
    assert bodies.count("VT_DECODE_CHILD_CALL") == 1012
    assert not re.search(r"if \(arrValues\) \{\s*if \(!VT_DECODE_CHILD_CALL", bodies)
    assert not re.search(r"if \(\w*(?:Count|count)\) \*\w+ = VT_DECODE_VALUE", bodies)
    assert "VkObject_fromId" not in bodies
    assert "ResourceMemory*)" not in bodies
    assert bodies.count("vt_decode_resolve_handle") == 111
    assert bodies.count("VT_DECODE_HANDLE_ROLE_RESOURCE_MEMORY_DEVICE_MEMORY") == 7
    assert bodies.count("VT_DECODE_HANDLE_ROLE_WINDOW_ID") == 1
    assert bodies.count("VT_DECODE_HANDLE_ROLE_SHADER_MODULE_WRAPPER") == 1
    assert bodies.count("VT_DECODE_HANDLE_ROLE_XWINDOW_SWAPCHAIN_WRAPPER") == 5
    assert bodies.count("VT_DECODE_NULL_DESCRIPTOR_FEATURE") == 4
    assert bodies.count("VT_DECODE_NULL_VULKAN") == 27
    assert bodies.count("VT_DECODE_NULL_NEVER") == 80


def test_serialization_half_is_byte_compatible() -> None:
    source = SOURCE.read_text(encoding="utf-8").replace("\r\n", "\n")
    output = OUTPUT.read_text(encoding="utf-8").replace("\r\n", "\n")
    source_without_decoders = without_decoders(source, "void")
    output_without_decoders = without_decoders(output, "bool").replace(
        '#include "vortek_decode.h"\n', ""
    )
    assert output_without_decoders == source_without_decoders


def test_async_payload_array_counts_are_semantically_correlated() -> None:
    text = OUTPUT.read_text(encoding="utf-8")
    assert text.count(
        "if ((uint64_t)vtDecodedCreateInfoCount != (uint64_t)dataSize4)"
    ) == 2
    assert text.count(
        "if ((uint64_t)vtDecodedCreateInfoCount != (uint64_t)dataSize6)"
    ) == 2
    assert (
        "if ((uint64_t)val->semaphoreCount != (uint64_t)dataSize6)"
        in text
    )
