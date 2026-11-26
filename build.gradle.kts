plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}

// Robolectric downloads its Android runtime jars by itself, outside Gradle and its cache, from Maven Central, which
// rate-limits shared CI runners (HTTP 429: run 59 failed so). The same Google mirror as in settings.gradle.kts.
subprojects {
    tasks.withType<Test>().configureEach {
        systemProperty("robolectric.dependency.repo.id", "google-maven-central")
        systemProperty("robolectric.dependency.repo.url", "https://maven-central.storage-download.googleapis.com/maven2")
    }
}
