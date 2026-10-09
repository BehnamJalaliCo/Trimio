pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        // Cafe Bazaar's billing library (Poolakey) is published only on JitPack.
        maven("https://jitpack.io") { mavenContent { includeGroup("com.github.cafebazaar.Poolakey") } }
        // Toolchains the Kotlin/Wasm web build downloads (Node.js, Yarn, Binaryen), asked only here.
        fun toolchain(url: String, layout: String, group: String, module: String) = exclusiveContent {
            forRepository {
                ivy(url) {
                    patternLayout { artifact(layout) }
                    metadataSources { artifact() }
                }
            }
            filter { includeModule(group, module) }
        }
        toolchain("https://nodejs.org/dist", "v[revision]/[artifact](-v[revision]-[classifier]).[ext]", "org.nodejs", "node")
        toolchain("https://github.com/yarnpkg/yarn/releases/download", "v[revision]/[artifact](-v[revision]).[ext]", "com.yarnpkg", "yarn")
        toolchain(
            "https://github.com/WebAssembly/binaryen/releases/download",
            "version_[revision]/[artifact]-version_[revision]-[classifier].[ext]", "com.github.webassembly", "binaryen",
        )
    }
}

rootProject.name = "Trimio"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(":androidApp")
include(":shared")
include(":webApp")
include(":core:model")
include(":core:pipeline")
include(":core:designsystem")
include(":core:brand")
include(":core:data")
include(":core:api")
include(":server")
include(":feature:stream")
include(":feature:studio")
include(":feature:create")
include(":feature:editor")
include(":feature:settings")
include(":feature:gallery")
include(":engine:media")
include(":engine:audio")
include(":engine:asr")
include(":engine:models")
include(":engine:render")
include(":engine:styles")
include(":engine:assets")
include(":engine:llm")
include(":engine:director")
