import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

/** Trimio on the web (Compose for Wasm). `./gradlew :webApp:wasmJsBrowserDistribution` builds the site. */
kotlin {
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        outputModuleName.set("trimio")
        browser {
            commonWebpackConfig { outputFileName = "trimio.js" }
        }
        binaries.executable()
    }

    sourceSets {
        wasmJsMain.dependencies {
            implementation(projects.shared)
            implementation(libs.compose.runtime)
            implementation(libs.compose.ui)
            implementation(libs.kotlinx.browser)
            implementation(libs.ktor.client.js)
        }
    }
}
