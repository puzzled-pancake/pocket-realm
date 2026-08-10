from __future__ import annotations

import hashlib
import importlib.util
import json
import unittest
from pathlib import Path

from tools import capture_rp6


ROOT = Path(__file__).resolve().parents[1]


class FakeDiscoveryClient:
    def __init__(self, devices: str, models: dict[str, str]):
        self.devices = devices
        self.models = models

    def host(self, *args: str) -> str:
        self.assert_devices(args)
        return self.devices

    def shell(self, serial: str, command: str, *, timeout: int | None = None) -> str:
        if command != "getprop ro.product.model":
            raise AssertionError(command)
        return self.models[serial]

    @staticmethod
    def assert_devices(args: tuple[str, ...]) -> None:
        if args != ("devices",):
            raise AssertionError(args)


class FakePreflightClient:
    def __init__(self, values: dict[str, str]):
        self.values = values

    def shell(self, serial: str, command: str, *, timeout: int | None = None) -> str:
        if serial != "transient-target":
            raise AssertionError("unexpected transient serial")
        if command not in self.values:
            raise AssertionError(f"capture proceeded past expected preflight: {command}")
        return self.values[command]


class DiscoveryTests(unittest.TestCase):
    def test_discovers_exact_model_without_using_listing_model_hint(self) -> None:
        client = FakeDiscoveryClient(
            "List of devices attached\nwireless-id\tdevice\nemulator-id\tdevice\n",
            {
                "wireless-id": "Retroid Pocket 6",
                "emulator-id": "Android SDK built for x86_64",
            },
        )
        target = capture_rp6.discover_target(client, "Retroid Pocket 6")
        self.assertEqual("wireless-id", target.serial)
        self.assertEqual("Retroid Pocket 6", target.model)

    def test_refuses_wrong_model(self) -> None:
        client = FakeDiscoveryClient(
            "List of devices attached\nonly-id\tdevice\n",
            {"only-id": "Android SDK built for x86_64"},
        )
        with self.assertRaisesRegex(capture_rp6.CaptureError, "no online device reports model"):
            capture_rp6.discover_target(client, "Retroid Pocket 6")

    def test_refuses_ambiguous_matching_devices(self) -> None:
        client = FakeDiscoveryClient(
            "List of devices attached\nfirst\tdevice\nsecond\tdevice\n",
            {"first": "Retroid Pocket 6", "second": "Retroid Pocket 6"},
        )
        with self.assertRaisesRegex(capture_rp6.CaptureError, "multiple online devices"):
            capture_rp6.discover_target(client, "Retroid Pocket 6")

    def test_refuses_wrong_primary_abi_before_collecting_evidence(self) -> None:
        client = FakePreflightClient(
            {
                "getprop ro.product.model": "Retroid Pocket 6",
                "getprop ro.product.cpu.abi": "x86_64",
                "getprop ro.product.cpu.abilist": "x86_64,x86",
                "getprop ro.product.cpu.abilist32": "x86",
                "getprop ro.product.cpu.abilist64": "x86_64",
            }
        )
        target = capture_rp6.AdbTarget("transient-target", "Retroid Pocket 6")
        with self.assertRaisesRegex(capture_rp6.CaptureError, "wrong device ABI"):
            capture_rp6.capture(
                client,
                target,
                expected_model="Retroid Pocket 6",
                expected_abi="arm64-v8a",
                expected_page_size=4096,
            )

    def test_refuses_wrong_page_size_before_collecting_evidence(self) -> None:
        client = FakePreflightClient(
            {
                "getprop ro.product.model": "Retroid Pocket 6",
                "getprop ro.product.cpu.abi": "arm64-v8a",
                "getprop ro.product.cpu.abilist": "arm64-v8a,armeabi-v7a,armeabi",
                "getprop ro.product.cpu.abilist32": "armeabi-v7a,armeabi",
                "getprop ro.product.cpu.abilist64": "arm64-v8a",
                "getconf PAGE_SIZE": "16384",
            }
        )
        target = capture_rp6.AdbTarget("transient-target", "Retroid Pocket 6")
        with self.assertRaisesRegex(capture_rp6.CaptureError, "wrong page size"):
            capture_rp6.capture(
                client,
                target,
                expected_model="Retroid Pocket 6",
                expected_abi="arm64-v8a",
                expected_page_size=4096,
            )


