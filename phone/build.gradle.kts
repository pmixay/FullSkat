// The phone app: sign-up, chat by text, voice and images, models and settings, linking the watch. Everything
// it shares with the watch (API client, synced dialogs, chat, renderer) lives in :core.
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The watch app travels inside the phone app: a release build carries the watch's release APK as the asset
// watch/fullskat-watch.apk (stored uncompressed), which Settings -> Install the watch app puts on the watch over
// wireless debugging. Debug builds (unit tests, screenshots) do not build the watch app; their installer says so.
abstract class BundleWatchApk : DefaultTask() {
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val apkDir: DirectoryProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty
    @get:Inject abstract val fileSystem: FileSystemOperations

    @TaskAction
    fun bundle() {
        val apks = apkDir.get().asFile.listFiles { f -> f.extension == "apk" }.orEmpty()
        check(apks.size == 1) { "Expected one watch APK in ${apkDir.get().asFile}, found ${apks.map { it.name }}" }
        fileSystem.sync {
            from(apks[0]) {
                into("watch")
                rename { "fullskat-watch.apk" }
            }
            into(outputDir)
        }
    }
}

// Wired by task path and output folder only (no access to the :app project object), so the configuration cache holds.
val bundleWatchApk = tasks.register<BundleWatchApk>("bundleWatchApk") {
    dependsOn(":app:assembleRelease")
    apkDir.set(rootProject.layout.projectDirectory.dir("app/build/outputs/apk/release"))
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
        // Conscrypt and SPAKE2 (the watch installer) ship x86 libraries too; phones are arm.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
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
        // The bundled watch APK is compressed inside already; stored as is, it is copied out without inflating.
        noCompress += "apk"
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
        // Conscrypt's TLS library is stored compressed (a smaller APK); the installer unpacks only the phone's own ABI.
        jniLibs.useLegacyPackaging = true
    }
}

// Robolectric brings the desktop Conscrypt for its own TLS; the Android one (same classes, Android-only native library)
// would shadow it in JVM tests and fail to load.
configurations.matching { it.name.endsWith("UnitTestRuntimeClasspath") }.configureEach {
    exclude(group = "org.conscrypt", module = "conscrypt-android")
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(bundleWatchApk, BundleWatchApk::outputDir)
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
    // Installing the watch app over wireless debugging: the ADB client with pairing (libadb-android, used under
    // Apache-2.0; it brings BouncyCastle and SPAKE2), Conscrypt for TLS 1.3, BouncyCastle PKIX for the client certificate.
    implementation(libs.libadb.android)
    implementation(libs.conscrypt.android)
    implementation(libs.bouncycastle.pkix)
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
