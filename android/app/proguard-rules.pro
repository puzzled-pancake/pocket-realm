# Add project-specific ProGuard rules here.
# Pocket Realm ships no reflection-heavy third-party libraries currently;
# native realm symbols are loaded by System.loadLibrary and are not renamed.
-keepclassmembers class com.pocketrealm.** { *; }
# The internet join's uniffi bindings resolve their native entry points
# through JNA reflection (Native.register on the binding interfaces).
-keep class com.sun.jna.** { *; }
-keep class computer.iroh.** { *; }
-dontwarn java.awt.**
