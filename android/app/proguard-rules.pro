# Add project-specific ProGuard rules here.
# Pocket Realm ships no reflection-heavy third-party libraries at this stage;
# native realm symbols are loaded by System.loadLibrary and are not renamed.
-keepclassmembers class com.pocketrealm.** { *; }
