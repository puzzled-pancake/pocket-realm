from __future__ import annotations

import hashlib
import json
import re
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "native/xserver-winlator/cpp/vortekrenderer/generated/request_handler_upstream_ca3d735.c"
OUTPUT = ROOT / "native/xserver-winlator/cpp/vortekrenderer/src/request_handler.c"
MANIFEST = ROOT / "native/xserver-winlator/cpp/vortekrenderer/generated/request_handler_manifest.json"
TOOL = ROOT / "tools/vortek_request_handler_hardener.py"


def test_request_handler_generation_is_deterministic() -> None:
    subprocess.run([sys.executable, str(TOOL), "--check"], cwd=ROOT, check=True)


def test_pin_manifest_and_residuals() -> None:
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    source = SOURCE.read_bytes().replace(b"\r\n", b"\n")
    assert hashlib.sha256(source).hexdigest() == manifest["source_normalized_sha256"]
    assert manifest["source_normalized_sha256"] == (
        "96050e259454c406ab26cb11a77aa0a4b0d4c019559617aecb5445adc502e019"
    )
    assert manifest["wire_format_changed"] is False
    assert manifest["authority_gate_default_closed"] is True
    assert manifest["active_raw_root_handle_conversions"] == 0
    assert manifest["active_raw_handle_output_serializations"] == 0
    assert manifest["legacy_raw_root_handle_sites"] == {}
    assert manifest["legacy_raw_handle_output_sites"] == {}
    assert manifest["metrics"]["decoder_calls"] == 305
    assert manifest["metrics"]["preflight_commands"] == 112
    assert manifest["metrics"]["root_handle_conversions_replaced"] == 343
    assert manifest["metrics"]["handle_outputs_published"] == 30
    assert manifest["metrics"]["checked_response_sends"] == 102
    assert manifest["residuals"] == {
        "ignored_decoder_returns": 0,
        "raw_char_decoder_calls": 0,
        "request_vlas": 0,
        "unchecked_request_calloc": 0,
        "unchecked_request_vt_alloc": 0,
        "unchecked_response_sends": 0,
    }


def test_decode_and_batch_side_effect_ordering() -> None:
    text = OUTPUT.read_text(encoding="utf-8")
    assert "static _Thread_local VtRequestDecode* vt_active_batch_request;" in text
    assert "vt_request_preflight_command" in text
    assert text.count("VT_REQUEST_DECODE(") >= 303
    assert not re.search(r"(?m)^\s*vt_unserialize_\w+\(", text)
    assert "VkObject_fromId(" not in text
    assert (text.count("VT_REQUEST_HANDLE(") +
            text.count("VT_REQUEST_RETIRED_HANDLE(")) == 343
    assert "VT_REQUEST_PUBLISH(" in text
    assert "VT_REQUEST_RETIRED_HANDLE(" in text
    assert "vt_request_authority_lookup" in text
    assert "vt_request_seed_handle_scope" in text
    end = text[text.index("void vt_handle_vkEndCommandBuffer"):]
    end = end[:end.index("void vt_handle_vkResetCommandBuffer")]
    assert end.index("vt_request_preflight_command") < end.index("getHandleRequestFunc((short)requestCode)(context)")
    assert end.index("getHandleRequestFunc((short)requestCode)(context)") < end.index("vulkanWrapper.vkEndCommandBuffer")


def test_no_request_derived_stack_arrays_or_raw_allocators() -> None:
    text = OUTPUT.read_text(encoding="utf-8")
    assert "calloc(" not in text
    assert "vt_alloc(&context->memoryPool" not in text
    assert not re.search(r"(?m)^\s+[A-Za-z_]\w*(?:\s+\w+)*\s+\w+\[[A-Za-z_]", text)


def test_all_count_array_queries_separate_capacity_and_actual() -> None:
    text = OUTPUT.read_text(encoding="utf-8")
    names = [
        "vkEnumeratePhysicalDevices",
        "vkGetPhysicalDeviceQueueFamilyProperties",
        "vkEnumerateInstanceExtensionProperties",
        "vkEnumerateDeviceExtensionProperties",
        "vkGetImageSparseMemoryRequirements",
        "vkGetPhysicalDeviceSparseImageFormatProperties",
        "vkGetSwapchainImagesKHR",
        "vkGetPhysicalDeviceQueueFamilyProperties2",
        "vkGetPhysicalDeviceSparseImageFormatProperties2",
        "vkEnumeratePhysicalDeviceGroups",
        "vkGetImageSparseMemoryRequirements2",
        "vkGetDeviceImageSparseMemoryRequirements",
        "vkGetPhysicalDeviceCalibrateableTimeDomainsKHR",
    ]
    for name in names:
        start = text.index(f"void vt_handle_{name}")
        end = text.index("\nvoid vt_handle_", start + 1)
        body = text[start:end]
        assert "guestCapacity" in body, name
        assert ("serverActual" in body or "exposedExtensionCount" in body), name
        assert "vt_request_query_copy_count" in body, name


