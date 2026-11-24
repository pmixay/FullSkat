// Code both apps share: the rich-text and figure renderer (with the math font), the v2 API client, the local
// copy of the user's dialogs that syncs through the server, the chat repository and the session.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.fullskat.core"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
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
    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.foundation)
    api(libs.androidx.core.ktx)
    api(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    api(libs.datastore.preferences)
    api(libs.okhttp)
    implementation(libs.androidx.exifinterface)
    api(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation("org.json:json:20260814") // the real org.json for JVM tests (android.jar only has stubs)
    testImplementation("com.squareup.okhttp3:mockwebserver:${libs.versions.okhttp.get()}")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${libs.versions.coroutines.get()}")
}
