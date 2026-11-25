# R8 rules for release builds. Libraries bring their own rules (MapLibre, Compose, kotlinx
# serialization); only what they miss goes here, each with its reason.

# Bouncy Castle (update feed signature, direct builds): the app uses only its Ed25519 classes. Other
# parts of the jar refer to JNDI (LDAP certificate stores), which Android does not have.
-dontwarn javax.naming.**
