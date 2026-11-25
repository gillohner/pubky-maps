# R8 rules for release builds. Libraries bring their own rules (MapLibre, Compose, kotlinx
# serialization); only what they miss goes here, each with its reason.

# Bouncy Castle (update feed signature, direct builds): the app uses only its Ed25519 classes. Other
# parts of the jar refer to JNDI (LDAP certificate stores), which Android does not have.
-dontwarn javax.naming.**

# The Rust core's UniFFI bindings call native code through JNA, which finds Java classes, fields and
# callbacks by name from native code: keep JNA and the generated bindings as they are.
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }
-keep class to.pubky.maps.core.** { *; }
# JNA's desktop helpers refer to AWT, which Android does not have.
-dontwarn java.awt.**
