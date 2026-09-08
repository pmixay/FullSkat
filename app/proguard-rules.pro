# Keep services that the system binds to by class name.
-keep class com.fullskat.watch.assistant.** { *; }
-keep class com.fullskat.watch.tile.** { *; }
-keep class com.fullskat.watch.complication.** { *; }
# OkHttp / okio ship their own consumer rules. Room generated code is kept by its consumer rules.
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
