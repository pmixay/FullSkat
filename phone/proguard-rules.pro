# The phone app itself uses no reflection. Room, OkHttp and AndroidX ship their own consumer rules.
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# ---- Watch installer: libadb-android, SPAKE2, Conscrypt, BouncyCastle ----

# libadb (small) reaches Conscrypt only by reflection: Class.forName("org.conscrypt.OpenSSLProvider") plus its no-arg
# constructor for the TLS 1.3 SSLContext, and Conscrypt.exportKeyingMaterial(SSLSocket, String, byte[], int) during
# pairing. Kept whole so nothing on that path is renamed or merged.
-keep class io.github.muntashirakon.adb.** { *; }

# SPAKE2 (the pairing key exchange) is a JNI library (libspake2.so): its classes and native methods keep their names.
-keep class io.github.muntashirakon.crypto.spake2.** { *; }

# Conscrypt's own consumer rules already keep org.conscrypt.** (JNI and reflection); repeated here because libadb
# depends on the class and method names above.
-keep class org.conscrypt.** { *; }
-dontwarn com.android.org.conscrypt.**
-dontwarn org.apache.harmony.xnet.provider.jsse.**
-dontwarn dalvik.system.**

# BouncyCastle is used through direct references only (libadb's pairing: AES-GCM, HKDF, SHA-256, Base64; our client
# certificate: bcpkix with the platform's JCA providers; BC is never registered as a provider), and its lightweight
# crypto, util, asn1, cert and operator packages contain no Class.forName, so R8 may shrink it. Its LDAP cert store
# classes (unused) refer to javax.naming, which Android lacks.
-dontwarn javax.naming.**
