# Connected instrumentation safety

Android Gradle Plugin connected-test tasks install and may replace or uninstall
`com.pocketrealm`. On a physical device that can erase all app-private user
data. The build therefore treats target selection as a deployment boundary.

## Emulator tests

Every `connected...AndroidTest` task requires `ANDROID_SERIAL` to contain one
serial of the exact form `emulator-<port>`. Missing serials, comma-separated
serials, wireless/USB device serials, and unrecognised forms fail before AGP's
connected task action. AGP's `--serial` task option is forbidden because it
takes precedence over `ANDROID_SERIAL`.

PowerShell example:

```powershell
$env:ANDROID_SERIAL = 'emulator-5554'
.\gradlew.bat :app:connectedDebugAndroidTest -PpocketAbi=x86_64
```

## Physical RP6 qualification

There is no generic physical-device override. The sole Gradle path is the
exact root task `:app:rp6HardwareQualificationAndroidTest`. It runs only
`com.pocketrealm.client.ClientActivityManifestTest`; it cannot be used to run
the general instrumentation suite. The test itself only reads the packaged
activity contract, but AGP's install/cleanup lifecycle can still erase the
installed app's private data.

Before the first qualification, put the approved device's exact current serial
on its own line in the local file below. Blank lines and `#` comments are
ignored. This file is deliberately outside the repository and must not contain
wildcards or model names.

```text
C:\Users\<you>\.gradle\pocket-realm-hardware-qualification-serials.txt
```

After preserving any required on-device data, invoke the task with the same
exact serial and the serial-bound acknowledgement:

```powershell
$rp6Serial = '<exact-allowlisted-serial>'
$env:ANDROID_SERIAL = $rp6Serial
$dataLossAcknowledgement = "I_ACKNOWLEDGE_CONNECTED_ANDROID_TEST_MAY_WIPE_COM_POCKETREALM_DATA_ON:$rp6Serial"
.\gradlew.bat :app:rp6HardwareQualificationAndroidTest `
  -PpocketAbi=arm64-v8a `
  "-PpocketHardwareQualificationAcknowledgement=$dataLossAcknowledgement"
```

The path fails closed if the exact task name was not a root request, the ABI is
not ARM64, the serial is absent from the local allowlist, the acknowledgement
does not embed that serial exactly, or external instrumentation arguments try
to change or extend the locked test selection.
