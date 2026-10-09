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
    }
}

rootProject.name = "Trimio"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(":androidApp")
include(":shared")
include(":core:model")
include(":core:pipeline")
include(":core:designsystem")
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
