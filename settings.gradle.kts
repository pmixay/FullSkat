pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
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
    }
}
rootProject.name = "FullSkat"
include(":app")