def test_outputs_and_implicit_lifetimes_remain_authoritative() -> None:
    text = OUTPUT.read_text(encoding="utf-8")
    assert "VT_REQUEST_SEND(result, &value, sizeof(value));" in text
    assert "VT_REQUEST_SEND(result, &semaphore" not in text
    assert "vt_send(context->clientRing" not in text
    assert text.count("vt_request_publish_vulkan_batch(") >= 6
    assert "vkFreeCommandBuffers(device, allocateInfo.commandPool" in text
    assert "vkFreeDescriptorSets(device, allocateInfo.descriptorPool" in text
    assert text.count("vt_request_tombstone_children(") >= 6
    assert "descriptorPoolToken" in text and "commandPoolToken" in text
    assert text.count("VT_REQUEST_PUBLISH_RESULT(result,") == 23
    assert len(re.findall(r"(?m)^\s*VT_REQUEST_PUBLISH\(", text)) == 2
    rollback = text[text.index("void vt_request_rollback_output("):]
    rollback = rollback[:rollback.index("\nbool vt_request_publish_vulkan_batch(")]
    assert "hostDeviceValue" in rollback
    assert "deviceOwner" not in rollback
    assert "context->nullDescriptorEnabled = false;" in rollback
    assert "TextureDecoder_destroyImage" in rollback
    assert "vkDestroyDebugReportCallback" in rollback
    assert "if (hostValue == 0) return false;" in text
    assert "tombstone_device_owned" not in text
    assert "tombstone_instance_owned" not in text

    destroy_device = text[text.index("void vt_handle_vkDestroyDevice"):]
    destroy_device = destroy_device[:destroy_device.index("\nvoid vt_handle_")]
    assert "VkContext_beginDeviceRetirement" in destroy_device
    assert "VORTEK_HANDLE_DRAIN_DEVICE" in destroy_device
    assert "VkContext_reclaimAuthority" in destroy_device
    assert "VT_REQUEST_RETIRED_HANDLE" not in destroy_device
    assert "vulkanWrapper.vkDestroyDevice" not in destroy_device

    destroy_instance = text[text.index("void vt_handle_vkDestroyInstance"):]
    destroy_instance = destroy_instance[:destroy_instance.index("\nvoid vt_handle_")]
    assert "VkContext_beginInstanceRetirement" in destroy_instance
    assert "VORTEK_HANDLE_DRAIN_INSTANCE" in destroy_instance
    assert "VkContext_reclaimAuthority" in destroy_instance
    assert "VT_REQUEST_RETIRED_HANDLE" not in destroy_instance
    assert "vulkanWrapper.vkDestroyInstance" not in destroy_instance
    assert destroy_instance.index("VkContext_reclaimAuthority") < destroy_instance.index(
        "VkContext_releaseWindowInstanceAuthority"
    )


def test_count_only_and_surface_queries_publish_only_returned_elements() -> None:
    text = OUTPUT.read_text(encoding="utf-8")

    physical = text[text.index("void vt_handle_vkEnumeratePhysicalDevices"):]
    physical = physical[:physical.index("\nvoid vt_handle_")]
    assert "physicalDeviceCount = guestCapacity == 0 ? serverActual" in physical
    assert "publishedCount = physicalDevices" in physical
    assert "sizeof(*physicalDevices), publishedCount, instanceId" in physical

    groups = text[text.index("void vt_handle_vkEnumeratePhysicalDeviceGroups"):]
    groups = groups[:groups.index("\nvoid vt_handle_")]
    assert "returnedGroupCount = guestCapacity > 0" in groups
    assert "publishedGroupCount =" in groups
    assert "result >= 0 && physicalDeviceGroupProperties" in groups
    assert groups.count("group < publishedGroupCount") == 3
    assert "group < physicalDeviceGroupCount" not in groups

    swapchain = text[text.index("void vt_handle_vkGetSwapchainImagesKHR"):]
    swapchain = swapchain[:swapchain.index("\nvoid vt_handle_")]
    assert "returnedCount = guestCapacity > 0" in swapchain
    assert "returnedCount, _vt_request.state->instance_owner" in swapchain

    for name in (
        "vkGetPhysicalDeviceSurfaceFormatsKHR",
        "vkGetPhysicalDeviceSurfacePresentModesKHR",
    ):
        body = text[text.index(f"void vt_handle_{name}"):]
        body = body[:body.index("\nvoid vt_handle_")]
        assert "guestCapacity" in body
        assert "serverActual" in body
        assert "vt_request_query_copy_count" in body
        assert "vt_request_query_result" in body


