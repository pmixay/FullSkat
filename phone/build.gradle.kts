// The phone app: sign-up, chat by text, voice and images, models and settings, linking the watch. Everything
// it shares with the watch (API client, synced dialogs, chat, renderer) lives in :core.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing: uses a keystore from environment variables (CI secrets) when present,
// otherwise falls back to the debug keystore so every build produces an installable APK.
val releaseKeystore = System.getenv("FULLSKAT_KEYSTORE_FILE")?.let { file(it) }?.takeIf { it.exists() }

android {
    namespace = "com.fullskat.phone"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.fullskat.phone"
        minSdk = 29
        targetSdk = 37
        versionCode = (System.getenv("FULLSKAT_VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("FULLSKAT_VERSION_NAME") ?: "1.0.0"
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("FULLSKAT_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("FULLSKAT_KEY_ALIAS")
                keyPassword = System.getenv("FULLSKAT_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (releaseKeystore != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        localeFilters += listOf("en", "ru")
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                // ./gradlew :phone:testDebugUnitTest -Pscreenshots renders the screens to phone/build/screenshots (see PhoneScreenshotTest).
                if (providers.gradleProperty("screenshots").isPresent) {
                    it.systemProperty("fullskat.screenshots", "true")
                    it.systemProperty("roborazzi.test.record", "true")
                    it.maxHeapSize = "3g"
                    // Robolectric's native-graphics runtime reaches into JDK internals (Java 17+).
                    it.jvmArgs(
                        "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
                        "--add-opens=java.base/java.lang=ALL-UNNAMED",
                        "--add-opens=java.base/java.io=ALL-UNNAMED",
                        "--add-opens=java.base/java.util=ALL-UNNAMED",
                    )
                }
            }
        }
    }

    packaging {
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/*.version", "kotlin/**", "DebugProbesKt.bin")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.androidx.exifinterface)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation(libs.junit)
    testImplementation("org.json:json:20260814") // the real org.json for JVM tests (android.jar only has stubs)
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.test:core:1.8.0-alpha01")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.77.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.77.0")
}
