#!/usr/bin/env python3
"""Capture a redacted Retroid Pocket 6 capability baseline through ADB.

The target is discovered by ``ro.product.model``.  ADB serials and transport
identifiers are deliberately neither accepted as stable identity nor written
to the record.  This is a capability snapshot only; it does not claim O14
acceptance.

Usage:
  python tools/capture_rp6.py
  python tools/capture_rp6.py --checkin
  python tools/capture_rp6.py --output tests/devices/local-rp6.json
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Callable, Iterable


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_OUTPUT = ROOT / "tests" / "devices" / "retroid-pocket-6" / "capability.json"
SCHEMA_ID = "pocket-realm/rp6-capability/1"
EXPECTED_MODEL = "Retroid Pocket 6"
EXPECTED_ABI = "arm64-v8a"
EXPECTED_PAGE_SIZE = 4096


class CaptureError(RuntimeError):
    """A safe, user-actionable capture failure."""


@dataclass(frozen=True)
class AdbTarget:
    """Transient selection result.  ``serial`` must never enter an artifact."""

    serial: str
    model: str


def resolve_adb(explicit: str | None = None) -> Path:
    if explicit:
        candidate = Path(explicit).expanduser()
        if candidate.is_file():
            return candidate.resolve()
        found = shutil.which(explicit)
        if found:
            return Path(found).resolve()
        raise CaptureError(f"adb executable not found: {explicit}")

    for env_name in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
        sdk_value = os.environ.get(env_name)
        if not sdk_value:
            continue
        executable = "adb.exe" if os.name == "nt" else "adb"
        candidate = Path(sdk_value) / "platform-tools" / executable
        if candidate.is_file():
            return candidate.resolve()
    found = shutil.which("adb")
    if found:
        return Path(found).resolve()
    raise CaptureError("adb not found; install platform-tools or set ANDROID_SDK_ROOT")


class AdbClient:
    def __init__(self, executable: Path, timeout_seconds: int = 30):
        self.executable = executable
        self.timeout_seconds = timeout_seconds

    def _run(self, args: list[str], *, timeout: int | None = None) -> str:
        try:
            result = subprocess.run(
                [str(self.executable), *args],
                capture_output=True,
                text=True,
                encoding="utf-8",
                errors="replace",
                timeout=timeout or self.timeout_seconds,
            )
        except subprocess.TimeoutExpired as exc:
            raise CaptureError(f"adb command timed out after {exc.timeout} seconds") from exc
        if result.returncode != 0:
            detail = first_line(result.stderr) or first_line(result.stdout) or "unknown adb error"
            transient_serial = args[args.index("-s") + 1] if "-s" in args else None
            raise CaptureError(f"adb command failed: {_redact_text(detail, transient_serial)}")
        return result.stdout.replace("\r\n", "\n").strip()

    def host(self, *args: str) -> str:
        return self._run(list(args))

    def shell(self, serial: str, command: str, *, timeout: int | None = None) -> str:
        return self._run(["-s", serial, "shell", command], timeout=timeout)


def first_line(value: str) -> str:
    return value.splitlines()[0].strip() if value else ""


def _online_serials(devices_output: str) -> list[str]:
    serials: list[str] = []
    for line in devices_output.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("List of devices"):
            continue
        fields = stripped.split()
        if len(fields) >= 2 and fields[1] == "device":
            serials.append(fields[0])
    return serials


def discover_target(client: AdbClient, expected_model: str) -> AdbTarget:
    online = _online_serials(client.host("devices"))
    if not online:
        raise CaptureError("no online ADB devices found")

    matches: list[AdbTarget] = []
    observed_models: list[str] = []
    for serial in online:
        model = first_line(client.shell(serial, "getprop ro.product.model"))
        observed_models.append(_redact_text(model) or "<empty model>")
        if model == expected_model:
            matches.append(AdbTarget(serial=serial, model=model))

    if not matches:
        observed = ", ".join(sorted(set(observed_models)))
        raise CaptureError(
            f"no online device reports model {expected_model!r}; observed models: {observed}"
        )
    if len(matches) > 1:
        raise CaptureError(
            f"multiple online devices report model {expected_model!r}; disconnect extras before capture"
        )
    return matches[0]


_MAC_RE = re.compile(r"(?i)(?<![0-9a-f])(?:[0-9a-f]{2}:){5}[0-9a-f]{2}(?![0-9a-f])")
_IPV4_RE = re.compile(
    r"(?<![0-9])(?:25[0-5]|2[0-4][0-9]|1?[0-9]{1,2})"
    r"(?:\.(?:25[0-5]|2[0-4][0-9]|1?[0-9]{1,2})){3}(?![0-9])"
)
_EMAIL_RE = re.compile(r"(?i)\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b")


def _redact_text(value: str, serial: str | None = None) -> str:
    redacted = value
    if serial:
        redacted = redacted.replace(serial, "[REDACTED_DEVICE_SERIAL]")
    redacted = _MAC_RE.sub("[REDACTED_MAC]", redacted)
    redacted = _IPV4_RE.sub("[REDACTED_IP]", redacted)
    redacted = _EMAIL_RE.sub("[REDACTED_EMAIL]", redacted)
    return redacted


def _sanitize(value: object, serial: str) -> object:
    if isinstance(value, str):
        return _redact_text(value, serial)
    if isinstance(value, list):
        return [_sanitize(item, serial) for item in value]
    if isinstance(value, dict):
        return {str(key): _sanitize(item, serial) for key, item in value.items()}
    return value


def _int(value: str, *, field: str) -> int:
    try:
        return int(value.strip(), 0)
    except ValueError as exc:
        raise CaptureError(f"device returned an invalid {field}: {value!r}") from exc


def _prop(shell: Callable[[str], str], name: str) -> str:
    return first_line(shell(f"getprop {name}"))


def _parse_meminfo(text: str) -> dict[str, int]:
    wanted = {
        "MemTotal": "total_bytes",
        "MemAvailable": "available_bytes",
        "SwapTotal": "swap_total_bytes",
        "SwapFree": "swap_free_bytes",
    }
    result: dict[str, int] = {}
    for line in text.splitlines():
        match = re.match(r"^([A-Za-z]+):\s+(\d+)\s+kB$", line.strip())
        if match and match.group(1) in wanted:
            result[wanted[match.group(1)]] = int(match.group(2)) * 1024
    if "total_bytes" not in result:
        raise CaptureError("could not parse MemTotal from /proc/meminfo")
    return result


def _parse_df(text: str, requested_path: str) -> dict[str, object]:
    lines = [line.strip() for line in text.splitlines() if line.strip()]
    if len(lines) < 2:
        raise CaptureError(f"could not parse df output for {requested_path}")
    fields = lines[-1].split()
    if len(fields) < 6 or not all(item.isdigit() for item in fields[1:4]):
        raise CaptureError(f"unexpected df output for {requested_path}: {_redact_text(lines[-1])}")
    return {
        "requested_path": requested_path,
        "filesystem": fields[0],
        "total_bytes": int(fields[1]) * 1024,
        "used_bytes": int(fields[2]) * 1024,
        "available_bytes": int(fields[3]) * 1024,
        "used_percent": fields[4],
        "mounted_on": fields[5],
        "source": "adb shell df -k",
    }


def _parse_wm_dimension(text: str, label: str) -> dict[str, object]:
    values: dict[str, object] = {}
    for line in text.splitlines():
        match = re.match(r"^(Physical|Override)\s+\w+:\s*(\d+)x(\d+)$", line.strip())
        if not match:
            continue
        key = match.group(1).lower()
        values[key] = {"width": int(match.group(2)), "height": int(match.group(3))}
    if "physical" not in values:
        raise CaptureError(f"could not parse physical {label} from wm output")
    return values


def _parse_wm_density(text: str) -> dict[str, int]:
    result: dict[str, int] = {}
    for line in text.splitlines():
        match = re.match(r"^(Physical|Override) density:\s*(\d+)$", line.strip())
        if match:
            result[match.group(1).lower()] = int(match.group(2))
    if "physical" not in result:
        raise CaptureError("could not parse physical display density from wm output")
    return result


def _parse_display_info(text: str) -> dict[str, object]:
    # dumpsys may wrap DisplayDeviceInfo across lines.  Limit parsing to the
    # first built-in screen object and never retain its uniqueId/address.
    collapsed = " ".join(line.strip() for line in text.splitlines())
    start = collapsed.find('DisplayDeviceInfo{"Built-in Screen"')
    if start < 0:
        return {"modes": []}
    section = collapsed[start:]
    next_device = section.find("DisplayDeviceInfo{", 20)
    if next_device > 0:
        section = section[:next_device]
    modes: list[dict[str, object]] = []
    seen: set[tuple[int, int, int, float]] = set()
    for match in re.finditer(
        r"id=(\d+), width=(\d+), height=(\d+), fps=([0-9.]+)", section
    ):
        key = (int(match.group(1)), int(match.group(2)), int(match.group(3)), float(match.group(4)))
        if key not in seen:
            seen.add(key)
            modes.append({"id": key[0], "width": key[1], "height": key[2], "refresh_hz": key[3]})
    result: dict[str, object] = {"modes": modes}
    scalar_patterns = {
        "current_mode_id": r"\bmodeId (\d+)",
        "default_mode_id": r"\bdefaultModeId (\d+)",
        "rotation": r"\brotation (\d+)",
    }
    for key, pattern in scalar_patterns.items():
        match = re.search(pattern, section)
        if match:
            result[key] = int(match.group(1))
    state = re.search(r"\bstate ([A-Z_]+)", section)
    if state:
        result["state"] = state.group(1)
    return result


def _parse_gles_line(text: str) -> dict[str, str]:
    line = first_line(text)
    if line.startswith("GLES:"):
        line = line[5:].strip()
    fields = [field.strip() for field in line.split(",", 2)] if line else []
    return {
        "vendor": fields[0] if len(fields) > 0 else "",
        "renderer": fields[1] if len(fields) > 1 else "",
        "version": fields[2] if len(fields) > 2 else "",
    }


def _decode_opengles_version(value: str) -> str:
    if not value.isdigit():
        return ""
    encoded = int(value)
    return f"{(encoded >> 16) & 0xffff}.{encoded & 0xffff}"


def _parse_battery(text: str) -> dict[str, object]:
    raw: dict[str, str] = {}
    for line in text.splitlines():
        match = re.match(r"^\s*([A-Za-z][A-Za-z ]+):\s*(.*?)\s*$", line)
        if match:
            raw[match.group(1).strip().lower().replace(" ", "_")] = match.group(2)

    def integer(key: str) -> int | None:
        value = raw.get(key, "")
        return int(value) if re.fullmatch(r"-?\d+", value) else None

    def boolean(key: str) -> bool | None:
        value = raw.get(key, "").lower()
        return {"true": True, "false": False}.get(value)

    temperature = integer("temperature")
    result: dict[str, object] = {
        "ac_powered": boolean("ac_powered"),
        "usb_powered": boolean("usb_powered"),
        "wireless_powered": boolean("wireless_powered"),
        "present": boolean("present"),
        "status_code": integer("status"),
        "health_code": integer("health"),
        "level_percent": integer("level"),
        "scale": integer("scale"),
        "voltage_millivolts": integer("voltage"),
        "temperature_deci_celsius": temperature,
        "technology": raw.get("technology", ""),
        "charge_counter_microamp_hours": integer("charge_counter"),
        "max_charging_current_microamps": integer("max_charging_current"),
        "max_charging_voltage_microvolts": integer("max_charging_voltage"),
    }
    if temperature is not None:
        result["temperature_celsius"] = temperature / 10.0
    return result


def _parse_thermal_service(text: str) -> dict[str, object]:
    status = re.search(r"^Thermal Status:\s*(\d+)\s*$", text, re.MULTILINE)
    hal_ready = re.search(r"^HAL Ready:\s*(true|false)\s*$", text, re.MULTILINE | re.IGNORECASE)
    temperatures: list[dict[str, object]] = []
    pattern = re.compile(
        r"Temperature\{mValue=([-+0-9.eE]+),\s*mType=(\d+),\s*"
        r"mName=([^,}]+),\s*mStatus=(\d+)\}"
    )
    for match in pattern.finditer(text):
        temperatures.append(
            {
                "value_celsius": float(match.group(1)),
                "type_code": int(match.group(2)),
                "name": match.group(3).strip(),
                "status_code": int(match.group(4)),
            }
        )
    return {
        "status_code": int(status.group(1)) if status else None,
        "hal_ready": hal_ready.group(1).lower() == "true" if hal_ready else None,
        "temperatures": temperatures,
    }


def _parse_thermal_sysfs(text: str) -> list[dict[str, object]]:
    result: list[dict[str, object]] = []
    for line in text.splitlines():
        fields = line.strip().split("|", 2)
        if len(fields) != 3 or not re.fullmatch(r"thermal_zone\d+", fields[0]):
            continue
        try:
            raw = int(fields[2])
        except ValueError:
            continue
        result.append(
            {
                "zone": fields[0],
                "type": fields[1],
                "raw_millicelsius": raw,
                "value_celsius": raw / 1000.0,
            }
        )
    return result


def _parse_zram(name: str, disksize: str, mm_stat: str) -> dict[str, object]:
    labels = [
        "original_data_size",
        "compressed_size",
        "memory_used_total",
        "memory_limit",
        "memory_max_used",
        "same_pages",
        "pages_compacted",
        "huge_pages",
        "huge_pages_since",
    ]
    values = [int(value) for value in mm_stat.split() if re.fullmatch(r"\d+", value)]
    stats = {label: values[index] for index, label in enumerate(labels) if index < len(values)}
    return {
        "name": name,
        "disksize_bytes": _int(disksize, field=f"{name} disksize"),
        "mm_stat_bytes_or_counts": stats,
    }


def _split_flags(value: str) -> list[str]:
    return [part.strip() for part in value.split("|") if part.strip()]


def _parse_event_hub_devices(text: str) -> list[dict[str, object]]:
    start = text.find("Event Hub State:")
    end = text.find("Input Reader State", start)
    if start < 0:
        return []
    section = text[start : end if end >= 0 else None]
    header = re.compile(r"^\s{4}(-?\d+):\s*(.*?)\s*$")
    devices: list[dict[str, object]] = []
    current: dict[str, object] | None = None
    for line in section.splitlines():
        match = header.match(line)
        if match:
            if current:
                devices.append(current)
            current = {"name": match.group(2)}
            continue
        if current is None:
            continue
        field = re.match(r"^\s{6}([A-Za-z]+):\s*(.*?)\s*$", line)
        if not field:
            continue
        key, value = field.group(1), field.group(2)
        if key == "Classes":
            current["classes"] = _split_flags(value)
        elif key == "Enabled":
            current["enabled"] = value.lower() == "true"
        elif key == "Descriptor" and re.fullmatch(r"[0-9a-fA-F]{40}", value):
            current["descriptor_sha1"] = value.lower()
        elif key == "ControllerNumber" and value.isdigit():
            current["controller_number"] = int(value)
        elif key == "Identifier":
            identifier = re.fullmatch(
                r"bus=0x([0-9a-fA-F]+), vendor=0x([0-9a-fA-F]+), "
                r"product=0x([0-9a-fA-F]+), version=0x([0-9a-fA-F]+)",
                value,
            )
            if identifier:
                current["identifier"] = {
                    "bus": int(identifier.group(1), 16),
                    "vendor": int(identifier.group(2), 16),
                    "product": int(identifier.group(3), 16),
                    "version": int(identifier.group(4), 16),
                }
    if current:
        devices.append(current)
    return devices


def _parse_input_reader(text: str) -> dict[str, dict[str, object]]:
    start = text.find("Input Reader State")
    if start < 0:
        return {}
    section = text[start:]
    devices: dict[str, dict[str, object]] = {}
    current: dict[str, object] | None = None
    in_motion_ranges = False
    for line in section.splitlines():
        header = re.match(r"^\s{2}Device\s+-?\d+:\s*(.*?)\s*$", line)
        if header:
            current = devices.setdefault(header.group(1), {})
            in_motion_ranges = False
            continue
        if current is None:
            continue
        if re.match(r"^\s{4}Motion Ranges:\s*$", line):
            current["motion_ranges"] = []
            in_motion_ranges = True
            continue
        if in_motion_ranges:
            motion = re.match(
                r"^\s{6}([A-Z0-9_]+): source=([^,]+), min=([-+0-9.]+), "
                r"max=([-+0-9.]+), flat=([-+0-9.]+), fuzz=([-+0-9.]+), "
                r"resolution=([-+0-9.]+)$",
                line,
            )
            if motion:
                current["motion_ranges"].append(
                    {
                        "axis": motion.group(1),
                        "source": motion.group(2),
                        "min": float(motion.group(3)),
                        "max": float(motion.group(4)),
                        "flat": float(motion.group(5)),
                        "fuzz": float(motion.group(6)),
                        "resolution": float(motion.group(7)),
                    }
                )
                continue
            if re.match(r"^\s{4}\S", line):
                in_motion_ranges = False
        field = re.match(r"^\s{4}(IsExternal|Sources|KeyboardType|ControllerNum):\s*(.*?)\s*$", line)
        if field:
            key, value = field.group(1), field.group(2)
            if key == "IsExternal":
                current["is_external"] = value.lower() == "true"
            elif key == "Sources":
                current["sources"] = _split_flags(value)
            elif key == "KeyboardType" and value.isdigit():
                current["keyboard_type"] = int(value)
            elif key == "ControllerNum" and value.isdigit():
                current["controller_number"] = int(value)
    return devices


def _parse_getevent_capabilities(text: str) -> dict[str, dict[str, object]]:
    devices: dict[str, dict[str, object]] = {}
    current: dict[str, object] | None = None
    current_event: str | None = None
    for line in text.splitlines():
        if line.startswith("add device "):
            current = None
            current_event = None
            continue
        name = re.match(r'^\s+name:\s+"(.*)"\s*$', line)
        if name:
            current = devices.setdefault(name.group(1), {"event_codes": {}, "absolute_axes": []})
            current_event = None
            continue
        if current is None:
            continue
        event = re.match(r"^\s{4}([A-Z]+)\s+\([0-9a-fA-F]+\):\s*(.*)$", line)
        if event:
            current_event = event.group(1)
            current["event_codes"].setdefault(current_event, [])
            _consume_getevent_values(current, current_event, event.group(2))
            continue
        if current_event and re.match(r"^\s{8,}\S", line):
            _consume_getevent_values(current, current_event, line.strip())
            continue
        props = re.match(r"^\s{4}(INPUT_PROP_[A-Z0-9_]+|<none>)\s*$", line)
        if props:
            current.setdefault("input_properties", [])
            if props.group(1) != "<none>":
                current["input_properties"].append(props.group(1))
    return devices


def _consume_getevent_values(device: dict[str, object], event_type: str, value: str) -> None:
    if event_type == "ABS":
        match = re.match(
            r"(ABS_[A-Z0-9_]+)\s*:\s*value\s+-?\d+,\s*min\s+(-?\d+),\s*"
            r"max\s+(-?\d+),\s*fuzz\s+(-?\d+),\s*flat\s+(-?\d+),\s*resolution\s+(-?\d+)",
            value,
        )
        if match:
            axes = device["absolute_axes"]
            if not isinstance(axes, list):
                raise TypeError(f"absolute_axes malformed: {axes!r}")
            axes.append(
                {
                    "code": match.group(1),
                    "min": int(match.group(2)),
                    "max": int(match.group(3)),
                    "fuzz": int(match.group(4)),
                    "flat": int(match.group(5)),
                    "resolution": int(match.group(6)),
                }
            )
            codes = device["event_codes"]
            if not isinstance(codes, dict):
                raise TypeError(f"event_codes malformed: {codes!r}")
            codes[event_type].append(match.group(1))
        return
    codes = device["event_codes"]
    if not isinstance(codes, dict):
        raise TypeError(f"event_codes malformed: {codes!r}")
    parsed = re.findall(r"\b(?:KEY|BTN|REL|SW|MSC|LED|SND|REP|FF)_[A-Z0-9_]+\b", value)
    codes[event_type].extend(parsed)


def _merge_input_devices(dumpsys: str, getevent: str) -> list[dict[str, object]]:
    devices = _parse_event_hub_devices(dumpsys)
    reader = _parse_input_reader(dumpsys)
    capabilities = _parse_getevent_capabilities(getevent)
    for device in devices:
        name = str(device.get("name", ""))
        device.update(reader.get(name, {}))
        if name in capabilities:
            device["linux_capabilities"] = capabilities[name]
    known = {str(device.get("name", "")) for device in devices}
    for name, value in capabilities.items():
        if name not in known:
            devices.append({"name": name, "linux_capabilities": value})
    return sorted(devices, key=lambda item: str(item.get("name", "")).casefold())


def _adb_version(client: AdbClient) -> str:
    for line in client.host("version").splitlines():
        if line.startswith("Android Debug Bridge version"):
            return line.rsplit(" ", 1)[-1]
    return ""


def capture(
    client: AdbClient,
    target: AdbTarget,
    *,
    expected_model: str,
    expected_abi: str,
    expected_page_size: int,
) -> dict[str, object]:
    serial = target.serial
    shell = lambda command: client.shell(serial, command)

    # Re-read identity for a time-of-check/time-of-use guard.
    model = _prop(shell, "ro.product.model")
    if model != expected_model:
        raise CaptureError(f"selected device model changed or is wrong: {model!r}")

    primary_abi = _prop(shell, "ro.product.cpu.abi")
    abilist = [item for item in _prop(shell, "ro.product.cpu.abilist").split(",") if item]
    abilist32 = [item for item in _prop(shell, "ro.product.cpu.abilist32").split(",") if item]
    abilist64 = [item for item in _prop(shell, "ro.product.cpu.abilist64").split(",") if item]
    if primary_abi != expected_abi or expected_abi not in abilist:
        raise CaptureError(
            f"wrong device ABI: expected primary {expected_abi!r}, "
            f"observed primary {primary_abi!r} with abilist {abilist!r}"
        )

    page_size = _int(first_line(shell("getconf PAGE_SIZE")), field="page size")
    if page_size != expected_page_size:
        raise CaptureError(
            f"wrong page size: expected {expected_page_size}, observed {page_size}"
        )

    sdk_text = _prop(shell, "ro.build.version.sdk")
    api_level = _int(sdk_text, field="API level")
    memory = _parse_meminfo(shell("cat /proc/meminfo"))
    storage = _parse_df(shell("df -k /data/data"), "/data/data")

    zram_devices: list[dict[str, object]] = []
    for name in shell("ls -1 /sys/block").splitlines():
        if re.fullmatch(r"zram\d+", name):
            zram_devices.append(
                _parse_zram(
                    name,
                    shell(f"cat /sys/block/{name}/disksize"),
                    shell(f"cat /sys/block/{name}/mm_stat"),
                )
            )

    wm_size = _parse_wm_dimension(shell("wm size"), "size")
    physical_size = wm_size["physical"]
    if not isinstance(physical_size, dict):
        raise TypeError(f"physical_size malformed: {physical_size!r}")
    wm_size["landscape_width"] = max(int(physical_size["width"]), int(physical_size["height"]))
    wm_size["landscape_height"] = min(int(physical_size["width"]), int(physical_size["height"]))

    gles_encoded = _prop(shell, "ro.opengles.version")
    gles = _parse_gles_line(shell("dumpsys SurfaceFlinger | grep -m 1 '^GLES:'"))
    input_dump = shell("dumpsys input")
    getevent_dump = shell("getevent -lp")

    thermal_command = (
        "for z in /sys/class/thermal/thermal_zone*; do "
        "t=$(cat \"$z/type\" 2>/dev/null); v=$(cat \"$z/temp\" 2>/dev/null); "
        "if [ -n \"$v\" ]; then n=${z##*/}; printf '%s|%s|%s\\n' \"$n\" \"$t\" \"$v\"; fi; done"
    )

    record: dict[str, object] = {
        "schema_id": SCHEMA_ID,
        "schema_version": 1,
        "record_kind": "rp6_device_capability",
        "record_state": "captured",
        "scope": "capability_baseline_only",
        "o14_acceptance": "not_evaluated",
        "captured_at_utc": datetime.now(timezone.utc).isoformat(),
        "capture": {
            "tool": "tools/capture_rp6.py",
            "tool_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
            "adb_version": _adb_version(client),
            "selection": "exact ro.product.model match across online adb devices",
            "redaction": {
                "serial_persisted": False,
                "excluded": [
                    "adb serial and transport id",
                    "Android/input unique ids and device paths",
                    "network addresses and email-like text",
                    "unrelated active-window state",
                ],
            },
        },
        "preflight": {
            "expected_model": expected_model,
            "model_match": True,
            "expected_primary_abi": expected_abi,
            "abi_match": True,
            "expected_page_size_bytes": expected_page_size,
            "page_size_match": True,
        },
        "device": {
            "manufacturer": _prop(shell, "ro.product.manufacturer"),
            "model": model,
            "product": _prop(shell, "ro.product.name"),
            "device": _prop(shell, "ro.product.device"),
            "hardware": _prop(shell, "ro.hardware"),
            "soc_manufacturer": _prop(shell, "ro.soc.manufacturer"),
            "soc_model": _prop(shell, "ro.soc.model"),
            "board_platform": _prop(shell, "ro.board.platform"),
        },
        "android": {
            "api_level": api_level,
            "release": _prop(shell, "ro.build.version.release"),
            "security_patch": _prop(shell, "ro.build.version.security_patch"),
            "build_id": _prop(shell, "ro.build.id"),
            "build_fingerprint": _prop(shell, "ro.build.fingerprint"),
            "kernel_release": first_line(shell("uname -r")),
        },
        "cpu": {
            "primary_abi": primary_abi,
            "abilist": abilist,
            "abilist32": abilist32,
            "abilist64": abilist64,
            "page_size_bytes": page_size,
            "page_size_source": "getconf PAGE_SIZE",
        },
        "memory": {**memory, "zram": zram_devices},
        "storage": {"data": storage},
        "display": {
            "size": wm_size,
            "density_dpi": _parse_wm_density(shell("wm density")),
            "built_in": _parse_display_info(shell("dumpsys display")),
        },
        "gpu": {
            "soc_model": _prop(shell, "ro.soc.model"),
            "egl_driver": _prop(shell, "ro.hardware.egl"),
            "vulkan_driver": _prop(shell, "ro.hardware.vulkan"),
            "opengles_version_property": gles_encoded,
            "opengles_version_decoded": _decode_opengles_version(gles_encoded),
            "surfaceflinger_gles": gles,
        },
        "input_devices": _merge_input_devices(input_dump, getevent_dump),
        "battery": _parse_battery(shell("dumpsys battery")),
        "thermal": {
            "service": _parse_thermal_service(shell("dumpsys thermalservice")),
            "sysfs_snapshot": _parse_thermal_sysfs(shell(thermal_command)),
        },
    }
    sanitized = _sanitize(record, serial)
    if not isinstance(sanitized, dict):
        raise TypeError(f"sanitized record malformed: {sanitized!r}")
    serialized = json.dumps(sanitized, ensure_ascii=False)
    if serial and serial in serialized:
        raise CaptureError("internal redaction failure: ADB serial remained in artifact")
    return sanitized


def _write_record(record: dict[str, object], path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(record, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", help="adb executable/path (not persisted)")
    output = parser.add_mutually_exclusive_group()
    output.add_argument(
        "--checkin",
        action="store_true",
        help="write tests/devices/retroid-pocket-6/capability.json",
    )
    output.add_argument("--output", type=Path, help="write an explicit output path")
    return parser


def main(argv: Iterable[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        client = AdbClient(resolve_adb(args.adb))
        target = discover_target(client, EXPECTED_MODEL)
        record = capture(
            client,
            target,
            expected_model=EXPECTED_MODEL,
            expected_abi=EXPECTED_ABI,
            expected_page_size=EXPECTED_PAGE_SIZE,
        )
        if args.checkin or args.output:
            path = DEFAULT_OUTPUT if args.checkin else args.output
            if path is None:
                raise ValueError("screenshot path unexpectedly missing")
            if not path.is_absolute():
                path = ROOT / path
            _write_record(record, path)
            print(f"wrote {path}")
        else:
            print(json.dumps(record, indent=2, ensure_ascii=False))
        return 0
    except CaptureError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