def test_failure_publication_free_descriptor_and_decode_bypass_contracts() -> None:
    text = OUTPUT.read_text(encoding="utf-8")
    shader = (ROOT / "native/xserver-winlator/cpp/vortekrenderer/src/shader_inspector.c").read_text(
        encoding="utf-8"
    )

    free_sets = text[text.index("void vt_handle_vkFreeDescriptorSets"):]
    free_sets = free_sets[:free_sets.index("\nvoid vt_handle_")]
    assert free_sets.index("vt_request_validate_batch") < free_sets.index(
        "vulkanWrapper.vkFreeDescriptorSets"
    )
    assert free_sets.index("vulkanWrapper.vkFreeDescriptorSets") < free_sets.index(
        "vt_request_tombstone_batch"
    )
    assert "if (result == VK_SUCCESS)" in free_sets

    enumerate_version = text[text.index("void vt_handle_vkEnumerateInstanceVersion"):]
    enumerate_version = enumerate_version[:enumerate_version.index("\nvoid vt_handle_")]
    assert "VT_REQUEST_BEGIN(context);" in enumerate_version
    assert "VT_REQUEST_DECODE(vt_unserialize_vkEnumerateInstanceVersion" in enumerate_version

    unmap = text[text.index("void vt_handle_vkUnmapMemory"):]
    unmap = unmap[:unmap.index("\nvoid vt_handle_")]
    assert "VT_REQUEST_DECODE(vt_unserialize_vkUnmapMemory" in unmap
    assert "VT_REQUEST_HANDLE(VkDevice" in unmap
    assert "VT_REQUEST_HANDLE(ResourceMemory*" in unmap

    assert "*ppModule = NULL;" in shader
    assert "if (!shaderModule->code)" in shader
    assert "return VK_ERROR_OUT_OF_HOST_MEMORY;" in shader
    assert "if (result != VK_SUCCESS) {\n            free(shaderModule);" in shader


def test_every_dispatched_handler_has_exact_decode_coverage() -> None:
    text = OUTPUT.read_text(encoding="utf-8")
    handlers = re.findall(
        r"(?ms)^void (vt_handle_\w+)\(VkContext\* context\) \{(.*?)^\}", text
    )
    assert len(handlers) == 254
    for name, body in handlers:
        assert "VT_REQUEST_BEGIN(context);" in body, name
        assert (
            "VT_REQUEST_DECODE(" in body or
            "vt_request_preflight_command(" in body
        ), name


def test_async_paths_own_decode_state_lease_and_cancellation() -> None:
    text = OUTPUT.read_text(encoding="utf-8")
    pipeline = (ROOT / "native/xserver-winlator/cpp/vortekrenderer/src/async_pipeline_creator.c").read_text(
        encoding="utf-8"
    )
    timeline = (ROOT / "native/xserver-winlator/cpp/vortekrenderer/src/timeline_semaphore.c").read_text(
        encoding="utf-8"
    )

    for name in ("vkCreateGraphicsPipelines", "vkCreateComputePipelines"):
        body = text[text.index(f"void vt_handle_{name}"):]
        body = body[:body.index("\nvoid vt_handle_")]
        assert "VT_REQUEST_DECODE(" in body
        assert "VT_REQUEST_HANDLE(VkDevice" in body
        assert "VT_REQUEST_HANDLE(VkPipelineCache" in body
        assert "AsyncPipelineCreator_create(" in body

    wait = text[text.index("void vt_handle_vkWaitSemaphores"):]
    wait = wait[:wait.index("\nvoid vt_handle_")]
    assert "VT_REQUEST_DECODE(" in wait
    assert "VT_REQUEST_HANDLE(VkDevice" in wait
    assert "TimelineSemaphore_asyncWait(" in wait

    for source in (pipeline, timeline):
        assert "MemoryPool memoryPool;" in source
        assert "VkContext_acquireDeviceLease" in source
        assert "VkContext_releaseDeviceLease" in source
        assert "ThreadPool_runWithCleanup" in source
        assert "vt_request_decode_pass_begin" in source
        assert "vt_decode_finished" in source
    assert "VkObjectAuthority_publishVulkanBatch" in pipeline
    assert "VkObjectAuthority_rollbackBatchWithLease" in pipeline
    assert "destroyPipelines" in pipeline
    assert "VORTEK_TIMELINE_WAIT_SLICE_NS" in timeline
    assert "ThreadPool_isCancellationRequested" in timeline


def test_null_descriptor_is_per_published_device() -> None:
    text = OUTPUT.read_text(encoding="utf-8")
    helper = (ROOT / "native/xserver-winlator/cpp/vortekrenderer/src/vulkan_helper.c").read_text(
        encoding="utf-8"
    )
    assert "VkObjectAuthority_setDeviceNullDescriptor" in text
    assert "deviceValue.nullDescriptorEnabled" in text
    assert "vt_decode_set_null_descriptor_enabled(request->state, false);" in text
    assert "VK_EXT_ROBUSTNESS_2_EXTENSION_NAME" in helper
