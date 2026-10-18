#!/usr/bin/env bash
# W6-N2 emulator leg — E:-resident AVD creation + boot (rider 4: zero C: writes;
# AVD home, emulator home, and all AVD images under E:\pocket_realm_toolchain\avd-home).
set -uo pipefail

export ANDROID_AVD_HOME='E:\pocket_realm_toolchain\avd-home\avd'
export ANDROID_EMULATOR_HOME='E:\pocket_realm_toolchain\avd-home'
export ANDROID_SDK_HOME='E:\pocket_realm_toolchain\avd-home'
export ANDROID_USER_HOME='E:\pocket_realm_toolchain\avd-home\.android'
export ANDROID_SDK_ROOT='E:\pocket_realm_toolchain\android-sdk'
export ANDROID_HOME='E:\pocket_realm_toolchain\android-sdk'
export JAVA_HOME='E:\pocket_realm_toolchain\jdk\jdk-17.0.20.8-hotspot'
export TEMP='E:\pocket_realm_toolchain\tmp' TMP='E:\pocket_realm_toolchain\tmp' TMPDIR='E:\pocket_realm_toolchain\tmp'

SDK=/e/pocket_realm_toolchain/android-sdk
mkdir -p "$ANDROID_AVD_HOME"

if [ ! -d "/e/pocket_realm_toolchain/avd-home/avd/N2Spike.avd" ]; then
  echo "creating E:-resident AVD N2Spike (system-images;android-35;default;x86_64)"
  echo no | "$SDK/cmdline-tools/latest/bin/avdmanager.bat" create avd \
    --name N2Spike --package "system-images;android-35;default;x86_64" \
    --device pixel_6 --force 2>&1 | tail -3
else
  echo "N2Spike AVD already exists on E:"
fi
ls /e/pocket_realm_toolchain/avd-home/avd/ 2>/dev/null

echo "booting emulator (headless, no snapshots)"
"$SDK/emulator/emulator.exe" -avd N2Spike \
  -no-window -no-audio -no-boot-anim -no-snapshot \
  -gpu swiftshader_indirect -memory 3072 \
  2>&1 | grep -viE "^$" &
EMU_PID=$!

ADB="$SDK/platform-tools/adb.exe"
"$ADB" wait-for-device
echo "device appeared; waiting for sys.boot_completed"
for i in $(seq 1 90); do
  BOOT=$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
  if [ "$BOOT" = "1" ]; then echo "BOOT COMPLETED after ~$((i * 5))s"; break; fi
  sleep 5
done
"$ADB" devices
echo "emulator ready; keeping process alive for the spike leg"
wait $EMU_PID
