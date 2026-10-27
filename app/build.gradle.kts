import java.net.URI
import java.security.MessageDigest
import java.util.Properties
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// On-device Russian speech recognition: sherpa-onnx runtime (AAR, see settings.gradle.kts) plus the
// streaming Zipformer "small-ru-vosk" model (int8, ~23 MB). The model is downloaded at build time,
// verified and packed into the APK assets (asr/ru/), so it is not kept in git.
val asrModelUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-small-ru-vosk-int8-2025-08-16.tar.bz2"
val asrModelSha256 = "6ba68a01ff3c5445aaf2d61e9b97b026f1149dcc9049d11af3f44f55176341d8"
val asrModelFiles = listOf("encoder.int8.onnx", "decoder.onnx", "joiner.int8.onnx", "tokens.txt")

abstract class FetchAsrModel : DefaultTask() {
    @get:Input abstract val url: Property<String>
    @get:Input abstract val sha256: Property<String>
    @get:Input abstract val files: ListProperty<String>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty
    @get:Inject abstract val archives: ArchiveOperations
    @get:Inject abstract val fileSystem: FileSystemOperations

    @TaskAction
    fun fetch() {
        val archive = temporaryDir.resolve("model.tar.bz2")
        fun digest() = MessageDigest.getInstance("SHA-256").digest(archive.readBytes()).joinToString("") { "%02x".format(it) }
        if (!archive.exists() || digest() != sha256.get()) {
            URI(url.get()).toURL().openStream().use { input -> archive.outputStream().use { input.copyTo(it) } }
            check(digest() == sha256.get()) { "Checksum mismatch for ${url.get()}" }
        }
        val wanted = files.get()
        fileSystem.copy {
            from(archives.tarTree(archives.bzip2(archive))) {
                include(wanted.map { "*/$it" })
                eachFile { path = name }
            }
            includeEmptyDirs = false
            into(outputDir.get().asFile.resolve("asr/ru"))
        }
    }
}

// Release signing: uses a keystore from environment variables (CI secrets) when present,
// otherwise falls back to the debug keystore so every build produces an installable APK.
val releaseKeystore = System.getenv("FULLSKAT_KEYSTORE_FILE")?.let { file(it) }?.takeIf { it.exists() }

android {
    namespace = "com.fullskat.watch"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.fullskat.watch"
        minSdk = 33
        targetSdk = 37
        versionCode = (System.getenv("FULLSKAT_VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("FULLSKAT_VERSION_NAME") ?: "1.0.0"
        vectorDrawables.useSupportLibrary = true
        // Wear OS watches are arm; skipping x86 drops ~30 MB of native libraries.
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
        // The ONNX model is memory-mapped/read as is; compressing it only slows loading down.
        noCompress += "onnx"
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                // ./gradlew :app:testDebugUnitTest -Pscreenshots renders the screens to app/build/screenshots (see ScreenshotTest).
                if (providers.gradleProperty("screenshots").isPresent) {
                    it.systemProperty("fullskat.screenshots", "true")
                    it.systemProperty("roborazzi.test.record", "true")
                    it.maxHeapSize = "3g"
                }
            }
        }
    }

    packaging {
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/*.version", "kotlin/**", "DebugProbesKt.bin")
    }
}

val fetchAsrModel = tasks.register<FetchAsrModel>("fetchAsrModel") {
    url.set(asrModelUrl)
    sha256.set(asrModelSha256)
    files.set(asrModelFiles)
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(fetchAsrModel, FetchAsrModel::outputDir)
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.wear.compose.material3)
    implementation(libs.wear.compose.foundation)
    implementation(libs.wear.compose.navigation)
    implementation(libs.wear.tooling.preview)
    implementation(libs.wear.input)
    implementation(libs.wear.tiles)
    implementation(libs.wear.protolayout)
    implementation(libs.wear.complications.datasource.ktx)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)
    implementation(libs.okhttp)
    implementation("com.k2fsa.sherpa:sherpa-onnx-static-link-onnxruntime:${libs.versions.sherpaOnnx.get()}@aar")
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.concurrent.futures)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.test:core:1.8.0-alpha01")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.77.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.77.0")
}
