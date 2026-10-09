plugins {
    alias(libs.plugins.trimio.kmp.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.engine.models)
            api(projects.engine.styles)
            api(libs.kotlinx.serialization.json)
            api(libs.kotlinx.io.core)
            api(libs.ktor.client.core)
        }
        jvmTest.dependencies {
            implementation(libs.ktor.client.mock)
            implementation(libs.cryptography.core)
            implementation(libs.cryptography.provider.optimal)
        }
    }
}