class ParserTests(unittest.TestCase):
    INPUT_DUMP = """
Event Hub State:
  Devices:
    7: Retroid Pocket Controller
      Classes: KEYBOARD | GAMEPAD | JOYSTICK | VIBRATOR | EXTERNAL
      Path: /dev/input/event7
      Enabled: true
      Descriptor: dc75afea56e3c3a269b97967aa26b8c93c0bd3fb
      Location:
      ControllerNumber: 1
      UniqueId:
      Identifier: bus=0x0003, vendor=0x2022, product=0x3001, version=0x0000
  Unattached video devices:
    <none>

Input Reader State (Nums of device: 1):
  Device 8: Retroid Pocket Controller
    IsExternal: true
    Sources: KEYBOARD | GAMEPAD | JOYSTICK
    KeyboardType: 1
    ControllerNum: 1
    Motion Ranges:
      X: source=JOYSTICK, min=-1.000, max=1.000, flat=0.001, fuzz=0.000, resolution=0.000
"""

    GETEVENT_DUMP = """
add device 1: /dev/input/event7
  name:     "Retroid Pocket Controller"
  events:
    KEY (0001): BTN_GAMEPAD           BTN_EAST              BTN_START
    ABS (0003): ABS_X                 : value 0, min -32767, max 32767, fuzz 0, flat 15, resolution 0
                ABS_Y                 : value 0, min -32767, max 32767, fuzz 0, flat 15, resolution 0
  input props:
    <none>
"""

    def test_combines_android_identity_sources_buttons_and_dead_zones(self) -> None:
        devices = capture_rp6._merge_input_devices(self.INPUT_DUMP, self.GETEVENT_DUMP)
        self.assertEqual(1, len(devices))
        controller = devices[0]
        self.assertEqual("dc75afea56e3c3a269b97967aa26b8c93c0bd3fb", controller["descriptor_sha1"])
        self.assertEqual(0x2022, controller["identifier"]["vendor"])
        self.assertEqual(["KEYBOARD", "GAMEPAD", "JOYSTICK"], controller["sources"])
        self.assertEqual(0.001, controller["motion_ranges"][0]["flat"])
        linux = controller["linux_capabilities"]
        self.assertIn("BTN_START", linux["event_codes"]["KEY"])
        self.assertEqual(15, linux["absolute_axes"][0]["flat"])

    def test_parses_thermal_and_battery_snapshots(self) -> None:
        thermal = capture_rp6._parse_thermal_sysfs(
            "thermal_zone0|battery|25800\nthermal_zone2|gpu|41000\ninvalid|x|3"
        )
        self.assertEqual(25.8, thermal[0]["value_celsius"])
        battery = capture_rp6._parse_battery(
            "Current Battery Service state:\n  USB powered: true\n  level: 85\n  temperature: 203\n"
        )
        self.assertTrue(battery["usb_powered"])
        self.assertEqual(20.3, battery["temperature_celsius"])

    def test_redacts_transport_and_contact_identifiers(self) -> None:
        serial = "adb-secret._adb-tls-connect._tcp"
        value = {
            "serial": serial,
            "name": "pad AA:BB:CC:DD:EE:FF at 192.168.1.4 user@example.com",
        }
        redacted = capture_rp6._sanitize(value, serial)
        serialized = json.dumps(redacted)
        self.assertNotIn(serial, serialized)
        self.assertNotIn("AA:BB:CC:DD:EE:FF", serialized)
        self.assertNotIn("192.168.1.4", serialized)
        self.assertNotIn("user@example.com", serialized)


@unittest.skipUnless(importlib.util.find_spec("jsonschema"), "jsonschema is not installed")
class CheckedInRecordTests(unittest.TestCase):
    def test_schema_accepts_template_and_live_record(self) -> None:
        import jsonschema

        schema = json.loads((ROOT / "tests/devices/rp6-capability.schema.json").read_text(encoding="utf-8"))
        validator = jsonschema.Draft202012Validator(schema)
        validator.check_schema(schema)
        for relative in (
            "tests/devices/rp6-capability.template.json",
            "tests/devices/retroid-pocket-6/capability.json",
        ):
            record = json.loads((ROOT / relative).read_text(encoding="utf-8"))
            errors = sorted(validator.iter_errors(record), key=lambda error: list(error.path))
            self.assertEqual([], errors, "\n".join(error.message for error in errors))

    def test_live_record_has_current_tool_hash_and_no_acceptance_claim(self) -> None:
        record_path = ROOT / "tests/devices/retroid-pocket-6/capability.json"
        record = json.loads(record_path.read_text(encoding="utf-8"))
        tool_hash = hashlib.sha256((ROOT / "tools/capture_rp6.py").read_bytes()).hexdigest()
        self.assertEqual(tool_hash, record["capture"]["tool_sha256"])
        self.assertEqual("not_evaluated", record["o14_acceptance"])
        self.assertFalse(record["capture"]["redaction"]["serial_persisted"])


if __name__ == "__main__":
    unittest.main()
