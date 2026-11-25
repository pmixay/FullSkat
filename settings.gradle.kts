pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        // Google's mirror of Maven Central first: Maven Central rate-limits shared CI runners (HTTP 429), which
        // failed builds while downloading large test artifacts. Maven Central itself stays as the fallback.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Google's mirror of Maven Central first: Maven Central rate-limits shared CI runners (HTTP 429), which
        // failed builds while downloading large test artifacts. Maven Central itself stays as the fallback.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        // sherpa-onnx (on-device speech recognition) is published only as an AAR on GitHub releases.
        exclusiveContent {
            forRepository {
                ivy("https://github.com/k2-fsa/sherpa-onnx/releases/download") {
                    patternLayout { artifact("v[revision]/[module]-[revision].[ext]") }
                    metadataSources { artifact() }
                }
            }
            filter { includeGroup("com.k2fsa.sherpa") }
        }
        // libadb-android and its SPAKE2 library (the phone installs the watch app over wireless debugging) are only on
        // JitPack; nothing else is fetched from there.
        exclusiveContent {
            forRepository { maven("https://jitpack.io") }
            filter { includeGroupByRegex("com\\.github\\.MuntashirAkon(\\..+)?") }
        }
    }
    components {
        // BouncyCastle's modules ask for each other by version range (bcpkix 1.81 wants bcutil [1.81,1.82), bcutil
        // wants bcprov the same way). A range makes Gradle list versions in every repository, Maven Central included,
        // which rate-limits shared CI runners (HTTP 429). Pinned to the asking module's own version instead.
        for (module in listOf("bcpkix-jdk15to18", "bcutil-jdk15to18")) {
            withModule("org.bouncycastle:$module") {
                val own = id.version
                allVariants {
                    withDependencies {
                        filter { it.group == "org.bouncycastle" }.forEach { dep -> dep.version { require(own) } }
                    }
                }
            }
        }
    }
}
rootProject.name = "FullSkat"
include(":app")
include(":core")
include(":phone")
