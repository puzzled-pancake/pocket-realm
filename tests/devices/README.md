# Physical-device evidence foundations

`tools/capture_rp6.py` records a redacted Retroid Pocket 6 capability baseline.
It discovers the target by querying `ro.product.model` on every online ADB
device, so a wireless ADB serial is never used as stable identity or persisted.
The capture refuses to write when it does not find exactly one
`Retroid Pocket 6`, when the primary ABI is not `arm64-v8a`, or when the page
size is not 4096 bytes.

Run the checked-in capture from the repository root:

```powershell
python tools/capture_rp6.py --checkin
```

Use `--output <path>` for a local record or omit both output flags to print the
JSON. `--adb <path>` may select a host executable, but that path is not stored.
There is intentionally no serial option and no CLI switch that weakens the
model, ABI, or page-size gates.

The record contains Android/API/build identity, ABI lists, kernel page size,
RAM/swap/zram, `/data` filesystem capacity, physical display modes/density,
GPU/GLES identity, Android and Linux input-device capabilities, battery state,
and thermal service/sysfs snapshots. Input paths and unique IDs, ADB serials
and transport IDs, network/contact-like identifiers, and unrelated active
window state are excluded or redacted.

- `rp6-capability.schema.json` is the JSON Schema 2020-12 contract.
- `rp6-capability.template.json` is a schema-valid, explicitly non-captured
  scaffold. It is not evidence and must not be hand-edited into evidence.
- `retroid-pocket-6/capability.json` is the latest live capture made by the
  tool in this repository.

`retroid-pocket-6/arm-o04-control-probe-20260809.json` records a separate,
development-only ARM64 `pocket_lifecycle_test` run over wireless ADB. It proves
the built native control/lifecycle library executes on the RP6 for two cycles;
the deliberate world-database schema gap is classified honestly. It is not
MariaDB, translated-Wine, physical-peripheral, or O14 acceptance evidence.

`retroid-pocket-6/arm-mariadb-qualification-build-20260809.json` records the
hash-verified ARM database-only qualification APK and its converted MariaDB
artifacts. `deviceExecution` and `o14Acceptance` are explicitly
`not_evaluated`; it is the artifact to install for the next live RP6 MariaDB
run, not device execution evidence. The final wireless-ADB attempt timed out,
so the build record also records that external blocker; no emulator result is
substituted for RP6 execution.

Validate the parser, refusal gates, redaction, schema/template, checked-in live
record, and tool hash with:

```powershell
python -m unittest discover -s tests -p "test_capture_rp6.py" -v
```

These artifacts establish only the capability-recording foundation. Their
`o14_acceptance` value is always `not_evaluated`; they do not represent any O14
UX, runtime, peripheral, teardown, or overall acceptance result.
