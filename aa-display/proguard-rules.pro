-keep class io.github.nitsuya.aa.display.** { *; }
# BouncyCastle Ed25519/X25519 used by AirPlay pair-setup/verify.
-keep class org.bouncycastle.crypto.params.Ed25519** { *; }
-keep class org.bouncycastle.crypto.params.X25519** { *; }
-keep class org.bouncycastle.crypto.signers.Ed25519Signer { *; }
# ViewBinding bases live outside the keep above; R8 rename + private-set lateinit
# breaks subclass `::baseBinding.isInitialized` (IllegalAccessError → AA crash-loop exit).
-keep class io.github.duzhaokun123.template.bases.** { *; }