# Pocket Realm 0.112.1

This Android patch uses `versionCode` 12.

- Launch and world-data preparation now recognize Vanilla clients imported with a differently capitalized executable name, such as `Wow.exe`. Existing imports can use the fix after updating.
- A cold start has more time to prepare the game runtime. The app still limits how long it waits for the game window once the client launches.
- When the game fails to open and the realm stays online, Home shows more specific retry guidance and points to the support bundle in Settings.

Update on ARM64 Android through **Settings → App updates**, or install the signed APK over the previous official build.
