# Backups, Diagnostics, and Support

The maintenance tools are under **Settings**, then **Setup**, then **Diagnostics and logs**.

## Storage check

The Diagnostics screen verifies the app's storage areas and reports whether the mutable world data is on internal storage. It also shows which roots exist and how much usable space remains.

If a required root is missing, avoid repeated game launches until the storage issue is understood. A missing or unwritable data area can prevent a safe start or backup.

## Create a backup

**Create named backup** asks the runtime supervisor to create a consistent snapshot. The screen follows the operation until it completes or fails.

Backups are intended for the local realm and its durable state. They are not a copy of the original user-selected client folder.

Create a backup before:

- A risky settings experiment.
- A major runtime or database update.
- Long device testing.
- Any change where restoring the current characters and world matters.

## Restore the newest backup

The restore button becomes available when the app can identify a backup. Restore is a controlled maintenance operation. The realm should not be treated as playable while restore verification is running.

Pocket Realm does not trust a restored folder merely because it can be copied. It starts the restored database, login server, and world server and waits for the world-ready state. It then closes that test cleanly. The restored data becomes active only after the full test succeeds. A failed test returns to the previous database generation.

After a restore, start the realm normally and check the account and one known character before doing more work.

## Redacted support bundle

The support bundle gathers useful diagnostic entries and a manifest while removing known secrets. It is meant to help another person investigate a failure without handing them the complete app-private data area.

It does not include the live database or managed client files. The exporter has a fixed size limit and checks that secret test markers are absent before publication.

Redaction lowers risk, but the player should still review any exported file before sharing it publicly.

## Recent log

The screen shows recent structured log entries. They can explain which phase started, what failed, and whether recovery was attempted.

When reporting a problem, include:

- What you pressed.
- What you expected.
- The exact visible message.
- Whether the realm was stopped, starting, online, saving, or recovering.
- The device model and Android version.
- A screenshot that does not expose a password.
- A support bundle if requested.

## Device capability report

The capability screen records hardware and process facts such as Android version, supported processor types, memory, storage availability, graphics support, and the app's native library location.

Its experiment buttons are for technical qualification. A normal player does not need to run them unless a tester or maintainer gives a specific instruction.
